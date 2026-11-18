package br.paulo.tvlauncher

import android.graphics.Canvas
import android.graphics.Paint
import android.view.View

/**
 * Elevação 16.8 (17/11/2026): árabe espelhado de verdade. As telas de View já espelham sozinhas (supportsRtl, margens
 * e recuos "start/end"); as desenhadas à mão (guia em grade, barras de progresso, gráficos) usam isto:
 *  - [espelhar] vira o desenho inteiro da direita para a esquerda (o tempo e o progresso andam para a esquerda);
 *  - [texto] escreve legível dentro do desenho virado (desvira só a palavra, no lugar espelhado).
 */
object Rtl {
    fun ehRtl(v: View): Boolean = v.layoutDirection == View.LAYOUT_DIRECTION_RTL

    /** Roda [desenho] com o canvas virado na horizontal quando a view está da direita para a esquerda. */
    inline fun espelhar(v: View, c: Canvas, desenho: () -> Unit) {
        if (!ehRtl(v)) { desenho(); return }
        val salvo = c.save()
        c.scale(-1f, 1f, v.width / 2f, 0f)
        try { desenho() } finally { c.restoreToCount(salvo) }
    }

    /** drawText que continua legível com o canvas virado por [espelhar] (o texto fica no lugar espelhado, não ao contrário). */
    fun texto(c: Canvas, rtl: Boolean, t: CharSequence, ini: Int, fim: Int, x: Float, y: Float, p: Paint) {
        if (!rtl) { c.drawText(t, ini, fim, x, y, p); return }
        val w = p.measureText(t, ini, fim)
        val meio = when (p.textAlign) { Paint.Align.LEFT -> x + w / 2; Paint.Align.RIGHT -> x - w / 2; else -> x }
        val salvo = c.save()
        c.scale(-1f, 1f, meio, y)
        c.drawText(t, ini, fim, x, y, p)
        c.restoreToCount(salvo)
    }

    fun texto(c: Canvas, rtl: Boolean, t: String, x: Float, y: Float, p: Paint) = texto(c, rtl, t, 0, t.length, x, y, p)

    /**
     * A tecla como ela se lê na tela: no árabe a ◀ do controle aponta para o que fica à esquerda, que é o "depois".
     * Usado só em navegação ESPACIAL feita à mão (ir para a coluna ao lado, andar entre cartões); setas que mudam um
     * valor (◀ ▶ trocam a opção) ficam como estão.
     */
    fun tecla(keyCode: Int, v: View?): Int = if (v == null || !ehRtl(v)) keyCode else when (keyCode) {
        android.view.KeyEvent.KEYCODE_DPAD_LEFT -> android.view.KeyEvent.KEYCODE_DPAD_RIGHT
        android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> android.view.KeyEvent.KEYCODE_DPAD_LEFT
        else -> keyCode
    }

    /** A seta de "mais" que aponta para onde a leitura continua: "›" ou "‹". */
    fun seta(rtl: Boolean) = if (rtl) "‹" else "›"
}
