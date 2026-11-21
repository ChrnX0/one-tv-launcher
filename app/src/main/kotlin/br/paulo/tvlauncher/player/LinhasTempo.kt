package br.paulo.tvlauncher.player

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.home.IconeLinha
import br.paulo.tvlauncher.screensaver.FonteTempo
import br.paulo.tvlauncher.screensaver.Protetor
import br.paulo.tvlauncher.screensaver.SeriesTempo
import br.paulo.tvlauncher.screensaver.TempoAgora
import br.paulo.tvlauncher.screensaver.WidgetsTempo
import br.paulo.tvlauncher.settings.Curiosidades
import br.paulo.tvlauncher.weather.CeuNoturno
import br.paulo.tvlauncher.weather.FraseTempo
import br.paulo.tvlauncher.weather.IconesClima
import br.paulo.tvlauncher.weather.Sgp4
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A fonte de tempo de hoje: o que a HOME já buscou e guardou nas prefs "clima" (Open-Meteo, ou MET Norway no build
 * com -Pclima_met). O protetor não tem rede própria para o clima. Trocar a fonte do produto = trocar o que a HOME grava
 * (ClimaFontes) ou escrever outra [FonteTempo]; nada daqui para baixo muda.
 */
class FonteTempoGuardado(private val ctx: Context) : FonteTempo {
    override fun ler(agora: Long): TempoAgora? {
        val c = ctx.getSharedPreferences(Prefs.CLIMA, Context.MODE_PRIVATE)
        // Mesma regra dos widgets: medida de até 3 h. Mais velha que isso, melhor calar.
        if (!c.contains("temp") || agora - c.getLong("em", 0) >= 3 * 60 * 60_000L) return null
        fun int(k: String) = if (c.contains(k)) c.getInt(k, 0) else null
        return TempoAgora(
            agora = agora,
            lat = c.getFloat("lat", Config.LATITUDE.toFloat()).toDouble(), lon = c.getFloat("lon", Config.LONGITUDE.toFloat()).toDouble(),
            celsius = !Config.USAR_FAHRENHEIT,
            temp = int("temp"), codigo = c.getInt("codigo", 0), chovendo = c.getBoolean("chovendo", false),
            umidade = c.getInt("umidade", -1).takeIf { it >= 0 }, max = int("max"), min = int("min"),
            horas = WidgetsTempo.lerHoras(c.getString("horas", "").orEmpty()),
            amanhaMax = int("amanha_max"), amanhaMin = int("amanha_min"),
            amanhaChuva = c.getInt("amanha_chuva", -1).takeIf { it >= 0 }, amanhaCodigo = int("amanha_codigo"),
            uv = SeriesTempo.ler(c.getString("uv", null)), aqi = c.getInt("aqi", -1).takeIf { it >= 0 },
            chuva15 = SeriesTempo.ler(c.getString("chuva15", null)),
        )
    }
}

/**
 * A Estação Espacial: o TLE (elementos da órbita, dado público do NORAD) vem do CelesTrak no máximo a cada 12 h e fica
 * nas prefs "ceu"; a passagem é calculada na TV ([CeuNoturno.proximaPassagem]). Sem rede ou sem TLE: sem a curiosidade.
 * Fora da thread da tela.
 */
object EstacaoEspacial {
    private const val TAG = "TvLauncher.Ceu"
    private const val URL_TLE = "https://celestrak.org/NORAD/elements/gp.php?CATNR=25544&FORMAT=TLE"

    fun proxima(ctx: Context, agora: Long, lat: Double, lon: Double): CeuNoturno.Passagem? {
        val p = ctx.getSharedPreferences("ceu", Context.MODE_PRIVATE)
        var l1 = p.getString("tle1", null); var l2 = p.getString("tle2", null)
        if (l1 == null || agora - p.getLong("tle_em", 0) > 12 * 3_600_000L) {
            baixar()?.let { (a, b) -> l1 = a; l2 = b; p.edit().putString("tle1", a).putString("tle2", b).putLong("tle_em", agora).apply() }
        }
        val sat = try { Sgp4(l1 ?: return null, l2 ?: return null) } catch (e: Exception) { Log.w(TAG, "TLE: $e"); return null }
        // TLE de mais de 5 dias erra a passagem por minutos: melhor não mostrar.
        if (agora - sat.epocaMs > 5 * 86_400_000L) return null
        return CeuNoturno.proximaPassagem(sat, agora, lat, lon, horas = 13)
    }

