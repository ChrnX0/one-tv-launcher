package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Canvas
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
 *
 * Sem novo layout quando a imagem chega (13/11/2026, rodada 4): o ImageView pede um layout a cada imagem trocada (o
 * tamanho dela muda de "nenhum" para o do pôster), e o cinza de espera, quando era o fundo, pedia outro ao entrar e
 * sair. Cada pedido subia até a raiz e refazia a grade inteira no quadro seguinte, uma vez por pôster que chegava. O
 * card tem tamanho fixo (a coluna da grade x a altura do pôster) e o CENTER_CROP se ajusta sozinho ao tamanho da View,
 * então a imagem nova só precisa ser redesenhada.
 */
class CapaCartao(ctx: Context, private val raioPx: Float) : ImageView(ctx) {
    // Pode ser lido antes do init (se o construtor do ImageView chamar setImageDrawable): por isso é anulável.
    private var espera: GradientDrawable? = GradientDrawable().apply { cornerRadius = raioPx; setColor(Estilo.BORDA) }

    // true só enquanto troca a imagem de um card que já tem tamanho: aí o pedido de layout do ImageView é ignorado.
    private var semLayout = false

    init {
        scaleType = ScaleType.CENTER_CROP
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) { o.setRoundRect(0, 0, v.width, v.height, raioPx) }
        }
        clipToOutline = true
    }

    // setImageBitmap e setImageResource passam por aqui. O ImageView já chama invalidate(): o quadro seguinte redesenha.
    override fun setImageDrawable(d: Drawable?) {
        semLayout = width > 0 && height > 0
        try { super.setImageDrawable(d) } finally { semLayout = false }
    }

    override fun requestLayout() {
        if (!semLayout) super.requestLayout()
    }

    // O cinza de espera é desenhado aqui (e não como fundo) para trocar de estado sem pedir layout.
    override fun onDraw(c: Canvas) {
        if (drawable != null) { super.onDraw(c); return }
        espera?.let { it.setBounds(0, 0, width, height); it.draw(c) }
    }
}
