package br.paulo.tvlauncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.settings.Fontes

/**
 * Uma COLUNA no jeito da cruz para telas inteiras (18/11/2026, despertador; Paulo: "todo o sistema com a mesma leitura
 * visual da cruz"). Sem caixas: o item escolhido fica grande e branco, com o ponto laranja à esquerda do nome e a
 * informação útil embaixo (creme, 18 sp); os vizinhos encolhem e apagam aos poucos, como na [HomeCruz]. Movimento em
 * mola ([MolaViews]) com a "onda" dos vizinhos.
 *
 * Não usa o foco do Android: quem tem a tela chama [mover] nas setas e lê [escolhido] no OK. Assim a coluna nunca perde
 * o foco para outra view e o escolhido fica sempre no mesmo lugar ([topo]); quem anda é a coluna.
 */
class CrossColumn(ctx: Context) : FrameLayout(ctx) {

    /** [apagado]: alarme desligado, por exemplo (nome em cinza, mas legível). [fonte] em sp do nome escolhido. */
    class Item(val nome: CharSequence, val info: CharSequence = "", val apagado: Boolean = false,
               val fonte: Float = FONTE_NOME)

    companion object {
        const val FONTE_NOME = 26f
        const val FONTE_INFO = HomeCruz.FONTE_SUB   // 18 sp, o texto padrão do Google para TV
        /** Vizinho = 72% do escolhido: 26 sp viram ~19 sp (o mínimo da casa para quem lê de longe). */
        const val ESCALA = 0.72f
        const val VAO = 12                          // dp entre itens
        /** Opacidade pela distância: embaixo segue a escada da cruz; em cima apaga mais (o que já passou). */
        fun alfa(dist: Int): Float = when {
            dist == 0 -> 1f
            dist > 0 -> HomeCruz.ALFA_COLUNA[dist] ?: 0.45f
            dist == -1 -> 0.6f
            dist == -2 -> 0.4f
            else -> 0.25f
        }
        const val COR_APAGADO = 0xFFA39C95.toInt()

        /** A fileira de dicas do pé (tecla numa pílula + o que ela faz), a mesma do Rádio e das listas. */
        fun dicas(ctx: Context, vararg pares: Pair<String, String>): android.view.ViewGroup = Estilo.barraDicas(ctx, *pares)
    }

    private class Linha(val raiz: LinearLayout, val ponto: View, val nome: TextView)

