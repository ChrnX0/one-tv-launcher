package br.paulo.tvlauncher.mcp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import br.paulo.tvlauncher.ConfirmarIaActivity
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.privilege.PrivilegioDO

/**
 * Pedido sem resposta na TV (15/11/2026, dono): quando ninguém responde à confirmação em 3 min, o pedido vira uma
 * notificação do launcher dizendo o que foi pedido e por quem. Tocando nela, a [ConfirmarIaActivity] pergunta
 * "Aceitar este pedido?" (Sim/Não); o Sim roda pelo [PedidoIa.aceitarDepois], que confere o catálogo de novo.
 * O pedido inteiro vai nos extras da notificação: sobrevive mesmo se o processo do launcher reiniciar.
 */
object PedidoPendente {
    private const val TAG = "TvLauncher.Ia"
    private const val CANAL = "pedidos_tv"
    const val EXTRA_TARDIO = "tardio"
    const val EXTRA_FRASE = "frase"
    const val EXTRA_ORIGEM = "origem"
    const val EXTRA_ACOES = "acoes"
    const val EXTRA_DESCRICOES = "descricoes"
    const val EXTRA_NOTIF = "notif"

    fun notificar(ctx: Context, p: PedidoIa.Pedido) {
        val app = ctx.applicationContext
        val descricoes = p.plano?.acoes?.map { it.descricao }.orEmpty()
        if (descricoes.isEmpty() || p.acoesJson.isEmpty()) return
        try {
            if (Build.VERSION.SDK_INT >= 33 && app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED)
                PrivilegioDO.conceder(app, app.packageName, "android.permission.POST_NOTIFICATIONS")
            val nm = app.getSystemService(NotificationManager::class.java) ?: return
            if (Build.VERSION.SDK_INT >= 26 && nm.getNotificationChannel(CANAL) == null)
                nm.createNotificationChannel(NotificationChannel(CANAL, app.getString(R.string.pp_canal), NotificationManager.IMPORTANCE_DEFAULT))
            val idNotif = p.id.hashCode()
            val abrir = Intent(app, ConfirmarIaActivity::class.java)
                .putExtra(EXTRA_TARDIO, true).putExtra(EXTRA_FRASE, p.frase).putExtra(EXTRA_ORIGEM, p.origem)
                .putExtra(EXTRA_ACOES, p.acoesJson).putExtra(EXTRA_DESCRICOES, descricoes.toTypedArray()).putExtra(EXTRA_NOTIF, idNotif)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val pi = PendingIntent.getActivity(app, idNotif, abrir, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(app, CANAL) else @Suppress("DEPRECATION") Notification.Builder(app)
            val texto = descricoes.joinToString(", ")
            val n = b.setSmallIcon(R.drawable.ic_onetv_frente)
                .setContentTitle(app.getString(R.string.pp_titulo, ConfirmarIaActivity.nomeOrigem(app, p.origem)))
                .setContentText(texto)
                .setStyle(Notification.BigTextStyle().bigText("“${p.frase}”\n$texto"))
                .setContentIntent(pi).setAutoCancel(true).setWhen(System.currentTimeMillis()).setShowWhen(true)
                .build()
            nm.notify(idNotif, n)
            Log.i(TAG, "pedido ${p.id} sem resposta: ficou nas notificações")
        } catch (e: Exception) { Log.w(TAG, "notificação do pedido falhou: ${e.javaClass.simpleName}") }
    }

    fun apagar(ctx: Context, idNotif: Int) {
        try { ctx.getSystemService(NotificationManager::class.java)?.cancel(idNotif) } catch (_: Exception) {}
    }
}
