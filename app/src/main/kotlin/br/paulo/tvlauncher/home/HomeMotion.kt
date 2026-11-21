package br.paulo.tvlauncher.home

import android.content.Intent
import android.util.Log
import br.paulo.tvlauncher.AddonsActivity
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.FilmesActivity
import br.paulo.tvlauncher.JogosActivity
import br.paulo.tvlauncher.MainActivity
import br.paulo.tvlauncher.MainActivity.Companion.CONTINUAR_MAX
import br.paulo.tvlauncher.MainActivity.Item
import br.paulo.tvlauncher.MidiaRedeActivity
import br.paulo.tvlauncher.PluginsTv
import br.paulo.tvlauncher.PodcastsActivity
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.RadioActivity
import br.paulo.tvlauncher.SeriesActivity
import br.paulo.tvlauncher.games.JogosTv
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.observer.DesempenhoTv
import br.paulo.tvlauncher.player.AddonsTv
import br.paulo.tvlauncher.player.BarraTempo
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.Posicao
import br.paulo.tvlauncher.player.Programa
import br.paulo.tvlauncher.player.Vod
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.screensaver.WallpaperPluginView

/** Movimento dos ícones da cruz (MovimentoAdaptativo). Saiu da MainActivity na refatoração de 21/11/2026. */

internal fun MainActivity.semAnimacoes() =
    runCatching { android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

/** Na volta da HOME: o nível que vale (aparelho, rebaixado, forçado pelo adb) e o juiz dos quadros. */
internal fun MainActivity.ligarMovimento() {
    val c = cruz ?: return
    c.nivel = MovimentoAdaptativo.atual(this, semAnimacoes())
    DesempenhoTv.limiteMs = MovimentoAdaptativo.limiteQuadroMs(runCatching { windowManager.defaultDisplay.refreshRate }.getOrDefault(60f))
    DesempenhoTv.aoPesado = if (c.nivel > MovimentoAdaptativo.Nivel.ECONOMICO) ({ ui.post { rebaixarMovimento() } }) else null
}

internal fun MainActivity.rebaixarMovimento() {
    val c = cruz ?: return
    if (!homeNaFrente) return
    val novo = MovimentoAdaptativo.descer(c.nivel)
    if (novo == c.nivel) { DesempenhoTv.aoPesado = null; return }
    Log.i("TvLauncher.Movimento", "quadros lentos demais: ${c.nivel} -> $novo")
    MovimentoAdaptativo.rebaixar(this, novo)
    c.nivel = novo
    if (novo <= MovimentoAdaptativo.Nivel.ECONOMICO) DesempenhoTv.aoPesado = null
}

internal fun MainActivity.espelho(acao: () -> Unit) = object : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = acao()
}

/**
 * Arte do item em foco (LayoutHome.arteCruz): a mesma capa à direita da tela antiga (CapaFoco, meia resolução,
 * escurecida), agora seguindo o item da cruz. TV, Séries, Filmes, Na rede e Jogos usam a arte do card (Séries e
 * Filmes: a cena do último título); um app, o banner dele; o resto, o degradê da cor do grupo.
 */
internal fun MainActivity.arteDoItem(item: HomeCruz.Item) {
    // Plugin de fundo que pediu o foco (a arte do item vem dele): nome e pacote do item, nada mais.
    (fundo as? WallpaperPluginView)?.focar(item.nome, pacoteDoItem(item.id))
    if (capa.desligada) return
    val id = item.id
    when {
        id == "tv" || id == "c:tv" -> mostrarCapaAtalho(IconeApp.Tipo.TV)
        id == "series" || id == "c:serie" -> mostrarCapaAtalho(IconeApp.Tipo.SERIES)
        id == "filmes" || id == "c:filme" -> mostrarCapaAtalho(IconeApp.Tipo.FILMES)
        id == "rede" -> mostrarCapaAtalho(IconeApp.Tipo.REDE)
        id == "central" -> mostrarCapaAtalho(IconeApp.Tipo.JOGOS)
        id.startsWith("app:") -> mostrarCapaDoApp(id.removePrefix("app:"), null)
        id.startsWith("c:jogo:") -> mostrarCapaDoApp(id.removePrefix("c:jogo:"), null)
        id.startsWith("c:midia:") -> mostrarCapaDoApp(id.removePrefix("c:midia:"), null)
        else -> {
            capaPedida = null
            val cor = when (cruz?.categoriaAtual()?.cat) {
                CruzLogica.Categoria.OUVIR -> 0xFF2C6E68.toInt()
                CruzLogica.Categoria.JOGAR -> 0xFF3A3F9E.toInt()
                CruzLogica.Categoria.APLICATIVOS -> 0xFF3A3640.toInt()
                else -> 0xFF8A4A2A.toInt()
            }
            capa.mostrar(null, intArrayOf(Estilo.matiz(cor, 0.6f), Estilo.matiz(cor, 0.35f)))
        }
    }
}

