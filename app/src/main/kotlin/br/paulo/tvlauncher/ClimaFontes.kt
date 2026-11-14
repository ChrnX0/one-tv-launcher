package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * O clima da HOME: busca (Open-Meteo + METAR do aeroporto + mediana de 3 modelos) e decide temperatura, ícone e a linha
 * de chuva. Saiu da MainActivity em 12/11/2026 sem mudar a conta; a tela só recebe o [Resultado]. Chamar fora da thread da tela.
 */
object ClimaFontes {
    /** O que a HOME mostra e guarda nas prefs "clima". [amanha] = máx, mín, código, chance (nulo sem o 2º dia). */
    class Resultado(val ponto: Localizacao.Ponto, val temp: Int, val umidade: Int?, val max: Int, val min: Int,
        val amanha: List<Int>?, val codigo: Int, val chovendo: Boolean, val eDia: Boolean, val picoChuva: Int,
        val linha: String, val icone: String, val horas: String = "")

    @Volatile private var ultimaMedida: Medida? = null   // a última leitura boa do aeroporto
    @Volatile private var ultimaMedidaMs = 0L

    /** null = sem lugar ou sem resposta da Open-Meteo (a HOME mantém o que mostra). Erro de formato sobe como exceção. */
    fun buscar(ctx: Context): Resultado? {
        // Onde estamos: Wi-Fi do Android (±dezenas de m) > IP > Config.kt. Ver Localizacao.kt.
        val ponto = Localizacao.atual(ctx) { httpGet(it) } ?: return null
        val unidade = if (Config.USAR_FAHRENHEIT) "&temperature_unit=fahrenheit" else ""
        // Previsao (minima/maxima, chance de chuva, ceu) do modelo da Open-Meteo, no ponto exato.
        val url = "https://api.open-meteo.com/v1/forecast" +
            "?latitude=${ponto.lat}&longitude=${ponto.lon}" +
            "&current=temperature_2m,relative_humidity_2m,weather_code,precipitation,is_day" +
            "&hourly=precipitation_probability,temperature_2m,weather_code&forecast_hours=24" +
            "&daily=temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max" +
            "&timezone=auto&forecast_days=${FaixaDias.DIAS}$unidade"
        val json = JSONObject(httpGet(url) ?: return null)
        val atual = json.getJSONObject("current")
        val dia = json.getJSONObject("daily")
        // Temperatura: a MEDIDA na estacao do aeroporto (METAR) vence a do modelo da Open-Meteo. Em 31/10 as 16:15
        // o modelo dizia 26,6 °C e o aeroporto da cidade mediu 32 °C. Sem METAR recente, fica o modelo.
        // Se o METAR falhar (timeout acontece), vale a ultima medida boa de ate 60 min, e nao o modelo: em 01/11 as 19:13 e 19:20
        // o METAR deu timeout e o modelo mostrou 24 C e "chovendo agora" com o aeroporto em 31 C, CAVOK.
        val nova = medidaAeroporto(ponto) ?: medidaAeroporto(ponto)   // uma segunda tentativa
        if (nova != null) { ultimaMedida = nova; ultimaMedidaMs = System.currentTimeMillis() }
        val medida = nova ?: ultimaMedida?.takeIf { System.currentTimeMillis() - ultimaMedidaMs < 60 * 60_000L }
        registrarComparacao(ctx, ponto, nova)
        // Umidade: a do aeroporto (calculada da temperatura e do ponto de orvalho medidos); sem ela, a do modelo.
        val umidade = medida?.umidade ?: atual.optInt("relative_humidity_2m", -1).takeIf { it >= 0 }
        val temp = medida?.temp ?: Math.round(atual.getDouble("temperature_2m")).toInt()
        // Minima/maxima do dia vem do modelo; se a medida de agora ja passou delas, o modelo errou: corrige com ela.
        val max = maxOf(temp, Math.round(dia.getJSONArray("temperature_2m_max").getDouble(0)).toInt())
        val min = minOf(temp, Math.round(dia.getJSONArray("temperature_2m_min").getDouble(0)).toInt())
        // Amanhã (índice 1 das listas diárias), para o protetor de tela. Nulo se a API não mandou o 2º dia.
        val amanha = try {
            listOf(Math.round(dia.getJSONArray("temperature_2m_max").getDouble(1)).toInt(),
                Math.round(dia.getJSONArray("temperature_2m_min").getDouble(1)).toInt(),
                dia.getJSONArray("weather_code").getInt(1),
                dia.getJSONArray("precipitation_probability_max").optInt(1, -1))
        } catch (e: Exception) { null }
        // Tempo AGORA: o aeroporto (o que um observador VIU) vence o modelo. Em 31/10 as 16:54 o modelo dizia
        // "garoa, chovendo agora" com ceu aberto; o aeroporto dizia CAVOK (sem chuva).
        var codigo = atual.getInt("weather_code")
        var chovendoAgora = atual.optDouble("precipitation", 0.0) >= 0.2 && codigo >= 51   // so o modelo (sem METAR): exige chuva de verdade, nao qualquer traco
        if (medida != null) {
            val visto = medida.fenomeno
            // O aeroporto vence o modelo SEMPRE que ha observacao: chuva/neblina (visto), ou, sem nada disso,
            // a nebulosidade que ele mediu (medida.nuvens). Antes so corrigia o modelo quando codigo >= 45
            // (chuva/neblina inventada); um "nublado" (codigo 1-3) errado do modelo ficava sem correcao —
            // bug visto em 03/11: modelo dizia nublado com o aeroporto (e o ceu real) limpo.
            codigo = visto ?: medida.nuvens
            chovendoAgora = visto != null && visto >= 51
        }
        // Dia/noite calculado AQUI, nao pelo "is_day" da Open-Meteo: ele vira noite no instante exato do por do
        // sol, sem o crepusculo civil (a claridade que sobra no ceu por uns 20 min depois). Flagrado em 03/11
        // as 18:38 (por as 18:24, icone ja de lua, ceu da varanda ainda claro). Usa o nascer/por REAL do lugar
        // (Astronomia.nascerEPor) com margem so no por (Config.MARGEM_CREPUSCULO_MIN); cai no is_day da API
        // se o calculo falhar (nao deve, exceto perto dos polos).
        val agoraMs = System.currentTimeMillis()
        val eDia = Astronomia.eDia(agoraMs, ponto.lat, ponto.lon, atual.optInt("is_day", 1) == 1)
        val (iconeDoTempo, descricao) = descricaoClima(codigo, eDia, chovendoAgora)
        // A linha informativa do clima: se chove agora ou ha previsao de chuva HOJE (primeira hora com chance >= 40%),
        // diz quando; senao, diz a condicao do tempo de agora (ex.: "Poucas nuvens").
        // Chance de chuva: a MEDIANA de ECMWF+ICON+GFS, nao o best_match (≈ECMWF) sozinho. Medido no CSV de comparacao:
        // em horas que o aeroporto marcou SEM chuva, o ECMWF deu 49-75% enquanto ICON/GFS davam 10-18%. A mediana
        // dos tres descarta esse exagero. Se a chamada multi-modelo falhar, cai no best_match da chamada principal.
        val medModelo = chuvaMediana(ponto)
        val horasData: List<String>; val horasChuva: List<Int>
        if (medModelo != null) { horasData = medModelo.horas; horasChuva = medModelo.chuva }
        else {
            val hc = json.getJSONObject("hourly").getJSONArray("precipitation_probability")
            val ht = json.getJSONObject("hourly").getJSONArray("time")
            horasChuva = (0 until hc.length()).map { hc.optInt(it, 0) }
            horasData = (0 until ht.length()).map { ht.getString(it) }
        }
        val hoje = atual.optString("time", "").take(10)   // "2026-11-01"
        var primeira = -1   // quantas horas faltam para a primeira hora de hoje com chance de chuva >= 40%
        var picoChuva = 0
        for (i in horasChuva.indices) {
            if (i >= horasData.size || !horasData[i].startsWith(hoje)) break   // so ate o fim do dia
            val p = horasChuva[i]
            if (p >= 40) { primeira = i; picoChuva = p; break }
        }
        val textoLinha = when {
            chovendoAgora -> ctx.getString(R.string.home_chovendo)
            primeira == 0 -> ctx.getString(R.string.home_chuva_agora, descricao, picoChuva)
            primeira > 0 -> ctx.getString(R.string.home_chuva_em, descricao, picoChuva, primeira)
            else -> descricao
        }
        // De noite, ceu limpo/poucas nuvens/parcial mostram a lua na fase de hoje no lugar do sol
        // (se estiver chovendo, vale a chuva, como de dia). Chuva, neblina etc. mantem o proprio icone.
        val icone = iconeDoClima(codigo, eDia, chovendoAgora, ponto.lat)
        // Decodifica e apara o ícone AQUI, fora da thread da tela (04/11/2026: um ANR de 5 s do launcher tinha
        // IconesClima.aparar na pilha da thread principal). Na tela, bitmap() já acha pronto no cache.
        if (IconesClima.ehIcone(icone)) IconesClima.bitmap(ctx, icone)
        return Resultado(ponto, temp, umidade, max, min, amanha, codigo, chovendoAgora, eDia, picoChuva, textoLinha, icone,
            proximasHoras(json, atual.optString("time", ""), horasData, horasChuva))
    }

