package br.paulo.tvlauncher.home

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.view.View
import android.view.ViewOutlineProvider
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.Estilo

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

    /**
     * Ícone do app só com o traço (pacotes embutidos Símbolo e Duotom, [PacoteIcones.Embutido]), sem fundo, como os
     * ícones da cruz. O traço é a camada monocromática do ícone adaptativo (Android 13+, desenhada pelo próprio app); sem
     * ela, recorta do ícone o que difere da cor de fundo. Devolve null se o recorte não dá um traço legível (quase vazio
     * ou quase tudo cheio): quem chama usa o ícone de sempre.
     */
    fun iconeTraco(res: Resources, d: Drawable, duotom: Boolean): Drawable? {
        val t = 192
        val mascara = Bitmap.createBitmap(t, t, Bitmap.Config.ALPHA_8)
        val c = Canvas(mascara)
        val extra = t / 4
        val adapt = d as? android.graphics.drawable.AdaptiveIconDrawable
        val mono = if (android.os.Build.VERSION.SDK_INT >= 33) adapt?.monochrome else null
        if (mono != null) {
            mono.setBounds(-extra, -extra, t + extra, t + extra); mono.draw(c)
        } else {
            // Sem camada monocromática: o ícone inteiro, e vira traço o que se afasta da cor do fundo.
            val cor = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
            val cc = Canvas(cor)
            if (adapt != null) {
                adapt.background?.let { it.setBounds(-extra, -extra, t + extra, t + extra); it.draw(cc) }
                adapt.foreground?.let { it.setBounds(-extra, -extra, t + extra, t + extra); it.draw(cc) }
            } else { d.setBounds(0, 0, t, t); d.draw(cc) }
            val px = IntArray(t * t); cor.getPixels(px, 0, t, 0, 0, t, t)
            val fundo = px[t / 12 * t + t / 2]
            val a = ByteArray(t * t)
            for (i in px.indices) {
                val p = px[i]
                val dist = kotlin.math.abs(((p shr 16) and 255) - ((fundo shr 16) and 255)) +
                    kotlin.math.abs(((p shr 8) and 255) - ((fundo shr 8) and 255)) + kotlin.math.abs((p and 255) - (fundo and 255))
                val v = ((dist - 40) * 4).coerceIn(0, 255) * (p ushr 24) / 255
                a[i] = v.toByte()
            }
            mascara.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(a))
            cor.recycle()
        }
        // Legível? Conta quanto da área virou traço.
        val buf = java.nio.ByteBuffer.allocate(t * t); mascara.copyPixelsToBuffer(buf)
        var cheio = 0
        for (b in buf.array()) if ((b.toInt() and 255) > 128) cheio++
        val cobertura = cheio.toFloat() / (t * t)
        if (cobertura < 0.02f || cobertura > 0.6f) { mascara.recycle(); return null }

        val saida = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
        val o = Canvas(saida)
        val tinta = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
        if (duotom) {
            // Halo: o traço na cor de destaque, borrado e um pouco maior, atrás do traço claro (como o Duotom da cruz).
            tinta.color = Estilo.ACENTO
            tinta.maskFilter = android.graphics.BlurMaskFilter(t * 0.035f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            tinta.alpha = 210
            for (k in 0 until 8) {
                val ang = k * Math.PI / 4
                o.drawBitmap(mascara, (kotlin.math.cos(ang) * t * 0.02f).toFloat(), (kotlin.math.sin(ang) * t * 0.02f).toFloat(), tinta)
            }
            tinta.maskFilter = null; tinta.alpha = 255
        }
        tinta.color = IconeCruz.COR_TRACO
        o.drawBitmap(mascara, 0f, 0f, tinta)
        mascara.recycle()
        return android.graphics.drawable.BitmapDrawable(res, saida)
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
