package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.SystemClock
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.home.HomeCruz
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.listen.CapaOuvir
import br.paulo.tvlauncher.listen.CruzOuvir
import br.paulo.tvlauncher.listen.IconeOuvir
import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.PodcastPlayerLogic
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.player.BarraTempo
import br.paulo.tvlauncher.player.Episodio
import br.paulo.tvlauncher.player.PlayerLogos
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.settings.irPara
import java.util.Date

/**
 * O player de podcast em tela cheia (pedido do Paulo, 18/11/2026: "mais com cara de player profissional, mas dentro da
 * nossa identidade visual"). Abre quando um episódio começa na tela de Podcasts e pelo "Tocando agora" de lá.
 *
 *  - capa grande à esquerda, com a própria capa desfocada e escurecida de fundo;
 *  - à direita: TOCANDO AGORA, o podcast, o título (até 2 linhas), data e duração;
 *  - a barra de tempo larga com o tempo passado, quando termina ("termina às 12:58") e quanto falta;
 *  - os controles numa fileira: −15 s, ⏯ grande, +30 s, velocidade, pular silêncios e timer para dormir;
 *  - ▼ abre "A seguir": os próximos episódios do mesmo podcast ([PodcastPlayerLogic.aSeguir]); OK toca um deles.
 *
 * Foco em três andares, sem o foco do Android (como a cruz): 0 = barra (◀ ▶ andam 10 s), 1 = controles (◀ ▶ trocam),
 * 2 = A seguir (◀ ▶ trocam, OK toca). ⏪ ⏩ ⏯ do controle valem em qualquer andar. Mesmo processo do som (":player").
 */
class PodcastPlayerActivity : Activity() {

    companion object {
        private const val EXTRA_EP = "ep"

        fun abrir(ctx: Context, e: OuvirServico.EpisodioTocando? = null) {
            val i = Intent(ctx, PodcastPlayerActivity::class.java)
            if (e != null) i.putExtra(EXTRA_EP, e.paraJson())
            if (ctx !is Activity) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { ctx.startActivity(i) }
        }

        // Medidas (dp) na tela de TV de 960 x 540.
        const val MARGEM = 64
        const val CAPA = 316
        const val TOPO = 70
        const val TEXTO_X = MARGEM + CAPA + 44
        const val TEXTO_L = 960 - TEXTO_X - MARGEM
        const val BOTAO = 60
        const val BOTAO_GRANDE = 84
        const val ESPACO_BOTOES = 16
        const val FONTE_TITULO = 30f
        const val FONTE_PODCAST = 19f
        const val FONTE_INFO = 18f
        const val FONTE_TEMPO = 18f
        const val FONTE_ROTULO_BOTAO = 18f
        const val SEGUIR_CAPA = 64
        const val SEGUIR_L = 266
        const val SEGUIR_N = 3
        const val ANDAR_BARRA = 0; const val ANDAR_BOTOES = 1; const val ANDAR_SEGUIR = 2
    }

    private enum class Acao { VOLTAR, ALTERNAR, AVANCAR, VELOCIDADE, SILENCIO, DORMIR }

    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val relogio = object : Runnable {
        override fun run() { atualizarTempo(); ui.postDelayed(this, 500) }
    }
    private val mudou: () -> Unit = { ui.post { atualizar() } }

    private lateinit var fonte: Typeface
    private lateinit var fonteForte: Typeface
    private lateinit var arte: ImageView
    private lateinit var capa: CapaOuvir
    private lateinit var rotulo: TextView
    private lateinit var podcast: TextView
    private lateinit var titulo: TextView
    private lateinit var info: TextView
    private lateinit var barra: BarraTempo
    private lateinit var passou: TextView
    private lateinit var termina: TextView
    private lateinit var falta: TextView
    private lateinit var botoes: LinearLayout
    private lateinit var rotuloBotao: TextView
    private lateinit var dica: TextView
    private lateinit var painel: LinearLayout
    private lateinit var fila: LinearLayout
    private val vistas = LinkedHashMap<Acao, Botao>()

