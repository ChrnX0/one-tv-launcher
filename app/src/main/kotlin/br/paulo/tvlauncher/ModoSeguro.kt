package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import br.paulo.tvlauncher.observer.QuedasSeguidas
import java.io.File

/**
 * Modo de segurança da HOME (degrau 2, 16/11/2026): a parte do Android. As regras estão em QuedasSeguidas.kt.
 *
 * Um arquivo de uma linha em filesDir (lido uma vez por processo, gravado só quando muda). Quem chama:
 *  - RegistroErros (processo morrendo): [aoCair];
 *  - MainActivity.onCreate: [desviar] manda para a HomeSeguraActivity se o modo estiver ligado;
 *  - MainActivity.onResume/onPause: [aoRetomarHome]/[aoPausarHome] marcam a HOME como estável depois de 30 s.
 */
object ModoSeguro {
    private const val TAG = "TvLauncher.ModoSeguro"
    private const val ARQUIVO = "modo-seguro.txt"

    /** A HOME já ficou estável neste processo (só a thread da tela escreve; o tratador de quedas lê). */
    @Volatile private var homeEstavel = false
    @Volatile private var cache: QuedasSeguidas.Estado? = null
    private val ui by lazy { Handler(Looper.getMainLooper()) }
    private val marcarEstavel = Runnable {
        homeEstavel = true
        val app = appRef ?: return@Runnable
        val e = estado(app)
        val n = QuedasSeguidas.aoEstabilizar(e)
        if (n != e) Fundo.rodar("modo-seguro") { gravar(app, n) }
    }
    @Volatile private var appRef: Context? = null

    private fun versao(): Long = BuildConfig.VERSION_CODE.toLong()
    private fun arquivo(ctx: Context) = File(ctx.filesDir, ARQUIVO)

    private fun estado(ctx: Context): QuedasSeguidas.Estado = cache ?: try {
        val f = arquivo(ctx)
        QuedasSeguidas.ler(if (f.exists()) f.readText() else null)
    } catch (_: Exception) { QuedasSeguidas.Estado() }.also { cache = it }

    private fun gravar(ctx: Context, e: QuedasSeguidas.Estado) {
        cache = e
        try { arquivo(ctx).writeText(QuedasSeguidas.texto(e)) } catch (ex: Exception) { Log.w(TAG, "não gravou: $ex") }
    }

    fun ativo(ctx: Context) = QuedasSeguidas.ativo(estado(ctx), versao())

    /** O processo está caindo (sem thread nova: grava aqui mesmo, é uma linha). */
    fun aoCair(ctx: Context) {
        // Só o processo principal (o da HOME): uma queda do player (":player") não é a HOME em laço.
        val nome = if (android.os.Build.VERSION.SDK_INT >= 28) android.app.Application.getProcessName() else null
        if (nome != null && nome != ctx.packageName) return
        val e = estado(ctx)
        val n = QuedasSeguidas.aoCair(e, versao(), System.currentTimeMillis(), homeEstavel)
        if (n != e) gravar(ctx, n)
        if (!QuedasSeguidas.ativo(e, versao()) && QuedasSeguidas.ativo(n, versao()))
            Log.e(TAG, "a HOME caiu ${QuedasSeguidas.QUEDAS} vezes seguidas: modo de segurança ligado")
    }

    /** No começo do onCreate da HOME. true = desviou para a HOME mínima (a HOME normal deve parar ali). */
    fun desviar(a: Activity): Boolean {
        if (!ativo(a)) return false
        try {
            a.startActivity(Intent(a, HomeSeguraActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (ex: Exception) { Log.w(TAG, "não abriu a HOME mínima: $ex"); return false }
        a.finish()
        return true
    }

    fun aoRetomarHome(a: Activity) {
        appRef = a.applicationContext
        if (homeEstavel) return
        ui.removeCallbacks(marcarEstavel)
        ui.postDelayed(marcarEstavel, QuedasSeguidas.ESTAVEL_MS)
    }

    fun aoPausarHome() = ui.removeCallbacks(marcarEstavel)

    /** "Tentar a tela inicial normal". */
    fun sair(ctx: Context) = gravar(ctx, QuedasSeguidas.sair())

    /** Liga à mão (adb: EXTRAS --es o modo_seguro_testar), para conferir a HOME mínima sem derrubar nada. */
    fun ligarParaTeste(ctx: Context) = gravar(ctx, QuedasSeguidas.Estado(seguroVersao = versao()))
}
