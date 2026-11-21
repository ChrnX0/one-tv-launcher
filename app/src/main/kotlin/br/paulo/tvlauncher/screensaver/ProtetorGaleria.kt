package br.paulo.tvlauncher.screensaver

import android.content.Context
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.FonteVidro
import br.paulo.tvlauncher.LembretesTv
import br.paulo.tvlauncher.NotificacoesTv
import br.paulo.tvlauncher.OuvinteNotificacoes
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.ProtetorTela
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.Retrospectiva
import br.paulo.tvlauncher.TocandoAgoraTv
import br.paulo.tvlauncher.Vidro
import br.paulo.tvlauncher.alarm.Agenda
import br.paulo.tvlauncher.alarm.Aniversarios
import br.paulo.tvlauncher.alarm.DescansoFase
import br.paulo.tvlauncher.alarm.Despertador
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.family.Mural
import br.paulo.tvlauncher.family.RetrospectivaAno
import br.paulo.tvlauncher.home.EstadoCeu
import br.paulo.tvlauncher.home.FundoGaleria
import br.paulo.tvlauncher.home.FundoTerra
import br.paulo.tvlauncher.home.IconeLinha
import br.paulo.tvlauncher.home.SofalyWordmark
import br.paulo.tvlauncher.home.desenharFundoEscuro
import br.paulo.tvlauncher.panel.eventos
import br.paulo.tvlauncher.player.EstacaoEspacial
import br.paulo.tvlauncher.player.FonteTempoGuardado
import br.paulo.tvlauncher.player.LinhasTempo
import br.paulo.tvlauncher.settings.Curiosidades
import br.paulo.tvlauncher.settings.Extras
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.weather.Astronomia
import br.paulo.tvlauncher.weather.AvisoRegras
import br.paulo.tvlauncher.weather.DesenhoCeu
import br.paulo.tvlauncher.weather.FraseTempo
import br.paulo.tvlauncher.weather.IconesClima
import br.paulo.tvlauncher.weather.Lua
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * O protetor "Galeria", direção B aprovada pelo dono em 10/11/2026 (modelos em protetor-versoes/B2 e B3):
 * - fundo: a pintura do dia do The Met em tela cheia ([FundoGaleria]), com movimento lento de câmera (Ken Burns);
 * - em cima, à esquerda: saudação, a hora grande em Manrope (hora cheia, minuto fino), a data e uma linha fina que mostra
 *   quanto do dia claro já passou (do nascer ao pôr do sol; some à noite);
 * - em cima, à direita: as notificações da TV em cartões de vidro fosco ([NotificacoesTv]), mais o próximo despertador;
 * - embaixo: os widgets do tempo (agora, próximas horas, amanhã) no estilo escolhido nas Configurações
 *   ([WidgetsProtetor], 15/11/2026: faixa de vidro, cartões ou sem caixa); acima deles, a obra do dia.
 * O vidro é a própria pintura desfocada atrás de cada painel ([Vidro]).
 *
 * Protetor v2 (17/11/2026, protótipo "Protetor Vivo 2" aprovado por Paulo): fundo opcional "A Terra vista do espaço"
 * ([FundoTerra]); o relógio em 6 estilos ([RelogioProtetor]), em 3 posições e 2 tamanhos; cada elemento liga e desliga;
 * e o resto pode ficar escondido ("só a hora", o padrão): a 1ª tecla do controle mostra tudo por 15 s, a hora desliza
 * para dar lugar e as informações entram em cascata ([revelar]). Escolhas em [Protetor].
 *
 * Quem cria e destrói é o [ProtetorTela]. [montar] monta a tela; [atualizar] relê clima, avisos e a obra do dia.
 * Agenda, desfoque e decodificação da pintura saem da thread da tela pelo [carregador].
 */
