package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Ideias 58 e 59: o diário da TV (prefs "diario", fora dos backups) e o webhook do dono. Regras em DiarioRegras.
 * Quem escreve: PainelArmazem.registrar (painel, Claude, backup) e o receptor do adb, num ponto só cada.
 */
object Diario {
    private const val TAG = "TvLauncher.Diario"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("diario", Context.MODE_PRIVATE)
    private val chamadas = ArrayDeque<Long>()

    @Synchronized fun anotar(ctx: Context, ip: String, acao: String, resultado: String) {
        val agora = System.currentTimeMillis()
        val origem = DiarioRegras.origem(ip)
        val item = JSONObject().put("quando", agora).put("origem", origem).put("acao", acao.take(200)).put("resultado", resultado.take(200))
        val p = prefs(ctx)
        p.edit().putString("itens", DiarioRegras.anotar(p.getString("itens", null), item, agora)).apply()
        avisarWebhook(ctx.applicationContext, origem, item)
    }

    fun ler(ctx: Context, horas: Int, origem: String? = null): JSONArray =
        DiarioRegras.filtrar(prefs(ctx).getString("itens", null), System.currentTimeMillis(), horas, origem)

    // ---------------------------------------------------------------- webhook (ideia 59)

    fun webhook(ctx: Context): String = prefs(ctx).getString("webhook", "") ?: ""
    fun origensWebhook(ctx: Context): Set<String> = DiarioRegras.lerOrigens(prefs(ctx).getString("webhook_origens", null))

    /** Endereço vazio desliga. False se o endereço não serve (DiarioRegras.webhookValido). */
    fun definirWebhook(ctx: Context, url: String, origens: Set<String>): Boolean {
        if (url.isBlank()) { prefs(ctx).edit().remove("webhook").remove("webhook_origens").apply(); return true }
        if (!DiarioRegras.webhookValido(url)) return false
        prefs(ctx).edit().putString("webhook", url.trim())
            .putString("webhook_origens", origens.filter { it in DiarioRegras.ORIGENS }.joinToString(",")).apply()
        return true
    }

    private fun avisarWebhook(app: Context, origem: String, item: JSONObject) {
        val url = webhook(app).ifEmpty { return }
        if (origem !in origensWebhook(app)) return
        val agora = System.currentTimeMillis()
        synchronized(chamadas) {
            while (chamadas.isNotEmpty() && agora - chamadas.first() > 60_000L) chamadas.removeFirst()
            if (!DiarioRegras.podeChamar(chamadas.toList(), agora)) return
            chamadas.addLast(agora)
        }
        val corpo = JSONObject(item.toString()).put("tv", android.os.Build.MODEL).toString().toByteArray(Charsets.UTF_8)
        Fundo.rodar("webhook") {
            try {
                val c = URL(url).openConnection() as HttpURLConnection   // https: a verificação de TLS padrão do Android
                c.connectTimeout = 5000; c.readTimeout = 5000; c.requestMethod = "POST"; c.doOutput = true
                c.instanceFollowRedirects = false
                c.setRequestProperty("Content-Type", "application/json"); c.setRequestProperty("User-Agent", "onetv-launcher")
                c.outputStream.use { it.write(corpo) }
                val codigo = c.responseCode
                c.disconnect()
                prefs(app).edit().putString("webhook_ultimo", "$codigo").apply()
            } catch (e: Exception) {
                Log.w(TAG, "webhook falhou: ${e.javaClass.simpleName}")
                prefs(app).edit().putString("webhook_ultimo", e.javaClass.simpleName).apply()
            }
        }
    }

    fun ultimoWebhook(ctx: Context): String = prefs(ctx).getString("webhook_ultimo", "") ?: ""
}
