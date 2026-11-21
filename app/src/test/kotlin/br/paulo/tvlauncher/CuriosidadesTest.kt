package br.paulo.tvlauncher

import br.paulo.tvlauncher.screensaver.TempoAgora
import br.paulo.tvlauncher.settings.Curiosidades
import br.paulo.tvlauncher.weather.CeuNoturno
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CuriosidadesTest {
    private val fuso = TimeZone.getTimeZone("GMT-3")
    private val meiaNoite = 1794884400000L   // 17/11/2026 00:00 em GMT-3 (pico das Leônidas)
    private val h = 3_600_000L

    private fun t(hora: Int, uv: List<Pair<Long, Double>> = emptyList(), aqi: Int? = null, codigo: Int = 3) =
        TempoAgora(agora = meiaNoite + hora * h + 60_000L, lat = -23.55, lon = -46.63, codigo = codigo, uv = uv, aqi = aqi)

    /** UV de amanhã (18/11): sobe das 8h ao pico de [pico] às 12h e cai até as 17h. */
    private fun uvAmanha(pico: Double) = (0..23).map { i ->
        val v = when (i) { in 8..16 -> pico * (1 - kotlin.math.abs(i - 12) / 5.0); else -> 0.0 }
        meiaNoite + (24 + i) * h to v
    }

    @Test fun niveisDaOmsEDoAr() {
        assertEquals(listOf(1, 2, 3, 4, 5), listOf(1.0, 4.0, 7.0, 9.0, 11.0).map { Curiosidades.nivelUv(it) })
        assertEquals(listOf(1, 2, 3, 4, 5, 6), listOf(40, 80, 120, 180, 250, 400).map { Curiosidades.nivelAr(it) })
    }

    @Test fun uvDeAmanhaComAFaixaDeProtecao() {
        val itens = Curiosidades.montar(t(21, uv = uvAmanha(10.0)), fuso = fuso)
        val uv = itens.filterIsInstance<Curiosidades.Item.UvAmanha>().single()
        assertEquals(10, uv.valor); assertEquals(4, uv.nivel)
        // UV >= 3 (2,5 arredonda para 3) das 9h às 15h (o bloco das 15h termina às 16h).
        assertEquals(meiaNoite + 33 * h, uv.de); assertEquals(meiaNoite + 40 * h, uv.ate)
        assertTrue(Curiosidades.montar(t(21, uv = uvAmanha(4.0)), fuso = fuso).none { it is Curiosidades.Item.UvAmanha })
    }

    @Test fun uvDeAgoraDeDia() {
        val agora = listOf(meiaNoite + 11 * h to 8.2, meiaNoite + 12 * h to 9.0)
        val uv = Curiosidades.montar(t(11, uv = agora), fuso = fuso).filterIsInstance<Curiosidades.Item.UvAgora>().single()
        assertEquals(8, uv.valor)
    }

    @Test fun arSoQuandoNaoEBom() {
        assertTrue(Curiosidades.montar(t(10, aqi = 30), fuso = fuso).none { it is Curiosidades.Item.Ar })
        assertEquals(Curiosidades.Item.Ar(130, 3), Curiosidades.montar(t(10, aqi = 130), fuso = fuso).filterIsInstance<Curiosidades.Item.Ar>().single())
    }

    @Test fun estacaoPrimeiroEMeteorosNoPico() {
        val p = CeuNoturno.Passagem(meiaNoite + 22 * h, meiaNoite + 22 * h + 300_000, 6, 2, 60)
        val itens = Curiosidades.montar(t(20), estacao = p, fuso = fuso)
        assertEquals(Curiosidades.Item.Estacao(p), itens.first())
        assertTrue(itens.any { it == Curiosidades.Item.Meteoros(CeuNoturno.ChuvaMeteoros.LEONIDAS) })
        // Passagem para daqui a 20 h: longe demais.
        assertTrue(Curiosidades.montar(t(1), estacao = p.copy(inicio = meiaNoite + 21 * h), fuso = fuso).none { it is Curiosidades.Item.Estacao })
    }

    @Test fun planetasSoComCeuAbertoEDeTarde() {
        assertTrue(Curiosidades.montar(t(20, codigo = 3), fuso = fuso).none { it is Curiosidades.Item.Planeta })
        assertTrue(Curiosidades.montar(t(10, codigo = 0), fuso = fuso).none { it is Curiosidades.Item.Planeta })
        assertTrue(Curiosidades.montar(t(20, codigo = 0), fuso = fuso).any { it is Curiosidades.Item.Planeta })
    }
}
