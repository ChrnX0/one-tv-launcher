package br.paulo.tvlauncher.home

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * Linha que quebra (20/11/2026, Configurações iguais ao painel): os filhos vão lado a lado e descem para a linha de baixo
 * quando não cabem, como as pílulas do índice e do "Conversa com" no painel web. Sem rolagem para o lado: nenhuma pílula
 * fica cortada fora da tela (o teste de tela reprova texto cortado). Respeita a direção da língua (árabe da direita).
 */
class WrapRow(ctx: Context, private val gapH: Int, private val gapV: Int) : ViewGroup(ctx) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val largura = MeasureSpec.getSize(widthMeasureSpec)
        val max = largura - paddingLeft - paddingRight
        var x = 0; var y = 0; var alturaLinha = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            c.measure(MeasureSpec.makeMeasureSpec(max, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + c.measuredWidth > max) { x = 0; y += alturaLinha + gapV; alturaLinha = 0 }
            x += c.measuredWidth + gapH
            alturaLinha = maxOf(alturaLinha, c.measuredHeight)
        }
        setMeasuredDimension(largura, y + alturaLinha + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val max = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == View.LAYOUT_DIRECTION_RTL
        var x = 0; var y = 0; var alturaLinha = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == View.GONE) continue
            if (x > 0 && x + c.measuredWidth > max) { x = 0; y += alturaLinha + gapV; alturaLinha = 0 }
            val esquerda = if (rtl) (r - l) - paddingRight - x - c.measuredWidth else paddingLeft + x
            c.layout(esquerda, paddingTop + y, esquerda + c.measuredWidth, paddingTop + y + c.measuredHeight)
            x += c.measuredWidth + gapH
            alturaLinha = maxOf(alturaLinha, c.measuredHeight)
        }
    }
}