/** O pacote por trás de um item da cruz ("app:", "c:jogo:", "c:midia:"), ou null (atalhos nossos). */
internal fun MainActivity.pacoteDoItem(id: String): String? = when {
    id.startsWith("app:") -> id.removePrefix("app:")
    id.startsWith("c:jogo:") -> id.removePrefix("c:jogo:")
    id.startsWith("c:midia:") -> id.removePrefix("c:midia:")
    else -> null
}

/**
 * Linha de baixo do canal de TV na cruz (Paulo, 18/11/2026: "as informações ali são ótimas, usar da melhor maneira"),
 * uma informação por linha, na ordem em que se lê: o canal; o programa de agora e quanto falta; o próximo e a hora.
 * [comPrograma] = false quando o programa já é o título do item (Continuar). Sem guia, só o canal.
 */
/** O programa no ar e quanto falta ("SP1 · faltam 58 min"); [comPrograma] = false só o quanto falta. */
internal fun MainActivity.linhaAgoraTv(agora: Programa?, ms: Long, comPrograma: Boolean): String {
    val falta = agora?.let { ((it.fim - ms) / 60_000).toInt() }?.takeIf { it > 0 }?.let { BarraTempo.faltam(this, duracaoTexto(it)) }
    return listOfNotNull(agora?.titulo?.trim()?.takeIf { comPrograma && it.isNotEmpty() }, falta).joinToString(" · ")
}

internal fun MainActivity.linhasTvCruz(u: PlayerArmazem.Ultimo, agora: Programa?, ms: Long, comPrograma: Boolean): String {
    val hora = android.text.format.DateFormat.getTimeFormat(this)
    val canal = if (u.nome.isBlank()) getString(R.string.home_dest_tv, u.numero) else getString(R.string.cruz_tv_canal, u.numero, u.nome.trim())
    val linhaAgora = linhaAgoraTv(agora, ms, comPrograma)
    val depois = u.programas.firstOrNull { it.inicio >= (agora?.fim ?: ms) && it.titulo.isNotBlank() }
        ?.let { getString(R.string.cruz_tv_depois, hora.format(java.util.Date(it.inicio)), it.titulo.trim()) }
    return CruzLogica.linhasTv(canal, linhaAgora, depois)
}

