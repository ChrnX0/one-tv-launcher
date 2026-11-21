package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import android.widget.ScrollView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.GraphicsMode

/**
 * Lista do [Estilo.lista] (menu "Áudio e legenda" do player, painel da direita desde 18/11/2026) rolada até o fim: nenhuma linha pode ser desenhada por
 * cima do título (visto na Sala em 15/11/2026). Desenha o cartão duas vezes, com a lista visível e escondida, e compara
 * a faixa do título pixel a pixel. Roda com -Ptelas; a foto vai para app/build/telas/lista-rolada.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34], qualifiers = "pt-w960dp-h540dp-land-television-xhdpi")
class ListaRoladaTelaTest {
    @Test fun linhasNaoPassamPorCimaDoTitulo() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val itens = (1..28).map { "Opção $it" }
        val d = Estilo.lista(a, "Áudio e legenda", itens) {}
        // O painel (à direita, 18/11/2026) é o 1º filho do conteúdo da janela: título, posição, ScrollView, rodapé.
        val cartao = ((d.findViewById<View>(android.R.id.content) as ViewGroup).getChildAt(0) as ViewGroup).getChildAt(0) as ViewGroup
        val rolagem = (0 until cartao.childCount).map { cartao.getChildAt(it) }.first { it is ScrollView } as ScrollView
        val larg = 940; val alt = 1000   // menor que a lista inteira, para ela precisar rolar
        cartao.measure(View.MeasureSpec.makeMeasureSpec(larg, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(alt, View.MeasureSpec.EXACTLY))
        cartao.layout(0, 0, larg, alt)
        rolagem.scrollTo(0, rolagem.getChildAt(0).height)   // até o fim, como na TV
        assertTrue("a lista precisa rolar para o teste valer", rolagem.scrollY > 0)

        fun foto(): Bitmap = Bitmap.createBitmap(larg, alt, Bitmap.Config.ARGB_8888).also { cartao.draw(Canvas(it)) }
        val comLista = foto()
        rolagem.visibility = View.INVISIBLE
        val semLista = foto()
        rolagem.visibility = View.VISIBLE
        File("build/telas").apply { mkdirs() }.let { File(it, "lista-rolada.png") }
            .outputStream().use { comLista.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // Faixa do título: do topo do cartão até onde a ScrollView começa.
        var diferentes = 0
        for (y in 0 until rolagem.top) for (x in 0 until larg) if (comLista.getPixel(x, y) != semLista.getPixel(x, y)) diferentes++
        assertEquals("pixels de linhas desenhados por cima do título", 0, diferentes)
        // A linha em foco não cresce (os vizinhos é que encolhem), então não há o que sobrar para fora do recorte.
        d.dismiss()
    }

    /** O mesmo para o molde de tela de lista ([Estilo.telaLista]): título e subtítulo fixos, só as linhas rolam. */
    @Test fun telaListaRoladaNaoCobreOTitulo() {
        val a = Robolectric.buildActivity(Activity::class.java).setup().get()
        val (raiz, coluna) = Estilo.telaLista(a, "Drive USB", "28 itens")
        (1..28).forEach { coluna.addView(Estilo.linhaTexto(a, "Pasta $it")) }
        raiz as ViewGroup
        val rolagem = (0 until raiz.childCount).map { raiz.getChildAt(it) }.first { it is ScrollView } as ScrollView
        val larg = 1920; val alt = 1080
        raiz.measure(View.MeasureSpec.makeMeasureSpec(larg, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(alt, View.MeasureSpec.EXACTLY))
        raiz.layout(0, 0, larg, alt)
        rolagem.scrollTo(0, rolagem.getChildAt(0).height)
        assertTrue("a lista precisa rolar para o teste valer", rolagem.scrollY > 0)

        fun foto(): Bitmap = Bitmap.createBitmap(larg, alt, Bitmap.Config.ARGB_8888).also { raiz.draw(Canvas(it)) }
        val comLista = foto()
        rolagem.visibility = View.INVISIBLE
        val semLista = foto()
        rolagem.visibility = View.VISIBLE
        File("build/telas").apply { mkdirs() }.let { File(it, "tela-lista-rolada.png") }
            .outputStream().use { comLista.compress(Bitmap.CompressFormat.PNG, 100, it) }
        var diferentes = 0
        for (y in 0 until rolagem.top) for (x in 0 until larg) if (comLista.getPixel(x, y) != semLista.getPixel(x, y)) diferentes++
        assertEquals("pixels de linhas desenhados por cima do título da tela", 0, diferentes)
        focadaCabe(coluna.getChildAt(coluna.childCount - 1), coluna.paddingLeft + rolagem.paddingLeft)
    }

    /**
     * A linha em foco cresce [Estilo.ESCALA_LINHA] para os dois lados: o que sobra de cada lado tem de caber na folga
     * entre a linha e o recorte (padding de quem está dentro do retângulo recortado).
     */
    private fun focadaCabe(linha: View, folga: Int) {
        val cresce = linha.width * (Estilo.ESCALA_LINHA - 1f) / 2f
        assertTrue("linha focada cortada: cresce $cresce px de cada lado, folga $folga px", cresce <= folga)
    }
}
