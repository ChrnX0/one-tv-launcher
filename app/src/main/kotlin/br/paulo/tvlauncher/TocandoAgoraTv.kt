package br.paulo.tvlauncher

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.service.notification.NotificationListenerService
import br.paulo.tvlauncher.alarm.TocandoAgora
import br.paulo.tvlauncher.listen.OuvirEstado
import br.paulo.tvlauncher.player.avisar
import br.paulo.tvlauncher.privilege.GuardiaoShizuku
import br.paulo.tvlauncher.screensaver.Protetor
import br.paulo.tvlauncher.settings.Extras
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
 * YouTube Music, o nosso player...) e controles tocar/pausar/próxima. Painel, tela Extras e adb. Desde a ideia 75
 * (14/11/2026), também uma pílula na barra do topo da HOME ([paraHome]).
 *
 * O NOSSO som (rádio do [OuvirServico], 17/11/2026) vem primeiro e por outro caminho ([OuvirEstado]): aparece na HOME,
 * no protetor e no painel mesmo com a chave do Tocando agora desligada e sem o ouvinte de notificações. A sessão de
 * mídia que o serviço também publica fica de fora da lista das outras (senão a rádio apareceria duas vezes).
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
            .filter { it.packageName != ctx.packageName }   // a nossa sessão: ver [nosso]
    } catch (e: SecurityException) { emptyList() }

    /** O nosso som (rádio), ou null. Leve: um arquivo pequeno e a lista de processos do app. */
    private fun nosso(ctx: Context): OuvirEstado.Estado? = runCatching { OuvirEstado.ler(ctx) }.getOrNull()
    private fun rotuloNosso(ctx: Context, e: OuvirEstado.Estado) = ctx.getString(if (e.tipo == "podcast") R.string.pod_titulo else R.string.ra_coluna)
    private fun jsonNosso(ctx: Context, e: OuvirEstado.Estado): JSONObject = JSONObject().put("app", rotuloNosso(ctx, e))
        .put("pacote", ctx.packageName).put("titulo", e.titulo).put("artista", e.detalhe).put("tocando", e.tocando)
        .put("nosso", true).put("tipo", e.tipo)

    private fun atual(ctx: Context): MediaController? {
        val cs = controladores(ctx)
        val escolhida = TocandoAgora.escolher(cs.map {
            TocandoAgora.Sessao(it.packageName, it.playbackState?.state == PlaybackState.STATE_PLAYING,
                it.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty(), it.playbackState?.lastPositionUpdateTime ?: 0L)
        }) ?: return null
        return cs.firstOrNull { it.packageName == escolhida.pacote }
    }

    fun json(ctx: Context): JSONObject {
        nosso(ctx)?.let { return jsonNosso(ctx, it).put("ligado", true) }
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
        nosso(ctx)?.let { return jsonNosso(ctx, it) }
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

    /** Ideia 75: o que a pílula da HOME mostra. [capa] = a arte que o app mandou (null: a HOME usa o ícone do app). */
    class Pilula(val pacote: String, val titulo: String, val subtitulo: String, val tocando: Boolean, val capa: android.graphics.Bitmap?,
                 val nosso: Boolean = false, val tipoNosso: String = "")

    /**
     * Para a pílula "Tocando agora" da HOME. Null = não mostrar: chave desligada, nada tocando, sem título ou pausado há
     * mais de [TocandoAgora.PAUSADA_MAX_MS]. Fora da thread da tela (são chamadas ao sistema).
     */
    fun paraHome(ctx: Context): Pilula? {
        // A rádio: nome da rádio no título (é o que se reconhece de longe), a música (ou "Rádio") ao lado.
        nosso(ctx)?.let { e ->
            return Pilula(ctx.packageName, TocandoAgora.tituloPilula(e.titulo), TocandoAgora.subtitulo(e.detalhe, rotuloNosso(ctx, e)),
                e.tocando, null, nosso = true, tipoNosso = e.tipo)
        }
        if (!Extras.ligado(ctx, Extras.Chave.TOCANDO_AGORA)) return null
        val c = atual(ctx) ?: return null
        val m = c.metadata
        val titulo = m?.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
        val estado = c.playbackState
        val tocando = estado?.state == PlaybackState.STATE_PLAYING
        // lastPositionUpdateTime está no relógio desde o boot: para uma sessão pausada, é quando ela pausou.
        val desde = estado?.lastPositionUpdateTime ?: 0L
        val pausadaHa = if (desde > 0) android.os.SystemClock.elapsedRealtime() - desde else -1L
        if (!TocandoAgora.mostrarPilula(titulo, tocando, pausadaHa)) return null
        val rotulo = try { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(c.packageName, 0)).toString() }
            catch (e: Exception) { c.packageName }
        val artista = (m?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: m?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
        val capa = m?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: m?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: m?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        return Pilula(c.packageName, TocandoAgora.tituloPilula(titulo), TocandoAgora.subtitulo(artista, rotulo), tocando, capa)
    }

    /**
     * Avisa [aoMudar] quando o que toca muda (sessão nova, play/pausa, outro título), sem ficar perguntando a cada
     * segundo: cada pergunta traria a capa inteira pelo binder. Só enquanto a HOME está na frente ([ligar]/[desligar]).
     * Sem acesso às sessões (ouvinte desligado), não faz nada.
     */
    class Vigia(private val ctx: Context, private val aoMudar: () -> Unit) {
        private val ui = android.os.Handler(android.os.Looper.getMainLooper())
        private var vigiados: List<MediaController> = emptyList()
        private val avisar = Runnable { aoMudar() }
        private val prazo = Runnable { aoMudar() }
        private fun pedir() { ui.removeCallbacks(avisar); ui.postDelayed(avisar, 250) }   // junta rajadas de eventos
        private val cb = object : MediaController.Callback() {
            override fun onPlaybackStateChanged(state: PlaybackState?) {
                pedir()
                // Pausou: confere de novo quando passar o prazo, para a pílula sumir sozinha.
                ui.removeCallbacks(prazo)
                if (state?.state != PlaybackState.STATE_PLAYING) ui.postDelayed(prazo, TocandoAgora.PAUSADA_MAX_MS + 1000)
            }
            override fun onMetadataChanged(metadata: MediaMetadata?) = pedir()
            override fun onSessionDestroyed() = pedir()
        }
        private val sessoes = MediaSessionManager.OnActiveSessionsChangedListener { l -> trocar(l.orEmpty()); pedir() }
        /** O nosso som avisa por broadcast ([OuvirEstado.ACAO_MUDOU]); vale com ou sem o Tocando agora ligado. */
        private val nossoMudou = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: Context, i: android.content.Intent) {
                pedir()
                // Pausou: confere de novo depois do prazo (a pílula some sozinha, como a dos outros apps).
                ui.removeCallbacks(prazo); ui.postDelayed(prazo, TocandoAgora.PAUSADA_MAX_MS + 1000)
            }
        }
        private var nossoLigado = false

        private fun trocar(l: List<MediaController>) {
            vigiados.forEach { runCatching { it.unregisterCallback(cb) } }
            vigiados = l
            l.forEach { runCatching { it.registerCallback(cb, ui) } }
        }

        fun ligar() {
            if (!nossoLigado) runCatching {
                val f = android.content.IntentFilter(OuvirEstado.ACAO_MUDOU)
                if (android.os.Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(nossoMudou, f, Context.RECEIVER_NOT_EXPORTED)
                else ctx.registerReceiver(nossoMudou, f)
                nossoLigado = true
            }
            if (!Extras.ligado(ctx, Extras.Chave.TOCANDO_AGORA)) { aoMudar(); return }
            try {
                val m = ctx.getSystemService(MediaSessionManager::class.java) ?: return
                m.addOnActiveSessionsChangedListener(sessoes, componente(ctx), ui)
                trocar(m.getActiveSessions(componente(ctx)).orEmpty())
            } catch (e: SecurityException) { }
            aoMudar()
        }

        fun desligar() {
            ui.removeCallbacks(avisar); ui.removeCallbacks(prazo)
            if (nossoLigado) { runCatching { ctx.unregisterReceiver(nossoMudou) }; nossoLigado = false }
            runCatching { ctx.getSystemService(MediaSessionManager::class.java)?.removeOnActiveSessionsChangedListener(sessoes) }
            trocar(emptyList())
        }
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
        // A rádio não tem próxima/anterior: só tocar/pausar. O podcast: próxima = +30 s, anterior = -15 s.
        nosso(ctx)?.let { e -> return when (acao) {
            "alternar" -> OuvirEstado.comandar(ctx, OuvirEstado.ALTERNAR)
            "proxima" -> e.tipo == "podcast" && OuvirEstado.comandar(ctx, OuvirEstado.AVANCAR)
            "anterior" -> e.tipo == "podcast" && OuvirEstado.comandar(ctx, OuvirEstado.VOLTAR)
            else -> false
        } }
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
