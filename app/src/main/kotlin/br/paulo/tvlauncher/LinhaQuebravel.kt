package br.paulo.tvlauncher

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Fileira que quebra linha: os filhos vão lado a lado e, quando não cabem, descem para a linha de baixo (como palavras).
 * Usada pela barra de dicas do controle ([Estilo.barraDicas]): numa coluna estreita (Filmes, Séries) as 4 dicas não
 * cabiam numa linha e a última sumia cortada. Respeita a direção da língua (árabe começa pela direita).
 * Cada filho é medido com WRAP_CONTENT; a margem entre itens vem de [vao] (horizontal) e [vaoLinha] (vertical).
 */
class LinhaQuebravel(ctx: Context, private val vao: Int, private val vaoLinha: Int) : ViewGroup(ctx) {

    override fun onMeasure(larg: Int, alt: Int) {
        val max = MeasureSpec.getSize(larg).takeIf { MeasureSpec.getMode(larg) != MeasureSpec.UNSPECIFIED } ?: Int.MAX_VALUE
        var x = 0; var y = 0; var alturaLinha = 0; var maiorX = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > max) { y += alturaLinha + vaoLinha; x = 0; alturaLinha = 0 }
            x += c.measuredWidth + vao
            maiorX = maxOf(maiorX, x - vao)
            alturaLinha = maxOf(alturaLinha, c.measuredHeight)
        }
        setMeasuredDimension(resolveSize(maiorX + paddingLeft + paddingRight, larg), resolveSize(y + alturaLinha + paddingTop + paddingBottom, alt))
    }

    override fun onLayout(mudou: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val largura = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = 0; var y = 0; var alturaLinha = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > largura) { y += alturaLinha + vaoLinha; x = 0; alturaLinha = 0 }
            val esq = if (rtl) paddingLeft + largura - x - c.measuredWidth else paddingLeft + x
            val topo = paddingTop + y
            c.layout(esq, topo, esq + c.measuredWidth, topo + c.measuredHeight)
            x += c.measuredWidth + vao
            alturaLinha = maxOf(alturaLinha, c.measuredHeight)
        }
    }
}
