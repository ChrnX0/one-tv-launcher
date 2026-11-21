package br.paulo.tvlauncher.weather

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.home.IconeLinha
import java.util.Calendar
import org.json.JSONObject

/**
 * Aviso de calor ou frio forte (ideia 94, 14/11/2026): o mesmo cartão por cima de qualquer tela do aviso de chuva
 * ([AvisoChuva]), pensado para os idosos da casa: "Calor forte hoje: 34° · Beba água" ou "Frio forte esta noite: 8° ·
 * Agasalhe-se".
 *
 * Quando: calor é avisado de manhã/tarde ([HORAS_CALOR]) se a máxima de HOJE chega a [CALOR]°; frio é avisado no fim da
 * tarde ([HORAS_FRIO]) se a mínima da madrugada de AMANHÃ cai a [FRIO]° ou menos. Um aviso de cada tipo por dia.
 * Fonte: a máxima/mínima diária da Open-Meteo (sem chave), no ponto da TV ([Localizacao]).
 * Custo: roda junto do relógio do aviso de chuva (a cada 30 min, só com a tela acesa), mas só busca a previsão quando a
 * hora é de avisar e o tipo ainda não avisou hoje: no máximo umas poucas chamadas por dia.
 */
object AvisoTemperatura {
    private const val TAG = "TvLauncher.Temp"
    private const val PREFS = "aviso_temperatura"
    const val CALOR = 33.0
    const val FRIO = 10.0
    val HORAS_CALOR = 9..15
    val HORAS_FRIO = 17..21
    private const val SEGUNDOS_NA_TELA = 20

    enum class Tipo { CALOR, FRIO }

    fun ligado(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("ligado", true)
    fun definirLigado(ctx: Context, sim: Boolean) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean("ligado", sim).apply()
    }

    // ---------------------------------------------------------------- lógica pura (AvisoTemperaturaTest)

    /** Qual tipo pode ser avisado agora pela hora do dia (antes de buscar a previsão). null = nenhum. */
    fun tipoDaHora(hora: Int): Tipo? = when (hora) {
        in HORAS_CALOR -> Tipo.CALOR
        in HORAS_FRIO -> Tipo.FRIO
        else -> null
    }

    /** Com a previsão em mãos: avisa ou não. [maxHoje] = máxima de hoje; [minAmanha] = mínima de amanhã (madrugada). */
    fun decidir(tipo: Tipo, maxHoje: Double?, minAmanha: Double?): Int? = when (tipo) {
        Tipo.CALOR -> maxHoje?.takeIf { it >= CALOR }?.let { Math.round(it).toInt() }
        Tipo.FRIO -> minAmanha?.takeIf { it <= FRIO }?.let { Math.round(it).toInt() }
    }

    /** "2026-11-14": a chave do dia, para um aviso de cada tipo por dia. */
    fun chaveDia(c: Calendar): String = String.format(java.util.Locale.US, "%04d-%02d-%02d",
        c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))

    // ---------------------------------------------------------------- na TV

    /** Chamado pelo relógio do aviso de chuva, fora da thread da tela e só com a tela acesa. */
    fun verificar(ctx: Context): String {
        val app = ctx.applicationContext
        if (!ligado(app)) return "desligado"
        if (app.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false) return "tela apagada"
        val agora = Calendar.getInstance()
        val tipo = tipoDaHora(agora.get(Calendar.HOUR_OF_DAY)) ?: return "fora do horário"
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val dia = chaveDia(agora)
        if (p.getString("dia_" + tipo.name, "") == dia) return "já avisou hoje"
        val (max, min) = try { buscar(app) } catch (e: Exception) { return "previsão falhou: ${e.javaClass.simpleName}" }
        val graus = decidir(tipo, max, min) ?: return "sem ${tipo.name.lowercase()} forte"
        val r = mostrar(app, tipo, graus)
        if (r == AvisoTv.Resultado.MOSTRADO) p.edit().putString("dia_" + tipo.name, dia).apply()
        Log.i(TAG, "${tipo.name} $graus°: $r")
        return "avisou ${tipo.name} $graus° ($r)"
    }

    fun mostrar(ctx: Context, tipo: Tipo, graus: Int): AvisoTv.Resultado {
        val (titulo, texto) = when (tipo) {
            Tipo.CALOR -> ctx.getString(R.string.tp_calor) to ctx.getString(R.string.tp_calor_texto, graus)
            Tipo.FRIO -> ctx.getString(R.string.tp_frio) to ctx.getString(R.string.tp_frio_texto, graus)
        }
        return AvisoTv.mostrar(ctx, titulo, texto, SEGUNDOS_NA_TELA, som = false, guardarSeApagada = false,
            icone = IconeLinha(IconeLinha.Tipo.GOTA), fica = AvisoRegras.Fica.CHUVA)
    }

    private fun buscar(app: Context): Pair<Double?, Double?> {
        val ponto = Localizacao.atual(app) { Http.getText(it) } ?: throw IllegalStateException("sem localização")
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${ponto.lat}&longitude=${ponto.lon}" +
            "&daily=temperature_2m_max,temperature_2m_min&forecast_days=2&timezone=auto"
        val d = (if (DataSources.weather(app) != DataSources.Weather.OPEN_METEO) DataSources.forecast(app, ponto.lat, ponto.lon, desdeAgora = true, dias = 2)
            else Http.getText(url)?.let { JSONObject(it) })?.getJSONObject("daily") ?: throw IllegalStateException("sem resposta")
        fun valor(k: String, i: Int) = d.optJSONArray(k)?.let { if (it.isNull(i)) null else it.optDouble(i).takeIf { v -> !v.isNaN() } }
        return valor("temperature_2m_max", 0) to valor("temperature_2m_min", 1)
    }
}
