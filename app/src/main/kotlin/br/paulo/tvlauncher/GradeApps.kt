package br.paulo.tvlauncher

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * A linha de apps da HOME no modo grade (layout flexível, 15/11/2026): os mesmos cartões, só que em fileiras de
 * [colunas], da esquerda para a direita e de cima para baixo. A ordem dos filhos é a mesma da fileira única, então
 * ordem, pilhas, modo mover e ocultos não mudam. Cada filho ocupa a largura e a margem que trouxe (LayoutParams com
 * margem, os mesmos do modo fileira); a altura da fileira é a do filho mais alto dela.
 */
class GradeApps(ctx: Context, var colunas: Int, private val vaoVertical: Int) : ViewGroup(ctx) {
    init { clipChildren = false; clipToPadding = false }

    override fun generateDefaultLayoutParams() = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?) = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?) = p is MarginLayoutParams

    override fun onMeasure(larguraSpec: Int, alturaSpec: Int) {
        var altura = 0; var linhaAltura = 0; var largura = 0; var x = 0
        for (i in 0 until childCount) {
            val f = getChildAt(i)
            if (f.visibility == View.GONE) continue
            measureChildWithMargins(f, larguraSpec, 0, alturaSpec, 0)
            val lp = f.layoutParams as MarginLayoutParams
            if (i % colunas == 0 && i > 0) { altura += linhaAltura + vaoVertical; linhaAltura = 0; x = 0 }
            x += f.measuredWidth + lp.leftMargin + lp.rightMargin
            largura = maxOf(largura, x)
            linhaAltura = maxOf(linhaAltura, f.measuredHeight + lp.topMargin + lp.bottomMargin)
        }
        altura += linhaAltura
        setMeasuredDimension(resolveSize(largura + paddingLeft + paddingRight, larguraSpec),
            resolveSize(altura + paddingTop + paddingBottom, alturaSpec))
    }

    override fun onLayout(mudou: Boolean, l: Int, t: Int, r: Int, b: Int) {
        var x = paddingLeft; var y = paddingTop; var linhaAltura = 0
        for (i in 0 until childCount) {
            val f = getChildAt(i)
            if (f.visibility == View.GONE) continue
            val lp = f.layoutParams as MarginLayoutParams
            if (i % colunas == 0 && i > 0) { x = paddingLeft; y += linhaAltura + vaoVertical; linhaAltura = 0 }
            f.layout(x + lp.leftMargin, y + lp.topMargin, x + lp.leftMargin + f.measuredWidth, y + lp.topMargin + f.measuredHeight)
            x += f.measuredWidth + lp.leftMargin + lp.rightMargin
            linhaAltura = maxOf(linhaAltura, f.measuredHeight + lp.topMargin + lp.bottomMargin)
        }
    }
}
