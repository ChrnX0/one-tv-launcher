package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
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

    @Test fun brilhoEmPassosDeDez() {
        assertEquals(60, CasaHA.proximoBrilho(55, +1))
        assertEquals(50, CasaHA.proximoBrilho(55, -1))
        assertEquals(70, CasaHA.proximoBrilho(60, +1))
        assertEquals(50, CasaHA.proximoBrilho(60, -1))
        assertEquals(10, CasaHA.proximoBrilho(null, +1))   // apagada: ◀ ▶ acende em 10 %
        assertEquals(0, CasaHA.proximoBrilho(10, -1))      // 10 % e ◀: apaga
        assertEquals(100, CasaHA.proximoBrilho(100, +1))
        assertEquals(0, CasaHA.proximoBrilho(null, -1))
    }

    @Test fun brilhoDoHomeAssistant() {
        assertEquals(100, CasaHA.brilhoPct(JSONObject().put("brightness", 255)))
        assertEquals(50, CasaHA.brilhoPct(JSONObject().put("brightness", 128)))
        assertEquals(1, CasaHA.brilhoPct(JSONObject().put("brightness", 1)))
        assertNull(CasaHA.brilhoPct(JSONObject().put("brightness", JSONObject.NULL)))
        assertNull(CasaHA.brilhoPct(JSONObject()))
    }

    @Test fun soLuzComBrilhoERegulavel() {
        val modos = { vararg m: String -> JSONObject().put("supported_color_modes", org.json.JSONArray(m.toList())) }
        assertTrue(CasaHA.regulavel("light.sala", modos("brightness")))
        assertTrue(CasaHA.regulavel("light.sala", modos("color_temp", "hs")))
        assertFalse(CasaHA.regulavel("light.sala", modos("onoff")))
        assertFalse(CasaHA.regulavel("switch.cafeteira", modos("brightness")))
        assertTrue(CasaHA.regulavel("light.velha", JSONObject().put("brightness", 200)))   // sem a lista de modos
    }

    @Test fun lerEstadosTrazOBrilho() {
        val l = CasaHA.lerEstados("""[{"entity_id":"light.sala","state":"on","attributes":{"friendly_name":"Sala",
            "brightness":153,"supported_color_modes":["brightness"]}}]""")
        assertEquals(60, l[0].brilho)
        assertTrue(l[0].regulavel)
    }
}
