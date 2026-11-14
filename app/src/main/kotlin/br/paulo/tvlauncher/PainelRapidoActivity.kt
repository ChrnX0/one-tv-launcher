package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.TextUtils
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.view.Gravity
import android.view.InputDevice
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.Date
import java.util.concurrent.Executors

/**
 * O painel rápido do launcher (11/11/2026, dono: "quero o padrão visual e as funcionalidades do painel do Google para o
 * nosso, e ocultar o do Google"; v2 no mesmo dia: "deixar mais bonito" e atalhos úteis, proposta aprovada).
 *
 * Desenho no molde do painel do Google TV (13/11/2026, dono: "meio transparente", "muito grande" e depois "seguindo a
 * referência do painel do launcherx"; medido numa foto do painel do Google na Sala): coluna SÓLIDA de 272 dp na direita,
 * da altura da tela (16 dp de margem), POR CIMA de qualquer app; o resto da tela só escurece (FLAG_DIM_BEHIND). De cima
 * para baixo:
 *  - hora (28 sp, peso único) com os botões Configurações do launcher, Configurações da TV e perfil (OK mostra os
 *    perfis); embaixo, a data e o clima da HOME numa linha;
 *  - "Tocando agora" (só quando há mídia): app, título, tocar/pausar e próxima;
 *  - tiles de 54 dp, ícone solto e nome de 14 sp: Wi-Fi, Bluetooth, Timer, Dormir, Protetor, Áudio, Alarme e Visita. O
 *    detalhe (12 sp) só aparece quando diz algo (rede, controle, horário); ligado = ícone e detalhe verde-água;
 *  - as notificações com o ícone do app (OK abre, o X dispensa) e, no pé do painel, "Limpar tudo". O aviso fixo de
 *    depuração por Wi-Fi nem chega: o ouvinte o adia.
 *
 * Quem abre: a bolinha do perfil na HOME, o HOME segurado (BotoesControle) e, se algo abrir o painel do Google por outro
 * caminho, o vigia de janelas troca pelo nosso (Home.janelaApareceu). Voltar ou abrir qualquer coisa fecha.
 * Sistema de design: Estilo (Manrope, vidro, foco de linha = pílula branca, acento só para "ligado", 8/16 dp).
 */
