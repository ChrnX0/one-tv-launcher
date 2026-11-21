package br.paulo.tvlauncher.family

import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.settings.Extras
import java.io.File
import java.util.Calendar
import org.json.JSONObject

/**
 * "Sua TV em 2026" (ideia 92, 14/11/2026): em dezembro, um cartão no protetor com o ano da TV: horas de tela, os apps
 * mais vistos e o canal favorito da TV ao vivo. Nada sai da TV.
 *
 * Fontes: o uso de apps do ano (UsageStatsManager, a mesma permissão da retrospectiva da semana) e os minutos por canal,
 * que o player soma ao trocar de canal ou sair ([somarCanal], arquivo em files/player/ano_canais.json: o player roda no
 * processo ":player", e arquivo é o que os dois processos leem igual). Canal bloqueado ou de categoria oculta não conta.
 * O cartão é montado no máximo uma vez por dia (cache em prefs "retrospectiva"). Lógica pura: RetrospectivaAnoTest.
 */
object RetrospectivaAno {
    private const val TAG = "TvLauncher.RetroAno"
    const val MES = Calendar.DECEMBER
    const val MAX_CANAIS = 300

    data class Ano(val ano: Int, val minutosTv: Long, val apps: List<String>, val canal: String?, val minutosCanal: Long)

    // ---------------------------------------------------------------- puro

    /** Soma [minutos] ao [canal] no mapa {"ano": 2026, "canais": {"Globo": 120}}; ano novo zera o mapa. */
    fun somar(json: String?, ano: Int, canal: String, minutos: Long): String {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: JSONObject()
        val canais = if (o.optInt("ano") == ano) o.optJSONObject("canais") ?: JSONObject() else JSONObject()
        val nome = canal.trim().take(60)
        if (nome.isNotEmpty() && minutos > 0) canais.put(nome, canais.optLong(nome) + minutos)
        // Teto: tira o menos visto (uma lista com milhares de canais não faz o arquivo crescer sem fim).
        while (canais.length() > MAX_CANAIS) {
            val menor = canais.keys().asSequence().minByOrNull { canais.optLong(it) } ?: break
            canais.remove(menor)
        }
        return JSONObject().put("ano", ano).put("canais", canais).toString()
    }

    /** O canal mais visto do [ano] e os minutos dele, ou null. */
    fun favorito(json: String?, ano: Int): Pair<String, Long>? {
        val o = runCatching { JSONObject(json ?: return null) }.getOrNull() ?: return null
        if (o.optInt("ano") != ano) return null
        val c = o.optJSONObject("canais") ?: return null
        return c.keys().asSequence().map { it to c.optLong(it) }.filter { it.second > 0 }.maxByOrNull { it.second }
    }

    /** Mostra o cartão? Só em dezembro. */
    fun ehEpoca(c: Calendar): Boolean = c.get(Calendar.MONTH) == MES

    fun resumir(ano: Int, usoMs: Map<String, Long>, nomes: (String) -> String, proprio: String, canal: Pair<String, Long>?): Ano {
        val uso = usoMs.filterKeys { it != proprio }.filterValues { it >= 60_000L }
        return Ano(ano, uso.values.sum() / 60_000, uso.entries.sortedByDescending { it.value }.take(3).map { nomes(it.key) },
            canal?.first, canal?.second ?: 0)
    }

    /** "812 h de TV · YouTube, Netflix, Globoplay" e "Canal favorito: Globo (120 h)". Frases vêm de fora (idioma). */
    fun linhas(a: Ano, deTv: (String) -> String = { "$it de TV" }, canalFav: (String, String) -> String = { c, h -> "Canal favorito: $c ($h)" }): Pair<String, String> {
        fun h(min: Long) = if (min >= 60) "${min / 60} h" else "$min min"
        val cima = deTv(h(a.minutosTv)) + if (a.apps.isEmpty()) "" else " · " + a.apps.joinToString(", ")
        val baixo = a.canal?.let { canalFav(it, h(a.minutosCanal)) } ?: ""
        return cima to baixo
    }

    private fun paraJson(a: Ano, em: Long) = JSONObject().put("em", em).put("ano", a.ano).put("min", a.minutosTv)
        .put("apps", org.json.JSONArray(a.apps)).put("canal", a.canal ?: JSONObject.NULL).put("minCanal", a.minutosCanal)

    private fun deJson(t: String?): Pair<Ano, Long>? = runCatching {
        val o = JSONObject(t ?: return null)
        val apps = o.optJSONArray("apps")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
        Ano(o.getInt("ano"), o.optLong("min"), apps, if (o.isNull("canal")) null else o.optString("canal"), o.optLong("minCanal")) to o.optLong("em")
    }.getOrNull()

    // ---------------------------------------------------------------- na TV

    private fun arquivo(ctx: Context) = File(PlayerArmazem.pasta(ctx), "ano_canais.json")

    /** Chamado pelo player (thread de disco) ao sair de um canal. */
    @Synchronized
    fun somarCanal(ctx: Context, canal: String, minutos: Long) {
        if (minutos <= 0) return
        val f = arquivo(ctx)
        val atual = runCatching { f.readText() }.getOrNull()
        val novo = somar(atual, Calendar.getInstance().get(Calendar.YEAR), canal, minutos)
        runCatching { File(f.parentFile, f.name + ".tmp").apply { writeText(novo) }.renameTo(f) }
    }

    /** Monta o ano até agora. FORA da thread da tela. */
    fun montar(ctx: Context, agora: Long = System.currentTimeMillis()): Ano {
        val app = ctx.applicationContext
        val c = Calendar.getInstance().apply { timeInMillis = agora }
        val ano = c.get(Calendar.YEAR)
        val inicio = (c.clone() as Calendar).apply {
            set(Calendar.DAY_OF_YEAR, 1); set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val uso = try {
            app.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(inicio, agora).mapValues { it.value.totalTimeInForeground }
        } catch (e: Exception) { Log.w(TAG, "uso: $e"); emptyMap() }
        val pm = app.packageManager
        val nomes = { p: String -> try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (_: Exception) { p.substringAfterLast('.') } }
        return resumir(ano, uso, nomes, app.packageName, favorito(runCatching { arquivo(app).readText() }.getOrNull(), ano))
    }

    /** O cartão do protetor (título, linha 1, linha 2) em dezembro, ou null. Remonta no máximo uma vez por dia. */
    fun cartao(ctx: Context, agora: Long = System.currentTimeMillis()): Triple<String, String, String>? {
        val c = Calendar.getInstance().apply { timeInMillis = agora }
        if (!ehEpoca(c) || !Extras.ligado(ctx, Extras.Chave.RETROSPECTIVA_ANO)) return null
        val p = ctx.getSharedPreferences("retrospectiva", Context.MODE_PRIVATE)
        val cache = deJson(p.getString("ano", null))
        val a = if (cache != null && cache.first.ano == c.get(Calendar.YEAR) && agora - cache.second in 0..24 * 3_600_000L) cache.first
            else montar(ctx, agora).also { p.edit().putString("ano", paraJson(it, agora).toString()).apply() }
        if (a.minutosTv < 60) return null   // TV quase sem uso (ou sem permissão de uso): não há o que contar
        val (l1, l2) = linhas(a, deTv = { ctx.getString(R.string.rt_de_tv, it) }, canalFav = { n, h -> ctx.getString(R.string.ra_canal, n, h) })
        return Triple(ctx.getString(R.string.ra_titulo, a.ano), l1, l2)
    }
}
