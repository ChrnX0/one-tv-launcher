package br.paulo.tvlauncher

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * As notificações que valem mostrar no protetor de tela (10/11/2026, dono: "sinto falta de notificações ali").
 * Quem enche a lista é o [OuvinteNotificacoes]; quem lê é o [ProtetorGaleria]. Só memória: nada vai para disco.
 *
 * Filtro (medido na Sala em 10/11/2026, a maior parte do que o sistema mantém é ruído):
 * - fora: notificações fixas (mídia tocando, "Depuração por Wi-Fi conectada", serviços em primeiro plano), resumos de
 *   grupo, as do próprio sistema ("android": avisos do administrador para cada app) e do controlador de permissões;
 * - fora: importância baixa (abaixo de "padrão": o que o próprio app marcou como sem importância) e sem título;
 * - fora: as do próprio launcher (o despertador já aparece do jeito dele).
 */
object NotificacoesTv {
    class Aviso(val chave: String, val pacote: String, val app: String, val titulo: String, val texto: String, val quando: Long)

    private val IGNORAR = setOf("android", "com.android.systemui", "com.google.android.permissioncontroller",
        "com.android.permissioncontroller", "com.android.shell")

    @Volatile var atuais: List<Aviso> = emptyList(); private set
    private val ouvintes = mutableSetOf<() -> Unit>()
    private val ui by lazy { Handler(Looper.getMainLooper()) }   // lazy: o teste JVM de haQuanto não tem Looper

    fun converter(ctx: Context, sbn: StatusBarNotification, ranking: NotificationListenerService.RankingMap?): Aviso? {
        val n = sbn.notification ?: return null
        // (parênteses obrigatórios: em Kotlin, "and" pesa menos que "!=")
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return null
        if (sbn.packageName in IGNORAR || sbn.packageName == ctx.packageName) return null
        if (n.category in setOf(Notification.CATEGORY_TRANSPORT, Notification.CATEGORY_SERVICE, Notification.CATEGORY_PROGRESS,
                Notification.CATEGORY_SYSTEM, Notification.CATEGORY_STATUS)) return null
        val r = NotificationListenerService.Ranking()
        if (ranking != null && ranking.getRanking(sbn.key, r) && r.importance < NotificationManager.IMPORTANCE_DEFAULT) return null
        val extras = n.extras
        val titulo = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        if (titulo.isEmpty()) return null
        val texto = (extras.getCharSequence(Notification.EXTRA_TEXT) ?: extras.getCharSequence(Notification.EXTRA_BIG_TEXT))
            ?.toString()?.trim()?.lineSequence()?.firstOrNull().orEmpty()
        val app = try {
            ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) { sbn.packageName }
        return Aviso(sbn.key, sbn.packageName, app, titulo, texto, sbn.postTime)
    }

    /** Troca a lista (mais nova primeiro) e avisa quem está olhando, na thread da tela. */
    fun trocar(nova: List<Aviso>) {
        atuais = nova.sortedByDescending { it.quando }
        ui.post { synchronized(ouvintes) { ouvintes.toList() }.forEach { it() } }
    }

    /** Algo que o protetor mostra mudou fora das notificações (um recado novo): avisa quem está olhando. */
    fun avisarMudanca() = trocar(atuais)

    fun ouvir(f: () -> Unit) = synchronized(ouvintes) { ouvintes.add(f) }
    fun parar(f: () -> Unit) = synchronized(ouvintes) { ouvintes.remove(f) }

    /** "agora", "12 min", "3 h", "ontem" ou a data curta. Puro: testado em NotificacoesTvTest. */
    fun haQuanto(quando: Long, agora: Long, ontem: String, agoraTxt: String): String {
        val min = ((agora - quando) / 60_000).coerceAtLeast(0)
        return when {
            min < 1 -> agoraTxt
            min < 60 -> "$min min"
            min < 24 * 60 -> "${min / 60} h"
            min < 48 * 60 -> ontem
            else -> "${min / (24 * 60)} d"
        }
    }
}