    /**
     * As próximas 18 horas para a barra do protetor (8 marcas de 2 em 2) (12/11/2026, dono escolheu "próximas horas"): "hora|temp|código|chuva"
     * separadas por ";", a partir da hora cheia de agora, ex. "2026-11-12T19:00|22|3|40". Temperatura e céu do modelo
     * (mesmo pedido à Open-Meteo, sem chamada nova); chance de chuva da mediana dos 3 modelos quando houver, como a HOME.
     */
    private fun proximasHoras(json: JSONObject, agora: String, horasData: List<String>, horasChuva: List<Int>): String = try {
        val h = json.getJSONObject("hourly")
        val tempos = h.getJSONArray("time"); val temps = h.getJSONArray("temperature_2m"); val cods = h.getJSONArray("weather_code")
        val chuvaPorHora = horasData.zip(horasChuva).toMap()
        val horaAgora = agora.take(13)   // "2026-11-12T18"
        (0 until tempos.length()).map { tempos.getString(it) }.withIndex()
            .filter { it.value.take(13) >= horaAgora }.take(18)
            .joinToString(";") { (i, t) ->
                val chuva = chuvaPorHora[t] ?: h.optJSONArray("precipitation_probability")?.optInt(i, -1) ?: -1
                "$t|${Math.round(temps.getDouble(i))}|${cods.optInt(i, 0)}|$chuva"
            }
    } catch (e: Exception) { "" }

