package br.paulo.tvlauncher

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider

/** Desenho dos cartões da HOME que não depende do estado da tela. Saiu da MainActivity em 12/11/2026, sem mudança. */
object DesenhoHome {
    /**
     * Prepara o ícone para o disco, UMA vez por app (na carga da lista, não a cada quadro):
     * - adaptativo (a maioria): desenha as camadas de fundo e frente sem máscara; o disco recorta em círculo.
     * - comum (ícone quadrado, ex.: YouTube, 1-Stream): pinta o fundo com a cor da borda do próprio ícone e aumenta o
     *   ícone 18%, para os cantos arredondados dele saírem do círculo e não aparecer "um quadrado dentro da bola".
     * Vira um bitmap de 192 px: desenhar bitmap é mais barato que redesenhar o ícone vetorial em cada animação de foco.
     */
    fun iconeRedondo(res: Resources, d: Drawable): Drawable {
        val t = 192
        val bmp = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        if (d is android.graphics.drawable.AdaptiveIconDrawable) {
            // As camadas têm 108 unidades; a parte visível são as 72 do meio: desenha 1,5x maior e centralizado.
            val extra = t / 4
            d.background?.let { it.setBounds(-extra, -extra, t + extra, t + extra); it.draw(c) }
            d.foreground?.let { it.setBounds(-extra, -extra, t + extra, t + extra); it.draw(c) }
        } else {
            val amostra = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, t, t); d.draw(Canvas(amostra))
            val px = amostra.getPixel(t / 2, t / 12)   // perto da borda de cima, no meio: a cor do fundo do ícone
            c.drawColor(if ((px ushr 24) > 200) px else 0xFFF2F2F2.toInt())
            val m = (t * 0.09f).toInt()
            d.setBounds(-m, -m, t + m, t + m); d.draw(c)
            amostra.recycle()
        }
        return android.graphics.drawable.BitmapDrawable(res, bmp)
    }

    /** Recorta a view em círculo. */
    fun circular(v: View) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    /** Recorta a view com cantos arredondados (clipToOutline recorta o conteúdo no formato do contorno). */
    fun arredondar(v: View, raioPx: Float) {
        v.clipToOutline = true
        v.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                outline.setRoundRect(0, 0, view.width, view.height, raioPx)
            }
        }
    }

    /** Cópia independente da imagem: o mesmo Drawable em duas views ao mesmo tempo pode se atrapalhar. */
    fun copia(res: Resources, d: Drawable): Drawable = d.constantState?.newDrawable(res)?.mutate() ?: d
}
