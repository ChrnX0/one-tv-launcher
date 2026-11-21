package br.paulo.tvlauncher

import br.paulo.tvlauncher.network.HomeAssistantFinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeAssistantFinderTest {

    @Test fun procuraNaRedeDaTvComecandoPertoDoUltimo() {
        val c = HomeAssistantFinder.candidatos("192.168.1.153", "192.168.1.236")
        assertEquals(253, c.size)                                   // 1..254 menos a própria TV
        assertFalse("192.168.1.153" in c)
        assertEquals("192.168.1.236", c.first())                    // o último que funcionava primeiro (pode ter voltado)
        assertTrue(c.indexOf("192.168.1.237") < c.indexOf("192.168.1.10"))
        assertEquals("192.168.1.152", HomeAssistantFinder.candidatos("192.168.1.153", "10.0.0.5").first())   // outra rede: perto da TV
        assertEquals(emptyList<String>(), HomeAssistantFinder.candidatos("sem ip", null))
    }

    @Test fun soAceitaHomeAssistantDeVerdade() {
        assertTrue(HomeAssistantFinder.pareceHomeAssistant(200, """[{"name":"Home Assistant Local","id":null,"type":"homeassistant"}]"""))
        assertTrue(HomeAssistantFinder.pareceHomeAssistant(200, """{"providers":[{"name":"Home Assistant Local","id":null,"type":"homeassistant"}],"preselect_remember_me":false}"""))
        assertFalse(HomeAssistantFinder.pareceHomeAssistant(200, "<html>homeassistant</html>"))   // página qualquer
        assertFalse(HomeAssistantFinder.pareceHomeAssistant(404, """[{"type":"homeassistant"}]"""))
        assertTrue(HomeAssistantFinder.aceitouChave(200, """{"message":"API running."}"""))
        assertFalse(HomeAssistantFinder.aceitouChave(401, "401: Unauthorized"))
        assertEquals("http://192.168.1.236:8123", HomeAssistantFinder.endereco("192.168.1.236", 8123))
    }

    @Test fun chaveSoVaiAoMesmoHomeAssistant() {
        assertTrue(HomeAssistantFinder.anuncioAceito(null, "abc"))          // 1ª procura: qualquer anúncio
        assertTrue(HomeAssistantFinder.anuncioAceito("abc", "abc"))
        assertFalse(HomeAssistantFinder.anuncioAceito("abc", "outro"))      // aparelho falso com outro uuid
        assertFalse(HomeAssistantFinder.anuncioAceito("abc", null))          // anúncio sem uuid
        assertTrue(HomeAssistantFinder.podeVarrer(null))
        assertFalse(HomeAssistantFinder.podeVarrer("abc"))                   // HA conhecido: sem varredura às cegas
        assertFalse(HomeAssistantFinder.hostAceito("127.0.0.1"))
        assertTrue(HomeAssistantFinder.hostAceito("192.168.1.20"))
    }
}
