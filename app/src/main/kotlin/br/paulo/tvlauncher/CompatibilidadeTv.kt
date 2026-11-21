package br.paulo.tvlauncher

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.os.SystemClock
import br.paulo.tvlauncher.home.Home
import br.paulo.tvlauncher.home.WatchNextTv
import br.paulo.tvlauncher.observer.DesempenhoTv
import br.paulo.tvlauncher.observer.LevezaTv
import br.paulo.tvlauncher.observer.RegistroErros
import br.paulo.tvlauncher.privilege.PrivilegioDO
import org.json.JSONArray

/**
 * A parte do Android do relatório de compatibilidade ([Compatibilidade]). Duas tarefas:
 *  - medir o "acordar": o instante do SCREEN_ON e o 1º quadro da HOME depois dele (o FrameMetrics da DesempenhoTv
 *    chama [quadroDaHome]); guarda as 20 últimas em prefs "compatibilidade";
 *  - juntar os fatos do aparelho em [fatos], para o JSON da página pública e o texto do adb.
 *
 * Por que uptimeMillis e não elapsedRealtime: o relógio só começa a contar DEPOIS do SCREEN_ON, então o tempo de
 * standby (em que o uptime para) nunca entra na conta; e o FrameMetrics já usa o mesmo relógio na DesempenhoTv.
 */
object CompatibilidadeTv {
    private const val PREFS = "compatibilidade"
    /** Depois de 10 s o 1º quadro já não é "acordar" (a pessoa estava em outro app e só voltou depois). */
    private const val JANELA_MS = 10_000
    @Volatile private var acendeuEm = 0L
    @Volatile private var ouvindo = false

    /** Liga o ouvinte de "tela acendeu". Uma vez por processo (SCREEN_ON só chega a receiver registrado em código). */
    fun vigiar(ctx: Context) {
        if (ouvindo) return
        ouvindo = true
        val app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) { acendeuEm = SystemClock.uptimeMillis() }
        }
        val f = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
    }

    /** Chamado pela DesempenhoTv a cada quadro da HOME (thread "quadros"). Só faz algo no 1º depois de acender. */
    fun quadroDaHome(ctx: Context) {
        val t0 = acendeuEm
        if (t0 == 0L) return
        acendeuEm = 0L
        val ms = (SystemClock.uptimeMillis() - t0).toInt()
        if (ms > JANELA_MS) return
        val app = ctx.applicationContext
        Fundo.rodar("compat-acordar") { runCatching { anotar(app, ms) } }
    }

    @Synchronized
    private fun anotar(ctx: Context, ms: Int) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val nova = Compatibilidade.anotarAcordar(lerAcordar(ctx), ms)
        p.edit().putString("acordar", JSONArray(nova).toString()).apply()
    }

    fun lerAcordar(ctx: Context): List<Int> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("acordar", null) ?: return emptyList()
        return runCatching { JSONArray(s).let { a -> List(a.length()) { a.optInt(it) } } }.getOrDefault(emptyList())
    }

    fun fatos(ctx: Context): Compatibilidade.Fatos {
        val pm = ctx.packageManager
        val ram = runCatching {
            ActivityManager.MemoryInfo().also { ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(it) }.totalMem / (1024 * 1024)
        }.getOrDefault(0L)
        val homeNossa = runCatching {
            pm.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName == ctx.packageName
        }.getOrDefault(false)
        val googleTv = runCatching { pm.getPackageInfo(Home.LAUNCHERX, 0); true }.getOrDefault(false)
        val agora = System.currentTimeMillis()
        val quedas = runCatching { RegistroErros.listar(ctx).count { agora - it.quando < 24 * 3_600_000L && it.daVersao(BuildConfig.VERSION_CODE.toLong()) } }.getOrDefault(0)
        return Compatibilidade.Fatos(
            fabricante = Build.MANUFACTURER ?: "?",
            modelo = Build.MODEL ?: "?",
            aparelho = Build.DEVICE ?: "?",
            android = Build.VERSION.RELEASE ?: "?",
            sdk = Build.VERSION.SDK_INT,
            abis = Build.SUPPORTED_ABIS?.toList() ?: emptyList(),
            ramMb = ram,
            processo64 = Process.is64Bit(),
            googleTv = googleTv,
            fireOs = pm.hasSystemFeature("amazon.hardware.fire_tv"),
            versaoApp = BuildConfig.VERSION_NAME,
            versaoCode = BuildConfig.VERSION_CODE.toLong(),
            dono = PrivilegioDO.somosDonos(ctx),
            acessibilidade = AutorizaShizukuService.instancia != null,
            homePreferida = homeNossa,
            aberturaMs = DesempenhoTv.aberturaMediana(ctx),
            acordarMs = lerAcordar(ctx),
            quedas24h = quedas,
            ligadoHoras = SystemClock.elapsedRealtime() / 3_600_000L,
            watchNext = WatchNextTv.ler(ctx).map { it.size }.getOrDefault(-1),
            entradas = runCatching { EntradasTv.listar(ctx).size }.getOrDefault(0),
        )
    }

    fun json(ctx: Context) = Compatibilidade.json(fatos(ctx), System.currentTimeMillis()).also { j ->
        // Números de leveza (degrau 2): memória e tempo da HOME vão junto para a página pública (só medidas).
        val l = LevezaTv.json(ctx)
        j.put("leveza", org.json.JSONObject().put("memoriaMb", l.get("memoriaMb")).put("apkMb", l.get("apkMb"))
            .put("homeCarregarMs", l.get("homeCarregarMs")).put("homeDesenharMs", l.get("homeDesenharMs")))
    }
    fun texto(ctx: Context) = Compatibilidade.texto(fatos(ctx))
}