    private fun baixar(): Pair<String, String>? = try {
        Http.getText(URL_TLE)?.lines()?.map { it.trimEnd() }?.let { linhas ->
            val a = linhas.firstOrNull { it.startsWith("1 25544") }; val b = linhas.firstOrNull { it.startsWith("2 25544") }
            if (a != null && b != null) a to b else null
        }
    } catch (e: Exception) { Log.w(TAG, "CelesTrak: ${e.javaClass.simpleName}"); null }
}

/**
 * As duas linhas do protetor que reage ao tempo (caminho 2, 17/11/2026), embaixo da data, iguais nos três fundos:
 *  1) a frase do tempo em amarelo, com o ícone do céu ([FraseTempo]);
 *  2) UMA curiosidade por vez, em branco ("UV 9 amanhã · chapéu e protetor das 10h às 15h"), que troca a cada
 *     [TROCA_MS] com um esmaecer, e bolinhas que mostram quantas há ([Curiosidades]).
 * Letras grandes (público idoso): 16 sp a frase, 14 sp a curiosidade. Sem dado: a linha some (nunca texto velho).
 */
class LinhasTempo(private val ctx: Context, private val m400: Typeface, private val m600: Typeface) : LinearLayout(ctx) {
    private val frase: TextView
    private val iconeFrase: ImageView
    private val linhaFrase: LinearLayout
    private val linhaCurio: LinearLayout
    private val iconeCurio: ImageView
    private val tituloCurio: TextView
    private val detalheCurio: TextView
    private val bolinhas: LinearLayout
    private var itens: List<Curiosidades.Item> = emptyList()
    private var atual = 0
    /** Liga/desliga de cada linha nas Configurações (protetor v2, 17/11/2026). Desligada = some mesmo com dado. */
    var comFrase = true
    var comCuriosidades = true
    private var semIcones = false