    private var andar = ANDAR_BOTOES
    private var botao = 1
    private var naFila = 0
    private var aSeguir: List<PodcastFeed.Episodio> = emptyList()
    private var filaDe = ""
    /** Onde o episódio parou da última vez: a barra já mostra isso enquanto o som não começa. */
    private var guardadaMs = 0L
    /** O episódio desta tela: o do serviço quando ele já começou, senão o que veio no Intent. */
    private var ep: OuvirServico.EpisodioTocando? = null
    private var jaTocou = false
    /** O episódio que acabou de ser pedido: até o serviço trocar para ele, o anterior ainda toca e não vale. */
    private var esperado: String? = null
    private val desistir = Runnable { if (!jaTocou) finish() }
    private var tituloVisto = ""

    private val dm by lazy { resources.displayMetrics }
    private fun dp(v: Float) = v * dm.density
    private fun dpi(v: Int) = (v * dm.density).toInt()

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return
        fonte = Fontes.nunito(this, 500); fonteForte = Fontes.nunito(this, 800)
        esperar(OuvirServico.EpisodioTocando.deJson(intent.getStringExtra(EXTRA_EP)))
        if (ep == null) ep = OuvirServico.instancia?.episodio
        setContentView(montar())
        atualizar()
    }

    override fun onNewIntent(i: Intent) {
        super.onNewIntent(i)
        setIntent(i)
        OuvirServico.EpisodioTocando.deJson(i.getStringExtra(EXTRA_EP))?.let { esperar(it) }
        andar = ANDAR_BOTOES; botao = 1
        atualizar()
    }

    override fun onStart() {
        super.onStart()
        OuvirServico.ouvir(mudou)
        ui.post(relogio)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onStop() {
        super.onStop()
        OuvirServico.esquecer(mudou)
        ui.removeCallbacks(relogio)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onDestroy() { ui.removeCallbacksAndMessages(null); OuvirServico.esquecer(mudou); super.onDestroy() }

    /** Mostra [e] já, enquanto o serviço começa; se ele não começar (endereço ruim, rede), a tela sai sozinha. */
    private fun esperar(e: OuvirServico.EpisodioTocando?) {
        e ?: return
        ep = e
        if (OuvirServico.instancia?.episodio?.ep?.guid == e.ep.guid) { jaTocou = true; esperado = null; return }
        jaTocou = false; esperado = e.ep.guid
        ui.removeCallbacks(desistir); ui.postDelayed(desistir, 20_000)
    }

    // ---------------------------------------------------------------- montagem

    private fun texto(sp: Float, cor: Int, forte: Boolean = false, linhas: Int = 1) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(cor); typeface = if (forte) fonteForte else fonte
        includeFontPadding = false; maxLines = linhas
        setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
    }

    private fun montar(): View {
        // A raiz guarda o foco (as teclas são tratadas no dispatchKeyEvent; nenhuma view do Android anda com elas).
        val raiz = FrameLayout(this).apply {
            background = Estilo.fundoTela(this@PodcastPlayerActivity); clipChildren = false
            isFocusable = true; isFocusableInTouchMode = true; descendantFocusability = android.view.ViewGroup.FOCUS_BLOCK_DESCENDANTS
        }
        raiz.post { raiz.requestFocus() }

        arte = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        raiz.addView(arte, FrameLayout.LayoutParams(-1, -1))
        // Véus: a capa desfocada dá a cor da tela, mas o texto e os botões ficam sempre legíveis.
        raiz.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x88121528.toInt(), 0xCC121528.toInt(), 0xE6121528.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))
        raiz.addView(View(this).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x00121528, 0x00121528, 0xF0121528.toInt()))
        }, FrameLayout.LayoutParams(-1, -1))

        capa = CapaOuvir(this, 22f).apply { fonte(fonteForte); cobrir = true; elevation = dp(14f) }
        raiz.addView(capa, FrameLayout.LayoutParams(dpi(CAPA), dpi(CAPA)).apply { marginStart = dpi(MARGEM); topMargin = dpi(TOPO) })

        rotulo = texto(15f, Estilo.TEXTO_2, forte = true).apply { letterSpacing = 0.16f }
        podcast = texto(FONTE_PODCAST, HomeCruz.COR_SUB, forte = true)
        titulo = texto(FONTE_TITULO, Estilo.TEXTO, forte = true, linhas = 2).apply { setLineSpacing(dp(3f), 1f) }
        info = texto(FONTE_INFO, HomeCruz.COR_SUB)
        barra = BarraTempo(this)
        passou = texto(FONTE_TEMPO, Estilo.TEXTO)
        termina = texto(FONTE_TEMPO, HomeCruz.COR_SUB).apply { gravity = Gravity.CENTER_HORIZONTAL }
        falta = texto(FONTE_TEMPO, Estilo.TEXTO).apply { gravity = Gravity.END }
        val tempos = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(passou, LinearLayout.LayoutParams(0, -2, 1f))
            addView(termina, LinearLayout.LayoutParams(0, -2, 2f))
            addView(falta, LinearLayout.LayoutParams(0, -2, 1f))
        }
        botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false; clipToPadding = false }
        for (a in Acao.entries) {
            val v = Botao(this, if (a == Acao.ALTERNAR) BOTAO_GRANDE else BOTAO, a == Acao.ALTERNAR)
            vistas[a] = v
            botoes.addView(v, LinearLayout.LayoutParams(dpi(v.lado), dpi(v.lado)).apply { if (a != Acao.VOLTAR) marginStart = dpi(ESPACO_BOTOES) })
        }
        rotuloBotao = texto(FONTE_ROTULO_BOTAO, Estilo.TEXTO, forte = true)

        val coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            addView(rotulo, LinearLayout.LayoutParams(-1, -2))
            addView(podcast, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(12) })
            addView(titulo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8) })
            addView(info, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8) })
            addView(barra, LinearLayout.LayoutParams(-1, dpi(26)).apply { topMargin = dpi(20) })
            addView(tempos, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4) })
            addView(botoes, LinearLayout.LayoutParams(-2, dpi(BOTAO_GRANDE + 8)).apply { topMargin = dpi(16) })
            addView(rotuloBotao, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(8) })
        }
        raiz.addView(coluna, FrameLayout.LayoutParams(dpi(TEXTO_L), -2).apply { marginStart = dpi(TEXTO_X); topMargin = dpi(TOPO) })

        // Pé: "▼ A seguir: <próximo>" enquanto a fila está fechada.
        dica = texto(FONTE_INFO, HomeCruz.COR_SUB)
        raiz.addView(dica, FrameLayout.LayoutParams(dpi(960 - 2 * MARGEM), -2, Gravity.BOTTOM or Gravity.START).apply { marginStart = dpi(MARGEM); bottomMargin = dpi(28) })

        // A fila "A seguir": sobe do pé com ▼.
        fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
        painel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            setPaddingRelative(dpi(MARGEM), dpi(14), dpi(MARGEM), dpi(22))
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0xE6121528.toInt(), 0xFA121528.toInt()))
            addView(texto(15f, Estilo.TEXTO_2, forte = true).apply { letterSpacing = 0.16f; text = getString(R.string.pl_a_seguir).uppercase() },
                LinearLayout.LayoutParams(-1, -2))
            addView(fila, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(10) })
            visibility = View.INVISIBLE
        }
        raiz.addView(painel, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return raiz
    }

    // ---------------------------------------------------------------- estado

    /** Episódio ou estado do som mudou: redesenha o que depende dele. */
    private fun atualizar() {
        val s = OuvirServico.instancia
        val doServico = s?.episodio
        if (doServico != null && (esperado == null || doServico.ep.guid == esperado)) { ep = doServico; jaTocou = true; esperado = null }
        else if (doServico == null && jaTocou) { finish(); return }   // parou (fim, ⏹ na HOME, notificação): a tela sai junto
        val e = ep ?: run { finish(); return }

        val meu = jaTocou && doServico != null
        val pausado = meu && s?.pausado == true
        podcast.text = e.podcast
        if (tituloVisto != e.ep.titulo) {
            tituloVisto = e.ep.titulo
            titulo.text = caber(e.ep.titulo, titulo, dpi(TEXTO_L), 2)
        }
        info.text = listOfNotNull(
            e.ep.dataMs.takeIf { it > 0 }?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM).format(Date(it)) },
            e.ep.temporada.takeIf { it > 0 && e.ep.numero > 0 }?.let { getString(R.string.pod_temp_ep, it, e.ep.numero) },
            e.ep.duracaoS.takeIf { it > 0 }?.let { getString(R.string.pod_minutos, maxOf(1, (it + 59) / 60)) },
        ).joinToString(" · ")
        val endereco = e.capaPodcast.ifEmpty { e.ep.capa }
        capa.monograma = CapaOuvir.iniciais(e.podcast)
        capa.cor = IconeOuvir.cor(IconeOuvir.Tipo.PODCAST)
        capa.carregar(endereco, 480)
        if (arte.tag != endereco) {
            arte.tag = endereco
            if (endereco.isEmpty()) arte.setImageDrawable(null) else PlayerLogos.carregar(this, endereco, arte, CruzOuvir.ARTE_LADO)
            arte.animate().alpha(if (endereco.isEmpty()) 0f else 0.55f).setDuration(500).start()
        }

        vistas[Acao.ALTERNAR]?.icone(if (pausado) IconeOuvir.Tipo.CONTINUAR else IconeOuvir.Tipo.PAUSAR)
        vistas[Acao.VOLTAR]?.icone(IconeOuvir.Tipo.VOLTAR)
        vistas[Acao.AVANCAR]?.icone(IconeOuvir.Tipo.AVANCAR)
        vistas[Acao.VELOCIDADE]?.escrito(PodcastPlayerLogic.rotuloVelocidade(OuvirServico.velocidade(this)))
        vistas[Acao.SILENCIO]?.apply { icone(IconeOuvir.Tipo.SILENCIO); ligado = OuvirServico.pularSilencio(this@PodcastPlayerActivity) }
        vistas[Acao.DORMIR]?.apply { icone(IconeOuvir.Tipo.DORMIR); ligado = (s?.dormirAte ?: 0L) > 0L }

        carregarFila(e)
        atualizarTempo()
        desenharFoco(animar = true)
    }

    /** A cada meio segundo: a barra, os tempos e o timer (sem medir de novo a tela: os textos têm largura fixa). */
    private fun atualizarTempo() {
        val s = OuvirServico.instancia
        val e = ep ?: return
        val tocando = s?.episodio?.ep?.guid == e.ep.guid
        val pos = if (tocando) s!!.posicaoMs else guardadaMs
        val dur = if (tocando) s!!.duracaoMs else e.ep.duracaoS * 1000L
        val v = OuvirServico.velocidade(this)
        barra.valor = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        passou.text = PodcastPlayerLogic.tempo(pos)
        falta.text = PodcastPlayerLogic.falta(pos, dur, v)
        val fim = PodcastPlayerLogic.terminaEm(System.currentTimeMillis(), pos, dur, v)
        val dormir = PodcastPlayerLogic.minutosParaDormir(s?.dormirAte ?: 0L, SystemClock.elapsedRealtime())
        termina.text = fim?.let { getString(R.string.pl_termina, DateFormat.getTimeFormat(this).format(Date(it))) }.orEmpty()
        // O timer vai no rótulo de cima (largo): no meio da linha dos tempos "dorme em 15 min" era cortado (Sala, v463223).
        val pausado = jaTocou && s?.episodio != null && s.pausado
        rotulo.text = listOfNotNull(
            getString(if (pausado) R.string.ouv_pausado else R.string.ouv_tocando_agora),
            dormir.takeIf { it > 0 }?.let { getString(R.string.pl_dorme_em, it) },
        ).joinToString("  ·  ").uppercase()
        if (andar != ANDAR_SEGUIR) rotuloBotao.text = rotuloDoBotao(Acao.entries[botao])
    }

    private fun rotuloDoBotao(a: Acao): String = when (a) {
        Acao.VOLTAR -> getString(R.string.ouv_voltar)
        Acao.ALTERNAR -> getString(if (OuvirServico.instancia?.pausado == true) R.string.ouv_continuar else R.string.ouv_pausar)
        Acao.AVANCAR -> getString(R.string.ouv_avancar)
        Acao.VELOCIDADE -> getString(R.string.pod_velocidade) + " · " + PodcastPlayerLogic.rotuloVelocidade(OuvirServico.velocidade(this))
        Acao.SILENCIO -> getString(R.string.pod_silencio) + " · " +
            getString(if (OuvirServico.pularSilencio(this)) R.string.pod_ligado else R.string.pod_desligado)
        Acao.DORMIR -> {
            val m = PodcastPlayerLogic.minutosParaDormir(OuvirServico.instancia?.dormirAte ?: 0L, SystemClock.elapsedRealtime())
            getString(R.string.pl_dormir) + " · " + (if (m > 0) getString(R.string.pl_dorme_em, m) else getString(R.string.pod_desligado))
        }
    }

    /** Lê o feed do podcast (disco, fora da tela) e monta a fila "A seguir". */
    private fun carregarFila(e: OuvirServico.EpisodioTocando) {
        val chave = e.feed + "\u0001" + e.ep.guid
        if (filaDe == chave) return
        filaDe = chave
        guardadaMs = 0L
        val app = applicationContext
        Podcasts.naFila {
            val f = Podcasts.feed(app, e.feed)
            val pos = Podcasts.posicoes(app)
            val prot = Podcasts.protegido(app)
            val l = PodcastPlayerLogic.aSeguir(f?.episodios.orEmpty().filter { Podcasts.visivel(it, prot) }, e.ep.guid,
                { x -> pos[Podcasts.chave(e.feed, x.guid)]?.ouvido == true })
            val g = pos[Podcasts.chave(e.feed, e.ep.guid)]?.takeIf { !it.ouvido }?.ms ?: 0L
            runOnUiThread { if (!isDestroyed && filaDe == chave) { guardadaMs = g; mostrarFila(l); atualizarTempo() } }
        }
    }

    @androidx.annotation.VisibleForTesting
    internal fun mostrarFila(l: List<PodcastFeed.Episodio>) {
        aSeguir = l
        naFila = naFila.coerceIn(0, maxOf(0, l.size - 1))
        fila.removeAllViews()
        val e = ep
        for (x in l.take(SEGUIR_N)) fila.addView(cartaoSeguir(x, e), LinearLayout.LayoutParams(dpi(SEGUIR_L), -2).apply { marginEnd = dpi(16) })
        dica.text = l.firstOrNull()?.let { caber("▼  " + getString(R.string.pl_a_seguir) + ": " + it.titulo, dica, dpi(960 - 2 * MARGEM), 1) }.orEmpty()
        if (l.isEmpty() && andar == ANDAR_SEGUIR) andar = ANDAR_BOTOES
        desenharFoco(animar = false)
    }

    private fun cartaoSeguir(x: PodcastFeed.Episodio, e: OuvirServico.EpisodioTocando?): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(dpi(6), dpi(6), dpi(10), dpi(6))
        val c = CapaOuvir(this@PodcastPlayerActivity, 12f).apply {
            fonte(fonteForte); cobrir = true; monograma = CapaOuvir.iniciais(e?.podcast.orEmpty())
            cor = IconeOuvir.cor(IconeOuvir.Tipo.PODCAST); carregar(e?.capaPodcast?.ifEmpty { x.capa } ?: x.capa, 160)
        }
        addView(c, LinearLayout.LayoutParams(dpi(SEGUIR_CAPA), dpi(SEGUIR_CAPA)))
        val t = texto(18f, Estilo.TEXTO, forte = true, linhas = 2)
        val d = texto(15f, HomeCruz.COR_SUB)
        addView(LinearLayout(this@PodcastPlayerActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(t, LinearLayout.LayoutParams(-1, -2))
            addView(d, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dpi(4) })
        }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dpi(12) })
        val larguraTexto = dpi(SEGUIR_L - SEGUIR_CAPA - 6 - 10 - 12)
        t.text = caber(x.titulo, t, larguraTexto, 2)
        d.text = caber(listOfNotNull(
            x.dataMs.takeIf { it > 0 }?.let { dataCurta(it) },
            x.duracaoS.takeIf { it > 0 }?.let { getString(R.string.pod_minutos, maxOf(1, (it + 59) / 60)) },
        ).joinToString(" · "), d, larguraTexto, 1)
        background = GradientDrawable().apply { cornerRadius = dp(16f); setColor(0x00000000) }
    }

    /** "13 de nov." / "Nov 13": dia e mês na ordem da língua, sem o ano. */
    private fun dataCurta(ms: Long): String =
        DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "dMMM"), ms).toString()

    /** Corta para caber sem as reticências do layout (a mesma regra da cruz). */
    private fun caber(texto: String, tv: TextView, largura: Int, linhas: Int): String {
        if (texto.isEmpty() || largura <= 0) return texto
        val l = android.text.StaticLayout.Builder.obtain(texto, 0, texto.length, tv.paint, largura).setIncludePad(false).build()
        if (l.lineCount <= linhas) return texto
        val inicio = l.getLineStart(linhas - 1)
        return texto.substring(0, inicio) + TextUtils.ellipsize(texto.substring(inicio), tv.paint, largura.toFloat(), TextUtils.TruncateAt.END)
    }

    // ---------------------------------------------------------------- foco e teclas

    private fun desenharFoco(animar: Boolean) {
        val abrir = andar == ANDAR_SEGUIR
        if (abrir != (painel.visibility == View.VISIBLE)) {
            if (abrir) {
                painel.visibility = View.VISIBLE
                painel.translationY = if (animar) dp(60f) else 0f; painel.alpha = if (animar) 0f else 1f
                painel.animate().translationY(0f).alpha(1f).setDuration(if (animar) 220 else 0).start()
            } else painel.visibility = View.INVISIBLE
        }
        dica.visibility = if (abrir || aSeguir.isEmpty()) View.INVISIBLE else View.VISIBLE
        // Com a fila aberta os controles saem de baixo dela (o botão grande aparecia pela metade atrás do painel).
        botoes.animate().alpha(if (abrir) 0f else 1f).setDuration(if (animar) 160 else 0).start()
        barra.focada = andar == ANDAR_BARRA
        for ((i, a) in Acao.entries.withIndex()) vistas[a]?.focado = andar == ANDAR_BOTOES && i == botao
        rotuloBotao.text = when (andar) {
            ANDAR_BOTOES -> rotuloDoBotao(Acao.entries[botao])
            ANDAR_BARRA -> getString(R.string.pl_barra_dica)
            else -> ""
        }
        for (i in 0 until fila.childCount) {
            val v = fila.getChildAt(i)
            val f = abrir && i == naFila
            (v.background as? GradientDrawable)?.setColor(if (f) 0x33FFFFFF else 0x00000000)
            v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).alpha(if (!abrir || f) 1f else 0.7f)
                .setDuration(if (animar) 140 else 0).start()
        }
        // Leitor de tela (estudo global 20/11): a raiz guarda o foco, então ela diz o que está escolhido e o episódio,
        // como CruzOuvir.anunciar. Por quê: sem isso o TalkBack ficava mudo no player inteiro.
        val itemFila = if (abrir) ((fila.getChildAt(naFila) as? android.view.ViewGroup)?.let { textoDe(it) }) else null
        val raiz = window.decorView.findFocus() ?: return
        raiz.contentDescription = listOfNotNull(itemFila ?: rotuloBotao.text?.toString(), titulo.text?.toString(), podcast.text?.toString())
            .filter { it.isNotBlank() }.joinToString(", ")
        raiz.sendAccessibilityEvent(android.view.accessibility.AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    /** Junta os textos de um cartão da fila ("A seguir") para o leitor de tela. */
    private fun textoDe(v: android.view.ViewGroup): String = (0 until v.childCount).joinToString(" ") { i ->
        when (val c = v.getChildAt(i)) { is TextView -> c.text.toString(); is android.view.ViewGroup -> textoDe(c); else -> "" }
    }.trim()

    /** Teclas que esta tela usa; o resto (volume, mudo, botão B do controle de jogo...) segue para o sistema. */
    private val teclasDaTela = setOf(KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP,
        KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
        KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_BACK)

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        // Antes engolia TODAS as teclas: o volume do controle não fazia nada nesta tela (debug 20/11/2026).
        if (e.keyCode !in teclasDaTela) return super.dispatchKeyEvent(e)
        if (e.action != KeyEvent.ACTION_DOWN) return if (e.keyCode == KeyEvent.KEYCODE_BACK) super.dispatchKeyEvent(e) else true
        val s = OuvirServico.instancia
        when (e.keyCode) {
            KeyEvent.KEYCODE_MEDIA_REWIND -> { s?.pular(-OuvirServico.VOLTAR_MS); atualizarTempo(); return true }
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { s?.pular(OuvirServico.AVANCAR_MS); atualizarTempo(); return true }
            // ▶ e ⏸ separados (controle da LG pelo HDMI) fazem o que dizem; só a tecla ⏯ alterna.
            KeyEvent.KEYCODE_MEDIA_PLAY -> { if (e.repeatCount == 0) s?.continuar(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { if (e.repeatCount == 0) s?.pausar(); return true }
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> { if (e.repeatCount == 0) s?.alternar(); return true }
            KeyEvent.KEYCODE_BACK -> {
                if (andar == ANDAR_SEGUIR || andar == ANDAR_BARRA) { andar = ANDAR_BOTOES; desenharFoco(true); return true }
                return super.dispatchKeyEvent(e)
            }
        }
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        when (andar) {
            ANDAR_BARRA -> when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    val sv = s ?: return true
                    val d = if (e.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) -PodcastPlayerLogic.PASSO_BARRA_MS else PodcastPlayerLogic.PASSO_BARRA_MS
                    // Segurando a seta, o passo cresce (10 s, 20 s, 30 s...): atravessar um episódio longo não leva minutos.
                    val vezes = 1 + minOf(e.repeatCount / 5, 5)
                    sv.irPara(PodcastPlayerLogic.andar(sv.posicaoMs, d * vezes, sv.duracaoMs)); atualizarTempo()
                }
                KeyEvent.KEYCODE_DPAD_DOWN -> { andar = ANDAR_BOTOES; desenharFoco(true) }
                else -> if (ok && e.repeatCount == 0) s?.alternar()   // segurar OK não liga/desliga sem parar
            }
            ANDAR_BOTOES -> when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (botao > 0) { botao--; desenharFoco(true) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (botao < Acao.entries.size - 1) { botao++; desenharFoco(true) }
                KeyEvent.KEYCODE_DPAD_UP -> { andar = ANDAR_BARRA; desenharFoco(true) }
                KeyEvent.KEYCODE_DPAD_DOWN -> if (aSeguir.isNotEmpty()) { andar = ANDAR_SEGUIR; naFila = 0; desenharFoco(true) }
                else -> if (ok && e.repeatCount == 0) acionar(Acao.entries[botao])
            }
            ANDAR_SEGUIR -> when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (naFila > 0) { naFila--; desenharFoco(true) }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (naFila < minOf(aSeguir.size, fila.childCount) - 1) { naFila++; desenharFoco(true) }
                KeyEvent.KEYCODE_DPAD_UP -> { andar = ANDAR_BOTOES; desenharFoco(true) }
                else -> if (ok && e.repeatCount == 0) tocarDaFila()
            }
        }
        return true
    }

    private fun acionar(a: Acao) {
        val s = OuvirServico.instancia
        when (a) {
            Acao.VOLTAR -> { s?.pular(-OuvirServico.VOLTAR_MS); atualizarTempo() }
            Acao.AVANCAR -> { s?.pular(OuvirServico.AVANCAR_MS); atualizarTempo() }
            Acao.ALTERNAR -> s?.alternar()
            Acao.VELOCIDADE -> {
                val v = OuvirServico.velocidade(this)
                Estilo.lista(this, getString(R.string.pod_velocidade), OuvirServico.VELOCIDADES.map { PodcastPlayerLogic.rotuloVelocidade(it) },
                    listOf(OuvirServico.VELOCIDADES.indexOf(v))) { i -> OuvirServico.instancia?.definirVelocidade(OuvirServico.VELOCIDADES[i]); atualizar() }
            }
            Acao.SILENCIO -> { s?.definirPularSilencio(!OuvirServico.pularSilencio(this)); atualizar() }
            Acao.DORMIR -> {
                val ops = PodcastPlayerLogic.DORMIR_MIN.map { if (it == 0) getString(R.string.pod_desligado).replaceFirstChar { c -> c.titlecase() } else getString(R.string.pl_dormir_min, it) }
                val atual = PodcastPlayerLogic.minutosParaDormir(s?.dormirAte ?: 0L, SystemClock.elapsedRealtime())
                Estilo.lista(this, getString(R.string.pl_dormir), ops, if (atual == 0) listOf(0) else emptyList()) { i ->
                    OuvirServico.instancia?.definirDormir(PodcastPlayerLogic.DORMIR_MIN[i] * 60_000L); atualizar()
                }
            }
        }
    }

    private fun tocarDaFila() {
        val x = aSeguir.getOrNull(naFila) ?: return
        val e = ep ?: return
        val novo = OuvirServico.EpisodioTocando(e.feed, e.podcast, e.capaPodcast, x)
        OuvirServico.tocarEpisodio(this, novo, OuvirServico.RETOMAR)
        esperar(novo); andar = ANDAR_BOTOES; botao = 1; naFila = 0
        atualizar()
    }

    // ---------------------------------------------------------------- peças

    /** A barra de tempo: trilho largo, parte ouvida na cor da marca e, com foco, um pino e o trilho mais alto. */
    private inner class BarraTempo(c: Context) : View(c) {
        var valor = 0f
            set(v) { if (field != v) { field = v; invalidate() } }
        var focada = false
            set(v) { if (field != v) { field = v; invalidate() } }
        private val trilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
        private val cheio = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.ACENTO }
        private val pino = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.TEXTO }
        private val r = RectF()
        override fun onDraw(c: Canvas) {
            val a = dp(if (focada) 10f else 6f)
            val y = height / 2f
            r.set(0f, y - a / 2, width.toFloat(), y + a / 2); c.drawRoundRect(r, a / 2, a / 2, trilho)
            val x = width * valor
            if (valor > 0f) { r.right = maxOf(a, x); c.drawRoundRect(r, a / 2, a / 2, cheio) }
            if (focada) c.drawCircle(x.coerceIn(dp(10f), width - dp(10f)), y, dp(10f), pino)
        }
    }

    /** Um botão redondo da fileira: ícone Linha (ou um texto, como "1,2x"); com foco, círculo claro e um pouco maior. */
    private inner class Botao(c: Context, val lado: Int, val grande: Boolean) : View(c) {
        private var ic: IconeOuvir? = null
        private var escrita: String? = null
        var focado = false
            set(v) { if (field != v) { field = v; animate().scaleX(if (v) 1.12f else 1f).scaleY(if (v) 1.12f else 1f).setDuration(140).start(); invalidate() } }
        var ligado = true
            set(v) { if (field != v) { field = v; invalidate() } }
        private val fundo = Paint(Paint.ANTI_ALIAS_FLAG)
        private val letra = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = fonteForte }
        private val ponto = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.ACENTO }
        // O ícone do botão grande vai escuro sobre o laranja (o traço Linha claro sumia nele).
        private val escuro = android.graphics.PorterDuffColorFilter(0xFF14161F.toInt(), android.graphics.PorterDuff.Mode.SRC_IN)

        init { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }

        fun icone(t: IconeOuvir.Tipo) { if (ic?.tipo != t) { ic = IconeOuvir(t); escrita = null; invalidate() } }
        fun escrito(t: String) { if (escrita != t) { escrita = t; ic = null; invalidate() } }

        override fun onDraw(cv: Canvas) {
            val w = width.toFloat(); val h = height.toFloat(); val raio = minOf(w, h) / 2f
            fundo.color = when {
                grande && !focado -> 0x33FFFFFF   // ⏯ maior, mas sem laranja: um só destaque na tela, o foco branco
                // Pente fino 19/11/2026: o foco era branco 25% (~1,6:1 contra o botão sem foco, abaixo dos 3:1 da WCAG
                // 1.4.11) e na Sala não dava para saber onde estava. Regra da casa: foco é BRANCO, ícone escuro em cima.
                focado -> Estilo.FOCO
                else -> 0x14FFFFFF
            }
            cv.drawCircle(w / 2, h / 2, raio, fundo)
            if (grande && focado) { fundo.color = Estilo.FOCO; fundo.style = Paint.Style.STROKE; fundo.strokeWidth = dp(3f); cv.drawCircle(w / 2, h / 2, raio - dp(1.5f), fundo); fundo.style = Paint.Style.FILL }
            val m = (raio * if (grande) 0.48f else 0.52f).toInt()
            ic?.let {
                it.setBounds(m, m, width - m, height - m)
                it.alpha = if (ligado || focado || grande) 255 else 140
                it.colorFilter = if (focado) escuro else null
                it.draw(cv)
            }
            escrita?.let {
                letra.color = if (focado) 0xFF14161F.toInt() else Estilo.TEXTO; letra.textSize = h * 0.3f
                cv.drawText(it, w / 2, h / 2 - (letra.descent() + letra.ascent()) / 2, letra)
            }
            // Ligado (pular silêncios, timer): um ponto laranja embaixo, como o ponto do item escolhido da cruz.
            if (!grande && ic != null && ligado && (ic?.tipo == IconeOuvir.Tipo.SILENCIO || ic?.tipo == IconeOuvir.Tipo.DORMIR)) {
                cv.drawCircle(w / 2, h - dp(7f), dp(3.5f), ponto)
            }
        }
    }
}
