package br.paulo.tvlauncher.observer

import android.app.ActivityManager
import android.content.Context
import android.os.Process
import br.paulo.tvlauncher.BuildConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Números de leveza" medidos na TV (degrau 2, item 7, 16/11/2026). O tamanho dos APKs sai sozinho em cada Release
 * (scripts/numeros-leveza.py no "Build e Release", tabela nas notas + leveza.json). O que só a TV sabe vem daqui:
 *  - memória do processo da HOME (PSS, o número que o Android usa para decidir quem fecha por falta de memória);
 *  - tempo da HOME: carregar a lista de apps (fora da tela) e desenhar (na thread da tela), mediana das últimas voltas.
 * `EXTRAS --es o leveza` devolve o JSON que a thread do Release copia para site/leveza.json depois de testar na Sala.
 */
object LevezaTv {
    private const val GUARDAR = 15
    private val carregar = ArrayDeque<Long>()
    private val desenhar = ArrayDeque<Long>()

    @Synchronized fun anotarHome(carregarMs: Long, desenharMs: Long) {
        carregar.addLast(carregarMs); desenhar.addLast(desenharMs)
        while (carregar.size > GUARDAR) carregar.removeFirst()
        while (desenhar.size > GUARDAR) desenhar.removeFirst()
    }

    /** Mediana (a do meio): uma volta lenta por acaso (o Android compilando) não puxa o número. */
    fun mediana(l: List<Long>): Long? = l.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

    @Synchronized fun json(ctx: Context): JSONObject {
        val am = ctx.getSystemService(ActivityManager::class.java)
        val pssKb = try { am.getProcessMemoryInfo(intArrayOf(Process.myPid())).firstOrNull()?.totalPss ?: 0 } catch (_: Exception) { 0 }
        val apk = try { File(ctx.applicationInfo.sourceDir).length() } catch (_: Exception) { 0L }
        return JSONObject()
            .put("versao", BuildConfig.VERSION_CODE)
            .put("memoriaMb", (pssKb + 512) / 1024)
            .put("apkMb", Math.round(apk / 1048576.0 * 10) / 10.0)
            .put("homeCarregarMs", mediana(carregar.toList()) ?: JSONObject.NULL)
            .put("homeDesenharMs", mediana(desenhar.toList()) ?: JSONObject.NULL)
            .put("voltas", carregar.size)
            .put("ramTvMb", ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.totalMem / 1048576)
            .put("aparelho", android.os.Build.MODEL)
            .put("abisTv", JSONArray(android.os.Build.SUPPORTED_ABIS.toList()))
    }
}