    /**
     * Variação do desenho (17/11/2026, [Protetor.LINHAS]): "icones" (o de antes), "vidro" (as duas linhas dentro de um
     * vidro escuro arredondado, para fundos claros) ou "texto" (sem os ícones, mais limpo).
     */
    fun estilo(e: String) {
        semIcones = e == Protetor.LINHAS_TEXTO
        if (semIcones) { iconeFrase.visibility = GONE; iconeCurio.visibility = GONE }
        if (e == Protetor.LINHAS_VIDRO) {
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0x66000000); cornerRadius = dp(16).toFloat() }
            setPaddingRelative(dp(16), dp(12), dp(18), dp(12))
        }
        (frase.layoutParams as? LayoutParams)?.marginStart = if (semIcones) 0 else dp(10)
        (tituloCurio.layoutParams as? LayoutParams)?.marginStart = if (semIcones) 0 else dp(12)
    }

    init {
        orientation = VERTICAL
        linhaFrase = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        iconeFrase = ImageView(ctx)
        linhaFrase.addView(iconeFrase, LayoutParams(dp(22), dp(22)))
        // Larguras máximas: a coluna das notificações começa em ~678 dp; as linhas (que começam em ~42 dp) param antes dela.
        frase = texto(16f, m600).apply { setTextColor(AMARELO); maxWidth = dp(600) }
        linhaFrase.addView(frase, LayoutParams(-2, -2).apply { marginStart = dp(10) })
        addView(linhaFrase, LayoutParams(-2, -2))

        linhaCurio = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        iconeCurio = ImageView(ctx).apply { alpha = 0.95f }
        linhaCurio.addView(iconeCurio, LayoutParams(dp(18), dp(18)).apply { marginStart = dp(2) })
        tituloCurio = texto(14f, m600).apply { maxWidth = dp(340) }
        linhaCurio.addView(tituloCurio, LayoutParams(-2, -2).apply { marginStart = dp(12) })
        detalheCurio = texto(14f, m400).apply { alpha = 0.88f }
        linhaCurio.addView(detalheCurio, LayoutParams(-2, -2).apply { marginStart = dp(6) })
        bolinhas = LinearLayout(ctx).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        linhaCurio.addView(bolinhas, LayoutParams(-2, -2).apply { marginStart = dp(16) })
        addView(linhaCurio, LayoutParams(-2, -2).apply { topMargin = dp(12) })
        visibility = GONE
    }

    /** Recebe o que mudou (a cada 15 min, pelo protetor). [ceuIcone] = o desenho do céu de agora, para a frase. */
    fun mostrar(t: TempoAgora?, f: FraseTempo.Frase?, lista: List<Curiosidades.Item>, iconeCeu: android.graphics.drawable.Drawable?) {
        val textoFrase = when {
            f != null -> textoDa(f)
            t != null -> IconesClima.texto(t.codigo)   // nada especial a dizer: o céu de agora
            else -> null
        }
        val fraseVisivel = textoFrase != null && comFrase
        linhaFrase.visibility = if (fraseVisivel) VISIBLE else GONE
        frase.text = textoFrase ?: ""
        iconeFrase.setImageDrawable(iconeCeu); iconeFrase.visibility = if (iconeCeu == null || semIcones) GONE else VISIBLE
        val mudou = lista != itens
        itens = lista
        if (mudou) atual = 0
        val curioVisivel = itens.isNotEmpty() && comCuriosidades
        linhaCurio.visibility = if (curioVisivel) VISIBLE else GONE
        visibility = if (!fraseVisivel && !curioVisivel) GONE else VISIBLE
        desenharCuriosidade()
        removeCallbacks(trocar)
        if (itens.size > 1) postDelayed(trocar, TROCA_MS)
    }

    private val trocar = object : Runnable {
        override fun run() {
            if (itens.size < 2 || !comCuriosidades) return
            // Esmaece, troca e volta: nada pisca na tela parada.
            linhaCurio.animate().alpha(0f).setDuration(500).withEndAction {
                atual = (atual + 1) % itens.size
                desenharCuriosidade()
                linhaCurio.animate().alpha(1f).setDuration(700).start()
            }.start()
            postDelayed(this, TROCA_MS)
        }
    }

    private fun desenharCuriosidade() {
        val item = itens.getOrNull(atual) ?: return
        val (titulo, detalhe, icone) = textoDe(item)
        tituloCurio.text = titulo
        detalheCurio.text = if (detalhe.isEmpty()) "" else "·  $detalhe"
        // O detalhe fica com o que sobra dos 600 dp depois do título (ícone, espaços e bolinhas descontados).
        val larguraTitulo = minOf(tituloCurio.paint.measureText(titulo).toInt(), dp(340))
        detalheCurio.maxWidth = (dp(600) - larguraTitulo - dp(32) - (if (itens.size > 1) dp(16 + 14 * itens.size) else 0)).coerceAtLeast(dp(120))
        iconeCurio.setImageDrawable(icone)
        bolinhas.removeAllViews()
        if (itens.size > 1) itens.indices.forEach { i ->
            bolinhas.addView(View(ctx).apply {
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(if (i == atual) 0xFFFFFFFF.toInt() else 0x66FFFFFF) }
            }, LayoutParams(dp(7), dp(7)).apply { marginStart = dp(if (i == 0) 0 else 7) })
        }
    }

    fun soltar() { removeCallbacks(trocar); linhaCurio.animate().cancel() }

    // ------------------------------------------------------------------ textos (idioma da TV)

    private fun hm(ms: Long) = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))

    fun textoDa(f: FraseTempo.Frase): String = when (f) {
        is FraseTempo.Frase.ChuvaEmMinutos -> ctx.getString(R.string.ft_chuva_min, f.minutos)
        is FraseTempo.Frase.ParaEmMinutos -> ctx.getString(R.string.ft_para_min, f.minutos)
        is FraseTempo.Frase.ChuvaPorVolta -> ctx.getString(R.string.ft_chuva_hora, hm(f.ms))
        is FraseTempo.Frase.ParaPorVolta -> ctx.getString(R.string.ft_para_hora, hm(f.ms))
        FraseTempo.Frase.ChuvaSegue -> ctx.getString(R.string.ft_chuva_segue)
        is FraseTempo.Frase.Trovoada -> ctx.getString(R.string.ft_trovoada, hm(f.ms))
        is FraseTempo.Frase.Geada -> ctx.getString(if (f.hoje) R.string.ft_geada_hoje else R.string.ft_geada_manha)
        is FraseTempo.Frase.CalorForte -> ctx.getString(R.string.ft_calor, f.max)
        FraseTempo.Frase.Neblina -> ctx.getString(R.string.ft_neblina)
        is FraseTempo.Frase.NoiteFria -> ctx.getString(R.string.ft_noite_fria, f.min)
        FraseTempo.Frase.SecarRoupa -> ctx.getString(R.string.ft_secar)
        is FraseTempo.Frase.AmanhaChove -> ctx.getString(R.string.ft_amanha_chove, f.chance)
        FraseTempo.Frase.CeuLimpo -> ctx.getString(R.string.ft_ceu_limpo)
    }

    private fun textoDe(i: Curiosidades.Item): Triple<String, String, android.graphics.drawable.Drawable> {
        val r = ctx.resources
        fun uvConselho(nivel: Int) = ctx.getString(when (nivel) { 1, 2 -> R.string.cu_uv_d2; 3 -> R.string.cu_uv_d3; 4 -> R.string.cu_uv_d4; else -> R.string.cu_uv_d5 })
        return when (i) {
            is Curiosidades.Item.UvAgora -> Triple(ctx.getString(R.string.cu_uv_agora, i.valor), uvConselho(i.nivel), IconeLinha(IconeLinha.Tipo.SOL))
            is Curiosidades.Item.UvAmanha -> Triple(ctx.getString(R.string.cu_uv_amanha, i.valor),
                ctx.getString(R.string.cu_uv_faixa, hm(i.de), hm(i.ate)), IconeLinha(IconeLinha.Tipo.SOL))
            is Curiosidades.Item.Ar -> Triple(ctx.getString(when (i.nivel) {
                2 -> R.string.cu_ar_2; 3 -> R.string.cu_ar_3; 4 -> R.string.cu_ar_4; 5 -> R.string.cu_ar_5; else -> R.string.cu_ar_6 }),
                ctx.getString(when (i.nivel) { 2 -> R.string.cu_ar_d2; 3 -> R.string.cu_ar_d3; 4 -> R.string.cu_ar_d4; else -> R.string.cu_ar_d5 }),
                IconeLinha(IconeLinha.Tipo.FOLHA))
            is Curiosidades.Item.Estacao -> {
                val rumos = r.getStringArray(R.array.ceu_rumos)
                Triple(ctx.getString(R.string.cu_estacao, hm(i.passagem.inicio)),
                    ctx.getString(R.string.cu_estacao_d, i.passagem.minutos, rumos[i.passagem.deRumo], rumos[i.passagem.paraRumo]),
                    IconeLinha(IconeLinha.Tipo.SATELITE))
            }
            is Curiosidades.Item.Meteoros -> Triple(ctx.getString(R.string.cu_meteoros),
                ctx.getString(R.string.cu_meteoros_d, r.getStringArray(R.array.ceu_meteoros)[i.chuva.ordinal], i.chuva.porHora),
                IconeLinha(IconeLinha.Tipo.ESTRELA))
            is Curiosidades.Item.EclipseSol -> Triple(ctx.getString(R.string.cu_eclipse,
                android.icu.text.DateFormat.getInstanceForSkeleton("EEEdMMM", Locale.getDefault()).format(Date(i.ms)) + " · " + hm(i.ms)),
                ctx.getString(R.string.cu_eclipse_d), IconeLinha(IconeLinha.Tipo.SOL))
            is Curiosidades.Item.LuaCheia -> Triple(ctx.getString(if (i.superlua) R.string.cu_superlua else R.string.cu_lua_cheia),
                ctx.getString(R.string.cu_lua_cheia_d), IconeLinha(IconeLinha.Tipo.LUA, 0.5))
            Curiosidades.Item.LuaNova -> Triple(ctx.getString(R.string.cu_lua_nova), ctx.getString(R.string.cu_lua_nova_d), IconeLinha(IconeLinha.Tipo.LUA, 0.0))
            is Curiosidades.Item.Planeta -> Triple(
                ctx.getString(R.string.cu_planeta, r.getStringArray(R.array.ceu_planetas)[i.visivel.planeta.ordinal], hm(i.visivel.ms)),
                r.getStringArray(R.array.ceu_olhe)[i.visivel.rumo], IconeLinha(IconeLinha.Tipo.ESTRELA))
        }
    }

    private fun texto(sp: Float, face: Typeface) = TextView(ctx).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = face; setTextColor(0xFFFFFFFF.toInt()); includeFontPadding = false
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), 0xB3000000.toInt())
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        /** Amarelo âmbar da frase (o dos desenhos aprovados): destaque do tempo, não a cor da marca. Nada rosado. */
        const val AMARELO = 0xFFF6C453.toInt()
        const val TROCA_MS = 15_000L
    }
}
