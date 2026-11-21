package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.graphics.Color
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
import br.paulo.tvlauncher.home.BibliotecaIndice
import br.paulo.tvlauncher.home.BibliotecaUnica
import br.paulo.tvlauncher.home.IconeMidia
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.input.SegurarOk
import br.paulo.tvlauncher.panel.entrar
import br.paulo.tvlauncher.player.AddonInstallDialog
import br.paulo.tvlauncher.player.AddonsProtocolo
import br.paulo.tvlauncher.player.AddonsTv
import br.paulo.tvlauncher.player.Conforto
import br.paulo.tvlauncher.player.ConfortoRegras
import br.paulo.tvlauncher.player.FimDoVideo
import br.paulo.tvlauncher.player.InfoExterna
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.PlayerLogos
import br.paulo.tvlauncher.player.Posicao
import br.paulo.tvlauncher.player.RecommendedAddons
import br.paulo.tvlauncher.player.Vod
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.search.BuscaOutrosApps
import java.util.concurrent.Executors

/**
 * Tela Addons (16/11/2026, estudo do Stremio, decisão do Paulo "Compatível com Stremio, só HTTP"). Mostra o que os addons
 * instalados oferecem e toca no nosso player. Regras em [AddonsProtocolo]; rede e lista em [AddonsTv].
 *
 *   Addons            Buscar · uma linha por catálogo de cada addon
 *   › catálogo        os títulos (OK abre), "Mais" no fim pede a página seguinte
 *   › série           os episódios por temporada (resumo escondido se "Esconder spoiler" estiver ligado)
 *   › fontes          as fontes HTTP que os addons deram (torrent nunca aparece: o protocolo já descarta)
 *
 * Mesmo desenho da tela "Na rede" ([MidiaRedeActivity]): lista à esquerda, pôster e sinopse à direita, VOLTAR sobe.
 * No fim de um episódio, o próximo começa com a contagem das Configurações ([Conforto.contagem]), na mesma fonte
 * ("bingeGroup") quando o addon diz qual é. Processo ":player".
 */
class AddonsActivity : Activity() {
    private class Secao(val nome: String)
    private class Linha(val icone: IconeMidia.Tipo, val titulo: String, val sub: String?, val alvo: Any)

    private object Buscar
    /** Linha "Adicionar add-on" (18/11/2026): QR para o painel ou digitar, em [AddonInstallDialog]. */
    private object Adicionar
    /** "Onde assistir" (18/11/2026): catálogo sem fonte que toque aqui abre a busca do Google TV com o título. */
    private class Onde(val p: BuscaOutrosApps.Pedido)
    private class Mais(val linha: AddonsTv.Linha, val skip: Int, val busca: String?)
    private class ItemDe(val item: AddonsProtocolo.Item, val origem: AddonsProtocolo.Instalado?)
    private class EpisodioDe(val ficha: AddonsProtocolo.Ficha, val v: AddonsProtocolo.Video)
    private class FonteDe(val addon: AddonsProtocolo.Instalado, val f: AddonsProtocolo.Fonte)

    /** Um nível da navegação. [ficha] é a série aberta (para achar o próximo episódio); [video] é o das fontes. */
    private class Nivel(val titulo: String, val itens: MutableList<Any>, var foco: Int = -1,
                        val ficha: AddonsProtocolo.Ficha? = null, val video: AddonsProtocolo.Video? = null,
                        val item: AddonsProtocolo.Item? = null)

    private val tela = Handler(Looper.getMainLooper())
    private val rede = Executors.newSingleThreadExecutor()
    private val pilha = ArrayList<Nivel>()
    private var ocupado = false
    private var semSpoiler = false
    private var posicoes: Map<String, Posicao> = emptyMap()

    private lateinit var trilha: TextView
    private lateinit var titulo: TextView
    private lateinit var aviso: TextView
    private lateinit var lista: ListView
    private lateinit var dicas: FrameLayout
    private lateinit var camada: FrameLayout
    private lateinit var ficha: LinearLayout
    private lateinit var fichaCapa: ImageView
    private lateinit var fichaTitulo: TextView
    private lateinit var fichaTexto: TextView

