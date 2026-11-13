package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MetarTest {
    @Test fun vizinhancaNaoEhChuvaAqui() {
        assertNull(Metar.fenomeno("VCSH"))
        assertNull(Metar.fenomeno("VCTS"))
        assertNull(Metar.fenomeno(""))
    }

    @Test fun fenomenoNaEstacao() {
        assertEquals(80, Metar.fenomeno("-SHRA"))
        assertEquals(95, Metar.fenomeno("TSRA VCSH"))
        assertEquals(61, Metar.fenomeno("-RA VCTS"))
        assertEquals(51, Metar.fenomeno("DZ"))
        assertEquals(45, Metar.fenomeno("BR"))
    }
}
