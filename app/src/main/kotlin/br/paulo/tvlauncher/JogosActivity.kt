package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela do card Jogos. Desde 14/11/2026 no esboço A "Console", escolhido pelo dono ("fluido, funcional e principalmente
 * intuitivo", com os logos originais de cada serviço):
 *  - em cima, o jogo em foco em destaque: o banner oficial do app (o mesmo que o app mostra na Google TV; nada de logo
 *    copiado para o repositório), nome grande, para que serve, quando foi jogado, e os botões Jogar / Ligar o PC e jogar;
 *  - embaixo, UMA fila "Seus jogos" com o jogado por último primeiro: ◀ ▶ escolhe, OK joga;
 *  - descendo (▼): mais opções (rede, controle, baixar, escolher apps) e os jogos separados por tipo.
 * Segurar OK (ou Y no controle) num jogo troca o grupo dele ou tira ele do card.
 *
 * Letras grandes (idosos usam a TV). A lista de apps, o uso e os banners são lidos fora da thread da tela.
 */
class JogosActivity : Activity() {
    private lateinit var coluna: LinearLayout
    private lateinit var chipControle: TextView
    private lateinit var chipRede: TextView
    private lateinit var brilho: View
    private lateinit var capa: FrameLayout
    private lateinit var capaImagem: ImageView
    private lateinit var heroGrupo: TextView
    private lateinit var heroNome: TextView
    private lateinit var heroTexto: TextView
    private lateinit var botaoJogar: TextView
    private lateinit var botaoPc: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var rede: JogosTv.Medida? = null
    private var jogos: List<JogosTv.App> = emptyList()   // já na ordem "jogado por último primeiro"
    private var uso: Map<String, Long> = emptyMap()
    private var atual: JogosTv.App? = null               // o jogo do destaque
    private var esperandoPc = false
    private var montado = false   // o 1º montar põe o foco no 1º jogo, nunca no Jogar que o Android focou sozinho
    private var focoPacote: String? = null   // ao redesenhar (mudou de grupo, voltou de um jogo), o foco volta no mesmo jogo

    /** O que a tela desenha de cada app: o banner oficial (logo) ou, sem banner, o ícone sobre a cor dele. */
    private class Visual(val banner: Bitmap?, val icone: Drawable?, val cores: IntArray)
    private val visuais = HashMap<String, Visual>()   // só na thread da tela

    private val ouvinteControles = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = atualizarStatus()
        override fun onInputDeviceRemoved(id: Int) = atualizarStatus()
        override fun onInputDeviceChanged(id: Int) = atualizarStatus()
    }

    private fun dp(v: Int) = Estilo.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro (VigiaAtualizacao)
        val raiz = FrameLayout(this).apply { background = Estilo.fundoTela(this@JogosActivity) }
        // Luz da cor do jogo em foco, atrás do banner: a tela "veste" o jogo (troca suave a cada foco).
        brilho = View(this)
        raiz.addView(brilho, FrameLayout.LayoutParams(-1, -1))

        val conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), dp(14))
        }
        conteudo.addView(topo())
        conteudo.addView(destaque(), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
        coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(Estilo.E2), 0, dp(Estilo.E3 * 3))
        }
        conteudo.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            isSmoothScrollingEnabled = true
            // Sala, 17/11/2026: rolando para "Mais opções", os cards de cima ficavam cortados a seco embaixo do destaque.
            // A borda esmaece em vez de cortar.
            isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(48))
            addView(coluna)
            // Corta embaixo: a fileira de baixo não passa por cima da barra de dicas (achado do teste de tela, ideia 96).
            // Corta em cima também (16/11/2026): com folga de 40 dp em cima, as fileiras roladas apareciam numa faixa por
            // cima do destaque. O card com foco cresce 8% (~5 dp) e cabe no padding E2 de `coluna`. Dos lados fica a folga.
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.clipBounds = android.graphics.Rect(-dp(40), 0, v.width + dp(40), v.height) }
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        conteudo.addView(Estilo.barraDicas(this,
            "◀ ▶" to getString(R.string.jogos_tecla_escolher),
            "OK" to getString(R.string.jogos_tecla_jogar),
            "▼" to getString(R.string.jogos_tecla_mais),
            getString(R.string.jogos_tecla_segurar) to getString(R.string.jogos_tecla_opcoes)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E1) })
        raiz.addView(conteudo, FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(ouvinteControles, ui)
        testarRede()
    }

    override fun onResume() {
        super.onResume()
        carregar()   // ao voltar de um jogo ou da Play Store: a ordem mudou ou um app foi instalado
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(ouvinteControles)
        esperandoPc = false
        super.onDestroy()
    }

    private fun carregar() {
        val ja = visuais.keys.toSet()
        Fundo.rodar("jogos") {
            val ctx = applicationContext
            val lista = JogosTv.listar(ctx)
            val u = Uso.ultimoUso(ctx)
            val porPacote = lista.associateBy { it.pacote }
            val ordenada = Jogos.ordenarPorUso(lista.map { it.pacote }, u).mapNotNull { porPacote[it] }
            // Banners e ícones só dos que ainda não foram lidos nesta tela (voltar de um jogo não relê nada).
            val novos = HashMap<String, Visual>()
            for (j in lista) if (j.pacote !in ja) novos[j.pacote] = lerVisual(j.pacote)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                visuais.putAll(novos); uso = u; jogos = ordenada
                if (focoPacote == null) focoPacote = atual?.pacote
                montar()
            }
        }
    }

    private fun lerVisual(pacote: String): Visual {
        val banner = CapaFoco.banner(packageManager, pacote)?.let { runCatching { CapaFoco.bitmapReduzido(it) }.getOrNull() }
        val icone = runCatching { packageManager.getApplicationIcon(pacote) }.getOrNull()
        val cores = icone?.let { runCatching { CapaFoco.corDoIcone(it) }.getOrNull() } ?: intArrayOf(0xFFF5A524.toInt(), 0xFF7A4A0E.toInt())
        return Visual(banner, icone, cores)
    }

    // ---------------------------------------------------------------- topo: título + controle + rede

    private fun topo(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(ImageView(this@JogosActivity).apply { setImageDrawable(IconeApp(IconeApp.Tipo.JOGOS, Estilo.TEXTO)) },
            LinearLayout.LayoutParams(dp(30), dp(30)).apply { marginEnd = dp(10) })
        addView(Estilo.tituloTela(this@JogosActivity, getString(R.string.jogos_titulo)).apply {
            textSize = 26f; setPadding(0, 0, 0, 0)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        chipControle = chip(); chipRede = chip()
        addView(chipControle)
        addView(chipRede, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E1 + 2) })
    }

    private fun chip(): TextView = Estilo.texto(this, Estilo.Papel.CORPO).apply {
        textSize = 15f
        background = Estilo.vidro(this@JogosActivity, Estilo.RAIO_PILULA)
        setPadding(dp(14), dp(7), dp(14), dp(7))
    }

    private fun atualizarStatus() {
        val c = JogosTv.controlesComBateria()
        chipControle.text = if (c.isEmpty()) "🎮  " + getString(R.string.jogos_chip_sem_controle)
            else "🎮  " + c.joinToString(", ") { k -> k.nome + (k.bateria?.let { "  $it%" } ?: "") }
        chipControle.setTextColor(if (c.isEmpty()) Estilo.TEXTO_2 else Estilo.TEXTO)
        val m = rede
        chipRede.text = if (m == null) getString(R.string.jogos_chip_medindo) else getString(when (m.rede) {
            Jogos.Rede.BOA -> R.string.jogos_chip_boa
            Jogos.Rede.OK -> R.string.jogos_chip_ok
            Jogos.Rede.FRACA -> R.string.jogos_chip_fraca
            Jogos.Rede.SEM -> R.string.jogos_chip_sem
        }) + (m.ms?.let { " · $it ms" } ?: "")
        chipRede.setTextColor(when (m?.rede) {
            Jogos.Rede.BOA -> 0xFF7EE2A8.toInt()
            Jogos.Rede.OK -> 0xFFFFD27A.toInt()
            Jogos.Rede.FRACA, Jogos.Rede.SEM -> 0xFFFF8A80.toInt()
            null -> Estilo.TEXTO_2
        })
    }

    private fun textoRede(m: JogosTv.Medida): String = getString(when (m.rede) {
        Jogos.Rede.BOA -> R.string.jogos_rede_boa
        Jogos.Rede.OK -> R.string.jogos_rede_ok
        Jogos.Rede.FRACA -> R.string.jogos_rede_fraca
        Jogos.Rede.SEM -> R.string.jogos_rede_sem
    }) + (m.ms?.let { " ($it ms)" } ?: "")

    private fun testarRede(avisar: Boolean = false) {
        rede = null
        atualizarStatus()
        Fundo.rodar("jogos-rede") {
            val m = JogosTv.medirRede(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rede = m; atualizarStatus()
                if (avisar) AvisoTv.rapido(this, getString(R.string.jogos_testar_rede), textoRede(m))
            }
        }
    }

    // ---------------------------------------------------------------- destaque: o jogo em foco

    private fun destaque(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        clipChildren = false
        val texto = LinearLayout(this@JogosActivity).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            gravity = Gravity.CENTER_VERTICAL
        }
        heroGrupo = Estilo.secao(this@JogosActivity, "").apply { textSize = 14f; setTextColor(0xFF8FC1FF.toInt()) }
        heroNome = Estilo.texto(this@JogosActivity, Estilo.Papel.TITULO).apply {
            textSize = 40f; typeface = Estilo.fonte(800)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        heroTexto = Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO).apply {
            textSize = 17f; setTextColor(Estilo.TEXTO_2); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.2f)
        }
        texto.addView(heroGrupo)
        texto.addView(heroNome, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
        texto.addView(heroTexto, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
        val botoes = LinearLayout(this@JogosActivity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        botaoJogar = Estilo.botao(this@JogosActivity, "▶  " + getString(R.string.jogos_jogar)).apply {
            textSize = 19f; id = View.generateViewId()
            setPadding(dp(24), dp(11), dp(24), dp(11))
            setOnClickListener { atual?.let { abrirJogo(it) } }
        }
        botaoPc = Estilo.botao(this@JogosActivity, "⏻  " + getString(R.string.jogos_ligar_pc_jogar), primario = false).apply {
            textSize = 19f; id = View.generateViewId()
            setPadding(dp(24), dp(11), dp(24), dp(11))
            setOnClickListener { jogarNoPc(atual?.pacote) }
        }
        botoes.addView(botaoJogar)
        botoes.addView(botaoPc, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
        texto.addView(botoes, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E2) })
        // Altura mínima do banner, mas cresce com o texto: com "Tamanho do texto" Grande/Maior o botão Jogar saía cortado
        // (visto na Sala, 15/11/2026).
        texto.minimumHeight = dp(198)
        addView(texto, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(Estilo.E3 * 2) })

        // O banner oficial do app, grande, à direita (16:9, como a Google TV mostra).
        capa = FrameLayout(this@JogosActivity).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(Estilo.RAIO + 2).toFloat())
            }
            elevation = dp(10).toFloat()
        }
        capaImagem = ImageView(this@JogosActivity)
        capa.addView(capaImagem, FrameLayout.LayoutParams(-1, -1))
        addView(capa, LinearLayout.LayoutParams(dp(352), dp(198)).apply { gravity = Gravity.TOP })
    }

    /** Troca o destaque para o jogo [j] (foco num card). Rápido: tudo já está lido; só o banner faz um esmaecer curto. */
    private fun mostrarDestaque(j: JogosTv.App?) {
        if (j == null) {
            atual = null
            heroGrupo.text = getString(R.string.jogos_para_comecar)
            heroNome.text = getString(R.string.jogos_nenhum_titulo)
            heroTexto.text = getString(R.string.jogos_vazio_dica)
            botaoJogar.visibility = View.GONE; botaoPc.visibility = View.GONE
            pintarCapa(null, null, intArrayOf(0xFFF5A524.toInt(), 0xFF7A4A0E.toInt()))
            return
        }
        val mudou = atual?.pacote != j.pacote
        atual = j
        heroGrupo.text = nomeGrupo(j.grupo)
        heroNome.text = j.nome
        val quando = uso[j.pacote]?.takeIf { it > 0 }?.let {
            " " + getString(R.string.jogos_jogado, DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString().lowercase())
        } ?: ""
        heroTexto.text = descGrupo(j.grupo) + quando
        botaoJogar.visibility = View.VISIBLE
        botaoPc.visibility = if (j.grupo == Jogos.Grupo.PC) View.VISIBLE else View.GONE
        if (mudou) {
            val v = visuais[j.pacote]
            pintarCapa(v?.banner, v?.icone, v?.cores)
        }
    }

    private fun pintarCapa(banner: Bitmap?, icone: Drawable?, cores: IntArray?) {
        val c = cores ?: intArrayOf(0xFF2B2F3A.toInt(), Estilo.FUNDO)
        if (banner != null) {
            capaImagem.scaleType = ImageView.ScaleType.CENTER_CROP
            capaImagem.setPadding(0, 0, 0, 0)
            capaImagem.setImageBitmap(banner)
            capa.background = null
        } else {
            // Sem banner: o ícone do app grande no meio da cor dele (ou o controle, sem jogo nenhum).
            capaImagem.scaleType = ImageView.ScaleType.FIT_CENTER
            capaImagem.setPadding(dp(110), dp(52), dp(110), dp(52))
            capaImagem.setImageDrawable(icone ?: IconeApp(IconeApp.Tipo.JOGOS, Estilo.TEXTO))
            capa.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, c)
        }
        capaImagem.alpha = 0.35f
        capaImagem.animate().alpha(1f).setDuration(180).start()
        brilho.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = dp(520).toFloat()
            setGradientCenter(0.78f, 0.22f)
            colors = intArrayOf(Estilo.alfa(c[0], 0x66), Estilo.alfa(c[0], 0x1A), 0x00000000)
        }
    }

    private fun descGrupo(g: Jogos.Grupo) = getString(when (g) {
        Jogos.Grupo.NUVEM -> R.string.jogos_desc_nuvem
        Jogos.Grupo.PC -> R.string.jogos_desc_pc
        Jogos.Grupo.RETRO -> R.string.jogos_desc_retro
        Jogos.Grupo.TV -> R.string.jogos_desc_tv
    })

    // ---------------------------------------------------------------- corpo

    private fun montar() {
        val focoBotao = (currentFocus?.tag as? String)?.takeIf { it.startsWith("botao:") }
        val heroComFoco = montado && (currentFocus === botaoJogar || currentFocus === botaoPc)
        coluna.removeAllViews()
        var alvo: View? = null
        var primeiro: View? = null

        // 1. Seus jogos: uma fila só, o jogado por último primeiro. É onde o foco começa.
        coluna.addView(Estilo.secao(this, getString(if (jogos.isEmpty()) R.string.jogos_para_comecar else R.string.jogos_seus)).apply { textSize = 14f })
        val fila = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        if (jogos.isEmpty()) {
            for (s in Jogos.SUGESTOES) fila.addView(cardSugestao(s).also { if (primeiro == null) primeiro = it }, lpCard())
        } else for (j in jogos) {
            val card = cardJogo(j)
            fila.addView(card, lpCard())
            if (primeiro == null) primeiro = card
            if (j.pacote == focoPacote) alvo = card
        }
        fila.addView(cardMaisJogos(), lpCard())
        coluna.addView(rolagem(fila))

        // 2. Mais opções (▼).
        coluna.addView(Estilo.secao(this, getString(R.string.jogos_mais_opcoes)).apply { textSize = 14f },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E1) })
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        fun botao(id: String, rotulo: String, ao: () -> Unit) = Estilo.botao(this, rotulo, primario = false).apply {
            textSize = 17f; tag = "botao:$id"
            setPadding(dp(22), dp(11), dp(22), dp(11))
            setOnClickListener { ao() }
            botoes.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(Estilo.E2) })
        }
        botao("rede", getString(R.string.jogos_testar_rede)) { testarRede(avisar = true) }
        botao("parear", getString(R.string.jogos_parear)) {
            if (!JogosTv.abrirPareamento(this)) AvisoTv.rapido(this, getString(R.string.jogos_parear), getString(R.string.jogos_parear_falhou))
        }
        botao("pc", getString(R.string.jogos_jogar_pc)) { jogarNoPc(null) }
        botao("baixar", getString(R.string.jogos_baixar)) { mostrarLoja() }
        botao("escolher", getString(R.string.jogos_escolher)) {
            startActivity(Intent(this, AppsEscolhaActivity::class.java).putExtra(AppsEscolhaActivity.EXTRA_MODO, AppsEscolhaActivity.MODO_JOGOS))
        }
        coluna.addView(rolagem(botoes))

        // 3. Por tipo: os mesmos jogos separados em Na nuvem, Do PC e do console, Retrô e Jogos da TV (grupo vazio some).
        val grupos = jogos.groupBy { it.grupo }
        if (grupos.size > 1) {
            coluna.addView(Estilo.secao(this, getString(R.string.jogos_por_tipo)).apply { textSize = 14f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E1) })
            for (g in Jogos.Grupo.values()) {
                val doGrupo = grupos[g] ?: continue
                coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, nomeGrupo(g)).apply { textSize = 15f },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E1); marginStart = dp(6) })
                val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
                for (j in doGrupo) linha.addView(cardJogo(j), lpCard())
                coluna.addView(rolagem(linha))
            }
        }

        // O destaque: o jogo que estava em foco, senão o jogado por último.
        val noDestaque = jogos.firstOrNull { it.pacote == (focoPacote ?: atual?.pacote) } ?: jogos.firstOrNull()
        atual = null   // força repintar (banner novo depois de reinstalar, por exemplo)
        mostrarDestaque(noDestaque)
        val voltar = focoBotao?.let { t -> (0 until botoes.childCount).map { botoes.getChildAt(it) }.firstOrNull { it.tag == t } }
        when {
            heroComFoco && botaoJogar.visibility == View.VISIBLE -> botaoJogar.requestFocus()
            else -> (alvo ?: voltar ?: primeiro)?.requestFocus()
        }
        focoPacote = null
        montado = true
        atualizarStatus()
    }

    private fun lpCard() = LinearLayout.LayoutParams(dp(208), dp(117)).apply { marginEnd = dp(Estilo.E2 + 2) }

    private fun rolagem(v: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
        setPadding(dp(6), dp(10), dp(6), dp(12))
        addView(v)
    }

    private fun nomeGrupo(g: Jogos.Grupo) = getString(when (g) {
        Jogos.Grupo.NUVEM -> R.string.jogos_grupo_nuvem
        Jogos.Grupo.PC -> R.string.jogos_grupo_pc
        Jogos.Grupo.RETRO -> R.string.jogos_grupo_retro
        Jogos.Grupo.TV -> R.string.jogos_grupo_tv
    })

    /**
     * Card 16:9 de um jogo: o banner oficial do app, que já traz o logo (sem selo de nome por cima, para o logo ficar
     * limpo). Sem banner: o ícone do app sobre a cor dele, com o nome embaixo. Foco = destaque em cima troca para ele.
     */
    private fun cardJogo(j: JogosTv.App): View = FrameLayout(this).apply {
        tag = j.pacote
        isFocusable = true; isClickable = true
        id = View.generateViewId()
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(Estilo.RAIO).toFloat())
        }
        contentDescription = j.nome
        val v = visuais[j.pacote]
        if (v?.banner != null) {
            addView(ImageView(this@JogosActivity).apply { setImageBitmap(v.banner); scaleType = ImageView.ScaleType.CENTER_CROP },
                FrameLayout.LayoutParams(-1, -1))
        } else {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, v?.cores ?: intArrayOf(0xFF2B2F3A.toInt(), Estilo.FUNDO))
            if (v?.icone != null) addView(ImageView(this@JogosActivity).apply { setImageDrawable(v.icone) },
                FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(14), 0) })
            addView(nomeNoCard(j.nome), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, dp(12), dp(10)) })
        }
        Estilo.focoCard(this)
        val base = onFocusChangeListener
        setOnFocusChangeListener { vw, foco ->
            base?.onFocusChange(vw, foco)
            if (foco) {
                mostrarDestaque(j)
                // ▲ volta ao Jogar; ▼ no Jogar volta para ESTE card.
                botaoJogar.nextFocusDownId = vw.id; botaoPc.nextFocusDownId = vw.id
            }
        }
        setOnClickListener { abrirJogo(j) }
        setOnLongClickListener { escolherGrupo(j); true }
    }

    private fun nomeNoCard(nome: String) = TextView(this).apply {
        text = nome
        setTextColor(Estilo.TEXTO)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Estilo.fonte(700)
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0xCC000000.toInt())
    }

    /** Último da fila: "+ Mais jogos" (a lista de sugestões da Play Store). */
    private fun cardMaisJogos(): View = FrameLayout(this).apply {
        isFocusable = true; isClickable = true
        tag = "botao:mais"
        background = GradientDrawable().apply {
            cornerRadius = dp(Estilo.RAIO).toFloat()
            setStroke(dp(2), 0x55FFFFFF, dp(8).toFloat(), dp(6).toFloat())
        }
        addView(Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO, "+\n" + getString(R.string.jogos_mais_jogos)).apply {
            textSize = 17f; gravity = Gravity.CENTER; typeface = Estilo.fonte(700); setTextColor(Estilo.TEXTO_2)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        Estilo.focoCard(this)
        setOnClickListener { mostrarLoja() }
    }

    private fun cardSugestao(s: Jogos.Sugestao): View = FrameLayout(this).apply {
        isFocusable = true; isClickable = true
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(Estilo.RAIO).toFloat())
        }
        // O âmbar do card Jogos da HOME.
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Estilo.matiz(0xFFF5A524.toInt(), 0.5f), Estilo.matiz(0xFF7A4A0E.toInt(), 0.4f)))
        contentDescription = s.nome
        addView(nomeNoCard(s.nome), FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), 0, dp(12), dp(10)) })
        Estilo.focoCard(this)
        setOnClickListener { abrirNaLoja(s.pacote) }
    }

    // ---------------------------------------------------------------- ações

    private fun abrir(pacote: String): Boolean {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pacote) ?: packageManager.getLaunchIntentForPackage(pacote) ?: return false
        return try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    /** Jogo na nuvem com a rede fraca: avisa antes (ideia 4 da especificação). Os outros abrem direto. */
    private fun abrirJogo(j: JogosTv.App) {
        val r = rede?.rede
        if (j.grupo == Jogos.Grupo.NUVEM && (r == Jogos.Rede.FRACA || r == Jogos.Rede.SEM)) {
            CaixaTv(this).setTitle(if (r == Jogos.Rede.SEM) R.string.jogos_rede_sem else R.string.jogos_aviso_fraca_titulo)
                .setMessage(R.string.jogos_aviso_fraca)
                .setPositiveButton(R.string.jogos_jogar_assim) { _, _ -> abrirOuAvisar(j.pacote) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        abrirOuAvisar(j.pacote)
    }

    private fun abrirOuAvisar(pacote: String) {
        if (!abrir(pacote)) AvisoTv.rapido(this, getString(R.string.jogos_titulo), getString(R.string.jogos_nao_abriu))
    }

    /** Segurar OK num jogo: em que grupo ele fica, tirar do card ou voltar ao automático. */
    private fun escolherGrupo(j: JogosTv.App) {
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var janela: CaixaTv.Janela? = null
        fun opcao(rotulo: String, valor: String?) {
            corpo.addView(Estilo.linhaTexto(this, rotulo).apply {
                setOnClickListener {
                    JogosTv.escolher(this@JogosActivity, j.pacote, valor)
                    focoPacote = j.pacote
                    janela?.dismiss()
                    carregar()
                }
            }, Estilo.lpLinha(this))
        }
        for (g in Jogos.Grupo.values()) opcao((if (g == j.grupo) "✓ " else "") + nomeGrupo(g), g.name)
        opcao(getString(R.string.jogos_tirar), Jogos.FORA)
        opcao(getString(R.string.jogos_automatico), null)
        janela = CaixaTv(this).setTitle(j.nome).setView(corpo).setNegativeButton(android.R.string.cancel, null).show()
        corpo.getChildAt(0)?.requestFocus()
    }

    /** Sugestões que ainda não estão na TV. A Play Store decide se roda neste aparelho (dono: "problema dele"). */
    private fun mostrarLoja() {
        val faltam = Jogos.SUGESTOES.filter { !JogosTv.instalado(this, it.pacote) }
        if (faltam.isEmpty()) { AvisoTv.rapido(this, getString(R.string.jogos_baixar), getString(R.string.jogos_loja_tudo)); return }
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var janela: CaixaTv.Janela? = null
        for (s in faltam) corpo.addView(Estilo.linhaTexto(this, s.nome + "  ·  " + nomeGrupo(s.grupo)).apply {
            setOnClickListener { janela?.dismiss(); abrirNaLoja(s.pacote) }
        }, Estilo.lpLinha(this))
        janela = CaixaTv(this).setTitle(R.string.jogos_baixar).setMessage(R.string.jogos_loja_explica).setView(corpo)
            .setNegativeButton(android.R.string.cancel, null).show()
        corpo.getChildAt(0)?.requestFocus()
    }

    private fun abrirNaLoja(pacote: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pacote")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) {
            AvisoTv.rapido(this, getString(R.string.jogos_baixar), getString(R.string.jogos_sem_loja))
        }
    }

    // ---------------------------------------------------------------- jogar no PC (ideia 3)

    /** [pacote] = o app de PC em destaque ("Ligar o PC e jogar"); null = o botão geral, que escolhe o app. */
    private fun jogarNoPc(pacote: String?) {
        if (AcordarPc.macGuardado(this).isEmpty()) {
            CaixaTv(this).setTitle(R.string.jogos_jogar_pc).setMessage(R.string.jogos_pc_sem_mac)
                .setPositiveButton(android.R.string.ok, null).show(); return
        }
        if (pacote != null) { acordarEAbrir(pacote); return }
        val instalados = Jogos.APPS_PC.filter { JogosTv.instalado(this, it) }.toSet()
        val app = Jogos.appDoPc(instalados, JogosTv.appPcEscolhido(this))
        if (app == null) {
            CaixaTv(this).setTitle(R.string.jogos_jogar_pc).setMessage(R.string.jogos_pc_sem_app)
                .setPositiveButton(R.string.jogos_baixar) { _, _ -> mostrarLoja() }
                .setNegativeButton(android.R.string.cancel, null).show(); return
        }
        if (instalados.size > 1 && JogosTv.appPcEscolhido(this) == null) { escolherAppPc(instalados.toList()); return }
        acordarEAbrir(app)
    }

    /** Mais de um app de PC instalado: pergunta uma vez qual usar (a escolha fica guardada). */
    private fun escolherAppPc(apps: List<String>) {
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var janela: CaixaTv.Janela? = null
        for (p in apps) {
            val nome = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
            corpo.addView(Estilo.linhaTexto(this, nome).apply {
                setOnClickListener { JogosTv.escolherAppPc(this@JogosActivity, p); janela?.dismiss(); acordarEAbrir(p) }
            }, Estilo.lpLinha(this))
        }
        janela = CaixaTv(this).setTitle(R.string.jogos_pc_qual_app).setView(corpo).setNegativeButton(android.R.string.cancel, null).show()
        corpo.getChildAt(0)?.requestFocus()
    }

    private fun acordarEAbrir(app: String) {
        if (esperandoPc) return
        esperandoPc = true
        val texto = Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.jogos_pc_acordando, 0)).apply { textSize = 19f }
        val janela = CaixaTv(this).setTitle(R.string.jogos_jogar_pc).setView(texto)
            .setNegativeButton(android.R.string.cancel) { _, _ -> esperandoPc = false }
            .setOnCancelListener { esperandoPc = false }.show()
        val app0 = applicationContext
        Fundo.rodar("jogos-pc") {
            val ok = JogosTv.acordarEEsperar(app0, { s -> runOnUiThread { texto.text = getString(R.string.jogos_pc_acordando, s) } }, { !esperandoPc })
            runOnUiThread {
                if (isDestroyed || !esperandoPc) return@runOnUiThread
                esperandoPc = false
                runCatching { janela.dismiss() }
                if (!ok) AvisoTv.rapido(this, getString(R.string.jogos_jogar_pc), getString(R.string.jogos_pc_nao_respondeu))
                abrirOuAvisar(app)   // abre mesmo sem resposta: o app de PC mostra o próprio erro e deixa tentar de novo
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Botão Y/△ do controle de videogame em cima de um jogo = o mesmo que segurar OK (escolher o grupo).
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_Y && event.action == KeyEvent.ACTION_UP) {
            val p = currentFocus?.tag as? String
            jogos.firstOrNull { it.pacote == p }?.let { escolherGrupo(it); return true }
        }
        return super.dispatchKeyEvent(event)
    }
}
