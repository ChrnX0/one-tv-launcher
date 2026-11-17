package br.paulo.tvlauncher

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.media.MediaMetadata
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.concurrent.CopyOnWriteArraySet

/**
 * O som que continua fora da tela (17/11/2026, "Ouvir" parte 1). Antes a rádio parava no onStop da RadioActivity; agora
 * quem toca é este serviço, no processo ":player" (o mesmo da tela da rádio e do player), e o som segue na HOME, no
 * protetor de tela e nas Configurações.
 *
 * - Serviço em primeiro plano do tipo "mediaPlayback" enquanto há algo tocando OU pausado: o Android não mata o
 *   processo, e voltar a tocar pela tecla ⏯ não depende de licença para começar serviço em segundo plano.
 * - Sessão de mídia do próprio Android ([MediaSession], sem biblioteca a mais): as teclas ⏯ ⏸ ▶ ⏹ do controle chegam
 *   aqui de qualquer tela, e o painel de notificações do Google mostra o que toca.
 * - O estado vai ao processo principal por [OuvirEstado] (pílula "Tocando" da HOME, protetor, painel, MCP).
 *
 * Quando para sozinho:
 * - outro app pega o som de vez (Netflix, YouTube, a TV ao vivo...): a perda definitiva do foco de áudio PARA (não só
 *   pausa), então o som não volta sozinho depois. Perda passageira (o Assistente falando) só abaixa/pausa e volta;
 * - a tela apaga (TV desligada pelo controle, timer de sono, "Desligar sozinha" depois de 3 h sem tecla);
 * - pausado por mais de [TocandoAgora.PAUSADA_MAX_MS]: solta a conexão e a memória;
 * - a rádio cai: tenta de novo [RETENTATIVAS] vezes (3 s entre elas), depois avisa e para.
 */
