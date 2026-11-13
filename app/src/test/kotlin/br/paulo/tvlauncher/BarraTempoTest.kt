package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class BarraTempoTest {
    @Test fun duracaoArredondaParaCima() {
        assertEquals("1 min", BarraTempo.duracao(0))
        assertEquals("1 min", BarraTempo.duracao(20_000))
        assertEquals("22 min", BarraTempo.duracao(21 * 60_000L + 1))
        assertEquals("59 min", BarraTempo.duracao(59 * 60_000L))
        assertEquals("1 h", BarraTempo.duracao(60 * 60_000L))
        assertEquals("1 h 05", BarraTempo.duracao(65 * 60_000L))
        assertEquals("2 h 30", BarraTempo.duracao(150 * 60_000L))
    }

    @Test fun fracaoFicaEntreZeroEUm() {
        assertEquals(0f, BarraTempo.fracao(10, 0), 0f)
        assertEquals(0.5f, BarraTempo.fracao(50, 100), 0f)
        assertEquals(1f, BarraTempo.fracao(150, 100), 0f)
        assertEquals(0f, BarraTempo.fracao(-5, 100), 0f)
    }
}