    /**
     * Ícone do clima. De noite, céu limpo mostra a lua na fase de hoje (com nuvens, o ícone lua+nuvem); chuva, neblina etc.
     * mantêm o próprio ícone. [lat] do lugar real, não Config.LATITUDE: o desenho da fase depende do hemisfério.
     */
    fun iconeDoClima(codigo: Int, eDia: Boolean, chovendo: Boolean, lat: Double): String {
        if (!eDia && codigo == 0 && !chovendo) return Lua.chaveIcone(System.currentTimeMillis(), lat)
        return descricaoClima(codigo, eDia, chovendo).first   // (se diz "nublado" mas chove, descricaoClima já devolve a chuva)
    }

    /** Códigos de clima da Open-Meteo (padrão WMO) -> chave do ícone (IconesClima.kt) + texto. O eclipse (ICONE_ECLIPSE) só entra em atualizarEclipse(). */
    fun descricaoClima(codigo: Int, dia: Boolean = true, chovendo: Boolean = false): Pair<String, String> {
        val texto = IconesClima.texto(codigo)
        return IconesClima.chave(codigo, dia, chovendo) to texto
    }

    private var ultimaComparacaoMs = 0L

    /**
     * TESTE DE FONTES (so grava, nao muda o que a tela mostra): a cada 30 min guarda uma linha em
     * Android/data/br.paulo.tvlauncher/files/comparacao_clima.csv com o que o aeroporto MEDIU (temperatura e fenomeno) e o que tres
     * modelos da Open-Meteo (ECMWF, ICON, GFS) PREVIAM para esta hora (temperatura e chance de chuva). Depois de alguns dias da para ver
     * qual modelo chega mais perto da medida. Chamar fora da thread da tela.
     */
    /**
     * Chance de chuva por hora (24 h) como MEDIANA de tres modelos (ECMWF, ICON, GFS). Uma chamada so a Open-Meteo,
     * com ?models=... : cada variavel volta com sufixo por modelo (precipitation_probability_ecmwf_ifs025, ...),
     * e o eixo "time" e compartilhado. null se a chamada falhar (quem chama cai no best_match). Nao verifiquei todos
     * os casos de borda da API; por isso o try e o fallback.
     */
    /** Chance de chuva pela mediana dos 3 modelos: [chuva] hora a hora (24 h, alinhada com [horas]) e [chuvaDias] por dia. */
    private class ChuvaModelos(val horas: List<String>, val chuva: List<Int>, val chuvaDias: List<Int>?)