class OuvirServico : Service() {
    companion object {
        private const val TAG = "TvLauncher.Ouvir"
        private const val CANAL = "ouvir"
        private const val NOTIF_ID = 8707
        private const val ACAO_RADIO = "br.paulo.tvlauncher.OUVIR_RADIO"
        private const val EXTRA_RADIO = "radio"
        const val RETENTATIVAS = 2
        private const val ESPERA_RETENTATIVA_MS = 3_000L
        /** Pausa mais longa que isto: continuar = voltar ao vivo. */
        const val VOLTAR_AO_VIVO_MS = 15_000L

        /** O serviço vivo (só no processo ":player"), ou null. */
        @Volatile var instancia: OuvirServico? = null
            private set
        private val ouvintes = CopyOnWriteArraySet<() -> Unit>()

        /** [f] é chamado na thread da tela a cada mudança (tocou, pausou, música nova, parou). Mesmo processo. */
        fun ouvir(f: () -> Unit) { ouvintes.add(f) }
        fun esquecer(f: () -> Unit) { ouvintes.remove(f) }

        /** Toca a rádio [r] (troca a que estiver tocando). Chame com a tela na frente: é ela que dá a licença de começar. */
        fun tocarRadio(ctx: Context, r: Radios.Radio) {
            instancia?.let { it.tocar(r); return }
            val i = Intent(ctx, OuvirServico::class.java).setAction(ACAO_RADIO).putExtra(EXTRA_RADIO, Radios.paraJson(listOf(r)).toString())
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { Log.w(TAG, "não deu para começar o serviço: $e") }
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var sessao: MediaSession? = null
    /** A rádio tocando (ou pausada), ou null. */
    var radio: Radios.Radio? = null
        private set
    /** A música que a rádio manda (ICY), "" se não manda. */
    var musica: String = ""
        private set
    private var tentativas = 0
    private var emPrimeiroPlano = false
    /** O último pedido de começar: [parar] encerra só até ele (um pedido novo que chegou no meio não é cortado). */
    private var ultimoInicio = 0
    /** Quando pausou (elapsedRealtime), 0 = tocando. */
    private var pausouEm = 0L

    /** Som saindo agora. */
    val tocando: Boolean get() = player?.isPlaying == true
    /** Pausado pela pessoa (ou pelo Android), com a rádio ainda carregada. */
    val pausado: Boolean get() = player?.playWhenReady == false

    private val pausaLonga = Runnable { Log.i(TAG, "pausado há muito tempo: parei"); parar() }

    private val telaApagou = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            if (i.action == Intent.ACTION_SCREEN_OFF && radio != null) { Log.i(TAG, "tela apagou: parei"); parar() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(telaApagou, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(telaApagou, f)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ultimoInicio = startId
        instancia = this
        // startForegroundService exige startForeground logo: primeiro a notificação, depois o resto.
        val r = intent?.takeIf { it.action == ACAO_RADIO }?.getStringExtra(EXTRA_RADIO)?.let { Radios.deJson(it).firstOrNull() }
        primeiroPlano(r?.nome ?: radio?.nome ?: "", "")
        if (r != null) tocar(r) else if (radio == null) parar()
        // Se o Android matar o processo, não recria sozinho: o som não pode voltar sem ninguém pedir.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(telaApagou) }
        soltarPlayer()
        sessao?.let { runCatching { it.isActive = false; it.release() } }; sessao = null
        radio = null; musica = ""
        OuvirEstado.gravar(this, null)
        if (instancia === this) instancia = null
        avisar()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- comandos

    fun tocar(r: Radios.Radio) {
        if (radio?.id == r.id && player != null) { continuar(); return }
        soltarPlayer()
        radio = r; musica = ""; tentativas = 0; pausouEm = 0L
        val http = DefaultHttpDataSource.Factory().setUserAgent("onetv-launcher/1")
            .setAllowCrossProtocolRedirects(true).setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000)
        val item = MediaItem.Builder().setUri(r.url).apply {
            if (r.url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)) setMimeType(MimeTypes.APPLICATION_M3U8)
        }.build()
        player = ExoPlayer.Builder(this).build().also { p ->
            // true = o ExoPlayer cuida do foco de áudio (pausa quando outro app toca, abaixa quando o Assistente fala).
            p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(), true)
            p.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(m: androidx.media3.common.MediaMetadata) {
                    // ICY "StreamTitle" (Artista - Música) chega como título; texto de espera não vale (Radios.musicaUtil).
                    val t = listOfNotNull(m.title?.toString(), m.artist?.toString()).filter { Radios.musicaUtil(it, r.nome) }
                        .distinct().joinToString(" · ")
                    if (t != musica) { musica = t; publicar() }
                }
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    if (isPlaying) tentativas = 0
                    publicar()
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                    if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                        // Outro app pegou o som de vez: parar (rádio ao vivo pausada não tem para onde voltar).
                        Log.i(TAG, "outro app pegou o som: parei"); parar(); return
                    }
                    ui.removeCallbacks(pausaLonga)
                    pausouEm = if (playWhenReady) 0L else SystemClock.elapsedRealtime()
                    if (!playWhenReady) ui.postDelayed(pausaLonga, TocandoAgora.PAUSADA_MAX_MS)
                    publicar()
                }
                override fun onPlayerError(error: PlaybackException) {
                    Log.w(TAG, "${r.nome}: ${error.errorCodeName} (tentativa ${tentativas + 1})")
                    if (tentativas < RETENTATIVAS) {
                        tentativas++
                        ui.postDelayed({ if (player === p) { p.prepare(); p.playWhenReady = true } }, ESPERA_RETENTATIVA_MS)
                        return
                    }
                    AvisoTv.rapido(this@OuvirServico, r.nome, getString(R.string.ra_falhou), 6)
                    parar()
                }
            })
            p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
            p.prepare(); p.playWhenReady = true
        }
        garantirSessao()
        primeiroPlano(r.nome, "")
        publicar()
        Fundo.rodar("radios") { Radios.contarClique(r) }
    }

    fun alternar() { val p = player ?: return; if (p.playWhenReady) pausar() else continuar() }
    fun pausar() { player?.playWhenReady = false }
    fun continuar() {
        val p = player ?: return
        val pausadoHa = if (pausouEm > 0) SystemClock.elapsedRealtime() - pausouEm else 0L
        // Rádio é ao vivo: depois de uma pausa de verdade, volta para o "agora" (reconecta), não para o áudio guardado.
        // Caiu (rede) enquanto pausado: também reconecta.
        if (p.playbackState == Player.STATE_IDLE || pausadoHa > VOLTAR_AO_VIVO_MS) { p.stop(); p.prepare() }
        p.playWhenReady = true
    }

    /** Para de vez: solta o player e a conexão, some da HOME e do protetor, e o serviço acaba. */
    fun parar() {
        ui.removeCallbacks(pausaLonga)
        soltarPlayer()
        radio = null; musica = ""
        sessao?.let { runCatching { it.setPlaybackState(estadoSessao(PlaybackState.STATE_STOPPED)); it.isActive = false } }
        OuvirEstado.gravar(this, null)
        if (emPrimeiroPlano) { stopForeground(STOP_FOREGROUND_REMOVE); emPrimeiroPlano = false }
        // Já some daqui: uma rádio pedida antes do onDestroy começa um serviço novo, não este que está acabando.
        if (instancia === this) instancia = null
        avisar()
        stopSelf(ultimoInicio)
    }

    private fun soltarPlayer() { player?.let { runCatching { it.release() } }; player = null }

    // ---------------------------------------------------------------- estado para fora

    private fun publicar() {
        val r = radio ?: return
        val agora = tocando
        OuvirEstado.gravar(this, OuvirEstado.Estado("radio", r.id, r.nome, musica, agora, Process.myPid(), SystemClock.elapsedRealtime()))
        sessao?.let { s ->
            runCatching {
                s.setPlaybackState(estadoSessao(when {
                    agora -> PlaybackState.STATE_PLAYING
                    player?.playWhenReady == true -> PlaybackState.STATE_BUFFERING
                    else -> PlaybackState.STATE_PAUSED
                }))
                s.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, musica.ifEmpty { r.nome })
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, if (musica.isEmpty()) getString(R.string.ra_coluna) else r.nome)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, -1L)   // ao vivo
                    .build())
            }
        }
        primeiroPlano(r.nome, musica)
        avisar()
    }

    private fun avisar() = ouvintes.forEach { f -> ui.post { runCatching { f() } } }

    private fun estadoSessao(estado: Int): PlaybackState = PlaybackState.Builder()
        .setActions(PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP)
        .setState(estado, PlaybackState.PLAYBACK_POSITION_UNKNOWN, if (estado == PlaybackState.STATE_PLAYING) 1f else 0f)
        .build()

    /** A sessão que recebe as teclas de mídia de qualquer tela. Uma por serviço; reativada a cada rádio nova. */
    private fun garantirSessao() {
        val s = sessao ?: MediaSession(this, "SofalyOuvir").also { nova ->
            nova.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = continuar()
                override fun onPause() = pausar()
                override fun onStop() = parar()
            }, ui)
            sessao = nova
        }
        runCatching {
            s.setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, RadioActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
            s.isActive = true
        }
    }

    // ---------------------------------------------------------------- notificação (exigida pelo serviço em primeiro plano)

    private fun primeiroPlano(titulo: String, texto: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CANAL) == null)
            nm?.createNotificationChannel(NotificationChannel(CANAL, getString(R.string.ouv_canal), NotificationManager.IMPORTANCE_LOW))
        fun acao(o: String, cod: Int) = PendingIntent.getBroadcast(this, cod,
            Intent(this, OuvirComandoReceiver::class.java).setAction(OuvirEstado.ACAO_COMANDO).putExtra(OuvirEstado.EXTRA_O, o),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CANAL) else @Suppress("DEPRECATION") Notification.Builder(this)
        b.setSmallIcon(R.drawable.ic_ouvir).setContentTitle(titulo.ifEmpty { getString(R.string.ra_coluna) }).setOngoing(true)
            .setContentText(texto.ifEmpty { null })
            .addAction(Notification.Action.Builder(null, getString(if (pausado) R.string.ra_continuar else R.string.ra_pausar), acao(OuvirEstado.ALTERNAR, 1)).build())
            .addAction(Notification.Action.Builder(null, getString(R.string.ra_parar), acao(OuvirEstado.PARAR, 2)).build())
        sessao?.let { b.setStyle(Notification.MediaStyle().setMediaSession(it.sessionToken).setShowActionsInCompactView(0, 1)) }
        val n = b.build()
        try {
            if (!emPrimeiroPlano) {
                if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
                else startForeground(NOTIF_ID, n)
                emPrimeiroPlano = true
            } else nm?.notify(NOTIF_ID, n)
        } catch (e: Exception) { Log.w(TAG, "primeiro plano: $e") }
    }
}

/**
 * Os comandos do Ouvir vindos de outro processo (pílula da HOME, protetor, painel, adb pelo EXTRAS) e dos botões da
 * notificação. Roda no ":player"; sem som tocando, o serviço não existe e nada acontece. Não exportado.
 */
class OuvirComandoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        if (i.action != OuvirEstado.ACAO_COMANDO) return
        val s = OuvirServico.instancia ?: return
        when (i.getStringExtra(OuvirEstado.EXTRA_O)) {
            OuvirEstado.ALTERNAR -> s.alternar()
            OuvirEstado.PAUSAR -> s.pausar()
            OuvirEstado.CONTINUAR -> s.continuar()
            OuvirEstado.PARAR -> s.parar()
        }
    }
}
