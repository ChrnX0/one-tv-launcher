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
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Mídias da rede (card "Na rede" da HOME). 07/11/2026: DLNA, pasta compartilhada e Jellyfin. 10/11/2026: Plex (login
 * por código), Emby, WebDAV/Nextcloud, link de vídeo ou lista M3U, computadores com pasta compartilhada achados sozinhos,
 * e a tela inteira no sistema de design (Estilo.kt: Manrope, linha que vira pílula branca no foco, ícones de linha).
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
    }
    private enum class Acao { PLEX, JELLYFIN, EMBY, SMB, WEBDAV, LINK, PROCURAR }
    /** "Usar estas fotos no protetor" (ideia 27): a pasta aberta agora, de uma conta SMB ou WebDAV. */
    private class FotosNoProtetor(val origem: FotosRede.Origem, val quantas: Int)
    /** "Buscar" na raiz de um Jellyfin, Emby ou Plex (ideia 29). */
    private class Buscar(val origem: Origem)

    /** O que a lista mostra: um nome de grupo (não focável) ou uma linha. */
    private class Secao(val nome: String)
    private class Linha(val icone: IconeMidia.Tipo, val titulo: String, val sub: String?, val valor: String?, val alvo: Any)

    /** Um nível da navegação: o que está na lista e onde o foco estava (para voltar no mesmo lugar). */
    private class Nivel(val titulo: String, val origem: Origem?, val itens: List<Any>, var foco: Int = -1)

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
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
            clipToPadding = false   // a lista entra 12 dp na margem (folga do foco); sem isso a margem cortava a pílula
        }
        trilha = Estilo.texto(this, Estilo.Papel.SECAO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START; visibility = View.GONE }
        titulo = Estilo.tituloTela(this, getString(R.string.rede_titulo)).apply { setPadding(0, dp(4), 0, 0) }
        aviso = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { setPadding(0, dp(Estilo.E1), 0, dp(Estilo.E2)); textSize = 15f }
        lista = ListView(this).apply {
            divider = null; dividerHeight = dp(6); isVerticalScrollBarEnabled = false; itemsCanFocus = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false; setPadding(dp(18), dp(4), dp(18), dp(4))   // a linha com foco cresce 2%: folga para não cortar
            adapter = adaptador
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ -> abrir(pos) }
            // "Segurar" que não vem do controle (acessibilidade, leitor de tela, MCP): o mesmo remover do segure OK.
            onItemLongClickListener = AdapterView.OnItemLongClickListener { _, _, pos, _ ->
                val conta = pilha.size == 1 && (pilha[0].itens.getOrNull(pos) as? Linha)?.alvo is ContaMidia
                if (conta) confirmarRemover(pos)
                conta
            }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = pintarFoco()
                override fun onNothingSelected(p: AdapterView<*>?) = pintarFoco()
            }
            setOnFocusChangeListener { _, _ -> pintarFoco() }
        }
        dicas = FrameLayout(this).apply { setPadding(0, dp(Estilo.E2), 0, 0) }
        coluna.addView(trilha); coluna.addView(titulo); coluna.addView(aviso)
        // Lista à esquerda e, quando o item tem pôster ou sinopse (Jellyfin, Emby, Plex, DLNA com capa), a ficha à direita.
        ficha = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE; setPadding(dp(Estilo.E2), dp(4), 0, 0) }
        fichaCapa = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_START; adjustViewBounds = true }
        fichaTitulo = Estilo.texto(this, Estilo.Papel.CORPO).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(Estilo.E1), 0, 0) }
        fichaTexto = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { maxLines = 9; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f); setPadding(0, dp(6), 0, 0) }
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
    }

    override fun onDestroy() {
        cancelarPlex?.set(true)
        tela.removeCallbacksAndMessages(null); rede.shutdownNow(); capas.shutdownNow(); super.onDestroy()
    }

    // ---------------------------------------------------------------- raiz: contas + achados + adicionar

    /** Refaz o 1º nível. Lê o disco (um arquivo pequeno). [texto] null = o resumo padrão; [focarConta] = id da conta a focar. */
    private fun desenharRaiz(texto: String? = null, focarConta: String? = null) {
        val contas = runCatching { MidiaArmazem.contas(this) }.getOrDefault(emptyList())
        val itens = ArrayList<Any>()
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
        itens += Linha(IconeMidia.Tipo.LINK, getString(R.string.rede_acao_link), getString(R.string.rede_sub_link), null, Acao.LINK)
        itens += Linha(IconeMidia.Tipo.ATUALIZAR, getString(R.string.rede_acao_procurar), null, null, Acao.PROCURAR)
        val velho = pilha[0]
        // Depois de adicionar, o foco vai para o que acabou de entrar (não para o 1º da lista).
        val novo = focarConta?.let { id -> itens.indexOfFirst { ((it as? Linha)?.alvo as? ContaMidia)?.id == id } } ?: -1
        pilha[0] = Nivel(velho.titulo, null, itens, if (novo >= 0) novo else velho.foco)
        val resumo = when {
            procurando -> getString(R.string.rede_procurando)
            contas.isEmpty() && novos.isEmpty() -> getString(R.string.rede_nenhum)
            else -> (contas.size + novos.size).let { resources.getQuantityString(R.plurals.rede_resumo, it, it) }
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
            ContaMidia.LINK -> IconeMidia.Tipo.LINK to (if (c.pasta == LISTA) getString(R.string.rede_sub_lista, onde) else getString(R.string.rede_sub_video, onde))
            else -> IconeMidia.Tipo.COMPUTADOR to getString(R.string.rede_sub_pasta, onde)
        }
        return Linha(icone, c.nome, sub, null, c)
    }

    private fun procurar() {
        if (procurando) return
        procurando = true
        if (pilha.size == 1) desenharRaiz()
        Thread {
            // O Android descarta pacotes de grupo (multicast) no Wi-Fi para poupar bateria: o "lock" libera enquanto procura.
            val wifi = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val trava = wifi.createMulticastLock("tvlauncher-midia").apply { setReferenceCounted(false) }
            runCatching { trava.acquire() }
            var d: List<Upnp.Servidor> = emptyList(); var j: List<Jellyfin.Achado> = emptyList()
            var p: List<Plex.Achado> = emptyList(); var m: List<Descoberta.Maquina> = emptyList()
            // Os quatro jeitos de achar ao mesmo tempo: a busca inteira leva o tempo do mais lento (~3 s), não a soma.
            val tarefas = listOf(
                Thread { d = runCatching { Upnp.procurar().mapNotNull { Upnp.ficha(it) }.distinctBy { it.pastas }.sortedBy { it.nome.lowercase() } }.getOrDefault(emptyList()) },
                Thread { j = runCatching { Jellyfin.procurarTodos() }.getOrDefault(emptyList()) },
                Thread { p = runCatching { Plex.procurar() }.getOrDefault(emptyList()) },
                Thread { m = runCatching { Descoberta.pastas(applicationContext) }.getOrDefault(emptyList()) },
            )
            tarefas.forEach { it.start() }
            tarefas.forEach { it.join(9000) }
            runCatching { trava.release() }
            tela.post {
                procurando = false
                if (isDestroyed) return@post
                // O Plex também responde ao DLNA quando o "DLNA" dele está ligado: aí ele aparece uma vez só, como Plex.
                dlna = d.filter { s -> p.none { s.pastas.contains(it.endereco.substringAfter("://").substringBefore(':')) && s.nome.contains("Plex", true) } }
                jellyAchados = j; plexAchados = p; maquinas = m
                desenharRaiz()
            }
        }.start()
    }

    // ---------------------------------------------------------------- navegar

    private fun entrar(origem: Origem, id: String, nome: String) {
        if (ocupado) return
        ocupado = true
        aviso.text = getString(R.string.rede_abrindo)
        rede.execute {
            val r = runCatching {
                when (origem) {
                    is Origem.Dlna -> Upnp.navegar(origem.s, id)
                    is Origem.Pasta -> Smb.listar(origem.c, id)
                    is Origem.Jelly -> Jellyfin.listar(origem.c, id, aparelho())
                    is Origem.PlexO -> Plex.listar(origem.c, id, aparelho())
                    is Origem.Dav -> WebDav.listar(origem.c, id)
                    is Origem.Link -> FontesMidia.listarLink(origem.c)
                }
            }
            tela.post {
                ocupado = false
                if (isDestroyed) return@post
                val entradas = r.getOrNull()
                if (entradas == null) { aviso.text = erroLegivel(r.exceptionOrNull()); return@post }
                pilha.last().foco = lista.selectedItemPosition
                val buscar = if (id == "" && (origem is Origem.Jelly || origem is Origem.PlexO))
                    listOf(Linha(IconeMidia.Tipo.LUPA, getString(R.string.rede_buscar), null, null, Buscar(origem))) else emptyList()
                pilha += Nivel(nome, origem, buscar + linhaFotos(origem, id, nome, entradas) + entradas.map { linhaEntrada(it) })
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
            is Origem.Pasta -> origem.c.id to ContaMidia.SMB
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
        pilha[pilha.lastIndex] = Nivel(nivel.titulo, nivel.origem, nivel.itens.toMutableList().also { it[pos] = linhaFotos(f) }, pos)
        desenhar(getString(if (usando) R.string.rede_fotos_paradas else R.string.rede_fotos_usando))
    }

    private fun linhaEntrada(e: Upnp.Entrada): Linha {
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
        // As pastas especiais vêm do servidor com nome fixo em português: aqui ganham o nome no idioma da TV.
        val titulo = when {
            e.pasta && (e.id == Jellyfin.CONTINUAR || e.id == Plex.CONTINUAR) -> getString(R.string.rede_continuar)
            e.pasta && (e.id == Jellyfin.RECENTES || e.id == Plex.RECENTES) -> getString(R.string.rede_recentes)
            else -> e.titulo
        }
        return Linha(icone, titulo, null, null, e)
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
        val c = when (origem) {
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
                else -> entrar(Origem.Pasta(e), Smb.inicio(e), e.nome)
            }
            is Plex.Achado -> loginPlex()
            is Jellyfin.Achado -> formularioJellyfin(e, e.emby)
            is Upnp.Servidor -> entrar(Origem.Dlna(e), "0", e.nome)
            is Descoberta.Maquina -> formularioSmb(e)
            Acao.PLEX -> loginPlex()
            Acao.JELLYFIN -> formularioJellyfin(null, false)
            Acao.EMBY -> formularioJellyfin(null, true)
            Acao.SMB -> formularioSmb(null)
            Acao.WEBDAV -> formularioWebDav()
            Acao.LINK -> formularioLink()
            Acao.PROCURAR -> procurar()
            is FotosNoProtetor -> alternarFotos(pos, e)
            is Buscar -> buscar(e)
            is Upnp.Entrada -> when {
                e.pasta -> entrar(nivel.origem ?: return, e.id, e.titulo)
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
        dicas.addView(if (pilha.size == 1) Estilo.barraDicas(this, "OK" to getString(R.string.rede_dica_abrir),
            getString(R.string.rede_tecla_segurar) to getString(R.string.rede_dica_remover), getString(R.string.rede_tecla_voltar) to getString(R.string.rede_dica_sair))
        else Estilo.barraDicas(this, "OK" to getString(R.string.rede_dica_abrir), getString(R.string.rede_tecla_voltar) to getString(R.string.rede_dica_subir)))
        if (camada.visibility != View.VISIBLE) lista.requestFocus()
        lista.post { pintarFoco() }
    }

    /** Mensagem para a pessoa, sem detalhe técnico (e sem nada da conta). */
    private fun erroLegivel(e: Throwable?): String {
        val nome = generateSequence(e) { it.cause }.map { it.javaClass.simpleName + " " + (it.message ?: "") }.joinToString(" ")
        return when {
            nome.contains("LOGON_FAILURE", true) || nome.contains("ACCESS_DENIED", true) ||
                nome.contains("SecurityException 401") -> getString(R.string.rede_erro_senha)
            nome.contains("BAD_NETWORK_NAME", true) -> getString(R.string.rede_erro_pasta)
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
        return super.dispatchKeyEvent(e)
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
            setPadding(dp(Estilo.E3 + 4), dp(Estilo.E3), dp(Estilo.E3 + 4), dp(Estilo.E3))
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
                setLineSpacing(0f, 1.2f); setPadding(0, dp(Estilo.E1), 0, dp(Estilo.E2))
            })
            val edits = campos.map { f -> campoTexto(f).also { c.addView(it, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(10) }) } }
            val erro = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { setTextColor(Estilo.ERRO); visibility = View.GONE; setPadding(0, dp(4), 0, 0) }
            c.addView(erro)
            val ok = Estilo.botao(this, rotulo, primario = true)
            val cancelar = Estilo.botao(this, getString(R.string.rede_cancelar), primario = false).apply { setOnClickListener { fecharPainel() } }
            c.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(Estilo.E2), 0, 0)
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
        typeface = Estilo.fonte(Estilo.Papel.CORPO.peso)   // o tipo "senha" troca a fonte por monoespaçada: volta a Manrope
        imeOptions = EditorInfo.IME_ACTION_NEXT or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        hint = f.dica; setText(f.inicial)
        setHintTextColor(Estilo.TEXTO_3)
        setPadding(dp(Estilo.E2), dp(12), dp(Estilo.E2), dp(12))
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
                setLineSpacing(0f, 1.2f); setPadding(0, dp(Estilo.E1), 0, dp(Estilo.E3))
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
            val corpo = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(Estilo.E2), 0, 0) }
            val esquerda = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            esquerda.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.rede_plex_explica)).apply { setLineSpacing(0f, 1.25f); textSize = 15f })
            codigoTv = Estilo.texto(this, Estilo.Papel.DISPLAY, "····").apply { letterSpacing = 0.25f; setPadding(0, dp(Estilo.E2), 0, dp(Estilo.E1)) }
            esquerda.addView(codigoTv)
            estado = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.rede_plex_pedindo))
            esquerda.addView(estado)
            corpo.addView(esquerda, LinearLayout.LayoutParams(0, -2, 1f))
            qr = ImageView(this).apply { visibility = View.INVISIBLE; background = Estilo.fundo(this@MidiaRedeActivity, Color.WHITE, Estilo.RAIO_PEQUENO) ; setPadding(dp(6), dp(6), dp(6), dp(6)) }
            corpo.addView(qr, LinearLayout.LayoutParams(dp(168), dp(168)).apply { marginStart = dp(Estilo.E3) })
            p.addView(corpo)
            deNovo = Estilo.botao(this, getString(R.string.rede_plex_outro), primario = true).apply { visibility = View.GONE; setOnClickListener { loginPlex() } }
            val fechar = Estilo.botao(this, getString(R.string.rede_cancelar), primario = false).apply { setOnClickListener { fecharPainel() } }
            p.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; setPadding(0, dp(Estilo.E3), 0, 0)
                addView(fechar); addView(deNovo, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
            })
            fechar.requestFocus()
        }
        val id = aparelho()
        Thread {
            fun naTela(f: () -> Unit) = tela.post { if (!isDestroyed && !cancelar.get()) f() }
            val cod = runCatching { Plex.pedirCodigo(id) }.getOrElse { e -> naTela { estado.text = erroLegivel(e); deNovo.visibility = View.VISIBLE }; return@Thread }
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
            if (cancelar.get()) return@Thread
            if (token == null) { naTela { estado.text = getString(R.string.rede_plex_venceu); codigoTv.alpha = 0.3f; deNovo.visibility = View.VISIBLE; deNovo.requestFocus() }; return@Thread }
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
        }.start()
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
                    is Origem.Pasta -> Smb.lerTudo(origem.c, e.id)
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
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = amostra })
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
            for ((tv, cor) in listOf(t to Estilo.TEXTO, s to Estilo.TEXTO_2, valor to Estilo.ACENTO)) { tv.setTag(R.id.estilo_cor_normal, cor); tv.setTextColor(cor) }
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
            setPadding(dp(Estilo.E2 + 4), dp(10), dp(Estilo.E2 + 4), dp(10))
            addView(ImageView(ctx), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(Estilo.E2) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(Estilo.texto(ctx, Estilo.Papel.CORPO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
                addView(Estilo.texto(ctx, Estilo.Papel.LEGENDA).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(3), 0, 0) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Estilo.texto(ctx, Estilo.Papel.CORPO), LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
            background = Estilo.superficie(ctx, false)
        }
    }

    private fun dp(v: Int) = Estilo.dp(this, v)

    private companion object {
        const val TAG_LINHA = "linha"
        const val CODIGO_TOCAR = 29
        /** ContaMidia.pasta de um LINK que é lista M3U (OK abre a lista em vez de tocar). */
        const val LISTA = "lista"
    }
}
