package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.FundoMeuRegras
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FundoMeuRegrasTest {
    @Test fun tipoPeloNome() {
        assertEquals(FundoMeuRegras.Tipo.IMAGEM, FundoMeuRegras.tipo("praia.JPG"))
        assertEquals(FundoMeuRegras.Tipo.ANIMADA, FundoMeuRegras.tipo("gato.gif"))
        assertEquals(FundoMeuRegras.Tipo.VIDEO, FundoMeuRegras.tipo("lareira.mp4"))
        assertNull(FundoMeuRegras.tipo("nota.txt"))
        assertNull(FundoMeuRegras.tipo("semextensao"))
    }

    @Test fun semAjusteSemFiltro() = assertNull(FundoMeuRegras.matriz(0, 0))

    @Test fun matrizDeBrilhoEContraste() {
        // Contraste +20: escala 1,2 em volta de 128 (soma 128*(1-1,2) = -25,6). Brilho +20: soma 51.
        val m = FundoMeuRegras.matriz(20, 20)!!
        assertEquals(1.2f, m[0], 1e-4f); assertEquals(1.2f, m[6], 1e-4f); assertEquals(1.2f, m[12], 1e-4f)
        assertEquals(-25.6f + 51f, m[4], 1e-3f)
        assertEquals(1f, m[18], 0f)   // alfa intacto
        // Só brilho -40: escala 1, soma -102.
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f, -102f), FundoMeuRegras.matriz(-40, 0)!!.copyOfRange(0, 5), 1e-3f)
    }

    @Test fun degrausDaoAVolta() {
        var v = 0
        val vistos = mutableListOf<Int>()
        repeat(5) { v = FundoMeuRegras.proximoDegrau(v); vistos += v }
        assertEquals(listOf(20, 40, -40, -20, 0), vistos)
        assertEquals(20, FundoMeuRegras.proximoDegrau(77))   // valor estranho guardado: recomeça
    }

    @Test fun amostragemNaoPassaDaTela() {
        assertEquals(2, FundoMeuRegras.amostragem(4000, 3000, 1920, 1080))
        assertEquals(1, FundoMeuRegras.amostragem(1920, 1080, 1920, 1080))
        assertEquals(4, FundoMeuRegras.amostragem(8000, 6000, 1920, 1080))
    }
}
