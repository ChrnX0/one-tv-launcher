package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import br.paulo.tvlauncher.backup.Nuvem
import br.paulo.tvlauncher.backup.RetornoNuvem
import br.paulo.tvlauncher.home.BibliotecaIndice
import br.paulo.tvlauncher.home.BibliotecaUnica
import br.paulo.tvlauncher.home.IconeMidia
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.input.LeitorNomes
import br.paulo.tvlauncher.input.SegurarOk
import br.paulo.tvlauncher.network.Descoberta
import br.paulo.tvlauncher.network.FilaRede
import br.paulo.tvlauncher.panel.FotosRede
import br.paulo.tvlauncher.panel.PainelArquivos
import br.paulo.tvlauncher.panel.PainelVizinhos
import br.paulo.tvlauncher.panel.PainelWeb
import br.paulo.tvlauncher.panel.PastaLocal
import br.paulo.tvlauncher.panel.Pastas
import br.paulo.tvlauncher.panel.QrCodigo
import br.paulo.tvlauncher.panel.Sftp
import br.paulo.tvlauncher.panel.Smb
import br.paulo.tvlauncher.panel.Upnp
import br.paulo.tvlauncher.panel.WebDav
import br.paulo.tvlauncher.panel.entrar
import br.paulo.tvlauncher.player.CatalogoPasta
import br.paulo.tvlauncher.player.ContaMidia
import br.paulo.tvlauncher.player.FimDoVideo
import br.paulo.tvlauncher.player.FontesMidia
import br.paulo.tvlauncher.player.Idioma
import br.paulo.tvlauncher.player.InfoExternaCache
import br.paulo.tvlauncher.player.Jellyfin
import br.paulo.tvlauncher.player.MidiaArmazem
import br.paulo.tvlauncher.player.MidiaContas
import br.paulo.tvlauncher.player.Plex
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.player.salvar
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mídias da rede (card "Na rede" da HOME). 07/11/2026: DLNA, pasta compartilhada e Jellyfin. 10/11/2026: Plex (login
 * por código), Emby, WebDAV/Nextcloud, link de vídeo ou lista M3U, computadores com pasta compartilhada achados sozinhos,
 * e a tela inteira no sistema de design (Estilo.kt: Nunito, linha que vira pílula branca no foco, ícones de linha).
 *
 *   Na rede
 *   SEUS SERVIDORES   o que a pessoa já adicionou (OK abre; segurar OK 2 s = remover)
 *   NA REDE           o que a TV achou sozinha: DLNA (abre direto), Plex/Jellyfin/Emby (entrar), PCs e NAS (adicionar)
 *   ADICIONAR         Plex · Jellyfin · Emby · Pasta compartilhada · WebDAV · Link · Procurar de novo
 *
 * Dentro de um servidor: pastas (OK entra, VOLTAR sobe), vídeo e música tocam na VodActivity, foto abre em tela cheia.
 * Formulários e confirmações são painéis desta tela (não AlertDialog): mesmo visual do resto do launcher.
 * Processo ":player", como o resto do player.
 */
class MidiaRedeActivity : Activity() {
    /** De onde vêm as pastas de um nível. */
    private sealed class Origem {
        class Dlna(val s: Upnp.Servidor) : Origem()
        class Pasta(val c: ContaMidia) : Origem()
        class Jelly(val c: ContaMidia) : Origem()
        class PlexO(val c: ContaMidia) : Origem()
        class Dav(val c: ContaMidia) : Origem()
        class Link(val c: ContaMidia) : Origem()
        /** Pendrive ou disco USB ligado na TV (14/11/2026). */
        class Local(val raiz: java.io.File) : Origem()
    }
    private enum class Acao { PLEX, JELLYFIN, EMBY, SMB, WEBDAV, ARQUIVOS, ONEDRIVE, DROPBOX, BOX, GDRIVE, LINK, PROCURAR }
    /** Um pendrive/disco montado agora: aparece sozinho na raiz, sem cadastrar. */
    private class Volume(val nome: String, val raiz: java.io.File)
    /** "Usar estas fotos no protetor" (ideia 27): a pasta aberta agora, de uma conta SMB ou WebDAV. */
    private class FotosNoProtetor(val origem: FotosRede.Origem, val quantas: Int)
    /** "Buscar" na raiz de um Jellyfin, Emby ou Plex (ideia 29). */
    private class Buscar(val origem: Origem)
    /** Elevação 8.2: a linha que liga/desliga pôster e sinopse do TMDB para os vídeos das pastas. */
    private object LigarCatalogo
    /** Ficha de um vídeo de pasta achada no TMDB (null no mapa = procurou e não achou). */
    private class FichaPasta(val titulo: String, val texto: String, val poster: String)

    /** O que a lista mostra: um nome de grupo (não focável) ou uma linha. */
    private class Secao(val nome: String)
    private class Linha(val icone: IconeMidia.Tipo, val titulo: String, val sub: String?, val valor: String?, val alvo: Any)

    /** Um nível da navegação: o que está na lista e onde o foco estava (para voltar no mesmo lugar). */
    private class Nivel(val titulo: String, val origem: Origem?, val itens: List<Any>, var foco: Int = -1,
                        /** Elevação 8.2: o que o leitor de nomes entendeu de cada vídeo deste nível. */
                        val lidos: Map<Upnp.Entrada, LeitorNomes.Lido> = emptyMap())

    private val tela = Handler(Looper.getMainLooper())
    private val rede = Executors.newSingleThreadExecutor()
    private val pilha = ArrayList<Nivel>()
    private var ocupado = false
    private var procurando = false
    private var dlna: List<Upnp.Servidor> = emptyList()
    private var jellyAchados: List<Jellyfin.Achado> = emptyList()
    private var plexAchados: List<Plex.Achado> = emptyList()
    private var maquinas: List<Descoberta.Maquina> = emptyList()
    private val segurar = SegurarOk()
    /** Login do Plex em andamento: virar true cancela a espera pelo código. */
    private var cancelarPlex: AtomicBoolean? = null

    private lateinit var trilha: TextView
    private lateinit var titulo: TextView
    private lateinit var aviso: TextView
    private lateinit var lista: ListView
    private lateinit var dicas: FrameLayout
    private lateinit var foto: ImageView
    private lateinit var camada: FrameLayout   // painéis (formulários, confirmação, código do Plex)

