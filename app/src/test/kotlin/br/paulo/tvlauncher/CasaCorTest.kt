package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideia 50: cor e temperatura da luz na tela Casa. */
class CasaCorTest {
    private fun atr(vararg modos: String) = JSONObject().put("supported_color_modes", JSONArray(modos.toList()))

    @Test fun tonsPeloModoDeCor() {
        assertEquals(3, CasaHA.coresDisponiveis("light.sala", atr("color_temp")).size)
        assertEquals(CasaHA.CorLuz.values().size, CasaHA.coresDisponiveis("light.sala", atr("color_temp", "hs")).size)
        assertEquals(CasaHA.CorLuz.values().size, CasaHA.coresDisponiveis("light.sala", atr("xy")).size)
        assertTrue(CasaHA.coresDisponiveis("light.sala", atr("brightness")).isEmpty())
        assertTrue(CasaHA.coresDisponiveis("light.sala", JSONObject()).isEmpty())
        assertTrue(CasaHA.coresDisponiveis("switch.x", atr("hs")).isEmpty())
    }

    @Test fun proximaCorDaAVolta() {
        assertEquals(0, CasaHA.proximaCor(3, -1, +1))
        assertEquals(2, CasaHA.proximaCor(3, -1, -1))
        assertEquals(0, CasaHA.proximaCor(3, 2, +1))
        assertEquals(2, CasaHA.proximaCor(3, 0, -1))
        assertEquals(-1, CasaHA.proximaCor(0, -1, +1))
    }

    @Test fun corpoDoServico() {
        val q = CasaHA.corpoCor("light.sala", CasaHA.CorLuz.QUENTE)
        assertEquals(2700, q.getInt("color_temp_kelvin")); assertFalse(q.has("hs_color"))
        val a = CasaHA.corpoCor("light.sala", CasaHA.CorLuz.AZUL)
        assertEquals(230, a.getJSONArray("hs_color").getInt(0)); assertEquals(100, a.getJSONArray("hs_color").getInt(1))
        assertEquals("light.sala", a.getString("entity_id"))
    }

    @Test fun lerEstadosTrazOsTons() {
        val json = """[{"entity_id":"light.sala","state":"on","attributes":{"friendly_name":"Sala","supported_color_modes":["color_temp"],"brightness":128}}]"""
        assertEquals(3, CasaHA.lerEstados(json)[0].cores.size)
    }
}
