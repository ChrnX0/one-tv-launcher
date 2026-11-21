package br.paulo.tvlauncher.weather

import android.util.Log
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.alarm.FaixaDias
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * Previsão do plano Pro (19/11/2026): WeatherAPI.com, pedida ao NOSSO servidor (GET api.sofaly.tv/v1/weather, ver
 * server/api/src/weather.js), porque os termos dela proíbem a chave dentro do app. Traz o que o MET Norway (Básico) não
 * tem fora da Escandinávia: chance de chuva de verdade por hora e qualidade do ar.
 *
 * Como o [ClimaMet], CONVERTE a resposta para o formato que o resto do código já lê da Open-Meteo (current / hourly /
 * daily, horas locais "2026-11-19T15:00"), com um bloco a mais: "air_quality": {"us_aqi": N}.
 *
 * Campos usados (li em weatherapi.com/docs/forecast-api e /docs/realtime-api em 19/11/2026): hour.time, temp_c/temp_f,
 * condition.code, chance_of_rain, wind_kph, vis_km, dewpoint_c/f, uv; day.maxtemp_c, mintemp_c, daily_chance_of_rain,
 * totalprecip_mm, condition.code; current.temp_c, feelslike_c, humidity, precip_mm, pressure_mb, wind_kph, wind_degree.
 * hour.time no formato "2026-11-16 20:00": verificado na resposta real no teste de chuva de 16/11 (PR #171).
 * air_quality.pm2_5 / pm10: NÃO verifiquei o nome dos campos (a página não lista); sem eles o cartão do ar some.
 * Códigos de tempo: a tabela oficial weatherapi.com/docs/weather_conditions.json (60 códigos, lida em 19/11/2026).
 */
object WeatherApiSource {
    private const val TAG = "TvLauncher.WeatherApi"
    const val URL_BASE = "https://api.sofaly.tv/v1/weather"
    /** Sem chave no servidor (503): não pergunta de novo por 6 h (a TV fica no MET Norway). */
    private const val PAUSA_MS = 6 * 3_600_000L
    /** Falha passageira (sem rede, 502 da WeatherAPI, 429 do limite do servidor): tenta de novo em 15 min. */
    private const val PAUSA_CURTA_MS = 15 * 60_000L
    /** Exigido no plano Free da WeatherAPI (weatherapi.com/terms.aspx); a tela de Previsão mostra quando os dados vêm dela. */
    const val FONTE = "weatherapi.com"
    /** O servidor guarda 30 min; aqui 15 min basta para a HOME (5 em 5 min), a Previsão e os avisos dividirem uma chamada. */
    private const val GUARDA_MS = 15 * 60_000L

    @Volatile private var pausaAte = 0L
    private class Guardado(val chave: String, val corpo: String, val em: Long)
    @Volatile private var guardado: Guardado? = null

    fun emPausa(agora: Long = System.currentTimeMillis()) = agora < pausaAte

    /** Só para o teste de telas (ver [ClimaMet.esquecerCache]). */
    internal fun esquecerCache() { guardado = null }

    /** Previsão do ponto no formato da Open-Meteo, ou null (sem servidor, sem chave, resposta estranha). Fora da thread da tela. */
    fun previsao(lat: Double, lon: Double, desdeAgora: Boolean, dias: Int = FaixaDias.DIAS): JSONObject? {
        if (emPausa()) return null
        val corpo = buscar(lat, lon, dias) ?: return null
        return try {
            converter(JSONObject(corpo), System.currentTimeMillis(), lat, lon, dias, desdeAgora, Config.USAR_FAHRENHEIT)
        } catch (e: Exception) {
            Log.w(TAG, "formato estranho: ${e.javaClass.simpleName}")
            null
        }
    }

    @Synchronized
    private fun buscar(lat: Double, lon: Double, dias: Int): String? {
        // Sem "days": o servidor sempre devolve todos os dias do plano contratado (uma entrada de cache por lugar) e o
        // [converter] corta no que a tela pediu.
        val chave = ClimaMet.coord(lat) + "," + ClimaMet.coord(lon)
        val agora = System.currentTimeMillis()
        guardado?.takeIf { it.chave == chave && agora - it.em < GUARDA_MS }?.let { return it.corpo }
        val c = URL("$URL_BASE?lat=${ClimaMet.coord(lat)}&lon=${ClimaMet.coord(lon)}").openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", ClimaMet.AGENTE)
            if (c.responseCode == 200) {
                c.inputStream.bufferedReader().use { it.readText() }.also { guardado = Guardado(chave, it, agora) }
            } else {
                val pausa = pausaPara(c.responseCode)
                Log.i(TAG, "servidor respondeu ${c.responseCode}: MET Norway por ${pausa / 60_000} min")
                pausaAte = agora + pausa
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "falhou: ${e.javaClass.simpleName}")
            pausaAte = agora + PAUSA_CURTA_MS   // sem rede: tenta de novo logo
            null
        } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- lógica pura (WeatherApiSourceTest)

    /** 503 = servidor sem a chave (só muda com um deploy): pausa longa. Qualquer outra coisa é passageira. */
    fun pausaPara(status: Int): Long = if (status == 503) PAUSA_MS else PAUSA_CURTA_MS

    /** Quantos dias a previsão convertida tem. */
    fun dias(j: JSONObject): Int = j.optJSONObject("daily")?.optJSONArray("time")?.length() ?: 0

    /**
     * O plano Free da WeatherAPI dá 3 dias e a tela mostra [FaixaDias.DIAS]: os dias (e horas) que faltam vêm do MET
     * Norway, no mesmo formato. Só entra o que é DEPOIS do último dia/hora da WeatherAPI; o "agora" e o ar ficam dela.
     */
    fun completar(wa: JSONObject, met: JSONObject?): JSONObject {
        if (met == null) return wa
        for (bloco in listOf("daily", "hourly")) {
            val a = wa.optJSONObject(bloco) ?: continue
            val b = met.optJSONObject(bloco) ?: continue
            val tA = a.optJSONArray("time") ?: continue
            val tB = b.optJSONArray("time") ?: continue
            val ultimo = if (tA.length() > 0) tA.optString(tA.length() - 1) else ""
            val chaves = a.keys().asSequence().toList()
            if (chaves.any { b.optJSONArray(it)?.length() != tB.length() }) continue   // formato diferente: não mistura
            for (i in 0 until tB.length()) {
                if (tB.optString(i) <= ultimo) continue
                for (k in chaves) a.getJSONArray(k).put(b.getJSONArray(k).get(i))
            }
        }
        return wa
    }

    /** Código da WeatherAPI -> código WMO (o que IconesClima e o resto do código entendem). Desconhecido = nublado. */
    fun codigoWmo(codigo: Int): Int = when (codigo) {
        1000 -> 0
        1003 -> 2
        1006, 1009 -> 3
        in 1012..1048, 1135 -> 45   // névoa, poeira, fumaça: o ícone de neblina (visibilidade baixa)
        1147 -> 48
        1063, 1180, 1240 -> 80
        1150, 1153 -> 51
        1072, 1168 -> 56
        1171 -> 57
        1183 -> 61
        1186, 1189 -> 63
        1192, 1195 -> 65
        1243 -> 81
        1246 -> 82
        1198, 1069, 1204, 1249 -> 66
        1201, 1207, 1252 -> 67
        1066, 1210, 1213 -> 71
        1216, 1219, 1114 -> 73
        1222, 1225, 1117 -> 75
        1237, 1261, 1264 -> 77
        1255 -> 85
        1258 -> 86
        1087, 1273, 1276, 1279, 1282 -> 95
        else -> 3
    }

    /**
     * Índice americano de qualidade do ar (0-500) pelas partículas, a mesma régua do us_aqi da Open-Meteo: o pior entre
     * PM2.5 e PM10 (faixas da EPA, PM2.5 revisado em 2024). Null se nenhum dos dois veio.
     */
    fun aqiAmericano(pm25: Double?, pm10: Double?): Int? {
        fun escala(c: Double, faixas: List<DoubleArray>): Int? {
            if (c.isNaN() || c < 0) return null
            val f = faixas.firstOrNull { c <= it[1] } ?: return 500
            return Math.round((f[3] - f[2]) / (f[1] - f[0]) * (c - f[0]) + f[2]).toInt()
        }
        val p25 = listOf(doubleArrayOf(0.0, 9.0, 0.0, 50.0), doubleArrayOf(9.1, 35.4, 51.0, 100.0), doubleArrayOf(35.5, 55.4, 101.0, 150.0),
            doubleArrayOf(55.5, 125.4, 151.0, 200.0), doubleArrayOf(125.5, 225.4, 201.0, 300.0), doubleArrayOf(225.5, 325.4, 301.0, 500.0))
        val p10 = listOf(doubleArrayOf(0.0, 54.0, 0.0, 50.0), doubleArrayOf(55.0, 154.0, 51.0, 100.0), doubleArrayOf(155.0, 254.0, 101.0, 150.0),
            doubleArrayOf(255.0, 354.0, 151.0, 200.0), doubleArrayOf(355.0, 424.0, 201.0, 300.0), doubleArrayOf(425.0, 604.0, 301.0, 500.0))
        // Valor entre duas faixas (ex. 9,05): vale a de cima, arredondando a concentração como a EPA manda (1 casa / inteiro).
        val a = pm25?.let { escala(Math.floor(it * 10) / 10, p25) }
        val b = pm10?.let { escala(Math.floor(it), p10) }
        return listOfNotNull(a, b).maxOrNull()
    }

    /**
     * Resposta (já pelo nosso servidor) -> JSON no formato da Open-Meteo. As horas da WeatherAPI já vêm no fuso do lugar.
     * [agoraMs] vem de fora para o teste ser determinístico; o "agora" do lugar é o location.localtime_epoch quando houver.
     */
    fun converter(wa: JSONObject, agoraMs: Long, lat: Double, lon: Double, dias: Int, desdeAgora: Boolean, fahrenheit: Boolean): JSONObject {
        val loc = wa.optJSONObject("location")
        val fuso = loc?.optString("tz_id")?.takeIf { it.isNotBlank() }?.let { TimeZone.getTimeZone(it) } ?: TimeZone.getDefault()
        val local = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = fuso }
        val soDia = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = fuso }
        val horaAgora = local.format(java.util.Date(agoraMs)).take(13) + ":00"
        val hoje = soDia.format(java.util.Date(agoraMs))
        fun temp(o: JSONObject, base: String) = o.optDouble(if (fahrenheit) "${base}_f" else "${base}_c")

        val cur = wa.getJSONObject("current")
        val diasA = wa.getJSONObject("forecast").getJSONArray("forecastday")
        require(diasA.length() > 0) { "WeatherAPI sem dias" }

        val hTempo = JSONArray(); val hTemp = JSONArray(); val hChance = JSONArray(); val hCod = JSONArray()
        val hVento = JSONArray(); val hVis = JSONArray(); val hOrv = JSONArray(); val hUv = JSONArray()
        val dTempo = JSONArray(); val dMax = JSONArray(); val dMin = JSONArray(); val dCod = JSONArray(); val dChance = JSONArray()
        val dNascer = JSONArray(); val dPor = JSONArray(); val dMm = JSONArray()
        var usados = 0
        for (i in 0 until diasA.length()) {
            val fd = diasA.getJSONObject(i)
            val data = fd.optString("date")
            if (data < hoje || usados >= dias) continue
            usados++
            val day = fd.getJSONObject("day")
            dTempo.put(data); dMax.put(temp(day, "maxtemp")); dMin.put(temp(day, "mintemp"))
            dCod.put(codigoWmo(day.optJSONObject("condition")?.optInt("code") ?: 0))
            dChance.put(day.optInt("daily_chance_of_rain", 0)); dMm.put(day.optDouble("totalprecip_mm", 0.0))
            val meioDia = soDia.parse(data)!!.time + 12 * 3_600_000L
            val sol = Astronomia.nascerEPor(meioDia, lat, lon, fuso)
            dNascer.put(sol?.let { local.format(java.util.Date(it.first)) } ?: "${data}T06:00")
            dPor.put(sol?.let { local.format(java.util.Date(it.second)) } ?: "${data}T18:00")
            val horas = fd.optJSONArray("hour") ?: continue
            for (j in 0 until horas.length()) {
                val h = horas.getJSONObject(j)
                val t = h.optString("time").replace(' ', 'T').take(16)
                if (t.length != 16 || (desdeAgora && t < horaAgora)) continue
                hTempo.put(t); hTemp.put(temp(h, "temp")); hChance.put(h.optInt("chance_of_rain", 0))
                hCod.put(codigoWmo(h.optJSONObject("condition")?.optInt("code") ?: 0))
                hVento.put(h.optDouble("wind_kph", 0.0)); hVis.put(h.optDouble("vis_km", 10.0) * 1000)
                hOrv.put(temp(h, "dewpoint")); hUv.put(h.optDouble("uv", 0.0))
            }
        }

        val current = JSONObject()
            .put("time", horaAgora)
            .put("temperature_2m", temp(cur, "temp"))
            .put("apparent_temperature", temp(cur, "feelslike").takeIf { !it.isNaN() } ?: temp(cur, "temp"))
            .put("relative_humidity_2m", cur.optInt("humidity", 0))
            .put("weather_code", codigoWmo(cur.optJSONObject("condition")?.optInt("code") ?: 0))
            .put("precipitation", cur.optDouble("precip_mm", 0.0))
            .put("pressure_msl", cur.optDouble("pressure_mb", 1013.0))
            .put("wind_speed_10m", cur.optDouble("wind_kph", 0.0))
            .put("wind_direction_10m", cur.optInt("wind_degree", 0))
            .put("is_day", cur.optInt("is_day", if (Astronomia.alturaSol(agoraMs, lat, lon) > 0) 1 else 0))
        val ar = cur.optJSONObject("air_quality")
        val aqi = ar?.let { aqiAmericano(it.optDouble("pm2_5").takeIf { v -> !v.isNaN() }, it.optDouble("pm10").takeIf { v -> !v.isNaN() }) }
        return JSONObject()
            .put("current", current)
            .put("hourly", JSONObject().put("time", hTempo).put("temperature_2m", hTemp).put("precipitation_probability", hChance)
                .put("weather_code", hCod).put("wind_speed_10m", hVento).put("visibility", hVis).put("dew_point_2m", hOrv).put("uv_index", hUv))
            .put("daily", JSONObject().put("time", dTempo).put("temperature_2m_max", dMax).put("temperature_2m_min", dMin)
                .put("weather_code", dCod).put("precipitation_probability_max", dChance).put("sunrise", dNascer).put("sunset", dPor)
                .put("precipitation_sum", dMm))
            .also { if (aqi != null) it.put("air_quality", JSONObject().put("us_aqi", aqi)) }
            .put("fonte", FONTE)
    }
}
