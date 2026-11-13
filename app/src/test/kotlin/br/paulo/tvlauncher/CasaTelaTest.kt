package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tela Casa redesenhada (13/11/2026): ícone, "ligado" e o estado otimista depois do OK (CasaHA, puras). */
class CasaTelaTest {
    @Test fun iconePorTipo() {
        assertEquals("lampada", CasaHA.iconeDe("light"))
        assertEquals("tomada", CasaHA.iconeDe("switch"))
        assertEquals("cortina", CasaHA.iconeDe("cover"))
        assertEquals("cena", CasaHA.iconeDe("scene"))
        assertEquals("rotina", CasaHA.iconeDe("script"))
        assertEquals("chave", CasaHA.iconeDe("input_boolean"))
    }

    @Test fun ligadoSoQuandoFazSentido() {
        assertTrue(CasaHA.ligado("light", "on"))
        assertFalse(CasaHA.ligado("light", "off"))
        assertFalse(CasaHA.ligado("light", "unavailable"))
        assertTrue(CasaHA.ligado("cover", "open"))
        assertFalse(CasaHA.ligado("cover", "closed"))
        assertFalse(CasaHA.ligado("scene", "on"))   // cena não fica "ligada": só roda
    }

    @Test fun inversoDoOk() {
        assertEquals("off", CasaHA.inverso("switch", "on"))
        assertEquals("on", CasaHA.inverso("fan", "off"))
        assertEquals("closed", CasaHA.inverso("cover", "opening"))
        assertNull(CasaHA.inverso("light", "unavailable"))   // não sabe: o cartão não muda antes da resposta
        assertNull(CasaHA.inverso("script", "off"))
    }
}
