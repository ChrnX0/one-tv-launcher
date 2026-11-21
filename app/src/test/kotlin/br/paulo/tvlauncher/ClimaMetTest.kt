package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.FaixaDias
import br.paulo.tvlauncher.weather.ClimaMet
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ClimaMet: a resposta real do MET Norway (Brasília, 16/11/2026 19:00 UTC) vira o formato da Open-Meteo que o resto lê. */
class ClimaMetTest {
    private val met = JSONObject(javaClass.getResource("/clima/met-brasilia.json")!!.readText())
    private val fuso = TimeZone.getTimeZone("America/Sao_Paulo")
    private val agora = 1794855600000L + 30 * 60_000L   // 16/11/2026 19:30 UTC = 16:30 em Brasília

    @Test fun simbolosViramCodigosWmo() {
        assertEquals(0, ClimaMet.codigoWmo("clearsky_day"))
        assertEquals(1, ClimaMet.codigoWmo("fair_night"))
        assertEquals(2, ClimaMet.codigoWmo("partlycloudy_polartwilight"))
        assertEquals(3, ClimaMet.codigoWmo("cloudy"))
        assertEquals(80, ClimaMet.codigoWmo("lightrainshowers_day"))
        assertEquals(65, ClimaMet.codigoWmo("heavyrain"))
        assertEquals(95, ClimaMet.codigoWmo("rainandthunder"))
        assertEquals(96, ClimaMet.codigoWmo("heavyrainshowersandthunder_day"))
        assertEquals(3, ClimaMet.codigoWmo(null))
    }

    @Test fun chanceSobeComAChuva() {
        assertEquals(0, ClimaMet.chanceDaChuva(0.0, 2))
        assertEquals(30, ClimaMet.chanceDaChuva(0.0, 80))   // símbolo de pancada sem mm: chance baixa, não zero
        assertTrue(ClimaMet.chanceDaChuva(0.1, 80) >= 40)  // 0,1 mm/h já passa do limiar da HOME (40%)
        assertEquals(90, ClimaMet.chanceDaChuva(2.0, 63))
    }

    @Test fun suavizaVizinhas() {
        assertEquals(listOf(60, 75, 60, 0), ClimaMet.suavizar(listOf(0, 75, 0, 0)))
        assertEquals(listOf(55, 55, 44), ClimaMet.suavizar(listOf(55, 55, 0)))
    }

    @Test fun coordenadaComNoMaximo4Casas() {
        assertEquals("-15.7942", ClimaMet.coord(-15.794213))
        assertEquals("-47.88", ClimaMet.coord(-47.88))
    }

    @Test fun converteDesdeAgora() {
        val j = ClimaMet.converter(met, agora, fuso, -15.79, -47.88, FaixaDias.DIAS, desdeAgora = true, fahrenheit = false)
        val h = j.getJSONObject("hourly")
        assertEquals("2026-11-16T16:00", h.getJSONArray("time").getString(0))
        assertEquals("2026-11-16T16:00", j.getJSONObject("current").getString("time"))
        val n = h.getJSONArray("time").length()
        for (k in listOf("temperature_2m", "precipitation_probability", "weather_code", "wind_speed_10m", "visibility", "dew_point_2m", "uv_index"))
            assertEquals(k, n, h.getJSONArray(k).length())
        assertTrue("pelo menos 24 h à frente", n >= 24)
        // As horas seguem de 1 em 1, sem buraco, mesmo onde o MET passa a ser de 6 em 6 h.
        assertEquals("2026-11-17T16:00", h.getJSONArray("time").getString(24))
    }

    @Test fun telaDePrevisaoComecaAMeiaNoite() {
        val j = ClimaMet.converter(met, agora, fuso, -15.79, -47.88, FaixaDias.DIAS, desdeAgora = false, fahrenheit = false)
        val h = j.getJSONObject("hourly"); val d = j.getJSONObject("daily")
        assertEquals("2026-11-16T00:00", h.getJSONArray("time").getString(0))
        assertEquals("2026-11-17T00:00", h.getJSONArray("time").getString(24))   // 24 por dia: a tela conta i / 24
        assertEquals("2026-11-16", d.getJSONArray("time").getString(0))
        assertTrue("vários dias", d.getJSONArray("time").length() >= 7)
        for (i in 0 until d.getJSONArray("time").length()) {
            assertTrue(d.getJSONArray("temperature_2m_max").getDouble(i) >= d.getJSONArray("temperature_2m_min").getDouble(i))
            assertTrue(d.getJSONArray("sunrise").getString(i) < d.getJSONArray("sunset").getString(i))
        }
        // FaixaDias lê o daily convertido como lê o da Open-Meteo.
        assertTrue(FaixaDias.ler(d, null).size >= 7)
    }

    @Test fun fahrenheitConverte() {
        val c = ClimaMet.converter(met, agora, fuso, -15.79, -47.88, 2, true, false).getJSONObject("current").getDouble("temperature_2m")
        val f = ClimaMet.converter(met, agora, fuso, -15.79, -47.88, 2, true, true).getJSONObject("current").getDouble("temperature_2m")
        assertEquals(c * 9 / 5 + 32, f, 0.01)
    }
}
