package br.paulo.tvlauncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.View
import br.paulo.tvlauncher.AboutActivity
import br.paulo.tvlauncher.DesignTokens
import br.paulo.tvlauncher.settings.Fontes

/**
 * A logo Sofaly (18/11/2026, Paulo: "em algum lugar do sistema deveria aparecer a nossa logo"). Desenho da marca fechada
 * em 16/11/2026 (artifact "Marca Sofaly", caminho C2): a TV-sofá (tela com moldura grossa, antenas "orelha de coelho",
 * braços e pezinhos de sofá) e o nome "sofaly" em Nunito 600, em que o "o" é a própria TV-sofá e o "ly" vai na cor do sofá.
 *
 * Desenhada em código, a partir do mesmo SVG da marca (quadro 120 x 112): nítida em qualquer tamanho e sem PNG.
 * Onde aparece: a abertura ao ligar ([SplashOverlay]), o topo das Configurações, a tela "Sobre a Sofaly"
 * ([AboutActivity]) e o canto do protetor de tela. A HOME não tem a logo fixa (decidido com o Paulo: brigaria com a barra).
 */
object SofalyLogo {
    /** Cores da marca: laranja do sofá (fundo escuro), o mesmo mais escuro (fundo claro), creme do texto e o miolo da tela. */
    const val ORANGE = DesignTokens.ORANGE   // uma fonte só: design/tokens.json
    const val CREAM = 0xFFFFF3E6.toInt()
    const val SCREEN = 0xFF1C2140.toInt()

    /** Quadro do símbolo (o SVG vai de y = -10 a 102; aqui já somado 10). */
    const val W = 120f
    const val H = 112f

    private val r = RectF()

    /**
     * Desenha a TV-sofá com o canto de cima à esquerda em (0, 0) e o quadro [W] x [H] (quem chama escala o canvas).
     * [alpha] 0..1 apaga o desenho inteiro (o protetor usa a logo discreta). Paints passados de fora: nada é criado aqui.
     */
    fun drawSymbol(c: Canvas, fill: Paint, stroke: Paint, path: Path, alpha: Float = 1f) {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        fill.style = Paint.Style.FILL; fill.shader = null
        stroke.style = Paint.Style.STROKE; stroke.shader = null; stroke.strokeCap = Paint.Cap.ROUND; stroke.strokeJoin = Paint.Join.ROUND
        // Antenas: duas varinhas de tamanhos diferentes saindo de um pino.
        stroke.color = ORANGE; stroke.alpha = a; stroke.strokeWidth = 8.5f
        c.drawLine(60f, 36f, 35f, 18f, stroke); c.drawLine(60f, 36f, 89f, 24f, stroke)
        fill.color = ORANGE; fill.alpha = a
        r.set(51f, 29f, 69f, 47f); c.drawArc(r, 180f, 180f, true, fill)
        // Tela: miolo escuro e moldura grossa (o traço do SVG fica centrado na borda do retângulo).
        fill.color = SCREEN; fill.alpha = a
        r.set(23.75f, 35.25f, 96.25f, 92.75f); c.drawRoundRect(r, 15.75f, 15.75f, fill)
        stroke.strokeWidth = 13.5f; stroke.color = ORANGE; stroke.alpha = a
        c.drawRoundRect(r, 15.75f, 15.75f, stroke)
        // Braços, assento e pés do sofá.
        fill.color = ORANGE; fill.alpha = a
        r.set(3.5f, 59f, 24f, 99.5f); c.drawRoundRect(r, 9.5f, 9.5f, fill)
        r.set(96f, 59f, 116.5f, 99.5f); c.drawRoundRect(r, 9.5f, 9.5f, fill)
        r.set(12f, 86f, 108f, 99.5f); c.drawRoundRect(r, 5f, 5f, fill)
        path.reset()
        path.moveTo(12.5f, 92f); path.lineTo(24.5f, 92f); path.lineTo(17f, 108.8f); path.quadTo(16f, 111f, 13.6f, 111f)
        path.lineTo(11.4f, 111f); path.quadTo(9f, 111f, 9.3f, 108.6f); path.close()
        path.moveTo(107.5f, 92f); path.lineTo(95.5f, 92f); path.lineTo(103f, 108.8f); path.quadTo(104f, 111f, 106.4f, 111f)
        path.lineTo(108.6f, 111f); path.quadTo(111f, 111f, 110.7f, 108.6f); path.close()
        c.drawPath(path, fill)
        // Reflexo curvo do vidro no canto da tela (não é um "rosto": é o brilho de uma tela de tubo).
        stroke.color = CREAM; stroke.alpha = (a * 0.85f).toInt(); stroke.strokeWidth = 5.5f
        path.reset(); path.moveTo(37f, 65f); path.quadTo(37f, 54f, 48f, 53f); c.drawPath(path, stroke)
    }

