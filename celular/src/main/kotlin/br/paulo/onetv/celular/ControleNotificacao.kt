package br.paulo.onetv.celular

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.TileService

/**
 * Controle fora do app (estudo global 20/11/2026, ideia 16).
 *
 * Por quê: para pausar ou baixar o volume, era preciso desbloquear, abrir o app e achar o botão.
 * Para quê: pausar e mudar o volume da TV direto da notificação, inclusive com o celular bloqueado, ou pelo bloco
 * "Controle da TV" nas Configurações rápidas do Android.
 * Como: depois de abrir o controle uma vez, fica uma notificação discreta (sem som, a pessoa pode deslizar para tirar)
 * com ⏯, Vol − e Vol +; cada botão manda a tecla pela [ApiTv] para a última TV usada.
 */
object ControleNotificacao {
    private const val CANAL = "controle"
    private const val ID = 1
    const val ACAO = "br.paulo.onetv.celular.TECLA"
    const val EXTRA_TECLA = "tecla"

    fun mostrar(ctx: Context, tv: Pareamento.Tv) {
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CANAL, ctx.getString(R.string.cel_notif_canal), NotificationManager.IMPORTANCE_LOW).apply {
            setShowBadge(false); lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        })
        fun acao(rotulo: Int, tecla: String, req: Int) = Notification.Action.Builder(
            Icon.createWithResource(ctx, R.drawable.ic_controle), ctx.getString(rotulo),
            PendingIntent.getBroadcast(ctx, req, Intent(ctx, TeclaRecebedor::class.java).setAction(ACAO).putExtra(EXTRA_TECLA, tecla),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)).build()
        val abrir = PendingIntent.getActivity(ctx, 0, Intent(ctx, ControleActivity::class.java)
            .putExtra(ControleActivity.EXTRA_CHAVE, Pareamento.chave(tv)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CANAL)
            .setSmallIcon(R.drawable.ic_controle)
            .setContentTitle(tv.nome.ifEmpty { tv.ip })
            .setContentText(ctx.getString(R.string.cel_notif_texto))
            .setContentIntent(abrir)
            .setVisibility(Notification.VISIBILITY_PUBLIC)   // tela bloqueada: os botões aparecem (nada secreto nela)
            .setColor(DesignTokens.ORANGE)                  // a cor da marca no ícone (o único laranja: marca)
            .setOnlyAlertOnce(true)
            .addAction(acao(R.string.cel_ctrl_vol_menos, "volume-", 1))
            .addAction(acao(R.string.cel_ctrl_play, "play", 2))
            .addAction(acao(R.string.cel_ctrl_vol_mais, "volume+", 3))
            .setStyle(Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2))
            .build()
        runCatching { nm.notify(ID, n) }   // sem a permissão de notificação (Android 13+), simplesmente não aparece
    }
}

/** Os botões da notificação chegam aqui; a tecla vai em segundo plano (goAsync segura o processo até terminar). */
class TeclaRecebedor : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != ControleNotificacao.ACAO) return
        val tecla = i.getStringExtra(ControleNotificacao.EXTRA_TECLA) ?: return
        if (tecla !in setOf("play", "volume+", "volume-", "mudo")) return   // só as teclas que a notificação oferece
        val tv = ApiTv.ultima(ctx) ?: return
        val fim = goAsync()
        ApiTv.emSegundoPlano { try { ApiTv.tecla(tv, tecla) } finally { fim.finish() } }
    }
}

/** O bloco "Controle da TV" nas Configurações rápidas: um toque abre o controle nativo da última TV. */
class ControleBloco : TileService() {
    override fun onStartListening() {
        qsTile?.apply { label = getString(R.string.cel_bloco); state = android.service.quicksettings.Tile.STATE_INACTIVE; updateTile() }
    }

    override fun onClick() {
        val i = Intent(this, ControleActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE))
        else @Suppress("DEPRECATION") startActivityAndCollapse(i)
    }
}
