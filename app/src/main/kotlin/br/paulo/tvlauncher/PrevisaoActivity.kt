package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.content.Intent
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.view.KeyEvent
import android.widget.TextView
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A previsão do tempo em tela cheia (abre com OK no bloco do clima, no topo da tela inicial). Voltar fecha.
 *
 * Três telas em carrossel (21/11/2026, pedido do Paulo): 1) agora, a frase do dia e três dicas (guarda-chuva, varal, hora
 * de sair; [DicasClima]); 2) hora a hora e os próximos dias; 3) detalhes, ar, sol e lua. Baixo passa para a próxima e da
 * última volta à primeira. Antes era uma coluna de cartões que rolava, como app de celular, ruim de ler do sofá.
 * Na faixa de horas, esquerda/direita andam hora a hora. Fonte: Open-Meteo (previsão) e a API de qualidade do ar
 * da própria Open-Meteo, no ponto da Localizacao. Tudo é desenhado em código (Views), sem XML.
 */
class PrevisaoActivity : Activity() {
    companion object {
        const val EXTRA_TEMP = "temp"            // a temperatura mostrada no topo (Int.MIN_VALUE = sem medida)
        const val EXTRA_CHOVENDO = "chovendo"    // o topo diz que chove agora
        const val EXTRA_CODIGO = "codigo"        // o código WMO que o topo mostra (-1 = sem medida)
        const val EXTRA_UMIDADE = "umidade"      // a umidade medida que o topo usa (-1 = sem medida)
        const val EXTRA_FALAR = "falar"          // ler a previsão em voz alta ao abrir
        const val EXTRA_BOM_DIA = "bom_dia"      // falar "Bom dia", a hora e a agenda de hoje junto (despertador)

        /** Teste de telas (ClimaTelaTest): uma previsão fixa no formato da Open-Meteo, para desenhar as 3 telas sem rede. */
        @Volatile internal var dadosDeTeste: JSONObject? = null
    }

    // Voz: o TextToSpeech do Android (motor do Google, já instalado na TV). Liga só quando a tela foi aberta para falar
    // (pelo atalho "Boletim do Tempo", que o Google Assistant abre por voz, ou com EXTRA_FALAR).
    private var voz: TextToSpeech? = null
    private var vozPronta = false
    private var falaPendente: String? = null
    private var bomDia = false
    @Volatile private var agendaHoje: String? = null   // lida em segundo plano; quase sempre pronta antes da previsão

    // Cores e fontes do sistema de design (Estilo); o azul da chuva e a escala do ar são cores de dado, ficam.
    private val textoSuave = Estilo.TEXTO_2
    private val azulChuva = 0xFF8EC5FF.toInt()
    private val fonteMedia = Fontes.nunito(600)
    private val fonteLeve = Fontes.nunito(200)

    private lateinit var raiz: FrameLayout
    private lateinit var palco: FrameLayout          // onde as telas do carrossel ficam, uma por cima da outra
    private lateinit var seta: TextView              // "▼ Próximas horas e dias": o que vem embaixo
    private lateinit var pontos: LinearLayout        // os pontinhos de página, na direita
    private val paginas = mutableListOf<LinearLayout>()
    private var entraDescendo: List<View> = emptyList()
    private var entraSubindo: List<View> = emptyList()
    private var atual = 0
    private var animando = false
    /** A tela do carrossel que está à mostra (0 = agora e dicas), para o teste de telas. */
    internal val paginaAtual: Int get() = atual
    private lateinit var fundoVivo: View   // o mesmo fundo do launcher (FundoEscuro/FundoGaleria, ver Fundos.kt), para as duas telas ficarem coerentes


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        // Três telas em carrossel vertical (21/11/2026, pedido do Paulo): baixo avança, da última volta à primeira; cima
        // faz o caminho de volta. Cada tela cabe inteira na TV (nada rola), e o relógio, a seta de baixo e os pontinhos
        // de página ficam fixos por cima delas.
        palco = FrameLayout(this).apply { clipChildren = false }
        // Mesmo fundo do launcher (criarFundo(), ver Fundos.kt): acompanha o tema configurado (Escuro/Galeria) em
        // vez de uma cor fixa só desta tela — pedido do dono para as duas ficarem coerentes.
        fundoVivo = criarFundo(this, Tema.atual(this), decoracao = false) { bloco -> Thread(bloco).start() }
        seta = Estilo.texto(this, Estilo.Papel.CORPO).apply { setTextColor(textoSuave); gravity = Gravity.CENTER; visibility = View.GONE }
        pontos = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        raiz = FrameLayout(this).apply {
            addView(fundoVivo, FrameLayout.LayoutParams(-1, -1))
            addView(palco, FrameLayout.LayoutParams(-1, -1))
            // Hora e data no canto, iguais às da HOME e das outras telas (Moldura). 48 dp de altura = a mesma linha da HOME.
            addView(Moldura.relogio(this@PrevisaoActivity), FrameLayout.LayoutParams(-2, dp(48), Gravity.TOP or Gravity.END).apply {
                topMargin = dp(28); marginEnd = dp(Grade.MARGEM) })
            addView(seta, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { bottomMargin = dp(14) })
            addView(pontos, FrameLayout.LayoutParams(dp(24), -2, Gravity.END or Gravity.CENTER_VERTICAL).apply { marginEnd = dp(12) })
        }
        setContentView(raiz)
        palco.addView(texto(18f, textoSuave).apply { text = getString(R.string.pv_carregando); setPaddingRelative(dp(Grade.MARGEM), dp(40), 0, 0) })