    // O que está tocando: a série e o episódio (para emendar o próximo) e a fonte (para pedir a mesma "maratona").
    private var tocandoFicha: AddonsProtocolo.Ficha? = null
    private var tocandoVideo: AddonsProtocolo.Video? = null
    private var tocandoGrupo = ""
    private var seguidos = 0
    private var contando: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return
        val raiz = FrameLayout(this).apply { background = Estilo.fundoTela(this@AddonsActivity) }
        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
            clipToPadding = false
        }
        trilha = Estilo.texto(this, Estilo.Papel.SECAO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.START; visibility = View.GONE }
        titulo = Estilo.tituloTela(this, getString(R.string.ad_titulo)).apply { setPaddingRelative(0, dp(4), 0, 0) }
        aviso = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { setPaddingRelative(0, dp(Estilo.E1), 0, dp(Estilo.E2)); textSize = 15f }
        lista = ListView(this).apply {
            divider = null; dividerHeight = dp(6); isVerticalScrollBarEnabled = false; itemsCanFocus = false
            selector = android.graphics.drawable.ColorDrawable(Color.TRANSPARENT)
            clipToPadding = false; setPaddingRelative(dp(18), dp(4), dp(18), dp(4))
            adapter = adaptador
            onItemClickListener = AdapterView.OnItemClickListener { _, _, pos, _ -> abrir(pos) }
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) = pintarFoco()
                override fun onNothingSelected(p: AdapterView<*>?) = pintarFoco()
            }
            setOnFocusChangeListener { _, _ -> pintarFoco() }
        }
        dicas = FrameLayout(this).apply { setPaddingRelative(0, dp(Estilo.E2), 0, 0) }
        coluna.addView(trilha); coluna.addView(titulo); coluna.addView(aviso)
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
        camada = FrameLayout(this).apply { visibility = View.GONE; setBackgroundColor(0xB3000000.toInt()); isClickable = true }
        raiz.addView(camada, FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)
        window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
        Estilo.entrar(coluna)
        desenharRaiz()
        // Lista curada: no máximo 1 pergunta por dia ao servidor; se mudou e a tela ainda está na raiz, redesenha.
        // Thread à parte, não a fila `rede`: o servidor lento não atrasa o 1º catálogo.
        Fundo.rodar("addons-sugeridos") {
            val antes = AddonsTv.sugeridos(this)
            AddonsTv.atualizarSugeridos(this)
            if (AddonsTv.sugeridos(this) != antes) tela.post { if (!isDestroyed && pilha.size == 1) desenharRaiz() }
        }
        // Elevação 7.1: aberta pela busca única com um título que só um addon tem.
        intent.getStringExtra(BibliotecaUnica.EXTRA_BUSCA)?.trim()?.takeIf { it.length >= 2 }?.let { buscar(it) }
    }

    override fun onResume() {
        super.onResume()
        semSpoiler = Conforto.semSpoiler(this)
        posicoes = PlayerArmazem.posicoes(this)
        adaptador.notifyDataSetChanged()
    }

    override fun onStop() { super.onStop(); pararContagem() }

    override fun onDestroy() { tela.removeCallbacksAndMessages(null); rede.shutdownNow(); super.onDestroy() }

    // ---------------------------------------------------------------- níveis

    private fun desenharRaiz() {
        val linhas = AddonsTv.linhas(this)
        val temBusca = AddonsTv.instalados(this).any { a -> a.m.catalogos.any { it.temBusca } }
        val itens = ArrayList<Any>()
        if (temBusca) itens += Linha(IconeMidia.Tipo.LUPA, getString(R.string.ad_buscar), null, Buscar)
        linhas.groupBy { it.addon.base }.values.forEach { grupo ->
            itens += Secao(grupo.first().addon.m.nome)
            grupo.forEach { l -> itens += Linha(IconeMidia.Tipo.PASTA, l.cat.nome.ifBlank { l.cat.id }, tipoLegivel(l.cat.tipo), l) }
        }
        // Lista curada (18/11/2026): o que ainda não está instalado; escolher pede PIN e instala sem digitar.
        val sugeridos = AddonsTv.sugeridos(this)
        // Uma seção por tipo (pedido do Paulo, 18/11: "separar por tipo, como o Stremio"): Filmes e séries, Anime, Legendas...
        RecommendedAddons.grouped(sugeridos.map { it.e }).forEach { (g, entradas) ->
            itens += Secao("${getString(R.string.ad_sug_secao)} · ${AddonsTv.nomeGrupo(this, g)}")
            entradas.forEach { e -> sugeridos.first { it.e == e }.let { s -> itens += Linha(IconeMidia.Tipo.MAIS, s.nome, s.texto.ifBlank { null }, s) } }
        }
        itens += Linha(IconeMidia.Tipo.MAIS, getString(R.string.ad_add_item), null, Adicionar)
        pilha.clear()
        pilha += Nivel(getString(R.string.ad_titulo), itens)
        desenhar(if (AddonsTv.instalados(this).isEmpty()) getString(R.string.ad_vazio) else getString(R.string.ad_resumo))
    }

    private fun tipoLegivel(tipo: String): String = when (tipo) {
        "movie" -> getString(R.string.ad_tipo_filmes)
        "series" -> getString(R.string.ad_tipo_series)
        "tv", "channel" -> getString(R.string.ad_tipo_canais)
        else -> tipo
    }

    /** Roda [trabalho] fora da tela e entrega o resultado em [pronto], se a tela ainda existir. */
    private fun <T> emSegundo(trabalho: () -> T, pronto: (T) -> Unit) {
        if (ocupado || rede.isShutdown) return
        ocupado = true
        aviso.text = getString(R.string.ad_carregando)
        rede.execute {
            val r = runCatching(trabalho).getOrNull()
            tela.post {
                ocupado = false
                if (isDestroyed) return@post
                if (r == null) aviso.text = getString(R.string.ad_falhou) else pronto(r)
            }
        }
    }

    private fun linhaItem(i: AddonsProtocolo.Item, origem: AddonsProtocolo.Instalado?) =
        Linha(if (i.tipo == "series") IconeMidia.Tipo.PASTA else IconeMidia.Tipo.VIDEO, i.nome,
            listOf(i.ano, tipoLegivel(i.tipo)).filter { it.isNotBlank() }.joinToString(" · "), ItemDe(i, origem))

    private fun abrirCatalogo(l: AddonsTv.Linha, skip: Int = 0, busca: String? = null) {
        emSegundo({ AddonsTv.catalogo(l, skip, busca = busca) }) { itens ->
            val linhas = itens.map { linhaItem(it, l.addon) }
            anotarNaBiblioteca(itens, l.addon)
            val mais = if (itens.size >= PAGINA_MINIMA) listOf(Linha(IconeMidia.Tipo.MAIS, getString(R.string.ad_mais), null,
                Mais(l, skip + itens.size, busca))) else emptyList()
            val atual = pilha.lastOrNull()
            if (skip > 0 && atual != null && atual.itens.lastOrNull().let { it is Linha && it.alvo is Mais }) {
                // "Mais": troca a linha "Mais" pela página nova, com o foco no 1º título novo.
                val onde = atual.itens.lastIndex
                atual.itens.removeAt(onde); atual.itens.addAll(linhas + mais); atual.foco = onde
                desenhar(contagem(atual))
                return@emSegundo
            }
            val nome = if (busca != null) "${getString(R.string.ad_buscar)}: $busca" else l.cat.nome.ifBlank { l.cat.id }
            entrarNivel(Nivel(nome, (linhas + mais).toMutableList()), if (itens.isEmpty()) getString(R.string.ad_nada) else null)
        }
    }

    private fun buscar(termo: String) {
        emSegundo({ AddonsTv.buscar(this, termo) }) { achados ->
            val itens = ArrayList<Any>()
            achados.forEach { (l, lista) ->
                itens += Secao("${l.addon.m.nome} · ${tipoLegivel(l.cat.tipo)}")
                lista.forEach { itens += linhaItem(it, l.addon) }
                anotarNaBiblioteca(lista, l.addon)
            }
            entrarNivel(Nivel("${getString(R.string.ad_buscar)}: $termo", itens), if (itens.isEmpty()) getString(R.string.ad_nada) else null)
        }
    }

    /** Elevação 7.1: filmes e séries vistos nos addons entram no índice da busca única (título, ano, id do IMDb). */
    private fun anotarNaBiblioteca(itens: List<AddonsProtocolo.Item>, addon: AddonsProtocolo.Instalado?) {
        val fontes = itens.filter { it.tipo == "movie" || it.tipo == "series" }.map { i ->
            BibliotecaUnica.Fonte(BibliotecaUnica.Origem.ADDON, addon?.m?.nome.orEmpty(), i.nome, i.ano.take(4).toIntOrNull(),
                i.tipo == "series", imdb = i.id.takeIf { InfoExterna.idImdbValido(it) }.orEmpty())
        }
        if (fontes.isEmpty()) return
        val app = applicationContext
        Fundo.rodar("biblioteca") { BibliotecaIndice.anotar(app, fontes) }
    }

    private fun abrirItem(d: ItemDe) {
        // Filme: direto às fontes. O resto (série, canal) passa pela ficha, que diz se há episódios.
        if (d.item.tipo == "movie") { abrirFontes(d.item.nome, d.item.tipo, d.item.id, null, null, d.item); return }
        emSegundo({ AddonsTv.ficha(this, d.item, d.origem) ?: AddonsProtocolo.Ficha(d.item, emptyList(), "") }) { f ->
            if (f.videos.isEmpty()) { abrirFontes(f.item.nome, d.item.tipo, d.item.id, null, null, f.item); return@emSegundo }
            val itens = ArrayList<Any>()
            var temporada = Int.MIN_VALUE
            f.videos.forEach { v ->
                if (v.temporada != temporada) {
                    temporada = v.temporada
                    itens += Secao(if (v.temporada == 0) getString(R.string.ad_especiais) else getString(R.string.ad_temporada, v.temporada))
                }
                itens += Linha(IconeMidia.Tipo.VIDEO, getString(R.string.ad_episodio, v.episodio, v.titulo.ifBlank { "" + v.episodio }), null, EpisodioDe(f, v))
            }
            val item = f.item.copy(poster = f.item.poster.ifEmpty { d.item.poster }, sinopse = f.item.sinopse.ifEmpty { d.item.sinopse })
            // Foco no 1º episódio não terminado (onde a pessoa parou).
            val foco = itens.indexOfFirst { val e = (it as? Linha)?.alvo as? EpisodioDe; e != null && !visto(e.v.id) }
            entrarNivel(Nivel(f.item.nome, itens, foco, ficha = f.copy(item = item), item = item), null)
        }
    }

    private fun chave(videoId: String) = "addon:$videoId"
    private fun comecado(videoId: String) = posicoes[chave(videoId)] != null
    private fun visto(videoId: String): Boolean {
        val p = posicoes[chave(videoId)] ?: return false
        return p.duracao > 0 && p.ms >= p.duracao * 9 / 10
    }

    /** Busca as fontes de [videoId]. Com [tocarGrupo], toca direto a da mesma "maratona" (próximo episódio). */
    private fun abrirFontes(nome: String, tipo: String, videoId: String, serie: AddonsProtocolo.Ficha?, video: AddonsProtocolo.Video?,
                            item: AddonsProtocolo.Item?, tocarGrupo: String? = null) {
        emSegundo({ AddonsTv.fontes(this, tipo, videoId) }) { fontes ->
            if (tocarGrupo != null) {
                AddonsProtocolo.mesmaMaratona(fontes.map { it.second }, tocarGrupo)?.let { f ->
                    val a = fontes.first { it.second == f }.first
                    tocar(FonteDe(a, f), serie, video, nome); return@emSegundo
                }
            }
            val itens: MutableList<Any> = fontes.map { (a, f) ->
                Linha(IconeMidia.Tipo.VIDEO, f.nome.ifBlank { a.m.nome }.replace('\n', ' '),
                    listOf(a.m.nome, f.descricao.replace('\n', ' ')).filter { it.isNotBlank() }.joinToString(" · "), FonteDe(a, f))
            }.toMutableList()
            // Add-on só de catálogo (TMDB, Mubi, Streaming Catalogs...): sem fonte aqui, mas o Google TV mostra em qual app está.
            val nomeOnde = (serie?.item ?: item)?.nome ?: nome
            val anoOnde = (serie?.item ?: item)?.ano?.take(4)?.toIntOrNull()
            BuscaOutrosApps.ondeAssistir(Vod.nomeLimpo(nomeOnde), anoOnde, BuscaActivity.comGoogleTv(this, emptySet()))?.let {
                itens += Linha(IconeMidia.Tipo.LUPA, getString(R.string.onde_assistir_botao), null, Onde(it))
            }
            val titulo = if (video != null) getString(R.string.ad_episodio, video.episodio, video.titulo.ifBlank { nome }) else nome
            entrarNivel(Nivel(titulo, itens, ficha = serie, video = video, item = item ?: serie?.item),
                if (fontes.isEmpty()) getString(R.string.ad_sem_fontes) else getString(R.string.ad_fontes_resumo))
        }
    }

    private fun tocar(d: FonteDe, serie: AddonsProtocolo.Ficha?, video: AddonsProtocolo.Video?, nome: String) {
        pararContagem()
        tocandoFicha = serie; tocandoVideo = video; tocandoGrupo = d.f.grupoMaratona
        val id = video?.id ?: pilha.lastOrNull()?.item?.id ?: return
        val titulo = if (video != null) "$nome · ${getString(R.string.ad_episodio, video.episodio, video.titulo.ifBlank { "" + video.episodio })}" else nome
        VodActivity.abrirAddon(this, CODIGO_TOCAR, d.f.url, titulo, chave(id), video != null, d.f.cabecalhos)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != CODIGO_TOCAR) return
        posicoes = PlayerArmazem.posicoes(this)
        adaptador.notifyDataSetChanged()
        val f = tocandoFicha; val v = tocandoVideo
        if (f == null || v == null) return
        val terminou = resultCode == RESULT_OK && data?.getBooleanExtra(VodActivity.RESULTADO_TERMINOU, false) == true
        val teclou = data?.getBooleanExtra(VodActivity.RESULTADO_TECLA, true) ?: true
        val desligou = data?.getBooleanExtra(VodActivity.RESULTADO_DESLIGOU, false) == true
        seguidos = FimDoVideo.seguidos(seguidos, teclou || !terminou)
        val prox = AddonsProtocolo.proximo(f.videos, v.id)
        when (FimDoVideo.depois(terminou, prox != null, desligou, seguidos, Conforto.contagem(this))) {
            FimDoVideo.Passo.PROXIMO_CONTANDO -> contar(f, prox!!, Conforto.contagem(this))
            FimDoVideo.Passo.PERGUNTAR -> { voltarParaSerie(f, prox!!); aviso.text = getString(R.string.rede_ainda_assistindo, prox.titulo.ifBlank { "" + prox.episodio }) }
            FimDoVideo.Passo.NADA, FimDoVideo.Passo.DESLIGAR -> {}
        }
    }

    /** Fecha o nível das fontes e foca [prox] na lista de episódios da série. */
    private fun voltarParaSerie(f: AddonsProtocolo.Ficha, prox: AddonsProtocolo.Video) {
        while (pilha.size > 1 && pilha.last().video != null) pilha.removeAt(pilha.lastIndex)
        val nivel = pilha.last()
        if (nivel.ficha?.item?.id == f.item.id) nivel.foco = nivel.itens.indexOfFirst { ((it as? Linha)?.alvo as? EpisodioDe)?.v?.id == prox.id }
        desenhar(contagem(nivel))
    }

    /** "Próximo em N s": OK começa já; qualquer outra tecla cancela. */
    private fun contar(f: AddonsProtocolo.Ficha, prox: AddonsProtocolo.Video, resta: Int) {
        if (resta == Conforto.contagem(this)) voltarParaSerie(f, prox)
        val nome = prox.titulo.ifBlank { "" + prox.episodio }
        aviso.text = getString(R.string.rede_proximo_em, nome, resta)
        val r = Runnable { if (resta <= 1) seguir(f, prox) else contar(f, prox, resta - 1) }
        contando = r
        tela.postDelayed(r, 1000)
    }

    private fun seguir(f: AddonsProtocolo.Ficha, prox: AddonsProtocolo.Video) {
        contando = null
        abrirFontes(f.item.nome, f.item.tipo, prox.id, f, prox, f.item, tocarGrupo = tocandoGrupo.takeIf { it.isNotBlank() } ?: "")
    }

    private fun pararContagem() {
        val r = contando ?: return
        tela.removeCallbacks(r); contando = null
        pilha.lastOrNull()?.let { aviso.text = contagem(it) }
    }

    private fun contagem(n: Nivel): String = n.itens.count { it is Linha }.let { resources.getQuantityString(R.plurals.rede_itens, it, it) }

    private fun entrarNivel(n: Nivel, texto: String?) {
        pilha.lastOrNull()?.foco = lista.selectedItemPosition
        pilha += n
        desenhar(texto ?: contagem(n))
    }

    private fun abrir(pos: Int) {
        val nivel = pilha.lastOrNull() ?: return
        val linha = nivel.itens.getOrNull(pos) as? Linha ?: return
        nivel.foco = pos
        when (val a = linha.alvo) {
            Buscar -> pedirBusca()
            Adicionar -> AddonInstallDialog.mostrar(this) { desenharRaiz() }
            is Onde -> BuscaActivity.procurarEm(this, a.p)
            is AddonsTv.Sugerido -> AddonInstallDialog.instalarSugerido(this, a) { desenharRaiz() }
            is AddonsTv.Linha -> abrirCatalogo(a)
            is Mais -> abrirCatalogo(a.linha, a.skip, a.busca)
            is ItemDe -> abrirItem(a)
            is EpisodioDe -> abrirFontes(a.ficha.item.nome, a.ficha.item.tipo, a.v.id, a.ficha, a.v, a.ficha.item)
            is FonteDe -> { seguidos = 0; tocar(a, nivel.ficha, nivel.video, nivel.ficha?.item?.nome ?: nivel.titulo) }
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
        lista.setSelection(maxOf(0, alvo))
        dicas.removeAllViews()
        dicas.addView(Estilo.barraDicas(this, "OK" to getString(R.string.rede_dica_abrir),
            getString(R.string.rede_tecla_voltar) to getString(if (pilha.size > 1) R.string.ad_dica_voltar else R.string.rede_dica_sair)))
        if (camada.visibility != View.VISIBLE) lista.requestFocus()
        lista.post { pintarFoco() }
    }

    // ---------------------------------------------------------------- busca (painel com campo de texto)

    private fun pedirBusca() {
        camada.removeAllViews()
        val cartao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundo(this@AddonsActivity, 0xF2141A24.toInt(), Estilo.RAIO, Estilo.VIDRO_BORDA)
            setPaddingRelative(dp(Estilo.E3 + 4), dp(Estilo.E3), dp(Estilo.E3 + 4), dp(Estilo.E3))
            clipChildren = false; clipToPadding = false
        }
        cartao.addView(Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.ad_buscar)))
        val campo = EditText(this).apply {
            hint = getString(R.string.ad_buscar_dica); isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT; imeOptions = EditorInfo.IME_ACTION_SEARCH
            setTextColor(Estilo.TEXTO); setHintTextColor(Estilo.TEXTO_2); textSize = 20f
        }
        fun ir() {
            val t = campo.text.toString().trim()
            if (t.isEmpty()) return
            fecharPainel(); buscar(t)
        }
        campo.setOnEditorActionListener { _, acao, _ -> if (acao == EditorInfo.IME_ACTION_SEARCH || acao == EditorInfo.IME_ACTION_DONE) { ir(); true } else false }
        cartao.addView(campo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
        val botao = Estilo.botao(this, getString(R.string.ad_buscar)).apply { setOnClickListener { ir() } }
        cartao.addView(botao, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E2) })
        camada.addView(cartao, FrameLayout.LayoutParams(dp(560), -2, Gravity.CENTER))
        camada.visibility = View.VISIBLE
        campo.requestFocus()
    }

    private fun fecharPainel() {
        camada.visibility = View.GONE; camada.removeAllViews()
        lista.requestFocus()
    }

    // ---------------------------------------------------------------- teclas

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (contando != null && e.action == KeyEvent.ACTION_DOWN && !SegurarOk.ehOk(e.keyCode)) pararContagem()
        return super.dispatchKeyEvent(e)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            if (camada.visibility == View.VISIBLE) { fecharPainel(); return true }
            if (pilha.size > 1 && !ocupado) {
                pilha.removeAt(pilha.lastIndex)
                desenhar(contagem(pilha.last()))
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---------------------------------------------------------------- ficha ao lado

    private fun atualizarFicha() {
        val nivel = pilha.lastOrNull()
        val alvo = (nivel?.itens?.getOrNull(lista.selectedItemPosition) as? Linha)?.alvo
        val (nome, texto, capa) = when (alvo) {
            is ItemDe -> Triple(alvo.item.nome, alvo.item.sinopse, alvo.item.poster)
            is EpisodioDe -> Triple(alvo.v.titulo.ifBlank { alvo.ficha.item.nome },
                if (ConfortoRegras.esconderResumo(semSpoiler, comecado(alvo.v.id))) getString(R.string.cf_resumo_escondido) else alvo.v.sinopse,
                alvo.ficha.item.poster)
            is FonteDe -> nivel.item?.let { Triple(it.nome, it.sinopse, it.poster) } ?: Triple("", "", "")
            else -> Triple("", "", "")
        }
        if (!lista.hasFocus() || (texto.isBlank() && capa.isBlank())) { ficha.visibility = View.GONE; return }
        ficha.visibility = View.VISIBLE
        fichaTitulo.text = nome
        fichaTexto.text = texto; fichaTexto.visibility = if (texto.isBlank()) View.GONE else View.VISIBLE
        fichaCapa.visibility = if (capa.isBlank()) View.GONE else View.VISIBLE
        if (capa.isNotBlank() && fichaCapa.tag != capa) PlayerLogos.carregar(this, capa, fichaCapa, dp(300))
    }

    // ---------------------------------------------------------------- lista

    private fun pintarFoco() {
        val sel = lista.selectedItemPosition
        for (i in 0 until lista.childCount) {
            val v = lista.getChildAt(i)
            val pos = lista.firstVisiblePosition + i
            if (v is LinearLayout && v.tag == TAG_LINHA) focar(v, lista.hasFocus() && pos == sel)
        }
        atualizarFicha()
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
        override fun isEnabled(p: Int) = getItem(p) is Linha

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
            for ((tv, cor) in listOf(t to Estilo.TEXTO, s to Estilo.TEXTO_2)) { tv.setTag(R.id.estilo_cor_normal, cor); tv.setTextColor(cor) }
            // Episódio visto ganha o ícone de "continuar" (sem texto novo para traduzir).
            val ep = l.alvo as? EpisodioDe
            icone.setImageDrawable(IconeMidia(if (ep != null && visto(ep.v.id)) IconeMidia.Tipo.CONTINUAR else l.icone))
            t.text = l.titulo
            s.text = l.sub.orEmpty(); s.visibility = if (l.sub.isNullOrEmpty()) View.GONE else View.VISIBLE
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
            background = Estilo.superficie(ctx, false)
        }
    }

    private fun dp(v: Int) = Estilo.dp(this, v)

    private companion object {
        const val TAG_LINHA = "linha"
        const val CODIGO_TOCAR = 31
        /** Página com menos títulos que isso é a última: não mostra "Mais" (addons costumam mandar 100 por página). */
        const val PAGINA_MINIMA = 20
    }
}