    private fun chuvaMediana(ponto: Localizacao.Ponto): ChuvaModelos? = try {
        val modelos = listOf("ecmwf_ifs025", "icon_global", "gfs_seamless")
        // Na mesma chamada, a chance máxima de cada dia (para a faixa de 9 dias): o mesmo exagero do ECMWF vale por dia.
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${ponto.lat}&longitude=${ponto.lon}" +
            "&hourly=precipitation_probability&forecast_hours=24" +
            "&daily=precipitation_probability_max,precipitation_probability_mean&forecast_days=${FaixaDias.DIAS}" +
            "&timezone=auto&models=${modelos.joinToString(",")}"
        val json = JSONObject(httpGet(url) ?: throw IllegalStateException("sem resposta"))
        val h = json.getJSONObject("hourly")
        val tempo = h.getJSONArray("time")
        val arrays = modelos.mapNotNull { h.optJSONArray("precipitation_probability_$it") }
        if (arrays.isEmpty()) throw IllegalStateException("sem modelos")
        val datas = (0 until tempo.length()).map { tempo.getString(it) }
        fun valores(a: org.json.JSONArray, n: Int) = (0 until n).map { i -> if (i < a.length() && !a.isNull(i)) a.optInt(i, -1).takeIf { it >= 0 } else null }
        // A mesma conta do aviso de chuva (AvisoChuva.mediana), para a HOME e o aviso nunca discordarem.
        val porModelo = arrays.map { valores(it, tempo.length()) }
        val d = json.optJSONObject("daily")
        fun porDia(campo: String) = d?.let { dj -> modelos.mapNotNull { dj.optJSONArray("${campo}_$it") }.map { valores(it, it.length()) } }
            ?.takeIf { it.isNotEmpty() }?.let { AvisoChuva.mediana(it) }
        ChuvaModelos(datas, AvisoChuva.mediana(porModelo),
            FaixaDias.chanceDoDia(porDia("precipitation_probability_max"), porDia("precipitation_probability_mean")))
    } catch (e: Exception) {
        Log.w("TvLauncher.Local", "chuva multi-modelo falhou: ${e.javaClass.simpleName}")
        null
    }

