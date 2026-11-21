package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.GloboLogica
import br.paulo.tvlauncher.listen.Radios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A conta do globo das rádios: projeção, distância, mira e onde começar. */
class GloboRadiosTest {
    private fun r(id: String, pais: String, lat: Double, lon: Double) = Radios.Radio(id, id, "http://x/$id", pais, lat = lat, lon = lon)

    @Test fun projecao() {
        val c = GloboLogica.projetar(10.0, 20.0, 10.0, 20.0)
        assertEquals(0.0, c.x, 1e-9); assertEquals(0.0, c.y, 1e-9); assertEquals(1.0, c.frente, 1e-9)
        assertTrue(GloboLogica.projetar(0.0, 180.0, 0.0, 0.0).frente < 0)   // o outro lado do mundo não aparece
        assertTrue(GloboLogica.projetar(0.0, 90.0, 0.0, 0.0).x > 0.99)      // a leste fica à direita
    }

    @Test fun miraEDistancia() {
        assertEquals(90.0, GloboLogica.distancia(0.0, 0.0, 0.0, 90.0), 1e-6)
        val l = listOf(r("sp", "BR", -23.5, -46.6), r("rio", "BR", -22.9, -43.2), r("lis", "PT", 38.7, -9.1))
        assertEquals("rio", GloboLogica.maisPerto(l, -22.0, -43.0)?.id)
        assertNull(GloboLogica.maisPerto(l, -30.0, -20.0))   // no meio do Atlântico
        assertEquals(-23.5 to -46.6, GloboLogica.centroDoPais(l, "br").let { it.first to it.second })
        assertEquals(0.0 to 0.0, GloboLogica.centroDoPais(l, "JP"))
        assertEquals(-170.0, GloboLogica.longitude(190.0), 1e-9)
    }
}
