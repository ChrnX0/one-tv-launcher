package br.paulo.tvlauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Ícones de linha do resto do app (sistema de design, 10/11/2026): mesmo traço dos ícones do protetor ([IconeLinha]):
 * quadro de 24×24, linha de 1,6 com pontas redondas, desenhados em código. Substituem os emojis (📺 🎞 🎬 🗄 🎙 ⏱),
 * que saíam coloridos e em outro estilo, cada um de um jeito.
 *
 * Ficam num arquivo separado do [IconeLinha] só para não mexer no arquivo do protetor (outra frente de trabalho).
 */
class IconeApp(private val tipo: Tipo, private val cor: Int = 0xFFFFFFFF.toInt()) : Drawable() {
    enum class Tipo { TV, SERIES, FILMES, REDE, MICROFONE, TIMER, SEM_REDE, AVISO, BUSCA }

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
            Tipo.TV -> {   // tela com antena
                c.drawRoundRect(RectF(3f, 6.5f, 21f, 18.5f), 2.5f, 2.5f, traco)
                c.drawLine(9f, 2.5f, 12f, 6.5f, traco); c.drawLine(15f, 2.5f, 12f, 6.5f, traco)
                c.drawLine(8f, 21f, 16f, 21f, traco)
            }
            Tipo.SERIES -> {   // dois quadros empilhados (episódios)
                c.drawRoundRect(RectF(3f, 7f, 17f, 19f), 2.5f, 2.5f, traco)
                p.reset(); p.moveTo(7f, 4f); p.lineTo(18.5f, 4f); p.quadTo(21f, 4f, 21f, 6.5f); p.lineTo(21f, 15f)
                c.drawPath(p, traco)
                p.reset(); p.moveTo(8.5f, 10f); p.lineTo(12.5f, 13f); p.lineTo(8.5f, 16f); p.close()
                c.drawPath(p, cheio)
            }
            Tipo.FILMES -> {   // claquete
                c.drawRoundRect(RectF(3f, 9f, 21f, 20f), 2.5f, 2.5f, traco)
                p.reset(); p.moveTo(3.5f, 9f); p.lineTo(20f, 4.5f)
                c.drawPath(p, traco)
                c.drawLine(8.5f, 7.6f, 10.5f, 9f, traco); c.drawLine(14f, 6.1f, 16f, 7.6f, traco)
            }
            Tipo.REDE -> {   // dois servidores
                c.drawRoundRect(RectF(4f, 4f, 20f, 10.5f), 2f, 2f, traco)
                c.drawRoundRect(RectF(4f, 13.5f, 20f, 20f), 2f, 2f, traco)
                c.drawCircle(7.5f, 7.25f, 1f, cheio); c.drawCircle(7.5f, 16.75f, 1f, cheio)
            }
            Tipo.MICROFONE -> {
                c.drawRoundRect(RectF(9f, 3f, 15f, 14f), 3f, 3f, traco)
                c.drawArc(RectF(5.5f, 5.5f, 18.5f, 17.5f), 0f, 180f, false, traco)
                c.drawLine(12f, 17.5f, 12f, 21f, traco)
            }
            Tipo.TIMER -> {   // cronômetro
                c.drawCircle(12f, 13.5f, 7.5f, traco)
                c.drawLine(12f, 13.5f, 12f, 9.5f, traco)
                c.drawLine(10f, 2.5f, 14f, 2.5f, traco); c.drawLine(12f, 2.5f, 12f, 6f, traco)
                c.drawLine(18.5f, 6f, 20f, 4.5f, traco)
            }
            Tipo.SEM_REDE -> {   // Wi-Fi riscado
                c.drawArc(RectF(2.5f, 5f, 21.5f, 24f), 225f, 90f, false, traco)
                c.drawArc(RectF(6f, 9f, 18f, 21f), 225f, 90f, false, traco)
                c.drawCircle(12f, 18.5f, 1.1f, cheio)
                c.drawLine(4f, 4f, 20f, 20f, traco)
            }
            Tipo.AVISO -> {   // círculo com "!"
                c.drawCircle(12f, 12f, 8.5f, traco)
                c.drawLine(12f, 7.5f, 12f, 13f, traco)
                c.drawCircle(12f, 16.3f, 1.1f, cheio)
            }
            Tipo.BUSCA -> {   // lupa
                c.drawCircle(10.5f, 10.5f, 6.5f, traco)
                c.drawLine(15.3f, 15.3f, 20.5f, 20.5f, traco)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { traco.alpha = alpha; cheio.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { traco.colorFilter = cf; cheio.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
