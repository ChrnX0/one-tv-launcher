package br.paulo.tvlauncher.home

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.paulo.tvlauncher.DesignTokens
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.settings.Fontes

/**
 * A tela "Todos os apps" (proposta C, escolhida pelo Paulo em 20/11/2026; estudo
 * https://claude.ai/artifact/MEFXwpHq57vjX4gi1sGBg1).
 *
 * Porquê: a grade antiga deixava dois terços da tela vazios, não dizia o nome nem o que o app é, escondia os apps
 * ocultos atrás de Configurações e não usava nada do que o launcher já sabe. Para quê: ver, abrir e cuidar de todos os
 * apps numa tela só, com letra de sofá. Como: à esquerda, uma prateleira por categoria (AppShelf: padrão da Play, com a
 * cor do mundo da cruz), mais Congelados e Escondidos (com PIN, ideia do Paulo); à direita, o painel do app em foco
 * (ficha do Observador, último uso, espaço, dica). OK abre; segurar OK abre as opções (as mesmas da cruz, mais Congelar).
 * A tela só desenha: quem decide e age é o [Host] (MainActivity).
 */
class AllAppsScreen(ctx: Context, private val host: Host) : FrameLayout(ctx) {

    interface Host {
        fun open(card: Card)
        fun hold(card: Card)
        /** O app em foco mudou: o host preenche o painel (pode demorar; chama [showInfo] depois). */
        fun focused(card: Card)
        fun loadBanner(card: Card, done: (Drawable?) -> Unit)
    }

    enum class Kind { APP, HIDDEN, FROZEN, LOCK }

    class Card(val pkg: String, val name: String, val kind: Kind, val icon: Drawable?)

    class Section(val title: String?, val color: Int, val cards: List<Card>)

    class Info(val title: String, val lines: List<String>, val tip: String?, val hint: String)

    private val d = resources.displayMetrics.density
    private fun dp(v: Int) = (v * d + 0.5f).toInt()
    private fun sp(t: TextView, v: Float) = t.setTextSize(TypedValue.COMPLEX_UNIT_SP, v)
    private val forte: Typeface = Fontes.nunito(800)
    private val medio: Typeface = Fontes.nunito(600)

    private val conta: TextView
    private val coluna = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
    // A "borda que esmaece" fica ligada só pela folga que ela dá: com ela o ScrollView para o cartão em foco 32 dp antes da
    // borda (sem isso o da última fileira ficava cortado embaixo). O desenho dela fica desligado (força 0): com os cartões
    // crescendo para fora da rolagem, o esmaecido virava uma faixa escura no meio da fileira (visto na Sala, 20/11/2026).
    private val rolagem = object : ScrollView(ctx) {
        override fun getTopFadingEdgeStrength() = 0f
        override fun getBottomFadingEdgeStrength() = 0f
    }.apply {
        isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isSmoothScrollingEnabled = true
        isVerticalFadingEdgeEnabled = true; setFadingEdgeLength(dp(32))
        setPaddingRelative(0, dp(12), 0, dp(64))
        addView(coluna)
    }
    private val painelBanner = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
    private val painelNome = TextView(ctx)
    private val painelLinhas = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val painelDica = TextView(ctx)
    private val painelAjuda = TextView(ctx)
    private val views = HashMap<String, View>()
    private var emFoco: Card? = null

