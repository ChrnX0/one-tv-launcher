package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.text.InputType
import android.text.format.DateUtils
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * Tela "Podcasts" ("Ouvir" parte 2, 17/11/2026), aberta pela tela de Rádios (prévia, atrás do ajuste "Podcasts" de lá até
 * o dono decidir onde mora). Uma lista só, na mesma letra grande da rádio:
 *  - Tocando: o episódio, onde está ("12:30 de 58:00 · 1,2x"); OK pausa/continua; ◀ ▶ nesta linha voltam 15 s e
 *    avançam 30 s. Embaixo: Parar, Velocidade (0,8x a 2x, sem mudar o tom) e Pular silêncios.
 *  - Continuar (começados), Episódios novos (14 dias, nunca começados), Meus podcasts, Adicionar.
 *  - Um podcast aberto mostra os episódios; segurar OK = marcar ouvido / tocar do começo / tirar a assinatura.
 * Episódio em vídeo abre no player de vídeo. Explícito some com o modo infantil ou o controle dos pais ligado.
 * Quem toca é o [OuvirServico] (o som segue na HOME e no protetor). Dados: [Podcasts]; leitura do feed: [PodcastFeed].
 */
class PodcastsActivity : Activity() {
    private lateinit var coluna: LinearLayout
    private lateinit var subtitulo: TextView
    private var assinaturas: List<Podcasts.Assinatura> = emptyList()
    private var feeds: Map<String, PodcastFeed.Feed> = emptyMap()
    private var posicoes: Map<String, Podcasts.Posicao> = emptyMap()
    private var protegido = false
    /** Resultado da busca (título da seção, lista), só com a busca ligada ([PodcastBusca]). */
    private var resultados: Pair<String, List<PodcastBusca.Resultado>>? = null
    /** O podcast aberto (lista de episódios), ou null = a tela principal. */
    private var aberto: String? = null
    /** Na próxima montagem, o foco vai para o 1º episódio (o mais novo) e a lista volta ao topo. */
    private var focarPrimeiroEp = false
    private val mudou: () -> Unit = { aoMudarSom() }
    private var visto: Any? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val relogio = object : Runnable {
        override fun run() { atualizarLinhaTocando(); ui.postDelayed(this, 1_000) }
    }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        val (raiz, col) = Estilo.telaLista(this, getString(R.string.pod_titulo), getString(R.string.pod_subtitulo))
        coluna = col
        subtitulo = (raiz as LinearLayout).getChildAt(1) as TextView
        raiz.addView(Estilo.barraDicas(this, getString(R.string.grv_dica_ok) to getString(R.string.ra_dica_ouvir),
            getString(R.string.grv_dica_segurar) to getString(R.string.pod_dica_mais)),
            LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = Estilo.dp(this@PodcastsActivity, Estilo.MARGEM_VERTICAL) })
        setContentView(FrameLayout(this).apply { addView(raiz, FrameLayout.LayoutParams(-1, -1)) })
        recarregar(buscarNovos = true)
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
        if (aberto != null) { aberto = null; montar(); return }
        @Suppress("DEPRECATION") super.onBackPressed()
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // ◀ ▶ na linha "Tocando" pulam no episódio (fora dela, mexem o foco como sempre).
        if (e.action == KeyEvent.ACTION_DOWN && coluna.focusedChild?.tag == "tocando" && OuvirServico.instancia?.episodio != null) {
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> { OuvirServico.instancia?.pular(-OuvirServico.VOLTAR_MS); atualizarLinhaTocando(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { OuvirServico.instancia?.pular(OuvirServico.AVANCAR_MS); atualizarLinhaTocando(); return true }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- dados

    /** Relê tudo do disco. Na fila dos podcasts: vem depois de qualquer posição que o serviço mandou gravar antes. */
    private fun recarregar(buscarNovos: Boolean = false) {
        val app = applicationContext
        Podcasts.naFila {
            val a = Podcasts.assinaturas(app); val f = Podcasts.feeds(app); val p = Podcasts.posicoes(app); val prot = Podcasts.protegido(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                assinaturas = a; feeds = f; posicoes = p; protegido = prot
                montar()
            }
            if (buscarNovos && a.isNotEmpty()) Fundo.rodar("podcast") {   // rede: fora da fila
                // Feeds lidos há mais de 6 h: relê (pedido condicional, quase sempre "nada novo").
                runOnUiThread { if (!isDestroyed) subtitulo.text = getString(R.string.pod_atualizando) }
                val mudaram = runCatching { Podcasts.atualizar(app) }.getOrDefault(0)
                runOnUiThread { if (!isDestroyed) subtitulo.text = getString(R.string.pod_subtitulo) }
                if (mudaram > 0) runOnUiThread { if (!isDestroyed) recarregar() }
            }
        }
    }

    // ---------------------------------------------------------------- tela

    private fun montar() {
        val focoAntes = coluna.focusedChild?.tag
        coluna.removeAllViews()
        linhasTocando()
        val url = aberto
        if (url != null) montarPodcast(url) else montarPrincipal()
        val filhos = (0 until coluna.childCount).map { coluna.getChildAt(it) }
        val primeiroEp = if (focarPrimeiroEp) filhos.firstOrNull { (it.tag as? String)?.startsWith("ep:") == true } else null
        if (primeiroEp != null) {
            focarPrimeiroEp = false
            (coluna.parent as? android.widget.ScrollView)?.scrollTo(0, 0)
            primeiroEp.requestFocus(); return
        }
        (filhos.firstOrNull { it.tag != null && it.tag == focoAntes } ?: filhos.firstOrNull { it.isFocusable })?.requestFocus()
    }

    private fun linhasTocando() {
        val s = OuvirServico.instancia ?: return
        val e = s.episodio ?: return
        linha("tocando", "● " + e.ep.titulo, textoTocando(s), getString(if (s.pausado) R.string.ra_continuar else R.string.ra_pausar),
            cor = Estilo.ATENCAO, ok = { s.alternar() }, segurar = null)
        linha("parar", "■ " + getString(R.string.ra_parar), getString(R.string.pod_dica_pular), null, ok = { s.parar() }, segurar = null)
        val v = OuvirServico.velocidade(this)
        linha("velocidade", getString(R.string.pod_velocidade), null, rotuloVelocidade(v), ok = {
            Estilo.lista(this, getString(R.string.pod_velocidade), OuvirServico.VELOCIDADES.map { rotuloVelocidade(it) },
                listOf(OuvirServico.VELOCIDADES.indexOf(v))) { i -> OuvirServico.instancia?.definirVelocidade(OuvirServico.VELOCIDADES[i]); montar() }
        }, segurar = null)
        val sil = OuvirServico.pularSilencio(this)
        linha("silencio", getString(R.string.pod_silencio), getString(R.string.pod_silencio_det),
            getString(if (sil) R.string.pod_ligado else R.string.pod_desligado), ok = { OuvirServico.instancia?.definirPularSilencio(!sil); montar() }, segurar = null)
    }

    private fun textoTocando(s: OuvirServico): String {
        val e = s.episodio ?: return ""
        val dur = s.duracaoMs
        val pos = if (dur > 0) getString(R.string.pod_de, tempo(s.posicaoMs), tempo(dur)) else tempo(s.posicaoMs)
        val v = OuvirServico.velocidade(this)
        return listOfNotNull(e.podcast.ifEmpty { null }, pos, if (v != 1f) rotuloVelocidade(v) else null).joinToString(" · ")
    }

    /** A cada segundo: só o texto da linha "Tocando" (sem refazer a lista, que perderia o foco). */
    private fun atualizarLinhaTocando() {
        val s = OuvirServico.instancia ?: return
        if (s.episodio == null) return
        val v = (0 until coluna.childCount).map { coluna.getChildAt(it) }.firstOrNull { it.tag == "tocando" } as? LinearLayout ?: return
        val textos = v.getChildAt(0) as? LinearLayout ?: return
        (textos.getChildAt(1) as? TextView)?.text = textoTocando(s)
    }

    private fun aoMudarSom() {
        if (isDestroyed || !::coluna.isInitialized) return
        val s = OuvirServico.instancia
        if (s?.episodio != null && s.tocando) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val agora = listOf(s?.episodio?.ep?.guid, s?.pausado, OuvirServico.velocidade(this), OuvirServico.pularSilencio(this))
        if (agora != visto) {
            // Parou ou trocou de episódio: a posição do anterior foi mandada gravar; relê (na fila, depois da gravação)
            // para "Continuar", "faltam" e "ouvido" saírem certos.
            val trocou = visto != null && (visto as List<*>)[0] != agora[0]
            visto = agora
            if (trocou) recarregar() else montar()
        }
    }

    private fun montarPrincipal() {
        resultados?.let { (titulo, lista) ->
            secao(titulo)
            lista.filter { !(protegido && it.explicito) }.forEach { r ->
                val seguido = assinaturas.any { it.url == r.url }
                linha("busca:${r.url}", r.titulo, r.autor, if (seguido) "✓" else null,
                    ok = { if (seguido) { aberto = r.url; montar() } else adicionar(r.url) }, segurar = null)
            }
        }
        val assinados = assinaturas.map { it.url }.toSet()
        val cont = Podcasts.continuar(posicoes, assinados)
            .mapNotNull { p -> feeds[p.feed]?.episodios?.firstOrNull { it.guid == p.guid }?.let { Triple(p.feed, it, p) } }
            .filter { Podcasts.visivel(it.second, protegido) }
        if (cont.isNotEmpty()) {
            secao(getString(R.string.pod_continuar))
            cont.forEach { (feed, ep, p) ->
                val nome = assinaturas.firstOrNull { it.url == feed }?.titulo.orEmpty()
                val falta = if (p.durMs > 0) getString(R.string.pod_faltam, duracaoCurta(p.durMs - p.ms)) else null
                linha("cont:${Podcasts.chave(feed, ep.guid)}", ep.titulo, listOfNotNull(nome, falta).joinToString(" · "), null,
                    ok = { tocar(feed, ep) }, segurar = { menuEpisodio(feed, ep) })
            }
        }
        val novos = Podcasts.novos(feeds, posicoes, System.currentTimeMillis()).filter { Podcasts.visivel(it.second, protegido) }
        if (novos.isNotEmpty()) {
            secao(getString(R.string.pod_novos))
            novos.forEach { (feed, ep) ->
                val nome = assinaturas.firstOrNull { it.url == feed }?.titulo.orEmpty()
                linha("novo:${Podcasts.chave(feed, ep.guid)}", ep.titulo, listOfNotNull(nome, quando(ep.dataMs), duracaoEp(ep)).joinToString(" · "),
                    if (ep.video) getString(R.string.pod_video) else null, ok = { tocar(feed, ep) }, segurar = { menuEpisodio(feed, ep) })
            }
        }
        secao(getString(R.string.pod_meus))
        if (assinaturas.isEmpty()) coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.pod_vazio)).apply {
            setPadding(Estilo.dp(this@PodcastsActivity, 16), Estilo.dp(this@PodcastsActivity, 8), 0, Estilo.dp(this@PodcastsActivity, 8)) })
        // Com o modo infantil ou o controle dos pais, podcast marcado como explícito nem aparece.
        assinaturas.filter { !(protegido && it.explicito) }.forEach { a ->
            val f = feeds[a.url]
            val det = listOfNotNull(a.autor.ifEmpty { null }, f?.episodios?.firstOrNull()?.let { quando(it.dataMs) }?.let { getString(R.string.pod_ultimo, it) })
            linha("pod:${a.url}", a.titulo, det.joinToString(" · "), null, ok = { aberto = a.url; montar() }, segurar = { menuPodcast(a) })
        }
        // Buscar é do Pro (Paulo, 17/11/2026); seguir pelo endereço ou pelo OPML é do Básico.
        if (PodcastBusca.disponivel() && Planos.permite(this, Planos.Recurso.PODCAST_BUSCA)) {
            // Busca pelo servidor nosso (Podcast Index). A atribuição vai na explicação, como os termos pedem.
            secao(getString(R.string.pod_procurar))
            linha("buscar", getString(R.string.pod_buscar), getString(R.string.pod_atribuicao), null, ok = { pedirBusca() }, segurar = null)
            linha("em_alta", getString(R.string.pod_em_alta), getString(R.string.pod_atribuicao), null, ok = {
                procurar(getString(R.string.pod_em_alta)) { PodcastBusca.emAlta(Locale.getDefault().language) }
            }, segurar = null)
        }
        secao(getString(R.string.pod_adicionar))
        linha("colar", getString(R.string.pod_colar), getString(R.string.pod_colar_det), null, ok = { pedirEndereco() }, segurar = null)
        linha("celular", getString(R.string.pod_pelo_celular), getString(R.string.pod_pelo_celular_det), null, ok = {
            AvisoTv.rapido(this, getString(R.string.pod_pelo_celular), getString(R.string.pod_pelo_celular_det), 8)
        }, segurar = null)
    }

    private fun montarPodcast(url: String) {
        val a = assinaturas.firstOrNull { it.url == url }
        val f = feeds[url]
        secao(a?.titulo ?: f?.titulo ?: url)
        f?.descricao?.takeIf { it.isNotBlank() }?.let { d ->
            coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, d).apply {
                maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(Estilo.dp(this@PodcastsActivity, 16), 0, Estilo.dp(this@PodcastsActivity, 16), Estilo.dp(this@PodcastsActivity, 8))
            })
        }
        val eps = f?.episodios.orEmpty().filter { Podcasts.visivel(it, protegido) }
        if (eps.isEmpty()) coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.pod_sem_episodios)))
        eps.take(100).forEach { ep ->
            val p = posicoes[Podcasts.chave(url, ep.guid)]
            val estado = when {
                p?.ouvido == true -> "✓ " + getString(R.string.pod_ouvido)
                p != null && p.ms >= Podcasts.MIN_CONTINUAR_MS && p.durMs > 0 -> getString(R.string.pod_faltam, duracaoCurta(p.durMs - p.ms))
                else -> duracaoEp(ep)
            }
            linha("ep:${ep.guid}", ep.titulo, listOfNotNull(quando(ep.dataMs), estado).joinToString(" · "),
                if (ep.video) getString(R.string.pod_video) else null, ok = { tocar(url, ep) }, segurar = { menuEpisodio(url, ep) })
        }
    }

    private fun secao(t: String) { coluna.addView(Estilo.secao(this, t)) }

    private fun linha(chave: String, titulo: String, explica: String?, direita: String?, cor: Int? = null, ok: () -> Unit, segurar: (() -> Unit)?) {
        val dir = direita?.let { Estilo.valor(this, it, seta = false) }
        val v = Estilo.linhaOpcao(this, titulo, explica?.ifEmpty { null }, dir).apply {
            tag = chave
            setOnClickListener { ok() }
            if (segurar != null) setOnLongClickListener { segurar(); true }
        }
        if (cor != null) ((v.getChildAt(0) as LinearLayout).getChildAt(0) as TextView).setTextColor(cor)
        coluna.addView(v, Estilo.lpLinha(this))
    }

    // ---------------------------------------------------------------- ações

    private fun tocar(feed: String, ep: PodcastFeed.Episodio, doComeco: Boolean = false) {
        val nome = assinaturas.firstOrNull { it.url == feed }?.titulo ?: feeds[feed]?.titulo.orEmpty()
        if (ep.video) {
            // Vídeo no feed: o player de vídeo (tela cheia, legenda, velocidade). A posição fica com ele.
            OuvirServico.instancia?.parar()
            VodActivity.abrirUrl(this, ep.url, ep.titulo)
            val app = applicationContext
            val p = Podcasts.Posicao(feed, ep.guid, 1, ep.duracaoS * 1000L, false, System.currentTimeMillis(), ep.titulo, nome)
            Podcasts.naFila { Podcasts.guardarPosicao(app, p) }
            return
        }
        // De onde parou: o serviço lê a posição gravada (a desta tela pode estar velha).
        val inicio = if (doComeco) 0L else OuvirServico.RETOMAR
        val capa = assinaturas.firstOrNull { it.url == feed }?.capa.orEmpty()
        OuvirServico.tocarEpisodio(this, OuvirServico.EpisodioTocando(feed, nome, capa, ep), inicio)
    }

    private fun menuEpisodio(feed: String, ep: PodcastFeed.Episodio) {
        val p = posicoes[Podcasts.chave(feed, ep.guid)]
        val ouvido = p?.ouvido == true
        val nome = assinaturas.firstOrNull { it.url == feed }?.titulo.orEmpty()
        Estilo.lista(this, ep.titulo, listOf(getString(R.string.pod_do_comeco),
            getString(if (ouvido) R.string.pod_marcar_nao else R.string.pod_marcar_ouvido))) { i ->
            if (i == 0) { tocar(feed, ep, doComeco = true); return@lista }
            val app = applicationContext
            val dur = ep.duracaoS * 1000L
            // Desmarcar = começa do zero de novo (sai de "ouvido" e de "Continuar").
            val nova = Podcasts.Posicao(feed, ep.guid, if (ouvido) 0 else maxOf(dur, 1), dur, !ouvido, System.currentTimeMillis(), ep.titulo, nome)
            Podcasts.naFila {
                // guardar() não tira o "ouvido" sozinho: para desmarcar, grava a posição nova por cima.
                Podcasts.editarPosicoes(app) { m -> LinkedHashMap(m).apply { this[Podcasts.chave(feed, ep.guid)] = nova } }
                runOnUiThread { recarregar() }
            }
        }
    }

    private fun menuPodcast(a: Podcasts.Assinatura) {
        Estilo.lista(this, a.titulo, listOf(getString(R.string.pod_abrir), getString(R.string.pod_remover))) { i ->
            if (i == 0) { aberto = a.url; montar(); return@lista }
            val app = applicationContext
            Fundo.rodar("podcast") {
                Podcasts.desassinar(app, a.url)
                runOnUiThread { AvisoTv.rapido(app, a.titulo, getString(R.string.pod_removido), 3); recarregar() }
            }
        }
    }

    private fun pedirEndereco() {
        val campo = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI; isSingleLine = true; textSize = 20f; hint = "https://" }
        val moldura = FrameLayout(this).apply { setPadding(Estilo.dp(this@PodcastsActivity, 20), Estilo.dp(this@PodcastsActivity, 8), Estilo.dp(this@PodcastsActivity, 20), 0); addView(campo) }
        val d = CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.pod_colar)).setView(moldura)
            .setPositiveButton(android.R.string.ok) { _, _ -> adicionar(campo.text.toString().trim()) }
            .setNegativeButton(android.R.string.cancel, null).create()
        enterConfirma(campo, d)
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }

    private fun pedirBusca() {
        val campo = EditText(this).apply { inputType = InputType.TYPE_CLASS_TEXT; isSingleLine = true; textSize = 20f }
        val moldura = FrameLayout(this).apply { setPadding(Estilo.dp(this@PodcastsActivity, 20), Estilo.dp(this@PodcastsActivity, 8), Estilo.dp(this@PodcastsActivity, 20), 0); addView(campo) }
        val d = CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert).setTitle(getString(R.string.pod_buscar)).setView(moldura)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = campo.text.toString().trim()
                if (t.length >= 2) procurar(getString(R.string.ra_resultado, t)) { PodcastBusca.buscar(t, Locale.getDefault().language) }
            }
            .setNegativeButton(android.R.string.cancel, null).create()
        enterConfirma(campo, d)
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }

    /**
     * Enter (ou "OK"/"Concluído" do teclado da TV) no campo = apertar OK. Visto na TV 17/11/2026: sem isto o Enter só
     * passava o foco adiante, e ele caía em Cancelar (o primeiro botão).
     */
    private fun enterConfirma(campo: EditText, d: CaixaTv.Janela) {
        campo.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        val ok = { d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.performClick(); true }
        campo.setOnEditorActionListener { _, _, _ -> ok() }
        campo.setOnKeyListener { _, tecla, e ->
            // A descida também é nossa: solta, ela andaria com o foco para o botão de baixo antes da subida chegar.
            if (tecla != KeyEvent.KEYCODE_ENTER && tecla != KeyEvent.KEYCODE_NUMPAD_ENTER) false
            else { if (e.action == KeyEvent.ACTION_UP) ok(); true }
        }
    }

    private fun procurar(titulo: String, buscar: () -> List<PodcastBusca.Resultado>) {
        subtitulo.text = getString(R.string.ra_carregando)
        val app = applicationContext
        Fundo.rodar("podcast") {
            val r = runCatching(buscar).getOrDefault(emptyList())
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                subtitulo.text = getString(R.string.pod_subtitulo)
                resultados = titulo to r
                if (r.isEmpty()) AvisoTv.rapido(app, titulo, getString(R.string.ra_nada), 5)
                montar()
            }
        }
    }

    private fun adicionar(url: String) {
        if (url.length < 8) return
        val app = applicationContext
        subtitulo.text = getString(R.string.pod_adicionando)
        Fundo.rodar("podcast") {
            val r = Podcasts.adicionar(app, url)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                subtitulo.text = getString(R.string.pod_subtitulo)
                r.onSuccess { nome ->
                    AvisoTv.rapido(app, nome, getString(R.string.pod_adicionado), 4)
                    // Abre o podcast recém-seguido com o foco no episódio MAIS NOVO (visto na TV 17/11/2026: o foco
                    // ficava em "Colar o endereço", no fim da lista, e o OK seguinte pegava o episódio mais antigo).
                    aberto = Podcasts.normalizar(url); focarPrimeiroEp = true
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