    /** A Nunito 600 do nome (a mesma do app, em res/font). */
    fun typeface(ctx: Context): Typeface = Fontes.nunito(ctx, 400)
}

/** A TV-sofá sozinha, centrada no espaço dado, sem deformar. */
class SofalySymbol(private val alphaFactor: Float = 1f) : Drawable() {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var extraAlpha = 255

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val esc = minOf(b.width() / SofalyLogo.W, b.height() / SofalyLogo.H)
        c.save()
        c.translate(b.left + (b.width() - SofalyLogo.W * esc) / 2f, b.top + (b.height() - SofalyLogo.H * esc) / 2f)
        c.scale(esc, esc)
        SofalyLogo.drawSymbol(c, fill, stroke, path, alphaFactor * extraAlpha / 255f)
        c.restore()
    }

    override fun setAlpha(alpha: Int) { extraAlpha = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; stroke.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * O nome "sofaly" desenhado como na marca: "s", a TV-sofá no lugar do "o", "fa" em creme e "ly" em laranja.
 * [textSizePx]: o tamanho da letra. A largura e a altura saem do texto (wrap_content). Medidas do SVG da marca: a TV
 * tem 1,75 x-height de altura, 1,766 de largura, desce 0,046 abaixo da linha de base e tem 0,058 em / 0,008 em de folga.
 */
class SofalyWordmark(ctx: Context, textSizePx: Float, private val dim: Float = 1f) : View(ctx) {
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = SofalyLogo.typeface(ctx); textSize = textSizePx; letterSpacing = -0.02f
    }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val xHeight: Float
    private val symW: Float
    private val symH: Float
    private val gapBefore = 0.058f * textSizePx
    private val gapAfter = 0.008f * textSizePx
    private val wS: Float
    private val wFa: Float
    private val wLy: Float
    private val ascent: Float
    private val descent: Float

    init {
        val b = Rect()
        text.getTextBounds("x", 0, 1, b)
        xHeight = b.height().toFloat().takeIf { it > 0f } ?: (textSizePx * 0.5f)
        symH = 1.75f * xHeight
        symW = 1.766f * xHeight
        wS = text.measureText("s"); wFa = text.measureText("fa"); wLy = text.measureText("ly")
        ascent = -text.fontMetrics.ascent; descent = text.fontMetrics.descent
        contentDescription = "Sofaly"
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    private val totalW get() = wS + gapBefore + symW + gapAfter + wFa + wLy

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = Math.ceil(totalW.toDouble()).toInt() + paddingLeft + paddingRight
        val h = Math.ceil((ascent + descent).toDouble()).toInt() + paddingTop + paddingBottom
        setMeasuredDimension(resolveSize(w, widthMeasureSpec), resolveSize(h, heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val a = (dim.coerceIn(0f, 1f) * 255).toInt()
        val base = paddingTop + ascent
        var x = paddingLeft + ((width - paddingLeft - paddingRight) - totalW).coerceAtLeast(0f) / 2f
        text.color = SofalyLogo.CREAM; text.alpha = a
        c.drawText("s", x, base, text); x += wS + gapBefore
        // A TV-sofá no lugar do "o": o quadro do SVG da palavra começa em x = 3,5 (113 de largura útil).
        val esc = symH / SofalyLogo.H
        c.save()
        c.translate(x - 3.5f * esc, base + 0.046f * xHeight - symH)
        c.scale(esc, esc)
        SofalyLogo.drawSymbol(c, fill, stroke, path, dim)
        c.restore()
        x += symW + gapAfter
        text.color = SofalyLogo.CREAM; text.alpha = a
        c.drawText("fa", x, base, text); x += wFa
        // "ly" na cor do sofá: a palavra se lê so-fa-ly (decisão de 16/11/2026).
        text.color = SofalyLogo.ORANGE; text.alpha = a
        c.drawText("ly", x, base, text)
    }
}
