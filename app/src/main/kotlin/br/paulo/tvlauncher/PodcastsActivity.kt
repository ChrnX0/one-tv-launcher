package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import br.paulo.tvlauncher.home.CruzLogica
import br.paulo.tvlauncher.home.SonsCruz
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.listen.CapaOuvir
import br.paulo.tvlauncher.listen.CruzOuvir
import br.paulo.tvlauncher.listen.IconeOuvir
import br.paulo.tvlauncher.listen.PodcastBusca
import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.PodcastStarter
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.listen.Radios
import br.paulo.tvlauncher.player.BarraTempo
import br.paulo.tvlauncher.player.Episodio
import br.paulo.tvlauncher.player.Posicao
import br.paulo.tvlauncher.player.escolherVelocidade
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.settings.Planos
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela "Podcasts" ("Ouvir" parte 2, 17/11/2026; redesenhada 18/11/2026 no padrão da cruz da HOME, dono: "a tela de
 * podcast está BEM ruim"). Antes era uma lista de Configurações sem capa nem progresso; agora é uma cruz ([CruzOuvir]):
 * ◀ ▶ trocam a prateleira, ▲ ▼ andam nos episódios, OK toca, OK segurado abre as opções. Prateleiras, na ordem:
 *  - Tocando (só com episódio): Pausar/Continuar, Voltar 15 s, Avançar 30 s, Velocidade (0,8x a 2x, sem mudar o tom),
 *    Pular silêncios e Parar. As teclas ⏪ ⏩ ⏯ do controle fazem o mesmo de qualquer prateleira;
 *  - Continuar (começados, com a barra do quanto já ouviu), Novos (14 dias, nunca começados), Meus podcasts (capas);
 *  - Sugestões (os que vêm no app, do idioma da TV, [PodcastStarter]; OK segue), Resultado da busca e Buscar (plano Pro,
 *    com a busca ligada), Adicionar (colar o endereço, pelo celular). Na primeira vez, segue sozinho 4 sugeridos.
 * Um podcast aberto troca as prateleiras pelos episódios dele e "Sobre" (a descrição, deixar de seguir); Voltar sai dele.
 * O canto de cima à direita mostra o episódio que toca, com capa, onde está e quanto falta (barra), e a capa desfocada no
 * fundo. Episódio em vídeo abre no player de vídeo. Explícito some com o modo infantil ou o controle dos pais ligado.
 * Quem toca é o [OuvirServico] (o som segue na HOME e no protetor). Dados: [Podcasts]; leitura do feed: [PodcastFeed].
 */
class PodcastsActivity : Activity() {
    private lateinit var cruz: CruzOuvir
    private var assinaturas: List<Podcasts.Assinatura> = emptyList()
    private var feeds: Map<String, PodcastFeed.Feed> = emptyMap()
    private var posicoes: Map<String, Podcasts.Posicao> = emptyMap()
    private var protegido = false
    private var leu = false
    /** Resultado da busca (título da prateleira, lista), só com a busca ligada ([PodcastBusca]). */
    private var resultados: Pair<String, List<PodcastBusca.Resultado>>? = null
    /** Os podcasts que vêm no app, do idioma da TV ([PodcastStarter]). */
    private var sugestoes: List<PodcastStarter.Sugestao> = emptyList()
    /** O podcast aberto (episódios dele), ou null = a tela principal. */
    private var aberto: String? = null
    /** Na próxima montagem, o foco vai para esta (prateleira, item). */
    private var focarDepois: Pair<String, String?>? = null
    private val mudou: () -> Unit = { aoMudarSom() }
    private var visto: Any? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val relogio = object : Runnable {
        override fun run() { atualizarTempo(); ui.postDelayed(this, 1_000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        cruz = CruzOuvir(this, Fontes.nunito(this, 500), Fontes.nunito(this, 800)).apply {
            definirTitulo(getString(R.string.pod_titulo))
            definirDicas(getString(R.string.grv_dica_ok) to getString(R.string.ra_dica_ouvir),
                getString(R.string.grv_dica_segurar) to getString(R.string.pod_dica_mais),
                "◀◀ ▶▶" to getString(R.string.ouv_dica_pular))
            aoSom = { s -> if (OuvirServico.instancia?.tocando != true) SonsCruz.tocar(this@PodcastsActivity, s, CruzLogica.Categoria.OUVIR) }
        }
        setContentView(FrameLayout(this).apply {
            background = Estilo.fundoTela(this@PodcastsActivity)
            addView(cruz, FrameLayout.LayoutParams(-1, -1))
        })
        cruz.requestFocus()
        recarregar(buscarNovos = true, inicio = true)
    }

    /** O onCreate já leu; os onStart seguintes releem (a tela é singleTask: volta a mesma, com posições velhas). */
    private var jaLeu = false

    override fun onStart() {
        super.onStart()
        if (jaLeu) recarregar() else jaLeu = true
        OuvirServico.ouvir(mudou)
        ui.post(relogio)
    }

    override fun onStop() {
        super.onStop()
        OuvirServico.esquecer(mudou)
        ui.removeCallbacks(relogio)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); OuvirServico.esquecer(mudou); super.onDestroy() }

    @Deprecated("Activity.onBackPressed")
    override fun onBackPressed() {
        val a = aberto
        if (a != null) { aberto = null; focarDepois = "meus" to "pod:$a"; montar(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // ⏪ ⏩ ⏯ do controle, de qualquer prateleira (fora da tela a sessão de mídia do serviço já atende).
        val s = OuvirServico.instancia
        if (e.action == KeyEvent.ACTION_DOWN && s?.episodio != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_MEDIA_REWIND -> { s.pular(-OuvirServico.VOLTAR_MS); atualizarTempo(); return true }
                KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { s.pular(OuvirServico.AVANCAR_MS); atualizarTempo(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY -> { if (e.repeatCount == 0) s.continuar(); return true }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (e.repeatCount == 0) s.pausar(); return true }
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (e.repeatCount == 0) s.alternar(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- dados

    /** Relê tudo do disco. Na fila dos podcasts: vem depois de qualquer posição que o serviço mandou gravar antes. */
    private fun recarregar(buscarNovos: Boolean = false, inicio: Boolean = false) {
        val app = applicationContext
        Podcasts.naFila {
            val a = Podcasts.assinaturas(app); val f = Podcasts.feeds(app); val p = Podcasts.posicoes(app); val prot = Podcasts.protegido(app)
            val sug = PodcastStarter.lista(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                assinaturas = a; feeds = f; posicoes = p; protegido = prot; sugestoes = sug; leu = true
                if (inicio) focarDepois = inicial()
                montar()
            }
            // Primeira vez com quase nada seguido: segue sozinho 4 sugeridos do idioma (rede: fora da fila).
            if (inicio && !PodcastStarter.jaFez(app)) Fundo.rodar("podcast") {
                val n = runCatching { PodcastStarter.seguirNaPrimeiraVez(app, a.map { it.url }.toSet()) }.getOrDefault(0)
                if (n > 0) runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    AvisoTv.rapido(app, getString(R.string.pod_titulo), getString(R.string.pod_sugeridos_seguidos), 5)
                    if (aberto == null) focarDepois = "meus" to null
                    recarregar()
                }
            }
            if (buscarNovos && a.isNotEmpty()) Fundo.rodar("podcast") {   // rede: fora da fila
                // Feeds lidos há mais de 6 h: relê (pedido condicional, quase sempre "nada novo").
                val mudaram = runCatching { Podcasts.atualizar(app) }.getOrDefault(0)
                if (mudaram > 0) runOnUiThread { if (!isDestroyed) recarregar() }
            }
        }
    }

    /** Abrindo: o que toca; senão o que dá para continuar; senão os novos; senão os podcasts; senão "Adicionar". */
    private fun inicial(): Pair<String, String?> = when {
        OuvirServico.instancia?.episodio != null -> "tocando" to null
        continuarLista().isNotEmpty() -> "continuar" to null
        novosLista().isNotEmpty() -> "novos" to null
        assinaturas.isNotEmpty() -> "meus" to null
        sugestoesVisiveis().isNotEmpty() -> "sugestoes" to null
        else -> "adicionar" to null
    }

    // ---------------------------------------------------------------- tela

    private fun nomeDe(feed: String) = assinaturas.firstOrNull { it.url == feed }?.titulo ?: feeds[feed]?.titulo.orEmpty()
    // A capa do PODCAST primeiro (a mesma em toda a lista, já baixada); a do episódio só quando o podcast não tem.
    // Na Sala (18/11/2026) a capa por episódio do Up First não chegava e a lista ficava só com as iniciais.
    private fun capaDe(feed: String, ep: PodcastFeed.Episodio? = null) =
        assinaturas.firstOrNull { it.url == feed }?.capa?.takeIf { it.isNotEmpty() } ?: feeds[feed]?.capa?.takeIf { it.isNotEmpty() }
            ?: ep?.capa.orEmpty()

    private fun continuarLista() = Podcasts.continuar(posicoes, assinaturas.map { it.url }.toSet())
        .mapNotNull { p -> feeds[p.feed]?.episodios?.firstOrNull { it.guid == p.guid }?.let { Triple(p.feed, it, p) } }
        .filter { Podcasts.visivel(it.second, protegido) }

    private fun sugestoesVisiveis() = PodcastStarter.paraMostrar(sugestoes, assinaturas.map { it.url }.toSet(), protegido)

    private fun novosLista() = Podcasts.novos(feeds, posicoes, System.currentTimeMillis()).filter { Podcasts.visivel(it.second, protegido) }

    private fun montar() {
        if (!leu) return
        val l = ArrayList<CruzOuvir.Prateleira>()
        prateleiraTocando()?.let { l += it }
        val url = aberto
        if (url != null) montarPodcast(url, l) else montarPrincipal(l)
        val f = focarDepois; focarDepois = null
        cruz.definir(l, f)
        cruz.tocando(cartao())
        aoMudarSomTela()
    }

    private fun prateleiraTocando(): CruzOuvir.Prateleira? {
        val s = OuvirServico.instancia ?: return null
        val e = s.episodio ?: return null
        val v = OuvirServico.velocidade(this)
        val sil = OuvirServico.pularSilencio(this)
        return CruzOuvir.Prateleira("tocando", getString(R.string.ouv_prat_tocando), IconeOuvir.Tipo.TOCANDO, listOf(
            // O player em tela cheia (capa grande, barra de tempo, timer para dormir, "A seguir").
            CruzOuvir.Item("abrir", getString(R.string.pl_abrir), CruzLogica.linhas(e.ep.titulo, getString(R.string.pl_abrir_det)),
                capa = capaDe(e.feed, e.ep).ifEmpty { e.capaPodcast }, cor = IconeOuvir.cor(IconeOuvir.Tipo.PODCAST),
                monograma = CapaOuvir.iniciais(e.podcast), cobrir = true, abrir = { PodcastPlayerActivity.abrir(this, OuvirServico.instancia?.episodio) }),
            CruzOuvir.Item("pausar", getString(if (s.pausado) R.string.ouv_continuar else R.string.ouv_pausar),
                CruzLogica.linhas(e.ep.titulo, e.podcast), if (s.pausado) IconeOuvir.Tipo.CONTINUAR else IconeOuvir.Tipo.PAUSAR,
                abrir = { OuvirServico.instancia?.alternar() }),
            CruzOuvir.Item("voltar", getString(R.string.ouv_voltar), getString(R.string.ouv_voltar_det), IconeOuvir.Tipo.VOLTAR,
                abrir = { OuvirServico.instancia?.pular(-OuvirServico.VOLTAR_MS); atualizarTempo() }),
            CruzOuvir.Item("avancar", getString(R.string.ouv_avancar), getString(R.string.ouv_avancar_det), IconeOuvir.Tipo.AVANCAR,
                abrir = { OuvirServico.instancia?.pular(OuvirServico.AVANCAR_MS); atualizarTempo() }),
            CruzOuvir.Item("velocidade", getString(R.string.pod_velocidade), getString(R.string.ouv_velocidade_det, rotuloVelocidade(v)),
                IconeOuvir.Tipo.VELOCIDADE, abrir = { escolherVelocidade() }),
            CruzOuvir.Item("silencio", getString(R.string.pod_silencio),
                CruzLogica.linhas(getString(if (sil) R.string.pod_ligado else R.string.pod_desligado).replaceFirstChar { it.titlecase(Locale.getDefault()) },
                    getString(R.string.pod_silencio_det)),
                IconeOuvir.Tipo.SILENCIO, abrir = { OuvirServico.instancia?.definirPularSilencio(!sil); montar() }),
            CruzOuvir.Item("parar", getString(R.string.ra_parar), getString(R.string.ouv_parar_pod_det), IconeOuvir.Tipo.PARAR,
                abrir = { OuvirServico.instancia?.parar() }),
        ))
    }

    private fun escolherVelocidade() {
        val v = OuvirServico.velocidade(this)
        Estilo.lista(this, getString(R.string.pod_velocidade), OuvirServico.VELOCIDADES.map { rotuloVelocidade(it) },
            listOf(OuvirServico.VELOCIDADES.indexOf(v))) { i -> OuvirServico.instancia?.definirVelocidade(OuvirServico.VELOCIDADES[i]); montar() }
    }

    private fun cartao(): CruzOuvir.Tocando? {
        val s = OuvirServico.instancia ?: return null
        val e = s.episodio ?: return null
        val (prog, tempo) = progressoTempo(s)
        return CruzOuvir.Tocando(e.ep.titulo, e.podcast, capaDe(e.feed, e.ep).ifEmpty { e.capaPodcast }, IconeOuvir.cor(IconeOuvir.Tipo.PODCAST),
            CapaOuvir.iniciais(e.podcast), cobrir = true, progresso = prog, tempo = tempo, pausado = s.pausado)
    }

    /** (0..1 ou null, "12:30 de 58:00 · faltam 45 min · 1,2x"). */
    private fun progressoTempo(s: OuvirServico): Pair<Float?, String> {
        val dur = s.duracaoMs; val pos = s.posicaoMs
        val v = OuvirServico.velocidade(this)
        val texto = listOfNotNull(
            if (dur > 0) getString(R.string.pod_de, tempo(pos), tempo(dur)) else tempo(pos),
            if (dur > pos && dur > 0) BarraTempo.faltam(this, duracaoCurta(((dur - pos) / v).toLong())) else null,
            if (v != 1f) rotuloVelocidade(v) else null).joinToString(" · ")
        return (if (dur > 0) pos.toFloat() / dur else null) to texto
    }

    /** A cada segundo: só a barra e o tempo do cartão (sem refazer a cruz, que perderia o lugar). */
    private fun atualizarTempo() {
        if (!::cruz.isInitialized) return
        val s = OuvirServico.instancia ?: return
        if (s.episodio == null) return
        val (prog, tempo) = progressoTempo(s)
        cruz.tempo(prog, tempo)
    }

    private fun aoMudarSom() {
        if (isDestroyed || !::cruz.isInitialized) return
        val s = OuvirServico.instancia
        val agora = listOf(s?.episodio?.ep?.guid, s?.pausado, OuvirServico.velocidade(this), OuvirServico.pularSilencio(this))
        if (agora != visto) {
            // Parou ou trocou de episódio: a posição do anterior foi mandada gravar; relê (na fila, depois da gravação)
            // para "Continuar", "faltam" e "ouvido" saírem certos.
            val trocou = visto != null && (visto as List<*>)[0] != agora[0]
            visto = agora
            if (trocou) recarregar() else montar()
        }
        aoMudarSomTela()
    }

    private fun aoMudarSomTela() {
        val s = OuvirServico.instancia
        if (s?.episodio != null && s.tocando) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun estadoEp(feed: String, ep: PodcastFeed.Episodio): Pair<String?, Float?> {
        val tocandoEste = OuvirServico.instancia?.episodio?.let { it.feed == feed && it.ep.guid == ep.guid } == true
        val p = posicoes[Podcasts.chave(feed, ep.guid)]
        return when {
            tocandoEste -> "● " + getString(R.string.ouv_tocando_agora) to p?.takeIf { it.durMs > 0 }?.let { it.ms.toFloat() / it.durMs }
            p?.ouvido == true -> "✓ " + getString(R.string.pod_ouvido).replaceFirstChar { it.titlecase(Locale.getDefault()) } to null
            p != null && p.ms >= Podcasts.MIN_CONTINUAR_MS && p.durMs > 0 -> BarraTempo.faltam(this, duracaoCurta(p.durMs - p.ms)) to p.ms.toFloat() / p.durMs
            else -> duracaoEp(ep) to null
        }
    }

    private fun itemEp(prefixo: String, feed: String, ep: PodcastFeed.Episodio, comPodcast: Boolean): CruzOuvir.Item {
        val (estado, prog) = estadoEp(feed, ep)
        val nome = nomeDe(feed)
        return CruzOuvir.Item("$prefixo${Podcasts.chave(feed, ep.guid)}", ep.titulo,
            CruzLogica.linhas(if (comPodcast) nome else null, CruzLogica.juntar(quando(ep.dataMs), estado, if (ep.video) getString(R.string.pod_video) else null),
                resumo(ep.resumo)),
            capa = capaDe(feed, ep), cor = IconeOuvir.cor(IconeOuvir.Tipo.PODCAST), monograma = CapaOuvir.iniciais(nome), cobrir = true,
            progresso = prog, abrir = { tocar(feed, ep) }, opcoes = { menuEpisodio(feed, ep) })
    }

    /** A primeira frase (ou ~110 letras) do resumo do episódio, numa linha só. */
    private fun resumo(t: String): String? {
        val limpo = t.replace(Regex("\\s+"), " ").trim()
        if (limpo.isEmpty()) return null
        return if (limpo.length <= 110) limpo else limpo.take(110).substringBeforeLast(' ') + "…"
    }

    private fun montarPrincipal(l: MutableList<CruzOuvir.Prateleira>) {
        val cont = continuarLista()
        if (cont.isNotEmpty()) l += CruzOuvir.Prateleira("continuar", getString(R.string.pod_continuar), IconeOuvir.Tipo.CONTINUAR,
            cont.map { (feed, ep, _) -> itemEp("cont:", feed, ep, comPodcast = true) })
        val novos = novosLista()
        if (novos.isNotEmpty()) l += CruzOuvir.Prateleira("novos", getString(R.string.pod_novos), IconeOuvir.Tipo.NOVOS,
            novos.map { (feed, ep) -> itemEp("novo:", feed, ep, comPodcast = true) })
        resultados?.let { (titulo, lista) ->
            val visiveis = lista.filter { !(protegido && it.explicito) }
            l += CruzOuvir.Prateleira("res", titulo, IconeOuvir.Tipo.BUSCAR,
                if (visiveis.isEmpty()) listOf(CruzOuvir.Item("res:nada", getString(R.string.ra_nada), "", IconeOuvir.Tipo.BUSCAR))
                else visiveis.map { r ->
                    val seguido = assinaturas.any { it.url == r.url }
                    CruzOuvir.Item("busca:${r.url}", r.titulo, CruzLogica.linhas(r.autor, if (seguido) "✓ " + getString(R.string.ouv_seguindo) else null),
                        capa = r.capa, cor = IconeOuvir.cor(IconeOuvir.Tipo.PODCAST), monograma = CapaOuvir.iniciais(r.titulo), cobrir = true,
                        abrir = { if (seguido) abrir(r.url) else adicionar(r.url) })
                })
        }
        // Com o modo infantil ou o controle dos pais, podcast marcado como explícito nem aparece.
        val meus = assinaturas.filter { !(protegido && it.explicito) }
        l += CruzOuvir.Prateleira("meus", getString(R.string.pod_meus), IconeOuvir.Tipo.MEUS,
            if (meus.isEmpty()) listOf(CruzOuvir.Item("meus:vazio", getString(R.string.ouv_sem_podcasts), getString(R.string.ouv_sem_podcasts_det), IconeOuvir.Tipo.MEUS,
                abrir = { cruz.ir("adicionar") }))
            else meus.map { a ->
                val f = feeds[a.url]
                val ultimo = f?.episodios?.firstOrNull()?.let { quando(it.dataMs) }?.let { getString(R.string.pod_ultimo, it) }
                val n = f?.episodios?.count { Podcasts.visivel(it, protegido) } ?: 0
                CruzOuvir.Item("pod:${a.url}", a.titulo,
                    CruzLogica.linhas(a.autor.ifEmpty { f?.autor }, CruzLogica.juntar(ultimo, if (n > 0) getString(R.string.ouv_n_episodios, n) else null)),
                    capa = a.capa.ifEmpty { f?.capa.orEmpty() }, cor = IconeOuvir.cor(IconeOuvir.Tipo.PODCAST), monograma = CapaOuvir.iniciais(a.titulo),
                    cobrir = true, abrir = { abrir(a.url) }, opcoes = { menuPodcast(a) })
            })
        // Os que vêm no app (18/11/2026): OK segue e abre o podcast, como um resultado de busca.
        val sug = sugestoesVisiveis()
        if (sug.isNotEmpty()) l += CruzOuvir.Prateleira("sugestoes", getString(R.string.pod_sugestoes), IconeOuvir.Tipo.EM_ALTA,
            sug.map { s ->
                CruzOuvir.Item("sug:${s.url}", s.titulo, CruzLogica.linhas(s.autor, getString(R.string.pod_sugestao_seguir)),
                    capa = s.capa, cor = IconeOuvir.cor(IconeOuvir.Tipo.PODCAST), monograma = CapaOuvir.iniciais(s.titulo), cobrir = true,
                    abrir = { adicionar(s.url) })
            })
        // Buscar é do Pro (Paulo, 17/11/2026); seguir pelo endereço ou pelo OPML é do Básico.
        if (PodcastBusca.disponivel() && Planos.permite(this, Planos.Recurso.PODCAST_BUSCA)) {
            // Busca pelo servidor nosso (Podcast Index). A atribuição vai na explicação, como os termos pedem.
            l += CruzOuvir.Prateleira("buscar", getString(R.string.pod_procurar), IconeOuvir.Tipo.BUSCAR, listOf(
                CruzOuvir.Item("buscar", getString(R.string.pod_buscar), getString(R.string.pod_atribuicao), IconeOuvir.Tipo.BUSCAR, abrir = { pedirBusca() }),
                CruzOuvir.Item("em_alta", getString(R.string.pod_em_alta), getString(R.string.pod_atribuicao), IconeOuvir.Tipo.EM_ALTA, abrir = {
                    procurar(getString(R.string.pod_em_alta)) { PodcastBusca.emAlta(Locale.getDefault().language) }
                })))
        }
        l += CruzOuvir.Prateleira("adicionar", getString(R.string.pod_adicionar), IconeOuvir.Tipo.ADICIONAR, listOf(
            CruzOuvir.Item("colar", getString(R.string.ouv_colar), getString(R.string.pod_colar_det), IconeOuvir.Tipo.ADICIONAR, abrir = { pedirEndereco() }),
            CruzOuvir.Item("celular", getString(R.string.pod_pelo_celular), getString(R.string.pod_pelo_celular_det), IconeOuvir.Tipo.CELULAR, abrir = {
                AvisoTv.rapido(this, getString(R.string.pod_pelo_celular), getString(R.string.pod_pelo_celular_det), 8)
            })))
    }

    private fun montarPodcast(url: String, l: MutableList<CruzOuvir.Prateleira>) {
        val a = assinaturas.firstOrNull { it.url == url }
        val f = feeds[url]
        val nome = a?.titulo ?: f?.titulo ?: url
        val eps = f?.episodios.orEmpty().filter { Podcasts.visivel(it, protegido) }.take(100)
        l += CruzOuvir.Prateleira("eps", nome, IconeOuvir.Tipo.EPISODIOS,
            if (eps.isEmpty()) listOf(CruzOuvir.Item("ep:nada", getString(R.string.pod_sem_episodios), "", IconeOuvir.Tipo.EPISODIOS))
            else eps.map { itemEp("ep:", url, it, comPodcast = false) })
        val sobre = ArrayList<CruzOuvir.Item>()
        val descricao = f?.descricao?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
        sobre += CruzOuvir.Item("sobre", nome, CruzLogica.linhas(a?.autor?.ifEmpty { null } ?: f?.autor, descricao.take(220)),
            capa = a?.capa?.ifEmpty { null } ?: f?.capa.orEmpty(), cor = IconeOuvir.cor(IconeOuvir.Tipo.PODCAST), monograma = CapaOuvir.iniciais(nome), cobrir = true)
        if (a != null) sobre += CruzOuvir.Item("remover", getString(R.string.pod_remover), getString(R.string.ouv_remover_det), IconeOuvir.Tipo.SAIR,
            abrir = { remover(a) })
        l += CruzOuvir.Prateleira("sobre", getString(R.string.ouv_sobre), IconeOuvir.Tipo.MEUS, sobre)
    }

    private fun abrir(url: String) {
        aberto = Podcasts.normalizar(url)
        focarDepois = "eps" to null
        montar()
    }

    // ---------------------------------------------------------------- ações

    private fun tocar(feed: String, ep: PodcastFeed.Episodio, doComeco: Boolean = false) {
        val nome = nomeDe(feed)
        if (ep.video) {
            // Vídeo no feed: o player de vídeo (tela cheia, legenda, velocidade). A posição fica com ele.
            OuvirServico.instancia?.parar()
            val app = applicationContext
            // O endereço vem do feed (de fora): para a própria TV ou a rede da casa, não abre (como no áudio).
            Fundo.rodar("podcast-video") {
                val ips = runCatching { java.net.InetAddress.getAllByName(java.net.URL(ep.url).host).toList() }.getOrNull()
                val ok = !ips.isNullOrEmpty() && ips.none { Radios.destinoDeFeedProibido(it) }
                runOnUiThread {
                    if (isDestroyed) return@runOnUiThread
                    if (ok) VodActivity.abrirUrl(this, ep.url, ep.titulo)
                    else AvisoTv.rapido(this, ep.titulo, getString(R.string.pod_falhou), 6)
                }
            }
            // ms = 0: só registra que abriu; 1 tirava o ✓ de "ouvido" (guardar() só o mantém com posição 0).
            val p = Podcasts.Posicao(feed, ep.guid, 0, ep.duracaoS * 1000L, false, System.currentTimeMillis(), ep.titulo, nome)
            Podcasts.naFila { Podcasts.guardarPosicao(app, p) }
            return
        }
        val atual = OuvirServico.instancia?.episodio
        if (!doComeco && atual != null && atual.feed == feed && atual.ep.guid == ep.guid) {
            // O que já toca: abre o player (e continua, se estava pausado), como nos apps de podcast.
            if (OuvirServico.instancia?.pausado == true) OuvirServico.instancia?.continuar()
            PodcastPlayerActivity.abrir(this, atual); return
        }
        // De onde parou: o serviço lê a posição gravada (a desta tela pode estar velha).
        val inicio = if (doComeco) 0L else OuvirServico.RETOMAR
        val capa = assinaturas.firstOrNull { it.url == feed }?.capa.orEmpty()
        val e = OuvirServico.EpisodioTocando(feed, nome, capa, ep)
        OuvirServico.tocarEpisodio(this, e, inicio)
        PodcastPlayerActivity.abrir(this, e)
    }

    private fun menuEpisodio(feed: String, ep: PodcastFeed.Episodio) {
        val p = posicoes[Podcasts.chave(feed, ep.guid)]
        val ouvido = p?.ouvido == true
        val nome = nomeDe(feed)
        val opcoes = listOf(getString(R.string.pod_do_comeco), getString(if (ouvido) R.string.pod_marcar_nao else R.string.pod_marcar_ouvido)) +
            (if (aberto == null) listOf(getString(R.string.ouv_ver_podcast)) else emptyList())
        Estilo.lista(this, ep.titulo, opcoes) { i ->
            if (i == 0) { tocar(feed, ep, doComeco = true); return@lista }
            if (i == 2) { abrir(feed); return@lista }
            val app = applicationContext
            val dur = ep.duracaoS * 1000L
            // Desmarcar = começa do zero de novo (sai de "ouvido" e de "Continuar").
            val nova = Podcasts.Posicao(feed, ep.guid, if (ouvido) 0 else maxOf(dur, 1), dur, !ouvido, System.currentTimeMillis(), ep.titulo, nome)
            // Marcar o que está tocando: para antes (a gravação do parar entra na fila antes), senão a gravação periódica
            // de 15 s gravava "ms > 0, não ouvido" por cima e a marca sumia.
            OuvirServico.instancia?.let { s -> s.episodio?.let { if (it.feed == feed && it.ep.guid == ep.guid) s.parar() } }
            Podcasts.naFila {
                // guardar() não tira o "ouvido" sozinho: para desmarcar, grava a posição nova por cima.
                Podcasts.editarPosicoes(app) { m -> LinkedHashMap(m).apply { this[Podcasts.chave(feed, ep.guid)] = nova } }
                runOnUiThread { recarregar() }
            }
        }
    }

    private fun menuPodcast(a: Podcasts.Assinatura) {
        Estilo.lista(this, a.titulo, listOf(getString(R.string.pod_abrir), getString(R.string.pod_remover))) { i ->
            if (i == 0) abrir(a.url) else remover(a)
        }
    }

    private fun remover(a: Podcasts.Assinatura) {
        val app = applicationContext
        Fundo.rodar("podcast") {
            Podcasts.desassinar(app, a.url)
            runOnUiThread {
                AvisoTv.rapido(app, a.titulo, getString(R.string.pod_removido), 3)
                if (aberto == a.url) { aberto = null; focarDepois = "meus" to null }
                recarregar()
            }
        }
    }

    private fun pedirEndereco() {
        val campo = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true; textSize = 20f; hint = "https://" }
        val moldura = FrameLayout(this).apply { setPaddingRelative(Estilo.dp(this@PodcastsActivity, 20), Estilo.dp(this@PodcastsActivity, 8), Estilo.dp(this@PodcastsActivity, 20), 0); addView(campo) }
        val d = CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.pod_colar)).setView(moldura)
            .setPositiveButton(android.R.string.ok) { _, _ -> adicionar(campo.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null).create()
        CampoTv.enterConfirma(campo, d)
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }

    private fun pedirBusca() {
        val campo = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; isSingleLine = true; textSize = 20f }
        val moldura = FrameLayout(this).apply { setPaddingRelative(Estilo.dp(this@PodcastsActivity, 20), Estilo.dp(this@PodcastsActivity, 8), Estilo.dp(this@PodcastsActivity, 20), 0); addView(campo) }
        val d = CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.pod_buscar)).setView(moldura)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = campo.text.toString().trim()
                if (t.length >= 2) procurar(getString(R.string.ra_resultado, t)) { PodcastBusca.buscar(t, Locale.getDefault().language) }
            }
            .setNegativeButton(android.R.string.cancel, null).create()
        CampoTv.enterConfirma(campo, d)
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }

    private fun procurar(titulo: String, buscar: () -> List<PodcastBusca.Resultado>) {
        val app = applicationContext
        Fundo.rodar("podcast") {
            val r = runCatching(buscar).getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                resultados = titulo to r
                if (r.isEmpty()) AvisoTv.rapido(app, titulo, getString(R.string.ra_nada), 5)
                focarDepois = "res" to null
                montar()
            }
        }
    }

    private fun adicionar(url: String) {
        if (url.length < 8) return
        val app = applicationContext
        AvisoTv.rapido(app, getString(R.string.pod_titulo), getString(R.string.pod_adicionando), 3)
        Fundo.rodar("podcast") {
            val r = Podcasts.adicionar(app, url)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                r.onSuccess { nome ->
                    AvisoTv.rapido(app, nome, getString(R.string.pod_adicionado), 4)
                    // Abre o podcast recém-seguido com o foco no episódio MAIS NOVO (visto na TV 17/11/2026: o foco
                    // ficava em "Colar o endereço", e o OK seguinte pegava o episódio mais antigo).
                    aberto = Podcasts.normalizar(url); focarDepois = "eps" to null
                    recarregar()
                }
                 .onFailure { AvisoTv.rapido(app, getString(R.string.pod_colar), getString(R.string.pod_nao_deu, it.message ?: "?"), 8) }
            }
        }
    }

    // ---------------------------------------------------------------- textos

    private fun rotuloVelocidade(v: Float) = String.format(Locale.getDefault(), "%.2fx", v).replace(Regex("0+x$"), "x").replace(Regex("[.,]x$"), "x")

    private fun tempo(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** "58 min", "1 h 02". */
    private fun duracaoCurta(ms: Long): String {
        val min = ((ms + 59_999) / 60_000).coerceAtLeast(1)
        return if (min >= 60) getString(R.string.pod_horas, min / 60, String.format(Locale.ROOT, "%02d", min % 60)) else getString(R.string.pod_minutos, min)
    }

    private fun duracaoEp(ep: PodcastFeed.Episodio): String? = if (ep.duracaoS > 0) duracaoCurta(ep.duracaoS * 1000L) else null

    private fun quando(ms: Long): String? {
        if (ms <= 0) return null
        return when {
            DateUtils.isToday(ms) -> getString(R.string.pod_hoje)
            DateUtils.isToday(ms + DateUtils.DAY_IN_MILLIS) -> getString(R.string.pod_ontem)
            else -> DateFormat.getDateInstance(DateFormat.MEDIUM, Locale.getDefault()).format(Date(ms))
        }
    }
}