    init {
        isFocusable = false
        // Esta tela corta cada filho no limite dele: a rolagem (que deixa 8 dp de folga à esquerda e 12 dp em cima para o
        // cartão crescer no foco) não desenha por cima do título nem por baixo do painel da direita. Dentro dela nada corta.
        // Visto na Sala 20/11/2026: com tudo sem corte, a 4ª coluna ia para baixo do painel e as fileiras roladas cobriam o
        // título; cortando na fileira (HorizontalScrollView) o cartão em foco perdia a borda.
        clipChildren = true
        // Véu Noite sobre o fundo da casa. Opaco em cima: com 80% a ilha recolhida da barra de cima (hora, "feriado hoje")
        // aparecia por trás do título (visto na Sala, 20/11/2026).
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0xFF121528.toInt(), 0xF0121528.toInt(), 0xF5121528.toInt()))
        val margem = dp(Grade.MARGEM)
        val titulo = TextView(ctx).apply {
            text = ctx.getString(R.string.cruz_todos_apps).uppercase(); letterSpacing = 0.16f; includeFontPadding = false
            setTextColor(Estilo.ACENTO); typeface = forte; sp(this, HomeCruz.FONTE_ROTULO)
        }
        conta = TextView(ctx).apply { includeFontPadding = false; setTextColor(HomeCruz.COR_SUB); typeface = medio; sp(this, HomeCruz.FONTE_POSICAO) }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(titulo)
            addView(conta, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(14) })
        }, LayoutParams(WRAP, WRAP).apply { marginStart = margem; topMargin = dp(28) })
        addView(rolagem, LayoutParams(MATCH, MATCH).apply {
            topMargin = dp(56); marginStart = margem - dp(8); marginEnd = dp(PAINEL + 20) + margem
        })
        // Painel do app em foco.
        painelNome.apply { setTextColor(Estilo.TEXTO); typeface = forte; sp(this, 26f); maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        painelDica.apply { setTextColor(DesignTokens.ATTENTION); typeface = medio; sp(this, 17f); visibility = GONE }
        painelAjuda.apply { setTextColor(Estilo.TEXTO_2); typeface = medio; sp(this, 16f) }
        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPaddingRelative(dp(20), dp(20), dp(20), dp(20))
            background = GradientDrawable().apply { cornerRadius = dp(DesignTokens.RADIUS_MEDIUM).toFloat(); setColor(0x8C000000.toInt()) }
            addView(painelBanner, LinearLayout.LayoutParams(MATCH, dp(126)))
            addView(painelNome, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })
            addView(painelLinhas, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
            addView(View(ctx), LinearLayout.LayoutParams(MATCH, 0, 1f))
            addView(painelDica, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
            addView(painelAjuda, LinearLayout.LayoutParams(MATCH, WRAP))
        }, LayoutParams(dp(PAINEL), MATCH).apply {
            gravity = Gravity.END; marginEnd = margem; topMargin = dp(28); bottomMargin = dp(28)
        })
    }

    /** Desenha as prateleiras. [focus] = pacote que fica com o foco (null = o primeiro). */
    fun show(count: String, sections: List<Section>, focus: String?) {
        conta.text = count
        coluna.removeAllViews(); views.clear()
        var primeiro: View? = null
        var alvo: View? = null
        for (s in sections) {
            if (s.cards.isEmpty()) continue
            if (s.title != null) coluna.addView(rotulo(s), LinearLayout.LayoutParams(WRAP, WRAP).apply {
                marginStart = dp(8); topMargin = if (coluna.childCount == 0) 0 else dp(18); bottomMargin = dp(8)
            })
            val linha = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; clipToPadding = false }
            for (c in s.cards) {
                val v = cartao(c)
                linha.addView(v, LinearLayout.LayoutParams(dp(CARTAO_L), dp(CARTAO_A)).apply { marginEnd = dp(VAO) })
                views[c.pkg + "|" + c.kind] = v
                if (primeiro == null) primeiro = v
                if (alvo == null && c.pkg == focus) alvo = v
            }
            coluna.addView(HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
                isHorizontalFadingEdgeEnabled = true; setFadingEdgeLength(dp(32))   // o 5º cartão esmaece antes do painel
                setPaddingRelative(dp(8), dp(8), dp(8), dp(8))
                addView(linha)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        (alvo ?: primeiro)?.let { v -> post { v.requestFocus() } }
    }

    fun showInfo(card: Card, info: Info) {
        if (emFoco !== card) return
        painelNome.text = info.title
        painelLinhas.removeAllViews()
        for (l in info.lines) painelLinhas.addView(TextView(context).apply {
            text = l; setTextColor(Estilo.TEXTO_2); typeface = medio; sp(this, 17f); maxLines = 4; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        painelDica.text = info.tip ?: ""; painelDica.visibility = if (info.tip == null) GONE else VISIBLE
        painelAjuda.text = info.hint
    }

    fun hasCard(pkg: String) = views.keys.any { it.startsWith("$pkg|") }

    /** Tecla com a tela aberta: OK curto abre, OK segurado (meio segundo) abre as opções. O resto segue o foco normal. */
    private var okDesde = 0L
    private var segurou = false
    fun key(e: KeyEvent): Boolean {
        val ok = e.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || e.keyCode == KeyEvent.KEYCODE_ENTER || e.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!ok) return false
        val c = emFoco ?: return false
        if (findFocus()?.tag !is Card) return false
        when (e.action) {
            KeyEvent.ACTION_DOWN -> {
                if (e.repeatCount == 0) { okDesde = e.eventTime; segurou = false }
                // isLongPress: o aviso de "segurou" do próprio Android (controles e `input keyevent --longpress` mandam a
                // repetição já marcada, às vezes antes dos 500 ms; sem ele o OK segurado abria o app, visto na Sala 20/11).
                else if (!segurou && (e.isLongPress || e.eventTime - okDesde >= SEGURAR_MS)) { segurou = true; host.hold(c) }
            }
            KeyEvent.ACTION_UP -> if (!segurou && !e.isCanceled) host.open(c)
        }
        return true
    }

    // ---------------------------------------------------------------- peças

    private fun rotulo(s: Section) = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(View(context).apply {
            background = GradientDrawable().apply { cornerRadius = dp(3).toFloat(); setColor(s.color) }
        }, LinearLayout.LayoutParams(dp(12), dp(12)).apply { marginEnd = dp(10) })
        addView(TextView(context).apply {
            text = s.title; setTextColor(HomeCruz.COR_CATEGORIA); typeface = forte; sp(this, 19f); includeFontPadding = false
        })
    }

    private fun cartao(c: Card): View {
        val raio = dp(DesignTokens.RADIUS_SMALL + 2).toFloat()
        val moldura = FrameLayout(context).apply {
            tag = c
            isFocusable = true; isFocusableInTouchMode = true
            background = GradientDrawable().apply { cornerRadius = raio; setColor(0x26FFFFFF) }
            clipToOutline = true
            if (c.kind == Kind.HIDDEN || c.kind == Kind.FROZEN) alpha = 0.6f
        }
        val img = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        val nome = TextView(context).apply {
            text = c.name; setTextColor(Estilo.TEXTO); typeface = forte; sp(this, 16f); gravity = Gravity.CENTER
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPaddingRelative(dp(8), 0, dp(8), 0)
        }
        moldura.addView(img, LayoutParams(MATCH, MATCH))
        moldura.addView(nome, LayoutParams(MATCH, MATCH))
        if (c.kind == Kind.LOCK) {
            nome.text = c.name
        } else {
            // Sem banner (ainda, ou o app não tem): o ícone no meio de cima e o nome embaixo.
            c.icon?.let { ic ->
                img.scaleType = ImageView.ScaleType.FIT_CENTER
                img.setPaddingRelative(dp(46), dp(14), dp(46), dp(36))
                img.setImageDrawable(ic.constantState?.newDrawable(resources)?.mutate() ?: ic)
                nome.gravity = Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM; nome.setPaddingRelative(dp(8), 0, dp(8), dp(8)); nome.maxLines = 1
            }
            host.loadBanner(c) { b ->
                if (b == null) return@loadBanner
                img.scaleType = ImageView.ScaleType.FIT_XY; img.setPaddingRelative(0, 0, 0, 0); img.setImageDrawable(b)
                nome.visibility = GONE
            }
        }
        selo(c)?.let { t ->
            moldura.addView(TextView(context).apply {
                text = t; setTextColor(Estilo.TEXTO); typeface = forte; sp(this, 13f)
                setPaddingRelative(dp(8), dp(2), dp(8), dp(2))
                background = GradientDrawable().apply { cornerRadius = dp(99).toFloat(); setColor(0xCC000000.toInt()) }
            }, LayoutParams(WRAP, WRAP).apply { gravity = Gravity.TOP or Gravity.END; topMargin = dp(6); marginEnd = dp(6) })
        }
        val anel = GradientDrawable().apply { cornerRadius = raio; setStroke(dp(3), DesignTokens.FOCUS); setColor(0) }
        moldura.setOnFocusChangeListener { v, tem ->
            val f = if (tem) 1.08f else 1f
            v.animate().scaleX(f).scaleY(f).setDuration(DesignTokens.MS_QUICK).start()
            v.foreground = if (tem) anel else null
            v.translationZ = if (tem) dp(4).toFloat() else 0f
            v.alpha = if (tem) 1f else if (c.kind == Kind.HIDDEN || c.kind == Kind.FROZEN) 0.6f else 1f
            if (tem) {
                emFoco = c
                painelBanner.setImageDrawable(img.drawable)
                // Sem imagem (cartão "Escondidos", app sem banner): o painel não guarda um buraco vazio em cima do nome.
                painelBanner.visibility = if (img.drawable == null) GONE else VISIBLE
                painelNome.text = c.name; painelLinhas.removeAllViews(); painelDica.visibility = GONE
                host.focused(c)
                v.postDelayed({ folgaEmbaixo(v) }, DesignTokens.MS_FAST + 50L)
            }
        }
        return moldura
    }

    /**
     * O cartão em foco da última fileira ficava encostado na borda de baixo da TV, com o anel cortado (visto na Sala,
     * 20/11/2026, mesmo com a folga da rolagem). Depois que a rolagem assenta, mede onde o cartão está NA TELA e, se
     * passou do limite, rola o que falta. Usa a tela e não a altura da rolagem porque é a borda da TV que corta.
     */
    private fun folgaEmbaixo(v: View) {
        if (!v.hasFocus() || !v.isAttachedToWindow) return
        val loc = IntArray(2); v.getLocationOnScreen(loc)
        val limite = rootView.height - dp(40)
        val fundo = loc[1] + (v.height * v.scaleY).toInt()
        if (fundo > limite) rolagem.smoothScrollBy(0, fundo - limite)
    }

    private fun selo(c: Card): String? = when (c.kind) {
        Kind.FROZEN -> context.getString(R.string.aa_badge_frozen)
        Kind.HIDDEN -> context.getString(R.string.aa_badge_hidden)
        Kind.LOCK -> "PIN"
        Kind.APP -> null
    }

    companion object {
        const val PAINEL = 270        // largura do painel do app em foco (dp)
        const val CARTAO_L = 168      // 16:9, ~1,3x o cartão da grade antiga; 3 por fileira à vista
        const val CARTAO_A = 95
        const val VAO = 18
        const val SEGURAR_MS = 500L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
