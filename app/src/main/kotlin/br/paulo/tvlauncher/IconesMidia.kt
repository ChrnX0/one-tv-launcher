package br.paulo.tvlauncher

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable

/**
 * Ícones de linha da tela "Na rede" (10/11/2026): mesmo traço do sistema de design ([IconeApp], [IconeLinha]): quadro
 * 24×24, linha 1,6 com pontas redondas. Substituem os emojis (📂 🟣 🖥 🎬 🎵 🖼), que saíam coloridos e cada um num estilo.
 * Arquivo separado do IconeApp para não disputar o arquivo do sistema de design com outra frente.
 */
class IconeMidia(private val tipo: Tipo, cor: Int = Estilo.TEXTO) : Drawable() {
    enum class Tipo { PASTA, VIDEO, MUSICA, FOTO, ARQUIVO, SERVIDOR, COMPUTADOR, NUVEM, LINK, MAIS, ATUALIZAR, CONTINUAR }

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
            Tipo.PASTA -> {
                p.reset(); p.moveTo(3f, 7.5f); p.quadTo(3f, 5f, 5.5f, 5f); p.lineTo(9.5f, 5f); p.lineTo(11.5f, 7f)
                p.lineTo(18.5f, 7f); p.quadTo(21f, 7f, 21f, 9.5f); p.lineTo(21f, 16.5f); p.quadTo(21f, 19f, 18.5f, 19f)
                p.lineTo(5.5f, 19f); p.quadTo(3f, 19f, 3f, 16.5f); p.close()
                c.drawPath(p, traco)
            }
            Tipo.VIDEO -> {   // tela com "play"
                c.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2.5f, 2.5f, traco)
                p.reset(); p.moveTo(10f, 9f); p.lineTo(15f, 12f); p.lineTo(10f, 15f); p.close()
                c.drawPath(p, cheio)
            }
            Tipo.MUSICA -> {
                c.drawCircle(7.5f, 17.5f, 2.5f, traco); c.drawCircle(17.5f, 15.5f, 2.5f, traco)
                p.reset(); p.moveTo(10f, 17.5f); p.lineTo(10f, 5.5f); p.lineTo(20f, 3.5f); p.lineTo(20f, 15.5f)
                c.drawPath(p, traco)
            }
            Tipo.FOTO -> {
                c.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2.5f, 2.5f, traco)
                c.drawCircle(8.5f, 9.5f, 1.5f, traco)
                p.reset(); p.moveTo(3.5f, 17f); p.lineTo(9f, 12.5f); p.lineTo(13f, 15.5f); p.lineTo(16f, 13f); p.lineTo(20.5f, 16.5f)
                c.drawPath(p, traco)
            }
            Tipo.ARQUIVO -> {
                p.reset(); p.moveTo(6f, 3f); p.lineTo(14f, 3f); p.lineTo(19f, 8f); p.lineTo(19f, 21f); p.lineTo(6f, 21f); p.close()
                c.drawPath(p, traco)
                p.reset(); p.moveTo(14f, 3f); p.lineTo(14f, 8f); p.lineTo(19f, 8f)
                c.drawPath(p, traco)
            }
            Tipo.SERVIDOR -> {   // dois servidores (como o card "Na rede" da HOME)
                c.drawRoundRect(RectF(4f, 4f, 20f, 10.5f), 2f, 2f, traco)
                c.drawRoundRect(RectF(4f, 13.5f, 20f, 20f), 2f, 2f, traco)
                c.drawCircle(7.5f, 7.25f, 1f, cheio); c.drawCircle(7.5f, 16.75f, 1f, cheio)
            }
            Tipo.COMPUTADOR -> {
                c.drawRoundRect(RectF(3f, 4f, 21f, 16f), 2f, 2f, traco)
                c.drawLine(12f, 16f, 12f, 20f, traco); c.drawLine(8f, 20f, 16f, 20f, traco)
            }
            Tipo.NUVEM -> {
                p.reset(); p.moveTo(7f, 18f); p.quadTo(3f, 18f, 3f, 14.5f); p.quadTo(3f, 11f, 6.8f, 11f)
                p.quadTo(7.5f, 6f, 12f, 6f); p.quadTo(16.3f, 6f, 17.2f, 10f); p.quadTo(21f, 10.3f, 21f, 14f)
                p.quadTo(21f, 18f, 17f, 18f); p.close()
                c.drawPath(p, traco)
            }
            Tipo.LINK -> {   // dois elos
                c.save(); c.rotate(-45f, 12f, 12f)
                c.drawRoundRect(RectF(2.5f, 9f, 12.5f, 15f), 3f, 3f, traco)
                c.drawRoundRect(RectF(11.5f, 9f, 21.5f, 15f), 3f, 3f, traco)
                c.restore()
            }
            Tipo.MAIS -> {
                c.drawLine(12f, 5f, 12f, 19f, traco); c.drawLine(5f, 12f, 19f, 12f, traco)
            }
            Tipo.ATUALIZAR -> {
                c.drawArc(RectF(5f, 5f, 19f, 19f), -60f, 300f, false, traco)
                p.reset(); p.moveTo(15.5f, 3.5f); p.lineTo(16.7f, 6.4f); p.lineTo(13.6f, 7.2f)
                c.drawPath(p, traco)
            }
            Tipo.CONTINUAR -> {   // círculo com "play"
                c.drawCircle(12f, 12f, 8.5f, traco)
                p.reset(); p.moveTo(10f, 8.5f); p.lineTo(15.5f, 12f); p.lineTo(10f, 15.5f); p.close()
                c.drawPath(p, cheio)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { traco.alpha = alpha; cheio.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { traco.colorFilter = cf; cheio.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
