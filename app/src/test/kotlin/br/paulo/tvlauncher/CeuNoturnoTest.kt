package br.paulo.tvlauncher

import br.paulo.tvlauncher.weather.CeuNoturno
import br.paulo.tvlauncher.weather.Sgp4
import java.util.TimeZone
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * O céu calculado na TV contra as bibliotecas de referência, rodadas em 17/11/2026 com os mesmos dados:
 * sgp4 2.x (Vallado, WGS-72) para a órbita e PyEphem 4.x para planetas, Lua e passagens.
 */
class CeuNoturnoTest {
    // TLE da Estação baixado do CelesTrak em 17/11/2026 (dado público do NORAD).
    private val l1 = "1 25544U 98067A   26320.56058678  .00009493  00000+0  17760-3 0  9996"
    private val l2 = "2 25544  51.6320 266.0553 0007391  32.4819 327.6623 15.49415609595375"

    private fun perto(esperado: Double, real: Double, tol: Double, o: String) =
        assertTrue("$o: esperado $esperado, veio $real", abs(esperado - real) <= tol)

    @Test fun sgp4IgualAReferencia() {
        val s = Sgp4(l1, l2)
        // Saída do sgp4 (Python) para t = 0, 60, 720 e 1440 min depois da época, em km (TEME).
        val ref = mapOf(
            0L to doubleArrayOf(-467.32063706413714, -6776.9753731296805, -0.0010200593346498413),
            60L to doubleArrayOf(-3056.6942060956735, 4352.431769493119, -4245.810502252242),
            720L to doubleArrayOf(-4209.717873217017, 369.46764650633844, -5332.461169577068),
            1440L to doubleArrayOf(912.2694479584188, 6737.789688060709, -177.76649325408826))
        for ((m, r) in ref) {
            val p = s.posicao(s.epocaMs + m * 60_000L)
            for (i in 0..2) perto(r[i], p[i], 0.001, "t=$m eixo $i")   // 1 metro
        }
    }

    @Test fun lerCamposDoTle() {
        assertEquals(0.17760e-3, Sgp4.expoente(" 17760-3"), 1e-12)
        assertEquals(-0.11606e-4, Sgp4.expoente("-11606-4"), 1e-12)
        assertEquals(0.0, Sgp4.expoente(" 00000+0"), 0.0)
        assertEquals(1767225600000L, Sgp4.msDoAno(2026))   // 2026-01-01T00:00Z
    }

    @Test fun planetasPertoDoPyEphem() {
        val t = 1795300000000L   // 2026-11-21 22:26:40 UTC
        // RA/Dec geocêntricos do PyEphem (equinócio da data). Os nossos são J2000: ~0,35° de precessão fazem parte da folga.
        val ref = mapOf(CeuNoturno.Planeta.VENUS to (202.282 to -9.332), CeuNoturno.Planeta.MARTE to (151.243 to 13.998),
            CeuNoturno.Planeta.JUPITER to (148.816 to 13.467), CeuNoturno.Planeta.SATURNO to (8.617 to 0.865))
        for ((p, rd) in ref) {
            val (ra, dec, _) = CeuNoturno.radec(p, t)
            perto(rd.first, Math.toDegrees(ra), 0.6, "$p RA")
            perto(rd.second, Math.toDegrees(dec), 0.6, "$p Dec")
        }
        perto(372128.0, CeuNoturno.distanciaLua(t), 2000.0, "Lua")
    }

    @Test fun saturnoAltoNaNoiteDeSaoPaulo() {
        // PyEphem: às 19:26 locais de 21/11/2026 Saturno está a 60° no nordeste de São Paulo.
        val v = CeuNoturno.planetas(1795300000000L, -23.55, -46.63, TimeZone.getTimeZone("America/Sao_Paulo"))
        val sat = v.firstOrNull { it.planeta == CeuNoturno.Planeta.SATURNO }
        assertNotNull(sat); assertTrue(sat!!.altura >= 60)
    }

    @Test fun passagemDaEstacaoEmLondres() {
        // PyEphem: passagem de 17/11/2026 17:32:08 a 17:43:09 UTC, máxima 47°, do oeste-sudoeste (237°) ao leste-nordeste.
        // A nossa conta só a parte VISÍVEL (sai da sombra da Terra / entra nela), por isso começa e termina dentro dessa janela.
        val s = Sgp4(l1, l2)
        val p = CeuNoturno.proximaPassagem(s, 1794935520000L /* 17/11 17:12Z */, 51.5, -0.12, horas = 2)
        assertNotNull(p)
        assertTrue(p!!.inicio >= 1794936720000L - 60_000 && p.fim <= 1794937389000L + 60_000)   // 17:32:00 .. 17:43:09
        perto(46.5, p.alturaMax.toDouble(), 2.0, "altura máxima")
        assertEquals(5, p.deRumo)   // sudoeste
    }

    @Test fun rumos() {
        assertEquals(0, CeuNoturno.rumo(0.0)); assertEquals(0, CeuNoturno.rumo(350.0)); assertEquals(2, CeuNoturno.rumo(90.0))
        assertEquals(4, CeuNoturno.rumo(180.0)); assertEquals(6, CeuNoturno.rumo(258.0)); assertEquals(7, CeuNoturno.rumo(320.0))
    }

    @Test fun meteorosSoNoPicoEDoHemisferioCerto() {
        val sp = TimeZone.getTimeZone("America/Sao_Paulo")
        val leonidas = 1794949200000L   // 2026-11-17 18:00 local
        assertEquals(CeuNoturno.ChuvaMeteoros.LEONIDAS, CeuNoturno.meteoros(leonidas, -23.55, sp))
        assertNull(CeuNoturno.meteoros(leonidas + 10 * 86_400_000L, -23.55, sp))
        // Quadrântidas (radiante a +49°) não sobem o bastante no sul.
        val quadrantidas = 1767441600000L   // 2026-01-03 12:00Z
        assertNull(CeuNoturno.meteoros(quadrantidas, -33.9, TimeZone.getTimeZone("UTC")))
        assertEquals(CeuNoturno.ChuvaMeteoros.QUADRANTIDAS, CeuNoturno.meteoros(quadrantidas, 51.5, TimeZone.getTimeZone("UTC")))
    }
}
