package br.paulo.tvlauncher.observer

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import android.util.Log
import br.paulo.tvlauncher.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A parte Android dos "vitals" locais (regras em VitaisLocais.kt). A cada processo principal novo: conta uma abertura
 * e soma as quedas/travadas que o Android registrou desde a última leitura. Arquivo filesDir/vitais.json (medição da
 * própria TV: não entra no backup). Mostra em Diagnóstico e responde a `EXTRAS --es o vitais`.
 */
object VitaisTv {
    private const val TAG = "TvLauncher.Vitais"
    private fun arquivo(ctx: Context) = File(ctx.filesDir, "vitais.json")
    private fun data(ms: Long) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    @Synchronized fun estado(ctx: Context): VitaisLocais.Estado =
        VitaisLocais.ler(try { arquivo(ctx).takeIf { it.exists() }?.readText() } catch (_: Exception) { null })

    @Synchronized private fun gravar(ctx: Context, e: VitaisLocais.Estado) = try {
        val tmp = File(ctx.filesDir, "vitais.json.tmp")
        tmp.writeText(VitaisLocais.texto(e)); tmp.renameTo(arquivo(ctx))
    } catch (e: Exception) { Log.w(TAG, "gravar: $e"); false }

    /** No processo principal novo (LauncherApp), fora da thread da tela. */
    @Synchronized fun aoIniciar(ctx: Context) {
        val agora = System.currentTimeMillis()
        val e = VitaisLocais.somarSaidas(VitaisLocais.abriu(estado(ctx), data(agora)), saidas(ctx))
        gravar(ctx, e)
    }

    private fun saidas(ctx: Context): List<VitaisLocais.Saida> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val am = ctx.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val l = try { am.getHistoricalProcessExitReasons(ctx.packageName, 0, 0) } catch (e: Exception) { return emptyList() }
        return l.mapNotNull { i ->
            val tipo = when (i.reason) {
                ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> VitaisLocais.Tipo.QUEDA
                ApplicationExitInfo.REASON_ANR -> VitaisLocais.Tipo.TRAVADA
                else -> return@mapNotNull null
            }
            VitaisLocais.Saida(i.timestamp, data(i.timestamp), tipo,
                i.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE)
        }
    }

    fun resumo7(ctx: Context): VitaisLocais.Resumo {
        val agora = System.currentTimeMillis()
        return VitaisLocais.resumo(estado(ctx), data(agora), data(agora - 6L * 24 * 60 * 60 * 1000))
    }

    /** Placar (elevação 2.1): o pilar 1 promete zero quedas em 30 dias. */
    fun resumo30(ctx: Context): VitaisLocais.Resumo {
        val agora = System.currentTimeMillis()
        return VitaisLocais.resumo(estado(ctx), data(agora), data(agora - (VitaisLocais.DIAS - 1L) * 24 * 60 * 60 * 1000))
    }

    /** A linha do Diagnóstico: "Últimos 7 dias: …". */
    fun item(ctx: Context): Diagnostico.Item {
        val r = resumo7(ctx)
        val t = ctx.getString(R.string.vit_titulo)
        if (r.quedas + r.travadas == 0) return Diagnostico.Item(t, ctx.getString(R.string.vit_ok, r.diasComUso), 0)
        return Diagnostico.Item(t, ctx.getString(R.string.vit_problemas, r.quedas, r.travadas, r.percebidas, r.diasComProblema, r.diasComUso), r.nivel)
    }

    fun textoAdb(ctx: Context): String {
        val r = resumo7(ctx)
        return "7d: dias=${r.diasComUso} aberturas=${r.aberturas} quedas=${r.quedas} travadas=${r.travadas} percebidas=${r.percebidas} diasComProblema=${r.diasComProblema}\n" +
            estado(ctx).dias.takeLast(14).joinToString("\n") { "${it.data} a=${it.aberturas} q=${it.quedas} t=${it.travadas} p=${it.percebidas}" }
    }
}
