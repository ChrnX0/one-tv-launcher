package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** player-11: erros do PIN guardados, com bloqueio crescente. */
class TentativasPinTest {
    private val t0 = 1_000_000_000L

    @Test fun cincoErrosBloqueiamECresce() {
        var e = TentativasPin.Estado()
        repeat(4) { e = TentativasPin.errou(e, t0) }
        assertEquals(0L, TentativasPin.espera(e, t0))
        e = TentativasPin.errou(e, t0)
        assertEquals(60_000L, TentativasPin.espera(e, t0))
        e = TentativasPin.errou(e, t0 + 60_000)
        assertEquals(120_000L, TentativasPin.espera(e, t0 + 60_000))
        repeat(20) { e = TentativasPin.errou(e, t0 + 60_000) }
        assertEquals(TentativasPin.ESPERA_MAX_MS, TentativasPin.espera(e, t0 + 60_000))   // teto de 1 h
    }

    @Test fun acertoEDiaSemErroZeram() {
        var e = TentativasPin.Estado()
        repeat(6) { e = TentativasPin.errou(e, t0) }
        assertEquals(TentativasPin.Estado(), TentativasPin.acertou())
        assertEquals(0L, TentativasPin.espera(e, t0 + TentativasPin.ESQUECER_MS + 1))
        assertEquals(1, TentativasPin.errou(e, t0 + TentativasPin.ESQUECER_MS + 1).erros)
    }

    @Test fun relogioQueVoltouNaoPrendeParaSempre() {
        val e = TentativasPin.Estado(9, t0 + 10 * 24 * 3_600_000L, t0)
        assertTrue(TentativasPin.espera(e, t0) <= TentativasPin.ESPERA_MAX_MS)
        assertEquals(1, TentativasPin.minutos(10_000))
        assertEquals(2, TentativasPin.minutos(61_000))
    }
}
