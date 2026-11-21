package br.paulo.tvlauncher

import br.paulo.tvlauncher.network.TvWakeRetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TvWakeRetryTest {
    @Test fun esperasSomamOsMomentosCertos() {
        var t = 0L
        val momentos = generateSequence(0) { it + 1 }.map { TvWakeRetry.proxima(it) }.takeWhile { it != null }
            .map { t += it!!; t }.toList()
        assertEquals(TvWakeRetry.ESPERAS_MS.toList(), momentos)
        assertNull(TvWakeRetry.proxima(TvWakeRetry.ESPERAS_MS.size))
    }

    @Test fun cabeNosDezMinutosDoAlarme() {
        assertTrue(TvWakeRetry.ESPERAS_MS.last() < 10 * 60_000L)
        assertTrue(TvWakeRetry.ESPERAS_MS.first() >= 20_000L)   // depois da tentativa do próprio Android
    }

    @Test fun soTentaTocandoESemNinguem() {
        assertTrue(TvWakeRetry.tentar(tocando = true, alguemMexeu = false))
        assertFalse(TvWakeRetry.tentar(tocando = true, alguemMexeu = true))
        assertFalse(TvWakeRetry.tentar(tocando = false, alguemMexeu = false))
    }
}
