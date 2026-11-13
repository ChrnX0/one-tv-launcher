package br.paulo.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import org.json.JSONObject

/**
 * O "ouvinte de notificações" do launcher. Dois usos:
 * - deixar o launcher ver as sessões de mídia (MediaSessionManager.getActiveSessions exige um ouvinte ligado);
 * - mostrar as notificações no protetor de tela ([NotificacoesTv], 10/11/2026). Ficam só na memória do processo, nunca
 *   em disco nem fora da TV.
 * Ligar: chave [Extras.Chave.TOCANDO_AGORA] ou o protetor Galeria, que rodam `cmd notification allow_listener` pelo
 * shell próprio.
 */
class OuvinteNotificacoes : NotificationListenerService() {
    override fun onListenerConnected() {
        conectado = true; instancia = this
        try { activeNotifications?.forEach { adiarAvisoDepuracao(it) } } catch (_: Exception) {}
        releia()
    }
    override fun onListenerDisconnected() { conectado = false; instancia = null; NotificacoesTv.trocar(emptyList()) }
    override fun onNotificationPosted(sbn: android.service.notification.StatusBarNotification?) {
        if (sbn != null) adiarAvisoDepuracao(sbn)
        releia()
    }
    override fun onNotificationRemoved(sbn: android.service.notification.StatusBarNotification?) = releia()
    override fun onNotificationRankingUpdate(rankingMap: RankingMap?) = releia()

    private fun releia() {
        val lista = try { activeNotifications?.toList().orEmpty() } catch (e: Exception) { emptyList() }
        val ranking = try { currentRanking } catch (e: Exception) { null }
        NotificacoesTv.trocar(lista.mapNotNull { NotificacoesTv.converter(this, it, ranking) })
    }

    /**
     * "Depuração por Wi-Fi conectada" (11/11/2026, dono: tirar do painel). É do próprio Android (pacote "android", canal
     * DEVELOPER_IMPORTANT) e nenhuma configuração a desliga (AdbDebuggingManager.showAdbConnectedNotification não
     * consulta chave nenhuma). A depuração é o nosso shell próprio, que fica sempre ligado, então o aviso é ruído fixo.
     * Adiar (snooze) a tira da tela sem desligar nada; quando o prazo acaba ela volta, passa por aqui e é adiada de novo.
     * Medido na Sala: `cmd notification snooze` tirou o aviso do painel do Google.
     */
    private fun adiarAvisoDepuracao(sbn: android.service.notification.StatusBarNotification) {
        if (sbn.packageName != "android" || sbn.notification?.channelId != "DEVELOPER_IMPORTANT" || !sbn.isOngoing) return
        try { snoozeNotification(sbn.key, ADIAR_MS) } catch (_: Exception) {}
    }

    companion object {
        @Volatile var conectado = false
        /** O ouvinte ligado agora (o painel rápido abre e dispensa notificações por ele), ou null. */
        @Volatile var instancia: OuvinteNotificacoes? = null
        private const val ADIAR_MS = 24 * 60 * 60 * 1000L

        /**
         * Abre a notificação [chave] como o painel do Google faz: dispara a ação dela (contentIntent) e, se ela pede
         * para sumir ao ser tocada (FLAG_AUTO_CANCEL), apaga. Devolve false se não deu (ouvinte desligado, já sumiu).
         * Android 14: quem dispara precisa ceder a própria licença de abrir tela (o painel está na frente), senão o
         * sistema barra a tela do app que criou a notificação, que está em segundo plano.
         */
        fun abrir(chave: String): Boolean {
            val o = instancia ?: return false
            val sbn = try { o.activeNotifications?.firstOrNull { it.key == chave } } catch (_: Exception) { null } ?: return false
            val n = sbn.notification ?: return false
            val acao = n.contentIntent ?: return false
            return try {
                val opcoes = if (android.os.Build.VERSION.SDK_INT >= 34) android.app.ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle() else null
                acao.send(o, 0, null, null, null, null, opcoes)
                if ((n.flags and android.app.Notification.FLAG_AUTO_CANCEL) != 0) o.cancelNotification(chave)
                true
            } catch (_: Exception) { false }
        }

        /** Dispensa a notificação [chave] (como deslizar no celular). As fixas o Android não deixa: nem aparecem no painel. */
        fun dispensar(chave: String): Boolean {
            val o = instancia ?: return false
            return try { o.cancelNotification(chave); true } catch (_: Exception) { false }
        }
    }
}

