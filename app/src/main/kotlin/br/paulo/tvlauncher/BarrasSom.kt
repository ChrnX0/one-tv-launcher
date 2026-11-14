package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.View

/**
 * Ideia 75 (14/11/2026): as três barrinhas da pílula "Tocando agora". Mexem só enquanto toca; pausado, ficam baixas e
 * paradas (nenhum quadro pedido). Herdam o foco da pílula ([isDuplicateParentStateEnabled]): no branco do foco, o
 * verde-água escurece, como o resto do texto.
 */
class BarrasSom(ctx: Context) : View(ctx) {
    private val pincel = Paint(Paint.ANTI_ALIAS_FLAG)
    private val r = RectF()
    private val inicio = SystemClock.uptimeMillis()

    var tocando = false
        set(v) { if (field != v) { field = v; invalidate() } }

    init { isDuplicateParentStateEnabled = true }

    override fun drawableStateChanged() { super.drawableStateChanged(); invalidate() }

    override fun onDraw(c: Canvas) {
        val focado = drawableState.contains(android.R.attr.state_focused)
        pincel.color = if (focado) Estilo.ACENTO_NO_FOCO else Estilo.ACENTO
        val n = 3
        val vao = width / (n * 2f + (n - 1) * 1f)   // barra = 2 partes, intervalo = 1
        val larg = vao * 2
        val t = (SystemClock.uptimeMillis() - inicio) / 1000.0
        for (i in 0 until n) {
            // Alturas desencontradas (frequências diferentes por barra); paradas, 30% da altura.
            val f = if (tocando) 0.3 + 0.7 * (0.5 + 0.5 * Math.sin(t * (5.0 + i * 1.7) + i * 1.3)) else 0.3
            val h = (height * f).toFloat()
            val x = i * (larg + vao)
            r.set(x, height - h, x + larg, height.toFloat())
            c.drawRoundRect(r, larg / 2, larg / 2, pincel)
        }
        // ~15 quadros por segundo bastam para barrinhas de 14 dp; só com a pílula na tela e tocando.
        if (tocando && isShown) postInvalidateDelayed(66)
    }
}
