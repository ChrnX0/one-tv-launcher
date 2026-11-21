package br.paulo.tvlauncher

import br.paulo.tvlauncher.weather.WeatherApiSource
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WeatherApiSource (plano Pro, 19/11/2026): a resposta da WeatherAPI (pelo api.sofaly.tv) vira o formato da Open-Meteo.
 * A amostra é montada com os campos da documentação (weatherapi.com/docs/forecast-api); não é uma resposta real.
 */
class WeatherApiSourceTest {
    // 19/11/2026 14:30 em São Paulo (17:30 UTC).
    private val agora = 1795109400000L

    private fun hora(data: String, h: Int, temp: Double, chance: Int, codigo: Int) = JSONObject()
        .put("time", "%s %02d:00".format(data, h)).put("temp_c", temp).put("temp_f", temp * 9 / 5 + 32)
        .put("condition", JSONObject().put("code", codigo)).put("chance_of_rain", chance).put("wind_kph", 10.0)
        .put("vis_km", 10.0).put("dewpoint_c", 15.0).put("dewpoint_f", 59.0).put("uv", 5.0)

    private fun dia(data: String, max: Double, min: Double, chance: Int, codigo: Int) = JSONObject()
        .put("date", data)
        .put("day", JSONObject().put("maxtemp_c", max).put("mintemp_c", min).put("maxtemp_f", max * 9 / 5 + 32).put("mintemp_f", min * 9 / 5 + 32)
            .put("daily_chance_of_rain", chance).put("totalprecip_mm", 3.2).put("condition", JSONObject().put("code", codigo)))
        .put("hour", JSONArray().apply { for (h in 0..23) put(hora(data, h, 20.0 + h / 2.0, if (h >= 16) 80 else 10, if (h >= 16) 1243 else 1003)) })

    private val amostra = JSONObject()
        .put("location", JSONObject().put("tz_id", "America/Sao_Paulo"))
        .put("current", JSONObject().put("temp_c", 27.0).put("temp_f", 80.6).put("feelslike_c", 29.0).put("feelslike_f", 84.2)
            .put("humidity", 60).put("condition", JSONObject().put("code", 1003)).put("precip_mm", 0.0).put("pressure_mb", 1012.0)
            .put("wind_kph", 12.0).put("wind_degree", 90).put("is_day", 1)
            .put("air_quality", JSONObject().put("pm2_5", 12.0).put("pm10", 20.0)))
        .put("forecast", JSONObject().put("forecastday", JSONArray()
            .put(dia("2026-11-18", 30.0, 18.0, 0, 1000))   // ontem: fica de fora
            .put(dia("2026-11-19", 31.0, 19.0, 85, 1243))
            .put(dia("2026-11-20", 28.0, 18.0, 40, 1063))
            .put(dia("2026-11-21", 26.0, 17.0, 10, 1006))))

    private fun converter(desdeAgora: Boolean = true, dias: Int = 9, f: Boolean = false) =
        WeatherApiSource.converter(amostra, agora, -23.55, -46.63, dias, desdeAgora, f)

    @Test fun codigosViramWmo() {
        assertEquals(0, WeatherApiSource.codigoWmo(1000))
        assertEquals(2, WeatherApiSource.codigoWmo(1003))
        assertEquals(3, WeatherApiSource.codigoWmo(1009))
        assertEquals(45, WeatherApiSource.codigoWmo(1135))
        assertEquals(45, WeatherApiSource.codigoWmo(1030))
        assertEquals(61, WeatherApiSource.codigoWmo(1183))
        assertEquals(81, WeatherApiSource.codigoWmo(1243))
        assertEquals(95, WeatherApiSource.codigoWmo(1276))
        assertEquals(75, WeatherApiSource.codigoWmo(1225))
        assertEquals(3, WeatherApiSource.codigoWmo(9999))
    }

    @Test fun aqiPelaReguaDaEpa() {
        assertEquals(50, WeatherApiSource.aqiAmericano(9.0, null))
        assertEquals(51, WeatherApiSource.aqiAmericano(9.1, null))
        assertEquals(100, WeatherApiSource.aqiAmericano(35.4, null))
        assertEquals(50, WeatherApiSource.aqiAmericano(null, 54.0))
        assertEquals(500, WeatherApiSource.aqiAmericano(900.0, null))
        assertEquals(56, WeatherApiSource.aqiAmericano(12.0, 20.0))   // o pior dos dois
        assertNull(WeatherApiSource.aqiAmericano(null, null))
    }