class ProtetorGaleria(
    private val ctx: Context, private val carregador: ExecutorService, private val comPintura: Boolean,
    /** Tema Escuro (dono, 11/11/2026): o MESMO protetor, só que com o wallpaper escuro da HOME no lugar da pintura. */
    private val wallpaperEscuro: Boolean = false,
    /** Tema Céu de verdade (17/11/2026): mapa da região, cidade e nuvens do satélite ([DesenhoCeu]) no lugar da pintura. */
    private val ceuDeVerdade: Boolean = false,
    /** Fundo "A Terra vista do espaço" (17/11/2026): as fotos da NASA no lugar do fundo do tema. */
    private val terra: Boolean = false,
) {
    private val m400: Typeface = Fontes.nunito(ctx, 400)
    private val m500: Typeface = Fontes.nunito(ctx, 500)
    private val m600: Typeface = Fontes.nunito(ctx, 600)
    private val fraunces: Typeface = Fontes.fraunces(ctx)
    private val branco = 0xFFFFFFFF.toInt()
    private val sombra = 0x59000000

    /** Camada que se move (anti-burn-in): o ProtetorTela desloca ela em "oito". */
    lateinit var camadaInfo: FrameLayout; private set
    private var fundo: FundoGaleria? = null
    private val vidro = FonteVidro()
    private val widgets by lazy { WidgetsProtetor(ctx, vidro) }
    private lateinit var raiz: FrameLayout
    private lateinit var saudacao: TextView
    private lateinit var relogio: RelogioProtetor
    private lateinit var grupo: LinearLayout
    private var veuBaixo: View? = null
    private var veuCanto: View? = null
    private var fundoTerra: FundoTerra? = null
    // Escolhas das Configurações (lidas ao abrir; mudar vale no próximo protetor).
    private val estiloRelogio = Protetor.relogio(ctx, Locale.getDefault().language)
    private val posicao = Protetor.POSICAO.ler(ctx)
    private val fator = Protetor.fatorTamanho(Protetor.TAMANHO.ler(ctx))
    private val modoMostrar = Protetor.MOSTRAR.ler(ctx)
    private val estiloAvisos = Protetor.AVISOS.ler(ctx)
    private val estiloTextos = Protetor.TEXTOS.ler(ctx)
    private val estiloCredito = Protetor.CREDITO.ler(ctx)
    private fun liga(e: Protetor.Elemento) = Protetor.ligado(ctx, e)
    /** O resto está à mostra? null = ainda não decidido (primeira vez, sem animação). */
    private var infoVisivel: Boolean? = null
    private var teclaAte = 0L
    private val abertoEm = System.currentTimeMillis()
    private lateinit var data: TextView
    private lateinit var agenda: LinearLayout
    private lateinit var progresso: FrameLayout
    private lateinit var progressoCheio: View
    private lateinit var colunaAvisos: LinearLayout
    private lateinit var barra: LinearLayout
    private lateinit var etiquetaObra: LinearLayout
    /** Caminho 2 (17/11/2026): a frase do tempo e uma curiosidade por vez, embaixo da data ([LinhasTempo]). */
    private lateinit var linhasTempo: LinhasTempo
    /** De onde vem o tempo: trocar a fonte do produto não mexe no protetor ([FonteTempo]). */
    private val fonteTempo: FonteTempo = FonteTempoGuardado(ctx)

    // Avisos que vêm da thread de fundo (despertador e agenda).
    private var despertador: Long? = null
    private var eventos: List<Pair<String, String>> = emptyList()
    /** Ideias de 12/11/2026: cartões do mural da família e da retrospectiva da semana (título, linha, linha 2). */
    private var extras12: List<Triple<String, String, String>> = emptyList()
    private val icones = HashMap<String, Drawable?>()

    // Fase do dia (estudo global 20/11, ideia 9; ver [DescansoFase]): logo, brilho, véu e tom quente mudam com a hora.
    private var fase = DescansoFase.agora()
    private var logo: View? = null
    private var veuFase: View? = null
    private var quenteFase: View? = null

    /** Aplica a fase atual. Troca de fase anima devagar (3 s): quem está olhando não vê um corte. */
    private fun aplicarFase(animar: Boolean) {
        val f = fase
        logo?.animate()?.alpha(if (f.logo) 1f else 0f)?.setDuration(if (animar) 3000 else 0)?.start()
        veuFase?.setBackgroundColor(f.veu shl 24)
        quenteFase?.setBackgroundColor(DescansoFase.corQuente(f))
        if (animar) camadaInfo.animate().alpha(f.brilhoInfo).setDuration(3000).start()
        conferirMostrar.run()
    }

    fun montar(raiz: FrameLayout) {
        this.raiz = raiz
        fundo = if (comPintura) FundoGaleria(ctx, { t -> if (!carregador.isShutdown) carregador.execute(t) }, comEtiqueta = false, veus = false).also { f ->
            raiz.addView(f, FrameLayout.LayoutParams(-1, -1))
            vidro.matrizNaTela = { matrizPintura(f) }
            // Pintura nova -> gera o vidro desfocado dela fora da thread da tela e redesenha os painéis.
            f.aoTrocarImagem = { bmp -> if (!carregador.isShutdown) carregador.execute { vidro.preparar(bmp) { raiz.post { vidro.redesenhar() } } } }
        } else null
        if (terra) montarTerra(raiz)
        else if (!comPintura && wallpaperEscuro) montarWallpaperEscuro(raiz)
        if (!comPintura && ceuDeVerdade) montarCeu(raiz)
        // Véu leve só no canto da hora: relógio branco continua legível sobre um céu claro de pintura.
        // Radial a partir do canto: o degradê linear numa caixa deixava uma emenda visível na borda dela (foto de 10/11).
        // Protetor v2: o véu acompanha a posição da hora (canto de baixo, centro ou canto de cima).
        veuCanto = View(ctx).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; gradientRadius = dp(620).toFloat()
                when (posicao) {
                    Protetor.POS_BAIXO -> setGradientCenter(0f, 1f)
                    Protetor.POS_CENTRO -> { setGradientCenter(0.5f, 0.5f); gradientRadius = dp(480).toFloat() }
                    else -> setGradientCenter(0f, 0f)
                }
                colors = if (posicao == Protetor.POS_CENTRO) intArrayOf(0x4D000000, 0x1A000000, 0x00000000) else intArrayOf(0x66000000, 0x26000000, 0x00000000)
            }
        }
        raiz.addView(veuCanto, FrameLayout.LayoutParams(-1, -1))
        veuFase = View(ctx).also { raiz.addView(it, FrameLayout.LayoutParams(-1, -1)) }

        camadaInfo = FrameLayout(ctx)
        montarTopo()
        colunaAvisos = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        // Logo Sofaly pequena e apagada no canto de cima à direita (18/11/2026, pedido do Paulo). Fica na camada que anda
        // (anti-burn-in) e empurra os avisos um pouco para baixo.
        // De madrugada ele some ([DescansoFase.logo]).
        camadaInfo.addView(SofalyWordmark(ctx, dp(22).toFloat(), dim = 0.55f).also { logo = it }, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(26); marginEnd = dp(36)
        })
        camadaInfo.addView(colunaAvisos, FrameLayout.LayoutParams(dp(250), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(32 + 40); marginEnd = dp(32)
        })
        // Sem caixa (12/11/2026, dono: "a B na proposta da C"): a linha de baixo fica solta sobre a pintura, legível por
        // um véu escuro que sobe do pé da tela (fica na raiz, parado: não anda com o anti-burn-in) e uma sombra nas letras.
        veuBaixo = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x9E000000.toInt(), 0x4D000000, 0x00000000))
        }
        raiz.addView(veuBaixo, raiz.childCount - 1, FrameLayout.LayoutParams(-1, dp(190), Gravity.BOTTOM))
        barra = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; clipChildren = false }
        camadaInfo.addView(barra, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply {
            marginStart = dp(Estilo.MARGEM_LATERAL); marginEnd = dp(Estilo.MARGEM_LATERAL); bottomMargin = dp(Estilo.MARGEM_VERTICAL)
        })
        etiquetaObra = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        camadaInfo.addView(etiquetaObra, FrameLayout.LayoutParams(dp(420), -2, Gravity.TOP or Gravity.END).apply {
            marginEnd = dp(Estilo.MARGEM_LATERAL)
        })
        raiz.addView(camadaInfo, FrameLayout.LayoutParams(-1, -1))
        // Tom quente por cima de tudo (fica parado, não anda com o anti-burn-in, e não recebe toque).
        quenteFase = View(ctx).also { raiz.addView(it, FrameLayout.LayoutParams(-1, -1)) }
        aplicarFase(animar = false)

        NotificacoesTv.ouvir(aoMudarNotificacoes)
        garantirOuvinte()
        atualizar()
        releTocando.run()
        tique.run()
        aplicarElementos()
        // Entrada suave: as informações aparecem em 1,5 s depois da pintura.
        camadaInfo.alpha = 0f
        camadaInfo.animate().alpha(fase.brilhoInfo).setStartDelay(600).setDuration(1500).withLayer().start()
        fundo?.let { kenBurns(it) }
        // O grupo da hora se reposiciona quando muda de tamanho (agenda, frase e curiosidade chegam depois).
        grupo.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) posicionarGrupo(true) }
        barra.addOnLayoutChangeListener { _, _, t, _, b, _, ot, _, ob -> if (b - t != ob - ot) posicionarGrupo(true) }
        conferirMostrar.run()
    }

    /**
     * Fundo "A Terra vista do espaço": a foto nova vira o vidro dos painéis e a legenda troca junto.
     */
    private fun montarTerra(raiz: FrameLayout) {
        val f = FundoTerra(ctx) { t -> if (!carregador.isShutdown) carregador.execute(t) }
        raiz.addView(f, FrameLayout.LayoutParams(-1, -1))
        vidro.matrizNaTela = { f.matrizNaTela() }
        f.aoPasso = { vidro.redesenhar() }
        f.aoTrocarImagem = { bmp ->
            if (!carregador.isShutdown) carregador.execute { vidro.preparar(bmp) { raiz.post { vidro.redesenhar() } } }
            montarEtiqueta(System.currentTimeMillis())
        }
        fundoTerra = f
    }

    // ------------------------------------------------------------------ protetor v2: elementos, "só a hora" e a hora que desliza
    /** Liga e desliga cada elemento conforme as Configurações. A hora sempre fica. */
    private fun aplicarElementos() {
        saudacao.visibility = if (liga(Protetor.Elemento.SAUDACAO)) View.VISIBLE else View.GONE
        data.visibility = if (liga(Protetor.Elemento.DATA)) View.VISIBLE else View.GONE
        agenda.visibility = if (liga(Protetor.Elemento.AGENDA)) View.VISIBLE else View.GONE
        colunaAvisos.visibility = if (liga(Protetor.Elemento.AVISOS)) View.VISIBLE else View.GONE
        val widget = liga(Protetor.Elemento.WIDGET)
        barra.visibility = if (widget) View.VISIBLE else View.GONE
        veuBaixo?.visibility = if (widget) View.VISIBLE else View.GONE
        etiquetaObra.visibility = if (liga(Protetor.Elemento.CREDITO)) View.VISIBLE else View.GONE
        linhasTempo.comFrase = liga(Protetor.Elemento.FRASE)
        linhasTempo.comCuriosidades = liga(Protetor.Elemento.CURIOSIDADES)
    }

    /** O que some no "só a hora" (na ordem em que entra de volta). */
    private fun infoViews(): List<View> = listOfNotNull(saudacao, data, agenda, progresso, linhasTempo, colunaAvisos, etiquetaObra, barra, veuBaixo)

    /** O resto está escondido agora? (a 1ª tecla então só mostra; [TeclasProtetor]). */
    val oculto: Boolean get() = infoVisivel == false

    /** Uma tecla do controle: mostra tudo por 15 s (cada tecla renova o prazo). */
    fun tecla() {
        teclaAte = System.currentTimeMillis() + Protetor.TECLA_MS
        conferirMostrar.run()
    }

    /** Uma vez por segundo: o modo (só a hora / de vez em quando / sempre) decide se o resto aparece. */
    private val conferirMostrar = object : Runnable {
        override fun run() {
            if (!::grupo.isInitialized) return
            grupo.removeCallbacks(this)
            // De manhã o resumo do dia fica à mostra mesmo no modo "só a hora" (ideia 9: responder "como está o dia?").
            val v = fase.resumoSempre || Protetor.infoVisivel(modoMostrar, System.currentTimeMillis(), teclaAte, abertoEm)
            if (v != infoVisivel) revelar(v, animar = infoVisivel != null)
            if (modoMostrar != Protetor.MOSTRAR_SEMPRE) grupo.postDelayed(this, 1000)
        }
    }

    /**
     * Mostra ou esconde o resto. Entrando: cada elemento sobe 10 dp e acende, um depois do outro (90 ms de intervalo).
     * Saindo: todos apagam mais depressa. withLayer: cada elemento vira uma textura durante a animação (a sombra das
     * letras não é redesenhada a cada quadro; ver o comentário de 12/11/2026 no ProtetorTela).
     */
    private fun revelar(mostrar: Boolean, animar: Boolean) {
        infoVisivel = mostrar
        val sobe = dp(10).toFloat()
        infoViews().forEachIndexed { i, v ->
            v.animate().cancel()
            if (!animar) { v.alpha = if (mostrar) 1f else 0f; v.translationY = 0f; return@forEachIndexed }
            if (mostrar) {
                v.translationY = sobe
                v.animate().alpha(1f).translationY(0f).setStartDelay(250L + i * 90L).setDuration(900)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).withLayer().start()
            } else {
                v.animate().alpha(0f).translationY(sobe / 2).setStartDelay(i * 40L).setDuration(600).withLayer().start()
            }
        }
        if (escolhido >= 0 && !mostrar) { escolhido = -1; montarAvisos() }
        posicionarGrupo(animar)
    }

    /**
     * Onde o grupo da hora fica: escondido o resto, a hora vai para o canto (ou o centro) sozinha; à mostra, o grupo
     * inteiro aparece, e embaixo ele sobe para dar lugar aos widgets do tempo. Desliza em 1,4 s.
     */
    private fun posicionarGrupo(animar: Boolean) {
        if (!::grupo.isInitialized || grupo.height == 0) { if (::grupo.isInitialized) grupo.post { if (grupo.height > 0) posicionarGrupo(false) }; return }
        val mostrar = infoVisivel != false
        val barraAlt = if (barra.visibility == View.VISIBLE) barra.height else 0
        val dy: Float = when (posicao) {
            Protetor.POS_BAIXO -> if (!mostrar) (grupo.height - relogio.bottom).toFloat()
                else if (barraAlt > 0) -(barraAlt + dp(22)).toFloat() else 0f
            Protetor.POS_CENTRO -> {
                val alto = raiz.height
                val alvo = if (!mostrar) alto / 2f - (relogio.top + relogio.height / 2f)
                    else ((alto - barraAlt - dp(Estilo.MARGEM_VERTICAL) - grupo.height) / 2f).coerceAtLeast(dp(28).toFloat())
                alvo - grupo.top
            }
            else -> if (!mostrar) -relogio.top.toFloat() else 0f
        }
        grupo.animate().cancel()
        if (!animar) { grupo.translationY = dy; return }
        grupo.animate().translationY(dy).setDuration(1400).setInterpolator(android.view.animation.DecelerateInterpolator(1.4f)).withLayer().start()
    }

    /**
     * O wallpaper escuro da HOME (desenharFundoEscuro, em Fundos.kt) desenhado numa imagem e posto onde a pintura ficaria.
     * O vidro dos painéis desfoca ESSA imagem, como desfocaria a pintura: o resto do protetor fica igual ao da Galeria.
     * Imagem em meia resolução (960x540, ~2 MB): é um degradê suave, ampliar não perde nada.
     */
    private fun montarWallpaperEscuro(raiz: FrameLayout) {
        val bmp = android.graphics.Bitmap.createBitmap(960, 540, android.graphics.Bitmap.Config.ARGB_8888)
        desenharFundoEscuro(android.graphics.Canvas(bmp), 960f, 540f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
        val img = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.FIT_XY; setImageBitmap(bmp) }
        raiz.addView(img, FrameLayout.LayoutParams(-1, -1))
        // Imagem -> tela: só a ampliação para o tamanho da raiz (o wallpaper fica parado, sem Ken Burns).
        vidro.matrizNaTela = {
            if (img.width == 0) null else Matrix().apply {
                setScale(img.width / 960f, img.height / 540f)
                val loc = IntArray(2); img.getLocationOnScreen(loc); postTranslate(loc[0].toFloat(), loc[1].toFloat())
            }
        }
        if (!carregador.isShutdown) carregador.execute { vidro.preparar(bmp) { raiz.post { vidro.redesenhar() } } }
    }

    private var imagemCeu: ImageView? = null
    private var ceuFeitoEm = 0L

    /**
     * Céu de verdade: a imagem do [DesenhoCeu] (960x540) onde a pintura ficaria, e o vidro dos painéis desfoca ela, como
     * no Escuro. A imagem é refeita fora da thread da tela a cada [atualizar] (15 min), com as nuvens novas do satélite.
     */
    private fun montarCeu(raiz: FrameLayout) {
        val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY; setBackgroundColor(0xFF0E1A2C.toInt()) }
        raiz.addView(img, FrameLayout.LayoutParams(-1, -1))
        imagemCeu = img
        vidro.matrizNaTela = {
            if (img.width == 0) null else Matrix().apply {
                setScale(img.width / 960f, img.height / 540f)
                val loc = IntArray(2); img.getLocationOnScreen(loc); postTranslate(loc[0].toFloat(), loc[1].toFloat())
            }
        }
    }

    private fun redesenharCeu(e: EstadoCeu) {
        val img = imagemCeu ?: return
        if (carregador.isShutdown || System.currentTimeMillis() - ceuFeitoEm < 10 * 60_000L) return
        ceuFeitoEm = System.currentTimeMillis()
        carregador.execute {
            val bmp = try { DesenhoCeu.imagem(ctx, 960, 540, e) } catch (ex: Throwable) { android.util.Log.w(TAG, "céu: $ex"); null } ?: return@execute
            vidro.preparar(bmp) { raiz.post { vidro.redesenhar() } }
            img.post { img.setImageBitmap(bmp) }
        }
    }

    // ------------------------------------------------------------------ topo: saudação, hora, data, linha do dia
    private fun montarTopo() {
        val centro = posicao == Protetor.POS_CENTRO
        val topo = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            if (centro) gravity = Gravity.CENTER_HORIZONTAL
        }
        grupo = topo
        val recuo = if (centro) 0 else dp(6)
        fun lpGrupo() = LinearLayout.LayoutParams(-2, -2).apply { if (centro) gravity = Gravity.CENTER_HORIZONTAL }
        // Saudação: 12 sp (era 9): letra grande para quem enxerga pouco; com a hora sozinha, ela só aparece com o resto.
        saudacao = texto("", 12f, m500).apply { letterSpacing = 0.24f; isAllCaps = true; alpha = 0.85f; setPaddingRelative(recuo, 0, 0, 0) }
        topo.addView(saudacao, lpGrupo().apply { bottomMargin = dp(4) })
        relogio = RelogioProtetor(ctx, estiloRelogio, fator, centro)
        topo.addView(relogio, lpGrupo())
        data = texto("", 16f, m500).apply { setPaddingRelative(recuo, 0, 0, 0) }
        topo.addView(data, lpGrupo().apply { topMargin = dp(8) })
        // Variação da saudação e da data (17/11/2026, Protetor.TEXTOS).
        when (estiloTextos) {
            Protetor.TEXTOS_SERIFA -> {
                saudacao.typeface = fraunces; saudacao.isAllCaps = false; saudacao.letterSpacing = 0f; saudacao.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                data.typeface = fraunces; data.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            }
            Protetor.TEXTOS_MAIUSCULAS -> { data.isAllCaps = true; data.letterSpacing = 0.16f; data.typeface = m600; data.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f) }
        }
        listOf(saudacao, data).forEach { it.sombrear() }
        // Compromissos de hoje logo abaixo da data (dono, 10/11: "o calendário poderia ficar junto da data").
        agenda = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(recuo, 0, 0, 0) }
        topo.addView(agenda, lpGrupo())
        progresso = FrameLayout(ctx).apply { background = arredondado(0x40FFFFFF, 2f); clipToOutline = true }
        progressoCheio = View(ctx).apply { background = arredondado(branco, 2f) }
        progresso.addView(progressoCheio, FrameLayout.LayoutParams(0, -1))
        topo.addView(progresso, LinearLayout.LayoutParams(dp(180), dp(2)).apply { topMargin = dp(11); marginStart = recuo; if (centro) gravity = Gravity.CENTER_HORIZONTAL })
        linhasTempo = LinhasTempo(ctx, m400, m600).apply { if (centro) gravity = Gravity.CENTER_HORIZONTAL; estilo(Protetor.LINHAS.ler(ctx)) }
        topo.addView(linhasTempo, lpGrupo().apply { topMargin = dp(20); marginStart = recuo })
        camadaInfo.addView(topo, when (posicao) {
            Protetor.POS_BAIXO -> FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply {
                marginStart = dp(36); bottomMargin = dp(Estilo.MARGEM_VERTICAL)
            }
            Protetor.POS_CENTRO -> FrameLayout.LayoutParams(-1, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            else -> FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply { marginStart = dp(36); topMargin = dp(26) }
        })
    }

    private var primeiroTique = true

    /** A cada virada de minuto: hora, saudação, data e a linha do dia. */
    private val tique = object : Runnable {
        override fun run() {
            val agora = System.currentTimeMillis()
            val c = Calendar.getInstance().apply { timeInMillis = agora }
            // O relógio no estilo escolhido (RelogioProtetor; o "fino" é o desenho de antes). Esmaece na virada do minuto.
            relogio.atualizar(agora, animar = primeiroTique.not())
            primeiroTique = false
            saudacao.text = ctx.getString(when (c.get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> R.string.pg_bom_dia
                in 12..17 -> R.string.pg_boa_tarde
                else -> R.string.pg_boa_noite
            })
            data.text = SimpleDateFormat(ctx.getString(R.string.fmt_data_longa), Locale.getDefault()).format(Date(agora))
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            val (lat, lon) = lugar()
            val frac = Astronomia.nascerEPor(agora, lat, lon)?.let { (nasce, poe) ->
                if (agora in nasce..poe) (agora - nasce).toFloat() / (poe - nasce) else null
            }
            progresso.visibility = if (frac == null || !liga(Protetor.Elemento.LINHA_DIA)) View.GONE else View.VISIBLE
            if (frac != null) progressoCheio.layoutParams = (progressoCheio.layoutParams as FrameLayout.LayoutParams).apply { width = (dp(180) * frac).toInt() }
            relogio.postDelayed(this, 60_000 - agora % 60_000 + 50)
        }
    }

    // ------------------------------------------------------------------ atualizar tudo (a cada 15 min, pelo ProtetorTela)
    fun atualizar() {
        val agora = System.currentTimeMillis()
        DescansoFase.agora().let { if (it != fase) { fase = it; aplicarFase(animar = true) } }
        val (lat, lon) = lugar()
        val clima = ctx.getSharedPreferences(Prefs.CLIMA, Context.MODE_PRIVATE)
        val estado = EstadoCeu(agora, lat, lon, clima.getInt("codigo", 0), clima.getBoolean("chovendo", false))
        fundo?.atualizar(estado)
        fundoTerra?.atualizar(estado)
        redesenharCeu(estado)
        montarBarra(agora, lat, lon)
        montarAvisos()
        carregarAvisos(agora)
        carregarTempo(agora)
    }

    /**
     * Frase e curiosidades fora da thread da tela: a passagem da Estação pode baixar o TLE e faz ~3 mil contas de órbita,
     * e os planetas mais ~400. Tudo cabe em poucos ms num aparelho de 32 bits, mas não na thread das teclas.
     */
    private fun carregarTempo(agora: Long) {
        if (carregador.isShutdown) return
        carregador.execute {
            val t = try { fonteTempo.ler(agora) } catch (e: Exception) { null }
            val (lat, lon) = lugar()
            val estacao = try { EstacaoEspacial.proxima(ctx, agora, lat, lon) } catch (e: Exception) { android.util.Log.w(TAG, "estação: $e"); null }
            val eclipses = try { Config.ECLIPSES_SOLARES.map { it.inicioMs } } catch (e: Exception) { emptyList() }
            val frase = t?.let { try { FraseTempo.escolher(it) } catch (e: Exception) { null } }
            // Sem clima recente, o céu da noite ainda vale (Estação, meteoros, Lua, planetas não dependem da previsão).
            val base = t ?: TempoAgora(agora = agora, lat = lat, lon = lon, codigo = 0)
            val lista = try { Curiosidades.montar(base, estacao, eclipses) } catch (e: Exception) { android.util.Log.w(TAG, "curiosidades: $e"); emptyList() }
            val icone = t?.let { iconeDaFrase(it, frase) }
            raiz.post { if (::linhasTempo.isInitialized) linhasTempo.mostrar(t, frase, lista, icone) }
        }
    }

    /** O ícone colorido ao lado da frase: o da chuva quando a frase fala de chuva; senão o céu de agora. */
    private fun iconeDaFrase(t: TempoAgora, f: FraseTempo.Frase?): Drawable? {
        val dia = Astronomia.eDia(t.agora, t.lat, t.lon, true)
        val codigo = when (f) {
            is FraseTempo.Frase.ChuvaEmMinutos, is FraseTempo.Frase.ParaEmMinutos, is FraseTempo.Frase.ChuvaPorVolta,
            is FraseTempo.Frase.ParaPorVolta, FraseTempo.Frase.ChuvaSegue, is FraseTempo.Frase.AmanhaChove -> 61
            is FraseTempo.Frase.Trovoada -> 95
            else -> t.codigo
        }
        return try { IconesClima.bitmap(ctx, IconesClima.chave(codigo, dia, codigo == 61 || codigo == 95))?.let { android.graphics.drawable.BitmapDrawable(ctx.resources, it) } } catch (e: Exception) { null }
    }

    private fun lugar(): Pair<Double, Double> {
        val clima = ctx.getSharedPreferences(Prefs.CLIMA, Context.MODE_PRIVATE)
        return clima.getFloat("lat", Config.LATITUDE.toFloat()).toDouble() to clima.getFloat("lon", Config.LONGITUDE.toFloat()).toDouble()
    }

    private fun montarBarra(agora: Long, lat: Double, lon: Double) {
        barra.removeAllViews()
        val clima = ctx.getSharedPreferences(Prefs.CLIMA, Context.MODE_PRIVATE)
        // Só com medida de até 3 h, como no protetor clássico: melhor calar que mostrar um número velho.
        val temClima = clima.contains("temp") && agora - clima.getLong("em", 0) < 3 * 60 * 60_000L
        val horas = if (temClima) WidgetsTempo.lerHoras(clima.getString("horas", "").orEmpty()) else emptyList()
        val marcas = WidgetsTempo.deDuasEmDuas(horas, agora)
        val nascerPor = Astronomia.nascerEPor(agora, lat, lon)
        // O sol que cai dentro das próximas horas: o pôr de hoje, senão o nascer de amanhã (a curva cobre ~9 h).
        val solNaCurva = nascerPor?.let { (nasce, poe) ->
            when {
                poe > agora -> poe to false
                nasce > agora -> nasce to true
                else -> Astronomia.nascerEPor(agora + 24 * 60 * 60_000L, lat, lon)?.first?.let { it to true }
            }
        }
        val lua = Lua.resumoCurto(agora, lat).split("  ·  ")
        // Fase da lua: chave CARTAO_LUA (HomeCards, 18/11/2026); desligada, o widget não desenha a lua.
        val fase = lua[0].substringAfter("  ").trim().takeIf { it.isNotEmpty() && Extras.ligado(ctx, Extras.Chave.CARTAO_LUA) }
        val dados = WidgetsProtetor.Dados(
            agora = agora, temClima = temClima,
            temp = clima.getInt("temp", 0), codigo = clima.getInt("codigo", 0), chovendo = clima.getBoolean("chovendo", false),
            // Dia/noite recalculado agora, não o gravado pela HOME (que pode ter até 3 h; auditoria 10/11).
            eDia = Astronomia.eDia(agora, lat, lon, clima.getBoolean("eDia", true)),
            max = if (clima.contains("max")) clima.getInt("max", 0) else null,
            min = if (clima.contains("min")) clima.getInt("min", 0) else null,
            horas = horas, marcas = marcas, eDiaEm = { ms -> Astronomia.eDia(ms, lat, lon, true) },
            nascerPor = nascerPor, solNaCurva = solNaCurva,
            luaFase = fase, luaFracao = Lua.idade(agora) / 29.530588853, sul = lat < 0,
            umidade = clima.getInt("umidade", -1).takeIf { it >= 0 }, chuvaHoje = clima.getInt("chuva", -1).takeIf { it >= 0 },
            amanha = if (clima.contains("amanha_max")) WidgetsProtetor.Amanha(clima.getInt("amanha_max", 0), clima.getInt("amanha_min", 0),
                clima.getInt("amanha_chuva", -1).takeIf { it >= 0 }, clima.getInt("amanha_codigo", 0)) else null,
        )
        barra.addView(widgets.montar(Protetor.widget(ctx), dados), LinearLayout.LayoutParams(0, -2, 1f))
        montarEtiqueta(agora)
    }

    /** A obra do dia (ou a foto da Terra, ou o crédito do céu): etiqueta de museu logo acima da linha, à direita. */
    private fun montarEtiqueta(agora: Long) {
        etiquetaObra.removeAllViews()
        // Céu de verdade: no lugar da etiqueta da obra, o crédito das nuvens e do mapa (NOAA/NASA e Natural Earth pedem).
        if (imagemCeu != null) etiquetaObra.addView(texto(ctx.getString(R.string.ceu_credito), 8f, m400).apply {
            linhaUnica(); alpha = 0.7f; gravity = Gravity.END
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        (fundoTerra?.legenda() ?: fundo?.legenda(agora))?.let { (titulo, autor) ->
            if (estiloCredito == Protetor.CREDITO_DISCRETO) {
                // Uma linha só, pequena e apagada (17/11/2026): o crédito fica, sem chamar atenção.
                etiquetaObra.addView(texto("$titulo  ·  $autor", 12f, m400).apply { linhaUnica(); alpha = 0.6f; gravity = Gravity.END; sombrear() },
                    LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
                return@let
            }
            etiquetaObra.addView(texto(titulo, 11.5f, fraunces).apply { linhaUnica(); gravity = Gravity.END; setShadowLayer(dp(10).toFloat(), 0f, 0f, 0x99000000.toInt()) },
                LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
            etiquetaObra.addView(texto(autor, 8.5f, m400).apply { linhaUnica(); alpha = 0.85f; gravity = Gravity.END; setShadowLayer(dp(10).toFloat(), 0f, 0f, 0x99000000.toInt()) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2); gravity = Gravity.END })
        }
        // A etiqueta fica logo acima da linha de baixo, seja qual for a altura dela.
        barra.post {
            (etiquetaObra.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.gravity = Gravity.BOTTOM or Gravity.END
                lp.bottomMargin = dp(Estilo.MARGEM_VERTICAL) + barra.height + dp(14)
                etiquetaObra.layoutParams = lp
            }
        }
    }

    /** Sombra suave nas letras: o texto solto precisa se destacar da pintura sozinho. */
    private fun TextView.sombrear() = setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), 0xB3000000.toInt())

    // ------------------------------------------------------------------ notificações (canto de cima, à direita)
    private val aoMudarNotificacoes: () -> Unit = { if (::colunaAvisos.isInitialized) montarAvisos() }
    private val tirarAvisoVencido = Runnable { if (::colunaAvisos.isInitialized) montarAvisos() }

    /** Liga o ouvinte de notificações pelo shell próprio se ainda não estiver ligado (só o shell consegue). */
    private fun garantirOuvinte() {
        if (OuvinteNotificacoes.conectado || carregador.isShutdown) return
        carregador.execute { try { TocandoAgoraTv.ajustar(ctx, true) } catch (e: Exception) { android.util.Log.w(TAG, "ouvinte: $e") } }
    }

    private class Cartao(val icone: Drawable?, val emoji: String?, val app: String, val tempo: String, val titulo: String, val texto: String,
        val tipo: TeclasProtetor.Tipo = TeclasProtetor.Tipo.OUTRO, val chave: String? = null)

    // ------------------------------------------------------------------ controle remoto (ideia 28, TeclasProtetor.kt)
    /** Cartões na ordem da coluna (todos, não só os 3 visíveis) e o marcado por ◀ ▶ (-1 = nenhum). */
    private var cartoesAtuais: List<Cartao> = emptyList()
    private var escolhido = -1
    /** O que está tocando (TocandoAgoraTv.paraPainel), relido a cada 10 s fora da thread da tela. null = nada. */
    @Volatile private var tocando: org.json.JSONObject? = null

    val totalCartoes get() = cartoesAtuais.size
    val tipoEscolhido: TeclasProtetor.Tipo? get() = cartoesAtuais.getOrNull(escolhido)?.tipo
    val haMusica get() = tocando != null

    fun mover(delta: Int) {
        escolhido = TeclasProtetor.mover(escolhido, delta, cartoesAtuais.size)
        reagendarMarca(); montarAvisos()
    }

    /** OK num recado (sai do protetor e do painel) ou numa notificação (como deslizar no celular). */
    fun dispensarEscolhido() {
        val c = cartoesAtuais.getOrNull(escolhido) ?: return
        when (c.tipo) {
            TeclasProtetor.Tipo.RECADO -> ctx.getSharedPreferences(AvisoTv.PREFS_ULTIMO, Context.MODE_PRIVATE).edit().putLong("em", 0).apply()
            TeclasProtetor.Tipo.NOTIFICACAO -> c.chave?.let { OuvinteNotificacoes.dispensar(it) }
            else -> return
        }
        reagendarMarca(); montarAvisos()
    }

    fun alternarMusica() {
        if (carregador.isShutdown) return
        carregador.execute {
            try { TocandoAgoraTv.controlar(ctx, "alternar"); Thread.sleep(400) } catch (_: Exception) {}
            lerTocando()
        }
    }

    private fun lerTocando() {
        val t = try { TocandoAgoraTv.paraPainel(ctx) } catch (_: Exception) { null }
        val mudou = t?.toString() != tocando?.toString()
        tocando = t
        if (mudou && ::colunaAvisos.isInitialized) colunaAvisos.post { montarAvisos() }
    }

    private val releTocando = object : Runnable {
        override fun run() {
            if (carregador.isShutdown) return
            carregador.execute { lerTocando() }
            colunaAvisos.postDelayed(this, 10_000)
        }
    }

    private val limparMarca = Runnable { escolhido = -1; montarAvisos() }
    private fun reagendarMarca() {
        colunaAvisos.removeCallbacks(limparMarca)
        if (escolhido >= 0) colunaAvisos.postDelayed(limparMarca, TeclasProtetor.MARCA_MS)
    }

    private fun montarAvisos() {
        val agora = System.currentTimeMillis()
        val cartoes = mutableListOf<Cartao>()
        // Tocando agora (ideia 28): primeiro da coluna; OK nele pausa ou retoma.
        tocando?.let { t ->
            val pacote = t.optString("pacote")
            val ic = icones.getOrPut(pacote) { try { ctx.packageManager.getApplicationIcon(pacote) } catch (e: Exception) { null } }
            cartoes.add(Cartao(ic, if (ic == null) "♪" else null, t.optString("app"), if (t.optBoolean("tocando")) "▶" else "❚❚",
                t.optString("titulo"), t.optString("artista"), TeclasProtetor.Tipo.TOCANDO))
        }
        // Último aviso da TV: recado, campainha e o que pede atenção ficam 6 h; chuva 2 h; confirmação (ex.: "O Claude
        // olhou a tela") 2 min (AvisoRegras, 12/11/2026). Antes tudo ficava 6 h. Ao vencer, o cartão sai sozinho.
        val ultimo = ctx.getSharedPreferences(AvisoTv.PREFS_ULTIMO, Context.MODE_PRIVATE)
        val emRecado = ultimo.getLong("em", 0)
        val restante = AvisoRegras.restanteMs(emRecado, ultimo.getString("fica", null), agora)
        colunaAvisos.removeCallbacks(tirarAvisoVencido)
        if (restante > 0) {
            colunaAvisos.postDelayed(tirarAvisoVencido, restante + 500)
            val linhas = ultimo.getString("texto", "").orEmpty().split('\n')
            cartoes.add(Cartao(IconeLinha(IconeLinha.Tipo.RECADO), null, ultimo.getString("titulo", null) ?: ctx.getString(R.string.pg_recado),
                NotificacoesTv.haQuanto(emRecado, agora, ctx.getString(R.string.pg_ontem), ctx.getString(R.string.pg_agora)),
                linhas.firstOrNull().orEmpty(), linhas.drop(1).joinToString(" "), TeclasProtetor.Tipo.RECADO))
        }
        // Retrospectiva e mural (12/11/2026): depois do recado, antes do despertador e das notificações.
        extras12.forEach { (titulo, linha1, linha2) ->
            cartoes.add(Cartao(IconeLinha(IconeLinha.Tipo.RECADO), null, titulo, "", linha1, linha2))
        }
        despertador?.takeIf { it > agora }?.let {
            cartoes.add(Cartao(IconeLinha(IconeLinha.Tipo.DESPERTADOR), null, ctx.getString(R.string.pg_app_despertador), "", quandoTexto(it, agora), ""))
        }
        NotificacoesTv.atuais.forEach { a ->
            val ic = icones.getOrPut(a.pacote) { try { ctx.packageManager.getApplicationIcon(a.pacote) } catch (e: Exception) { null } }
            cartoes.add(Cartao(ic, if (ic == null) "🔔" else null, a.app,
                NotificacoesTv.haQuanto(a.quando, agora, ctx.getString(R.string.pg_ontem), ctx.getString(R.string.pg_agora)), a.titulo, a.texto,
                TeclasProtetor.Tipo.NOTIFICACAO, a.chave))
        }
        colunaAvisos.removeAllViews()
        cartoesAtuais = cartoes
        if (escolhido >= cartoes.size) escolhido = cartoes.size - 1   // o marcado sumiu (dispensado ou vencido)
        // Só 3 à vista; com ◀ ▶ a janela anda para o marcado continuar visível. "Um por vez" (17/11/2026): 1 à vista,
        // trocando a cada 8 s ([rodarAviso]); com ◀ ▶ fica no marcado.
        val visiveis = if (estiloAvisos == Protetor.AVISOS_UM) 1 else 3
        val inicio = if (visiveis == 1 && escolhido < 0) (if (cartoes.isEmpty()) 0 else rodizio % cartoes.size)
            else TeclasProtetor.inicioJanela(escolhido, cartoes.size, visiveis)
        colunaAvisos.removeCallbacks(rodarAviso)
        if (visiveis == 1 && cartoes.size > 1) colunaAvisos.postDelayed(rodarAviso, 8_000)
        cartoes.drop(inicio).take(visiveis).forEachIndexed { i, c ->
            val v = if (estiloAvisos == Protetor.AVISOS_LISTA) linhaAviso(c) else cartao(c)
            if (escolhido >= 0) {
                if (inicio + i == escolhido) v.foreground = GradientDrawable().apply {
                    cornerRadius = dp(13).toFloat(); setStroke(dp(2), 0xE6FFFFFF.toInt())
                } else v.alpha = 0.55f
            }
            colunaAvisos.addView(v, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) })
        }
        val fora = cartoes.size - inicio - visiveis
        if (fora > 0 && visiveis > 1) colunaAvisos.addView(texto(ctx.getString(R.string.pg_mais_notif, fora), 8f, m500).apply {
            alpha = 0.8f; gravity = Gravity.END; setPaddingRelative(0, 0, dp(4), 0)
        }, LinearLayout.LayoutParams(-1, -2))
        // Dica do que o OK faz, só com um cartão marcado (o protetor parado continua limpo).
        cartoes.getOrNull(escolhido)?.let { c ->
            val dica = when (c.tipo) {
                TeclasProtetor.Tipo.RECADO, TeclasProtetor.Tipo.NOTIFICACAO -> R.string.pt_dica_dispensar
                TeclasProtetor.Tipo.TOCANDO -> R.string.pt_dica_pausar
                TeclasProtetor.Tipo.OUTRO -> R.string.pt_dica_andar
            }
            colunaAvisos.addView(texto(ctx.getString(dica), 8f, m500).apply {
                alpha = 0.85f; gravity = Gravity.END; setPaddingRelative(0, dp(2), dp(4), 0); sombrear()
            }, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** "Um por vez": o próximo aviso, com o mesmo esmaecer da curiosidade. */
    private var rodizio = 0
    private val rodarAviso = Runnable {
        colunaAvisos.animate().alpha(0f).setDuration(450).withLayer().withEndAction {
            rodizio++; montarAvisos()
            colunaAvisos.animate().alpha(1f).setDuration(650).withLayer().start()
        }.start()
    }

    /** Avisos sem caixa (17/11/2026, "Lista"): ícone, título e texto soltos, alinhados à direita, com sombra nas letras. */
    private fun linhaAviso(c: Cartao): View = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.END; setPaddingRelative(dp(6), dp(4), dp(6), dp(4))
        val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        tx.addView(texto(listOf(c.app, c.tempo).filter { it.isNotEmpty() }.joinToString("  ·  "), 12f, m600).apply {
            letterSpacing = 0.1f; isAllCaps = true; alpha = 0.75f; linhaUnica(); gravity = Gravity.END; sombrear()
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        tx.addView(texto(c.titulo, 14f, m600).apply { linhaUnica(); gravity = Gravity.END; sombrear() },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(2) })
        if (c.texto.isNotEmpty()) tx.addView(texto(c.texto, 12f, m400).apply { linhaUnica(); alpha = 0.85f; gravity = Gravity.END; sombrear() },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(1) })
        addView(tx, LinearLayout.LayoutParams(0, -2, 1f))
        val ic = FrameLayout(ctx)
        if (c.icone != null) ic.addView(ImageView(ctx).apply { setImageDrawable(c.icone) }, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        else ic.addView(texto(c.emoji ?: "", 16f, m400).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        addView(ic, LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(10) })
    }

    private fun cartao(c: Cartao): View = Vidro(ctx, vidro, 13f).apply {
        val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPaddingRelative(dp(12), dp(10), dp(12), dp(10)) }
        val caixaIcone = FrameLayout(ctx).apply { background = arredondado(0x2EFFFFFF, 7f) }
        if (c.icone != null) caixaIcone.addView(ImageView(ctx).apply { setImageDrawable(c.icone) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        else caixaIcone.addView(texto(c.emoji ?: "", 14f, m400).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        linha.addView(caixaIcone, LinearLayout.LayoutParams(dp(26), dp(26)))
        val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cabeca = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        cabeca.addView(texto(c.app, 7.5f, m600).apply { letterSpacing = 0.12f; isAllCaps = true; alpha = 0.75f; linhaUnica() },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (c.tempo.isNotEmpty()) cabeca.addView(texto(c.tempo, 7.5f, m600).apply { alpha = 0.75f }, LinearLayout.LayoutParams(-2, -2))
        tx.addView(cabeca, LinearLayout.LayoutParams(-1, -2))
        tx.addView(texto(c.titulo, 11f, m600).apply { linhaUnica() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        if (c.texto.isNotEmpty()) tx.addView(texto(c.texto, 9.5f, m400).apply { linhaUnica(); alpha = 0.85f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        linha.addView(tx, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(9) })
        addView(linha, FrameLayout.LayoutParams(-1, -2))
    }

    /** Próximo despertador e o próximo compromisso de hoje: lidos fora da thread da tela (a agenda é uma consulta). */
    private fun carregarAvisos(agora: Long) {
        if (carregador.isShutdown) return
        carregador.execute {
            var alarme: Long? = null
            var lista: List<Pair<String, String>> = emptyList()
            try {
                // Só o que toca nas próximas 24 h: um alarme de daqui a 5 dias não ajuda na tela parada.
                Despertador.proximo(ctx)?.let { (_, quando) -> if (quando - agora < 24 * 60 * 60_000L) alarme = quando }
            } catch (e: Exception) { android.util.Log.w(TAG, "despertador: $e") }
            try {
                if (!ModoVisita.ativo(ctx)) {
                    val fimDoDia = Calendar.getInstance().apply {
                        timeInMillis = agora; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                    }.timeInMillis
                    val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
                    lista = Agenda.eventosDaTv(ctx, agora, fimDoDia).filter { it.fim > agora }.take(2).map { e ->
                        e.titulo to (if (e.diaInteiro) ctx.getString(R.string.pg_dia_todo) else hm.format(Date(e.inicio)))
                    }
                }
            } catch (e: Exception) { android.util.Log.w(TAG, "agenda: $e") }
            val cartoes12 = ArrayList<Triple<String, String, String>>()
            try {
                if (!ModoVisita.ativo(ctx)) {
                    Retrospectiva.cartao(ctx, agora)?.let { cartoes12 += it }
                    runCatching { RetrospectivaAno.cartao(ctx, agora) }.getOrNull()?.let { cartoes12 += it }   // ideia 92 (dezembro)
                    // Ideia 12: o próximo jogo do "Meu time" nas próximas 24 h ("Flamengo × Palmeiras · 21:30").
                    runCatching { LembretesTv.proximoJogo(ctx, agora) }.getOrNull()
                        ?.let { cartoes12 += Triple(ctx.getString(R.string.bu_motivo_time), it, "") }
                    // Ideia 93: aniversários da lista da TV, na véspera e no dia (independe da chave do mural).
                    Aniversarios.textos(ctx, agora).take(3).forEach { cartoes12 += Triple(ctx.getString(R.string.an_rotulo), it, "") }
                    if (Extras.ligado(ctx, Extras.Chave.MURAL)) {
                        val m = Mural.ler(ctx)
                        // Aniversários de hoje (calendário de aniversários do Google, que vem dos contatos).
                        val inicioDia = Calendar.getInstance().apply {
                            timeInMillis = agora; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                        }.timeInMillis
                        Agenda.eventosDaTv(ctx, inicioDia, inicioDia + 24 * 3_600_000L - 1)
                            .filter { it.diaInteiro && Mural.eAniversario(it.titulo) }.take(2)
                            .forEach { cartoes12 += Triple(ctx.getString(R.string.pv_hoje), it.titulo, "") }
                        m.naoLidos.forEach { cartoes12 += Triple(ctx.getString(R.string.pg_recado), it, "") }   // ideia 95
                        if (m.compras.isNotEmpty()) cartoes12 += Triple(ctx.getString(R.string.pg_compras), m.compras.joinToString(", "), "")
                    }
                }
            } catch (e: Exception) { android.util.Log.w(TAG, "mural: $e") }
            raiz.post {
                extras12 = cartoes12
                despertador = alarme; eventos = lista
                montarAvisos()
                montarAgenda()
            }
        }
    }

    /** Uma linha por compromisso, embaixo da data: ícone de calendário, título e "hoje" ou a hora. */
    private fun montarAgenda() {
        agenda.removeAllViews()
        eventos.forEachIndexed { i, (titulo, quando) ->
            agenda.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply { setImageDrawable(IconeLinha(IconeLinha.Tipo.CALENDARIO)); alpha = 0.9f },
                    LinearLayout.LayoutParams(dp(15), dp(15)))
                addView(texto(titulo, 12f, m600).apply { linhaUnica(); maxWidth = dp(300); setShadowLayer(dp(8).toFloat(), 0f, 0f, sombra) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })
                addView(texto("·  $quando", 12f, m400).apply { alpha = 0.8f; setShadowLayer(dp(8).toFloat(), 0f, 0f, sombra) },
                    LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (i == 0) 10 else 5) })
        }
    }

    private fun quandoTexto(quando: Long, agora: Long): String {
        val h = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(quando))
        val hoje = Calendar.getInstance().apply { timeInMillis = agora }.get(Calendar.DAY_OF_YEAR)
        val dia = Calendar.getInstance().apply { timeInMillis = quando }.get(Calendar.DAY_OF_YEAR)
        return if (dia == hoje) ctx.getString(R.string.pg_hoje_as, h) else ctx.getString(R.string.pg_amanha_as, h)
    }

    // ------------------------------------------------------------------ movimento e vidro
    /**
     * Movimento lento de câmera sobre a pintura ("efeito Ken Burns"): aproxima de 6 % a 12 % e desliza de um lado ao outro
     * em 75 s, e volta. Dá vida ao quadro e nenhum pixel fica parado (anti-burn-in). Camada de hardware: a GPU só move a
     * textura. Os painéis de vidro redesenham junto, para o desfoque atrás deles acompanhar a pintura.
     */
    private fun kenBurns(v: View) {
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // O zoom mínimo tem que cobrir o deslize dos dois lados (1 + 2·deslize/largura), senão aparece a borda da
        // pintura (aconteceu na 1ª foto da TV: 1.04 com 36 dp mostrava uma faixa escura na direita).
        val desliza = dp(24).toFloat()
        v.scaleX = 1.06f; v.scaleY = 1.06f; v.translationX = -desliza
        // 10 passos por segundo, não 60 (13/11/2026): a pintura anda ~96 px em 75 s, 0,02 px por quadro a 60 fps. A 10 fps
        // cada passo é ~0,1 px (invisível) e a GPU e a thread da tela (a mesma das teclas) trabalham 6x menos, por horas.
        val curva = android.view.animation.AccelerateDecelerateInterpolator()
        val inicio = android.os.SystemClock.uptimeMillis()
        val passo = object : Runnable {
            override fun run() {
                val t = ((android.os.SystemClock.uptimeMillis() - inicio) % 150_000L) / 75_000f   // 0..2: ida e volta
                val f = curva.getInterpolation(if (t <= 1f) t else 2f - t)
                val esc = 1.06f + 0.06f * f
                v.scaleX = esc; v.scaleY = esc; v.translationX = -desliza + 2 * desliza * f
                vidro.redesenhar()
                v.postDelayed(this, PASSO_KEN_BURNS_MS)
            }
        }
        animador = v to passo
        passo.run()
    }
    private var animador: Pair<View, Runnable>? = null

    /** Pixels da pintura -> tela: recorte do ImageView, depois o zoom/deslize do Ken Burns, depois onde a raiz está. */
    private fun matrizPintura(f: FundoGaleria): Matrix? {
        val m = f.matrizImagem() ?: return null   // (10x por segundo agora, não 60: as alocações daqui pesam pouco)
        m.postConcat(f.matrix)
        m.postTranslate(f.left.toFloat(), f.top.toFloat())
        val loc = IntArray(2); raiz.getLocationOnScreen(loc)
        m.postTranslate(loc[0].toFloat(), loc[1].toFloat())
        return m
    }

    // ------------------------------------------------------------------ utilidades
    private fun texto(t: String, sp: Float, face: Typeface) = TextView(ctx).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = face; setTextColor(branco); includeFontPadding = false
    }

    private fun TextView.linhaUnica() { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    private fun arredondado(cor: Int, raioDp: Float) = GradientDrawable().apply { setColor(cor); cornerRadius = raioDp * ctx.resources.displayMetrics.density }

    fun soltar() {
        animador?.let { (v, r) -> v.removeCallbacks(r) }; animador = null
        if (::relogio.isInitialized) { relogio.removeCallbacks(tique); relogio.soltar() }
        if (::grupo.isInitialized) grupo.removeCallbacks(conferirMostrar)
        fundoTerra?.soltar()
        NotificacoesTv.parar(aoMudarNotificacoes)
        if (::linhasTempo.isInitialized) linhasTempo.soltar()
        if (::colunaAvisos.isInitialized) { colunaAvisos.removeCallbacks(tirarAvisoVencido); colunaAvisos.removeCallbacks(releTocando); colunaAvisos.removeCallbacks(limparMarca); colunaAvisos.removeCallbacks(rodarAviso) }
        vidro.soltar()
        fundo?.let { f -> (0 until f.childCount).map { f.getChildAt(it) }.filterIsInstance<ImageView>().forEach { it.setImageDrawable(null) } }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private companion object { const val TAG = "TvLauncher.Protetor"; const val PASSO_KEN_BURNS_MS = 100L }
}
