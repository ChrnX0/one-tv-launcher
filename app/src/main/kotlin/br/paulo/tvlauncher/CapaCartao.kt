package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView

/**
 * Pôster de card das grades (Filmes, Séries), com cantos arredondados (13/11/2026).
 *
 * Antes era um ImageView com fundo cinza arredondado SEMPRE: com o pôster carregado, a GPU pintava o cinza e o pôster
 * por cima, em cada card, a cada quadro da rolagem (sobreposição medida na Sala). Agora o cinza só existe enquanto o
 * pôster não chega. O arredondado do recorte vem de um contorno próprio (antes vinha do fundo, que agora some).
 */
class CapaCartao(ctx: Context, private val raioPx: Float) : ImageView(ctx) {
    // Pode ser lido antes do init (se o construtor do ImageView chamar setImageDrawable): por isso é anulável.
    private var espera: GradientDrawable? = GradientDrawable().apply { cornerRadius = raioPx; setColor(Estilo.BORDA) }

    init {
        scaleType = ScaleType.CENTER_CROP
        background = espera
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, raioPx) }
        }
        clipToOutline = true
    }

    // setImageBitmap e setImageResource passam por aqui: com imagem, sem fundo; sem imagem, o cinza de espera.
    override fun setImageDrawable(d: Drawable?) {
        super.setImageDrawable(d)
        background = if (d == null) espera else null
    }
}
