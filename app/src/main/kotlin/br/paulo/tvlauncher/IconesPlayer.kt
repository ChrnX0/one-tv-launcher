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
 * Ícones da lista de canais e da grade da TV ao vivo (ideia 17, 14/11/2026): mesmo traço do sistema de design
 * ([IconeApp], [IconeMidia]): quadro 24×24, linha 1,6 com pontas redondas, desenhados em código. Substituem os símbolos
 * de texto (★ ↺ ☰ ▦ 🔒), que dependiam da fonte de reserva da TV e saíam cada um num tamanho (o 🔒 vinha colorido).
 * A lupa da busca é a [IconeMidia.Tipo.LUPA], a mesma da tela "Na rede".
 */
class IconePlayer(private val tipo: Tipo, cor: Int = Estilo.TEXTO) : Drawable() {
    enum class Tipo { ESTRELA, RECENTES, FILTROS, GRADE, CADEADO,
        // Coluna nova do player (14/11/2026): Continuar, Assistidos, Todos, Esportes e "Mostrar ocultas".
        PLAY, VISTO, TODOS, ESPORTE, OCULTO }

    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = cor
    }
    private val cheio = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = cor; strokeJoin = Paint.Join.ROUND }
    private val p = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        when (tipo) {
            Tipo.ESTRELA -> {   // favorito: estrela de 5 pontas cheia (como a ★ de antes)
                p.reset()
                for (k in 0 until 10) {
                    val raio = if (k % 2 == 0) 9.5 else 4.1
                    val a = -PI / 2 + k * PI / 5
                    val x = (12 + raio * cos(a)).toFloat(); val y = (12.8 + raio * sin(a)).toFloat()
                    if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                p.close()
                c.drawPath(p, cheio)
            }
            Tipo.RECENTES -> {   // relógio com a seta voltando (histórico)
                c.drawArc(RectF(4f, 4f, 20f, 20f), -150f, 300f, false, traco)
                p.reset(); p.moveTo(4.4f, 4.6f); p.lineTo(5.1f, 8f); p.lineTo(8.5f, 7.4f)
                c.drawPath(p, traco)
                p.reset(); p.moveTo(12f, 8f); p.lineTo(12f, 12f); p.lineTo(14.8f, 13.7f)
                c.drawPath(p, traco)
            }
            Tipo.FILTROS -> {   // três réguas com botão (ajustes)
                for ((y, x) in listOf(7f to 9f, 12f to 15f, 17f to 10.5f)) {
                    c.drawLine(4f, y, 20f, y, traco)
                    c.drawCircle(x, y, 2.1f, cheio)
                }
            }
            Tipo.GRADE -> {   // guia em grade: moldura, faixa de horas e blocos de programa
                c.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2.5f, 2.5f, traco)
                c.drawLine(3f, 9.5f, 21f, 9.5f, traco)
                c.drawLine(9f, 9.5f, 9f, 19f, traco)
                c.drawLine(9f, 14.25f, 21f, 14.25f, traco)
                c.drawLine(15f, 9.5f, 15f, 14.25f, traco)
            }
            Tipo.CADEADO -> {   // categoria oculta que está aparecendo (depois do PIN)
                c.drawRoundRect(RectF(5.5f, 10.5f, 18.5f, 20f), 2.2f, 2.2f, traco)
                p.reset(); p.moveTo(8.5f, 10.5f); p.lineTo(8.5f, 7.5f)
                p.arcTo(RectF(8.5f, 4f, 15.5f, 11f), 180f, 180f, false); p.lineTo(15.5f, 10.5f)
                c.drawPath(p, traco)
                c.drawCircle(12f, 15.2f, 1.3f, cheio)
            }
            Tipo.PLAY -> {   // continuar: triângulo cheio
                p.reset(); p.moveTo(8f, 5.5f); p.lineTo(19f, 12f); p.lineTo(8f, 18.5f); p.close()
                c.drawPath(p, cheio)
            }
            Tipo.VISTO -> {   // assistidos: visto
                p.reset(); p.moveTo(5f, 12.5f); p.lineTo(10f, 17.5f); p.lineTo(19.5f, 7f)
                c.drawPath(p, traco)
            }
            Tipo.TODOS -> {   // todos: quatro quadrados
                for ((x, y) in listOf(4.5f to 4.5f, 13f to 4.5f, 4.5f to 13f, 13f to 13f)) c.drawRoundRect(RectF(x, y, x + 6.5f, y + 6.5f), 1.5f, 1.5f, traco)
            }
            Tipo.ESPORTE -> {   // esportes: bola com as costuras de uma bola de vôlei/futebol estilizada
                c.drawCircle(12f, 12f, 8.5f, traco)
                c.drawCircle(12f, 12f, 2.6f, cheio)
                for (k in 0 until 5) {
                    val a = -PI / 2 + k * 2 * PI / 5
                    c.drawLine((12 + 2.6 * cos(a)).toFloat(), (12 + 2.6 * sin(a)).toFloat(), (12 + 8.5 * cos(a)).toFloat(), (12 + 8.5 * sin(a)).toFloat(), traco)
                }
            }
            Tipo.OCULTO -> {   // mostrar ocultas: olho riscado
                p.reset(); p.moveTo(3f, 12f); p.quadTo(12f, 3.5f, 21f, 12f); p.quadTo(12f, 20.5f, 3f, 12f)
                c.drawPath(p, traco)
                c.drawCircle(12f, 12f, 2.8f, traco)
                c.drawLine(4.5f, 19.5f, 19.5f, 4.5f, traco)
            }
        }
        c.restore()
    }

    override fun setAlpha(alpha: Int) { traco.alpha = alpha; cheio.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { traco.colorFilter = cf; cheio.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