/**
 * Tocando agora (10/11/2026, 2ª leva das ideias; li no Home Deck): título, artista e app do que está tocando (Spotify,
 * YouTube Music, o nosso player...) e controles tocar/pausar/próxima. Painel, tela Extras e adb. A pílula na HOME fica
 * para depois da foto da HOME (o visual dela está em revisão).
 */
object TocandoAgoraTv {
    private fun componente(ctx: Context) = ComponentName(ctx, OuvinteNotificacoes::class.java)
    private fun alvo(ctx: Context) = "${ctx.packageName}/${OuvinteNotificacoes::class.java.name}"

    /** Liga ou desliga o acesso do ouvinte pelo shell próprio. Fora da thread da tela (o shell espera até 40 s). */
    fun ajustar(ctx: Context, ligar: Boolean): String =
        // O protetor Galeria também usa o ouvinte (notificações na tela): desligar o Tocando agora não o desliga.
        if (!ligar && Protetor.estilo(ctx) == Protetor.GALERIA) "ok (o protetor de tela continua usando)" else GuardiaoShizuku.comando(ctx, "cmd notification ${if (ligar) "allow_listener" else "disallow_listener"} ${alvo(ctx)}")
            ?.trim()?.ifEmpty { "ok" } ?: "sem shell (o adb próprio está desligado?)"

    private fun controladores(ctx: Context): List<MediaController> = try {
        ctx.getSystemService(MediaSessionManager::class.java)?.getActiveSessions(componente(ctx)).orEmpty()
    } catch (e: SecurityException) { emptyList() }

    private fun atual(ctx: Context): MediaController? {
        val cs = controladores(ctx)
        val escolhida = TocandoAgora.escolher(cs.map {
            TocandoAgora.Sessao(it.packageName, it.playbackState?.state == PlaybackState.STATE_PLAYING,
                it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), it.playbackState?.lastPositionUpdateTime ?: 0L)
        }) ?: return null
        return cs.firstOrNull { it.packageName == escolhida.pacote }
    }

    fun json(ctx: Context): JSONObject {
        if (!Extras.ligado(ctx, Extras.Chave.TOCANDO_AGORA)) return JSONObject().put("ligado", false)
        val c = atual(ctx) ?: return JSONObject().put("ligado", true).put("nada", true).put("acesso", OuvinteNotificacoes.conectado)
        val m = c.metadata
        val rotulo = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString() }
            catch (e: Exception) { c.packageName }
        return JSONObject().put("ligado", true).put("app", rotulo)
            .put("titulo", m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty())
            .put("artista", (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty())
            .put("tocando", c.playbackState?.state == PlaybackState.STATE_PLAYING)
    }

    /** O que está tocando, para o painel rápido (vale mesmo com a chave do Tocando agora desligada): null = nada. */
    fun paraPainel(ctx: Context): JSONObject? {
        val c = atual(ctx) ?: return null
        val m = c.metadata
        val titulo = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        if (titulo.isBlank()) return null
        val rotulo = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString() }
            catch (e: Exception) { c.packageName }
        return JSONObject().put("app", rotulo).put("pacote", c.packageName).put("titulo", titulo)
            .put("artista", (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty())
            .put("tocando", c.playbackState?.state == PlaybackState.STATE_PLAYING)
    }

    fun texto(ctx: Context): String = json(ctx).let {
        when {
            !it.optBoolean("ligado") -> "desligado"
            it.optBoolean("nada") -> if (it.optBoolean("acesso")) "nada tocando" else "sem acesso às sessões de mídia"
            else -> "${if (it.optBoolean("tocando")) "▶" else "⏸"} ${it.optString("titulo")} — ${it.optString("artista")} (${it.optString("app")})"
        }
    }

    /** "alternar" (tocar/pausar), "proxima", "anterior". */
    fun controlar(ctx: Context, acao: String): Boolean {
        val c = atual(ctx) ?: return false
        val t = c.transportControls
        when (acao) {
            "alternar" -> if (c.playbackState?.state == PlaybackState.STATE_PLAYING) t.pause() else t.play()
            "proxima" -> t.skipToNext()
            "anterior" -> t.skipToPrevious()
            else -> return false
        }
        return true
    }
}