    // ---- ideia 29: ficha ao lado da lista (pôster + sinopse) e a pasta que emenda o próximo arquivo
    private lateinit var ficha: LinearLayout
    private lateinit var fichaCapa: ImageView
    private lateinit var fichaTitulo: TextView
    private lateinit var fichaTexto: TextView
    private val capas = Executors.newSingleThreadExecutor()
    private val cacheCapas = android.util.LruCache<String, Bitmap>(12)
    private var capaPedida: String? = null
    private val fichasPasta = HashMap<String, FichaPasta?>()
    /** O que está tocando a partir desta tela: posição na lista do nível e o tipo (vídeo ou música). */
    private var tocandoPos = -1
    private var tocandoTipo: Upnp.Tipo? = null
    private var tocandoNivel: Nivel? = null
    private var seguidos = 0
    private var contando: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val raiz = FrameLayout(this).apply { background = Estilo.fundoTela(this@MidiaRedeActivity) }
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
            clipToPadding = false   // a lista entra 12 dp na margem (folga do foco); sem isso a margem cortava a pílula
        }
        trilha = Estilo.texto(this, Estilo.Papel.SECAO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START; visibility = View.GONE }
        titulo = Estilo.tituloTela(this, getString(R.string.rede_titulo)).apply { setPaddingRelative(0, dp(4), 0, 0) }
        aviso = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { setPaddingRelative(0, dp(Estilo.E1), 0, dp(Estilo.E2)); textSize = 15f }
        lista = ListView(this).apply {
            divider = null; dividerHeight = dp(6); isVerticalScrollBarEnabled = false; itemsCanFocus = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false; setPaddingRelative(dp(18), dp(4), dp(18), dp(4))   // a linha com foco cresce 2%: folga para não cortar
            adapter = adaptador
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ -> abrir(pos) }
            // "Segurar" que não vem do controle (acessibilidade, leitor de tela, MCP): o mesmo remover do segure OK.
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
                val conta = pilha.size == 1 && (pilha[0].itens.getOrNull(pos) as? Linha)?.alvo is ContaMidia
                if (conta) confirmarRemover(pos)
                val lido = !conta && pilha.size > 1 && CatalogoPasta.ligado(this@MidiaRedeActivity) && videoLido(pos)
                if (lido) corrigirCasamento(pos)
                conta || lido
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = pintarFoco()
                override fun onNothingSelected(p: AdapterView<*>?) = pintarFoco()
            }
            setOnFocusChangeListener { _, _ -> pintarFoco() }
        }
        dicas = FrameLayout(this).apply { setPaddingRelative(0, dp(Estilo.E2), 0, 0) }
        coluna.addView(trilha); coluna.addView(titulo); coluna.addView(aviso)
        // Lista à esquerda e, quando o item tem pôster ou sinopse (Jellyfin, Emby, Plex, DLNA com capa), a ficha à direita.
        ficha = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPaddingRelative(dp(Estilo.E2), dp(4), 0, 0) }
        fichaCapa = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true }
        fichaTitulo = Estilo.texto(this, Estilo.Papel.CORPO).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPaddingRelative(0, dp(Estilo.E1), 0, 0) }
        fichaTexto = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { maxLines = 9; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f); setPaddingRelative(0, dp(6), 0, 0) }
        ficha.addView(fichaCapa, LinearLayout.LayoutParams(dp(200), dp(300)))
        ficha.addView(fichaTitulo); ficha.addView(fichaTexto)
        val miolo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        miolo.addView(lista, LinearLayout.LayoutParams(0, -1, 1f).apply { marginStart = -dp(12); marginEnd = -dp(12) })
        miolo.addView(ficha, LinearLayout.LayoutParams(dp(360), -1).apply { marginStart = dp(Estilo.E2) })
        coluna.addView(miolo, LinearLayout.LayoutParams(-1, 0, 1f)); coluna.addView(dicas)
        raiz.addView(coluna, FrameLayout.LayoutParams(-1, -1))
        foto = ImageView(this).apply { visibility = View.GONE; setBackgroundColor(Color.BLACK); scaleType = ImageView.ScaleType.FIT_CENTER }
        raiz.addView(foto, FrameLayout.LayoutParams(-1, -1))
        camada = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(0xB3000000.toInt()); isClickable = true }
        raiz.addView(camada, FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)
        // Com o teclado aberto, a janela encolhia e cortava o painel (botões pela metade, visto na TV): empurra em vez de encolher.
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        Estilo.entrar(coluna)
        pilha += Nivel(getString(R.string.rede_titulo), null, emptyList())
        desenharRaiz()
        procurar()
        intent.getStringExtra(EXTRA_BUSCA)?.trim()?.takeIf { it.length >= 2 }?.let { q ->
            val c = runCatching { MidiaArmazem.contas(this) }.getOrDefault(emptyList()).firstOrNull { it.ehJellyOuEmby || it.tipo == ContaMidia.PLEX }
            if (c != null) entrar(if (c.tipo == ContaMidia.PLEX) Origem.PlexO(c) else Origem.Jelly(c),
                (if (c.tipo == ContaMidia.PLEX) Plex.BUSCA else Jellyfin.BUSCA) + q, "“$q”")
        }
    }

    // Saiu para a HOME (que não chega como tecla) ou outro app: o "próximo em 10 s" não pode tocar por cima deles.
    override fun onStop() { super.onStop(); pararContagem() }

    override fun onDestroy() {
        cancelarPlex?.set(true)
        tela.removeCallbacksAndMessages(null); rede.shutdownNow(); capas.shutdownNow(); super.onDestroy()
    }

    // ---------------------------------------------------------------- raiz: contas + achados + adicionar

    /** Refaz o 1º nível. Lê o disco (um arquivo pequeno). [texto] null = o resumo padrão; [focarConta] = id da conta a focar. */
    private fun desenharRaiz(texto: String? = null, focarConta: String? = null) {
        val contas = runCatching { MidiaArmazem.contas(this) }.getOrDefault(emptyList())
        val itens = ArrayList<Any>()
        val volumes = volumesUsb()
        if (volumes.isNotEmpty()) {
            itens += Secao(getString(R.string.rede_secao_na_tv))
            volumes.forEach { itens += Linha(IconeMidia.Tipo.PENDRIVE, it.nome, getString(R.string.rede_sub_usb), null, it) }
        }
        if (contas.isNotEmpty()) {
            itens += Secao(getString(R.string.rede_secao_seus))
            contas.forEach { itens += linhaConta(it) }
        }
        val novos = ArrayList<Linha>()
        plexAchados.filter { a -> contas.none { it.tipo == ContaMidia.PLEX && it.usuarioId == a.id } }.forEach {
            novos += Linha(IconeMidia.Tipo.SERVIDOR, it.nome, "Plex · " + it.endereco.substringAfter("://"), getString(R.string.rede_entrar), it)
        }
        jellyAchados.filter { j -> contas.none { it.ehJellyOuEmby && it.servidor.removeSuffix("/emby") == j.endereco } }.forEach {
            novos += Linha(IconeMidia.Tipo.SERVIDOR, it.nome, (if (it.emby) "Emby · " else "Jellyfin · ") + it.endereco.substringAfter("://"),
                getString(R.string.rede_entrar), it)
        }
        dlna.forEach { novos += Linha(IconeMidia.Tipo.SERVIDOR, it.nome, getString(R.string.rede_sub_dlna), null, it) }
        maquinas.filter { m -> contas.none { it.tipo == ContaMidia.SMB && (it.servidor.equals(m.ip, true) || it.servidor.equals(m.nome, true)) } }.forEach {
            novos += Linha(IconeMidia.Tipo.COMPUTADOR, it.nome, getString(R.string.rede_sub_maquina, it.ip), getString(R.string.rede_adicionar), it)
        }
        if (novos.isNotEmpty()) { itens += Secao(getString(R.string.rede_secao_achados)); itens.addAll(novos) }
        itens += Secao(getString(R.string.rede_secao_adicionar))
        itens += Linha(IconeMidia.Tipo.SERVIDOR, "Plex", getString(R.string.rede_sub_plex), null, Acao.PLEX)
        itens += Linha(IconeMidia.Tipo.SERVIDOR, "Jellyfin", getString(R.string.rede_sub_jellyfin), null, Acao.JELLYFIN)
        itens += Linha(IconeMidia.Tipo.SERVIDOR, "Emby", getString(R.string.rede_sub_jellyfin), null, Acao.EMBY)
        itens += Linha(IconeMidia.Tipo.COMPUTADOR, getString(R.string.rede_acao_smb), getString(R.string.rede_sub_smb), null, Acao.SMB)
        itens += Linha(IconeMidia.Tipo.NUVEM, getString(R.string.rede_acao_webdav), getString(R.string.rede_sub_webdav), null, Acao.WEBDAV)
        itens += Linha(IconeMidia.Tipo.COMPUTADOR, getString(R.string.rede_acao_arquivos), getString(R.string.rede_sub_arquivos), null, Acao.ARQUIVOS)
        itens += Linha(IconeMidia.Tipo.NUVEM, "OneDrive", getString(R.string.rede_sub_nuvem), null, Acao.ONEDRIVE)
        itens += Linha(IconeMidia.Tipo.NUVEM, "Dropbox", getString(R.string.rede_sub_nuvem), null, Acao.DROPBOX)
        itens += Linha(IconeMidia.Tipo.NUVEM, "Box", getString(R.string.rede_sub_nuvem), null, Acao.BOX)
        itens += Linha(IconeMidia.Tipo.NUVEM, "Google Drive", getString(R.string.rede_sub_nuvem), null, Acao.GDRIVE)
        itens += Linha(IconeMidia.Tipo.LINK, getString(R.string.rede_acao_link), getString(R.string.rede_sub_link), null, Acao.LINK)
        itens += Linha(IconeMidia.Tipo.ATUALIZAR, getString(R.string.rede_acao_procurar), null, null, Acao.PROCURAR)
        val velho = pilha[0]
        // Depois de adicionar, o foco vai para o que acabou de entrar (não para o 1º da lista).
        val novo = focarConta?.let { id -> itens.indexOfFirst { ((it as? Linha)?.alvo as? ContaMidia)?.id == id } } ?: -1
        pilha[0] = Nivel(velho.titulo, null, itens, if (novo >= 0) novo else velho.foco)
        val resumo = when {
            procurando -> getString(R.string.rede_procurando)
            contas.isEmpty() && novos.isEmpty() && volumes.isEmpty() -> getString(R.string.rede_nenhum)
            else -> (contas.size + novos.size + volumes.size).let { resources.getQuantityString(R.plurals.rede_resumo, it, it) }
        }
        if (pilha.size == 1) desenhar(texto ?: resumo)
    }

    private fun linhaConta(c: ContaMidia): Linha {
        val onde = c.servidor.substringAfter("://").substringBefore('/')
        val (icone, sub) = when (c.tipo) {
            ContaMidia.PLEX -> IconeMidia.Tipo.SERVIDOR to "Plex · $onde"
            ContaMidia.JELLYFIN -> IconeMidia.Tipo.SERVIDOR to "Jellyfin · $onde"
            ContaMidia.EMBY -> IconeMidia.Tipo.SERVIDOR to "Emby · $onde"
            ContaMidia.WEBDAV -> IconeMidia.Tipo.NUVEM to "WebDAV · $onde"
            ContaMidia.FTP -> IconeMidia.Tipo.COMPUTADOR to "FTP · $onde"
            ContaMidia.FTPS -> IconeMidia.Tipo.COMPUTADOR to "FTPS · $onde"
            ContaMidia.SFTP -> IconeMidia.Tipo.COMPUTADOR to "SFTP · $onde"
            ContaMidia.NFS -> IconeMidia.Tipo.COMPUTADOR to "NFS · $onde"
            ContaMidia.ONEDRIVE -> IconeMidia.Tipo.NUVEM to "OneDrive · ${c.usuario}"
            ContaMidia.DROPBOX -> IconeMidia.Tipo.NUVEM to "Dropbox · ${c.usuario}"
            ContaMidia.BOX -> IconeMidia.Tipo.NUVEM to "Box · ${c.usuario}"
            ContaMidia.GDRIVE -> IconeMidia.Tipo.NUVEM to "Google Drive · ${c.usuario}"
            ContaMidia.LINK -> IconeMidia.Tipo.LINK to (if (c.pasta == LISTA) getString(R.string.rede_sub_lista, onde) else getString(R.string.rede_sub_video, onde))
            else -> IconeMidia.Tipo.COMPUTADOR to getString(R.string.rede_sub_pasta, onde)
        }
        return Linha(icone, c.nome, sub, null, c)
    }

    private fun procurar() {
        if (procurando) return
        procurando = true
        if (pilha.size == 1) desenharRaiz()
        Fundo.rodar("midia-procurar") {
            // O Android descarta pacotes de grupo (multicast) no Wi-Fi para poupar bateria: o "lock" libera enquanto procura.
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val trava = wifi.createMulticastLock("tvlauncher-midia").apply { setReferenceCounted(false) }
            runCatching { trava.acquire() }
            var d: List<Upnp.Servidor> = emptyList(); var j: List<Jellyfin.Achado> = emptyList()
            var p: List<Plex.Achado> = emptyList(); var m: List<Descoberta.Maquina> = emptyList()
            // Os quatro jeitos de achar ao mesmo tempo: a busca inteira leva o tempo do mais lento (~3 s), não a soma.
            Fundo.juntos(9000,
                { d = Upnp.procurar().mapNotNull { Upnp.ficha(it) }.distinctBy { it.pastas }.sortedBy { it.nome.lowercase() } },
                { j = Jellyfin.procurarTodos() },
                { p = Plex.procurar() },
                { m = Descoberta.pastas(applicationContext) },
            )
            runCatching { trava.release() }
            tela.post {
                procurando = false
                if (isDestroyed) return@post
                // O Plex também responde ao DLNA quando o "DLNA" dele está ligado: aí ele aparece uma vez só, como Plex.
                dlna = d.filter { s -> p.none { s.pastas.contains(it.endereco.substringAfter("://").substringBefore(':')) && s.nome.contains("Plex", true) } }
                jellyAchados = j; plexAchados = p; maquinas = m
                desenharRaiz()
            }
        }
    }

    // ---------------------------------------------------------------- navegar

    private fun entrar(origem: Origem, id: String, nome: String) {
        if (ocupado) return
        ocupado = true
        aviso.text = getString(R.string.rede_abrindo)
        val pastas = pilha.drop(2).map { it.titulo } + nome   // lido aqui (thread da tela); o resto vai para a rede
        rede.execute {
            val r = runCatching {
                when (origem) {
                    is Origem.Dlna -> Upnp.navegar(origem.s, id)
                    is Origem.Pasta -> Pastas.listar(origem.c, id)
                    is Origem.Local -> listarLocal(origem.raiz, id)
                    is Origem.Jelly -> Jellyfin.listar(origem.c, id, aparelho())
                    is Origem.PlexO -> Plex.listar(origem.c, id, aparelho())
                    is Origem.Dav -> WebDav.listar(origem.c, id)
                    is Origem.Link -> FontesMidia.listarLink(origem.c)
                }
            }
            // Rodada 15: ler os nomes e ordenar FORA da thread da tela (pasta DLNA com milhares de vídeos dava ANR).
            // Elevação 8.2: vídeos de pasta (não Jellyfin/Plex, que já têm ficha) ganham título, temporada e episódio.
            val lidos = r.getOrNull()?.takeUnless { origem is Origem.Jelly || origem is Origem.PlexO }
                ?.filter { !it.pasta && it.tipo == Upnp.Tipo.VIDEO }
                ?.mapNotNull { e -> LeitorNomes.ler(e.titulo, pastas).takeIf { CatalogoPasta.leuAlgo(e.titulo, it) }?.let { e to it } }?.toMap()
                ?: emptyMap()
            val ordem = r.getOrNull()?.let { if (lidos.isEmpty()) it else CatalogoPasta.ordenar(it, { e -> e.pasta }, { e -> lidos[e] }) }
            tela.post {
                ocupado = false
                if (isDestroyed) return@post
                val entradas = r.getOrNull()
                if (entradas == null || ordem == null) { aviso.text = erroLegivel(r.exceptionOrNull()); return@post }
                pilha.last().foco = lista.selectedItemPosition
                val buscar = if (id == "" && (origem is Origem.Jelly || origem is Origem.PlexO))
                    listOf(Linha(IconeMidia.Tipo.LUPA, getString(R.string.rede_buscar), null, null, Buscar(origem))) else emptyList()
                val catalogo = if (lidos.isNotEmpty() && InfoExternaCache.temFicha()) listOf(linhaCatalogo()) else emptyList()
                pilha += Nivel(nome, origem, buscar + catalogo + linhaFotos(origem, id, nome, entradas) + ordem.map { linhaEntrada(it, lidos[it]) }, lidos = lidos)
                anotarNaBiblioteca(origem, entradas, lidos)
                desenhar(if (entradas.isEmpty()) getString(R.string.rede_vazia) else resources.getQuantityString(R.plurals.rede_itens, entradas.size, entradas.size))
            }
        }
    }

    /**
     * Pasta com fotos de um PC/NAS (SMB) ou Nextcloud (WebDAV): a 1ª linha põe essas fotos no protetor de tela, ou tira,
     * se já for a pasta escolhida. As fotos são copiadas para a TV (FotosRede.kt), então o protetor não depende do PC.
     */
    private fun linhaFotos(origem: Origem, id: String, nome: String, entradas: List<Upnp.Entrada>): List<Linha> {
        val (conta, tipo) = when (origem) {
            is Origem.Pasta -> if (origem.c.tipo in FotosRede.TIPOS_ACEITOS) origem.c.id to origem.c.tipo else return emptyList()
            is Origem.Dav -> origem.c.id to ContaMidia.WEBDAV
            else -> return emptyList()
        }
        val n = FotosRede.fotosDa(entradas).size
        if (n == 0) return emptyList()
        return listOf(linhaFotos(FotosNoProtetor(FotosRede.Origem(conta, tipo, id, nome), n)))
    }

    private fun linhaFotos(f: FotosNoProtetor): Linha {
        val usando = FotosRede.origem(this)?.let { it.conta == f.origem.conta && it.caminho == f.origem.caminho } == true
        return Linha(IconeMidia.Tipo.FOTO, getString(if (usando) R.string.rede_fotos_parar else R.string.rede_fotos_usar),
            resources.getQuantityString(R.plurals.rede_fotos_sub, f.quantas, f.quantas), null, f)
    }

    private fun alternarFotos(pos: Int, f: FotosNoProtetor) {
        val usando = FotosRede.origem(this)?.let { it.conta == f.origem.conta && it.caminho == f.origem.caminho } == true
        FotosRede.escolher(this, if (usando) null else f.origem)
        val nivel = pilha.lastOrNull() ?: return
        pilha[pilha.lastIndex] = Nivel(nivel.titulo, nivel.origem, nivel.itens.toMutableList().also { it[pos] = linhaFotos(f) }, pos, nivel.lidos)
        desenhar(getString(if (usando) R.string.rede_fotos_paradas else R.string.rede_fotos_usando))
    }

    // ---------------------------------------------------------------- elevação 7.1: biblioteca única

    /**
     * Os vídeos vistos aqui entram no índice da busca única (só na TV): título, ano, qualidade e o endereço do arquivo
     * quando ele não leva senha nem chave (BibliotecaIndice.enderecoGuardavel). Jellyfin/Plex/Emby entram sem endereço:
     * a busca abre a procura no servidor.
     */
    private fun anotarNaBiblioteca(origem: Origem, entradas: List<Upnp.Entrada>, lidos: Map<Upnp.Entrada, LeitorNomes.Lido>) {
        val (tipo, onde) = when (origem) {
            is Origem.Pasta -> (if (origem.c.tipo in setOf(ContaMidia.ONEDRIVE, ContaMidia.DROPBOX, ContaMidia.BOX, ContaMidia.GDRIVE))
                BibliotecaUnica.Origem.NUVEM else BibliotecaUnica.Origem.PASTA) to origem.c.nome
            is Origem.Local -> BibliotecaUnica.Origem.PASTA to origem.raiz.name
            is Origem.Dav -> BibliotecaUnica.Origem.NUVEM to origem.c.nome
            is Origem.Dlna -> BibliotecaUnica.Origem.SERVIDOR to origem.s.nome
            is Origem.Jelly -> BibliotecaUnica.Origem.SERVIDOR to origem.c.nome
            is Origem.PlexO -> BibliotecaUnica.Origem.SERVIDOR to origem.c.nome
            is Origem.Link -> return
        }
        val servidor = origem is Origem.Jelly || origem is Origem.PlexO
        val app = applicationContext
        if (capas.isShutdown) return
        capas.execute {   // a leitura dos nomes que faltam também sai da thread da tela (rodada 15)
        val fontes = entradas.filter { !it.pasta && it.tipo == Upnp.Tipo.VIDEO }.mapNotNull { e ->
            val l = lidos[e] ?: LeitorNomes.ler(e.titulo).takeIf { it.nome.isNotBlank() && !it.extra } ?: return@mapNotNull null
            BibliotecaUnica.Fonte(tipo, onde, l.nome, l.ano, l.serie, qualidade = BibliotecaUnica.qualidadeDoNome(e.titulo),
                versao = Idioma.versao(e.titulo, ""), abrir = if (servidor || l.serie) "" else e.url.orEmpty())
        }.distinctBy { listOf(it.titulo, it.serie, it.abrir) }   // série: uma linha por série (cada episódio não vira título)
        if (fontes.isNotEmpty()) BibliotecaIndice.anotar(app, fontes)
        }
    }

    // ---------------------------------------------------------------- elevação 8.2: a pasta vira catálogo

    private fun linhaCatalogo(): Linha = Linha(IconeMidia.Tipo.VIDEO, getString(R.string.elv_cat_titulo), getString(R.string.elv_cat_explica),
        getString(if (CatalogoPasta.ligado(this)) R.string.elv_cat_ligado else R.string.elv_cat_desligado), LigarCatalogo)

    private fun alternarCatalogo(pos: Int) {
        CatalogoPasta.definir(this, !CatalogoPasta.ligado(this))
        val nivel = pilha.lastOrNull() ?: return
        pilha[pilha.lastIndex] = Nivel(nivel.titulo, nivel.origem, nivel.itens.toMutableList().also { it[pos] = linhaCatalogo() }, pos, nivel.lidos)
        desenhar(aviso.text.toString())
    }

    /** "Breaking Bad · T2 E5", "Frieren · Episódio 7", "Duna (2021)". */
    private fun rotulo(l: LeitorNomes.Lido): String = when {
        l.temporada != null && l.episodio != null -> getString(R.string.elv_cat_ep, l.nome, l.temporada, l.episodio) +
            (if (l.episodios.size > 1) "–" + l.episodios.last() else "")
        l.episodio != null -> getString(R.string.elv_cat_so_ep, l.nome, l.episodio)
        l.temporada != null -> getString(R.string.elv_cat_temporada, l.nome, l.temporada)
        l.data != null -> "${l.nome} · ${l.data}"
        l.ano != null -> getString(R.string.elv_cat_filme_ano, l.nome, l.ano)
        else -> l.nome
    }

    /** Ficha de um vídeo de pasta: pôster, sinopse (do episódio, se for série) e temporadas, pelo cache do TMDB. */
    private fun fichaDaPasta(lido: LeitorNomes.Lido) {
        val b = CatalogoPasta.busca(lido, CatalogoPasta.nomeCorrigido(this, lido))
        if (b == null) { ficha.visibility = View.GONE; capaPedida = null; return }
        val chave = b.chave + (lido.temporada?.let { ":$it" } ?: "") + (lido.episodio?.let { "x$it" } ?: "")
        ficha.visibility = View.VISIBLE
        fichaTitulo.text = rotulo(lido)
        capaPedida = chave
        if (fichasPasta.containsKey(chave)) { mostrarFichaPasta(fichasPasta[chave]); return }
        fichaTexto.text = getString(R.string.elv_cat_procurando); fichaTexto.visibility = View.VISIBLE
        fichaCapa.setImageDrawable(null); fichaCapa.visibility = View.GONE
        if (capas.isShutdown) return
        val app = applicationContext
        capas.execute {
            if (capaPedida != chave) return@execute   // o foco já andou: não pergunta
            val f = runCatching {
                val g = InfoExternaCache.resolver(app, b.chave, b.nome, b.ano, b.serie, null)
                val d = g.detalhe ?: return@runCatching null
                val t = lido.temporada; val n = lido.episodio
                val ep = if (b.serie && t != null && n != null) InfoExternaCache.episodio(d.tmdb, t, n) else null
                val temporadas = if (d.temporadas > 0) getString(R.string.elv_cat_n_temporadas, d.temporadas) else null
                val texto = listOfNotNull(ep?.first?.takeIf { it.isNotBlank() }, ep?.second ?: d.sinopse.takeIf { it.isNotBlank() }, temporadas).joinToString("\n\n")
                FichaPasta(rotulo(lido), texto, d.poster)
            }.getOrNull()
            if (f != null && f.poster.isNotEmpty() && cacheCapas.get(f.poster) == null)
                runCatching { baixarCapa(null, f.poster) }.getOrNull()?.let { bmp -> tela.post { cacheCapas.put(f.poster, bmp); if (capaPedida == chave) mostrarFichaPasta(f) } }
            tela.post {
                if (isDestroyed) return@post
                fichasPasta[chave] = f
                if (capaPedida == chave) mostrarFichaPasta(f)
            }
        }
    }

    private fun mostrarFichaPasta(f: FichaPasta?) {
        fichaTexto.visibility = View.VISIBLE
        fichaTexto.text = f?.texto?.ifBlank { null } ?: getString(R.string.elv_cat_nada)
        val bmp = f?.poster?.takeIf { it.isNotEmpty() }?.let { cacheCapas.get(it) }
        fichaCapa.visibility = if (bmp == null) View.GONE else View.VISIBLE
        if (bmp != null) fichaCapa.setImageBitmap(bmp) else fichaCapa.setImageDrawable(null)
    }

    /** Segurar OK num vídeo lido: corrigir o casamento ("não é este" ou escrever o nome certo). */
    private fun corrigirCasamento(pos: Int) {
        val nivel = pilha.lastOrNull() ?: return
        val e = (nivel.itens.getOrNull(pos) as? Linha)?.alvo as? Upnp.Entrada ?: return
        val lido = nivel.lidos[e] ?: return
        val b = CatalogoPasta.busca(lido, CatalogoPasta.nomeCorrigido(this, lido))
        fun esquecer() { fichasPasta.keys.removeAll { b != null && it.startsWith(b.chave) } }
        abrirPainel(600) { p ->
            p.addView(Estilo.texto(this, Estilo.Papel.TITULO, getString(R.string.elv_cat_corrigir_titulo, rotulo(lido))).apply { textSize = 22f })
            p.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.elv_cat_corrigir_explica)).apply {
                setLineSpacing(0f, 1.2f); setPaddingRelative(0, dp(Estilo.E1), 0, dp(Estilo.E3))
            })
            val cancelar = Estilo.botao(this, getString(R.string.rede_cancelar), primario = false).apply { setOnClickListener { fecharPainel() } }
            val escrever = Estilo.botao(this, getString(R.string.elv_cat_escrever), primario = false).apply {
                setOnClickListener {
                    val inicial = CatalogoPasta.nomeCorrigido(this@MidiaRedeActivity, lido) ?: listOfNotNull(lido.nome, lido.ano?.toString()).joinToString(" ")
                    formulario(getString(R.string.elv_cat_corrigir_titulo, rotulo(lido)), getString(R.string.elv_cat_corrigir_explica),
                        listOf(Campo(getString(R.string.elv_cat_campo), inicial = inicial)), getString(R.string.elv_cat_salvar)) { v, fim ->
                        esquecer()
                        CatalogoPasta.corrigirNome(this@MidiaRedeActivity, lido, v[0])
                        fim(null); atualizarFicha()
                    }
                }
            }
            val naoE = Estilo.botao(this, getString(R.string.elv_cat_nao_e), primario = true).apply {
                isEnabled = b != null
                setOnClickListener {
                    if (b == null) return@setOnClickListener
                    val app = applicationContext
                    capas.execute { InfoExternaCache.recusar(app, b.chave); tela.post { if (!isDestroyed) { esquecer(); fecharPainel(); atualizarFicha() } } }
                }
            }
            p.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                addView(cancelar)
                addView(escrever, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
                addView(naoE, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
            })
            (if (b != null) naoE else escrever).requestFocus()
        }
    }

    private fun linhaEntrada(e: Upnp.Entrada, lido: LeitorNomes.Lido? = null): Linha {
        val icone = when {
            e.pasta && (e.id == Jellyfin.CONTINUAR || e.id == Plex.CONTINUAR) -> IconeMidia.Tipo.CONTINUAR
            e.pasta && (e.id == Jellyfin.RECENTES || e.id == Plex.RECENTES) -> IconeMidia.Tipo.NOVOS
            else -> when (e.tipo) {
                Upnp.Tipo.PASTA -> IconeMidia.Tipo.PASTA
                Upnp.Tipo.VIDEO -> IconeMidia.Tipo.VIDEO
                Upnp.Tipo.AUDIO -> IconeMidia.Tipo.MUSICA
                Upnp.Tipo.FOTO -> IconeMidia.Tipo.FOTO
                Upnp.Tipo.OUTRO -> IconeMidia.Tipo.ARQUIVO
            }
        }
        // Vídeo lido (8.2): o título limpo em cima, o nome do arquivo embaixo (a pessoa ainda acha o arquivo dela).
        return if (lido != null) Linha(icone, rotulo(lido), e.titulo, null, e) else Linha(icone, tituloEntrada(e), null, null, e)
    }

    /** As pastas especiais vêm do servidor com nome fixo em português: aqui ganham o nome no idioma da TV (lista e título). */
    private fun tituloEntrada(e: Upnp.Entrada): String = when {
        e.pasta && (e.id == Jellyfin.CONTINUAR || e.id == Plex.CONTINUAR) -> getString(R.string.rede_continuar)
        e.pasta && (e.id == Jellyfin.RECENTES || e.id == Plex.RECENTES) -> getString(R.string.rede_recentes)
        else -> e.titulo
    }

    // ---------------------------------------------------------------- ideia 29: buscar, ficha e fila

    private fun buscar(b: Buscar) {
        formulario(getString(R.string.rede_buscar), getString(R.string.rede_buscar_explica), listOf(Campo(getString(R.string.rede_buscar_campo))),
            getString(R.string.rede_buscar)) { v, fim ->
            val q = v[0].trim()
            if (q.length < 2) { fim(getString(R.string.rede_buscar_curto)); return@formulario }
            fim(null)
            entrar(b.origem, (if (b.origem is Origem.Jelly) Jellyfin.BUSCA else Plex.BUSCA) + q, "“$q”")
        }
    }

    /** Mostra a ficha do item com foco (pôster baixado fora da tela, guardado em memória; sinopse do servidor). */
    private fun atualizarFicha() {
        val nivel = pilha.lastOrNull()
        val e = (nivel?.itens?.getOrNull(lista.selectedItemPosition) as? Linha)?.alvo as? Upnp.Entrada
        if (e != null && e.capa == null && e.sinopse == null && lista.hasFocus() && CatalogoPasta.ligado(this)) {
            nivel.lidos[e]?.let { fichaDaPasta(it); return }
        }
        if (e == null || (e.capa == null && e.sinopse == null) || !lista.hasFocus()) { ficha.visibility = View.GONE; capaPedida = null; return }
        ficha.visibility = View.VISIBLE
        fichaTitulo.text = e.titulo
        fichaTexto.text = e.sinopse.orEmpty(); fichaTexto.visibility = if (e.sinopse == null) View.GONE else View.VISIBLE
        val chave = e.capa?.let { (nivel.origem?.hashCode() ?: 0).toString() + it }
        capaPedida = chave
        fichaCapa.visibility = if (chave == null) View.GONE else View.VISIBLE
        if (chave == null) return
        cacheCapas.get(chave)?.let { fichaCapa.setImageBitmap(it); return }
        fichaCapa.setImageDrawable(null)
        val origem = nivel.origem
        if (capas.isShutdown) return
        capas.execute {
            if (capaPedida != chave) return@execute   // o foco já andou: não baixa
            val bmp = runCatching { baixarCapa(origem, e.capa) }.getOrNull()
            tela.post {
                if (isDestroyed || bmp == null) return@post
                cacheCapas.put(chave, bmp)
                if (capaPedida == chave) fichaCapa.setImageBitmap(bmp)
            }
        }
    }

    private fun baixarCapa(origem: Origem?, capa: String): Bitmap? {
        // Pôster do TMDB (8.2): só o endereço montado por InfoExterna.urlPoster (https, image.tmdb.org).
        val c = if (capa.startsWith("https://image.tmdb.org/t/p/")) URL(capa).openConnection() as HttpURLConnection else when (origem) {
            is Origem.Jelly -> (URL(origem.c.servidor + capa).openConnection() as HttpURLConnection).apply {
                setRequestProperty("Authorization", Jellyfin.cabecalho(aparelho(), origem.c.token)) }
            is Origem.PlexO -> (URL(origem.c.servidor + capa).openConnection() as HttpURLConnection).apply {
                Plex.cabecalhos(aparelho(), origem.c.token).forEach { (k, v) -> setRequestProperty(k, v) } }
            is Origem.Dlna -> if (capa.startsWith("http://") || capa.startsWith("https://")) URL(capa).openConnection() as HttpURLConnection else null
            else -> null
        } ?: return null
        c.connectTimeout = 4000; c.readTimeout = 10000; c.instanceFollowRedirects = false
        val bytes = lerFotoComTeto(c, 5 * 1024 * 1024) ?: return null
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var amostra = 1
        while (o.outWidth / (amostra * 2) >= 400 && o.outHeight / (amostra * 2) >= 600) amostra *= 2
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = amostra })
    }

    /** Toca o arquivo da posição [pos] pedindo o resultado: no fim, a pasta emenda o próximo do mesmo tipo. */
    private fun tocar(pos: Int, e: Upnp.Entrada, url: String) {
        pararContagem()
        val nivel = pilha.lastOrNull() ?: return
        nivel.foco = pos
        tocandoPos = pos; tocandoTipo = e.tipo; tocandoNivel = nivel
        VodActivity.abrirUrlComResultado(this, CODIGO_TOCAR, url, e.titulo)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CODIGO_TOCAR) return
        val nivel = pilha.lastOrNull(); val tipo = tocandoTipo
        if (nivel == null || nivel !== tocandoNivel || tipo == null) return   // a pessoa saiu da pasta
        val terminou = resultCode == RESULT_OK && data?.getBooleanExtra(VodActivity.RESULTADO_TERMINOU, false) == true
        val teclou = data?.getBooleanExtra(VodActivity.RESULTADO_TECLA, true) ?: true
        val desligou = data?.getBooleanExtra(VodActivity.RESULTADO_DESLIGOU, false) == true
        seguidos = FimDoVideo.seguidos(seguidos, teclou || !terminou)
        val tipos = nivel.itens.map { ((it as? Linha)?.alvo as? Upnp.Entrada)?.takeIf { e -> e.url != null }?.tipo }
        val prox = FilaRede.proximo(tipos, tocandoPos, tipo)
        val e = ((nivel.itens.getOrNull(prox) as? Linha)?.alvo as? Upnp.Entrada)
        val url = e?.url
        when (if (url == null) FilaRede.Passo.NADA else FilaRede.depois(tipo, terminou, true, desligou, seguidos)) {
            FilaRede.Passo.TOCAR_JA -> tocar(prox, e!!, url!!)
            FilaRede.Passo.CONTAR -> contar(prox, e!!, url!!, FimDoVideo.CONTAGEM_S)
            FilaRede.Passo.PERGUNTAR -> { lista.setSelection(prox); nivel.foco = prox; aviso.text = getString(R.string.rede_ainda_assistindo, e!!.titulo) }
            FilaRede.Passo.NADA -> {}
        }
    }

    /** "Próximo em 10 s": o foco já fica no próximo (OK começa agora); qualquer outra tecla cancela. */
    private fun contar(pos: Int, e: Upnp.Entrada, url: String, resta: Int) {
        lista.setSelection(pos); lista.requestFocus()
        aviso.text = getString(R.string.rede_proximo_em, e.titulo, resta)
        val r = Runnable { if (resta <= 1) tocar(pos, e, url) else contar(pos, e, url, resta - 1) }
        contando = r
        tela.postDelayed(r, 1000)
    }

    private fun pararContagem() {
        contando?.let { tela.removeCallbacks(it) }
        if (contando != null) aviso.text = (pilha.lastOrNull()?.itens?.size ?: 0).let { resources.getQuantityString(R.plurals.rede_itens, it, it) }
        contando = null
    }

    private fun abrir(pos: Int) {
        val nivel = pilha.lastOrNull() ?: return
        val linha = nivel.itens.getOrNull(pos) as? Linha ?: return
        when (val e = linha.alvo) {
            is ContaMidia -> when (e.tipo) {
                ContaMidia.JELLYFIN, ContaMidia.EMBY -> entrar(Origem.Jelly(e), "", e.nome)
                ContaMidia.PLEX -> entrar(Origem.PlexO(e), "", e.nome)
                ContaMidia.WEBDAV -> entrar(Origem.Dav(e), "", e.nome)
                ContaMidia.LINK -> if (e.pasta == LISTA) entrar(Origem.Link(e), "", e.nome) else { nivel.foco = pos; VodActivity.abrirUrl(this, e.servidor, e.nome) }
                else -> entrar(Origem.Pasta(e), Pastas.inicio(e), e.nome)
            }
            is Volume -> entrar(Origem.Local(e.raiz), "", e.nome)
            is Plex.Achado -> loginPlex()
            is Jellyfin.Achado -> formularioJellyfin(e, e.emby)
            is Upnp.Servidor -> entrar(Origem.Dlna(e), "0", e.nome)
            is Descoberta.Maquina -> formularioSmb(e)
            Acao.PLEX -> loginPlex()
            Acao.JELLYFIN -> formularioJellyfin(null, false)
            Acao.EMBY -> formularioJellyfin(null, true)
            Acao.SMB -> formularioSmb(null)
            Acao.WEBDAV -> formularioWebDav()
            Acao.ARQUIVOS -> formularioArquivos()
            Acao.ONEDRIVE -> loginOneDrive()
            Acao.DROPBOX -> loginDropbox()
            Acao.BOX -> loginBox()
            Acao.GDRIVE -> loginDrive()
            Acao.LINK -> formularioLink()
            Acao.PROCURAR -> procurar()
            is FotosNoProtetor -> alternarFotos(pos, e)
            is Buscar -> buscar(e)
            LigarCatalogo -> alternarCatalogo(pos)
            is Upnp.Entrada -> when {
                e.pasta -> entrar(nivel.origem ?: return, e.id, tituloEntrada(e))
                e.tipo == Upnp.Tipo.FOTO -> mostrarFoto(nivel.origem, e)
                e.url != null -> tocar(pos, e, e.url)
            }
        }
    }

    private fun desenhar(texto: String) {
        val nivel = pilha.lastOrNull() ?: return
        titulo.text = nivel.titulo
        trilha.text = pilha.dropLast(1).joinToString("  ›  ") { it.titulo }
        trilha.visibility = if (pilha.size > 1) View.VISIBLE else View.GONE
        aviso.text = texto
        adaptador.notifyDataSetChanged()
        val alvo = nivel.foco.takeIf { it >= 0 && nivel.itens.getOrNull(it) is Linha } ?: nivel.itens.indexOfFirst { it is Linha }
        // No 1º item, setSelection rolava o nome do grupo ("SEUS SERVIDORES") para fora: mostra a lista desde o topo.
        if (alvo >= 0 && nivel.itens.take(alvo).all { it is Secao }) lista.setSelectionFromTop(alvo, alturaSecoes(alvo)) else lista.setSelection(maxOf(0, alvo))
        dicas.removeAllViews()
        // "segure OK remover" só quando há conta salva para remover (Sala, 17/11/2026: aparecia só com o pendrive, que não sai).
        val temConta = nivel.itens.any { (it as? Linha)?.alvo is ContaMidia }
        dicas.addView(if (pilha.size == 1 && temConta) Estilo.barraDicas(this, "OK" to getString(R.string.rede_dica_abrir),
            getString(R.string.rede_tecla_segurar) to getString(R.string.rede_dica_remover), getString(R.string.rede_tecla_voltar) to getString(R.string.rede_dica_sair))
        else if (pilha.size == 1) Estilo.barraDicas(this, "OK" to getString(R.string.rede_dica_abrir), getString(R.string.rede_tecla_voltar) to getString(R.string.rede_dica_sair))
        else if (nivel.lidos.isNotEmpty() && CatalogoPasta.ligado(this)) Estilo.barraDicas(this, "OK" to getString(R.string.rede_dica_abrir),
            getString(R.string.rede_tecla_segurar) to getString(R.string.elv_cat_dica), getString(R.string.rede_tecla_voltar) to getString(R.string.rede_dica_subir))
        else Estilo.barraDicas(this, "OK" to getString(R.string.rede_dica_abrir), getString(R.string.rede_tecla_voltar) to getString(R.string.rede_dica_subir)))
        if (camada.visibility != View.VISIBLE) lista.requestFocus()
        lista.post { pintarFoco() }
    }

    /** Mensagem para a pessoa, sem detalhe técnico (e sem nada da conta). */
    private fun erroLegivel(e: Throwable?): String {
        val nome = generateSequence(e) { it.cause }.map { it.javaClass.simpleName + " " + (it.message ?: "") }.joinToString(" ")
        return when {
            nome.contains("NUVEM_ENTRAR_DE_NOVO") -> getString(R.string.rede_erro_nuvem_login)
            nome.contains("LOGON_FAILURE", true) || nome.contains("ACCESS_DENIED", true) ||
                nome.contains("SecurityException 401") -> getString(R.string.rede_erro_senha)
            nome.contains("BAD_NETWORK_NAME", true) -> getString(R.string.rede_erro_pasta)
            nome.contains("NFS_PORTA") -> getString(R.string.rede_erro_nfs_porta)
            nome.contains("NFS_IP") || nome.contains("NFS_RECUSADO") -> getString(R.string.rede_erro_nfs_ip)
            nome.contains("CHAVE_MUDOU") -> getString(R.string.rede_erro_chave_mudou)
            nome.contains("SEM_ACESSO_ARQUIVOS") -> getString(R.string.rede_erro_usb_acesso)
            nome.contains("certificado", true) || nome.contains("SSLHandshake", true) || nome.contains("CertPath", true) ->
                getString(R.string.rede_erro_certificado)
            nome.contains("UnknownHost", true) || nome.contains("ConnectException", true) || nome.contains("Timeout", true) ||
                nome.contains("NoRouteToHost", true) -> getString(R.string.rede_erro_servidor)
            else -> getString(R.string.rede_erro)
        }
    }

    // ---------------------------------------------------------------- teclas

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Contagem do próximo arquivo: OK começa já (o foco está nele); qualquer outra tecla cancela e segue normal.
        if (contando != null && e.action == KeyEvent.ACTION_DOWN && !SegurarOk.ehOk(e.keyCode)) pararContagem()
        // OK segurado 2 s numa conta salva = remover (mesmo gesto dos favoritos e interesses do player).
        if (SegurarOk.ehOk(e.keyCode) && lista.hasFocus() && foto.visibility != View.VISIBLE && camada.visibility != View.VISIBLE &&
            pilha.size == 1 && (pilha[0].itens.getOrNull(lista.selectedItemPosition) as? Linha)?.alvo is ContaMidia) {
            return segurar.tratar(e, aoCurto = { abrir(lista.selectedItemPosition) }, aoSegurar = { confirmarRemover(lista.selectedItemPosition) })
        }
        // Segurar OK num vídeo lido (8.2), com o pôster ligado: corrigir o casamento.
        if (SegurarOk.ehOk(e.keyCode) && lista.hasFocus() && foto.visibility != View.VISIBLE && camada.visibility != View.VISIBLE &&
            pilha.size > 1 && CatalogoPasta.ligado(this) && videoLido(lista.selectedItemPosition)) {
            return segurar.tratar(e, aoCurto = { abrir(lista.selectedItemPosition) }, aoSegurar = { corrigirCasamento(lista.selectedItemPosition) })
        }
        return super.dispatchKeyEvent(e)
    }

    private fun videoLido(pos: Int): Boolean {
        val nivel = pilha.lastOrNull() ?: return false
        val e = (nivel.itens.getOrNull(pos) as? Linha)?.alvo as? Upnp.Entrada ?: return false
        return nivel.lidos.containsKey(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (camada.visibility == View.VISIBLE) { fecharPainel(); return true }
            if (foto.visibility == View.VISIBLE) { foto.visibility = View.GONE; foto.setImageDrawable(null); lista.requestFocus(); return true }
            if (pilha.size > 1 && !ocupado) {
                pilha.removeAt(pilha.lastIndex)
                if (pilha.size == 1) desenharRaiz() else desenhar(pilha.last().itens.size.let { resources.getQuantityString(R.plurals.rede_itens, it, it) })
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- painéis (formulário, confirmação, código)

    /** Abre um painel de vidro no centro, por cima da lista. [conteudo] recebe o cartão para preencher. */
    private fun abrirPainel(largura: Int = 560, conteudo: (LinearLayout) -> Unit) {
        camada.removeAllViews()
        val cartao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundo(this@MidiaRedeActivity, 0xF2141A24.toInt(), Estilo.RAIO, Estilo.VIDRO_BORDA)
            setPaddingRelative(dp(Estilo.E3 + 4), dp(Estilo.E3), dp(Estilo.E3 + 4), dp(Estilo.E3))
        }
        conteudo(cartao)
        soltarRecorte(cartao); cartao.clipToPadding = false   // o botão com foco cresce 8% e saía cortado (visto na TV)
        camada.addView(cartao, FrameLayout.LayoutParams(dp(largura), -2, Gravity.CENTER))
        camada.visibility = View.VISIBLE
        Estilo.entrar(cartao)
    }

    private fun soltarRecorte(v: View) {
        if (v is ViewGroup) { v.clipChildren = false; for (i in 0 until v.childCount) soltarRecorte(v.getChildAt(i)) }
    }

    private fun fecharPainel() {
        cancelarPlex?.set(true); cancelarPlex = null
        camada.visibility = View.GONE; camada.removeAllViews()
        lista.requestFocus()
    }

    private class Campo(val dica: String, val senha: Boolean = false, val endereco: Boolean = false, val inicial: String = "")

    /**
     * Formulário padrão: título, explicação, campos, linha de erro e dois botões. [aoConfirmar] recebe os textos e uma
     * função [fim]: fim(null) fecha o painel, fim("texto") mostra o erro e devolve o botão.
     */
    private fun formulario(tituloP: String, explica: String, campos: List<Campo>, rotulo: String,
                           focoInicial: Int = 0, aoConfirmar: (List<String>, (String?) -> Unit) -> Unit) {
        abrirPainel { c ->
            c.addView(Estilo.texto(this, Estilo.Papel.TITULO, tituloP).apply { textSize = 22f })
            c.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, explica).apply {
                setLineSpacing(0f, 1.2f); setPaddingRelative(0, dp(Estilo.E1), 0, dp(Estilo.E2))
            })
            val edits = campos.map { f -> campoTexto(f).also { c.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) } }
            val erro = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { setTextColor(Estilo.ERRO); visibility = View.GONE; setPaddingRelative(0, dp(4), 0, 0) }
            c.addView(erro)
            val ok = Estilo.botao(this, rotulo, primario = true)
            val cancelar = Estilo.botao(this, getString(R.string.rede_cancelar), primario = false).apply { setOnClickListener { fecharPainel() } }
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPaddingRelative(0, dp(Estilo.E2), 0, 0)
                addView(cancelar); addView(ok, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
            })
            ok.id = View.generateViewId()
            edits.lastOrNull()?.nextFocusDownId = ok.id   // descer do último campo vai para o botão principal, não para Cancelar
            ok.setOnClickListener {
                if (!ok.isEnabled) return@setOnClickListener
                ok.isEnabled = false; ok.text = getString(R.string.rede_testando); erro.visibility = View.GONE
                aoConfirmar(edits.map { it.text.toString() }) { msg ->
                    when {
                        isDestroyed -> {}
                        msg == null -> fecharPainel()
                        else -> { ok.isEnabled = true; ok.text = rotulo; erro.text = msg; erro.visibility = View.VISIBLE; ok.requestFocus() }
                    }
                }
            }
            (edits.getOrNull(focoInicial) ?: ok).requestFocus()
        }
    }

    /** Campo de texto no sistema de design: superfície da linha; com foco, fio branco. */
    private fun campoTexto(f: Campo): EditText = EditText(this).apply {
        // isSingleLine troca a forma de mostrar o texto e desfazia o "senha" (a senha aparecia legível): vem ANTES do inputType.
        // Estilo.aplicar ANTES: isAllCaps = false zera a TransformationMethod, e a senha voltava a aparecer (visto na TV).
        Estilo.aplicar(this, Estilo.Papel.CORPO)
        isSingleLine = true
        inputType = when {
            f.senha -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            f.endereco -> InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            else -> InputType.TYPE_CLASS_TEXT
        }
        typeface = Estilo.fonte(Estilo.Papel.CORPO.peso)   // o tipo "senha" troca a fonte por monoespaçada: volta a Nunito
        imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        hint = f.dica; setText(f.inicial)
        setHintTextColor(Estilo.TEXTO_3)
        setPaddingRelative(dp(Estilo.E2), dp(12), dp(Estilo.E2), dp(12))
        val normal = Estilo.fundo(context, Estilo.SUPERFICIE, Estilo.RAIO_PEQUENO + 4, Estilo.BORDA)
        val focado = Estilo.fundo(context, Estilo.SUPERFICIE_ALTA, Estilo.RAIO_PEQUENO + 4, Estilo.FOCO, 2)
        background = normal
        setOnFocusChangeListener { v, foco -> v.background = if (foco) focado else normal }
    }

    private fun confirmarRemover(pos: Int) {
        val c = (pilha[0].itens.getOrNull(pos) as? Linha)?.alvo as? ContaMidia ?: return
        abrirPainel(520) { p ->
            p.addView(Estilo.texto(this, Estilo.Papel.TITULO, getString(R.string.rede_remover_titulo, c.nome)).apply { textSize = 22f })
            p.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.rede_remover_explica)).apply {
                setLineSpacing(0f, 1.2f); setPaddingRelative(0, dp(Estilo.E1), 0, dp(Estilo.E3))
            })
            val cancelar = Estilo.botao(this, getString(R.string.rede_cancelar), primario = false).apply { setOnClickListener { fecharPainel() } }
            val remover = Estilo.botao(this, getString(R.string.rede_remover), primario = true).apply {
                setOnClickListener {
                    rede.execute {
                        MidiaArmazem.apagar(this@MidiaRedeActivity, c.id)
                        tela.post { if (!isDestroyed) { fecharPainel(); desenharRaiz(getString(R.string.rede_removida, c.nome)) } }
                    }
                }
            }
            p.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END
                addView(cancelar); addView(remover, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
            })
            cancelar.requestFocus()   // apagar nunca é o padrão
        }
    }

    // ---------------------------------------------------------------- adicionar: pasta compartilhada

    private fun formularioSmb(achada: Descoberta.Maquina?) {
        val inicial = achada?.let { "\\\\${it.nome}\\" } ?: ""
        formulario(achada?.nome ?: getString(R.string.rede_smb_titulo), getString(R.string.rede_smb_explica), listOf(
            Campo(getString(R.string.rede_smb_endereco), endereco = true, inicial = inicial),
            Campo(getString(R.string.rede_smb_usuario)),
            Campo(getString(R.string.rede_smb_senha), senha = true),
            Campo(getString(R.string.rede_nome_opcional)),
        ), getString(R.string.rede_testar_salvar)) { v, fim ->
            var lido = MidiaContas.lerEnderecoSmb(v[0])
            // Achado na rede: se o nome NetBIOS não resolver, o IP resolve; quem escreveu "\\NOME\Filmes" ganha o IP por baixo.
            if (lido != null && achada != null && lido.first.equals(achada.nome, true)) lido = Triple(achada.ip, lido.second, lido.third)
            if (lido == null) { fim(getString(R.string.rede_smb_endereco_erro)); return@formulario }
            val (servidor, compartilhamento, sub) = lido
            // "DOMINIO\usuario" (rede de empresa); o comum é só "usuario".
            val u = v[1].trim()
            val (dom, us) = if (u.contains('\\')) u.substringBefore('\\') to u.substringAfter('\\') else "" to u
            val conta = ContaMidia("", ContaMidia.SMB,
                v[3].trim().ifEmpty { if (sub.isEmpty()) compartilhamento else sub.substringAfterLast('/') },
                servidor, MidiaContas.juntar(compartilhamento, sub), us, v[2], dom)
            testarESalvar(conta, fim) { Smb.listar(it, Smb.inicio(it)) }
        }
    }

    /** Antes de guardar, abre a pasta: senha errada ou nome errado aparecem na hora, não depois. */
    private fun testarESalvar(c: ContaMidia, fim: (String?) -> Unit, testar: (ContaMidia) -> Unit) {
        rede.execute {
            val r = runCatching { testar(c) }
            val salva = if (r.isSuccess) MidiaArmazem.salvar(this, c) else null
            tela.post {
                if (isDestroyed) return@post
                when {
                    r.isFailure -> fim(erroLegivel(r.exceptionOrNull()))
                    salva == null -> fim(getString(R.string.rede_erro_cheio))
                    else -> { fim(null); desenharRaiz(getString(R.string.rede_salva, salva.nome), salva.id) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- adicionar: FTP, FTPS, SFTP e NFS (14/11/2026)

    /** Um formulário só para os quatro: o começo do endereço (ftp://, ftps://, sftp://, nfs://) diz o protocolo. */
    private fun formularioArquivos() {
        formulario(getString(R.string.rede_acao_arquivos), getString(R.string.rede_arq_explica), listOf(
            Campo(getString(R.string.rede_arq_endereco), endereco = true),
            Campo(getString(R.string.rede_arq_usuario)),
            Campo(getString(R.string.rede_senha), senha = true),
            Campo(getString(R.string.rede_nome_opcional)),
        ), getString(R.string.rede_testar_salvar)) { v, fim ->
            val e = MidiaContas.lerEnderecoArquivos(v[0])
            if (e == null) { fim(getString(R.string.rede_arq_endereco_erro)); return@formulario }
            val u = v[1].trim()
            if (e.tipo == ContaMidia.SFTP && u.isEmpty()) { fim(getString(R.string.rede_jf_usuario_falta)); return@formulario }
            val nome = v[3].trim().ifEmpty { e.pasta.substringAfterLast('/').ifEmpty { e.servidor.substringBefore(':') } }
            val conta = ContaMidia("", e.tipo, nome, e.servidor, e.pasta, u, if (e.tipo == ContaMidia.NFS) "" else v[2])
            if (e.tipo == ContaMidia.SFTP) testarESalvarComo(conta, fim) { Sftp.conhecer(it) }
            else testarESalvar(conta, fim) { Pastas.listar(it.copy(id = "teste"), Pastas.inicio(it)) }
        }
    }

    /** Como [testarESalvar], mas o teste devolve a conta completada (SFTP: impressão da chave e pasta inicial). */
    private fun testarESalvarComo(c: ContaMidia, fim: (String?) -> Unit, testar: (ContaMidia) -> ContaMidia) {
        rede.execute {
            val r = runCatching { testar(c) }
            val salva = r.getOrNull()?.let { MidiaArmazem.salvar(this, it) }
            tela.post {
                if (isDestroyed) return@post
                when {
                    r.isFailure -> fim(erroLegivel(r.exceptionOrNull()))
                    salva == null -> fim(getString(R.string.rede_erro_cheio))
                    else -> { fim(null); desenharRaiz(getString(R.string.rede_salva, salva.nome), salva.id) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- pendrive e disco USB (14/11/2026)

    /** Volumes removíveis montados agora (pendrive, disco no hub USB). Android 11+: a TV é 14. */
    private fun volumesUsb(): List<Volume> {
        if (android.os.Build.VERSION.SDK_INT < 30) return emptyList()
        val sm = getSystemService(android.os.storage.StorageManager::class.java) ?: return emptyList()
        return runCatching {
            sm.storageVolumes.filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }
                .mapNotNull { v -> v.directory?.let { Volume(v.getDescription(this).ifBlank { it.name }, it) } }
        }.getOrDefault(emptyList())
    }

    /** Pasta do pendrive. Na 1ª vez pede ao nosso shell o "acesso a todos os arquivos" (fora da thread da tela). */
    private fun listarLocal(raiz: java.io.File, caminho: String): List<Upnp.Entrada> {
        if (!PainelArquivos.garantirAcesso(applicationContext)) throw SecurityException("SEM_ACESSO_ARQUIVOS")
        require(Pastas.caminhoValido(caminho)) { "caminho inválido" }
        return PastaLocal(raiz).use { p ->
            Pastas.entradas(p.listar(caminho), caminho) { sub -> android.net.Uri.fromFile(java.io.File(raiz, sub)).toString() }
        }
    }

    // ---------------------------------------------------------------- adicionar: Jellyfin e Emby

    /** Identificador deste aparelho para Jellyfin/Emby/Plex (resumo do ANDROID_ID, o mesmo do painel). */
    private fun aparelho(): String = runCatching { PainelVizinhos.idDaTv(this) }.getOrDefault("tv")

    private fun formularioJellyfin(achado: Jellyfin.Achado?, emby: Boolean) {
        val marca = if (emby) "Emby" else "Jellyfin"
        val campos = ArrayList<Campo>()
        if (achado == null) campos += Campo(getString(R.string.rede_jf_endereco), endereco = true)
        campos += Campo(getString(R.string.rede_jf_usuario)); campos += Campo(getString(R.string.rede_senha), senha = true)
        formulario(achado?.nome ?: marca, getString(R.string.rede_jf_explica, marca), campos, getString(R.string.rede_entrar)) { v, fim ->
            val servidor = achado?.endereco ?: MidiaContas.lerEnderecoJellyfin(v[0])
            if (servidor == null) { fim(getString(R.string.rede_endereco_erro)); return@formulario }
            val off = if (achado == null) 1 else 0
            val u = v[off].trim(); val s = v[off + 1]
            if (u.isEmpty()) { fim(getString(R.string.rede_jf_usuario_falta)); return@formulario }
            rede.execute {
                val r = runCatching { Jellyfin.entrar(servidor, u, s, aparelho(), achado?.nome, if (emby) ContaMidia.EMBY else ContaMidia.JELLYFIN) }
                val salva = r.getOrNull()?.let { MidiaArmazem.salvar(this, it) }
                tela.post {
                    if (isDestroyed) return@post
                    when {
                        r.isFailure -> fim(erroLegivel(r.exceptionOrNull()))
                        salva == null -> fim(getString(R.string.rede_erro_cheio))
                        else -> { fim(null); desenharRaiz(getString(R.string.rede_salva, salva.nome), salva.id) }
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- adicionar: WebDAV / Nextcloud e link

    private fun formularioWebDav() {
        formulario(getString(R.string.rede_acao_webdav), getString(R.string.rede_dav_explica), listOf(
            Campo(getString(R.string.rede_dav_endereco), endereco = true),
            Campo(getString(R.string.rede_jf_usuario)),
            Campo(getString(R.string.rede_senha), senha = true),
            Campo(getString(R.string.rede_nome_opcional)),
        ), getString(R.string.rede_testar_salvar)) { v, fim ->
            val base = MidiaContas.lerEnderecoWebDav(v[0])
            if (base == null) { fim(getString(R.string.rede_endereco_erro)); return@formulario }
            val nome = v[3].trim().ifEmpty { base.substringAfter("://").substringBefore('/') }
            testarESalvar(ContaMidia("", ContaMidia.WEBDAV, nome, base, usuario = v[1].trim(), senha = v[2]), fim) {
                WebDav.listar(it.copy(id = "teste"), "")
            }
        }
    }

    private fun formularioLink() {
        formulario(getString(R.string.rede_acao_link), getString(R.string.rede_link_explica), listOf(
            Campo(getString(R.string.rede_link_endereco), endereco = true),
            Campo(getString(R.string.rede_nome_opcional)),
        ), getString(R.string.rede_salvar)) { v, fim ->
            val url = MidiaContas.lerLink(v[0])
            if (url == null) { fim(getString(R.string.rede_link_erro)); return@formulario }
            rede.execute {
                val lista = FontesMidia.linkEhLista(url)
                val c = ContaMidia("", ContaMidia.LINK, v[1].trim().ifEmpty { MidiaContas.nomeDoLink(url) }, url, pasta = if (lista) LISTA else "")
                val salva = MidiaArmazem.salvar(this, c)
                tela.post {
                    if (isDestroyed) return@post
                    if (salva == null) fim(getString(R.string.rede_erro_cheio))
                    else { fim(null); desenharRaiz(getString(R.string.rede_salva, salva.nome), salva.id) }
                }
            }
        }
    }

    // ---------------------------------------------------------------- Plex: login por código

    /**
     * Mostra o código de 4 letras (e um QR com o código já preenchido) e espera a pessoa confirmar no plex.tv.
     * Depois busca os servidores da conta, testa o endereço de cada um e guarda uma conta por servidor.
     */
    private fun loginPlex() {
        cancelarPlex?.set(true)
        val cancelar = AtomicBoolean(false).also { cancelarPlex = it }
        lateinit var codigoTv: TextView
        lateinit var estado: TextView
        lateinit var qr: ImageView
        lateinit var deNovo: TextView
        abrirPainel(720) { p ->
            p.addView(Estilo.texto(this, Estilo.Papel.TITULO, getString(R.string.rede_plex_titulo)).apply { textSize = 22f })
            val corpo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(0, dp(Estilo.E2), 0, 0) }
            val esquerda = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            esquerda.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.rede_plex_explica)).apply { setLineSpacing(0f, 1.25f); textSize = 15f })
            codigoTv = Estilo.texto(this, Estilo.Papel.DISPLAY, "····").apply { letterSpacing = 0.25f; setPaddingRelative(0, dp(Estilo.E2), 0, dp(Estilo.E1)) }
            esquerda.addView(codigoTv)
            estado = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.rede_plex_pedindo))
            esquerda.addView(estado)
            corpo.addView(esquerda, LinearLayout.LayoutParams(0, -2, 1f))
            qr = ImageView(this).apply { visibility = View.INVISIBLE; background = Estilo.fundo(this@MidiaRedeActivity, Color.WHITE, Estilo.RAIO_PEQUENO) ; setPaddingRelative(dp(6), dp(6), dp(6), dp(6)) }
            corpo.addView(qr, LinearLayout.LayoutParams(dp(168), dp(168)).apply { marginStart = dp(Estilo.E3) })
            p.addView(corpo)
            deNovo = Estilo.botao(this, getString(R.string.rede_plex_outro), primario = true).apply { visibility = View.GONE; setOnClickListener { loginPlex() } }
            val fechar = Estilo.botao(this, getString(R.string.rede_cancelar), primario = false).apply { setOnClickListener { fecharPainel() } }
            p.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPaddingRelative(0, dp(Estilo.E3), 0, 0)
                addView(fechar); addView(deNovo, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
            })
            fechar.requestFocus()
        }
        val id = aparelho()
        Fundo.rodar("plex-login") {
            fun naTela(f: () -> Unit) = tela.post { if (!isDestroyed && !cancelar.get()) f() }
            val cod = runCatching { Plex.pedirCodigo(id) }.getOrElse { e -> naTela { estado.text = erroLegivel(e); deNovo.visibility = View.VISIBLE }; return@rodar }
            val bmp = runCatching { QrCodigo.bitmap(Plex.paginaDoCodigo(id, cod.codigo), dp(156)) }.getOrNull()
            naTela {
                codigoTv.text = cod.codigo
                estado.text = getString(R.string.rede_plex_esperando)
                if (bmp != null) { qr.setImageBitmap(bmp); qr.visibility = View.VISIBLE }
            }
            // Pergunta a cada 2 s até o código vencer (o plex.tv dá ~15 min; paramos em 10).
            val fim = System.currentTimeMillis() + minOf(cod.expiraEmS, 600) * 1000L
            var token: String? = null
            while (!cancelar.get() && System.currentTimeMillis() < fim) {
                Thread.sleep(2000)
                token = runCatching { Plex.conferirCodigo(id, cod.id) }.getOrNull()
                if (token != null) break
            }
            if (cancelar.get()) return@rodar
            if (token == null) { naTela { estado.text = getString(R.string.rede_plex_venceu); codigoTv.alpha = 0.3f; deNovo.visibility = View.VISIBLE; deNovo.requestFocus() }; return@rodar }
            naTela { estado.text = getString(R.string.rede_plex_buscando) }
            val r = runCatching {
                val servidores = Plex.servidores(id, token)
                val atuais = MidiaArmazem.contas(this)
                servidores.mapNotNull { s ->
                    val end = Plex.escolherEndereco(s, id) ?: return@mapNotNull null
                    val velha = atuais.firstOrNull { it.tipo == ContaMidia.PLEX && it.usuarioId == s.id }
                    MidiaArmazem.salvar(this, Plex.conta(s, end).copy(id = velha?.id.orEmpty()))
                } to servidores.size
            }
            naTela {
                val (salvas, total) = r.getOrElse { e -> estado.text = erroLegivel(e); deNovo.visibility = View.VISIBLE; return@naTela }
                when {
                    total == 0 -> { estado.text = getString(R.string.rede_plex_sem_servidor); deNovo.visibility = View.VISIBLE }
                    salvas.isEmpty() -> { estado.text = getString(R.string.rede_plex_inacessivel); deNovo.visibility = View.VISIBLE }
                    else -> {
                        fecharPainel()
                        desenharRaiz(if (salvas.size == 1) getString(R.string.rede_salva, salvas[0].nome) else getString(R.string.rede_plex_varios, salvas.size), salvas.firstOrNull()?.id)
                        if (salvas.size == 1) entrar(Origem.PlexO(salvas[0]), "", salvas[0].nome)
                    }
                }
            }
        }
    }

    // ---------------------------------------------------------------- nuvem: OneDrive e Dropbox (14/11/2026, Nuvem.kt)

    private fun nuvemPrefs() = getSharedPreferences("nuvem", Context.MODE_PRIVATE)

    /** Guarda a conta da nuvem (troca a do mesmo usuário no mesmo serviço) e já abre a pasta. Chamar fora da tela. */
    private fun salvarNuvem(tipo: String, servico: String, usuario: String, appId: String, renovacao: String, estado: TextView, segredo: String = "") {
        val velha = MidiaArmazem.contas(this).firstOrNull { it.tipo == tipo && it.usuario == usuario }
        val salva = MidiaArmazem.salvar(this, ContaMidia(velha?.id.orEmpty(), tipo, servico, servico, "", usuario, segredo, "", renovacao, appId))
        salva?.let { Nuvem.esquecer(it.id) }
        tela.post {
            if (isDestroyed) return@post
            if (salva == null) { estado.text = getString(R.string.rede_erro_cheio); return@post }
            fecharPainel()
            desenharRaiz(getString(R.string.rede_salva, salva.nome), salva.id)
            entrar(Origem.Pasta(salva), "", salva.nome)
        }
    }

    /** Passo 1 do OneDrive: o id do app que o dono registrou na Microsoft (fica lembrado para a próxima conta). */
    private fun loginOneDrive() {
        formulario(getString(R.string.rede_od_titulo), getString(R.string.rede_od_explica),
            listOf(Campo(getString(R.string.rede_od_id), inicial = nuvemPrefs().getString("onedrive_id", "").orEmpty())),
            getString(R.string.rede_nuvem_seguir)) { v, fim ->
            val id = v[0].trim()
            if (!Nuvem.idMicrosoftValido(id)) { fim(getString(R.string.rede_od_id_erro)); return@formulario }
            nuvemPrefs().edit().putString("onedrive_id", id).apply()
            fim(null)
            codigoOneDrive(id)
        }
    }

    /** Passo 2: código na tela (o mesmo jeito do login do Google da TV), a pessoa digita em microsoft.com/link. */
    private fun codigoOneDrive(appId: String) {
        cancelarPlex?.set(true)
        val cancelar = AtomicBoolean(false).also { cancelarPlex = it }
        lateinit var codigoTv: TextView
        lateinit var estado: TextView
        abrirPainel(720) { p ->
            p.addView(Estilo.texto(this, Estilo.Papel.TITULO, "OneDrive").apply { textSize = 22f })
            val corpo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(0, dp(Estilo.E2), 0, 0) }
            val esquerda = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            esquerda.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.rede_od_codigo_explica)).apply { setLineSpacing(0f, 1.25f); textSize = 15f })
            codigoTv = Estilo.texto(this, Estilo.Papel.DISPLAY, "····").apply { letterSpacing = 0.2f; setPaddingRelative(0, dp(Estilo.E2), 0, dp(Estilo.E1)) }
            esquerda.addView(codigoTv)
            estado = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.rede_nuvem_pedindo))
            esquerda.addView(estado)
            corpo.addView(esquerda, LinearLayout.LayoutParams(0, -2, 1f))
            runCatching { QrCodigo.bitmap(Nuvem.MS_PAGINA, dp(156)) }.getOrNull()?.let { bmp ->
                corpo.addView(ImageView(this).apply { setImageBitmap(bmp); background = Estilo.fundo(this@MidiaRedeActivity, Color.WHITE, Estilo.RAIO_PEQUENO); setPaddingRelative(dp(6), dp(6), dp(6), dp(6)) },
                    LinearLayout.LayoutParams(dp(168), dp(168)).apply { marginStart = dp(Estilo.E3) })
            }
            p.addView(corpo)
            val fechar = Estilo.botao(this, getString(R.string.rede_cancelar), primario = false).apply { setOnClickListener { fecharPainel() } }
            p.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPaddingRelative(0, dp(Estilo.E3), 0, 0); addView(fechar) })
            fechar.requestFocus()
        }
        Fundo.rodar("onedrive-login") {
            fun naTela(f: () -> Unit) = tela.post { if (!isDestroyed && !cancelar.get()) f() }
            val r = runCatching { Nuvem.postarForm("${Nuvem.MS_LOGIN}/devicecode", mapOf("client_id" to appId, "scope" to Nuvem.MS_ESCOPOS)) }
            val cod = r.mapCatching { if (it.codigo != 200) throw IllegalStateException("app") else Nuvem.lerCodigoTv(it.corpo) }
                .getOrElse { e -> naTela { estado.text = if (r.isSuccess) getString(R.string.rede_od_erro_app) else erroLegivel(e) }; return@rodar }
            naTela { codigoTv.text = cod.usuario; estado.text = getString(R.string.rede_nuvem_esperando) }
            var intervalo = cod.intervaloS
            val fim = System.currentTimeMillis() + minOf(cod.expiraEmS, 900) * 1000L
            while (!cancelar.get() && System.currentTimeMillis() < fim) {
                Thread.sleep(intervalo * 1000L)
                val t = runCatching { Nuvem.postarForm("${Nuvem.MS_LOGIN}/token", mapOf("client_id" to appId, "device_code" to cod.dispositivo,
                    "grant_type" to "urn:ietf:params:oauth:grant-type:device_code")) }.getOrNull() ?: continue
                if (t.codigo != 200) {
                    when (runCatching { org.json.JSONObject(t.corpo).optString("error") }.getOrDefault("")) {
                        "authorization_pending" -> continue
                        "slow_down" -> { intervalo += 5; continue }
                        else -> { naTela { estado.text = getString(R.string.rede_nuvem_recusado); codigoTv.alpha = 0.3f }; return@rodar }
                    }
                }
                val tokens = runCatching { Nuvem.lerTokens(t.corpo) }.getOrNull()
                if (tokens?.renovacao == null) { naTela { estado.text = getString(R.string.rede_od_erro_app) }; return@rodar }
                naTela { estado.text = getString(R.string.rede_nuvem_salvando) }
                // Nome que aparece na lista: o dono do OneDrive (Files.Read basta para ler /me/drive).
                val nome = runCatching {
                    org.json.JSONObject(Nuvem.conferir(Nuvem.get("${Nuvem.GRAPH}/me/drive?\$select=owner", tokens.acesso)))
                        .optJSONObject("owner")?.optJSONObject("user")?.optString("displayName")
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "OneDrive"
                if (!cancelar.get()) salvarNuvem(ContaMidia.ONEDRIVE, "OneDrive", nome, appId, tokens.renovacao, estado)
                return@rodar
            }
            naTela { estado.text = getString(R.string.rede_plex_venceu); codigoTv.alpha = 0.3f }
        }
    }

    /** Passo 1 do Dropbox: a "App key" do app que o dono criou no App Console. */
    private fun loginDropbox() {
        formulario("Dropbox", getString(R.string.rede_dbx_explica),
            listOf(Campo(getString(R.string.rede_dbx_chave), inicial = nuvemPrefs().getString("dropbox_chave", "").orEmpty())),
            getString(R.string.rede_nuvem_seguir)) { v, fim ->
            val chave = v[0].trim()
            if (!Nuvem.chaveDropboxValida(chave)) { fim(getString(R.string.rede_dbx_chave_erro)); return@formulario }
            nuvemPrefs().edit().putString("dropbox_chave", chave).apply()
            fim(null)
            codigoDropbox(chave)
        }
    }

    /** Passo 2: QR para o celular autorizar; o Dropbox mostra um código, que a pessoa digita aqui (PKCE, sem segredo). */
    private fun codigoDropbox(chave: String) {
        cancelarPlex?.set(true)
        val cancelar = AtomicBoolean(false).also { cancelarPlex = it }
        val verificador = Nuvem.novoVerificador()
        val pagina = Nuvem.paginaDropbox(chave, Nuvem.desafio(verificador))
        lateinit var campo: EditText
        lateinit var estado: TextView
        lateinit var ok: TextView
        abrirPainel(760) { p ->
            p.addView(Estilo.texto(this, Estilo.Papel.TITULO, "Dropbox").apply { textSize = 22f })
            val corpo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(0, dp(Estilo.E2), 0, 0) }
            val esquerda = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            esquerda.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.rede_dbx_codigo_explica)).apply { setLineSpacing(0f, 1.25f); textSize = 15f })
            campo = campoTexto(Campo(getString(R.string.rede_dbx_codigo)))
            esquerda.addView(campo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
            estado = Estilo.texto(this, Estilo.Papel.LEGENDA, "").apply { setPaddingRelative(0, dp(Estilo.E1), 0, 0) }
            esquerda.addView(estado)
            corpo.addView(esquerda, LinearLayout.LayoutParams(0, -2, 1f))
            runCatching { QrCodigo.bitmap(pagina, dp(156)) }.getOrNull()?.let { bmp ->
                corpo.addView(ImageView(this).apply { setImageBitmap(bmp); background = Estilo.fundo(this@MidiaRedeActivity, Color.WHITE, Estilo.RAIO_PEQUENO); setPaddingRelative(dp(6), dp(6), dp(6), dp(6)) },
                    LinearLayout.LayoutParams(dp(168), dp(168)).apply { marginStart = dp(Estilo.E3) })
            }
            p.addView(corpo)
            val fechar = Estilo.botao(this, getString(R.string.rede_cancelar), primario = false).apply { setOnClickListener { fecharPainel() } }
            ok = Estilo.botao(this, getString(R.string.rede_nuvem_entrar), primario = true)
            p.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPaddingRelative(0, dp(Estilo.E3), 0, 0)
                addView(fechar); addView(ok, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
            })
            ok.id = View.generateViewId(); campo.nextFocusDownId = ok.id
            campo.requestFocus()
        }
        ok.setOnClickListener {
            val codigo = campo.text.toString().trim()
            if (codigo.length < 8 || codigo.any { it.isWhitespace() }) { estado.text = getString(R.string.rede_dbx_codigo_erro); return@setOnClickListener }
            if (!ok.isEnabled) return@setOnClickListener
            ok.isEnabled = false; estado.text = getString(R.string.rede_nuvem_salvando)
            Fundo.rodar("dropbox-login") {
                fun naTela(f: () -> Unit) = tela.post { if (!isDestroyed && !cancelar.get()) f() }
                val tokens = runCatching {
                    val r = Nuvem.postarForm(Nuvem.DBX_TOKEN, mapOf("code" to codigo, "grant_type" to "authorization_code",
                        "client_id" to chave, "code_verifier" to verificador))
                    if (r.codigo != 200) throw IllegalStateException("recusado")
                    Nuvem.lerTokens(r.corpo)
                }.getOrNull()
                if (tokens?.renovacao == null) { naTela { estado.text = getString(R.string.rede_nuvem_recusado); ok.isEnabled = true }; return@rodar }
                val nome = runCatching {
                    org.json.JSONObject(Nuvem.conferir(Nuvem.postarJson("${Nuvem.DBX_API}/2/users/get_current_account", tokens.acesso, "null")))
                        .let { o -> o.optJSONObject("name")?.optString("display_name")?.takeIf { it.isNotBlank() } ?: o.optString("email") }
                }.getOrNull()?.takeIf { it.isNotBlank() } ?: "Dropbox"
                if (!cancelar.get()) salvarNuvem(ContaMidia.DROPBOX, "Dropbox", nome, chave, tokens.renovacao, estado)
            }
        }
    }

    /** Passo 1 do Box: Client ID e Client Secret do app que o dono criou no Developer Console (o ID fica lembrado). */
    private fun loginBox() {
        formulario("Box", getString(R.string.rede_box_explica, Nuvem.RETORNO),
            listOf(Campo(getString(R.string.rede_box_id), inicial = nuvemPrefs().getString("box_id", "").orEmpty()),
                Campo(getString(R.string.rede_box_segredo), senha = true)),
            getString(R.string.rede_nuvem_seguir)) { v, fim ->
            val id = v[0].trim(); val segredo = v[1].trim()
            if (!Nuvem.credencialBoxValida(id) || !Nuvem.credencialBoxValida(segredo)) { fim(getString(R.string.rede_box_erro)); return@formulario }
            val ip = PainelWeb.ipsDaRede().minOrNull() ?: run { fim(getString(R.string.rede_volta_sem_rede)); return@formulario }
            nuvemPrefs().edit().putString("box_id", id).apply()
            fim(null)
            val numero = RetornoNuvem.novoNumero()
            codigoVolta("Box", Nuvem.paginaBox(id, Nuvem.estadoRetorno(numero, ip, RetornoNuvem.PORTA)), numero, getString(R.string.rede_volta_explica, "Box"),
                trocar = { codigo -> Nuvem.postarForm(Nuvem.BOX_TOKEN, mapOf("grant_type" to "authorization_code", "code" to codigo,
                    "client_id" to id, "client_secret" to segredo)) },
                nome = { acesso -> org.json.JSONObject(Nuvem.conferir(Nuvem.get("${Nuvem.BOX_API}/users/me?fields=name,login", acesso)))
                    .let { o -> o.optString("name").takeIf { it.isNotBlank() } ?: o.optString("login") } },
                tipo = ContaMidia.BOX, appId = id, segredo = segredo)
        }
    }

    /**
     * Google Drive (por último, pedido do dono): o login de TV do Google só deixa ver arquivos que o próprio app criou
     * (drive.file); para ler os vídeos que já estão no Drive é drive.readonly, que só sai pelo login no navegador.
     * Então é igual ao Box: um cliente "Aplicativo da Web" no Google Cloud do dono, e o celular volta sozinho para a TV.
     */
    private fun loginDrive() {
        formulario("Google Drive", getString(R.string.rede_gd_explica, Nuvem.RETORNO),
            listOf(Campo(getString(R.string.rede_gd_id), inicial = nuvemPrefs().getString("gdrive_id", "").orEmpty()),
                Campo(getString(R.string.rede_gd_segredo), senha = true)),
            getString(R.string.rede_nuvem_seguir)) { v, fim ->
            val id = v[0].trim(); val segredo = v[1].trim()
            if (!Nuvem.clienteGoogleValido(id) || !Nuvem.segredoGoogleValido(segredo)) { fim(getString(R.string.rede_gd_erro)); return@formulario }
            val ip = PainelWeb.ipsDaRede().minOrNull() ?: run { fim(getString(R.string.rede_volta_sem_rede)); return@formulario }
            nuvemPrefs().edit().putString("gdrive_id", id).apply()
            fim(null)
            val numero = RetornoNuvem.novoNumero()
            val verificador = Nuvem.novoVerificador()
            codigoVolta("Google Drive", Nuvem.paginaDrive(id, Nuvem.desafio(verificador), Nuvem.estadoRetorno(numero, ip, RetornoNuvem.PORTA)), numero,
                getString(R.string.rede_volta_explica, "Google") + " " + getString(R.string.rede_gd_aviso),
                trocar = { codigo -> Nuvem.postarForm(Nuvem.GOOGLE_TOKEN, mapOf("grant_type" to "authorization_code", "code" to codigo,
                    "client_id" to id, "client_secret" to segredo, "redirect_uri" to Nuvem.RETORNO, "code_verifier" to verificador)) },
                nome = { acesso -> org.json.JSONObject(Nuvem.conferir(Nuvem.get("${Nuvem.DRIVE_API}/about?fields=user(displayName,emailAddress)", acesso)))
                    .optJSONObject("user")?.let { u -> u.optString("displayName").takeIf { it.isNotBlank() } ?: u.optString("emailAddress") }.orEmpty() },
                tipo = ContaMidia.GDRIVE, appId = id, segredo = segredo)
        }
    }

    /**
     * Passo 2 do Box e do Drive: QR para o celular (na mesma rede da TV) entrar e permitir; o serviço devolve o celular
     * à TV pela página volta.html (RetornoNuvem) e a TV troca o código pelo token sozinha. Nada para digitar.
     */
    private fun codigoVolta(servico: String, pagina: String, numero: String, explica: String, trocar: (String) -> Nuvem.Resposta,
                            nome: (String) -> String, tipo: String, appId: String, segredo: String) {
        cancelarPlex?.set(true)
        val cancelar = AtomicBoolean(false).also { cancelarPlex = it }
        lateinit var estado: TextView
        abrirPainel(720) { p ->
            p.addView(Estilo.texto(this, Estilo.Papel.TITULO, servico).apply { textSize = 22f })
            val corpo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(0, dp(Estilo.E2), 0, 0) }
            val esquerda = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            esquerda.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, explica).apply { setLineSpacing(0f, 1.25f); textSize = 15f })
            estado = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.rede_nuvem_esperando)).apply { setPaddingRelative(0, dp(Estilo.E2), 0, 0) }
            esquerda.addView(estado)
            corpo.addView(esquerda, LinearLayout.LayoutParams(0, -2, 1f))
            runCatching { QrCodigo.bitmap(pagina, dp(156)) }.getOrNull()?.let { bmp ->
                corpo.addView(ImageView(this).apply { setImageBitmap(bmp); background = Estilo.fundo(this@MidiaRedeActivity, Color.WHITE, Estilo.RAIO_PEQUENO); setPaddingRelative(dp(6), dp(6), dp(6), dp(6)) },
                    LinearLayout.LayoutParams(dp(168), dp(168)).apply { marginStart = dp(Estilo.E3) })
            }
            p.addView(corpo)
            // Fechar o painel (Cancelar ou Voltar) liga cancelarPlex: o porto fecha em até 1 s.
            val fechar = Estilo.botao(this, getString(R.string.rede_cancelar), primario = false).apply { setOnClickListener { fecharPainel() } }
            p.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPaddingRelative(0, dp(Estilo.E3), 0, 0); addView(fechar) })
            fechar.requestFocus()
        }
        Fundo.rodar("nuvem-volta") {
            fun naTela(f: () -> Unit) = tela.post { if (!isDestroyed && !cancelar.get()) f() }
            var ocupado = false
            val chegou = RetornoNuvem.esperar(numero, System.currentTimeMillis() + 10 * 60_000L, cancelar) { ocupado = true }
                ?: run {
                    naTela { estado.text = if (ocupado) getString(R.string.rede_volta_porta, RetornoNuvem.PORTA) else getString(R.string.rede_plex_venceu) }
                    return@rodar
                }
            val codigo = (chegou as? RetornoNuvem.Chegada.Codigo)?.codigo
                ?: run { naTela { estado.text = getString(R.string.rede_nuvem_recusado) }; return@rodar }
            naTela { estado.text = getString(R.string.rede_nuvem_salvando) }
            val tokens = runCatching {
                val r = trocar(codigo)
                if (r.codigo != 200) throw IllegalStateException("recusado")
                Nuvem.lerTokens(r.corpo)
            }.getOrNull()
            if (tokens?.renovacao == null) { naTela { estado.text = getString(R.string.rede_nuvem_recusado) }; return@rodar }
            val quem = runCatching { nome(tokens.acesso) }.getOrNull()?.takeIf { it.isNotBlank() } ?: servico
            if (!cancelar.get()) salvarNuvem(tipo, servico, quem, appId, tokens.renovacao, estado, segredo)
        }
    }

    // ---------------------------------------------------------------- foto

    /**
     * Lê a foto com teto de 30 MB (o mesmo do SMB) e só se a resposta for 2xx. Antes lia tudo sem limite: um servidor
     * da rede mandando um arquivo enorme derrubava o launcher por falta de memória (auditoria 10/11/2026, player-10).
     */
    private fun lerFotoComTeto(c: HttpURLConnection, max: Int = 30 * 1024 * 1024): ByteArray? {
        try {
            if (c.responseCode !in 200..299) return null
            if (c.contentLengthLong > max) return null
            c.inputStream.use { ent ->
                val saida = java.io.ByteArrayOutputStream()
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = ent.read(buf)
                    if (n < 0) break
                    if (saida.size() + n > max) return null
                    saida.write(buf, 0, n)
                }
                return saida.toByteArray()
            }
        } finally { c.disconnect() }
    }

    private fun mostrarFoto(origem: Origem?, e: Upnp.Entrada) {
        aviso.text = getString(R.string.rede_abrindo)
        rede.execute {
            val bmp = runCatching {
                val bytes = when (origem) {
                    is Origem.Pasta -> Pastas.lerTudo(origem.c, e.id)
                    is Origem.Local -> PastaLocal(origem.raiz).use { Pastas.lerTudo(it, e.id) }
                    is Origem.Jelly -> (URL("${origem.c.servidor}/Items/${e.id}/Images/Primary?maxWidth=1920").openConnection() as HttpURLConnection).run {
                        connectTimeout = 4000; readTimeout = 15000
                        setRequestProperty("Authorization", Jellyfin.cabecalho(aparelho(), origem.c.token))
                        lerFotoComTeto(this)
                    }
                    else -> {
                        // Plex e WebDAV: o endereço de verdade (com token ou senha no cabeçalho) sai do FontesMidia.
                        val t = FontesMidia.resolver(this, e.url ?: return@runCatching null) ?: return@runCatching null
                        (URL(t.url).openConnection() as HttpURLConnection).run {
                            connectTimeout = 4000; readTimeout = 15000; instanceFollowRedirects = t.cabecalhos.isEmpty()
                            t.cabecalhos.forEach { (k, v) -> setRequestProperty(k, v) }
                            lerFotoComTeto(this)
                        }
                    }
                }
                reduzir(bytes ?: return@runCatching null)
            }.getOrNull()
            tela.post {
                if (isDestroyed) return@post
                aviso.text = (pilha.lastOrNull()?.itens?.size ?: 0).let { resources.getQuantityString(R.plurals.rede_itens, it, it) }
                if (bmp == null) { aviso.text = getString(R.string.rede_erro); return@post }
                foto.setImageBitmap(bmp); foto.visibility = View.VISIBLE
            }
        }
    }

    /** Reduz para caber na tela (1920 px): foto de câmera tem 4000+ px e estouraria a memória do processo. */
    private fun reduzir(bytes: ByteArray): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
        var amostra = 1
        while (o.outWidth / (amostra * 2) >= 1920 || o.outHeight / (amostra * 2) >= 1080) amostra *= 2
        // RGB_565 (metade da memória, como no FotosProtetor): foto de 24 MP fica 3000x2000 = 12 MB, não 24 MB, na TV de 32 bits.
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply {
            inSampleSize = amostra; inPreferredConfig = Bitmap.Config.RGB_565
        })
    }

    // ---------------------------------------------------------------- lista

    /** A linha selecionada com a lista focada vira pílula branca (Estilo.focarLinha); as outras voltam ao normal. */
    private fun pintarFoco() {
        val sel = lista.selectedItemPosition
        for (i in 0 until lista.childCount) {
            val v = lista.getChildAt(i)
            val pos = lista.firstVisiblePosition + i
            if (v is LinearLayout && v.tag == TAG_LINHA) focar(v, lista.hasFocus() && pos == sel)
        }
        atualizarFicha()
    }

    /** Altura dos [n] nomes de grupo do topo, para o 1º item ficar logo abaixo deles. */
    private fun alturaSecoes(n: Int): Int {
        if (n == 0) return 0
        val t = Estilo.secao(this, "X")
        t.measure(View.MeasureSpec.makeMeasureSpec(resources.displayMetrics.widthPixels, View.MeasureSpec.AT_MOST), View.MeasureSpec.UNSPECIFIED)
        return n * (t.measuredHeight + lista.dividerHeight) + lista.paddingTop
    }

    private fun focar(v: LinearLayout, foco: Boolean) {
        Estilo.focarLinha(v, foco)
        (v.getChildAt(0) as? ImageView)?.let { if (foco) it.setColorFilter(Estilo.TEXTO_NO_FOCO) else it.clearColorFilter() }
        v.animate().scaleX(if (foco) Estilo.ESCALA_LINHA else 1f).scaleY(if (foco) Estilo.ESCALA_LINHA else 1f).setDuration(Estilo.FOCO_MS).start()
    }

    private val adaptador = object : BaseAdapter() {
        override fun getCount() = pilha.lastOrNull()?.itens?.size ?: 0
        override fun getItem(p: Int) = pilha.last().itens[p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getViewTypeCount() = 2
        override fun getItemViewType(p: Int) = if (getItem(p) is Secao) 0 else 1
        override fun areAllItemsEnabled() = false
        override fun isEnabled(p: Int) = getItem(p) is Linha   // nome de grupo não recebe foco: o controle pula

        override fun getView(p: Int, velha: View?, pai: ViewGroup): View {
            val item = getItem(p)
            if (item is Secao) {
                val t = (velha as? TextView) ?: Estilo.secao(pai.context, "")
                t.text = item.nome
                return t
            }
            val l = item as Linha
            val linha = (velha as? LinearLayout) ?: novaLinha(pai.context)
            val icone = linha.getChildAt(0) as ImageView
            val textos = linha.getChildAt(1) as LinearLayout
            val t = textos.getChildAt(0) as TextView
            val s = textos.getChildAt(1) as TextView
            val valor = linha.getChildAt(2) as TextView
            // Cor normal guardada de novo a cada uso: a linha reciclada pode ter vindo com a cor do foco.
            for ((tv, cor) in listOf(t to Estilo.TEXTO, s to Estilo.TEXTO_2, valor to Estilo.TEXTO_2)) { tv.setTag(R.id.estilo_cor_normal, cor); tv.setTextColor(cor) }
            icone.setImageDrawable(IconeMidia(l.icone))
            t.text = l.titulo
            s.text = l.sub.orEmpty(); s.visibility = if (l.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
            valor.text = l.valor?.let { "$it  ›" }.orEmpty(); valor.visibility = if (l.valor == null) View.GONE else View.VISIBLE
            focar(linha, lista.hasFocus() && p == lista.selectedItemPosition)
            return linha
        }

        private fun novaLinha(ctx: Context): LinearLayout = LinearLayout(ctx).apply {
            tag = TAG_LINHA
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
            setPaddingRelative(dp(Estilo.E2 + 4), dp(10), dp(Estilo.E2 + 4), dp(10))
            addView(ImageView(ctx), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(Estilo.E2) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(Estilo.texto(ctx, Estilo.Papel.CORPO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(Estilo.texto(ctx, Estilo.Papel.LEGENDA).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPaddingRelative(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Estilo.texto(ctx, Estilo.Papel.CORPO), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
            background = Estilo.superficie(ctx, false)
        }
    }

    private fun dp(v: Int) = Estilo.dp(this, v)

    companion object {
        /** Elevação 3.11: aberta pela busca da HOME; procura este termo no primeiro Jellyfin/Emby/Plex guardado. */
        const val EXTRA_BUSCA = "busca"
        private const val TAG_LINHA = "linha"
        private const val CODIGO_TOCAR = 29
        /** ContaMidia.pasta de um LINK que é lista M3U (OK abre a lista em vez de tocar). */
        private const val LISTA = "lista"
    }
}