class PainelRapidoActivity : Activity() {
    companion object {
        fun abrir(ctx: Context) {
            try {
                ctx.startActivity(Intent(ctx, PainelRapidoActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            } catch (_: Exception) {}
        }

        /** Opções do timer de suspensão, em minutos: cada OK passa para a próxima; depois da última, desliga. */
        val OPCOES_TIMER = listOf(15, 30, 60, 90, 120)

        /** A próxima opção do timer, dado quanto falta agora (null = sem timer). null na saída = desligar. Puro. */
        fun proximoTimer(faltamMin: Int?): Int? =
            if (faltamMin == null) OPCOES_TIMER.first() else OPCOES_TIMER.firstOrNull { it > faltamMin }

        /**
         * `service call` deu certo? A 1ª palavra da resposta é o código de erro: 00000000 = ok; uma exceção (sem permissão,
         * serviço errado) volta como ffffff.. com a mensagem. Puro.
         */
        fun chamadaRespondeu(saida: String?): Boolean =
            saida != null && Regex("""Result: Parcel\(\s*(0x[0-9a-f]+:\s*)?00000000\b""").containsMatchIn(saida)

        /** Largura: a do painel do Google TV medida na foto da Sala (13/11/2026). */
        private const val LARGURA_DP = 272
        /** Margem da janela: 16 dp em cima e embaixo, 24 dp à direita (como o do Google). */
        private const val MARGEM_V_DP = 16
        private const val MARGEM_D_DP = 24
        /** Raio do painel (o do Google é bem arredondado). */
        private const val RAIO_PAINEL_DP = 24
        /** Hora: informação, não o assunto (o DISPLAY da HOME é 56 sp). */
        private const val HORA_SP = 28f
        /** Tile: altura fixa e tamanhos do nome e do detalhe. */
        private const val TILE_DP = 54
        private const val TILE_SP = 14f
        private const val DETALHE_SP = 12f
        /** Botões redondos do cabeçalho e do Tocando agora. */
        private const val BOTAO_DP = 34
        /** Fundo do painel: azul-ardósia escuro e opaco, como o do Google (e próximo do FUNDO do launcher). */
        private const val FUNDO_PAINEL = 0xFF151C25.toInt()
        /** Ícone de tile desligado: o texto do sistema a 85%. */
        private const val ICONE_APAGADO = 0xD9F2F5F3.toInt()
    }

    /** Um tile: a casca focável, o ícone, o nome e o detalhe (some quando vazio). [ligado] pinta ícone e detalhe de acento. */
    private inner class Tile(val casca: LinearLayout, val icone: IconeCard, val titulo: TextView, val detalhe: TextView) {
        var ligado = false
            set(v) { field = v; pintar() }
        fun pintar() {
            val foco = casca.isFocused
            casca.background = Estilo.superficie(this@PainelRapidoActivity, foco)
            titulo.setTextColor(if (foco) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO)
            titulo.typeface = Estilo.fonte(if (foco) 600 else 500)   // no branco o texto escuro precisa de mais peso
            icone.definirCor(when {
                ligado && foco -> Estilo.ACENTO_NO_FOCO
                ligado -> Estilo.ACENTO
                foco -> Estilo.TEXTO_NO_FOCO
                else -> ICONE_APAGADO
            })
            detalhe.setTextColor(when {
                ligado && foco -> Estilo.ACENTO_NO_FOCO
                ligado -> Estilo.ACENTO
                foco -> Estilo.TEXTO_NO_FOCO_2
                else -> Estilo.TEXTO_3
            })
        }
        /** Troca o detalhe; vazio = tile de uma linha só. */
        fun detalhe(texto: String) {
            detalhe.text = texto
            detalhe.visibility = if (texto.isEmpty()) View.GONE else View.VISIBLE
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private val fundo = Executors.newSingleThreadExecutor()
    private lateinit var txtData: TextView
    private lateinit var txtHora: TextView
    private lateinit var txtClima: TextView
    private lateinit var imgClima: ImageView
    private lateinit var sepClima: TextView
    private lateinit var limpar: TextView
    private lateinit var fotoPerfil: ImageView
    private lateinit var linhaPerfis: View
    private lateinit var cartaoMidia: LinearLayout
    private lateinit var txtMidiaApp: TextView
    private lateinit var txtMidiaTitulo: TextView
    private lateinit var imgMidiaApp: ImageView
    private lateinit var iconeTocar: IconeCard
    private lateinit var linhaSecao: LinearLayout
    private lateinit var txtSecao: TextView
    private lateinit var listaAvisos: LinearLayout
    private lateinit var tWifi: Tile
    private lateinit var tBluetooth: Tile
    private lateinit var tTimer: Tile
    private lateinit var tAudio: Tile
    private lateinit var tDespertador: Tile
    private lateinit var tVisita: Tile
    private lateinit var tFiltro: Tile
    /** Os blocos que entraram na tela, na ordem (ideia 42): o foco começa no 1º. */
    private val blocosNaTela = ArrayList<View>()
    private val visita by lazy { ModoVisita.ativo(this) }

    private val relogio = object : Runnable {
        override fun run() {
            val agora = Date()
            txtData.text = DateFormat.format(DateFormat.getBestDateTimePattern(java.util.Locale.getDefault(), "EEEdMMM"), agora).toString()
            mostrarTimer()
            mostrarDespertador()
            mostrarClima()
            ui.postDelayed(this, 60_000 - System.currentTimeMillis() % 60_000 + 50)
        }
    }

    /** A cada 3 s com o painel aberto: rede (fora da tela), mídia e Bluetooth (baratos). */
    private val medir = object : Runnable {
        override fun run() {
            fundo.execute {
                val r = try { Rede.medir(this@PainelRapidoActivity) } catch (_: Exception) { null }
                val m = try { TocandoAgoraTv.paraPainel(this@PainelRapidoActivity) } catch (_: Exception) { null }
                ui.post { if (!isFinishing) { mostrarRede(r); mostrarMidia(m) } }
            }
            mostrarBluetooth()
            mostrarAudio()
            ui.postDelayed(this, 3_000)
        }
    }

    private val aoMudarAvisos: () -> Unit = { if (!isFinishing) preencherAvisos() }

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        configurarJanela()

        val conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(12), dp(20), dp(12), dp(12))
        }
        conteudo.addView(cabecalho())
        linhaPerfis = perfis().apply { visibility = View.GONE }
        conteudo.addView(linhaPerfis, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
        conteudo.addView(midia(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
        conteudo.addView(tiles(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18) })
        // Sem margem a mais: o título de seção (Estilo.secao) já traz 24 dp em cima e 8 embaixo. Com a margem antiga sobrava
        // um vão de ~45 dp entre os tiles e "Notificações" (foto na Sala, 13/11).
        conteudo.addView(secaoAvisos(), LinearLayout.LayoutParams(-1, -2))
        listaAvisos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        conteudo.addView(listaAvisos, LinearLayout.LayoutParams(-1, -2))
        // "Limpar tudo" no pé do painel: o espaço que sobra fica antes dele (a rolagem enche a altura: fillViewport).
        conteudo.addView(View(this), LinearLayout.LayoutParams(-1, 0, 1f))
        conteudo.addView(botaoLimpar(), LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(12) })

        val rolagem = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            isFillViewport = true
            addView(conteudo, FrameLayout.LayoutParams(-1, -2))
        }
        val painel = FrameLayout(this).apply {
            // Opaco (13/11/2026): com o vidro, a HOME e os banners dos apps apareciam por trás ("meio transparente").
            background = Estilo.fundo(this@PainelRapidoActivity, FUNDO_PAINEL, RAIO_PAINEL_DP)
            clipToOutline = true
            addView(rolagem, FrameLayout.LayoutParams(-1, -1))
        }
        setContentView(painel)
        // Entra deslizando da direita, como o do Google.
        painel.translationX = dp(Estilo.E3).toFloat()
        painel.alpha = 0f
        painel.animate().translationX(0f).alpha(1f).setDuration(Estilo.TRANSICAO_MS).setInterpolator(DecelerateInterpolator()).start()
        painel.post { (blocosNaTela.firstOrNull() ?: tWifi.casca).requestFocus() }   // ideia 42: o 1º bloco escolhido

        // O ouvinte de notificações costuma estar ligado (o protetor usa). Se não estiver, liga pelo shell, fora da tela.
        if (!OuvinteNotificacoes.conectado) fundo.execute { TocandoAgoraTv.ajustar(applicationContext, true) }
    }

    /**
     * A janela do tamanho do painel: 272 dp, altura da tela menos 16 dp em cima e embaixo, a 24 dp da direita, com a tela
     * de trás escurecida. Sem desfoque de propósito: com o fundo opaco ele não aparece e só gastaria GPU.
     */
    private fun configurarJanela() {
        val w = window
        w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        w.setDimAmount(0.5f)
        w.setLayout(dp(LARGURA_DP), resources.displayMetrics.heightPixels - 2 * dp(MARGEM_V_DP))
        w.setGravity(Gravity.END or Gravity.CENTER_VERTICAL)
        w.attributes = w.attributes.apply { x = dp(MARGEM_D_DP) }
    }

    override fun onResume() {
        super.onResume()
        ui.post(relogio)
        ui.post(medir)
        preencherAvisos()
        NotificacoesTv.ouvir(aoMudarAvisos)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(relogio)
        ui.removeCallbacks(medir)
        NotificacoesTv.parar(aoMudarAvisos)
    }

    override fun onDestroy() {
        super.onDestroy()
        fundo.shutdownNow()
    }

    // ---------------------------------------------------------------- cabeçalho e perfis

    private fun cabecalho(): View {
        // Hora num peso só (o desenho forte/fino da HOME fica estranho pequeno). TextClock: atualiza sozinho.
        txtHora = android.widget.TextClock(this).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            textSize = HORA_SP
            typeface = Estilo.fonte(600)
            setTextColor(Estilo.TEXTO)
            fontFeatureSettings = "tnum"
            letterSpacing = -0.01f
            includeFontPadding = false
        }
        // Data e clima numa linha, embaixo da hora (dono, 13/11: "hora acima da data", para os 3 botões caberem).
        txtData = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { maxLines = 1; typeface = Estilo.fonte(500) }
        sepClima = Estilo.texto(this, Estilo.Papel.LEGENDA, " · ").apply { typeface = Estilo.fonte(500) }
        imgClima = ImageView(this)
        txtClima = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END; typeface = Estilo.fonte(500) }
        val linhaInfo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(6), 0)
            addView(txtData)
            addView(sepClima)
            addView(imgClima, LinearLayout.LayoutParams(dp(16), dp(16)).apply { marginEnd = dp(4) })
            addView(txtClima, LinearLayout.LayoutParams(0, -2, 1f))
        }
        // Dois botões de configurações ao lado do perfil (12/11/2026, dono): o ícone do launcher abre as nossas
        // Configurações; a engrenagem abre as do Android (o app de Configurações fica fora da linha de apps).
        val configLauncher = botaoRedondo(IconeCard(this, "launcher"), getString(R.string.pr_configuracoes)) {
            abrirEFechar(Intent(this, ConfigLauncherActivity::class.java))
        }
        val configTv = botaoRedondo(IconeCard(this, "sistema"), getString(R.string.pr_config_tv)) {
            abrirEFechar(Intent(Settings.ACTION_SETTINGS))
        }
        fotoPerfil = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = circulo()
            setImageBitmap(Perfil.avatar(this@PainelRapidoActivity, dp(BOTAO_DP), conta = if (visita) null else Perfil.ativa(this@PainelRapidoActivity)))
        }
        val perfil = botaoRedondo(fotoPerfil, getString(R.string.pr_perfis)) {
            linhaPerfis.visibility = if (linhaPerfis.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        val topo = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            setPadding(dp(6), 0, dp(4), 0)
            addView(txtHora, LinearLayout.LayoutParams(0, -2, 1f))
            // Modo visita: nada de Configurações nem de trocar perfil.
            if (!visita) {
                addView(configLauncher, LinearLayout.LayoutParams(dp(BOTAO_DP), dp(BOTAO_DP)).apply { marginEnd = dp(Estilo.E1) })
                addView(configTv, LinearLayout.LayoutParams(dp(BOTAO_DP), dp(BOTAO_DP)).apply { marginEnd = dp(Estilo.E1) })
                addView(perfil, LinearLayout.LayoutParams(dp(BOTAO_DP), dp(BOTAO_DP)))
            }
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(topo)
            addView(linhaInfo, LinearLayout.LayoutParams(-1, -2))
        }
    }

    /** O clima que a HOME guardou (prefs "clima"): ícone, temperatura e condição. Velho (> 3 h) some. */
    private fun mostrarClima() {
        val c = getSharedPreferences("clima", MODE_PRIVATE)
        val codigo = c.getInt("codigo", -1)
        if (codigo < 0 || System.currentTimeMillis() - c.getLong("em", 0) > 3 * 3600_000L) {
            sepClima.visibility = View.GONE; imgClima.visibility = View.GONE; txtClima.visibility = View.GONE; return
        }
        val chovendo = c.getBoolean("chovendo", false)
        val lat = c.getFloat("lat", Config.LATITUDE.toFloat()).toDouble()
        val lon = c.getFloat("lon", Config.LONGITUDE.toFloat()).toDouble()
        val eDia = Astronomia.eDia(System.currentTimeMillis(), lat, lon, c.getBoolean("eDia", true))
        val chave = IconesClima.chave(codigo, eDia, chovendo)
        val bmp = IconesClima.bitmap(this, chave)
        imgClima.visibility = if (bmp != null) View.VISIBLE else View.GONE
        if (bmp != null) imgClima.setImageBitmap(bmp)
        txtClima.text = "${c.getInt("temp", 0)}° ${IconesClima.texto(codigo, chovendo)}"
        txtClima.visibility = View.VISIBLE
        sepClima.visibility = View.VISIBLE
    }

    /** Botão redondo do cabeçalho: vidro claro em repouso, branco e cresce com o foco. */
    private fun botaoRedondo(dentro: View, descricao: String, aoClicar: () -> Unit): FrameLayout = FrameLayout(this).apply {
        isFocusable = true
        isClickable = true
        contentDescription = descricao
        background = if (dentro is ImageView) null else anel(false)
        val lado = if (dentro is ImageView) dp(BOTAO_DP - 4) else dp(16)
        addView(dentro, FrameLayout.LayoutParams(lado, lado, Gravity.CENTER))
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, foco ->
            v.background = if (dentro is ImageView && !foco) null else anel(foco)
            (dentro as? IconeCard)?.definirCor(if (foco) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO)
            v.animate().scaleX(if (foco) 1.1f else 1f).scaleY(if (foco) 1.1f else 1f).setDuration(Estilo.FOCO_MS).start()
        }
    }

    private fun anel(foco: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(if (foco) Estilo.FOCO else Estilo.SUPERFICIE_ALTA)
        setStroke(dp(1), if (foco) Estilo.FOCO else Estilo.BORDA)
    }

    /** As contas Google da TV como bolinhas (OK troca o perfil ativo) e o "+" (Configurações > Contas e perfis). */
    private fun perfis(): View {
        val contas = Perfil.contas(this)
        val ativa = Perfil.ativa(this, contas)
        val legenda = Estilo.texto(this, Estilo.Papel.LEGENDA, ativa?.let { Perfil.usuario(it) } ?: getString(R.string.pp_sem_conta))
        val bolinhas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        for (conta in contas) {
            val eAtiva = conta == ativa
            val foto = ImageView(this).apply {
                setImageBitmap(Perfil.avatar(this@PainelRapidoActivity, dp(48), conta, contas))
                scaleType = ImageView.ScaleType.CENTER_CROP
                clipToOutline = true
                outlineProvider = circulo()
            }
            val b = bolinhaPerfil(foto, eAtiva) {
                if (!eAtiva) Perfil.definirAtiva(this, conta)
                finish()   // a HOME redesenha a bolinha no onResume
            }
            b.setOnFocusChangeListener { v, foco ->
                v.background = anelPerfil(foco, eAtiva)
                v.animate().scaleX(if (foco) 1.1f else 1f).scaleY(if (foco) 1.1f else 1f).setDuration(Estilo.FOCO_MS).start()
                if (foco) legenda.text = Perfil.usuario(conta) + if (eAtiva) getString(R.string.pp_ativo) else ""
            }
            bolinhas.addView(b, LinearLayout.LayoutParams(dp(56), dp(56)).apply { marginEnd = dp(Estilo.E1) })
        }
        val mais = Estilo.texto(this, Estilo.Papel.TITULO, "+").apply {
            gravity = Gravity.CENTER
            typeface = Estilo.fonte(200)
            includeFontPadding = false
        }
        val b = bolinhaPerfil(mais, false) { abrirEFechar(Intent(Settings.ACTION_SETTINGS)) }
        b.contentDescription = getString(R.string.pp_adicionar)
        b.setOnFocusChangeListener { v, foco ->
            v.background = anelPerfil(foco, false)
            v.animate().scaleX(if (foco) 1.1f else 1f).scaleY(if (foco) 1.1f else 1f).setDuration(Estilo.FOCO_MS).start()
            if (foco) legenda.text = getString(R.string.pp_adicionar)
        }
        bolinhas.addView(b, LinearLayout.LayoutParams(dp(56), dp(56)))
        val rolagem = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            clipChildren = false
            clipToPadding = false
            setPadding(dp(4), dp(4), dp(4), dp(4))
            addView(bolinhas)
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            addView(rolagem)
            addView(legenda, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        }
    }

    private fun bolinhaPerfil(dentro: View, eAtiva: Boolean, aoClicar: () -> Unit) = FrameLayout(this).apply {
        isFocusable = true
        isClickable = true
        setPadding(dp(4), dp(4), dp(4), dp(4))
        background = anelPerfil(false, eAtiva)
        addView(dentro, FrameLayout.LayoutParams(-1, -1))
        setOnClickListener { aoClicar() }
    }

    private fun anelPerfil(foco: Boolean, eAtiva: Boolean) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(3), when { foco -> Estilo.FOCO; eAtiva -> Estilo.ACENTO; else -> Estilo.BORDA })
    }

    // ---------------------------------------------------------------- tocando agora

    private fun midia(): View {
        imgMidiaApp = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dp(Estilo.RAIO_PEQUENO).toFloat())
            }
        }
        txtMidiaApp = Estilo.texto(this, Estilo.Papel.LEGENDA).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        txtMidiaTitulo = Estilo.texto(this, Estilo.Papel.CORPO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(txtMidiaApp)
            addView(txtMidiaTitulo)
        }
        iconeTocar = IconeCard(this, "pausar")
        val tocar = botaoRedondo(iconeTocar, getString(R.string.pr_tocar_pausar)) {
            fundo.execute { TocandoAgoraTv.controlar(applicationContext, "alternar") }
            ui.removeCallbacks(medir); ui.postDelayed(medir, 400)   // sem tirar o agendado, cada OK criava outro ciclo de 3 s
        }
        val proxima = botaoRedondo(IconeCard(this, "proxima"), getString(R.string.pr_proxima)) {
            fundo.execute { TocandoAgoraTv.controlar(applicationContext, "proxima") }
            ui.removeCallbacks(medir); ui.postDelayed(medir, 600)   // sem tirar o agendado, cada OK criava outro ciclo de 3 s
        }
        cartaoMidia = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            visibility = View.GONE
            setPadding(dp(Estilo.E2 - 4), dp(Estilo.E2 - 4), dp(Estilo.E2 - 4), dp(Estilo.E2 - 4))
            background = Estilo.superficie(this@PainelRapidoActivity, false)
            addView(imgMidiaApp, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(Estilo.E2 - 4) })
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            addView(tocar, LinearLayout.LayoutParams(dp(BOTAO_DP), dp(BOTAO_DP)).apply { marginStart = dp(Estilo.E1) })
            addView(proxima, LinearLayout.LayoutParams(dp(BOTAO_DP), dp(BOTAO_DP)).apply { marginStart = dp(Estilo.E1) })
        }
        return cartaoMidia
    }

    private fun mostrarMidia(m: org.json.JSONObject?) {
        if (m == null) { if (!cartaoMidia.hasFocus()) cartaoMidia.visibility = View.GONE; return }
        val tocando = m.optBoolean("tocando")
        txtMidiaApp.text = "${m.optString("app")} · ${getString(if (tocando) R.string.pr_tocando else R.string.pr_pausado)}"
        val artista = m.optString("artista")
        txtMidiaTitulo.text = if (artista.isNotBlank()) "${m.optString("titulo")} — $artista" else m.optString("titulo")
        if (imgMidiaApp.tag != m.optString("pacote")) {
            imgMidiaApp.tag = m.optString("pacote")
            imgMidiaApp.setImageDrawable(try { packageManager.getApplicationIcon(m.optString("pacote")) } catch (_: Exception) { null })
        }
        val novo = if (tocando) "pausar" else "tocar"
        if (iconeTocar.tag != novo) {
            // O ícone é desenhado pelo tipo: troca a view inteira dentro do botão, mantendo a cor do foco.
            iconeTocar.tag = novo
            val pai = iconeTocar.parent as FrameLayout
            val focado = pai.isFocused
            pai.removeView(iconeTocar)
            iconeTocar = IconeCard(this, novo).also { it.tag = novo; it.definirCor(if (focado) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO) }
            pai.addView(iconeTocar, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        }
        cartaoMidia.visibility = View.VISIBLE
    }

    // ---------------------------------------------------------------- tiles

    private fun tiles(): View {
        tWifi = tile("wifi", "Wi-Fi") {
            // Ideia 54: com visita em casa e o Wi-Fi das visitas gravado, o OK mostra o QR (visita não mexe na rede da TV).
            if (ModoVisita.ativo(this) && WifiVisita.configurado(this)) WifiVisita.mostrarQr(this)
            else abrirEFechar(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        tBluetooth = tile("bluetooth", "Bluetooth") {
            // A tela de parear do sistema (a lista "Controles remotos e acessórios" não tem Activity própria).
            abrirEFechar(Intent().setClassName("com.android.tv.settings", "com.android.tv.settings.accessories.AddAccessoryActivity"))
        }
        tTimer = tile("timer", getString(R.string.pr_timer)) { trocarTimer() }
        val dormir = tile("dormir", getString(R.string.pr_dormir)) {
            val app = applicationContext
            finish()
            ui.postDelayed({ Desligamento.apagarTela(app) }, 300)
        }
        val protetor = tile("protetor", getString(R.string.pr_protetor)) { ligarProtetor() }
        tAudio = tile("audio", getString(R.string.pp_audio)) { abrirEFechar(Intent(Settings.ACTION_SOUND_SETTINGS)) }
        tDespertador = tile("despertador", getString(R.string.pr_despertador)) {
            abrirEFechar(Intent(this, AlarmesActivity::class.java))
        }
        tVisita = tile("visita", getString(R.string.pr_visita)) {
            ModoVisita.definir(this, !ModoVisita.ativo(this))
            mostrarVisita()
        }
        mostrarVisita()
        // Casa (13/11/2026): as luzes e cenas do Home Assistant (CasaActivity), no lugar do "Google Home" do painel
        // do Google (a tela da casa do Google não tem intent público; ver Home.abrirGoogleHome).
        val casa = tile("casa", getString(R.string.pr_casa)) { abrirEFechar(Intent(this, CasaActivity::class.java)) }
        // Cenas (14/11/2026, ideia 4): só com alguma cena criada (painel web > Extras > Cenas).
        val cenas = tile("cena", getString(R.string.pr_cenas)) { abrirEFechar(Intent(this, CenasActivity::class.java)) }
        // Ideia 42 (14/11/2026): filtro noturno e DNS também podem virar bloco; quais e em que ordem é escolha de
        // quem usa (Configurações > Aparência > Blocos do painel rápido, PainelBlocos). Sem escolha = o painel de antes.
        tFiltro = tile("filtro", getString(R.string.pb_filtro)) {
            ExtrasTv.alternar(this, Extras.Chave.FILTRO_NOTURNO)
            mostrarFiltro()
        }
        mostrarFiltro()
        val dns = tile("dns", getString(R.string.pb_dns)) { abrirEFechar(Intent(this, DnsActivity::class.java)) }
        dns.detalhe(Dns.rotulo(this))
        val porId = mapOf("wifi" to tWifi, "bluetooth" to tBluetooth, "timer" to tTimer, "dormir" to dormir, "protetor" to protetor,
            "audio" to tAudio, "despertador" to tDespertador, "visita" to tVisita, "casa" to casa, "cenas" to cenas,
            "filtro" to tFiltro, "dns" to dns)
        val visiveis = PainelBlocos.visiveis(PainelBlocosTv.escolhidos(this)) { id ->
            when (id) {
                "casa" -> CasaHA.configurado(this)
                "cenas" -> Cenas.lista(this).isNotEmpty()
                else -> id in porId
            }
        }
        val grade = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        for ((i, linha) in PainelBlocos.linhas(visiveis).withIndex()) {
            grade.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
                for ((j, id) in linha.withIndex()) addView(porId.getValue(id).casca.also { blocosNaTela += it },
                    LinearLayout.LayoutParams(0, dp(TILE_DP), 1f).apply { if (j > 0) marginStart = dp(Estilo.E1) })
            }, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(Estilo.E1) })
        }
        return grade
    }

    /**
     * Um tile no molde do Google: ícone solto (18 dp) à esquerda; nome (14 sp) e, só quando diz algo, o detalhe (12 sp).
     * Medido com a Manrope: sobram ~74 dp para o texto; "Bluetooth" tem 65 e "Amanhã 06:30" ~70.
     */
    private fun tile(tipoIcone: String, titulo: String, aoClicar: () -> Unit): Tile {
        val icone = IconeCard(this, tipoIcone)
        val tit = Estilo.texto(this, Estilo.Papel.CORPO, titulo).apply {
            textSize = TILE_SP; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; includeFontPadding = false
        }
        val det = Estilo.texto(this, Estilo.Papel.LEGENDA, "").apply {
            textSize = DETALHE_SP; typeface = Estilo.fonte(500); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false; visibility = View.GONE
        }
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(tit)
            addView(det, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        }
        val casca = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(12), 0, dp(6), 0)
            addView(icone, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(9) })
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { aoClicar() }
        }
        val t = Tile(casca, icone, tit, det)
        casca.setOnFocusChangeListener { v, foco ->
            t.pintar()
            // Passada na TV (12/11): o tile da última linha ganhava o foco cortado pela borda do painel. Rola até caber.
            if (foco) v.post { mostrarLinhaInteira(v) }
            v.animate().scaleX(if (foco) 1.03f else 1f).scaleY(if (foco) 1.03f else 1f).setDuration(Estilo.FOCO_MS).start()
        }
        t.pintar()
        return t
    }

    private fun mostrarRede(r: Rede.Resultado?) {
        when {
            r == null -> {}
            !r.conectado -> { tWifi.ligado = false; tWifi.titulo.text = "Wi-Fi"; tWifi.detalhe(getString(R.string.pp_conectar)) }
            else -> {
                tWifi.ligado = true
                tWifi.titulo.text = r.nome
                val sinal = getString(when (r.nivel) { 0 -> R.string.pr_sinal_bom; 1 -> R.string.pr_sinal_medio; else -> R.string.pr_sinal_fraco })
                tWifi.detalhe(if (r.mbps > 0) "${r.mbps} Mbps" else sinal)
            }
        }
    }

    /**
     * Bluetooth sem pedir permissão de Bluetooth: o que está conectado aparece como aparelho de entrada (o controle) ou
     * como saída de som (fone, caixa). Na Sala (dumpsys input, 11/11/2026) só o "Chromecast Remote" (bus 0x0005, Bluetooth)
     * é externo; os de fábrica (vad_keypad, gpio_keypad, cec_input, input_btrcu) não são. O virtual-remote também é
     * externo, por isso o filtro pelo nome. Filtrar por fabricante contava 5.
     */
    private fun mostrarBluetooth() {
        val audio = try {
            getSystemService(AudioManager::class.java)?.getDevices(AudioManager.GET_DEVICES_OUTPUTS)?.firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET || it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER
            }?.productName?.toString()
        } catch (_: Exception) { null }
        val controles = try {
            InputDevice.getDeviceIds().toList().mapNotNull { id -> InputDevice.getDevice(id) }
                .filter { it.isExternal && !it.isVirtual && !it.name.startsWith("virtual", ignoreCase = true) }
        } catch (_: Exception) { emptyList() }
        // O controle aparece com o nome de fábrica ("Chromecast Remote"), que não cabe no tile: vira "Controle".
        val nomes = listOfNotNull(audio) + controles.map {
            if (it.name.contains("remote", ignoreCase = true)) getString(R.string.pr_bt_controle) else it.name
        }
        tBluetooth.ligado = nomes.isNotEmpty()
        tBluetooth.detalhe(when {
            nomes.isEmpty() -> ""
            nomes.size == 1 -> nomes[0]
            else -> getString(R.string.pr_bt_conectados, nomes.size)
        })
    }

    /** Timer de suspensão (Desligamento.kt): cada OK passa para a próxima opção (15, 30, 60, 90, 120 min, desligado). */
    private fun trocarTimer() {
        // Arredonda para CIMA: com 15 min marcados, um instante depois faltam 14,9; arredondando para baixo dava 14 e o
        // próximo voltava a ser 15, e o OK nunca passava de 15 nem desligava (achado na Sala, 11/11).
        val faltam = Desligamento.marcado(this)?.let { ((it - System.currentTimeMillis() + 59_999) / 60_000).toInt() }
        val proximo = proximoTimer(faltam)
        if (proximo == null) Desligamento.cancelar(this) else Desligamento.agendar(this, proximo)
        mostrarTimer()
    }

    private fun mostrarTimer() {
        val em = Desligamento.marcado(this)
        tTimer.ligado = em != null
        tTimer.detalhe(if (em == null) "" else getString(R.string.pr_timer_as, DateFormat.getTimeFormat(this).format(Date(em))))
    }

    /** O próximo alarme do despertador: "Amanhã, 6:30", "Hoje, 22:00" ou "qua., 6:30". */
    private fun mostrarDespertador() {
        val p = try { Despertador.proximo(this) } catch (_: Exception) { null }
        tDespertador.ligado = p != null
        tDespertador.detalhe(p?.let { (_, em) ->
            val hora = DateFormat.getTimeFormat(this).format(Date(em))
            val dia = when {
                DateUtils.isToday(em) -> getString(R.string.pr_hoje)
                DateUtils.isToday(em - DateUtils.DAY_IN_MILLIS) -> getString(R.string.pr_amanha)
                else -> DateFormat.format("EEE", Date(em)).toString()
            }
            "$dia $hora"
        } ?: "")
    }

    /** Filtro noturno (ideia 42): ligado = a chave; o detalhe diz o horário em que ele age. */
    private fun mostrarFiltro() {
        val ligado = Extras.ligado(this, Extras.Chave.FILTRO_NOTURNO)
        tFiltro.ligado = ligado
        fun hhmm(m: Int) = "%02d:%02d".format(m / 60, m % 60)
        tFiltro.detalhe(if (!ligado) "" else hhmm(Extras.numero(this, FiltroNoturno.CHAVE_INICIO, FiltroNoturno.PADRAO_INICIO)) +
            "–" + hhmm(Extras.numero(this, FiltroNoturno.CHAVE_FIM, FiltroNoturno.PADRAO_FIM)))
    }

    private fun mostrarVisita() {
        val ativo = ModoVisita.ativo(this)
        tVisita.ligado = ativo
        tVisita.detalhe(if (ativo) getString(R.string.pr_ligado) else "")
    }

    /** A saída de som em uso: fone/caixa Bluetooth, receiver pelo HDMI ARC, ou a própria TV pelo HDMI. */
    private fun mostrarAudio() {
        val am = getSystemService(AudioManager::class.java) ?: return
        val saidas = try { am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList() } catch (_: Exception) { return }
        val bt = saidas.firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_BLE_HEADSET ||
            it.type == AudioDeviceInfo.TYPE_BLE_SPEAKER }
        // Só diz algo quando o som sai por um lugar diferente do normal (fone ou caixa Bluetooth, receiver pelo ARC).
        tAudio.ligado = bt != null
        tAudio.detalhe(when {
            bt != null -> bt.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth"
            saidas.any { it.type == AudioDeviceInfo.TYPE_HDMI_ARC || it.type == AudioDeviceInfo.TYPE_HDMI_EARC } -> getString(R.string.pr_audio_arc)
            else -> ""
        })
    }

    /**
     * Liga o protetor de tela agora (o nosso, ProtetorTela). App comum não tem API para isso; o shell próprio tem a
     * permissão WRITE_DREAM_STATE e chama IDreamManager.dream() direto (`service call dreams 1`: dream() é a 1ª função
     * da interface do Android 10 ao 14). O `cmd dreams start-dreaming` NÃO serve: exige root (medido na Sala, 11/11/2026,
     * "Must be root to call Dream shell commands") e o painel fechava sem ligar nada. Sem shell, ou se a chamada não
     * respondeu, abre a tela de protetor das Configurações.
     */
    private fun ligarProtetor() {
        val app = applicationContext
        finish()
        Fundo.rodar {
            val r = GuardiaoShizuku.comando(app, "service call dreams 1", 10_000)
            if (!chamadaRespondeu(r)) ui.post {
                try { app.startActivity(Intent(Settings.ACTION_DREAM_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {}
            }
        }
    }

    // ---------------------------------------------------------------- notificações

    private fun secaoAvisos(): View {
        txtSecao = Estilo.secao(this, getString(R.string.pr_notificacoes))
        linhaSecao = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(txtSecao, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return linhaSecao
    }

    /** "Limpar tudo": pílula no pé do painel, só com notificação para limpar. */
    private fun botaoLimpar(): View {
        limpar = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.pr_limpar_tudo)).apply {
            isFocusable = true
            isClickable = true
            typeface = Estilo.fonte(500)
            setPadding(dp(Estilo.E2 - 4), dp(7), dp(Estilo.E2 - 4), dp(7))
            background = Estilo.fundo(this@PainelRapidoActivity, Estilo.SUPERFICIE, Estilo.RAIO_PILULA)
            setOnClickListener { NotificacoesTv.atuais.forEach { OuvinteNotificacoes.dispensar(it.chave) } }
            setOnFocusChangeListener { v, foco ->
                v.background = Estilo.fundo(this@PainelRapidoActivity, if (foco) Estilo.FOCO else Estilo.SUPERFICIE, Estilo.RAIO_PILULA)
                (v as TextView).setTextColor(if (foco) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO_2)
            }
        }
        return limpar
    }

    private fun preencherAvisos() {
        val focoDentro = listaAvisos.hasFocus() || limpar.hasFocus()
        listaAvisos.removeAllViews()
        val avisos = NotificacoesTv.atuais
        txtSecao.text = if (avisos.isEmpty()) getString(R.string.pr_notificacoes).uppercase()
            else "${getString(R.string.pr_notificacoes).uppercase()} · ${avisos.size}"
        limpar.visibility = if (avisos.isEmpty()) View.GONE else View.VISIBLE
        if (avisos.isEmpty()) {
            listaAvisos.addView(Estilo.texto(this, Estilo.Papel.LEGENDA,
                getString(if (OuvinteNotificacoes.conectado) R.string.pr_sem_notificacoes else R.string.pr_notificacoes_desligadas)))
        }
        val agora = System.currentTimeMillis()
        for ((i, a) in avisos.take(12).withIndex()) {
            listaAvisos.addView(linhaAviso(a, agora), LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(Estilo.E1) })
        }
        if (focoDentro) {
            val primeiro = (listaAvisos.getChildAt(0) as? ViewGroup)?.getChildAt(0)?.takeIf { it.isFocusable }
            (primeiro ?: blocosNaTela.lastOrNull() ?: tVisita.casca).requestFocus()
        }
    }

    private fun linhaAviso(a: NotificacoesTv.Aviso, agora: Long): View {
        val quando = NotificacoesTv.haQuanto(a.quando, agora, getString(R.string.pr_ontem), getString(R.string.pr_agora))
        val icone = ImageView(this).apply {
            setImageDrawable(try { packageManager.getApplicationIcon(a.pacote) } catch (_: Exception) { null })
            clipToOutline = true
            outlineProvider = circulo()
        }
        val textos = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Estilo.texto(this@PainelRapidoActivity, Estilo.Papel.LEGENDA, "${a.app} · $quando").apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            addView(Estilo.texto(this@PainelRapidoActivity, Estilo.Papel.CORPO, a.titulo).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            if (a.texto.isNotBlank()) addView(Estilo.texto(this@PainelRapidoActivity, Estilo.Papel.LEGENDA, a.texto).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        }
        val cartao = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isFocusable = true
            isClickable = true
            setPadding(dp(Estilo.E2 - 4), dp(Estilo.E1 + 4), dp(Estilo.E2 - 4), dp(Estilo.E1 + 4))
            background = Estilo.superficie(this@PainelRapidoActivity, false)
            addView(icone, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(Estilo.E2 - 4) })
            addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
            setOnClickListener { if (OuvinteNotificacoes.abrir(a.chave)) finish() }
            setOnFocusChangeListener { v, foco ->
                Estilo.focarLinha(v, foco)
                v.animate().scaleX(if (foco) Estilo.ESCALA_LINHA else 1f).scaleY(if (foco) Estilo.ESCALA_LINHA else 1f).setDuration(Estilo.FOCO_MS).start()
            }
        }
        val dispensar = botaoRedondo(IconeCard(this, "fechar"), getString(R.string.pr_dispensar)) { OuvinteNotificacoes.dispensar(a.chave) }
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(cartao, LinearLayout.LayoutParams(0, -2, 1f))
            addView(dispensar, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(Estilo.E1) })
        }
        // O X é mais baixo que o cartão: com o foco nele, a rolagem só garantia o X à vista e o cartão ficava cortado
        // embaixo (foto do dono, 11/11). Com o foco no cartão ou no X, a rolagem mostra a linha inteira, com folga
        // para o cartão crescer no foco.
        for (v in listOf(cartao, dispensar)) {
            val anterior = v.onFocusChangeListener
            v.setOnFocusChangeListener { alvo, foco ->
                anterior?.onFocusChange(alvo, foco)
                if (foco) linha.post { mostrarLinhaInteira(linha) }
            }
        }
        return linha
    }

    /** Rola o painel até a linha caber inteira, com uma folga embaixo (o foco cresce 2% e tem sombra). */
    private fun mostrarLinhaInteira(linha: View) {
        val folga = dp(Estilo.E2)
        linha.requestRectangleOnScreen(android.graphics.Rect(0, -folga, linha.width, linha.height + folga), false)
    }

    // ---------------------------------------------------------------- utilidades

    /** Abre outra tela e fecha o painel (como o do Google: o painel não fica por baixo do que abriu). */
    private fun abrirEFechar(i: Intent) {
        try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        catch (_: Exception) { try { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Exception) {} }
        finish()
    }

    private fun circulo() = object : ViewOutlineProvider() {
        override fun getOutline(view: View, outline: Outline) = outline.setOval(0, 0, view.width, view.height)
    }

    private fun dp(v: Int) = Estilo.dp(this, v)
}
