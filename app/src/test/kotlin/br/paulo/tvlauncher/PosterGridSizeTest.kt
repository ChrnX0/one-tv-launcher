package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.PosterGridSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PosterGridSizeTest {
    @Test fun tvDaSalaCabemDuasFileirasInteiras() {
        // Área medida na foto da Sala (19/11/2026): ~642 x 304 dp.
        val m = PosterGridSize.calcular(642, 304)
        assertEquals(6, m.colunas)
        // Duas fileiras + o vão cabem na altura: nenhuma fileira cortada.
        assertTrue(2 * m.alturaCard + PosterGridSize.VAO_FILEIRAS <= 304)
        assertTrue(m.alturaPoster >= PosterGridSize.POSTER_MINIMO)
    }

    @Test fun pouquissimaAlturaViraUmaFileiraSo() {
        val m = PosterGridSize.calcular(642, 200)
        assertEquals(180, m.alturaPoster)
        assertTrue(m.alturaCard <= 200)
    }

    @Test fun colunasFicamNoLimite() {
        assertEquals(PosterGridSize.COLUNAS_MAX, PosterGridSize.calcular(3000, 304).colunas)
        assertEquals(PosterGridSize.COLUNAS_MIN, PosterGridSize.calcular(200, 304).colunas)
    }

    @Test fun telaAltaNaoPassaDoMaximo() {
        assertEquals(PosterGridSize.POSTER_MAXIMO, PosterGridSize.calcular(900, 900).alturaPoster)
    }
}
