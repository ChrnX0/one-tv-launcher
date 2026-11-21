package br.paulo.tvlauncher.observer

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Animatable
import android.graphics.drawable.Drawable
import android.view.animation.LinearInterpolator
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.DesignTokens
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.home.MovimentoAdaptativo
import br.paulo.tvlauncher.home.SofalyLogo
import br.paulo.tvlauncher.observer.SofyMotion.Part
import br.paulo.tvlauncher.observer.SofyPolicy.Expression

/**
 * A Sofy desenhada (20/11/2026): a TV-sofá da marca ([SofalyLogo.drawSymbol], mesmas medidas) com cada parte solta para
 * se mexer, e o rosto escrito na tela com símbolos ([Expression]). Um [Drawable] [Animatable]: entra em qualquer
 * ImageView, inclusive na pílula do [AvisoTv], e [start] toca o movimento da expressão uma vez ([SofyMotion]).
 *
 * Sem imagem nem biblioteca: só caminhos e texto no Canvas, redesenhados enquanto o movimento dura (até 2,5 s) e
 * depois nunca mais. Com animações desligadas no sistema, alto contraste ou o movimento adaptativo em PARADO, [start]
 * vai direto para a pose final (só o rosto muda).
 *
 * [bubble]: só dois balões existem, porque dizem algo que o rosto não diz: aspas (recado de outra pessoa) e ♪ (música).
 */
