package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.PlayerGrade
import org.junit.Assert.assertEquals
import org.junit.Test

/** Elevação 16.1 (o guia desenhado à mão fala com o leitor de tela) e 16.8 (a seta segue o espelho do árabe). */
class LeitorGuiaTest {
    @Test fun falaDoPrograma() {
        assertEquals("5, Globo. Jornal Nacional, 20:30 – 21:15",
            PlayerGrade.falaLeitor(5, "Globo", "Jornal Nacional", "20:30", "21:15", "Sem guia"))
    }

    @Test fun falaSemGuia() {
        assertEquals("12, Canal X. Sem guia", PlayerGrade.falaLeitor(12, "Canal X", null, null, null, "Sem guia"))
        assertEquals("12, Canal X. Sem guia", PlayerGrade.falaLeitor(12, "Canal X", " ", "1", "2", "Sem guia"))
    }

    @Test fun setaNoArabe() {
        assertEquals(1, PlayerGrade.passoDaSeta(direita = true, rtl = false))
        assertEquals(-1, PlayerGrade.passoDaSeta(direita = false, rtl = false))
        assertEquals(-1, PlayerGrade.passoDaSeta(direita = true, rtl = true))
        assertEquals(1, PlayerGrade.passoDaSeta(direita = false, rtl = true))
    }
}
