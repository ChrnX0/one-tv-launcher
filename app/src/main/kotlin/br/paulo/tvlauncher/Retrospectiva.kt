package br.paulo.tvlauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Retrospectiva da semana (12/11/2026, ideia 9). Domingo às [HORA]h a TV junta a semana: horas de tela por app, internet
 * gasta e quedas de rede. Vira um cartão no protetor (até segunda ao meio-dia) e fica no painel. Nada sai da TV.
 *
 * As fontes já existiam: uso de apps (UsageStatsManager, a mesma permissão do "uso recente"), consumo por app
 * (ConsumoRedeTv) e o histórico de rede (HistoricoRede). A HOME e o protetor do próprio launcher não contam como "TV".
 */
object Retrospectiva {
    private const val TAG = "TvLauncher.Retro"
    private const val PREFS = "retrospectiva"
    const val HORA = 20
    /** Até quando o cartão fica no protetor depois de pronto: segunda ao meio-dia (~40 h depois de domingo 20h). */
    const val VALIDADE_CARTAO_MS = 40 * 3_600_000L

    data class App(val nome: String, val minutos: Long)
    data class Resumo(val desde: Long, val ate: Long, val minutosTv: Long, val apps: List<App>, val bytes: Long?, val quedas: Int?, val foraMin: Long?)

    /** Regra pura: o próximo domingo às [HORA]h depois de [agora] (no fuso do [cal]). */
    fun proximoDomingo(agora: Long, cal: Calendar = Calendar.getInstance()): Long {
        cal.timeInMillis = agora
        cal.set(Calendar.HOUR_OF_DAY, HORA); cal.set(Calendar.MINUTE, 0); cal.set(Calendar.SECOND, 0); cal.set(Calendar.MILLISECOND, 0)
        while (cal.get(Calendar.DAY_OF_WEEK) != Calendar.SUNDAY || cal.timeInMillis <= agora) cal.add(Calendar.DAY_OF_MONTH, 1)
        return cal.timeInMillis
    }

    /** Regra pura: junta o uso por app (ms) num resumo, sem o próprio launcher, com os 3 maiores. */
    fun resumir(desde: Long, ate: Long, usoMs: Map<String, Long>, nomes: (String) -> String, proprio: String,
                bytes: Long?, quedas: Int?, foraMin: Long?): Resumo {
        val semProprio = usoMs.filterKeys { it != proprio }.filterValues { it >= 60_000L }
        val apps = semProprio.entries.sortedByDescending { it.value }.take(3).map { App(nomes(it.key), it.value / 60_000) }
        return Resumo(desde, ate, semProprio.values.sum() / 60_000, apps, bytes, quedas, foraMin)
    }

    /** Regra pura: as linhas do cartão. "12 h de TV · YouTube 5 h, Netflix 3 h" / "Internet: 42 GB · 1 queda (3 min)". */
    fun linhas(r: Resumo): Pair<String, String> {
        fun h(min: Long) = if (min >= 60) "${min / 60} h" + (if (min % 60 >= 10 && min < 600) " ${min % 60} min" else "") else "$min min"
        val cima = txt(R.string.rt_de_tv, h(r.minutosTv)) + if (r.apps.isEmpty()) "" else " · " + r.apps.joinToString(", ") { "${it.nome} ${h(it.minutos)}" }
        val partes = ArrayList<String>()
        r.bytes?.let { partes += txt(R.string.rt_internet, ConsumoRede.texto(it)) }
        r.quedas?.let { partes += if (it == 0) txt(R.string.rt_sem_queda) else txtN(R.plurals.rt_quedas, it) + " (${r.foraMin ?: 0} min)" }
        return cima to partes.joinToString(" · ")
    }

    fun paraJson(r: Resumo): JSONObject = JSONObject().put("desde", r.desde).put("ate", r.ate).put("minutosTv", r.minutosTv)
        .put("apps", JSONArray().apply { r.apps.forEach { put(JSONObject().put("nome", it.nome).put("minutos", it.minutos)) } })
        .put("bytes", r.bytes ?: JSONObject.NULL).put("quedas", r.quedas ?: JSONObject.NULL).put("foraMin", r.foraMin ?: JSONObject.NULL)

    fun deJson(texto: String?): Resumo? = try {
        val o = JSONObject(texto ?: return null)
        val a = o.optJSONArray("apps") ?: JSONArray()
        Resumo(o.getLong("desde"), o.getLong("ate"), o.optLong("minutosTv"),
            (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { j -> App(j.optString("nome"), j.optLong("minutos")) } },
            if (o.isNull("bytes")) null else o.optLong("bytes"), if (o.isNull("quedas")) null else o.optInt("quedas"),
            if (o.isNull("foraMin")) null else o.optLong("foraMin"))
    } catch (_: Exception) { null }

    // ---- na TV

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun pendente(ctx: Context) = PendingIntent.getBroadcast(ctx, 44, Intent(ctx, RetrospectivaReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        if (!Extras.ligado(ctx, Extras.Chave.RETROSPECTIVA)) { am.cancel(pendente(ctx)); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, proximoDomingo(System.currentTimeMillis()), pendente(ctx))
    }

    /** Monta a da última semana e guarda. FORA da thread da tela (consulta o uso e a rede). */
    fun montar(ctx: Context): Resumo {
        val app = ctx.applicationContext
        val ate = System.currentTimeMillis(); val desde = ate - 7 * 24 * 3_600_000L
        val uso = try {
            app.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(desde, ate)
                .mapValues { it.value.totalTimeInForeground }
        } catch (e: Exception) { Log.w(TAG, "uso: $e"); emptyMap() }
        val pm = app.packageManager
        val nomes = { p: String -> try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p.substringAfterLast('.') } }
        val bytes = ConsumoRedeTv.medir(app, 7).getOrNull()?.sumOf { it.bytes }
        val rede = runCatching { ResumoRede.resumir(HistoricoRede.amostras(app, 168), desde, ate) }.getOrNull()
        val r = resumir(desde, ate, uso, nomes, app.packageName, bytes,
            rede?.quedas?.count { it.tipo == "internet" }, rede?.let { it.foraMs / 60_000 })
        prefs(app).edit().putString("ultima", paraJson(r).toString()).apply()
        return r
    }

    fun ultima(ctx: Context): Resumo? = deJson(prefs(ctx).getString("ultima", null))

    /** O cartão do protetor (título, linha 1, linha 2) enquanto valer, ou null. */
    fun cartao(ctx: Context, agora: Long = System.currentTimeMillis()): Triple<String, String, String>? {
        if (!Extras.ligado(ctx, Extras.Chave.RETROSPECTIVA)) return null
        val r = ultima(ctx) ?: return null
        if (agora - r.ate !in 0..VALIDADE_CARTAO_MS) return null
        val (a, b) = linhas(r)
        return Triple(ctx.getString(R.string.rt_titulo), a, b)
    }
}

/** Domingo às 20h: monta a retrospectiva e agenda a próxima. Não exportado. */
class RetrospectivaReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val r = goAsync(); val app = ctx.applicationContext
        Fundo.rodar("retrospectiva") {
            try { Retrospectiva.montar(app) } catch (e: Exception) { Log.w("TvLauncher.Retro", "falhou: $e") }
            finally { Retrospectiva.agendar(app); r.finish() }
        }
    }
}
