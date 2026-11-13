package br.paulo.tvlauncher

import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.content.Context
import android.net.ConnectivityManager
import android.os.Process
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * Quem gastou a internet (10/11/2026, 2ª leva das ideias): bytes por app desde a meia-noite (ou nos últimos N dias),
 * no Wi-Fi e no cabo, pelo NetworkStatsManager. Precisa do acesso a "uso de apps" (GET_USAGE_STATS), que a autocura do
 * GuardiaoShizuku já concede. Nada sai da TV. Chamar fora da thread da tela.
 */
object ConsumoRedeTv {
    fun medir(ctx: Context, dias: Int = 1): Result<List<ConsumoRede.PorApp>> = try {
        val nsm = ctx.getSystemService(NetworkStatsManager::class.java) ?: error("sem NetworkStatsManager")
        val inicio = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_MONTH, -(dias.coerceIn(1, 30) - 1))
        }.timeInMillis
        val fim = System.currentTimeMillis()
        val usos = ArrayList<ConsumoRede.Uso>()
        for (tipo in intArrayOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_ETHERNET)) {
            val st: NetworkStats = try { nsm.querySummary(tipo, null, inicio, fim) } catch (e: Exception) { continue }
            val b = NetworkStats.Bucket()
            st.use { while (it.hasNextBucket()) { it.getNextBucket(b); usos += ConsumoRede.Uso(b.uid, b.rxBytes + b.txBytes) } }
        }
        val pm = ctx.packageManager
        Result.success(ConsumoRede.somar(usos, { uid ->
            when (uid) {
                Process.SYSTEM_UID -> "Sistema Android"
                NetworkStats.Bucket.UID_REMOVED -> "Apps removidos"
                NetworkStats.Bucket.UID_TETHERING -> "Ponto de acesso"
                else -> pm.getPackagesForUid(uid)?.let { pacotes ->
                    pacotes.joinToString(" + ") { p ->
                        try { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() } catch (e: Exception) { p }
                    }
                }
            }
        }))
    } catch (e: SecurityException) {
        Result.failure(IllegalStateException("sem acesso ao uso de apps (a autocura do shell concede)"))
    } catch (e: Exception) { Result.failure(e) }

    fun json(ctx: Context, dias: Int): JSONObject = medir(ctx, dias).fold(
        { l -> JSONObject().put("dias", dias).put("total", ConsumoRede.texto(l.sumOf { it.bytes }))
            .put("apps", JSONArray().apply { l.forEach { put(JSONObject().put("nome", it.nome).put("bytes", it.bytes).put("texto", ConsumoRede.texto(it.bytes))) } }) },
        { e -> JSONObject().put("erro", e.message ?: e.javaClass.simpleName) })

    fun texto(ctx: Context, dias: Int = 1): String = medir(ctx, dias).fold(
        { l -> l.joinToString("\n") { "${ConsumoRede.texto(it.bytes)}  ${it.nome}" }.ifEmpty { "nada medido ainda" } },
        { e -> "erro: ${e.message}" })
}
