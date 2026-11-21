package br.paulo.tvlauncher

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import br.paulo.tvlauncher.alarm.ConsumoRede
import br.paulo.tvlauncher.network.ConsumoRedeTv
import br.paulo.tvlauncher.network.HistoricoRede
import br.paulo.tvlauncher.network.ResumoRede
import br.paulo.tvlauncher.observer.Recursos
import br.paulo.tvlauncher.panel.eventos
import br.paulo.tvlauncher.settings.Extras
import java.util.Calendar
import org.json.JSONArray
import org.json.JSONObject

/**
 * Retrospectiva da semana (12/11/2026, ideia 9). Domingo às [HORA]h a TV junta a semana: horas de tela por app, internet
 * gasta e quedas de rede. Vira um cartão no protetor (até segunda ao meio-dia) e fica no painel. Nada sai da TV.
 *
 * As fontes já existiam: uso de apps (UsageStatsManager, a mesma permissão do "uso recente"), consumo por app
 * (ConsumoRedeTv) e o histórico de rede (HistoricoRede). A HOME e o protetor do próprio launcher não contam como "TV",
 * mas o player dele (TV ao vivo, filmes, séries, rádio) conta: o uso por pacote mistura os dois, então o tempo do player sai
 * dos eventos de tela (RESUMED/PAUSED por Activity). Antes ficava de fora e o cartão dizia "0 min de TV" (teste 15/11/2026).
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

    /** Chave do tempo do próprio player no mapa de uso (não é pacote; [montar] dá o nome). */
    const val CHAVE_PLAYER = "#player"
    /** Telas do próprio launcher em que a pessoa está assistindo/ouvindo. */
    val TELAS_PLAYER = setOf("br.paulo.tvlauncher.PlayerActivity", "br.paulo.tvlauncher.RadioActivity")

    /** Um evento de tela do próprio app: [tipo] = UsageEvents.Event.ACTIVITY_RESUMED (1) ou ACTIVITY_PAUSED (2). */
    data class EventoTela(val tipo: Int, val classe: String, val em: Long)

    /** Regra pura: ms em que uma das [classes] ficou na frente entre [desde] e [ate]. Aberta no fim conta até [ate]. */
    fun msNasTelas(eventos: List<EventoTela>, classes: Set<String>, desde: Long, ate: Long): Long {
        var total = 0L; var inicio = -1L
        for (e in eventos.sortedBy { it.em }) {
            val nossa = e.classe in classes
            if (e.tipo == 1 && nossa) { if (inicio < 0) inicio = e.em }
            else if (inicio >= 0 && (e.tipo == 2 && nossa || e.tipo == 1)) {
                total += (minOf(e.em, ate) - maxOf(inicio, desde)).coerceAtLeast(0); inicio = -1
            }
        }
        if (inicio >= 0) total += (ate - maxOf(inicio, desde)).coerceAtLeast(0)
        return total
    }

    /** Regra pura: junta o uso por app (ms) num resumo, sem o próprio launcher, com os 3 maiores. */
    fun resumir(desde: Long, ate: Long, usoMs: Map<String, Long>, nomes: (String) -> String, proprio: String,
                bytes: Long?, quedas: Int?, foraMin: Long?): Resumo {
        val semProprio = usoMs.filterKeys { it != proprio }.filterValues { it >= 60_000L }
        val apps = semProprio.entries.sortedByDescending { it.value }.take(3).map { App(nomes(it.key), it.value / 60_000) }
        return Resumo(desde, ate, semProprio.values.sum() / 60_000, apps, bytes, quedas, foraMin)
    }

    /** Regra pura: as linhas do cartão. "12 h de TV · YouTube 5 h, Netflix 3 h" / "Internet: 42 GB · 1 queda (3 min)".
     *  As frases vêm em [Frases]: o padrão é o português (teste JVM); a TV passa as do idioma dela ([cartao]). */
    class Frases(val deTv: (String) -> String = { "$it de TV" }, val internet: (String) -> String = { "Internet: $it" },
                 val semQueda: String = "nenhuma queda", val quedas: (Int) -> String = { if (it == 1) "1 queda" else "$it quedas" })

    fun linhas(r: Resumo, f: Frases = Frases()): Pair<String, String> {
        fun h(min: Long) = if (min >= 60) "${min / 60} h" + (if (min % 60 >= 10 && min < 600) " ${min % 60} min" else "") else "$min min"
        val cima = f.deTv(h(r.minutosTv)) + if (r.apps.isEmpty()) "" else " · " + r.apps.joinToString(", ") { "${it.nome} ${h(it.minutos)}" }
        val partes = ArrayList<String>()
        r.bytes?.let { partes += f.internet(ConsumoRede.texto(it)) }
        r.quedas?.let { partes += if (it == 0) f.semQueda else f.quedas(it) + " (${r.foraMin ?: 0} min)" }
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
        val player = try { msNasTelas(eventosProprios(app, desde, ate), TELAS_PLAYER, desde, ate) }
            catch (e: Exception) { Log.w(TAG, "player: $e"); 0L }
        val pm = app.packageManager
        val nomes = { p: String -> if (p == CHAVE_PLAYER) app.getString(R.string.rt_player) else try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p.substringAfterLast('.') } }
        val bytes = ConsumoRedeTv.medir(app, 7).getOrNull()?.sumOf { it.bytes }
        val rede = runCatching { ResumoRede.resumir(HistoricoRede.amostras(app, 168), desde, ate) }.getOrNull()
        val r = resumir(desde, ate, uso + (CHAVE_PLAYER to player), nomes, app.packageName, bytes,
            rede?.quedas?.count { it.tipo == "internet" }, rede?.let { it.foraMs / 60_000 })
        prefs(app).edit().putString("ultima", paraJson(r).toString()).apply()
        return r
    }

    /** Eventos RESUMED/PAUSED das telas do próprio app (o sistema guarda alguns dias; o que faltar só deixa o total menor). */
    private fun eventosProprios(app: Context, desde: Long, ate: Long): List<EventoTela> {
        val ev = app.getSystemService(UsageStatsManager::class.java).queryEvents(desde, ate) ?: return emptyList()
        val lista = ArrayList<EventoTela>(); val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.packageName != app.packageName) continue
            val tipo = when (e.eventType) { UsageEvents.Event.ACTIVITY_RESUMED -> 1; UsageEvents.Event.ACTIVITY_PAUSED -> 2; else -> continue }
            lista += EventoTela(tipo, e.className ?: "", e.timeStamp)
        }
        return lista
    }

    fun ultima(ctx: Context): Resumo? = deJson(prefs(ctx).getString("ultima", null))

    /**
     * A da última semana para quem pergunta (MCP, painel). Se a guardada saiu sem medida (0 min e sem consumo de rede:
     * a permissão de uso estava negada quando o domingo chegou) e hoje a permissão existe, refaz na hora. Sala,
     * 17/11/2026: a semana dizia "0 min de TV" com a permissão já liberada. FORA da thread da tela.
     */
    fun ultimaMedida(ctx: Context): Resumo? {
        val r = ultima(ctx)
        val semMedida = r == null || (r.minutosTv < 1 && r.bytes == null)
        return if (semMedida && Recursos.usoLiberado(ctx)) runCatching { montar(ctx) }.getOrNull() ?: r else r
    }

    /** O cartão do protetor (título, linha 1, linha 2) enquanto valer, ou null. */
    fun cartao(ctx: Context, agora: Long = System.currentTimeMillis()): Triple<String, String, String>? {
        if (!Extras.ligado(ctx, Extras.Chave.RETROSPECTIVA)) return null
        val r = ultima(ctx) ?: return null
        if (agora - r.ate !in 0..VALIDADE_CARTAO_MS) return null
        if (r.minutosTv < 1) return null   // sem uso medido (ou sem permissão): "0 min de TV" só confunde
        val (a, b) = linhas(r, Frases(deTv = { ctx.getString(R.string.rt_de_tv, it) }, internet = { ctx.getString(R.string.rt_internet, it) },
            semQueda = ctx.getString(R.string.rt_sem_queda), quedas = { ctx.resources.getQuantityString(R.plurals.rt_quedas, it, it) }))
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
