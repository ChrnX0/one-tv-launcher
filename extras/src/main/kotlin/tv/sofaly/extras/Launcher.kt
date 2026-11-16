package tv.sofaly.extras

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle

/**
 * A conversa com o launcher (o ExtrasProvider dele, ver ExtrasPlugin.kt no app). Tudo síncrono e curto: chamar fora da
 * thread da tela. O launcher só responde depois do OK do dono com PIN; antes disso devolve autorizado=false.
 */
object Launcher {
    /** O launcher da Play primeiro; o do site depois. */
    val PACOTES = listOf("tv.sofaly.launcher", "br.paulo.tvlauncher")

    fun instalado(ctx: Context): String? = PACOTES.firstOrNull { p ->
        runCatching { ctx.packageManager.getPackageInfo(p, 0) }.isSuccess
    }

    private fun chamar(ctx: Context, metodo: String): Bundle? {
        val p = instalado(ctx) ?: return null
        return runCatching { ctx.contentResolver.call(Uri.parse("content://$p.extras"), metodo, null, null) }.getOrNull()
    }

    data class Estado(val instalado: Boolean, val autorizado: Boolean, val naoLigouAtivo: Boolean, val nome: String)

    fun estado(ctx: Context): Estado {
        if (instalado(ctx) == null) return Estado(false, false, false, "")
        val b = chamar(ctx, "estado") ?: return Estado(true, false, false, "")
        return Estado(true, b.getBoolean("autorizado"), b.getBoolean("nao_ligou_ativo"), b.getString("nome").orEmpty())
    }

    /** Pede ao launcher a conferência do "não ligou". Quem decide (hora de costume, margem, consentimento) é ele. */
    fun conferir(ctx: Context): Boolean = chamar(ctx, "conferir_nao_ligou")?.getBoolean("aceito") == true

    /**
     * Confere a cada 30 min (alarme inexato: não acorda a TV à toa e não pede permissão de alarme exato). O launcher só
     * avisa entre a hora de costume + margem e 23:30, uma vez por dia.
     */
    fun agendar(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(ctx, 79, Intent(ctx, ConferirReceiver::class.java).setAction(ConferirReceiver.ACAO),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        am.setInexactRepeating(AlarmManager.RTC_WAKEUP, System.currentTimeMillis() + AlarmManager.INTERVAL_HALF_HOUR,
            AlarmManager.INTERVAL_HALF_HOUR, pi)
    }
}
