package br.paulo.tvlauncher.home

import android.content.Context
import android.graphics.Canvas
import android.graphics.Outline
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.ImageView
import br.paulo.tvlauncher.DesignTokens
import br.paulo.tvlauncher.Estilo

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

    /**
     * Avisa quando o card ganha ou perde o pôster (19/11/2026): o nome do filme só aparece sobre o cinza de espera ou
     * quando o pôster não existe. Quem usa troca INVISIBLE/VISIBLE (sem GONE), que não pede layout.
     */
    var aoTrocar: ((temImagem: Boolean) -> Unit)? = null

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
        aoTrocar?.invoke(d != null)
    }

    override fun requestLayout() {
        if (!semLayout) super.requestLayout()
    }

    /**
     * Capa gerada (estudo global 20/11/2026, ideia 8). Por quê: título sem pôster virava um retângulo cinza (visto na Sala,
     * em Desenhos). Para quê: a grade continua bonita e se lê pelo nome, que já aparece por cima. Como: um degradê de uma
     * das cores dos mundos da cruz (escolhida pelo título, então o mesmo título tem sempre a mesma cor) para a Noite.
     * Quem usa passa o título em [semente]; sem semente, fica o cinza de espera de antes.
     */
    var semente: String? = null
        set(v) { if (field != v) { field = v; gerada = null; invalidate() } }
    private var gerada: GradientDrawable? = null

    // O cinza de espera é desenhado aqui (e não como fundo) para trocar de estado sem pedir layout.
    override fun onDraw(c: Canvas) {
        if (drawable != null) { super.onDraw(c); return }
        val s = semente
        val d = if (s.isNullOrBlank()) espera else gerada ?: GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(escurecer(corDe(s)), DesignTokens.NIGHT)).apply { cornerRadius = raioPx }.also { gerada = it }
        d?.let { it.setBounds(0, 0, width, height); it.draw(c) }
    }

    companion object {
        private val CORES = intArrayOf(DesignTokens.WORLD_CONTINUE, DesignTokens.WORLD_WATCH, DesignTokens.WORLD_LISTEN,
            DesignTokens.WORLD_PLAY, DesignTokens.WORLD_APPS, DesignTokens.WORLD_NETWORK)

        /** A cor do título: sempre a mesma para o mesmo nome (sem maiúsculas e espaços contarem). */
        fun corDe(titulo: String): Int = CORES[Math.floorMod(titulo.lowercase().filter { it.isLetterOrDigit() }.hashCode(), CORES.size)]

        /** 45% da cor: o nome em creme por cima continua lendo bem (a capa é fundo, não destaque). */
        fun escurecer(cor: Int): Int {
            fun c(sh: Int) = ((cor shr sh and 0xFF) * 0.45f).toInt()
            return (0xFF shl 24) or (c(16) shl 16) or (c(8) shl 8) or c(0)
        }
    }
}