    private fun registrarComparacao(ctx: Context, ponto: Localizacao.Ponto, medida: Medida?) {
        val agora = System.currentTimeMillis()
        // ultimaComparacaoMs zerava a cada abertura do app -> gravava linha repetida a cada boot. Agora o carimbo
        // persiste em SharedPreferences, entao vale o intervalo de 30 min mesmo apos reiniciar o launcher.
        val prefs = ctx.getSharedPreferences("clima", Context.MODE_PRIVATE)
        if (ultimaComparacaoMs == 0L) ultimaComparacaoMs = prefs.getLong("ultima_comparacao", 0)
        if (agora - ultimaComparacaoMs < 30 * 60_000L) return
        try {
            val modelos = listOf("ecmwf_ifs025", "icon_global", "gfs_seamless")
            val url = "https://api.open-meteo.com/v1/forecast?latitude=${ponto.lat}&longitude=${ponto.lon}" +
                "&hourly=precipitation_probability,temperature_2m&forecast_hours=1&timezone=auto&models=${modelos.joinToString(",")}"
            val h = JSONObject(httpGet(url) ?: return).getJSONObject("hourly")
            val colunas = modelos.flatMap { m ->
                listOf(h.getJSONArray("temperature_2m_$m").optString(0), h.getJSONArray("precipitation_probability_$m").optString(0))
            }
            val linha = (listOf(java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(agora)),
                medida?.temp?.toString() ?: "", medida?.fenomeno?.toString() ?: "") + colunas).joinToString(",")
            val arq = java.io.File(ctx.getExternalFilesDir(null), "comparacao_clima.csv")
            if (!arq.exists()) arq.writeText("hora,metar_temp,metar_fenomeno,ecmwf_temp,ecmwf_chuva,icon_temp,icon_chuva,gfs_temp,gfs_chuva\n")
            arq.appendText(linha + "\n")
            ultimaComparacaoMs = agora
            prefs.edit().putLong("ultima_comparacao", agora).apply()
        } catch (e: Exception) {
            Log.w("TvLauncher.Local", "comparacao falhou: ${e.javaClass.simpleName}")
        }
    }

    /**
     * O que o aeroporto mediu: temperatura (ja na unidade do Config), umidade relativa em % (null se faltar o orvalho),
     * [fenomeno] = codigo WMO do tempo observado (95 trovoada, 80 pancada, 61 chuva, 51 garoa, 45 neblina; null = nada) e
     * [nuvens] = codigo WMO da nebulosidade (1 poucas, 2 parcial, 3 nublado).
     */
    private class Medida(val temp: Int, val umidade: Int?, val fenomeno: Int?, val nuvens: Int)

    /**
     * Ultimo boletim METAR (todo aeroporto publica, em geral a cada hora) da estacao MAIS PERTO de [ponto] num raio
     * de ~65 km, com boletim de no maximo 90 min. Em geral e o aeroporto da cidade ou o mais proximo (METAR existe em
     * quase todo aeroporto; estacoes meteorologicas automaticas nem sempre). Config.ESTACAO_METAR fixa uma estacao.
     * null se nao houver estacao valida ou a busca falhar: ai fica o modelo. Chamar fora da thread da tela.
     */
    private fun medidaAeroporto(ponto: Localizacao.Ponto): Medida? {
        return try {
            val filtro = if (Config.ESTACAO_METAR.isNotEmpty()) "ids=${Config.ESTACAO_METAR}"
                // A API recusa (erro 400) coordenadas com muitas casas decimais: vao com 2 (~1 km, sobra para uma caixa de 130 km).
                else "bbox=" + listOf(ponto.lat - 0.6, ponto.lon - 0.6, ponto.lat + 0.6, ponto.lon + 0.6)
                    .joinToString(",") { String.format(java.util.Locale.US, "%.2f", it) }
            val lista = JSONArray(httpGet("https://aviationweather.gov/api/data/metar?$filtro&format=json") ?: return null)
            val agoraS = System.currentTimeMillis() / 1000
            val boletim = (0 until lista.length()).mapNotNull { lista.optJSONObject(it) }
                .filter { it.has("temp") && (agoraS - it.optLong("obsTime")) / 60 <= 90 }
                .minByOrNull { Localizacao.km(ponto.lat, ponto.lon, it.optDouble("lat"), it.optDouble("lon")) }
                ?: return null
            Log.i("TvLauncher.Local", "estacao a " +   // sem o código ICAO: ele diz a cidade
                "${Localizacao.km(ponto.lat, ponto.lon, boletim.optDouble("lat"), boletim.optDouble("lon")).toInt()} km")
            val celsius = boletim.getDouble("temp")
            // Umidade relativa pela formula de Magnus: compara a pressao de vapor no ponto de orvalho com a da temperatura.
            val umidade = if (boletim.has("dewp")) {
                val orvalho = boletim.getDouble("dewp")
                val vapor = { t: Double -> Math.exp(17.625 * t / (243.04 + t)) }
                Math.round(100 * vapor(orvalho) / vapor(celsius)).toInt().coerceIn(0, 100)
            } else null
            val temp = Math.round(if (Config.USAR_FAHRENHEIT) celsius * 9 / 5 + 32 else celsius).toInt()
            // Tempo presente do METAR (ex.: "-RA" chuva fraca, "SHRA" pancada, "TSRA" trovoada, "DZ" garoa, "BR"/"FG" nevoa).
            val fenomeno = Metar.fenomeno(boletim.optString("wxString", ""))   // sem os grupos "VC" (vizinhança): ver Metar.kt
            // Nebulosidade: a camada mais cheia. CAVOK/sem camadas = nada abaixo de 1500 m; nuvens altas podem existir,
            // por isso vira "poucas nuvens" e nao "ceu limpo".
            val camadas = boletim.optJSONArray("clouds")
            var nuvens = 1
            if (camadas != null) for (i in 0 until camadas.length()) {
                nuvens = maxOf(nuvens, when (camadas.optJSONObject(i)?.optString("cover")) {
                    "SCT" -> 2
                    "BKN", "OVC" -> 3
                    else -> 1
                })
            }
            Medida(temp, umidade, fenomeno, nuvens)
        } catch (e: Exception) {
            Log.w("TvLauncher.Local", "METAR falhou: ${e.javaClass.simpleName}")
            null   // formato mudou, sem internet etc.: fica o modelo
        }
    }

    private fun httpGet(endereco: String): String? {
        val conexao = URL(endereco).openConnection() as HttpURLConnection
        return try {
            conexao.connectTimeout = 8000
            conexao.readTimeout = 8000
            conexao.setRequestProperty("User-Agent", "onetv-launcher")
            if (conexao.responseCode == 200) conexao.inputStream.bufferedReader().use { it.readText() } else null
        } finally {
            conexao.disconnect()
        }
    }
}
