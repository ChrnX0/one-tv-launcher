package br.paulo.tvlauncher.weather

import android.util.Log
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.alarm.FaixaDias
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * Clima pelo MET Norway (api.met.no), o do app da Play (16/11/2026). Por quê: o Open-Meteo grátis é só para uso não
 * comercial, e o MET Norway libera "qualquer fim, inclusive apps pagos", dados CC BY 4.0 (crédito "Data from MET
 * Norway" na tela de Licenças e na loja). Regras dos termos: User-Agent com nome e contato, coordenadas com no máximo
 * 4 casas, respeitar o Expires (cache) e menos de 20 pedidos/s somando todas as TVs.
 *
 * Em vez de reescrever a HOME, a Previsão e os avisos, este arquivo CONVERTE a resposta do MET no mesmo formato que o
 * resto do código já lê da Open-Meteo (current / hourly / daily, horas locais "2026-11-16T15:00"). Quem escolhe a fonte
 * é o [DataSources] (19/11/2026: MET Norway é a fonte do plano Básico, em todo build).
 *
 * Diferenças que ficam (verificado na resposta real de 16/11/2026, uma cidade do Brasil):
 *  - Fora da Escandinávia o MET NÃO manda chance de chuva. A chance aqui é ESTIMADA pela chuva prevista em mm e pelo
 *    símbolo (ver [chanceDaChuva]); é inferência, comparar com o Google antes de confiar.
 *  - Sem visibilidade: vira estimativa pela fração de neblina ([visibilidade]). Sem qualidade do ar (o cartão some).
 *  - Horário: hora a hora nos ~2,5 primeiros dias, depois de 6 em 6 h; cada hora vazia repete o bloco que a cobre.
 *  - UV é o de céu limpo (ultraviolet_index_clear_sky), um teto do real.
 */
object ClimaMet {
    private const val TAG = "TvLauncher.Met"
    /** Exigência dos termos: nome do app e um contato. Sem dado pessoal: o site do projeto. */
    const val AGENTE = Http.USER_AGENT

    // ---------------------------------------------------------------- rede (com cache, como os termos pedem)

    private class Guardado(val chave: String, val corpo: String, val expiraMs: Long, val modificado: String?)
    @Volatile private var guardado: Guardado? = null

    /** Só para o teste de telas: a Previsão medida sem a resposta que a HOME deixou na memória. */
    internal fun esquecerCache() { guardado = null }

    /** 4 casas (~11 m), o máximo que os termos aceitam; mais que isso eles recusam ou contam como pedidos diferentes. */
    fun coord(v: Double): String = String.format(Locale.US, "%.4f", v).trimEnd('0').trimEnd('.')

    /**
     * Previsão do ponto já no formato da Open-Meteo. [desdeAgora] = o "hourly" começa na hora cheia de agora (como o
     * forecast_hours=24 da HOME e dos avisos); false = começa à meia-noite de hoje (como a tela de Previsão, que conta
     * 24 horas por dia). null = sem rede ou resposta estranha. Fora da thread da tela.
     */
    fun previsao(lat: Double, lon: Double, desdeAgora: Boolean, dias: Int = FaixaDias.DIAS): JSONObject? {
        val corpo = buscar(lat, lon) ?: return null
        return try {
            converter(JSONObject(corpo), System.currentTimeMillis(), TimeZone.getDefault(), lat, lon, dias, desdeAgora, Config.USAR_FAHRENHEIT)
        } catch (e: Exception) {
            Log.w(TAG, "MET em formato estranho: ${e.javaClass.simpleName}")
            null
        }
    }

    /** Uma chamada para a HOME, a Previsão e os avisos juntos: guarda até o Expires e depois pergunta com If-Modified-Since. */
    @Synchronized
    private fun buscar(lat: Double, lon: Double): String? {
        val chave = coord(lat) + "," + coord(lon)
        val g = guardado
        val agora = System.currentTimeMillis()
        if (g != null && g.chave == chave && agora < g.expiraMs) return g.corpo
        val c = URL("https://api.met.no/weatherapi/locationforecast/2.0/complete?lat=${coord(lat)}&lon=${coord(lon)}")
            .openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = 8000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", AGENTE)
            if (g != null && g.chave == chave && g.modificado != null) c.setRequestProperty("If-Modified-Since", g.modificado)
            when (c.responseCode) {
                200 -> {
                    val corpo = c.inputStream.bufferedReader().use { it.readText() }
                    // Expires vem em geral ~30 min à frente; sem ele, 15 min. Nunca menos de 5 min (o relógio do clima roda a cada 5).
                    val expira = c.getHeaderFieldDate("Expires", 0L).takeIf { it > agora } ?: (agora + 15 * 60_000L)
                    guardado = Guardado(chave, corpo, maxOf(expira, agora + 5 * 60_000L), c.getHeaderField("Last-Modified"))
                    corpo
                }
                304 -> g!!.corpo.also { guardado = Guardado(chave, it, agora + 10 * 60_000L, g.modificado) }
                203 -> { Log.w(TAG, "MET avisa API obsoleta (203)"); c.inputStream.bufferedReader().use { it.readText() } }
                else -> { Log.w(TAG, "MET respondeu ${c.responseCode}"); g?.takeIf { it.chave == chave }?.corpo }
            }
        } catch (e: Exception) {
            Log.w(TAG, "MET falhou: ${e.javaClass.simpleName}")
            g?.takeIf { it.chave == chave }?.corpo   // sem rede: a última boa do mesmo lugar
        } finally { c.disconnect() }
    }

    // ---------------------------------------------------------------- lógica pura (testada em ClimaMetTest)

    /** Símbolo do MET ("lightrainshowers_day") -> código WMO que IconesClima e o resto do código entendem. */
    fun codigoWmo(simbolo: String?): Int {
        val s = simbolo.orEmpty().substringBefore('_')
        return when {
            s.isEmpty() -> 3
            "thunder" in s -> if (s.startsWith("heavy")) 96 else 95
            s == "clearsky" -> 0
            s == "fair" -> 1
            s == "partlycloudy" -> 2
            s == "cloudy" -> 3
            s == "fog" -> 45
            s == "lightrainshowers" -> 80
            s == "rainshowers" -> 81
            s == "heavyrainshowers" -> 82
            s == "lightrain" -> 61
            s == "rain" -> 63
            s == "heavyrain" -> 65
            "sleet" in s -> if (s.startsWith("heavy")) 67 else 66
            s == "lightsnowshowers" || s == "snowshowers" -> 85
            s == "heavysnowshowers" -> 86
            s == "lightsnow" -> 71
            s == "snow" -> 73
            s == "heavysnow" -> 75
            else -> 3
        }
    }

    /**
     * Chance de chuva ESTIMADA (0-100) para uma hora, pela chuva prevista nela em mm e pelo código. O MET não manda a
     * chance fora da Escandinávia. Os degraus seguem a régua usual de chuva fraca/moderada (0,1 mm/h é o mínimo que
     * molha; 1 mm/h já é chuva de guarda-chuva). Inferência: não comparei com uma fonte de chance de verdade.
     */
    fun chanceDaChuva(mmNaHora: Double, codigo: Int): Int {
        val pelaQuantia = when {
            mmNaHora >= 1.0 -> 90
            mmNaHora >= 0.5 -> 75
            mmNaHora >= 0.2 -> 55
            mmNaHora >= 0.1 -> 40
            mmNaHora > 0.0 -> 25
            else -> 0
        }
        val peloSimbolo = when {
            codigo >= 95 -> 60
            codigo in 51..67 || codigo in 80..82 -> 30
            else -> 0
        }
        return maxOf(pelaQuantia, peloSimbolo)
    }

    /**
     * Suaviza a chance hora a hora: cada hora fica com o maior entre ela e 80% das vizinhas (1 h antes e depois).
     * Chuva prevista às 21 h quer dizer "pode chover por volta das 21 h", não "zero às 20 h". Inferência, como [chanceDaChuva].
     */
    fun suavizar(c: List<Int>): List<Int> = c.indices.map { i ->
        val viz = maxOf(c.getOrElse(i - 1) { 0 }, c.getOrElse(i + 1) { 0 })
        maxOf(c[i], Math.round(viz * 0.8).toInt())
    }

    /** Visibilidade estimada (m) pela fração da área com neblina; o MET não manda visibilidade. 10 km = "10+ km" na tela. */
    fun visibilidade(neblina: Double): Double = when {
        neblina.isNaN() -> 10000.0
        neblina >= 50 -> 1000.0
        neblina >= 10 -> 5000.0
        else -> 10000.0
    }

    private class Passo(val ms: Long, val inst: JSONObject, val simbolo: String?, val mm: Double, val horas: Int)

    /**
     * Resposta do MET -> JSON no formato da Open-Meteo (current, hourly, daily), na hora local de [fuso].
     * [agoraMs] e [fuso] vêm de fora para o teste ser determinístico.
     */
    fun converter(met: JSONObject, agoraMs: Long, fuso: TimeZone, lat: Double, lon: Double, dias: Int,
                  desdeAgora: Boolean, fahrenheit: Boolean): JSONObject {
        val utc = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val local = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = fuso }
        val soDia = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = fuso }
        fun t(c: Double) = if (fahrenheit) c * 9 / 5 + 32 else c

        val serie = met.getJSONObject("properties").getJSONArray("timeseries")
        val passos = (0 until serie.length()).mapNotNull { i ->
            val o = serie.getJSONObject(i)
            val ms = utc.parse(o.getString("time"))?.time ?: return@mapNotNull null
            val d = o.getJSONObject("data")
            val inst = d.getJSONObject("instant").getJSONObject("details")
            val n1 = d.optJSONObject("next_1_hours"); val n6 = d.optJSONObject("next_6_hours")
            val bloco = n1 ?: n6
            Passo(ms, inst, bloco?.optJSONObject("summary")?.optString("symbol_code"),
                bloco?.optJSONObject("details")?.optDouble("precipitation_amount", 0.0) ?: 0.0, if (n1 != null) 1 else 6)
        }.sortedBy { it.ms }
        require(passos.isNotEmpty()) { "MET sem horas" }

        // Hora cheia de agora e meia-noite de hoje, no fuso local.
        val cal = java.util.Calendar.getInstance(fuso).apply { timeInMillis = agoraMs; set(java.util.Calendar.MINUTE, 0)
            set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val horaAgora = cal.timeInMillis
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        val meiaNoite = cal.timeInMillis
        val inicio = if (desdeAgora) horaAgora else meiaNoite
        val total = if (desdeAgora) 24 * dias - ((horaAgora - meiaNoite) / 3_600_000L).toInt() else 24 * dias

        /** O passo que cobre o instante: o último que começou até ele (antes do primeiro, o primeiro). */
        fun passoEm(ms: Long): Passo = passos.lastOrNull { it.ms <= ms } ?: passos.first()

        val hTempo = JSONArray(); val hTemp = JSONArray(); val hChance = JSONArray(); val hCod = JSONArray()
        val hVento = JSONArray(); val hVis = JSONArray(); val hOrv = JSONArray(); val hUv = JSONArray()
        // Por dia (data local): temperaturas vistas, chances, códigos de dia claro, chuva em mm.
        class Acum { var max = Double.NaN; var min = Double.NaN; val chances = ArrayList<Int>(); var codigo = -1; var mm = 0.0 }
        val porDia = LinkedHashMap<String, Acum>()
        val ultimoMs = passos.last().ms + 6 * 3_600_000L
        // Chance de cada hora primeiro, depois suavizada ([suavizar]): a estimativa por mm pula de 0% a 75% de uma hora
        // para a outra (visto em 16/11/2026 contra a Open-Meteo), o que faria a HOME e o aviso de chuva piscarem.
        val brutas = ArrayList<Int>()
        run { var m = meiaNoite; while (m < ultimoMs) { val p = passoEm(m); brutas += chanceDaChuva(p.mm / p.horas, codigoWmo(p.simbolo)); m += 3_600_000L } }
        val chances = suavizar(brutas)
        var ms = meiaNoite
        var idx = 0
        while (porDia.size <= dias && ms < ultimoMs) {
            val p = passoEm(ms)
            val cod = codigoWmo(p.simbolo)
            val mmHora = p.mm / p.horas
            val chance = chances[idx++]
            val dataLocal = soDia.format(java.util.Date(ms))
            val temp = p.inst.optDouble("air_temperature")
            val a = porDia.getOrPut(dataLocal) { Acum() }
            if (porDia.size > dias) break
            if (!temp.isNaN()) { a.max = if (a.max.isNaN()) temp else maxOf(a.max, temp); a.min = if (a.min.isNaN()) temp else minOf(a.min, temp) }
            a.chances += chance
            a.mm += mmHora
            val hora = ((ms - meiaNoite) / 3_600_000L % 24).toInt()
            if (hora in 6..20) a.codigo = maxOf(a.codigo, cod)   // o tempo "do dia": o pior entre 6 h e 20 h
            if (ms >= inicio && hTempo.length() < total) {
                hTempo.put(local.format(java.util.Date(ms)))
                hTemp.put(t(temp)); hChance.put(chance); hCod.put(cod)
                hVento.put(p.inst.optDouble("wind_speed", 0.0) * 3.6)   // m/s -> km/h (a unidade da Open-Meteo)
                hVis.put(visibilidade(p.inst.optDouble("fog_area_fraction")))
                hOrv.put(t(p.inst.optDouble("dew_point_temperature", temp)))
                hUv.put(p.inst.optDouble("ultraviolet_index_clear_sky", 0.0))
            }
            ms += 3_600_000L
        }
        // Máxima/mínima dos blocos de 6 h (o MET manda o extremo do período, que as temperaturas pontuais podem perder).
        for (i in 0 until serie.length()) {
            val o = serie.getJSONObject(i)
            val det = o.getJSONObject("data").optJSONObject("next_6_hours")?.optJSONObject("details") ?: continue
            val inicioBloco = utc.parse(o.getString("time"))?.time ?: continue
            val a = porDia[soDia.format(java.util.Date(inicioBloco + 3 * 3_600_000L))] ?: continue
            det.optDouble("air_temperature_max").takeIf { !it.isNaN() }?.let { a.max = if (a.max.isNaN()) it else maxOf(a.max, it) }
            det.optDouble("air_temperature_min").takeIf { !it.isNaN() }?.let { a.min = if (a.min.isNaN()) it else minOf(a.min, it) }
        }

        val dTempo = JSONArray(); val dMax = JSONArray(); val dMin = JSONArray(); val dCod = JSONArray(); val dChance = JSONArray()
        val dNascer = JSONArray(); val dPor = JSONArray(); val dMm = JSONArray()
        for ((data, a) in porDia.entries.take(dias)) {
            if (a.max.isNaN()) continue
            dTempo.put(data); dMax.put(t(a.max)); dMin.put(t(a.min))
            dCod.put(if (a.codigo >= 0) a.codigo else 3)
            // Meio-termo entre o pico e a média, a mesma régua de FaixaDias.chanceDoDia.
            val pico = a.chances.maxOrNull() ?: 0; val media = if (a.chances.isEmpty()) 0 else a.chances.sum() / a.chances.size
            dChance.put(Math.round((pico + media) / 2.0).toInt())
            dMm.put(Math.round(a.mm * 10) / 10.0)
            val meioDia = soDia.parse(data)!!.time + 12 * 3_600_000L
            val sol = Astronomia.nascerEPor(meioDia, lat, lon, fuso)
            dNascer.put(sol?.let { local.format(java.util.Date(it.first)) } ?: "${data}T06:00")
            dPor.put(sol?.let { local.format(java.util.Date(it.second)) } ?: "${data}T18:00")
        }

        val agora = passoEm(horaAgora)
        val i = agora.inst
        val codAgora = codigoWmo(agora.simbolo)
        val current = JSONObject()
            .put("time", local.format(java.util.Date(horaAgora)))
            .put("temperature_2m", t(i.getDouble("air_temperature")))
            .put("apparent_temperature", t(i.optDouble("apparent_air_temperature", i.getDouble("air_temperature"))))
            .put("relative_humidity_2m", Math.round(i.optDouble("relative_humidity", 0.0)).toInt())
            .put("weather_code", codAgora)
            .put("precipitation", agora.mm / agora.horas)
            .put("pressure_msl", i.optDouble("air_pressure_at_sea_level", 1013.0))
            .put("wind_speed_10m", i.optDouble("wind_speed", 0.0) * 3.6)
            .put("wind_direction_10m", Math.round(i.optDouble("wind_from_direction", 0.0)).toInt())
            .put("is_day", if (Astronomia.alturaSol(agoraMs, lat, lon) > 0) 1 else 0)
        return JSONObject()
            .put("current", current)
            .put("hourly", JSONObject().put("time", hTempo).put("temperature_2m", hTemp).put("precipitation_probability", hChance)
                .put("weather_code", hCod).put("wind_speed_10m", hVento).put("visibility", hVis).put("dew_point_2m", hOrv).put("uv_index", hUv))
            .put("daily", JSONObject().put("time", dTempo).put("temperature_2m_max", dMax).put("temperature_2m_min", dMin)
                .put("weather_code", dCod).put("precipitation_probability_max", dChance).put("sunrise", dNascer).put("sunset", dPor)
                .put("precipitation_sum", dMm))
            .put("fonte", "met.no")
    }
}
