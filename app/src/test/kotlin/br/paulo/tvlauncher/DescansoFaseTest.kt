package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.DescansoFase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DescansoFaseTest {
    @Test fun horasDoDia() {
        assertEquals(DescansoFase.MADRUGADA, DescansoFase.de(4))
        assertEquals(DescansoFase.MADRUGADA, DescansoFase.de(23))
        assertEquals(DescansoFase.MANHA, DescansoFase.de(7))
        assertEquals(DescansoFase.TARDE, DescansoFase.de(15))
        assertEquals(DescansoFase.NOITE, DescansoFase.de(20))
    }

    @Test fun madrugadaSemLogoEEscura() {
        val f = DescansoFase.de(3)
        assertFalse(f.logo)
        assertTrue(f.veu > 0x80 && f.brilhoInfo < 1f && f.quente > 0)
    }

    @Test fun todaHoraTemFase() { for (h in 0..23) DescansoFase.de(h) }
}
