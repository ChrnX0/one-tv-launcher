package br.paulo.tvlauncher.home

import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.player.IconePlayer

/**
 * Ícones das ações da ficha de filme e série (coluna fina, 16/11/2026): mesmo traço de [IconePlayer] (quadro 24×24,
 * linha 1,6, pontas redondas), desenhados em código para não depender da fonte de reserva da TV. A cor segue o foco do
 * botão: [setTintList] com um ColorStateList (escuro na faixa branca do foco, claro fora dela).
 */
class IconeFicha(private val tipo: Tipo) : Drawable() {
    enum class Tipo { PLAY, COMECO, EPISODIOS, TEMPORADAS, TRAILER, ELENCO, VERSAO, COLECAO, SINOPSE, NAO_E_ESTE, ONDE }

    private var cores: ColorStateList? = null
    private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1.6f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = Estilo.TEXTO
    }
    private val cheio = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = Estilo.TEXTO }
    private val p = Path()

    override fun draw(c: Canvas) {
        val b = bounds
        c.save()
        c.translate(b.left.toFloat(), b.top.toFloat())
        c.scale(b.width() / 24f, b.height() / 24f)
        when (tipo) {
            Tipo.PLAY -> { p.reset(); p.moveTo(7.5f, 5f); p.lineTo(19f, 12f); p.lineTo(7.5f, 19f); p.close(); c.drawPath(p, cheio) }
            Tipo.COMECO -> {   // do começo: barra e triângulo virado para ela
                c.drawLine(6f, 5.5f, 6f, 18.5f, traco)
                p.reset(); p.moveTo(18.5f, 5.5f); p.lineTo(9f, 12f); p.lineTo(18.5f, 18.5f); p.close(); c.drawPath(p, cheio)
            }
            Tipo.EPISODIOS -> {   // lista: três pontos e três linhas
                for (y in listOf(6.5f, 12f, 17.5f)) { c.drawCircle(5f, y, 1.3f, cheio); c.drawLine(9f, y, 20f, y, traco) }
            }
            Tipo.TEMPORADAS -> {   // pilha de cartões
                c.drawRoundRect(RectF(4f, 9f, 17f, 20f), 2f, 2f, traco)
                p.reset(); p.moveTo(7f, 6f); p.lineTo(18f, 6f); p.quadTo(20f, 6f, 20f, 8f); p.lineTo(20f, 16.5f); c.drawPath(p, traco)
            }
            Tipo.TRAILER -> {   // tela com o play
                c.drawRoundRect(RectF(3f, 5f, 21f, 19f), 2.5f, 2.5f, traco)
                p.reset(); p.moveTo(10f, 9f); p.lineTo(15f, 12f); p.lineTo(10f, 15f); p.close(); c.drawPath(p, cheio)
            }
            Tipo.ELENCO -> {   // duas pessoas
                c.drawCircle(9f, 8.5f, 3.2f, traco)
                p.reset(); p.moveTo(3f, 19.5f); p.quadTo(3f, 14f, 9f, 14f); p.quadTo(15f, 14f, 15f, 19.5f); c.drawPath(p, traco)
                c.drawCircle(16.5f, 9f, 2.6f, traco)
                p.reset(); p.moveTo(16.5f, 14f); p.quadTo(21f, 14.3f, 21f, 19f); c.drawPath(p, traco)
            }
            Tipo.VERSAO -> {   // trocar: duas setas
                c.drawLine(4f, 8.5f, 19f, 8.5f, traco); p.reset(); p.moveTo(15.5f, 5f); p.lineTo(19f, 8.5f); p.lineTo(15.5f, 12f); c.drawPath(p, traco)
                c.drawLine(20f, 15.5f, 5f, 15.5f, traco); p.reset(); p.moveTo(8.5f, 12f); p.lineTo(5f, 15.5f); p.lineTo(8.5f, 19f); c.drawPath(p, traco)
            }
            Tipo.COLECAO -> {   // três capas lado a lado
                for (x in listOf(3.5f, 9.5f, 15.5f)) c.drawRoundRect(RectF(x, 6f, x + 5f, 18f), 1.2f, 1.2f, traco)
            }
            Tipo.SINOPSE -> {   // texto: linhas, a última curta
                for ((y, f) in listOf(6f to 20f, 10f to 20f, 14f to 20f, 18f to 13f)) c.drawLine(4f, y, f, y, traco)
            }
            Tipo.NAO_E_ESTE -> {   // círculo com X
                c.drawCircle(12f, 12f, 8.5f, traco); c.drawLine(9f, 9f, 15f, 15f, traco); c.drawLine(15f, 9f, 9f, 15f, traco)
            }
            Tipo.ONDE -> {   // lupa
                c.drawCircle(10.5f, 10.5f, 6.5f, traco); c.drawLine(15.2f, 15.2f, 20f, 20f, traco)
            }
        }
        c.restore()
    }

    override fun setTintList(tint: ColorStateList?) { cores = tint; aplicarCor(state) }
    override fun isStateful() = cores?.isStateful == true
    override fun onStateChange(state: IntArray): Boolean = aplicarCor(state)

    private fun aplicarCor(state: IntArray): Boolean {
        val cor = cores?.getColorForState(state, cores!!.defaultColor) ?: return false
        if (cor == traco.color) return false
        traco.color = cor; cheio.color = cor; invalidateSelf(); return true
    }

    override fun setAlpha(alpha: Int) { traco.alpha = alpha; cheio.alpha = alpha }
    override fun setColorFilter(cf: ColorFilter?) { traco.colorFilter = cf; cheio.colorFilter = cf }
    @Deprecated("Deprecated in Java") override fun getOpacity() = PixelFormat.TRANSLUCENT
}