    private val mola = MolaViews(this)
    private val linhas = ArrayList<Linha>()
    private var itens: List<Item> = emptyList()
    private val rtl = ctx.resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL
    private val info = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_INFO); typeface = Fontes.nunito(500); setTextColor(HomeCruz.COR_SUB)
        includeFontPadding = false; setLineSpacing(dp(4f), 1f)
        setPaddingRelative(dpi(HomeCruz.PONTO + 14), 0, 0, 0)
        alpha = 0f
    }

    /** Onde fica o topo do item escolhido (px, dentro da coluna). */
    var topo = 0f
        set(v) { field = v; arrumar(false) }

    /** false = o foco está em outra parte da tela: o escolhido perde o ponto e apaga um pouco. */
    var ativa = true
        set(v) { if (field != v) { field = v; arrumar(true) } }

    var escolhido = 0
        private set

    val tamanho get() = itens.size

    init {
        clipChildren = true
        addView(info, LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or_, ob -> if (r - l != or_ - ol || b - t != ob - ot) arrumar(false) }
    }

    /** Troca os itens. Mesma quantidade: só os textos mudam (o lugar fica); outra: refaz as linhas. */
    fun definir(novos: List<Item>, escolher: Int = escolhido) {
        if (novos.size != linhas.size) {
            linhas.forEach { removeView(it.raiz); mola.esquecer(it.raiz) }
            linhas.clear()
            repeat(novos.size) { linhas += criarLinha() }
        }
        itens = novos
        for ((i, it) in novos.withIndex()) preencher(linhas[i], it)
        escolhido = escolher.coerceIn(0, (novos.size - 1).coerceAtLeast(0))
        arrumar(false)
    }

    /** Anda [delta] itens. Devolve false na ponta (quem chama toca o som de borda). */
    fun mover(delta: Int): Boolean {
        val n = escolhido + delta
        if (n !in itens.indices) return false
        escolhido = n
        arrumar(true)
        announceForAccessibility(itens[n].nome.toString() + ". " + itens[n].info)
        return true
    }

    /** O escolhido por extenso, para o leitor de tela. */
    fun descricao(): String = itens.getOrNull(escolhido)?.let { it.nome.toString() + if (it.info.isNotEmpty()) ". " + it.info else "" }.orEmpty()

    fun irPara(i: Int, animar: Boolean = true) {
        if (i !in itens.indices) return
        escolhido = i; arrumar(animar)
    }

    private fun criarLinha(): Linha {
        val ponto = View(context).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Estilo.ACENTO) }
            alpha = 0f
        }
        val nome = TextView(context).apply {
            typeface = Fontes.nunito(700); setTextColor(Estilo.TEXTO); includeFontPadding = false
            maxLines = 2; setLineSpacing(dp(2f), 1f)
        }
        val raiz = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            addView(ponto, LinearLayout.LayoutParams(dpi(HomeCruz.PONTO), dpi(HomeCruz.PONTO)).apply { marginEnd = dpi(14) })
            addView(nome, LinearLayout.LayoutParams(0, -2, 1f))
            alpha = 0f
        }
        addView(raiz, LayoutParams(-1, -2, Gravity.TOP or Gravity.START))
        return Linha(raiz, ponto, nome)
    }

    private fun preencher(l: Linha, it: Item) {
        l.nome.text = it.nome
        l.nome.setTextSize(TypedValue.COMPLEX_UNIT_SP, it.fonte)
        l.nome.setTextColor(if (it.apagado) COR_APAGADO else Estilo.TEXTO)
        // ponto na altura do meio da 1ª linha do nome
        (l.ponto.layoutParams as LinearLayout.LayoutParams).topMargin =
            ((l.nome.paint.fontMetrics.let { m -> m.descent - m.ascent } - dp(HomeCruz.PONTO.toFloat())) / 2).toInt().coerceAtLeast(0)
        l.raiz.contentDescription = it.nome.toString() + if (it.info.isNotEmpty()) ". " + it.info else ""
    }

    private fun alturaDe(v: View): Int {
        val w = width.takeIf { it > 0 } ?: return 0
        v.measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        return v.measuredHeight
    }

    /** Põe cada linha no lugar: o escolhido em [topo], os vizinhos em escada para cima e para baixo. */
    private fun arrumar(animar: Boolean) {
        if (width == 0 || itens.isEmpty()) return
        val s = escolhido
        val vao = dp(VAO.toFloat())
        val px = if (rtl) width.toFloat() else 0f
        info.text = itens[s].info
        val hs = alturaDe(linhas[s].raiz)
        val hi = if (itens[s].info.isEmpty()) 0 else alturaDe(info)
        val infoY = topo + hs + dp(6f)
        mola.mover(info, 0f, infoY, if (hi == 0) 0f else if (ativa) 1f else 0.6f, 1f, animar)
        val ys = FloatArray(itens.size)
        ys[s] = topo
        var y = topo + hs + (if (hi > 0) hi + dp(8f) else 0f) + vao
        for (k in s + 1 until itens.size) { ys[k] = y; y += alturaDe(linhas[k].raiz) * ESCALA + vao }
        y = topo - vao
        for (k in s - 1 downTo 0) { val h = alturaDe(linhas[k].raiz) * ESCALA; ys[k] = y - h; y = ys[k] - vao }
        for ((k, l) in linhas.withIndex()) {
            val dist = k - s
            l.raiz.pivotX = px; l.raiz.pivotY = 0f
            var a = alfa(dist)
            if (!ativa) a *= if (dist == 0) 0.8f else 0.7f
            // só aparece quem cabe inteiro na coluna: linha cortada pela borda (embaixo, as dicas) parece defeito
            val alto = l.raiz.measuredHeight * (if (dist == 0) 1f else ESCALA)
            if (ys[k] < 0f || ys[k] + alto > height) a = 0f
            val atraso = (kotlin.math.abs(dist) * HomeCruz.ONDA_MS).coerceAtMost(HomeCruz.ONDA_MAX_MS)
            mola.mover(l.raiz, 0f, ys[k], a, if (dist == 0) 1f else ESCALA, animar, if (animar) atraso else 0L)
            val p = if (dist == 0 && ativa) 1f else 0f
            if (animar && p == 1f && l.ponto.alpha < 1f) { l.ponto.scaleX = 0.4f; l.ponto.scaleY = 0.4f }   // o ponto "acende"
            if (animar) l.ponto.animate().alpha(p).scaleX(1f).scaleY(1f).setDuration(Estilo.LISTA_FOCO_MS).start()
            else { l.ponto.animate().cancel(); l.ponto.alpha = p; l.ponto.scaleX = 1f; l.ponto.scaleY = 1f }
        }
    }

    override fun onDetachedFromWindow() { mola.pararTudo(); super.onDetachedFromWindow() }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}

