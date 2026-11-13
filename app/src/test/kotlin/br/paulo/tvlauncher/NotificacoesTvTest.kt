package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class NotificacoesTvTest {
    private val agora = 1_000_000_000_000L
    private fun ha(min: Long) = NotificacoesTv.haQuanto(agora - min * 60_000, agora, "ontem", "agora")

    @Test fun faixas() {
        assertEquals("agora", ha(0))
        assertEquals("12 min", ha(12))
        assertEquals("3 h", ha(3 * 60 + 20))
        assertEquals("ontem", ha(30 * 60))
        assertEquals("4 d", ha(4 * 24 * 60))
    }

    @Test fun relogioAdiantadoNaoDaNegativo() = assertEquals("agora", NotificacoesTv.haQuanto(agora + 90_000, agora, "ontem", "agora"))
}
