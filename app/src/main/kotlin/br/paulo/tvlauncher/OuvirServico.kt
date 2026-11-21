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
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import br.paulo.tvlauncher.alarm.TocandoAgora
import br.paulo.tvlauncher.listen.OuvirEstado
import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.listen.Radios
import br.paulo.tvlauncher.player.Episodio
import br.paulo.tvlauncher.player.PlayerEngine
import br.paulo.tvlauncher.player.Posicao
import br.paulo.tvlauncher.player.avisar
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.settings.irPara
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

        private const val ACAO_EPISODIO = "br.paulo.tvlauncher.OUVIR_EPISODIO"
        private const val EXTRA_EP = "ep"
        private const val SALVAR_MS = 15_000L
        /** ◀ volta, ▶ avança (podcast). */
        const val VOLTAR_MS = 15_000L
        const val AVANCAR_MS = 30_000L
        /** Velocidades oferecidas (sem mudar o tom: o ExoPlayer ajusta o tempo, não a afinação). */
        val VELOCIDADES = listOf(0.8f, 1.0f, 1.2f, 1.5f, 1.75f, 2.0f)
        private const val PREFS = "ouvir"
        private const val CHAVE_VELOCIDADE = "velocidade"
        private const val CHAVE_SILENCIO = "pular_silencio"
        fun velocidade(ctx: Context) = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getFloat(CHAVE_VELOCIDADE, 1f).takeIf { it in VELOCIDADES } ?: 1f
        fun pularSilencio(ctx: Context) = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(CHAVE_SILENCIO, false)

        /** [tocarEpisodio] com isto = de onde parou (o serviço lê a posição gravada, depois de gravar a do anterior). */
        const val RETOMAR = -1L

        /** Toca o episódio [e] a partir de [inicioMs] ou [RETOMAR] (troca o que estiver tocando). Chame com a tela na frente. */
        fun tocarEpisodio(ctx: Context, e: EpisodioTocando, inicioMs: Long) {
            instancia?.let { it.tocarEp(e, inicioMs); return }
            val i = Intent(ctx, OuvirServico::class.java).setAction(ACAO_EPISODIO).putExtra(EXTRA_EP, e.paraJson()).putExtra("inicio", inicioMs)
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (ex: Exception) { Log.w(TAG, "não deu para começar o serviço: $ex") }
        }

        /** Toca a rádio [r] (troca a que estiver tocando). Chame com a tela na frente: é ela que dá a licença de começar. */
        fun tocarRadio(ctx: Context, r: Radios.Radio) {
            instancia?.let { it.tocar(r); return }
            val i = Intent(ctx, OuvirServico::class.java).setAction(ACAO_RADIO).putExtra(EXTRA_RADIO, Radios.paraJson(listOf(r)).toString())
            try {
                if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i) else ctx.startService(i)
            } catch (e: Exception) { Log.w(TAG, "não deu para começar o serviço: $e") }
        }
    }

    /** Um episódio de podcast com o que a tela e a pílula precisam (nome e capa do podcast, endereço do feed). */
    data class EpisodioTocando(val feed: String, val podcast: String, val capaPodcast: String, val ep: PodcastFeed.Episodio) {
        fun paraJson(): String = org.json.JSONObject().put("feed", feed).put("podcast", podcast).put("capa", capaPodcast)
            .put("f", Podcasts.feedParaJson(PodcastFeed.Feed(podcast, "", "", capaPodcast, "", "", false, listOf(ep)))).toString()
        companion object {
            fun deJson(t: String?): EpisodioTocando? = runCatching {
                val o = org.json.JSONObject(t ?: return null)
                val ep = Podcasts.feedDeJson(o.optString("f"))?.episodios?.firstOrNull() ?: return null
                val feed = o.optString("feed").takeIf { PodcastFeed.urlValida(it) } ?: return null
                EpisodioTocando(feed, o.optString("podcast"), o.optString("capa"), ep)
            }.getOrNull()
        }
    }

    private val ui = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var sessao: MediaSession? = null
    /** A rádio tocando (ou pausada), ou null. */
    var radio: Radios.Radio? = null
        private set
    /** O episódio tocando (ou pausado), ou null. Só um dos dois, [radio] ou [episodio], existe. */
    var episodio: EpisodioTocando? = null
        private set
    private val ativo: Boolean get() = radio != null || episodio != null
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
    /** Podcast: grava onde está a cada [SALVAR_MS] tocando (a TV pode ser desligada da tomada a qualquer hora). */
    private val salvarPeriodico = object : Runnable {
        override fun run() { if (tocando) salvarPosicao(); ui.postDelayed(this, SALVAR_MS) }
    }

    /**
     * Pausada, a rádio não fica sabendo quando outro app pega o som (o foco só é avisado com o som pedido). Então: pausada
     * e algum som tocando no aparelho = é de outro app (o nosso está parado) → para de vez, e a pílula dá lugar a ele.
     */
    private val outroTocando = object : android.media.AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<android.media.AudioPlaybackConfiguration>?) {
            if (!ativo || !pausado) return
            // Logo depois de pausar, o nosso próprio som ainda pode constar como ativo por um instante.
            if (SystemClock.elapsedRealtime() - pausouEm < 2_000) return
            val am = getSystemService(android.media.AudioManager::class.java) ?: return
            if (am.isMusicActive) { Log.i(TAG, "pausada e outro app tocou: parei"); ui.post { parar() } }
        }
    }

    private val telaApagou = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            // Sem "&& ativo" (18/11/2026): entre escolher o episódio e ele começar (DNS, fila) nada está "ativo", e a
            // TV desligada nesse meio tempo começava a tocar com a tela apagada. parar() também cancela o pedido.
            if (i.action == Intent.ACTION_SCREEN_OFF) { Log.i(TAG, "tela apagou: parei"); parar() }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instancia = this
        val f = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(telaApagou, f, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(telaApagou, f)
        runCatching { getSystemService(android.media.AudioManager::class.java)?.registerAudioPlaybackCallback(outroTocando, ui) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ultimoInicio = startId
        instancia = this
        // startForegroundService exige startForeground logo: primeiro a notificação, depois o resto.
        val r = intent?.takeIf { it.action == ACAO_RADIO }?.getStringExtra(EXTRA_RADIO)?.let { Radios.deJson(it).firstOrNull() }
        val e = intent?.takeIf { it.action == ACAO_EPISODIO }?.getStringExtra(EXTRA_EP)?.let { EpisodioTocando.deJson(it) }
        primeiroPlano(r?.nome ?: e?.podcast ?: radio?.nome ?: episodio?.podcast ?: "", "")
        // Sem primeiro plano o serviço já pediu para parar: não cria player (ficava tocando sem serviço, sem liberar).
        if (!emPrimeiroPlano) return START_NOT_STICKY
        when {
            r != null -> tocar(r)
            e != null -> tocarEp(e, intent.getLongExtra("inicio", RETOMAR))
            !ativo -> parar()
        }
        // Se o Android matar o processo, não recria sozinho: o som não pode voltar sem ninguém pedir.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        pedidoEp++   // um episódio ainda resolvendo o endereço não começa depois do fim do serviço
        ui.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(telaApagou) }
        runCatching { getSystemService(android.media.AudioManager::class.java)?.unregisterAudioPlaybackCallback(outroTocando) }
        if (episodio != null) salvarPosicao()
        soltarPlayer()
        sessao?.let { runCatching { it.isActive = false; it.release() } }; sessao = null
        radio = null; episodio = null; musica = ""
        OuvirEstado.gravar(this, null)
        if (instancia === this) instancia = null
        avisar()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- comandos

    fun tocar(r: Radios.Radio) {
        if (radio?.id == r.id && player != null) { continuar(); return }
        pedidoEp++   // episódio pedido e ainda não começado não passa por cima da rádio
        largarAtual()
        // O timer para dormir é do player de podcast: sem isto a rádio pausava sozinha depois, sem nada na tela dizendo
        // por quê (debug 20/11/2026).
        ui.removeCallbacks(dormir); dormirAte = 0L
        radio = r; musica = ""
        player = novoPlayer(r.url, fala = false).also { it.prepare(); it.playWhenReady = true }
        garantirSessao()
        primeiroPlano(r.nome, "")
        publicar()
        Fundo.rodar("radios") { Radios.contarClique(r); Radios.anotarUltima(applicationContext, r); Radios.anotarRecente(applicationContext, r) }
    }

    /** Conta os pedidos de episódio: só o último que chegou começa a tocar (escolher A e logo B toca B). */
    private var pedidoEp = 0

    /**
     * Toca o episódio [e] a partir de [inicioMs] ([RETOMAR] = de onde parou). O mesmo episódio já carregado continua
     * (e vai para [inicioMs], se veio um). Antes de tocar, fora da thread da tela:
     *  - o endereço do áudio vem do feed (de fora): apontando para a própria TV ou a rede da casa, não toca;
     *  - a posição é lida na fila dos podcasts, DEPOIS da gravação de onde o anterior parou (a ordem é garantida).
     */
    fun tocarEp(e: EpisodioTocando, inicioMs: Long) {
        val atual = episodio
        if (atual != null && atual.feed == e.feed && atual.ep.guid == e.ep.guid && player != null) {
            if (inicioMs >= 0) player?.seekTo(inicioMs)
            continuar(); return
        }
        val n = ++pedidoEp
        largarAtual()
        val app = applicationContext
        Fundo.rodar("podcast") {
            val ips = runCatching { java.net.InetAddress.getAllByName(java.net.URL(e.ep.url).host).toList() }.getOrNull()
            val proibido = ips.isNullOrEmpty() || ips.any { Radios.destinoDeFeedProibido(it) }
            Podcasts.naFila {
                val inicio = if (inicioMs >= 0) inicioMs else Podcasts.retomarEm(Podcasts.posicoes(app)[Podcasts.chave(e.feed, e.ep.guid)])
                ui.post {
                    if (n != pedidoEp) return@post   // outro pedido chegou depois
                    if (proibido) {
                        Log.w(TAG, "endereço do episódio aponta para a própria rede: não toquei")
                        AvisoTv.rapido(this, e.ep.titulo, getString(R.string.pod_falhou), 6); parar()
                    } else comecarEp(e, inicio)
                }
            }
        }
    }

    private fun comecarEp(e: EpisodioTocando, inicioMs: Long) {
        episodio = e
        if (e.ep.video) Log.w(TAG, "episódio em vídeo tocado só como áudio: ${e.ep.titulo}")   // a tela abre o vídeo no player
        player = novoPlayer(e.ep.url, fala = true).also { p ->
            p.setPlaybackSpeed(velocidade(this))
            p.skipSilenceEnabled = pularSilencio(this)
            if (inicioMs > 0) p.seekTo(inicioMs)
            p.prepare(); p.playWhenReady = true
        }
        ui.removeCallbacks(salvarPeriodico); ui.postDelayed(salvarPeriodico, SALVAR_MS)
        garantirSessao()
        primeiroPlano(e.podcast, e.ep.titulo)
        publicar()
    }

    /** Antes de trocar de rádio/episódio: grava onde o episódio parou e solta o player. */
    private fun largarAtual() {
        if (episodio != null) salvarPosicao()
        ui.removeCallbacks(salvarPeriodico)
        soltarPlayer()
        radio = null; episodio = null; musica = ""; tentativas = 0; pausouEm = 0L
    }

    private fun novoPlayer(url: String, fala: Boolean): ExoPlayer {
        val http = PlayerEngine.data(this, userAgent = Http.USER_AGENT)
        val item = PlayerEngine.item(url).build()
        // O motor único (PlayerEngine), com o foco de áudio: pausa quando outro app toca, abaixa quando o Assistente fala.
        return PlayerEngine.build(this, contentType = if (fala) C.AUDIO_CONTENT_TYPE_SPEECH else C.AUDIO_CONTENT_TYPE_MUSIC).also { p ->
            p.addListener(object : Player.Listener {
                override fun onMediaMetadataChanged(m: androidx.media3.common.MediaMetadata) {
                    val r = radio ?: return
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
                        // Outro app pegou o som de vez: parar (rádio ao vivo pausada não tem para onde voltar; o
                        // episódio grava onde parou). Fora do próprio callback: soltar o player dentro dele é arriscado.
                        Log.i(TAG, "outro app pegou o som: parei"); ui.post { parar() }; return
                    }
                    ui.removeCallbacks(pausaLonga)
                    pausouEm = if (playWhenReady) 0L else SystemClock.elapsedRealtime()
                    if (!playWhenReady) { ui.postDelayed(pausaLonga, TocandoAgora.PAUSADA_MAX_MS); if (episodio != null) salvarPosicao() }
                    publicar()
                }
                override fun onPlaybackStateChanged(estado: Int) {
                    if (estado != Player.STATE_ENDED) return
                    // Rádio: o servidor fechou a transmissão sem erro (reiniciou); ao vivo não "acaba", é queda.
                    // Episódio: chegou ao fim = ouvido, e para (nada toca sozinho depois).
                    if (episodio != null) ui.post { terminouEpisodio() } else caiu(p, "fim da transmissão")
                }
                override fun onPlayerError(error: PlaybackException) = caiu(p, error.errorCodeName)
            })
            p.setMediaSource(DefaultMediaSourceFactory(http).createMediaSource(item))
        }
    }

    /** A rádio caiu: com o som pedido, tenta de novo [RETENTATIVAS] vezes; depois avisa e para. Pausada, espera o continuar. */
    private fun caiu(p: ExoPlayer, motivo: String) {
        val nome = radio?.nome ?: episodio?.ep?.titulo ?: return
        Log.w(TAG, "$nome: $motivo (tentativa ${tentativas + 1})")
        if (!p.playWhenReady) { publicar(); return }   // pausada: continuar() reconecta; não volta a tocar sozinha
        if (tentativas < RETENTATIVAS) {
            tentativas++
            // Rádio: reconecta no "agora". Episódio: o prepare retoma do ponto em que estava.
            ui.postDelayed({ if (player === p && p.playWhenReady) { if (radio != null) p.stop(); p.prepare() } }, ESPERA_RETENTATIVA_MS)
            return
        }
        AvisoTv.rapido(this, nome, getString(if (radio != null) R.string.ra_falhou else R.string.pod_falhou), 6)
        ui.post { parar() }
    }

    fun alternar() { val p = player ?: return; if (p.playWhenReady) pausar() else continuar() }
    fun pausar() { player?.playWhenReady = false }
    fun continuar() {
        val p = player ?: return
        val pausadoHa = if (pausouEm > 0) SystemClock.elapsedRealtime() - pausouEm else 0L
        if (episodio != null) {
            // Episódio: continua do ponto (caiu enquanto pausado: reconecta no mesmo ponto).
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
        } else if (p.playbackState == Player.STATE_IDLE || p.playbackState == Player.STATE_ENDED || pausadoHa > VOLTAR_AO_VIVO_MS) {
            // Rádio é ao vivo: depois de uma pausa de verdade, volta para o "agora" (reconecta), não para o áudio guardado.
            p.stop(); p.prepare()
        }
        p.playWhenReady = true
    }

    /** Podcast: pula [deltaMs] (negativo = volta). Rádio ao vivo não pula. */
    fun pular(deltaMs: Long) {
        val p = player ?: return
        if (episodio == null) return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: Long.MAX_VALUE
        p.seekTo((p.currentPosition + deltaMs).coerceIn(0L, maxOf(0L, dur - 1_000)))
        publicar()
    }

    fun irPara(ms: Long) { val p = player ?: return; if (episodio != null) { p.seekTo(ms.coerceAtLeast(0)); publicar() } }

    fun definirVelocidade(v: Float) {
        if (v !in VELOCIDADES) return
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(CHAVE_VELOCIDADE, v).apply()
        if (episodio != null) player?.setPlaybackSpeed(v)
        publicar()
    }

    fun definirPularSilencio(sim: Boolean) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CHAVE_SILENCIO, sim).apply()
        if (episodio != null) player?.skipSilenceEnabled = sim
        publicar()
    }

    /**
     * Timer para dormir (player de podcast, 18/11/2026): pausa sozinho daqui a [ms] (null ou 0 = desliga). Pausa, não
     * para: de manhã o ⏯ continua de onde parou. [dormirAte] é o relógio do aparelho (elapsedRealtime) da pausa; 0 = sem timer.
     */
    var dormirAte: Long = 0L
        private set
    private val dormir = Runnable { dormirAte = 0L; pausar(); publicar() }
    fun definirDormir(ms: Long?) {
        ui.removeCallbacks(dormir); dormirAte = 0L
        if (ms != null && ms > 0) { dormirAte = SystemClock.elapsedRealtime() + ms; ui.postDelayed(dormir, ms) }
        publicar()
    }

    /** Posição e duração do episódio (ms); duração 0 = ainda não sabe. */
    val posicaoMs: Long get() = player?.currentPosition ?: 0L
    val duracaoMs: Long get() = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: ((episodio?.ep?.duracaoS ?: 0) * 1000L)

    /** Grava onde o episódio está (fora da thread da tela: é disco). */
    private fun salvarPosicao(fim: Boolean = false) {
        val e = episodio ?: return
        val dur = duracaoMs
        val ms = if (fim) maxOf(dur, posicaoMs) else posicaoMs
        val p = Podcasts.Posicao(e.feed, e.ep.guid, ms, dur, fim, System.currentTimeMillis(), e.ep.titulo, e.podcast)
        val app = applicationContext
        Podcasts.naFila { Podcasts.guardarPosicao(app, p) }
    }

    private fun terminouEpisodio() {
        if (episodio == null) return
        // Conexão que cai sem erro (áudio sem tamanho declarado) também chega aqui: antes de 95% da duração conhecida
        // não é fim, é queda. Guarda onde parou (o parar grava) e avisa; não marca como ouvido.
        val dur = duracaoMs; val pos = posicaoMs
        // Só com a duração que o PLAYER sabe: a do feed (itunes:duration) costuma vir inflada pela propaganda dinâmica,
        // e um fim de verdade virava "falhou" e nunca marcava ouvido.
        val durDoPlayer = player?.duration?.let { it != C.TIME_UNSET && it > 0 } == true
        if (durDoPlayer && dur > 0 && !Podcasts.ehOuvido(pos, dur)) {
            Log.w(TAG, "fim antes da hora ($pos de $dur ms): guardei a posição")
            AvisoTv.rapido(this, episodio?.ep?.titulo.orEmpty(), getString(R.string.pod_falhou), 6)
            parar(); return
        }
        salvarPosicao(fim = true)
        episodio = null   // já gravado como ouvido: o parar() não grava de novo no meio
        parar()
    }

    /** Para de vez: solta o player e a conexão, some da HOME e do protetor, e o serviço acaba. */
    fun parar() {
        pedidoEp++   // episódio pedido e ainda não começado não começa mais
        ui.removeCallbacks(pausaLonga); ui.removeCallbacks(salvarPeriodico); ui.removeCallbacks(dormir); dormirAte = 0L
        if (episodio != null) salvarPosicao()
        soltarPlayer()
        radio = null; episodio = null; musica = ""
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
        val r = radio; val e = episodio
        if (r == null && e == null) return
        val agora = tocando
        // Para fora vai o PEDIDO (tocar/pausado), não o som: reconectando, a pílula mostra "tocando" e o OK pausa, como esperado.
        val pedido = player?.playWhenReady == true
        val (tipo, id, titulo, detalhe) = if (r != null) listOf("radio", r.id, r.nome, musica)
            else listOf("podcast", e!!.feed + "\u0001" + e.ep.guid, e.podcast, e.ep.titulo)
        OuvirEstado.gravar(this, OuvirEstado.Estado(tipo, id, titulo, detalhe, pedido, Process.myPid(), SystemClock.elapsedRealtime()))
        sessao?.let { s ->
            runCatching {
                s.setPlaybackState(estadoSessao(when {
                    agora -> PlaybackState.STATE_PLAYING
                    pedido -> PlaybackState.STATE_BUFFERING
                    else -> PlaybackState.STATE_PAUSED
                }))
                s.setMetadata(MediaMetadata.Builder()
                    .putString(MediaMetadata.METADATA_KEY_TITLE, if (r != null) musica.ifEmpty { r.nome } else e!!.ep.titulo)
                    .putString(MediaMetadata.METADATA_KEY_ARTIST, if (r != null) (if (musica.isEmpty()) getString(R.string.ra_coluna) else r.nome) else e!!.podcast)
                    .putLong(MediaMetadata.METADATA_KEY_DURATION, if (r != null) -1L else duracaoMs)   // -1 = ao vivo
                    .build())
            }
        }
        primeiroPlano(titulo, detalhe)
        avisar()
    }

    private fun avisar() = ouvintes.forEach { f -> ui.post { runCatching { f() } } }

    private fun estadoSessao(estado: Int): PlaybackState {
        val ep = episodio != null
        val acoes = PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or PlaybackState.ACTION_STOP or
            (if (ep) PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND else 0L)
        val vel = if (estado == PlaybackState.STATE_PLAYING) (if (ep) player?.playbackParameters?.speed ?: 1f else 1f) else 0f
        return PlaybackState.Builder().setActions(acoes)
            .setState(estado, if (ep) posicaoMs else PlaybackState.PLAYBACK_POSITION_UNKNOWN, vel).build()
    }

    /** A sessão que recebe as teclas de mídia de qualquer tela. Uma por serviço; reativada a cada rádio nova. */
    private fun garantirSessao() {
        val s = sessao ?: MediaSession(this, "SofalyOuvir").also { nova ->
            nova.setCallback(object : MediaSession.Callback() {
                override fun onPlay() = continuar()
                // ⏯ enquanto carrega: o padrão só vê "tocando" no STATE_PLAYING e chamava onPlay (nada). Aqui vale o pedido.
                override fun onMediaButtonEvent(i: Intent): Boolean {
                    @Suppress("DEPRECATION") val k = i.getParcelableExtra<android.view.KeyEvent>(Intent.EXTRA_KEY_EVENT)
                    if (k != null && (k.keyCode == android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || k.keyCode == android.view.KeyEvent.KEYCODE_HEADSETHOOK)) {
                        if (k.action == android.view.KeyEvent.ACTION_DOWN && k.repeatCount == 0) alternar()
                        return true
                    }
                    return super.onMediaButtonEvent(i)
                }
                override fun onPause() = pausar()
                override fun onStop() = parar()
                // Teclas ⏪ ⏩ do controle (podcast): 15 s para trás, 30 s para frente.
                override fun onFastForward() = pular(AVANCAR_MS)
                override fun onRewind() = pular(-VOLTAR_MS)
                override fun onSeekTo(pos: Long) = irPara(pos)
            }, ui)
            sessao = nova
        }
        runCatching {
            s.setSessionActivity(PendingIntent.getActivity(this, 0, Intent(this, if (episodio != null) PodcastsActivity::class.java else RadioActivity::class.java)
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
        } catch (e: Exception) {
            Log.w(TAG, "primeiro plano: $e")
            // Sem primeiro plano o Android derruba o processo depois (ForegroundServiceDidNotStartInTime): sai antes.
            if (!emPrimeiroPlano) { soltarPlayer(); radio = null; episodio = null; stopSelf() }
        }
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
            OuvirEstado.AVANCAR -> s.pular(OuvirServico.AVANCAR_MS)
            OuvirEstado.VOLTAR -> s.pular(-OuvirServico.VOLTAR_MS)
        }
    }
}
