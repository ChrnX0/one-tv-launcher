package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideia 40: presença sem UniFi (Home Assistant e IP que responde na rede) e cidade fora do Brasil para o clima. */
class PresencaFontesTest {
    private val min = 60_000L

    @Test fun umaFonteEmCasaBasta() {
        assertEquals(true, Presenca.juntar(listOf(false, true, null)))
        assertEquals(false, Presenca.juntar(listOf(false, null, false)))
        assertNull(Presenca.juntar(listOf(null, null)))
        assertNull(Presenca.juntar(emptyList()))
    }

    @Test fun naoSeiNuncaDesliga() {
        assertFalse(Presenca.decidirJunto(null, 0L, 60 * min, 60, true).desligar)
        assertNull(Presenca.decidirJunto(null, 0L, 60 * min, 60, true).ausenteDesde)
        assertNull(Presenca.decidirJunto(true, 0L, 60 * min, 60, true).ausenteDesde)
    }

    @Test fun ninguem15MinSemControleDesliga() {
        val d0 = Presenca.decidirJunto(false, null, 0L, 60, true)
        assertEquals(0L, d0.ausenteDesde); assertFalse(d0.desligar)
        assertTrue(Presenca.decidirJunto(false, 0L, 15 * min, 15, true).desligar)
        assertFalse(Presenca.decidirJunto(false, 0L, 15 * min, 3, true).desligar)    // alguém mexeu no controle
        assertFalse(Presenca.decidirJunto(false, 0L, 15 * min, 15, false).desligar)  // tela apagada
    }

    @Test fun entidadesSoPessoaERastreador() {
        assertEquals(listOf("person.ana", "device_tracker.cel_bia"),
            Presenca.lerEntidades("person.ana, light.sala; device_tracker.cel_bia person.ana Person.X! sensor.temp"))
    }

    @Test fun ipsSoDeCasa() {
        assertEquals(listOf("192.168.1.20", "10.0.0.5", "172.16.3.4"),
            Presenca.lerIps("192.168.1.20, 8.8.8.8 10.0.0.5;172.16.3.4 172.32.0.1 192.168.1.256 192.168.01.2 1.2.3 x; rm -rf /"))
    }

    @Test fun comandoSoComIpsValidados() {
        assertEquals("ping -c 1 -W 1 192.168.1.20 >/dev/null 2>&1; ip neigh show 192.168.1.20",
            Presenca.comandoIps(Presenca.lerIps("192.168.1.20;\$(reboot)")))
    }

    @Test fun vizinhoNaRede() {
        assertTrue(Presenca.algumVizinho("192.168.1.20 dev wlan0 lladdr aa:bb:cc:dd:ee:ff STALE\n"))
        assertTrue(Presenca.algumVizinho("192.168.1.21 dev wlan0  FAILED\n192.168.1.20 dev wlan0 lladdr aa:bb:cc:dd:ee:ff REACHABLE"))
        assertFalse(Presenca.algumVizinho("192.168.1.20 dev wlan0  FAILED"))
        assertFalse(Presenca.algumVizinho("192.168.1.20 dev wlan0 INCOMPLETE"))
        assertFalse(Presenca.algumVizinho(""))
    }
}
