package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/** privilegios-7 e privilegios-9 (10/11/2026): espera crescente com teto e janela do relógio do guardião. */
class EsperaCrescenteTest {
    @Test fun esperaDobraACadaFalha() {
        assertEquals(3_000L, EsperaCrescente.esperaMs(0))
        assertEquals(6_000L, EsperaCrescente.esperaMs(1))
        assertEquals(12_000L, EsperaCrescente.esperaMs(2))
        assertEquals(96_000L, EsperaCrescente.esperaMs(5))
    }

    @Test fun esperaNaoPassaDoTetoNemEstoura() {
        assertEquals(EsperaCrescente.RELIGAR_TETO_MS, EsperaCrescente.esperaMs(7))
        assertEquals(EsperaCrescente.RELIGAR_TETO_MS, EsperaCrescente.esperaMs(1_000))
        assertEquals(EsperaCrescente.RELIGAR_TETO_MS, EsperaCrescente.esperaMs(Int.MAX_VALUE))
        assertEquals(3_000L, EsperaCrescente.esperaMs(-4))
    }

    @Test fun falhasZeramQuandoAReligadaSegurou() {
        assertEquals(0, EsperaCrescente.falhasAgora(5, null))                              // nunca religou
        assertEquals(0, EsperaCrescente.falhasAgora(5, EsperaCrescente.RELIGAR_ESTAVEL_MS))  // ficou ligada 2 min
        assertEquals(5, EsperaCrescente.falhasAgora(5, 10_000))                            // caiu de novo em 10 s
    }

    @Test fun janelaDoRelogio() {
        assertEquals(Pair(1_000L + 600_000L, 300_000L), EsperaCrescente.janelaRelogio(1_000, 600_000))
        assertEquals(60_000L, EsperaCrescente.janelaRelogio(0, 30_000).second)   // janela mínima de 1 min
    }
}
