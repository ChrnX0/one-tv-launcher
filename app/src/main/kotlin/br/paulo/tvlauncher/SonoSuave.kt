package br.paulo.tvlauncher

import android.graphics.Color
import android.graphics.PixelFormat
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Desligar suave (12/11/2026, ideia 5): nos últimos [DURACAO_MS] do timer de sono ("desligar em 30 min"), a tela escurece
 * aos poucos e o volume desce até [QUEDA_VOLUME] do que era, em vez de cortar de uma vez. Quem dormiu não acorda com o
 * silêncio súbito; quem está acordado percebe e cancela.
 *
 * A película é a mesma janela do filtro noturno (TYPE_ACCESSIBILITY_OVERLAY, sem foco). Não apaga a imagem, então não
 * esbarra no HDMI-CEC. O volume volta ao de antes alguns segundos depois de a tela apagar (na próxima vez que a TV ligar,
 * o som está normal) ou se o timer for cancelado.
 *
 * Chave: [Extras.Chave.SONO_SUAVE] (ligada por padrão: só age quando alguém marcou o timer).
 */
object SonoSuave {
    private const val TAG = "TvLauncher.SonoSuave"
    const val DURACAO_MS = 5 * 60_000L
    const val QUEDA_VOLUME = 0.3          // termina em 30% do volume de antes
    const val ESCURO_MAX = 0.65           // película termina 65% opaca
    private const val PASSO_MS = 10_000L

    /** Regra pura: quanto do caminho já andou (0..1) entre [inicio] e [fim]. */
    fun fracao(agora: Long, inicio: Long, fim: Long): Double =
        if (fim <= inicio) 1.0 else ((agora - inicio).toDouble() / (fim - inicio)).coerceIn(0.0, 1.0)

    /** Regra pura: volume no ponto [f] (0..1), descendo em linha reta de [original] até [QUEDA_VOLUME] dele. Nunca 0 se era > 0. */
    fun volume(original: Int, f: Double): Int {
        if (original <= 0) return original
        val alvo = original * (1 - (1 - QUEDA_VOLUME) * f.coerceIn(0.0, 1.0))
        return Math.round(alvo).toInt().coerceIn(1, original)
    }

    /** Regra pura: opacidade da película (0..255) no ponto [f]. */
    fun alfa(f: Double): Int = (ESCURO_MAX * f.coerceIn(0.0, 1.0) * 255).toInt()

    private val ui by lazy { Handler(Looper.getMainLooper()) }
    private var pelicula: View? = null
    private var volumeAntes = -1
    private var fim = 0L
    private var inicio = 0L

    private val passo = object : Runnable {
        override fun run() {
            val svc = AutorizaShizukuService.instancia ?: return
            val f = fracao(System.currentTimeMillis(), inicio, fim)
            val am = svc.getSystemService(AudioManager::class.java)
            if (volumeAntes > 0) try {
                val alvo = volume(volumeAntes, f)
                if (am.getStreamVolume(AudioManager.STREAM_MUSIC) > alvo) am.setStreamVolume(AudioManager.STREAM_MUSIC, alvo, 0)
            } catch (_: Exception) {}
            por(svc, alfa(f))
            if (f < 1.0) ui.postDelayed(this, PASSO_MS)
        }
    }

    /** Começa a descida, terminando em [fimMs] (a hora de desligar). Pode chamar de qualquer thread. */
    fun comecar(ctx: android.content.Context, fimMs: Long) {
        val app = ctx.applicationContext
        if (!Extras.ligado(app, Extras.Chave.SONO_SUAVE)) return
        ui.post {
            if (AutorizaShizukuService.instancia == null) return@post
            if (volumeAntes < 0) volumeAntes = try {
                app.getSystemService(AudioManager::class.java).getStreamVolume(AudioManager.STREAM_MUSIC)
            } catch (_: Exception) { -1 }
            fim = fimMs; inicio = System.currentTimeMillis()
            Log.i(TAG, "descendo até ${fimMs - inicio} ms (volume era $volumeAntes)")
            ui.removeCallbacks(passo); ui.post(passo)
        }
    }

    /**
     * Para e devolve tudo como era. [esperarMs] > 0 = devolve o volume só depois (a tela acabou de apagar: subir o volume
     * agora daria um susto antes de o CEC desligar a TV).
     */
    fun parar(ctx: android.content.Context, esperarMs: Long = 0L) {
        val app = ctx.applicationContext
        ui.post {
            ui.removeCallbacks(passo)
            tirar()
            val v = volumeAntes
            volumeAntes = -1
            if (v > 0) ui.postDelayed({
                try { app.getSystemService(AudioManager::class.java).setStreamVolume(AudioManager.STREAM_MUSIC, v, 0) } catch (_: Exception) {}
            }, esperarMs)
        }
    }

    private fun por(svc: android.accessibilityservice.AccessibilityService, a: Int) {
        val cor = Color.argb(a, 0, 0, 0)
        pelicula?.let { it.setBackgroundColor(cor); return }
        val v = View(svc).apply { setBackgroundColor(cor) }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        try { svc.getSystemService(WindowManager::class.java).addView(v, p); pelicula = v } catch (_: Exception) {}
    }

    private fun tirar() {
        val v = pelicula ?: return
        pelicula = null
        try { v.context.getSystemService(WindowManager::class.java).removeView(v) } catch (_: Exception) {}
    }
}