class SofyDrawable(
    private val ctx: Context,
    expression: Expression = Expression.ATTENTIVE,
    private val bubble: String? = null,
) : Drawable(), Animatable {

    var expression: Expression = expression
        set(v) { field = v; clip = SofyMotion.clip(v); ms = clip.lengthMs; invalidateSelf() }

    private var clip = SofyMotion.clip(expression)
    private var ms: Long = clip.lengthMs   // parada na pose final até alguém chamar start()
    private var animator: ValueAnimator? = null
    private var alpha255 = 255

    /** Falando (SofyVoice): a boca alterna "o" e "-" a cada palavra; os olhos ficam os da expressão. */
    var talking = false
        set(v) { field = v; aberta = v; invalidateSelf() }
    private var aberta = false
    fun word() { if (talking) { aberta = !aberta; invalidateSelf() } }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); textAlign = Paint.Align.CENTER; color = SofalyLogo.CREAM
    }
    private val path = Path()
    private val r = RectF()

    override fun start() {
        animator?.cancel()
        if (semMovimento()) { ms = clip.lengthMs; invalidateSelf(); return }
        animator = ValueAnimator.ofFloat(0f, clip.lengthMs.toFloat()).apply {
            duration = clip.lengthMs
            interpolator = LinearInterpolator()   // as curvas de cada trilha já estão no SofyMotion
            addUpdateListener { ms = (it.animatedValue as Float).toLong(); invalidateSelf() }
            start()
        }
    }

    override fun stop() { animator?.cancel(); animator = null; ms = clip.lengthMs; invalidateSelf() }
    override fun isRunning(): Boolean = animator?.isRunning == true

    /** Mesmo critério do resto do app: sistema sem animação, alto contraste, ou o aparelho no PARADO. */
    private fun semMovimento(): Boolean {
        val sistema = runCatching {
            android.provider.Settings.Global.getFloat(ctx.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        if (sistema || Estilo.altoContraste) return true
        return runCatching { MovimentoAdaptativo.atual(ctx, sistema) == MovimentoAdaptativo.Nivel.PARADO }.getOrDefault(false)
    }

    override fun draw(c: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        // Quadro com folga para o pulo e os braços (o símbolo é 0..120 x 0..112). Sem balão o quadro é justo, para ela sair
        // maior na pílula (conferido na Sala 20/11: com a folga do balão sempre, o rosto ficava pequeno a 3 m).
        val qx: Float; val qy: Float; val qw: Float; val qh: Float
        // Sem balão: o braço erguido chega a ~-20 e ~142 em x, o pulo a ~-12 em y.
        if (bubble == null) { qx = -22f; qy = -16f; qw = 164f; qh = 132f } else { qx = -30f; qy = -30f; qw = 180f; qh = 150f }
        val esc = minOf(b.width() / qw, b.height() / qh)
        c.save()
        c.translate(b.left + (b.width() - qw * esc) / 2f - qx * esc, b.top + (b.height() - qh * esc) / 2f - qy * esc)
        c.scale(esc, esc)
        val a = alpha255
        val laranja = SofalyLogo.ORANGE

        val body = SofyMotion.poseAt(clip, Part.BODY, ms)
        c.save(); aplicar(c, Part.BODY, body)

        // Antenas (atrás da tela) e o pino.
        val ant = clip.antennas
        stroke.color = laranja; stroke.alpha = a; stroke.strokeWidth = 8.5f
        c.save(); aplicar(c, Part.ANTENNA_LEFT, SofyMotion.poseAt(clip, Part.ANTENNA_LEFT, ms))
        c.drawLine(60f, 36f, ant.lx, ant.ly, stroke); c.restore()
        c.save(); aplicar(c, Part.ANTENNA_RIGHT, SofyMotion.poseAt(clip, Part.ANTENNA_RIGHT, ms))
        if (ant.rightCurl) {
            path.reset(); path.moveTo(60f, 36f); path.quadTo(74f, 6f, 84f, 12f); path.quadTo(92f, 18f, 84f, 24f); path.quadTo(78f, 28f, 80f, 34f)
            c.drawPath(path, stroke)
        } else c.drawLine(60f, 36f, ant.rx, ant.ry, stroke)
        c.restore()
        fill.color = laranja; fill.alpha = a
        r.set(51f, 29f, 69f, 47f); c.drawArc(r, 180f, 180f, true, fill)

        // Pés e assento.
        desenharPe(c, Part.FOOT_LEFT, true, a)
        desenharPe(c, Part.FOOT_RIGHT, false, a)
        fill.color = laranja; fill.alpha = a
        r.set(12f, 86f, 108f, 99.5f); c.drawRoundRect(r, 5f, 5f, fill)

        // Tela: miolo e moldura.
        fill.color = SofalyLogo.SCREEN; fill.alpha = a
        r.set(23.75f, 35.25f, 96.25f, 92.75f); c.drawRoundRect(r, 15.75f, 15.75f, fill)
        stroke.strokeWidth = 13.5f; stroke.color = laranja; stroke.alpha = a
        c.drawRoundRect(r, 15.75f, 15.75f, stroke)

        // Rosto escrito na tela (a boca só quando a expressão tem).
        val face = SofyMotion.faceAt(clip, ms).let { if (talking) it.copy(mouth = SofyMotion.talkingMouth(aberta)) else it }
        glyph.alpha = a
        glyph.textSize = if (face.eyes.length > 3) 20f else 22f
        glyph.letterSpacing = 0.1f
        c.drawText(face.eyes, 60f, if (face.mouth.isEmpty()) 70f else 67f, glyph)
        if (face.mouth.isNotEmpty()) { glyph.textSize = 12f; c.drawText(face.mouth, 60f, 82f, glyph) }

        // Braços por cima da tela (assim a mão no "queixo" aparece na frente).
        desenharBraco(c, Part.ARM_LEFT, 3.5f, a)
        desenharBraco(c, Part.ARM_RIGHT, 96f, a)

        c.restore()   // corpo

        bubble?.let { desenharBalao(c, it, a) }
        c.restore()
    }

    private fun aplicar(c: Canvas, p: Part, k: SofyMotion.Key) {
        c.translate(k.tx, k.ty)
        c.rotate(k.rot, p.pivotX, p.pivotY)
        if (k.sx != 1f || k.sy != 1f) c.scale(k.sx, k.sy, p.pivotX, p.pivotY)
    }

    private fun desenharBraco(c: Canvas, p: Part, x: Float, a: Int) {
        c.save(); aplicar(c, p, SofyMotion.poseAt(clip, p, ms))
        fill.color = SofalyLogo.ORANGE; fill.alpha = a
        r.set(x, 59f, x + 20.5f, 99.5f); c.drawRoundRect(r, 9.5f, 9.5f, fill)
        c.restore()
    }

    private fun desenharPe(c: Canvas, p: Part, esquerdo: Boolean, a: Int) {
        c.save(); aplicar(c, p, SofyMotion.poseAt(clip, p, ms))
        fill.color = SofalyLogo.ORANGE; fill.alpha = a
        path.reset()
        if (esquerdo) {
            path.moveTo(12.5f, 92f); path.lineTo(24.5f, 92f); path.lineTo(17f, 108.8f); path.quadTo(16f, 111f, 13.6f, 111f)
            path.lineTo(11.4f, 111f); path.quadTo(9f, 111f, 9.3f, 108.6f); path.close()
        } else {
            path.moveTo(107.5f, 92f); path.lineTo(95.5f, 92f); path.lineTo(103f, 108.8f); path.quadTo(104f, 111f, 106.4f, 111f)
            path.lineTo(108.6f, 111f); path.quadTo(111f, 111f, 110.7f, 108.6f); path.close()
        }
        c.drawPath(path, fill)
        c.restore()
    }

    private fun desenharBalao(c: Canvas, texto: String, a: Int) {
        val w = maxOf(30f, texto.length * 10f + 16f)
        fill.color = SofalyLogo.CREAM; fill.alpha = a
        r.set(100f - w / 2f, -20f, 100f + w / 2f, 4f); c.drawRoundRect(r, 12f, 12f, fill)
        path.reset(); path.moveTo(94f, 3f); path.lineTo(92f, 12f); path.lineTo(101f, 3f); path.close(); c.drawPath(path, fill)
        glyph.color = DesignTokens.ORANGE_ON_LIGHT; glyph.alpha = a; glyph.textSize = 15f; glyph.letterSpacing = 0f
        c.drawText(texto, 100f, -2.5f, glyph)
        glyph.color = SofalyLogo.CREAM
    }

    override fun setAlpha(alpha: Int) { alpha255 = alpha; invalidateSelf() }
    override fun setColorFilter(cf: ColorFilter?) { fill.colorFilter = cf; stroke.colorFilter = cf; glyph.colorFilter = cf }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        const val QUOTE = "“ ”"
        const val MUSIC = "♪"
    }
}
