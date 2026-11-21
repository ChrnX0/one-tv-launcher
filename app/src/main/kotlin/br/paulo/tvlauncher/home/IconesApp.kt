package br.paulo.tvlauncher.home

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
    enum class Tipo { TV, SERIES, FILMES, REDE, MICROFONE, TIMER, SEM_REDE, AVISO, BUSCA, JOGOS, OUVIR }

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
            Tipo.REDE -> {   // símbolo USB ("Rede", pedido do Paulo 20/11/2026; antes uma pasta "Do computador")
                c.drawLine(12f, 18f, 12f, 5.5f, traco)
                p.reset(); p.moveTo(12f, 2.2f); p.lineTo(14.3f, 5.8f); p.lineTo(9.7f, 5.8f); p.close(); c.drawPath(p, cheio)
                p.reset(); p.moveTo(12f, 15f); p.lineTo(6.5f, 11.8f); p.lineTo(6.5f, 9.4f); c.drawPath(p, traco)
                c.drawCircle(6.5f, 7.8f, 1.6f, cheio)
                p.reset(); p.moveTo(12f, 12.6f); p.lineTo(17.5f, 9.6f); p.lineTo(17.5f, 7.9f); c.drawPath(p, traco)
                c.drawRect(16.1f, 5f, 18.9f, 7.8f, cheio)
                c.drawCircle(12f, 19.8f, 2.1f, cheio)
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
            Tipo.JOGOS -> {   // controle de videogame: corpo com duas alças, cruz à esquerda e dois botões à direita
                p.reset()
                p.moveTo(7f, 7f); p.lineTo(17f, 7f)
                p.quadTo(20.5f, 7f, 21.3f, 11f); p.lineTo(22f, 15.5f)
                p.quadTo(22.4f, 18.5f, 20f, 18.5f); p.quadTo(18.4f, 18.5f, 17f, 16.5f); p.lineTo(16f, 15f); p.lineTo(8f, 15f)
                p.lineTo(7f, 16.5f); p.quadTo(5.6f, 18.5f, 4f, 18.5f); p.quadTo(1.6f, 18.5f, 2f, 15.5f)
                p.lineTo(2.7f, 11f); p.quadTo(3.5f, 7f, 7f, 7f); p.close()
                c.drawPath(p, traco)
                c.drawLine(5.5f, 11f, 9.5f, 11f, traco); c.drawLine(7.5f, 9f, 7.5f, 13f, traco)
                c.drawCircle(15.5f, 11.8f, 1f, cheio); c.drawCircle(18f, 10f, 1f, cheio)
            }
            Tipo.OUVIR -> {   // fone de ouvido: arco por cima e as duas conchas
                c.drawArc(RectF(4f, 4f, 20f, 20f), 180f, 180f, false, traco)
                c.drawLine(4f, 12f, 4f, 15f, traco); c.drawLine(20f, 12f, 20f, 15f, traco)
                c.drawRoundRect(RectF(3f, 13f, 8f, 20.5f), 2f, 2f, cheio)
                c.drawRoundRect(RectF(16f, 13f, 21f, 20.5f), 2f, 2f, cheio)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { traco.alpha = alpha; cheio.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { traco.colorFilter = cf; cheio.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