/** Refaz a lista da cruz com os dados de agora. Barato: a HomeCruz só recria views quando os ids mudam. */
internal fun MainActivity.atualizarCruz() {
    val c = cruz ?: return
    if (!cruzContinuarLido) return
    val agora = System.currentTimeMillis()
    val programa = ultimoTv?.programas?.firstOrNull { it.inicio <= agora && agora < it.fim && it.titulo.isNotBlank() }
    fun faltaFilme(f: PlayerArmazem.FichaHome): String? = posicoesHome[f.chave]?.takeIf { it.duracao > 0 && Vod.deveRetomar(it) }
        ?.let { ((it.duracao - it.ms) / 60_000).toInt() }?.takeIf { it > 0 }?.let { BarraTempo.faltam(this, duracaoTexto(it)) }
    // Quanto já foi visto (barra no pé do ícone em Continuar): filme/série pela posição salva; TV, o programa no ar.
    fun visto(f: PlayerArmazem.FichaHome, serie: Boolean): Float? = posicoesHome[f.chave]
        ?.takeIf { it.duracao > 0 && (serie || Vod.deveRetomar(it)) }?.let { (it.ms.toFloat() / it.duracao).coerceIn(0f, 1f) }
    val noAr = programa?.takeIf { it.fim > it.inicio }?.let { ((agora - it.inicio).toFloat() / (it.fim - it.inicio)).coerceIn(0f, 1f) }
    // Sem internet: Séries, Filmes, Rádio e Podcasts avisam antes do OK (Na rede, TV gravada e pendrive seguem normais).
    val precisa = if (semInternetCruz) getString(R.string.cruz_precisa_internet) else null
    // A situação de cada item (1ª linha do canto de baixo à direita), sempre "rótulo + o quê · % · quanto falta" (Paulo, 18/11/2026 14:15
    // local: "mais inteligente, coerente e lógico"). Gravando vence No ar; Tocando vence Parou em.
    fun situacaoTv(oQue: String?, tira: String?): CruzLogica.Situacao? =
        gravandoTv?.let { CruzLogica.Situacao(getString(R.string.cruz_canto_gravando), it, "tv") }
            ?: programa?.let { p -> CruzLogica.Situacao(getString(R.string.cruz_canto_no_ar),
                CruzLogica.andamento(oQue, noAr, linhaAgoraTv(p, agora, comPrograma = false)), "tv", listOfNotNull(tira)) }
                ?.takeIf { it.texto.isNotEmpty() }
    fun situacaoParou(oQue: String?, fracao: Float?, falta: String?, tira: String?, origem: String): CruzLogica.Situacao? =
        falta?.takeIf { it.isNotBlank() }?.let { CruzLogica.Situacao(getString(R.string.cruz_canto_parou), CruzLogica.andamento(oQue, fracao, it), origem, listOfNotNull(tira)) }
    fun situacaoTocando(tipo: String): CruzLogica.Situacao? = ouvindoCruz?.takeIf { it.first == tipo && it.second.isNotBlank() }
        ?.let { CruzLogica.Situacao(getString(R.string.cruz_canto_tocando), it.second, tipo, listOf(getString(R.string.cruz_tocando, it.second))) }
    fun fracaoPodcast(p: Podcasts.Posicao): Float? = if (p.durMs > 0) (p.ms.toFloat() / p.durMs).coerceIn(0f, 1f) else null
    val cats = ArrayList<HomeCruz.Categoria>()
    fun categoria(k: CruzLogica.Categoria, nome: Int, tipo: IconeCruz.Tipo, itens: List<HomeCruz.Item>) {
        if (itens.isNotEmpty()) cats += HomeCruz.Categoria(k, getString(nome), icCruz("cat:" + k.id, tipo), itens)
    }

    // Os apps visíveis (appsVisiveis), cada um na categoria dele (CategoriaApps: escolha manual "Mover para…" > tipo
    // que o app declara > Aplicativos). Os jogos moram em Jogar (jogosNaCruz).
    val porPacote = LinkedHashMap<String, Item>()
    for (item in visiveis) porPacote[item.pacote] = item
    val escolhasCat = CategoriaApps.escolhas(this)
    val porDestino = porPacote.keys.groupBy { CategoriaApps.destino(it, this@atualizarCruz.categoria(it), false, escolhasCat[it], catsFicha?.get(it)) }
    val frequencia = frequenciaApps()
    val fixados = fixadosCruz()
    fun appsDe(k: CruzLogica.Categoria): List<HomeCruz.Item> =
        CruzLogica.ordenarApps(porDestino[k].orEmpty(), ultimoUso, frequencia, fixados).map { id -> porPacote.getValue(id).let { app ->
            HomeCruz.Item("app:$id", app.nome, linhasApp(id, app.nome), icApp(app), { abrir(id) }, opcoes = { menuAppCruz(id) })
        } }

    // Continuar (18/11/2026, pedido do dono): o canal de TV primeiro (a TV da casa); depois, do mais recente para o mais
    // antigo, a série, o filme, a última rádio, o podcast pela metade, até 3 jogos e o último de cada app de mídia
    // (YouTube, Spotify, Netflix…; MidiaRecente). "Dormir em 30 min" no fim, à noite.
    // O nome de cada item é O QUE ele é (TV, Série, Filme, Rádio, Podcast, Jogo, ou o app), como nas outras categorias;
    // o título vem embaixo e o resto no canto (Paulo, 19/11/2026 12:30 local: "tem q mostrar o nome do que é… eu gosto
    // de coerência"). Antes o nome era o título, cortado ("Lei e Ordem: Unid…") e sem dizer o que era.
    val continuar = ArrayList<HomeCruz.Item>()
    ultimoTv?.let { u ->
        continuar += HomeCruz.Item("c:tv", getString(R.string.home_card_tv),
            CruzLogica.linhas(programa?.titulo, linhasTvCruz(u, programa, agora, comPrograma = false)), icCruz("c:tv", IconeCruz.Tipo.TV), { abrirTv() }, progresso = noAr,
            situacao = situacaoTv(null, linhaAgoraTv(programa, agora, comPrograma = false)))
    }
    val recentes = ArrayList<Pair<Long, HomeCruz.Item>>()
    if (cruzTemVod) {
        fichaSerie?.let { f -> recentes += (posicoesHome[f.chave]?.quando ?: 0L) to HomeCruz.Item("c:serie", getString(R.string.continue_series),
            CruzLogica.linhas(CruzLogica.semMarca(f.titulo), CruzLogica.juntar(epLegivel(f.episodio), faltaSerie(f)), metaFicha(f, serie = true)),
            icCruz("c:serie", IconeCruz.Tipo.SERIES), { abrirCatalogo(SeriesActivity::class.java) }, progresso = visto(f, serie = true),
            situacao = situacaoParou(epLegivel(f.episodio), visto(f, serie = true), faltaSerie(f),
                CruzLogica.juntar(epLegivel(f.episodio), faltaSerie(f)), "c:serie")) }
        fichaFilme?.let { f -> recentes += (posicoesHome[f.chave]?.quando ?: 0L) to HomeCruz.Item("c:filme", getString(R.string.cruz_filme),
            CruzLogica.linhas(CruzLogica.semMarca(f.titulo), faltaFilme(f), metaFicha(f, serie = false)),
            icCruz("c:filme", IconeCruz.Tipo.FILMES), { abrirCatalogo(FilmesActivity::class.java) }, progresso = visto(f, serie = false),
            situacao = situacaoParou(null, visto(f, serie = false), faltaFilme(f), faltaFilme(f), "c:filme")) }
    }
    radioCruz?.let { (r, ouvidaEm) -> recentes += ouvidaEm to HomeCruz.Item("c:radio", getString(R.string.cruz_radio),
        CruzLogica.linhas(r.nome, quando(ouvidaEm)?.let { getString(R.string.cruz_tocou, it) }),
        icCruz("c:radio", IconeCruz.Tipo.RADIO), { tocarRadioCruz(r) }, situacao = situacaoTocando("radio")?.takeIf { it.texto.contains(r.nome, true) }) }
    podcastCruz?.let { p -> recentes += p.em to HomeCruz.Item("c:podcast", getString(R.string.continue_podcast),
        CruzLogica.linhas(p.titulo, p.podcast, faltaPodcast(p), quando(p.em)?.let { getString(R.string.cruz_tocou, it) }),
        icCruz("c:podcast", IconeCruz.Tipo.PODCASTS), { retomarPodcastCruz(p) },
        progresso = fracaoPodcast(p),
        situacao = situacaoTocando("podcast") ?: situacaoParou(null, fracaoPodcast(p), faltaPodcast(p), faltaPodcast(p), "c:podcast")) }
    for ((pacote, item) in jogosRecentes()) recentes += (ultimoUso[pacote] ?: 0L) to HomeCruz.Item("c:jogo:$pacote", getString(R.string.cruz_jogo),
        CruzLogica.linhas(item.nome, quando(ultimoUso[pacote])?.let { getString(R.string.cruz_jogado, it) }), icApp(item), { abrir(pacote) }, opcoes = { menuAppCruz(pacote) })
    for (m in midiaCruz) {
        val nomeApp = nomeDe(m.registro.pacote)
        recentes += m.registro.quando to HomeCruz.Item("c:midia:${m.registro.pacote}", nomeApp,
            CruzLogica.linhas(m.registro.titulo, m.registro.sub, faltaMidia(m.registro), quando(m.registro.quando)?.let { getString(R.string.cruz_aberto, it) }),
            m.capa ?: instalados.firstOrNull { it.pacote == m.registro.pacote }?.let { icApp(it) } ?: icCruz("c:midia", IconeCruz.Tipo.CONTINUAR),
            { abrirMidiaCruz(m.registro.pacote) }, progresso = MidiaRecente.progresso(m.registro),
            opcoes = { menuMidiaCruz(m.registro.pacote, nomeApp) },
            situacao = situacaoParou(null, MidiaRecente.progresso(m.registro), faltaMidia(m.registro), faltaMidia(m.registro), "c:midia"))
    }
    continuar += CruzLogica.recentes(recentes, CONTINUAR_MAX - continuar.size)
    itemDormir()?.let { continuar += it }   // onda 3: à noite, "Dormir em 30 min" no fim do Continuar
    categoria(CruzLogica.Categoria.CONTINUAR, R.string.cruz_continuar, IconeCruz.Tipo.CONTINUAR, continuar)

    // Assistir: os cards de hoje (TV, Séries, Filmes, Na rede, Addons, plugins), cada um só se o recurso existe.
    val assistir = ArrayList<HomeCruz.Item>()
    if (cruzTemTv) assistir += HomeCruz.Item("tv", getString(R.string.home_card_tv),
        (ultimoTv?.let { linhasTvCruz(it, programa, agora, comPrograma = true) } ?: getString(R.string.home_destaque_tv_sub)).split("\n").let { l ->
            // O jogo do seu time (HomeCards, chave CARTAO_PLACAR) logo depois do canal: as linhas param em 4.
            CruzLogica.linhas(l.first(), jogoCruz?.let { getString(R.string.hc_seu_time, it) }, *l.drop(1).toTypedArray()) },
        icCruz("tv", IconeCruz.Tipo.TV), { abrirTv() }, progresso = noAr,
        situacao = situacaoTv(programa?.titulo, programa?.let { linhaAgoraTv(it, agora, comPrograma = true) }))
    if (cruzTemVod) {
        assistir += HomeCruz.Item("series", getString(R.string.home_card_series),
            precisa ?: fichaSerie?.let { f -> CruzLogica.linhas(f.titulo, CruzLogica.juntar(epLegivel(f.episodio), faltaSerie(f)), metaFicha(f, serie = true), f.genero) }
                ?: getString(R.string.home_destaque_series),
            icCruz("series", IconeCruz.Tipo.SERIES), { abrirCatalogo(SeriesActivity::class.java) },
            situacao = fichaSerie?.takeIf { precisa == null }?.let { f ->
                situacaoParou(epLegivel(f.episodio), visto(f, serie = true), faltaSerie(f), CruzLogica.juntar(epLegivel(f.episodio), faltaSerie(f)), "series") })
        assistir += HomeCruz.Item("filmes", getString(R.string.home_card_filmes),
            precisa ?: fichaFilme?.let { f -> CruzLogica.linhas(f.titulo, metaFicha(f, serie = false), f.genero, faltaFilme(f)) }
                ?: getString(R.string.home_destaque_filmes),
            icCruz("filmes", IconeCruz.Tipo.FILMES), { abrirCatalogo(FilmesActivity::class.java) },
            situacao = fichaFilme?.takeIf { precisa == null }?.let { f -> situacaoParou(null, visto(f, serie = false), faltaFilme(f), faltaFilme(f), "filmes") })
    }
    val pendrives = pendrivesCruz()
    assistir += HomeCruz.Item("rede", getString(R.string.home_card_rede),
        CruzLogica.linhas(getString(R.string.home_destaque_rede), *pendrives.map { getString(R.string.cruz_pendrive, it) }.toTypedArray()),
        icCruz("rede", IconeCruz.Tipo.REDE), { abrirCatalogo(MidiaRedeActivity::class.java) },
        situacao = pendrives.takeIf { it.isNotEmpty() }?.let { pd ->
            CruzLogica.Situacao(getString(R.string.cruz_canto_conectado), pd.joinToString(" · "), "rede", pd.map { getString(R.string.cruz_pendrive, it) }) })
    if (AddonsTv.instalados(this).isNotEmpty()) assistir += HomeCruz.Item("addons", getString(R.string.ad_titulo),
        CruzLogica.linhas(getString(R.string.ad_destaque), getString(R.string.cruz_addons_n, AddonsTv.instalados(this).size)), icCruz("addons", IconeCruz.Tipo.ADDONS), { abrirCatalogo(AddonsActivity::class.java) })
    for ((i, p) in cartoesPlugin.withIndex()) assistir += HomeCruz.Item("plugin:${p.pacote}:$i", p.titulo.ifEmpty { p.valor },
        if (p.titulo.isEmpty()) p.detalhe else listOf(p.valor, p.detalhe).filter { it.isNotEmpty() }.joinToString(" · "),
        icCruz("plugin:${p.pacote}:$i", IconeCruz.Tipo.PLUGIN), { PluginsTv.abrir(this, p) })
    assistir += appsDe(CruzLogica.Categoria.ASSISTIR)   // YouTube, Netflix… (movidos ou pelo tipo do app)
    categoria(CruzLogica.Categoria.ASSISTIR, R.string.cruz_assistir, IconeCruz.Tipo.ASSISTIR, assistir)

    // Ouvir: as rádios (catálogo mundial, sem conta) e os podcasts (plano Básico, Paulo 17/11/2026).
    val ouvir = listOf(
        HomeCruz.Item("radio", getString(R.string.cruz_radio),
            CruzLogica.linhas(ouvindoCruz?.takeIf { it.first == "radio" }?.let { getString(R.string.cruz_tocando, it.second) } ?: precisa ?: getString(R.string.cruz_radio_sub),
                radioCruz?.let { (r, q) -> CruzLogica.juntar(getString(R.string.cruz_por_ultimo, r.nome), quando(q)) }),
            icCruz("radio", IconeCruz.Tipo.RADIO), {
            try { startActivity(Intent(this, RadioActivity::class.java)) }
            catch (e: Exception) { Log.w("TvLauncher.Cruz", "rádio não abriu: ${e.javaClass.simpleName}"); cruz?.naoAbriu() }
        }, situacao = situacaoTocando("radio")),
        HomeCruz.Item("podcasts", getString(R.string.pod_titulo),
            CruzLogica.linhas(ouvindoCruz?.takeIf { it.first == "podcast" }?.let { getString(R.string.cruz_tocando, it.second) } ?: precisa ?: getString(R.string.ra_podcasts_det),
                podcastCruz?.let { p -> CruzLogica.juntar(p.titulo, faltaPodcast(p)) },
                assinadosCruz.takeIf { it > 0 }?.let { getString(R.string.cruz_podcasts_n, it) }),
            icCruz("podcasts", IconeCruz.Tipo.PODCASTS), {
            try { startActivity(Intent(this, PodcastsActivity::class.java)) }
            catch (e: Exception) { Log.w("TvLauncher.Cruz", "podcasts não abriram: ${e.javaClass.simpleName}"); cruz?.naoAbriu() }
        }, situacao = situacaoTocando("podcast") ?: podcastCruz?.let { p ->
            situacaoParou(p.titulo, fracaoPodcast(p), faltaPodcast(p), CruzLogica.juntar(p.titulo, faltaPodcast(p)), "podcasts") })) + appsDe(CruzLogica.Categoria.OUVIR)
    categoria(CruzLogica.Categoria.OUVIR, R.string.cruz_ouvir, IconeCruz.Tipo.OUVIR, ouvir)

    // Jogar (18/11/2026, pedido do dono: "Jogos" e "Sofá" abriam a mesma tela): a Central de jogos no topo (o painel com
    // controles, turma do Discord, rede e recentes) e cada jogo instalado, do jogado por último ao mais antigo.
    val jogos = ArrayList<HomeCruz.Item>()
    if (JogosTv.cardLigado(this)) {
        jogos += HomeCruz.Item("central", getString(R.string.cruz_central), linhasCentral(), icCruz("central", IconeCruz.Tipo.CENTRAL),
            { abrirCatalogo(JogosActivity::class.java) })
        val doJogo = jogosNaCruz().associateBy { it.pacote }
        for (p in CruzLogica.ordenarApps(doJogo.keys.sorted(), ultimoUso, emptyMap(), fixadosCruz())) doJogo.getValue(p).let { app ->
            jogos += HomeCruz.Item("app:$p", app.nome,
                CruzLogica.linhas(subApp(p, app.nome), quando(ultimoUso[p])?.let { getString(R.string.cruz_jogado, it) }),
                icApp(app), { abrir(p) }, opcoes = { menuAppCruz(p) })
        }
        categoria(CruzLogica.Categoria.JOGAR, R.string.cruz_jogar, IconeCruz.Tipo.JOGAR, jogos)
    }

    // Aplicativos: os 8 mais usados dos que moram aqui (nem movidos nem automáticos de Assistir/Ouvir) e "Todos os apps".
    val apps = CruzLogica.appsDaColuna(porDestino[CruzLogica.Categoria.APLICATIVOS].orEmpty(), ultimoUso, frequencia, fixados).map { id ->
        if (id == CruzLogica.ID_TODOS_APPS) HomeCruz.Item(id, getString(R.string.cruz_todos_apps),
            CruzLogica.linhas(getString(R.string.cruz_todos_apps_sub), contaApps()),
            icCruz(id, IconeCruz.Tipo.TODOS_APPS), { abrirTodos() })
        else porPacote.getValue(id).let { app ->
            HomeCruz.Item("app:$id", app.nome, linhasApp(id, app.nome), icApp(app), { abrir(id) }, opcoes = { menuAppCruz(id) })
        }
    }
    categoria(CruzLogica.Categoria.APLICATIVOS, R.string.cruz_aplicativos, IconeCruz.Tipo.APLICATIVOS, apps)

    c.definir(cats)
    if (focoInicialNaTv && !todosAbertos) { focoInicialNaTv = false; c.requestFocus() }
    else if (currentFocus == null && !todosAbertos) c.requestFocus()
}
