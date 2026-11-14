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
 * Tela do card Jogos (14/11/2026, esboço aprovado pelo dono): em cima, os controles ligados e a rede; 4 botões grandes
 * (Jogar no PC, Testar a rede, Parear controle, Baixar jogos); depois os grupos Na nuvem, Do PC e do console, Retrô e
 * Jogos da TV (grupo vazio não aparece). Segurar OK num jogo troca o grupo dele ou tira ele do card.
 *
 * Letras grandes (idosos usam a TV): nomes dos jogos em 17 sp, botões em 19 sp. A lista de apps e a rede são lidas
 * fora da thread da tela.
 */
class JogosActivity : Activity() {
    private lateinit var coluna: LinearLayout
    private lateinit var status: TextView
    private val ui = Handler(Looper.getMainLooper())
    private var rede: JogosTv.Medida? = null
    private var jogos: List<JogosTv.App> = emptyList()
    private var esperandoPc = false
    private var focoPacote: String? = null   // ao redesenhar (mudou de grupo), o foco volta no mesmo jogo

    private val ouvinteControles = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = atualizarStatus()
        override fun onInputDeviceRemoved(id: Int) = atualizarStatus()
        override fun onInputDeviceChanged(id: Int) {}
    }

    private fun dp(v: Int) = Estilo.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro (VigiaAtualizacao)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(this@JogosActivity)
            clipChildren = false; clipToPadding = false
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL), dp(Estilo.MARGEM_LATERAL), 0)
        }
        raiz.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(this@JogosActivity).apply { setImageDrawable(IconeApp(IconeApp.Tipo.JOGOS, Estilo.TEXTO)) },
                LinearLayout.LayoutParams(dp(36), dp(36)).apply { marginEnd = dp(12) })
            addView(Estilo.tituloTela(this@JogosActivity, getString(R.string.jogos_titulo)).apply { textSize = 32f })
        })
        status = Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.jogos_medindo)).apply { textSize = 18f; setTextColor(Estilo.TEXTO_2) }
        raiz.addView(status, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        coluna = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(0, dp(Estilo.E2), 0, dp(Estilo.MARGEM_VERTICAL + 16))
        }
        raiz.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(coluna)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(raiz)
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(ouvinteControles, ui)
        testarRede()
    }

    override fun onResume() {
        super.onResume()
        carregar()   // ao voltar de um jogo ou da Play Store: um app pode ter sido instalado
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(ouvinteControles)
        esperandoPc = false
        super.onDestroy()
    }

    private fun carregar() {
        Fundo.rodar("jogos") {
            val lista = JogosTv.listar(applicationContext)
            runOnUiThread { if (!isDestroyed) { jogos = lista; montar() } }
        }
    }

    // ---------------------------------------------------------------- topo

    private fun atualizarStatus() {
        val partes = mutableListOf<String>()
        val c = JogosTv.controles()
        partes += if (c.isEmpty()) getString(R.string.jogos_sem_controle)
            else resources.getQuantityString(R.plurals.jogos_controles, c.size, c.size) + " (" + c.joinToString(", ") + ")"
        partes += rede?.let { textoRede(it) } ?: getString(R.string.jogos_medindo)
        status.text = partes.joinToString("  ·  ")
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

    // ---------------------------------------------------------------- corpo

    private fun montar() {
        val focado = currentFocus
        val focoBotao = (focado?.tag as? String)?.takeIf { it.startsWith("botao:") }
        coluna.removeAllViews()
        val botoes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        fun botao(id: String, rotulo: String, ao: () -> Unit) = Estilo.botao(this, rotulo).apply {
            textSize = 19f; tag = "botao:$id"
            setPadding(dp(26), dp(14), dp(26), dp(14))
            setOnClickListener { ao() }
            botoes.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(Estilo.E2) })
        }
        botao("pc", getString(R.string.jogos_jogar_pc)) { jogarNoPc() }
        botao("rede", getString(R.string.jogos_testar_rede)) { testarRede(avisar = true) }
        botao("parear", getString(R.string.jogos_parear)) {
            if (!JogosTv.abrirPareamento(this)) AvisoTv.rapido(this, getString(R.string.jogos_parear), getString(R.string.jogos_parear_falhou))
        }
        botao("baixar", getString(R.string.jogos_baixar)) { mostrarLoja() }
        botao("escolher", getString(R.string.jogos_escolher)) {
            startActivity(Intent(this, AppsEscolhaActivity::class.java).putExtra(AppsEscolhaActivity.EXTRA_MODO, AppsEscolhaActivity.MODO_JOGOS))
        }
        coluna.addView(rolagem(botoes))

        val grupos = jogos.groupBy { it.grupo }   // JogosTv.listar já filtrou e deu o grupo de cada um
        var primeiroJogo: View? = null
        var alvo: View? = null
        for (g in Jogos.Grupo.values()) {
            val doGrupo = grupos[g] ?: continue
            coluna.addView(Estilo.secao(this, nomeGrupo(g)).apply { textSize = 14f })
            val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for (j in doGrupo) {
                val card = cardJogo(j)
                linha.addView(card, LinearLayout.LayoutParams(dp(190), dp(107)).apply { marginEnd = dp(Estilo.E2 + 4) })
                if (primeiroJogo == null) primeiroJogo = card
                if (j.pacote == focoPacote) alvo = card
            }
            coluna.addView(rolagem(linha))
        }
        if (jogos.isEmpty()) {
            // Card vazio (dono: "sempre" na HOME): o que dá para baixar, em cards grandes.
            coluna.addView(Estilo.secao(this, getString(R.string.jogos_para_comecar)).apply { textSize = 14f })
            val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            for (s in Jogos.SUGESTOES) linha.addView(cardSugestao(s), LinearLayout.LayoutParams(dp(190), dp(107)).apply { marginEnd = dp(Estilo.E2 + 4) })
            coluna.addView(rolagem(linha))
            coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.jogos_vazio_dica)).apply { textSize = 15f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E1) })
        } else {
            coluna.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.jogos_dica_segurar)).apply { textSize = 15f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E2) })
        }
        val voltar = focoBotao?.let { t -> (0 until botoes.childCount).map { botoes.getChildAt(it) }.firstOrNull { it.tag == t } }
        (alvo ?: voltar ?: primeiroJogo ?: botoes.getChildAt(0))?.requestFocus()
        focoPacote = null
        atualizarStatus()
    }

    private fun rolagem(v: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
        setPadding(dp(6), dp(10), dp(6), dp(10))
        addView(v)
    }

    private fun nomeGrupo(g: Jogos.Grupo) = getString(when (g) {
        Jogos.Grupo.NUVEM -> R.string.jogos_grupo_nuvem
        Jogos.Grupo.PC -> R.string.jogos_grupo_pc
        Jogos.Grupo.RETRO -> R.string.jogos_grupo_retro
        Jogos.Grupo.TV -> R.string.jogos_grupo_tv
    })

    /** Card 16:9 de um jogo: o banner do app (como na linha de apps da HOME) ou a cor do ícone, com o nome embaixo. */
    private fun cardJogo(j: JogosTv.App): View = moldura(j.nome).apply {
        tag = j.pacote
        Fundo.rodar("jogos-capa") {
            val banner: Bitmap? = CapaFoco.banner(packageManager, j.pacote)?.let { runCatching { CapaFoco.bitmapReduzido(it) }.getOrNull() }
            val icone: Drawable? = runCatching { packageManager.getApplicationIcon(j.pacote) }.getOrNull()
            val cores = icone?.let { runCatching { CapaFoco.corDoIcone(it) }.getOrNull() }
            runOnUiThread { if (!isDestroyed) pintar(this, banner, icone, cores) }
        }
        setOnClickListener { abrirJogo(j) }
        setOnLongClickListener { escolherGrupo(j); true }
    }

    private fun cardSugestao(s: Jogos.Sugestao): View = moldura(s.nome).apply {
        // O âmbar do card Jogos da HOME.
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Estilo.matiz(0xFFF5A524.toInt(), 0.5f), Estilo.matiz(0xFF7A4A0E.toInt(), 0.4f)))
        setOnClickListener { abrirNaLoja(s.pacote) }
    }

    /** Card vazio com o nome embaixo (selo escuro para ler em cima de qualquer banner) e o foco padrão. */
    private fun moldura(nome: String): FrameLayout = FrameLayout(this).apply {
        isFocusable = true; isClickable = true
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(Estilo.RAIO).toFloat())
        }
        background = Estilo.fundo(this@JogosActivity, Estilo.SUPERFICIE_ALTA)
        contentDescription = nome
        addView(TextView(this@JogosActivity).apply {
            text = nome
            setTextColor(Estilo.TEXTO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            typeface = Estilo.fonte(600)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(0xBF0B0F17.toInt()) }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(10), dp(10), dp(10), dp(10)) })
        Estilo.focoCard(this)
    }

    private fun pintar(card: FrameLayout, banner: Bitmap?, icone: Drawable?, cores: IntArray?) {
        if (banner != null) {
            card.addView(ImageView(this).apply { setImageBitmap(banner); scaleType = ImageView.ScaleType.CENTER_CROP }, 0,
                FrameLayout.LayoutParams(-1, -1))
            return
        }
        if (cores != null) card.background = GradientDrawable(GradientDrawable.Orientation.TL_BR, cores)
        if (icone != null) card.addView(ImageView(this).apply { setImageDrawable(icone) }, 0,
            FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(14), 0) })
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

    private fun jogarNoPc() {
        if (AcordarPc.macGuardado(this).isEmpty()) {
            CaixaTv(this).setTitle(R.string.jogos_jogar_pc).setMessage(R.string.jogos_pc_sem_mac)
                .setPositiveButton(android.R.string.ok, null).show(); return
        }
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
