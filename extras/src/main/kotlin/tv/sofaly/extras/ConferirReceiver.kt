package tv.sofaly.extras

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Boot e o alarme de 30 min: reagenda e pede ao launcher a conferência do "não ligou" (fora da thread principal). */
class ConferirReceiver : BroadcastReceiver() {
    companion object { const val ACAO = "tv.sofaly.extras.CONFERIR" }

    override fun onReceive(ctx: Context, intent: Intent) {
        val app = ctx.applicationContext
        Launcher.agendar(app)
        if (intent.action != ACAO) return
        val fim = goAsync()
        Thread { try { Launcher.conferir(app) } finally { fim.finish() } }.start()
    }
}