    @Test fun formatoDaOpenMeteo() {
        val j = converter()
        val c = j.getJSONObject("current")
        assertEquals("2026-11-19T14:00", c.getString("time"))
        assertEquals(27.0, c.getDouble("temperature_2m"), 0.01)
        assertEquals(29.0, c.getDouble("apparent_temperature"), 0.01)
        assertEquals(2, c.getInt("weather_code"))
        assertEquals(56, j.getJSONObject("air_quality").getInt("us_aqi"))
        assertEquals("weatherapi.com", j.getString("fonte"))

        val d = j.getJSONObject("daily")
        assertEquals("2026-11-19", d.getJSONArray("time").getString(0))   // ontem ficou de fora
        assertEquals(3, d.getJSONArray("time").length())
        assertEquals(85, d.getJSONArray("precipitation_probability_max").getInt(0))
        assertEquals(81, d.getJSONArray("weather_code").getInt(0))
        assertTrue(d.getJSONArray("sunrise").getString(0).startsWith("2026-11-19T05:"))

        val h = j.getJSONObject("hourly")
        assertEquals("2026-11-19T14:00", h.getJSONArray("time").getString(0))   // desde a hora cheia de agora
        assertEquals(10 + 24 * 2, h.getJSONArray("time").length())
        assertEquals(80, h.getJSONArray("precipitation_probability").getInt(2))   // 16 h: a chance de verdade
        assertEquals(10000.0, h.getJSONArray("visibility").getDouble(0), 0.1)
    }

    @Test fun desdeMeiaNoiteECorteDeDias() {
        val j = converter(desdeAgora = false, dias = 2)
        assertEquals("2026-11-19T00:00", j.getJSONObject("hourly").getJSONArray("time").getString(0))
        assertEquals(48, j.getJSONObject("hourly").getJSONArray("time").length())
        assertEquals(2, j.getJSONObject("daily").getJSONArray("time").length())
    }

    @Test fun fahrenheitESemAr() {
        val sem = JSONObject(amostra.toString()).apply { getJSONObject("current").remove("air_quality") }
        val j = WeatherApiSource.converter(sem, agora, -23.55, -46.63, 9, true, true)
        assertEquals(80.6, j.getJSONObject("current").getDouble("temperature_2m"), 0.01)
        assertFalse(j.has("air_quality"))
    }

    @Test fun pausaLongaSoSemChave() {
        assertEquals(6 * 3_600_000L, WeatherApiSource.pausaPara(503))
        for (c in listOf(400, 429, 500, 502)) assertEquals(15 * 60_000L, WeatherApiSource.pausaPara(c))
    }

    private fun previsao(dias: List<String>, horas: List<String>, fonte: String) = JSONObject()
        .put("daily", JSONObject().put("time", JSONArray(dias)).put("temperature_2m_max", JSONArray(dias.map { fonte })))
        .put("hourly", JSONObject().put("time", JSONArray(horas)).put("temperature_2m", JSONArray(horas.map { fonte })))
        .put("fonte", fonte)

    @Test fun completarComMetSoDepoisDoUltimoDia() {
        val wa = previsao(listOf("2026-11-19", "2026-11-20", "2026-11-21"), listOf("2026-11-21T23:00"), "wa")
        val met = previsao((19..27).map { "2026-11-$it" }, listOf("2026-11-21T23:00", "2026-11-22T00:00", "2026-11-22T06:00"), "met")
        val j = WeatherApiSource.completar(wa, met)
        assertEquals(9, WeatherApiSource.dias(j))
        val max = j.getJSONObject("daily").getJSONArray("temperature_2m_max")
        assertEquals("wa", max.getString(2)); assertEquals("met", max.getString(3))
        val horas = j.getJSONObject("hourly").getJSONArray("time")
        assertEquals(listOf("2026-11-21T23:00", "2026-11-22T00:00", "2026-11-22T06:00"), (0 until horas.length()).map { horas.getString(it) })
        assertEquals("wa", j.getString("fonte"))   // o crédito continua sendo da WeatherAPI
    }

    @Test fun completarSemMetOuFormatoDiferenteNaoMexe() {
        val wa = previsao(listOf("2026-11-19"), listOf(), "wa")
        assertEquals(1, WeatherApiSource.dias(WeatherApiSource.completar(wa, null)))
        val torto = previsao(listOf("2026-11-19", "2026-11-20"), listOf(), "met")
        torto.getJSONObject("daily").put("temperature_2m_max", JSONArray())
        assertEquals(1, WeatherApiSource.dias(WeatherApiSource.completar(wa, torto)))
    }
}
