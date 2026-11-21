package br.paulo.tvlauncher

import br.paulo.tvlauncher.screensaver.TempoAgora
import br.paulo.tvlauncher.screensaver.WidgetsTempo
import br.paulo.tvlauncher.weather.FraseTempo
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FraseTempoTest {
    private val fuso = TimeZone.getTimeZone("GMT-3")
    private val meiaNoite = 1794884400000L   // 17/11/2026 00:00 em GMT-3
    private val h = 3_600_000L
    private val m15 = 15 * 60_000L

    private fun t(hora: Int, codigo: Int = 1, chovendo: Boolean = false, chuva15: List<Double> = emptyList(),
        horasChuva: List<Int> = emptyList(), max: Int? = 28, min: Int? = 18, amanhaMin: Int? = 18, umidade: Int? = 60,
        amanhaChuva: Int? = null, codigos: List<Int> = emptyList()): TempoAgora {
        val agora = meiaNoite + hora * h
        val inicio = meiaNoite + hora * h
        return TempoAgora(agora = agora + 60_000L, lat = -23.55, lon = -46.63, codigo = codigo, chovendo = chovendo,
            umidade = umidade, max = max, min = min, amanhaMin = amanhaMin, amanhaChuva = amanhaChuva,
            horas = (horasChuva.indices).map { i -> WidgetsTempo.Marca(inicio + (i + 1) * h, 20, codigos.getOrElse(i) { 1 }, horasChuva[i]) },
            chuva15 = chuva15.mapIndexed { i, mm -> inicio + i * m15 to mm })
    }

    @Test fun chuvaPorMinutosVenceTudo() {
        val f = FraseTempo.escolher(t(21, chuva15 = listOf(0.0, 0.0, 0.5, 1.0), horasChuva = listOf(80, 80)), fuso)
        // Bloco das 21:30, agora 21:01: 29 min -> "uns 30 min".
        assertEquals(FraseTempo.Frase.ChuvaEmMinutos(30), f)
    }

    @Test fun paraQuandoDoisBlocosSecos() {
        val f = FraseTempo.escolher(t(21, chovendo = true, chuva15 = listOf(1.0, 0.0, 0.8, 0.0, 0.0)), fuso)
        assertEquals(FraseTempo.Frase.ParaEmMinutos(45), f)   // o bloco seco das 21:15 é só trégua; para às 21:45
    }

    @Test fun semMinutosUsaAHoraAHora() {
        val f = FraseTempo.escolher(t(14, horasChuva = listOf(10, 20, 70)), fuso)
        assertEquals(FraseTempo.Frase.ChuvaPorVolta(meiaNoite + 17 * h), f)
    }

    @Test fun trovoadaGeadaCalorNeblina() {
        assertTrue(FraseTempo.escolher(t(14, horasChuva = listOf(10, 20), codigos = listOf(1, 95)), fuso) is FraseTempo.Frase.Trovoada)
        assertEquals(FraseTempo.Frase.Geada(hoje = true), FraseTempo.escolher(t(20, amanhaMin = 2), fuso))
        assertEquals(FraseTempo.Frase.Geada(hoje = false), FraseTempo.escolher(t(7, min = 1), fuso))
        assertEquals(FraseTempo.Frase.CalorForte(36), FraseTempo.escolher(t(11, max = 36, umidade = 80), fuso))
        assertEquals(FraseTempo.Frase.Neblina, FraseTempo.escolher(t(7, codigo = 45), fuso))
    }

    @Test fun fahrenheitConverteOsLimites() {
        // 95 °F = 35 °C: calor forte; 34 °F = 1 °C: geada.
        assertEquals(FraseTempo.Frase.CalorForte(95), FraseTempo.escolher(t(11, max = 95, min = 70, umidade = 80).copy(celsius = false), fuso))
        assertEquals(FraseTempo.Frase.Geada(hoje = true), FraseTempo.escolher(t(20, max = 50, amanhaMin = 34).copy(celsius = false), fuso))
        assertNull(FraseTempo.escolher(t(11, max = 80, min = 60, umidade = 80).copy(celsius = false), fuso))
    }

    @Test fun secarRoupaSoComTempoFirme() {
        val firme = List(8) { 5 }
        assertEquals(FraseTempo.Frase.SecarRoupa, FraseTempo.escolher(t(9, horasChuva = firme), fuso))
        assertNull(FraseTempo.escolher(t(9, horasChuva = firme, umidade = 85), fuso))
        assertNull(FraseTempo.escolher(t(9, horasChuva = List(8) { 25 }), fuso))
    }

    @Test fun noiteFriaAmanhaChoveCeuLimpo() {
        assertEquals(FraseTempo.Frase.NoiteFria(8), FraseTempo.escolher(t(18, amanhaMin = 8), fuso))
        assertEquals(FraseTempo.Frase.AmanhaChove(80), FraseTempo.escolher(t(18, amanhaChuva = 80), fuso))
        assertEquals(FraseTempo.Frase.CeuLimpo, FraseTempo.escolher(t(21, codigo = 0), fuso))
        assertNull(FraseTempo.escolher(t(21, codigo = 2), fuso))
    }
}