        // Aberta pelo atalho "Boletim do Tempo" (action MAIN) ou com EXTRA_FALAR: prepara a voz enquanto busca os dados.
        // "Bom dia": o despertador (Parar) ou o atalho de voz "Bom dia" (alias .BomDia, ver o manifesto).
        bomDia = intent.getBooleanExtra(EXTRA_BOM_DIA, false) || intent.component?.className?.endsWith(".BomDia") == true
        if (bomDia) Fundo.rodar { agendaHoje = try { Agenda.resumoHoje(this) } catch (e: Exception) { null } }
        if (intent.action == Intent.ACTION_MAIN || intent.getBooleanExtra(EXTRA_FALAR, false) || bomDia) {
            voz = TextToSpeech(this) { status ->
                if (status != TextToSpeech.SUCCESS) return@TextToSpeech
                val r = voz?.setLanguage(Locale.getDefault()) ?: TextToSpeech.LANG_NOT_SUPPORTED
                vozPronta = r != TextToSpeech.LANG_MISSING_DATA && r != TextToSpeech.LANG_NOT_SUPPORTED
                android.util.Log.i("TvLauncher.Voz", "TTS pronto, idioma pt-BR: $r")
                falaPendente?.let { falar(it) }
            }
        }
        // A medida do topo vem no Intent (OK no clima) ou, aberta por voz, do que a tela inicial guardou (se tiver até 90 min).
        val guardado = getSharedPreferences("clima", MODE_PRIVATE)
        val recente = System.currentTimeMillis() - guardado.getLong("em", 0) < 90 * 60_000L
        val tempTopo = intent.getIntExtra(EXTRA_TEMP, Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE }
            ?: if (recente && guardado.contains("temp")) guardado.getInt("temp", 0) else null
        val chovendo = if (intent.hasExtra(EXTRA_CHOVENDO)) intent.getBooleanExtra(EXTRA_CHOVENDO, false)
            else recente && guardado.getBoolean("chovendo", false)
        // O céu e a umidade de agora também vêm do topo (aeroporto): antes a HOME dizia "Poucas nuvens" e esta tela,
        // com o modelo, "Garoa forte" e outra umidade para o mesmo instante (teste na TV, 15/11/2026).
        val codigoTopo = intent.getIntExtra(EXTRA_CODIGO, -1).takeIf { it >= 0 }
            ?: if (recente) guardado.getInt("codigo", -1).takeIf { it >= 0 } else null
        val umidadeTopo = intent.getIntExtra(EXTRA_UMIDADE, -1).takeIf { it >= 0 }
            ?: if (recente) guardado.getInt("umidade", -1).takeIf { it >= 0 } else null
        Fundo.rodar {
            val dados = try { carregar() } catch (e: Exception) { null }
            dados?.let { d -> codigoTopo?.let { d.codigo = it }; umidadeTopo?.let { d.umidade = it } }
            runOnUiThread { if (!isFinishing) montar(dados, tempTopo, chovendo) }
        }
    }

    private fun falar(frase: String) {
        if (!vozPronta) { falaPendente = frase; return }
        falaPendente = null
        voz?.speak(frase, TextToSpeech.QUEUE_FLUSH, null, "previsao")
    }

    override fun onDestroy() {
        voz?.stop(); voz?.shutdown(); voz = null
        super.onDestroy()
    }

    /** "Bom dia! São 7 horas e 5 minutos, quinta-feira, 5 de novembro." (com "horas" e "minutos" por extenso: o
     *  leitor de voz lê "7:05" de jeitos diferentes conforme o motor). */
    private fun saudacao(): String {
        val c = java.util.Calendar.getInstance()
        val h = c.get(java.util.Calendar.HOUR_OF_DAY); val m = c.get(java.util.Calendar.MINUTE)
        val ola = getString(when { h < 12 -> R.string.pv_bom_dia; h < 18 -> R.string.pv_boa_tarde; else -> R.string.pv_boa_noite })
        val horas = resources.getQuantityString(R.plurals.pv_sao_horas, h, h)
        val agora = if (m == 0) horas else horas + getString(R.string.dur_e) + resources.getQuantityString(R.plurals.dur_minutos, m, m)
        // Data no formato do idioma da TV ("quinta-feira, 13 de novembro" / "Thursday, November 13").
        val dia = java.text.SimpleDateFormat(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "EEEEdMMMM"),
            Locale.getDefault()).format(c.time)
        return getString(R.string.pv_saudacao, ola, agora, dia)
    }

    /** "Agora faz 20 graus, chuva. Hoje, máxima de 27 e mínima de 20, com 86 por cento de chance de chuva. Amanhã, ..." */
    private fun boletim(d: Dados, tempAgora: Int, maxHoje: Int, minHoje: Int, chovendo: Boolean): String {
        val b = StringBuilder(getString(R.string.pv_voz_agora, tempAgora, IconesClima.texto(d.codigo, chovendo).lowercase()))
        b.append(getString(R.string.pv_voz_hoje, maxHoje, minHoje))
        b.append(if (d.dChuva[0] >= 20) getString(R.string.pv_voz_chuva, d.dChuva[0]) else getString(R.string.pv_voz_sem_chuva))
        if (d.dData.size > 1) {
            b.append(getString(R.string.pv_voz_amanha, IconesClima.texto(d.dCodigo[1]).lowercase(), Math.round(d.dMax[1]).toInt(), Math.round(d.dMin[1]).toInt()))
            if (d.dChuva[1] >= 20) b.append(getString(R.string.pv_voz_amanha_chuva, d.dChuva[1]))
            b.append(".")
        }
        return b.toString()
    }

    // ---------------------------------------------------------------- dados

    private class Dados(
        val ponto: Localizacao.Ponto,
        val agoraTexto: String,            // "2026-11-02T16:15" (hora local do lugar)
        val temp: Double, val sensacao: Double, var umidade: Int, var codigo: Int, val pressao: Double,   // var: o topo da HOME manda (montar)
        val vento: Double, val ventoDir: Int, val eDia: Boolean,
        val hTempo: List<String>, val hTemp: List<Double>, val hChuva: List<Int>, val hCodigo: List<Int>,
        val hVento: List<Double>, val hVisib: List<Double>, val hOrvalho: List<Double>, val hUv: List<Double>,
        val dData: List<String>, val dCodigo: List<Int>, val dMax: List<Double>, val dMin: List<Double>, val dChuva: List<Int>,
        val dNascer: List<String>, val dPoente: List<String>, val dChuvaMm: Double,
        val aqi: Int?,
        /** "weatherapi.com", "met.no" ou "" (Open-Meteo): a WeatherAPI pede o crédito na tela. */
        val fonte: String = "",
    )

    private fun carregar(): Dados? {
        dadosDeTeste?.let { return dados(it, Localizacao.Ponto(-23.55, -46.63, "teste", "Bairro de exemplo"), 42) }
        val ponto = Localizacao.atual(this) { httpGet(it) } ?: return null
        val unidade = if (Config.USAR_FAHRENHEIT) "&temperature_unit=fahrenheit" else ""
        val openMeteo = DataSources.weather(this) == DataSources.Weather.OPEN_METEO
        val j = if (!openMeteo) DataSources.forecast(this, ponto.lat, ponto.lon, desdeAgora = false) ?: return null else JSONObject(httpGet(
            "https://api.open-meteo.com/v1/forecast?latitude=${ponto.lat}&longitude=${ponto.lon}" +
                "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,pressure_msl,wind_speed_10m,wind_direction_10m,is_day" +
                "&hourly=temperature_2m,precipitation_probability,weather_code,wind_speed_10m,visibility,dew_point_2m,uv_index" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max,sunrise,sunset,precipitation_sum" +
                "&timezone=auto&forecast_days=${FaixaDias.DIAS}$unidade"
        ) ?: return null)
        // Qualidade do ar: Open-Meteo (Ultra) ou WeatherAPI (Pro, já no JSON convertido); no MET (Básico) o cartão some.
        val aqi = if (!openMeteo) j.optJSONObject("air_quality")?.optInt("us_aqi", -1)?.takeIf { it >= 0 } else try {
            JSONObject(httpGet("https://air-quality-api.open-meteo.com/v1/air-quality?latitude=${ponto.lat}&longitude=${ponto.lon}&current=us_aqi")
                ?: "{}").optJSONObject("current")?.optInt("us_aqi", -1)?.takeIf { it >= 0 }
        } catch (e: Exception) { null }
        return dados(j, ponto, aqi)
    }

    /** O JSON no formato da Open-Meteo (o MET e a WeatherAPI já chegam convertidos nele) vira [Dados]. */
    private fun dados(j: JSONObject, ponto: Localizacao.Ponto, aqi: Int?): Dados {
        val c = j.getJSONObject("current"); val h = j.getJSONObject("hourly"); val d = j.getJSONObject("daily")
        fun <T> lista(o: JSONObject, k: String, f: (org.json.JSONArray, Int) -> T) = o.getJSONArray(k).let { a -> (0 until a.length()).map { f(a, it) } }
        return Dados(
            ponto, c.getString("time"),
            c.getDouble("temperature_2m"), c.getDouble("apparent_temperature"), c.getInt("relative_humidity_2m"), c.getInt("weather_code"),
            c.getDouble("pressure_msl"), c.getDouble("wind_speed_10m"), c.getInt("wind_direction_10m"),
            // Mesma regra da HOME (Astronomia.eDia, com o crepúsculo): antes era o is_day cru da API e as telas discordavam.
            Astronomia.eDia(System.currentTimeMillis(), ponto.lat, ponto.lon, c.getInt("is_day") == 1),
            lista(h, "time") { a, i -> a.getString(i) }, lista(h, "temperature_2m") { a, i -> a.optDouble(i) },
            lista(h, "precipitation_probability") { a, i -> a.optInt(i, 0) }, lista(h, "weather_code") { a, i -> a.optInt(i, 3) },
            lista(h, "wind_speed_10m") { a, i -> a.optDouble(i) }, lista(h, "visibility") { a, i -> a.optDouble(i) },
            lista(h, "dew_point_2m") { a, i -> a.optDouble(i) }, lista(h, "uv_index") { a, i -> a.optDouble(i, 0.0) },
            lista(d, "time") { a, i -> a.getString(i) }, lista(d, "weather_code") { a, i -> a.optInt(i, 3) },
            lista(d, "temperature_2m_max") { a, i -> a.optDouble(i) }, lista(d, "temperature_2m_min") { a, i -> a.optDouble(i) },
            lista(d, "precipitation_probability_max") { a, i -> a.optInt(i, 0) },
            lista(d, "sunrise") { a, i -> a.getString(i) }, lista(d, "sunset") { a, i -> a.getString(i) },
            d.getJSONArray("precipitation_sum").optDouble(0, 0.0),
            aqi,
            j.optString("fonte"),
        )
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

    // ---------------------------------------------------------------- tela

    private fun montar(d: Dados?, tempTopo: Int?, chovendo: Boolean) {
        palco.removeAllViews(); paginas.clear()
        if (d == null) {
            // Erro com saída (crítica de design 19/11/2026) e com o mascote no meio da tela (estudo global 20/11, ideia 6).
            val tentar = Estilo.botao(this, getString(R.string.tentar_de_novo)).apply { setOnClickListener { recreate() } }
            palco.addView(Estilo.estadoVazio(this, null, getString(R.string.pv_sem_titulo), getString(R.string.pv_sem), tentar),
                FrameLayout.LayoutParams(-1, -2, Gravity.CENTER_VERTICAL))
            tentar.requestFocus()
            // Sem rede o "Bom dia" ficava mudo: a saudação e a agenda não dependem da previsão, então fala o que tem.
            if (voz != null) falar(FalaBoletim.frase(bomDia, saudacao(), null, agendaHoje, getString(R.string.pv_sem)))
            return
        }
        val agoraMin = minutos(d.agoraTexto)
        val tempAgora = tempTopo ?: Math.round(d.temp).toInt()
        val i0 = max(0, d.hTempo.indexOfFirst { it >= d.agoraTexto.substring(0, 13) + ":00" })
        fun eDiaNaHora(i: Int): Boolean { val di = min(i / 24, d.dNascer.size - 1); return d.hTempo[i] >= d.dNascer[di] && d.hTempo[i] < d.dPoente[di] }

        (fundoVivo as? FundoVivo)?.atualizar(EstadoCeu(System.currentTimeMillis(), d.ponto.lat, d.ponto.lon, d.codigo, chovendo))

        val maxHoje = max(Math.round(d.dMax[0]).toInt(), tempAgora)
        val minHoje = min(Math.round(d.dMin[0]).toInt(), tempAgora)
        // O modelo erra a temperatura de agora (hoje: modelo 25°, aeroporto 20°). Corrige a sensação pela mesma diferença,
        // para "20°, sensação 28° mais quente" não aparecer.
        val sensacao = Math.round(d.sensacao + (tempAgora - d.temp)).toInt()
        val iconeAgora = IconesClima.chave(d.codigo, d.eDia, chovendo)
        val fim = min(d.hTempo.size, i0 + 24)

        // ================================================================ tela 1: agora, a frase do dia e as dicas
        val hojeData = d.agoraTexto.substring(0, 10)
        val entrada = DicasClima.Entrada(chovendo,
            (i0 until fim).map { i -> DicasClima.Hora(d.hTempo[i].substring(11, 13).toInt(), if (i == i0) tempAgora.toDouble() else d.hTemp[i],
                d.hChuva[i], d.hUv.getOrElse(i) { 0.0 }, if (i == i0) d.eDia else eDiaNaHora(i), d.hTempo[i].startsWith(hojeData)) },
            maxHoje, d.dChuva.getOrNull(1))
        val frase = DicasClima.frase(entrada)
        val p1 = pagina(28)
        // O lugar escolhido no painel (LocalClima.kt), para não haver dúvida de onde é a previsão. Na linha do relógio.
        p1.addView(Estilo.texto(this, Estilo.Papel.CORPO, d.ponto.nome ?: "").apply {
            setTextColor(textoSuave); gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPaddingRelative(0, 0, dp(300), 0)
        }, LinearLayout.LayoutParams(-1, dp(48)))
        val hero = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        hero.addView(IconesClima.imagem(this, iconeAgora), LinearLayout.LayoutParams(dp(132), dp(132)))
        hero.addView(texto(104f, Estilo.TEXTO, fonteLeve).apply { text = "$tempAgora°"; includeFontPadding = false; setPaddingRelative(dp(14), 0, dp(18), 0) })
        hero.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(30f, Estilo.TEXTO, fonteMedia).apply { text = IconesClima.texto(d.codigo, chovendo); maxLines = 1 })
            addView(texto(20f, textoSuave).apply { text = getString(R.string.pv_max_min, maxHoje, minHoje, sensacao); setPaddingRelative(0, dp(4), 0, 0) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
        // A Sofy reage ao tempo (como o sapo do Google Weather): preocupada com chuva, feliz com dia bom, dormindo de noite.
        val sofy = SofyDrawable(this, when {
            chovendo || frase.tipo == DicasClima.TipoFrase.CHUVA_AS -> SofyPolicy.Expression.WORRIED
            frase.tipo == DicasClima.TipoFrase.CALOR || frase.tipo == DicasClima.TipoFrase.FRIO -> SofyPolicy.Expression.SURPRISED
            !d.eDia -> SofyPolicy.Expression.SLEEPING
            else -> SofyPolicy.Expression.HAPPY
        })
        hero.addView(android.widget.ImageView(this).apply { setImageDrawable(sofy); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(dp(150), dp(140)))
        p1.addView(hero, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        p1.addView(texto(30f, Estilo.TEXTO, fonteMedia).apply { text = textoFrase(frase); maxLines = 2; setLineSpacing(0f, 1.1f) },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(18); marginEnd = dp(40) })
        val linhaDicas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val dicas = listOf(
            dicaTile("cloud_with_rain", getString(R.string.cl_d_guarda), textoDica(DicasClima.guardaChuva(entrada))),
            dicaTile("wind_face", getString(R.string.cl_d_varal), textoDica(DicasClima.varal(entrada))),
            dicaTile("sun", getString(R.string.cl_d_sair), textoDica(DicasClima.sair(entrada))),
        )
        dicas.forEachIndexed { i, t ->
            linhaDicas.addView(t, LinearLayout.LayoutParams(0, -2, 1f).apply {
                if (i > 0) marginStart = dp(Grade.VAO / 2); if (i < dicas.size - 1) marginEnd = dp(Grade.VAO / 2) })
        }
        p1.addView(linhaDicas, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(22); marginEnd = dp(28) })

        // ================================================================ tela 2: hora a hora e os próximos dias
        val p2 = pagina(84)
        val horas = cartao(getString(R.string.pv_24h), focavel = false)
        val faixa = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (i in i0 until fim) {
            val hora = if (i == i0) getString(R.string.pv_agora) else getString(R.string.cl_hora, d.hTempo[i].substring(11, 13).toInt())
            val tempH = if (i == i0) tempAgora else Math.round(d.hTemp[i]).toInt()
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPaddingRelative(dp(4), dp(6), dp(4), dp(6))
                isFocusable = true
                background = itemFoco(false)
                contentDescription = "$hora, $tempH°, ${d.hChuva[i]}%"
                setOnFocusChangeListener { v, f -> v.background = itemFoco(f) }
            }
            item.addView(texto(17f, textoSuave).apply { text = hora; gravity = Gravity.CENTER; maxLines = 1 })
            item.addView(IconesClima.imagem(this, if (i == i0) iconeAgora else IconesClima.chave(d.hCodigo[i], eDiaNaHora(i), false)),
                LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(4); bottomMargin = dp(4) })
            item.addView(texto(22f, Estilo.TEXTO, fonteMedia).apply { text = "$tempH°"; gravity = Gravity.CENTER })
            item.addView(texto(17f, azulChuva).apply { text = "${d.hChuva[i]}%"; gravity = Gravity.CENTER })
            faixa.addView(item, LinearLayout.LayoutParams(dp(78), ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        horas.addView(android.widget.HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(faixa) })
        p2.addView(horas, LinearLayout.LayoutParams(-1, -2))

        val dias = cartao(getString(R.string.pv_7d))
        val linhaDias = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val entradaData = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val nomeDia = SimpleDateFormat("EEE", Locale.getDefault())
        val descricao = StringBuilder()
        for (i in d.dData.indices) {
            val nome = if (i == 0) getString(R.string.pv_hoje)
                else nomeDia.format(entradaData.parse(d.dData[i])!!).trimEnd('.').replaceFirstChar { it.uppercase() }
            val mx = if (i == 0) maxHoje else Math.round(d.dMax[i]).toInt()
            val mn = if (i == 0) minHoje else Math.round(d.dMin[i]).toInt()
            descricao.append("$nome $mx° $mn° ${d.dChuva[i]}%. ")
            linhaDias.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(texto(17f, if (i == 0) Estilo.TEXTO else textoSuave, fonteMedia).apply { text = nome; maxLines = 1; gravity = Gravity.CENTER })
                addView(IconesClima.imagem(this@PrevisaoActivity, IconesClima.chave(d.dCodigo[i], true, false)),
                    LinearLayout.LayoutParams(dp(44), dp(44)).apply { topMargin = dp(4); bottomMargin = dp(2) })
                addView(texto(22f, Estilo.TEXTO, fonteMedia).apply { text = "$mx°"; gravity = Gravity.CENTER })
                addView(texto(18f, textoSuave).apply { text = "$mn°"; gravity = Gravity.CENTER })
                addView(texto(17f, azulChuva).apply { text = "${d.dChuva[i]}%"; gravity = Gravity.CENTER })
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        dias.contentDescription = getString(R.string.pv_7d) + ". " + descricao
        dias.addView(linhaDias, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        p2.addView(dias, lpCartao())

        // ================================================================ tela 3: detalhes, ar, sol e lua
        val p3 = pagina(84)
        val uv = d.hUv.getOrElse(i0) { 0.0 }
        val sensTexto = when { sensacao > tempAgora + 1 -> getString(R.string.pv_mais_quente); sensacao < tempAgora - 1 -> getString(R.string.pv_mais_frio); else -> getString(R.string.pv_igual) }
        val vis = d.hVisib.getOrElse(i0) { 10000.0 } / 1000
        val rosa = resources.getStringArray(R.array.rosa_ventos)[((d.ventoDir + 22.5) / 45).toInt() % 8]
        val detalhes = cartao(getString(R.string.pv_detalhes))
        val linhas = listOf(
            listOf(
                Triple(getString(R.string.pv_sensacao), "$sensacao°", "thermometer" to sensTexto),
                Triple(getString(R.string.pt_umidade), "${d.umidade}%", "droplet" to ""),
                Triple(getString(R.string.pv_vento), "${Math.round(d.vento).toInt()} km/h", "wind_face" to rosa),
                Triple(getString(R.string.pv_uv), "${Math.round(uv).toInt()}", "sun" to rotuloUv(uv)),
            ),
            listOf(
                Triple(getString(R.string.pv_visib), if (vis >= 10) "10+ km" else "${"%.1f".format(Locale.getDefault(), vis)} km", "eye" to ""),
                Triple(getString(R.string.pv_orvalho), "${Math.round(d.hOrvalho.getOrElse(i0) { d.temp }).toInt()}°", "droplet" to ""),
                Triple(getString(R.string.pv_pressao), "${Math.round(d.pressao).toInt()} hPa", "cloud" to ""),
                Triple(getString(R.string.pv_chuva_hoje), "${"%.1f".format(Locale.getDefault(), d.dChuvaMm)} mm", "cloud_with_rain" to ""),
            ),
        )
        val descDetalhes = StringBuilder(getString(R.string.pv_detalhes)).append(". ")
        linhas.forEach { l ->
            detalhes.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                setPaddingRelative(0, dp(8), 0, dp(2))
                l.forEach { (rot, valor, extra) ->
                    descDetalhes.append("$rot $valor ${extra.second}. ")
                    addView(tile(rot, valor, extra.first, extra.second), LinearLayout.LayoutParams(0, -2, 1f))
                }
            })
        }
        detalhes.contentDescription = descDetalhes
        p3.addView(detalhes, LinearLayout.LayoutParams(-1, -2))

        val trio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        val ar = cartao(getString(R.string.pv_ar))
        if (d.aqi != null) {
            val (rot, cor) = rotuloAqi(d.aqi)
            ar.contentDescription = "${getString(R.string.pv_ar)}: ${d.aqi}, $rot"
            ar.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(0, dp(6), 0, dp(6))
                addView(texto(44f, cor, fonteLeve).apply { text = "${d.aqi}"; includeFontPadding = false })
                addView(texto(20f, Estilo.TEXTO, fonteMedia).apply { text = rot; setPaddingRelative(dp(12), 0, 0, 0) })
            })
            ar.addView(BarraAqi(this, d.aqi), LinearLayout.LayoutParams(-1, dp(8)))
        } else {
            ar.contentDescription = "${getString(R.string.pv_ar)}: ${getString(R.string.pv_sem_dados)}"
            ar.addView(texto(18f, textoSuave).apply { text = getString(R.string.pv_sem_dados); setPaddingRelative(0, dp(10), 0, 0) })
        }

        val sol = cartao(getString(R.string.pt_sol))
        val nascerMin = minutos(d.dNascer[0]); val poenteMin = minutos(d.dPoente[0])
        sol.contentDescription = "${getString(R.string.pt_sol)}: ↑ ${d.dNascer[0].substring(11)}, ↓ ${d.dPoente[0].substring(11)}"
        sol.addView(ArcoSol(this, ((agoraMin - nascerMin).toFloat() / (poenteMin - nascerMin)).coerceIn(-0.05f, 1.05f)),
            LinearLayout.LayoutParams(-1, dp(70)).apply { topMargin = dp(4) })
        sol.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(texto(18f, textoSuave).apply { text = "↑ ${d.dNascer[0].substring(11)}" }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(texto(18f, textoSuave).apply { text = "↓ ${d.dPoente[0].substring(11)}"; gravity = Gravity.END }, LinearLayout.LayoutParams(0, -2, 1f))
        })

        val lua = cartao(getString(R.string.pt_lua))
        val agoraMs = System.currentTimeMillis()
        val partes = Lua.resumo(agoraMs, d.ponto.lat).split("  ·  ")
        lua.contentDescription = "${getString(R.string.pt_lua)}: ${partes.joinToString(", ") { it.trim() }}"
        lua.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(0, dp(6), 0, 0)
            addView(IconesClima.imagem(this@PrevisaoActivity, Lua.chaveIcone(agoraMs, d.ponto.lat)), LinearLayout.LayoutParams(dp(60), dp(60)))
            addView(LinearLayout(this@PrevisaoActivity).apply {
                orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(10), 0, 0, 0)
                addView(texto(19f, Estilo.TEXTO, fonteMedia).apply { text = partes[0].substringAfter("  ").trim() })
                addView(texto(17f, textoSuave).apply { text = "${partes.getOrElse(1) { "" }} · ${partes.getOrElse(2) { "" }}" })
            })
        })
        trio.addView(ar, LinearLayout.LayoutParams(0, -1, 1f).apply { marginEnd = dp(Grade.VAO / 2) })
        trio.addView(sol, LinearLayout.LayoutParams(0, -1, 1.15f).apply { marginStart = dp(Grade.VAO / 2); marginEnd = dp(Grade.VAO / 2) })
        if (Extras.ligado(this, Extras.Chave.CARTAO_LUA))   // chave CARTAO_LUA (HomeCards, 18/11/2026)
            trio.addView(lua, LinearLayout.LayoutParams(0, -1, 1.15f).apply { marginStart = dp(Grade.VAO / 2) })
        p3.addView(trio, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) })
        // Crédito exigido pelo plano Free da WeatherAPI (Pro, 19/11/2026).
        if (d.fonte == WeatherApiSource.FONTE)
            p3.addView(texto(Estilo.Papel.LEGENDA.sp, textoSuave).apply { text = getString(R.string.pv_powered_weatherapi) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })

        if (voz != null) {
            val previsao = boletim(d, tempAgora, maxHoje, minHoje, chovendo)
            falar(FalaBoletim.frase(bomDia, saudacao(), previsao, agendaHoje, getString(R.string.pv_sem)))
        }

        // Por onde o foco entra em cada tela: descendo, pelo alto; subindo, pelo que fica embaixo.
        paginas += listOf(p1, p2, p3)
        entraDescendo = listOf(dicas[0], faixa.getChildAt(0) ?: dias, detalhes)
        entraSubindo = listOf(dicas[0], dias, ar)
        paginas.forEach { palco.addView(it, FrameLayout.LayoutParams(-1, -1)); it.visibility = View.GONE }
        mostrar(0, descendo = true, animar = false)
        sofy.start()
    }

    /** A frase do dia (ver [DicasClima.frase]) no idioma da TV. */
    private fun textoFrase(f: DicasClima.Frase): String = when (f.tipo) {
        DicasClima.TipoFrase.PARA_AS -> getString(R.string.cl_f_parar, hora(f.a))
        DicasClima.TipoFrase.CHUVA_SEGUE -> getString(R.string.cl_f_segue)
        DicasClima.TipoFrase.CHUVA_AS -> getString(R.string.cl_f_chuva, hora(f.a), f.b)
        DicasClima.TipoFrase.CALOR -> getString(R.string.cl_f_calor, f.a, hora(f.b))
        DicasClima.TipoFrase.FRIO -> getString(R.string.cl_f_frio, f.a)
        DicasClima.TipoFrase.SOL_FORTE -> getString(R.string.cl_f_sol, hora(f.a), hora(f.b))
        DicasClima.TipoFrase.AMANHA_CHUVA -> getString(R.string.cl_f_amanha, f.a)
        DicasClima.TipoFrase.CALMO -> getString(R.string.cl_f_calmo, f.a)
    }

    private fun textoDica(d: DicasClima.Dica): String = when (d.resposta) {
        DicasClima.Resposta.AGORA -> getString(R.string.cl_v_agora)
        DicasClima.Resposta.SIM_AS -> getString(R.string.cl_v_sim_as, hora(d.hora))
        DicasClima.Resposta.TALVEZ -> getString(R.string.cl_v_talvez)
        DicasClima.Resposta.NAO -> getString(R.string.cl_v_nao)
        DicasClima.Resposta.ATE -> getString(R.string.cl_v_ate, hora(d.hora))
        DicasClima.Resposta.SECA_HOJE -> getString(R.string.cl_v_seca)
        DicasClima.Resposta.MELHOR_NAO -> getString(R.string.cl_v_melhor_nao)
        DicasClima.Resposta.AMANHA_SIM -> getString(R.string.cl_v_amanha_sim)
        DicasClima.Resposta.AMANHA_NAO -> getString(R.string.cl_v_amanha_nao)
        DicasClima.Resposta.AS -> getString(R.string.cl_v_as, hora(d.hora))
        DicasClima.Resposta.AMANHA_AS -> getString(R.string.cl_v_amanha_as, hora(d.hora))
        DicasClima.Resposta.HOJE_NAO -> getString(R.string.cl_v_hoje_nao)
    }

    private fun hora(h: Int) = getString(R.string.cl_hora, h)

    /** Uma dica da tela 1: ícone e pergunta pequenos, a resposta grande. OK leva às horas (a conta por trás da dica). */
    private fun dicaTile(icone: String, rotulo: String, valor: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(20), dp(14), dp(20), dp(16))
        background = fundoCartao(false)
        isFocusable = true
        contentDescription = "$rotulo: $valor"
        setOnFocusChangeListener { v, f -> v.background = fundoCartao(f) }
        setOnClickListener { irPara(1, descendo = true) }
        addView(LinearLayout(this@PrevisaoActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(IconesClima.imagem(this@PrevisaoActivity, IconesClima.PREFIXO + "3d/" + icone), LinearLayout.LayoutParams(dp(30), dp(30)))
            addView(texto(18f, textoSuave).apply { text = rotulo; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPaddingRelative(dp(8), 0, 0, 0) })
        })
        addView(texto(30f, Estilo.TEXTO, Estilo.fonte(700)).apply { text = valor; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            setPaddingRelative(0, dp(6), 0, 0) })
    }

    private fun tile(rotulo: String, valor: String, icone: String, extra: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(0, 0, dp(10), 0)
        addView(LinearLayout(this@PrevisaoActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(IconesClima.imagem(this@PrevisaoActivity, IconesClima.PREFIXO + "3d/" + icone), LinearLayout.LayoutParams(dp(22), dp(22)))
            addView(texto(Estilo.Papel.LEGENDA.sp, textoSuave).apply { text = rotulo; setPaddingRelative(dp(6), 0, 0, 0); maxLines = 1 })
        })
        addView(texto(26f, Estilo.TEXTO, fonteMedia).apply { text = valor; setPaddingRelative(0, dp(4), 0, 0); maxLines = 1 })
        addView(texto(Estilo.Papel.LEGENDA.sp, textoSuave).apply { text = extra.ifEmpty { " " } })
    }

    private fun rotuloUv(uv: Double) = when {
        uv < 3 -> getString(R.string.uv_baixo); uv < 6 -> getString(R.string.uv_moderado); uv < 8 -> getString(R.string.uv_alto); uv < 11 -> getString(R.string.uv_muito_alto); else -> getString(R.string.uv_extremo)
    }

    private fun rotuloAqi(a: Int): Pair<String, Int> = when {
        a <= 50 -> getString(R.string.aq_boa) to 0xFF7BE0A0.toInt()
        a <= 100 -> getString(R.string.aq_moderada) to 0xFFF2E26B.toInt()
        a <= 150 -> getString(R.string.aq_sensiveis) to 0xFFFFB566.toInt()
        a <= 200 -> getString(R.string.aq_ruim) to 0xFFFF7A6B.toInt()
        a <= 300 -> getString(R.string.aq_muito_ruim) to 0xFFD08CFF.toInt()
        else -> getString(R.string.aq_perigosa) to 0xFFFF5C7A.toInt()
    }

    /** "2026-11-02T16:15" -> minutos desde a meia-noite (só a hora do lugar; sem fuso, de propósito). */
    private fun minutos(t: String) = t.substring(11, 13).toInt() * 60 + t.substring(14, 16).toInt()

    // ---------------------------------------------------------------- cartões e foco

    private fun lpCartao() = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(16) }

    /** Um cartão com título. [focavel]: o cartão inteiro é um ponto de parada (a faixa de horas tem os próprios itens). */
    private fun cartao(titulo: String, focavel: Boolean = true) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPaddingRelative(dp(20), dp(14), dp(20), dp(14))
        background = fundoCartao(false)
        if (focavel) {
            isFocusable = true
            setOnFocusChangeListener { v, f -> v.background = fundoCartao(f) }
        }
        addView(Estilo.texto(context, Estilo.Papel.SECAO, titulo))   // nome de grupo no papel padrão
    }

    // ---------------------------------------------------------------- carrossel de telas

    /** Uma tela do carrossel: ocupa a TV inteira, sem rolar. [topo] = 28 na 1ª (a linha do relógio é dela), 84 nas outras. */
    private fun pagina(topo: Int) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        setPaddingRelative(dp(Grade.MARGEM), dp(topo), dp(Grade.MARGEM), dp(48))
    }

    /** O nome de cada tela: a seta de baixo diz o da próxima (na última, o da primeira) e o leitor de tela fala o da atual. */
    private val nomes = intArrayOf(R.string.cl_pg_agora, R.string.cl_pg_horas, R.string.cl_pg_detalhes)

    /**
     * Baixo/cima andam dentro da tela enquanto houver para onde; na borda, trocam de tela (da 3ª, baixo volta à 1ª; da
     * 1ª, cima vai à 3ª). Durante a troca as setas esperam, para o foco não cair numa tela que está saindo.
     */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val vertical = e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN || e.keyCode == KeyEvent.KEYCODE_DPAD_UP
        if (vertical && paginas.size > 1) {
            if (animando) return true
            if (e.action == KeyEvent.ACTION_DOWN) {
                val descendo = e.keyCode == KeyEvent.KEYCODE_DPAD_DOWN
                val prox = currentFocus?.focusSearch(if (descendo) View.FOCUS_DOWN else View.FOCUS_UP)
                if (prox == null || !dentro(paginas[atual], prox)) {
                    irPara(Math.floorMod(atual + if (descendo) 1 else -1, paginas.size), descendo); return true
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    private fun dentro(pai: View, v: View): Boolean {
        var p: android.view.ViewParent? = v.parent
        while (p != null) { if (p === pai) return true; p = p.parent }
        return false
    }

    private fun irPara(nova: Int, descendo: Boolean) {
        if (nova == atual || animando || nova !in paginas.indices) return
        mostrar(nova, descendo, animar = !semMovimento())
    }

    /** Página deslizando 250 ms com a curva suave (tokens do transitions.dev: "page slide"); sem movimento, troca seca. */
    private fun mostrar(nova: Int, descendo: Boolean, animar: Boolean) {
        val sai = paginas[atual].takeIf { it.visibility == View.VISIBLE && atual != nova }
        val entra = paginas[nova]
        atual = nova
        val dist = dp(56).toFloat() * if (descendo) 1 else -1
        entra.visibility = View.VISIBLE
        // Recém-mostrada, a tela ainda não passou pelo layout e o pedido de foco pode não pegar: repete depois dele.
        (if (descendo) entraDescendo else entraSubindo).getOrNull(nova)?.let { alvo -> if (!alvo.requestFocus()) alvo.post { alvo.requestFocus() } }
        seta.text = "▼  " + getString(nomes[(nova + 1) % nomes.size]); seta.visibility = View.VISIBLE
        desenharPontos()
        entra.announceForAccessibility(getString(nomes[nova]))
        if (!animar || sai == null) { sai?.visibility = View.GONE; return }
        animando = true
        val curva = android.view.animation.PathInterpolator(0.22f, 1f, 0.36f, 1f)
        entra.alpha = 0f; entra.translationY = dist
        sai.animate().alpha(0f).translationY(-dist).setDuration(250).setInterpolator(curva)
            .withEndAction { sai.visibility = View.GONE; sai.alpha = 1f; sai.translationY = 0f }.start()
        entra.animate().alpha(1f).translationY(0f).setDuration(250).setInterpolator(curva).withEndAction { animando = false }.start()
    }

    /** Os pontinhos da direita: onde estou entre as 3 telas (o atual maior e branco). */
    private fun desenharPontos() {
        pontos.removeAllViews()
        paginas.indices.forEach { i ->
            val on = i == atual
            pontos.addView(View(this).apply { background = Estilo.fundo(this@PrevisaoActivity, if (on) Estilo.TEXTO else 0x59FFFFFF, 6) },
                LinearLayout.LayoutParams(dp(if (on) 10 else 8), dp(if (on) 22 else 8)).apply { topMargin = dp(5); bottomMargin = dp(5) })
        }
    }

    /** Mesmo critério do resto do app: sistema sem animação, alto contraste, ou o aparelho no PARADO. */
    private fun semMovimento(): Boolean {
        val sistema = runCatching {
            android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        if (sistema || Estilo.altoContraste) return true
        return runCatching { MovimentoAdaptativo.atual(this, sistema) == MovimentoAdaptativo.Nivel.PARADO }.getOrDefault(false)
    }


    // Os cartões ficam por cima do céu/da pintura do fundo: vidro do sistema de design (Estilo.vidro). Foco = anel branco,
    // o mesmo dos cards da HOME (o cartão tem gráficos desenhados, então não vira pílula branca como uma linha de lista).
    private fun fundoCartao(foco: Boolean) =
        if (foco) Estilo.fundo(this, Estilo.VIDRO_TINTA, Estilo.RAIO, Estilo.FOCO, 2) else Estilo.vidro(this)

    private fun itemFoco(foco: Boolean) =
        if (foco) Estilo.fundo(this, Estilo.SUPERFICIE_ALTA, Estilo.RAIO_PEQUENO + 4, Estilo.FOCO, 2)
        else Estilo.fundo(this, Color.TRANSPARENT, Estilo.RAIO_PEQUENO + 4)

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null) = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    // ---------------------------------------------------------------- desenhos


    /** Escala de 0 a 300+ do índice de qualidade do ar (verde a vinho) com um marcador onde o valor está. */
    private class BarraAqi(ctx: Context, val aqi: Int) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) = Rtl.espelhar(this, c) { desenhar(c) }   // 16.8: o tempo anda para a esquerda no árabe
        private fun desenhar(c: Canvas) {
            val h = height.toFloat()
            p.shader = LinearGradient(0f, 0f, width.toFloat(), 0f,
                intArrayOf(0xFF7BE0A0.toInt(), 0xFFF2E26B.toInt(), 0xFFFFB566.toInt(), 0xFFFF7A6B.toInt(), 0xFFD08CFF.toInt()), null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, 0f, width.toFloat(), h), h / 2, h / 2, p); p.shader = null
            p.color = Color.WHITE
            c.drawCircle(((aqi.coerceIn(0, 300) / 300f) * (width - h) + h / 2), h / 2, h * 0.9f, p)
        }
    }

    /** O arco do sol: tracejado do nascer ao pôr, com o sol (bolinha amarela) na posição de agora ([fracao] 0 = nascer, 1 = pôr). */
    private class ArcoSol(ctx: Context, val fracao: Float) : View(ctx) {
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) = Rtl.espelhar(this, c) { desenhar(c) }   // 16.8: o tempo anda para a esquerda no árabe
        private fun desenhar(c: Canvas) {
            val d = resources.displayMetrics.density
            val raioX = width / 2f - 12 * d; val raioY = height - 10 * d; val cx = width / 2f; val cy = height - 4 * d
            p.style = Paint.Style.STROKE; p.strokeWidth = 2 * d; p.color = 0x55FFFFFF
            p.pathEffect = android.graphics.DashPathEffect(floatArrayOf(5 * d, 5 * d), 0f)
            c.drawArc(RectF(cx - raioX, cy - raioY, cx + raioX, cy + raioY), 180f, 180f, false, p); p.pathEffect = null
            p.color = 0x55FFFFFF; c.drawLine(8 * d, cy, width - 8 * d, cy, p)
            val ang = PI * (1 - fracao.coerceIn(0f, 1f))
            val x = cx + raioX * cos(ang).toFloat(); val y = cy - raioY * sin(ang).toFloat()
            p.style = Paint.Style.FILL
            p.color = 0x55FFD36B; c.drawCircle(x, y, 11 * d, p)
            p.color = 0xFFFFD36B.toInt(); c.drawCircle(x, y, 6.5f * d, p)
        }
    }
}

/** O que a previsão fala em voz alta. Pura, para testar no computador (PrevisaoFalaTest). */
internal object FalaBoletim {
    /**
     * [previsao] null = não veio a previsão (sem rede): no lugar dela vai o aviso [semPrevisao]. No "Bom dia", a
     * saudação e a agenda vão junto mesmo assim.
     */
    fun frase(bomDia: Boolean, saudacao: String, previsao: String?, agenda: String?, semPrevisao: String): String {
        val boletim = previsao ?: semPrevisao
        return if (bomDia) listOfNotNull(saudacao, boletim, agenda).joinToString(" ") else boletim
    }
}
