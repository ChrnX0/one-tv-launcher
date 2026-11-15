package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.provider.Settings
import android.util.Log

/**
 * A LG ligou pelo controle DELA e o Chromecast continuou dormindo: tela preta na entrada HDMI (Sala, 15/11/2026, das
 * 11:29 às 11:31; o log do CEC mostrou que a LG só pergunta "como você está?" ao ligar e não manda o Chromecast
 * acordar). O app não pode ler o CEC (permissão de sistema), então olha a LG pela rede: em standby ela sai da rede.
 *
 * Enquanto a tela do Chromecast está apagada, a cada [INTERVALO_MS] vê se a LG está na rede ([VolumeLg.naRede]). Se
 * ela estava fora e voltou ([LgNetcast.Volta]), lê a entrada pelo ROAP e só acorda o Chromecast se a LG estiver na entrada DELE
 * ([LgNetcast.entradaDoChromecast]). Na antena ou em outro HDMI, não faz nada: acordar puxaria a LG para o Chromecast.
 * Precisa da LG pareada (lg_parear); sem isso, não roda.
 */
object AcordarComLg {
    private const val TAG = "TvLauncher.AcordarLg"
    const val INTERVALO_MS = 8_000L
    /** Depois que a LG volta à rede, o ROAP pode demorar a responder: tenta ler a entrada por até isto. */
    private const val ESPERA_ENTRADA_MS = 30_000L

    @Volatile private var app: Context? = null
    @Volatile private var vigia: Thread? = null
    @Volatile private var ultimo = "ainda não olhou"

    /** Liga o vigia (tela apagada = olha a LG; acesa = para). Idempotente. */
    @Synchronized fun iniciar(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == Intent.ACTION_SCREEN_OFF) comecar(a) else parar()
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        if (Build.VERSION.SDK_INT >= 33) a.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else a.registerReceiver(r, f)
        if (!acesa(a)) comecar(a)
    }

    fun estado(ctx: Context): String {
        val e = runCatching { VolumeLg.entrada(ctx) }.getOrNull()
        return "vigia=${if (vigia != null) "olhando" else "parado"}; último: $ultimo; entrada agora: " +
            (e?.let { "${it.nome} (${it.rotulo})" } ?: "sem resposta") + "; aprendida: ${VolumeLg.entradaAprendida(ctx) ?: "-"}"
    }

    private fun acesa(ctx: Context) = ctx.getSystemService(PowerManager::class.java)?.isInteractive != false

    @Synchronized private fun comecar(ctx: Context) {
        if (vigia != null || !VolumeLg.configurado(ctx) || !VolumeLg.acordarJunto(ctx)) return
        val t = Thread({ rodar(ctx) }, "acordar-lg").apply { isDaemon = true }
        vigia = t
        t.start()
    }

    @Synchronized private fun parar() {
        vigia?.interrupt()
        vigia = null
    }

    private fun rodar(ctx: Context) {
        val eu = Thread.currentThread()
        val regra = LgNetcast.Volta()
        try {
            while (vigia === eu && !acesa(ctx)) {
                if (regra.ver(VolumeLg.naRede(ctx))) conferirEAcordar(ctx)
                Thread.sleep(INTERVALO_MS)
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            Log.w(TAG, "vigia parou: $e")
        } finally {
            synchronized(this) { if (vigia === eu) vigia = null }
        }
    }

    private fun conferirEAcordar(ctx: Context) {
        val fim = SystemClock.elapsedRealtime() + ESPERA_ENTRADA_MS
        var e: LgNetcast.Entrada? = null
        while (e == null && SystemClock.elapsedRealtime() < fim && !acesa(ctx)) {
            e = VolumeLg.entrada(ctx)
            if (e == null) Thread.sleep(3000)
        }
        if (acesa(ctx)) { ultimo = "LG ligou, mas o Chromecast já tinha acordado"; return }
        if (e == null) { ultimo = "LG ligou, mas não disse a entrada"; Log.i(TAG, ultimo); return }
        val nosso = runCatching { Settings.Global.getString(ctx.contentResolver, "device_name") }.getOrNull()
        if (!LgNetcast.entradaDoChromecast(e, nosso, VolumeLg.entradaAprendida(ctx))) {
            ultimo = "LG ligou em ${e.nome} (${e.rotulo}): não é o Chromecast, não acordei"
            Log.i(TAG, ultimo)
            return
        }
        VolumeLg.aprenderEntrada(ctx, e.nome)
        ultimo = "LG ligou em ${e.nome}: acordei o Chromecast"
        Log.i(TAG, ultimo)
        runCatching { Diario.anotar(ctx, "tv", "LG ligou", "acordei o Chromecast (${e.nome})") }
        try {
            ctx.startActivity(Intent(ctx, RotinaAcordarActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION))
        } catch (x: Exception) { Log.w(TAG, "não consegui acordar: $x") }
    }
}
