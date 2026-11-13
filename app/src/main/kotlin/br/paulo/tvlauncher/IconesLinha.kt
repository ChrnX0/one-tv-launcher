package br.paulo.tvlauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Ícones de linha do protetor (10/11/2026, dono: "tem que melhorar esses ícones" — os emojis destoavam do vidro e da
 * Manrope). Desenhados em código, num quadro de 24×24 unidades, traço branco de 1,6 com pontas redondas: ficam nítidos
 * em qualquer tamanho, sem arquivo de imagem nem licença de terceiros.
 *
 * A lua é desenhada na fase de verdade ([fracaoLua]: 0 = nova, 0,5 = cheia), espelhada no hemisfério sul.
 */
class IconeLinha(private val tipo: Tipo, private val fracaoLua: Double = 0.0, private val sul: Boolean = false,
                 private val cor: Int = 0xFFFFFFFF.toInt()) : Drawable() {
    enum class Tipo { HORIZONTE, LUA, GOTA, CHUVA, CALENDARIO, DESPERTADOR, RECADO }

    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = cor
    }
    private val cheio = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = cor }
    private val p = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        when (tipo) {
            Tipo.HORIZONTE -> {
                // Sol nascendo: meio disco sobre a linha do horizonte, com raios.
                c.drawLine(2.5f, 18f, 21.5f, 18f, traco)
                c.drawArc(RectF(7f, 13f, 17f, 23f), 180f, 180f, false, traco)
                for (a in listOf(180.0, 225.0, 270.0, 315.0, 360.0)) {
                    val r = a * PI / 180
                    c.drawLine((12 + 7.5 * cos(r)).toFloat(), (18 + 7.5 * sin(r)).toFloat(),
                        (12 + 9.5 * cos(r)).toFloat(), (18 + 9.5 * sin(r)).toFloat(), traco)
                }
            }
            Tipo.LUA -> lua(c)
            Tipo.GOTA -> {
                p.reset(); p.moveTo(12f, 3f)
                p.cubicTo(9f, 7.5f, 6f, 11f, 6f, 14.5f)
                p.arcTo(RectF(6f, 8.5f, 18f, 20.5f), 180f, -180f, false)
                p.cubicTo(18f, 11f, 15f, 7.5f, 12f, 3f)
                c.drawPath(p, traco)
            }
            Tipo.CHUVA -> {
                // Guarda-chuva: cúpula, cabo e o gancho.
                p.reset(); p.moveTo(3.5f, 12f)
                p.arcTo(RectF(3.5f, 3.5f, 20.5f, 20.5f), 180f, 180f, false)
                p.quadTo(18.25f, 10f, 16.25f, 12f); p.quadTo(14.1f, 10f, 12f, 12f)
                p.quadTo(9.9f, 10f, 7.75f, 12f); p.quadTo(5.75f, 10f, 3.5f, 12f)
                c.drawPath(p, traco)
                c.drawLine(12f, 12f, 12f, 18.5f, traco)
                c.drawArc(RectF(8f, 16.5f, 12f, 20.5f), 0f, 180f, false, traco)
            }
            Tipo.CALENDARIO -> {
                c.drawRoundRect(RectF(4f, 5.5f, 20f, 20f), 2.5f, 2.5f, traco)
                c.drawLine(4f, 10f, 20f, 10f, traco)
                c.drawLine(8.5f, 3.5f, 8.5f, 7f, traco); c.drawLine(15.5f, 3.5f, 15.5f, 7f, traco)
                c.drawCircle(9f, 14.5f, 1.1f, cheio)
            }
            Tipo.RECADO -> {
                // Balão de conversa com três pontos.
                c.drawRoundRect(RectF(3.5f, 4.5f, 20.5f, 16.5f), 4f, 4f, traco)
                p.reset(); p.moveTo(8f, 16.5f); p.lineTo(7f, 20.5f); p.lineTo(12f, 16.5f); c.drawPath(p, traco)
                for (x in listOf(8.5f, 12f, 15.5f)) c.drawCircle(x, 10.5f, 1.1f, cheio)
            }
            Tipo.DESPERTADOR -> {
                c.drawCircle(12f, 13f, 7f, traco)
                c.drawLine(12f, 13f, 12f, 9.5f, traco); c.drawLine(12f, 13f, 14.5f, 14.5f, traco)
                c.drawLine(4f, 6f, 6.5f, 3.5f, traco); c.drawLine(20f, 6f, 17.5f, 3.5f, traco)
                c.drawLine(7.5f, 19.5f, 6f, 21f, traco); c.drawLine(16.5f, 19.5f, 18f, 21f, traco)
            }
        }
        c.restore()
    }

    /**
     * Lua: o contorno do disco e, cheia de branco, a parte iluminada. A parte acesa vai da borda (um semicírculo) até o
     * "terminador", uma meia elipse de largura r·cos(2π·fase): positiva = crescente fino, negativa = gibosa.
     * Crescendo, a luz fica à direita (hemisfério norte); minguando ou no sul, à esquerda.
     */
    private fun lua(c: Canvas) {
        val r = 8f; val cx = 12f; val cy = 12f
        c.drawCircle(cx, cy, r, traco.apply { alpha = 0x80 })
        traco.alpha = 0xFF
        val f = ((fracaoLua % 1.0) + 1.0) % 1.0
        val crescendo = f < 0.5
        val ladoDireito = crescendo != sul
        val k = cos(2 * PI * f).toFloat()   // 1 = nova, -1 = cheia
        val s = if (ladoDireito) 1f else -1f
        p.reset()
        val passos = 24
        for (i in 0..passos) {   // borda acesa, de cima para baixo
            val t = (-PI / 2 + PI * i / passos)
            val x = cx + s * r * cos(t).toFloat(); val y = cy + r * sin(t).toFloat()
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        for (i in passos downTo 0) {   // terminador, de baixo para cima
            val t = (-PI / 2 + PI * i / passos)
            p.lineTo(cx + s * k * r * cos(t).toFloat(), cy + r * sin(t).toFloat())
        }
        p.close()
        c.drawPath(p, cheio)
    }

    override fun setAlpha(alpha: Int) { traco.alpha = alpha; cheio.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { traco.colorFilter = cf; cheio.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