/**
 * Rolete de número (hora ou minuto) no jeito da coluna da cruz: o valor grande no meio, os vizinhos menores e apagados em
 * cima e embaixo, e setas ▲ ▼ quando está ativo, para quem não sabe que as setas mudam o número. Ao mudar, o rolete
 * "gira": os números chegam do lado de onde vieram.
 */
class TimeWheel(ctx: Context, private val maximo: Int) : FrameLayout(ctx) {

    companion object {
        const val FONTE = 88f
        const val ESCALA_1 = 0.42f
        const val ESCALA_2 = 0.32f
    }

    var valor = 0
        private set
    var ativo = false
        set(v) { field = v; pintar() }

    private val numeros = List(5) { i ->
        TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE); typeface = Fontes.nunito(if (i == 2) 700 else 600)
            setTextColor(Estilo.TEXTO); includeFontPadding = false; gravity = Gravity.CENTER
        }
    }
    private val pilha = FrameLayout(ctx)
    private val setaCima = Seta(ctx, true)
    private val setaBaixo = Seta(ctx, false)

    init {
        clipChildren = false
        numeros.forEach { pilha.addView(it, LayoutParams(-1, -2, Gravity.CENTER)) }
        addView(pilha, LayoutParams(-1, -1))
        addView(setaCima, LayoutParams(dpi(30), dpi(14), Gravity.CENTER))
        addView(setaBaixo, LayoutParams(dpi(30), dpi(14), Gravity.CENTER))
        addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> posicionar() }
    }

    /** Altura do algarismo desenhado (~72% do tamanho da letra na Nunito), não da linha inteira da fonte. */
    private fun alturaNumero(): Float = numeros[2].textSize * 0.72f

    private fun posicionar() {
        val h = alturaNumero()
        val vao = dpi(14).toFloat()
        val seta = dpi(14).toFloat()
        val d1 = h / 2 + vao + seta + vao + h * ESCALA_1 / 2
        val d2 = d1 + h * ESCALA_1 / 2 + vao + h * ESCALA_2 / 2
        val pos = floatArrayOf(-d2, -d1, 0f, d1, d2)
        val esc = floatArrayOf(ESCALA_2, ESCALA_1, 1f, ESCALA_1, ESCALA_2)
        for ((i, t) in numeros.withIndex()) { t.translationY = pos[i]; t.scaleX = esc[i]; t.scaleY = esc[i] }
        setaCima.translationY = -(h / 2 + vao + seta / 2)
        setaBaixo.translationY = h / 2 + vao + seta / 2
        pintar()
    }

    private fun pintar() {
        val alfas = floatArrayOf(0.22f, 0.5f, 1f, 0.5f, 0.22f)
        for ((i, t) in numeros.withIndex()) t.alpha = if (i == 2) (if (ativo) 1f else 0.82f) else alfas[i] * (if (ativo) 1f else 0.7f)
        setaCima.alpha = if (ativo) 1f else 0f
        setaBaixo.alpha = if (ativo) 1f else 0f
    }

    /**
     * Põe o valor; [de] +1/-1 = subiu/desceu (anima o rolete), 0 só troca. O número MAIOR fica em cima, como a seta ▲
     * sugere: ▲ traz o de cima para o meio (conferido na Sala em 18/11: com o menor em cima, ▲ ia "para baixo").
     */
    fun definir(v: Int, de: Int = 0) {
        valor = ((v % maximo) + maximo) % maximo
        for ((i, t) in numeros.withIndex()) t.text = "%02d".format(((valor + 2 - i) % maximo + maximo) % maximo)
        contentDescription = "%02d".format(valor)
        if (de != 0 && !MolaViews.semAnimacao(this)) {
            val passo = alturaNumero() * 0.9f
            pilha.animate().cancel()
            pilha.translationY = -passo * de   // subiu: a pilha desce do lugar do número de cima
            pilha.animate().translationY(0f).setDuration(Estilo.LISTA_FOCO_MS).setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
    }

    private fun dpi(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    /** Setinha ▲/▼ em laranja (o acento: "isto muda"). Desenhada: a fonte da casa não tem o triângulo em todo idioma. */
    private class Seta(ctx: Context, private val cima: Boolean) : View(ctx) {
        private val tinta = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.ACENTO; style = Paint.Style.FILL }
        private val p = Path()
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.reset()
            if (cima) { p.moveTo(w / 2, 0f); p.lineTo(w, h); p.lineTo(0f, h) } else { p.moveTo(0f, 0f); p.lineTo(w, 0f); p.lineTo(w / 2, h) }
            p.close(); c.drawPath(p, tinta)
        }
    }
}
