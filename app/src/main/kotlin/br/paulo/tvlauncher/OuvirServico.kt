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
import br.paulo.tvlauncher.input.EfeitosSom
import br.paulo.tvlauncher.listen.AjustesPodcast
import br.paulo.tvlauncher.listen.AtrasoRadio
import br.paulo.tvlauncher.listen.Capitulos
import br.paulo.tvlauncher.listen.OuvirEstado
import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.listen.Radios
import br.paulo.tvlauncher.listen.Transcricao
import br.paulo.tvlauncher.listen.TranscricaoNuvem
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
        private const val CHAVE_VOZ = "voz_clara"
        /** Voz clara (equalizador da fala + compressor, o mesmo efeito do vídeo): vale para a rádio e para os podcasts sem ajuste próprio. */
        fun vozClara(ctx: Context) = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(CHAVE_VOZ, false)
        /** O ajuste próprio do podcast [feed] ([AjustesPodcast]), ou null (vale o geral). */
        fun ajuste(ctx: Context, feed: String): AjustesPodcast.Ajuste? =
            AjustesPodcast.deJson(ctx.getSharedPreferences(PREFS, MODE_PRIVATE).getString(AjustesPodcast.chave(feed), null))
        fun gravarAjuste(ctx: Context, feed: String, a: AjustesPodcast.Ajuste?) {
            val ed = ctx.getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            if (a == null || a.vazio) ed.remove(AjustesPodcast.chave(feed)) else ed.putString(AjustesPodcast.chave(feed), AjustesPodcast.paraJson(a))
            ed.apply()
        }
        /** O que vale para o podcast [feed]: o ajuste dele ou o geral. */
        fun efetivo(ctx: Context, feed: String?): AjustesPodcast.Efetivo =
            AjustesPodcast.efetivo(feed?.let { ajuste(ctx, it) }, velocidade(ctx), vozClara(ctx), pularSilencio(ctx))

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
        /**
         * A fila do "Meu jornal" ([MeuJornal]): o que toca depois do episódio atual, em ordem. Fica no companion porque o
         * primeiro episódio pode precisar ligar o serviço (ainda não há instância). Escolher outro episódio ou rádio limpa.
         */
        @Volatile var fila: List<EpisodioTocando> = emptyList()
            private set

        /** Toca a lista em sequência, do começo de cada episódio. */
        fun tocarFila(ctx: Context, l: List<EpisodioTocando>) {
            if (l.isEmpty()) return
            tocarEpisodio(ctx, l.first(), 0L)
            fila = l.drop(1)   // depois: o tocarEp do primeiro limpa a fila antiga
        }

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
    /** Voz clara no som do Ouvir (o efeito fica preso à sessão de áudio do player da vez). */
    private val efeitos = EfeitosSom()
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
    /** "Já tocou" de cada rádio desta vez (id → mais nova primeiro). Só na memória: some quando o player fecha. */
    private val tocadas = HashMap<String, List<Radios.Tocada>>()
    fun tocadas(radioId: String): List<Radios.Tocada> = tocadas[radioId].orEmpty()
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
            // daFila: o serviço ligou agora para o 1º do Meu jornal; a fila já está no companion (o parar limpa a velha).
            e != null -> tocarEp(e, intent.getLongExtra("inicio", RETOMAR), daFila = true)
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
        fila = emptyList()
        if (radio?.id == r.id && player != null) { continuar(); return }
        pedidoEp++   // episódio pedido e ainda não começado não passa por cima da rádio
        // O timer de um podcast não passa para a rádio (ela pausava sozinha sem nada na tela dizendo por quê, debug
        // 20/11/2026). Trocar de uma rádio para outra mantém o timer: ele foi marcado na tela da rádio (21/11/2026).
        if (episodio != null || radio == null) { ui.removeCallbacks(dormir); dormirAte = 0L }
        largarAtual()
        radio = r; musica = ""
        player = novoPlayer(r.url, fala = false).also { it.prepare(); it.playWhenReady = true }
        // Voltar 30 s: a gravação paralela começa junto ([AtrasoRadio]); falhando, a rádio segue sem o botão.
        if (AtrasoRadio.suporta(r)) gravador = AtrasoRadio.Gravador(r.url) { ui.post { publicar() } }.also { it.comecar() }
        aplicarEfeito()
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
    fun tocarEp(e: EpisodioTocando, inicioMs: Long, daFila: Boolean = false) {
        if (!daFila) fila = emptyList()
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
        val ef = efetivo(this, e.feed)
        player = novoPlayer(e.ep.url, fala = true).also { p ->
            p.setPlaybackSpeed(ef.velocidade)
            p.skipSilenceEnabled = ef.pularSilencio
            // Começando do zero, pula a abertura que o dono marcou para este podcast.
            val inicio = AjustesPodcast.inicio(inicioMs, ef.aberturaS, e.ep.duracaoS * 1000L)
            if (inicio > 0) p.seekTo(inicio)
            p.prepare(); p.playWhenReady = true
        }
        aplicarEfeito()
        ui.removeCallbacks(salvarPeriodico); ui.postDelayed(salvarPeriodico, SALVAR_MS)
        ui.removeCallbacks(vigiaFim); ui.postDelayed(vigiaFim, 1_000)
        carregarCapitulos(e)
        carregarTexto(e)
        garantirSessao()
        primeiroPlano(e.podcast, e.ep.titulo)
        publicar()
    }

    /** Os capítulos do episódio tocando ([Capitulos]); vazio = o episódio não tem (ou ainda está baixando). */
    @Volatile var capitulos: List<Capitulos.Capitulo> = emptyList()
        private set

    /** Capítulos: os do feed (Podlove) na hora; os do JSON (Podcasting 2.0) baixados fora da thread da tela. */
    private fun carregarCapitulos(e: EpisodioTocando) {
        val campo = e.ep.capitulos
        capitulos = Capitulos.doCampo(campo)
        if (!Capitulos.precisaBaixar(campo)) return
        val guid = e.ep.guid
        Fundo.rodar("capitulos") {
            // O endereço vem do feed (de fora): apontando para a própria rede da casa, não baixa.
            val ips = runCatching { java.net.InetAddress.getAllByName(java.net.URL(campo).host).toList() }.getOrNull()
            if (ips.isNullOrEmpty() || ips.any { Radios.destinoDeFeedProibido(it) }) return@rodar
            val c = Capitulos.doJson(runCatching { Http.getText(campo, maxBytes = 512 * 1024, followRedirects = false) }.getOrNull())
            ui.post { if (episodio?.ep?.guid == guid && c.isNotEmpty()) { capitulos = c; publicar() } }
        }
    }

    /** O texto da fala do episódio tocando ([Transcricao]); vazio = não tem (ou ainda está baixando). */
    @Volatile var texto: List<Transcricao.Trecho> = emptyList()
        private set
    /** Ainda baixando o texto da fala (a tela diz "carregando" em vez de "não tem"). */
    @Volatile var textoCarregando = false
        private set

    /** Baixa a transcrição que o próprio feed oferece (a melhor para sincronizar), fora da thread da tela. */
    private fun carregarTexto(e: EpisodioTocando) {
        texto = emptyList(); textoGerado = false
        val tr = Transcricao.escolher(e.ep.transcricoes) ?: run {
            // Sem texto no feed: o que já foi gerado na nuvem antes (fica guardado; o mesmo episódio nunca vai de novo).
            val guid = e.ep.guid
            Fundo.rodar("transcricao-guardada") {
                val g = TranscricaoNuvem.carregar(this, e.feed, guid)
                if (g != null) ui.post { if (episodio?.ep?.guid == guid) { texto = g.trechos; textoGerado = true; publicar() } }
            }
            return
        }
        val guid = e.ep.guid
        textoCarregando = true
        Fundo.rodar("transcricao") {
            val ips = runCatching { java.net.InetAddress.getAllByName(java.net.URL(tr.url).host).toList() }.getOrNull()
            val l = if (ips.isNullOrEmpty() || ips.any { Radios.destinoDeFeedProibido(it) }) emptyList()
                else Transcricao.ler(runCatching { Http.getText(tr.url, readMs = 15_000, maxBytes = 3 * 1024 * 1024, followRedirects = false) }.getOrNull(), tr.tipo)
            ui.post { if (episodio?.ep?.guid == guid) { texto = l; textoCarregando = false; publicar() } }
        }
    }

    /** O texto veio da nuvem (gerado aqui), não do feed. */
    @Volatile var textoGerado = false
        private set
    /** Gerando o texto na nuvem agora, e o motivo de ter parado ("" = sem erro; "cota", "falhou"). */
    @Volatile var textoGerando = false
        private set
    @Volatile var textoErro = ""
        private set

    /** Dá para pedir o texto à nuvem neste episódio (MP3, sem texto no feed)? */
    fun podeGerarTexto(): Boolean {
        val e = episodio ?: return false
        return Transcricao.escolher(e.ep.transcricoes) == null && TranscricaoNuvem.podeGerar(e.ep)
    }

    /**
     * Gera o texto da fala na nuvem ([TranscricaoNuvem]), a partir de onde está tocando, pedaço por pedaço: cada pedaço
     * que volta já aparece na tela. Para sozinho ao trocar de episódio, ao acabar a cota do dia ou num erro.
     */
    fun gerarTexto() {
        val e = episodio ?: return
        if (textoGerando || !podeGerarTexto()) return
        textoGerando = true; textoErro = ""; publicar()
        val app = applicationContext
        val guid = e.ep.guid
        val dur = duracaoMs.takeIf { it > 0 } ?: (e.ep.duracaoS * 1000L)
        val pos = posicaoMs
        val idioma = java.util.Locale.getDefault().language
        Fundo.rodar("transcricao-nuvem") {
            var erro = ""
            var g = TranscricaoNuvem.carregar(app, e.feed, guid)
            val total = g?.total?.takeIf { it > 0 } ?: TranscricaoNuvem.tamanho(e.ep.url)
            if (total <= 0 || dur <= 0) erro = "falhou"
            else {
                var feitos = g?.feitos.orEmpty()
                var trechos = g?.trechos.orEmpty()
                for (i in TranscricaoNuvem.ordem(total, TranscricaoNuvem.byteDe(pos, total, dur))) {
                    if (episodio?.ep?.guid != guid) break
                    if (i in feitos) continue
                    val de = i * TranscricaoNuvem.PEDACO
                    val ate = minOf(total - 1, de + TranscricaoNuvem.PEDACO - 1)
                    val bytes = runCatching { TranscricaoNuvem.baixar(e.ep.url, de, ate) }.getOrNull()
                    if (bytes == null) { erro = "falhou"; break }
                    val ini = TranscricaoNuvem.msDe(de, total, dur)
                    val seg = (TranscricaoNuvem.msDe(ate + 1, total, dur) - ini) / 1000f
                    val resp = try { TranscricaoNuvem.transcrever(app, bytes, seg, idioma) }
                        catch (x: TranscricaoNuvem.SemCota) { erro = "cota"; break }
                        catch (x: Exception) { null }
                    if (resp == null) { erro = "falhou"; break }
                    trechos = Transcricao.unir(trechos, TranscricaoNuvem.ler(resp, ini))
                    feitos = feitos + i
                    g = TranscricaoNuvem.Guardado(trechos, feitos, total)
                    TranscricaoNuvem.gravar(app, e.feed, guid, g)
                    val agora = trechos
                    ui.post { if (episodio?.ep?.guid == guid) { texto = agora; textoGerado = true; publicar() } }
                }
            }
            ui.post { if (episodio?.ep?.guid == guid) { textoGerando = false; textoErro = erro; publicar() } }
        }
    }

    // ---------------------------------------------------------------- voltar 30 s na rádio (21/11/2026)

    private var gravador: AtrasoRadio.Gravador? = null
    private var leitor: AtrasoRadio.Leitor? = null

    /** Dá para voltar agora (gravando, e já há algo gravado)? */
    val podeVoltar: Boolean get() = radio != null && gravador?.let { !it.falhou && it.anel.escrito > 0 } == true

    private fun taxaRadio(): Long = AtrasoRadio.taxa(radio?.bitrate ?: 0, gravador?.medidoBps ?: 0)

    /** Segundos atrás do ao vivo (0 = ao vivo). */
    val atrasoS: Int get() {
        val l = leitor ?: return 0
        val g = gravador ?: return 0
        return AtrasoRadio.atrasoS(l.pos, g.anel.escrito, taxaRadio()).coerceAtLeast(1)
    }

    /** Volta [AtrasoRadio.PASSO_S] segundos: o player passa a ler da gravação. */
    @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
    fun voltarRadio() {
        val g = gravador ?: return; val p = player ?: return
        if (!podeVoltar) return
        val agora = leitor?.pos ?: g.anel.escrito
        val de = AtrasoRadio.destino(agora, AtrasoRadio.PASSO_S, taxaRadio(), g.anel.maisVelho)
        val l = AtrasoRadio.Leitor(g.anel, de)
        leitor = l
        val fonte = androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory { l }
            .createMediaSource(androidx.media3.common.MediaItem.fromUri("sofaly://radio-atraso"))
        p.setMediaSource(fonte); p.prepare(); p.playWhenReady = true
        publicar()
    }

    /** De volta ao ao vivo (a gravação continua para o próximo "voltar"). */
    fun aoVivo() {
        val r = radio ?: return; val p = player ?: return
        if (leitor == null) return
        leitor = null
        p.setMediaSource(DefaultMediaSourceFactory(PlayerEngine.data(this, userAgent = Http.USER_AGENT)).createMediaSource(PlayerEngine.item(r.url).build()))
        p.prepare(); p.playWhenReady = true
        publicar()
    }

    private fun pararGravador() { gravador?.parar(); gravador = null; leitor = null }

    /** O resumo da Sofy do episódio tocando ("" = ainda não pedido) e as propagandas achadas junto. */
    @Volatile var resumo: String = ""
        private set
    @Volatile var resumindo = false
        private set
    private var propagandas: List<LongRange> = emptyList()
    private val puladas = HashSet<Long>()

    /** Pede o resumo e as propagandas do texto que já existe; [aoTerminar] na thread da tela ("cota" = acabou a de hoje). */
    fun pedirResumo(aoTerminar: (erro: String) -> Unit) {
        val e = episodio ?: return
        if (resumo.isNotEmpty()) { aoTerminar(""); return }
        if (resumindo || texto.isEmpty()) return
        resumindo = true
        val l = texto; val guid = e.ep.guid
        val idioma = java.util.Locale.getDefault().language
        Fundo.rodar("resumo-sofy") {
            var erro = ""
            val r = try { TranscricaoNuvem.resumir(e.ep.titulo, l, idioma) } catch (x: TranscricaoNuvem.SemCota) { erro = "cota"; null } catch (x: Exception) { null }
            ui.post {
                resumindo = false
                if (episodio?.ep?.guid != guid) return@post
                if (r != null && r.first.isNotEmpty()) { resumo = r.first; propagandas = r.second } else if (erro.isEmpty()) erro = "falhou"
                aoTerminar(erro)
            }
        }
    }

    /** Vai para o próximo capítulo (+1) ou volta ao começo deste/anterior (-1). Sem capítulos, não faz nada. */
    fun irCapitulo(delta: Int) {
        val c = capitulos; if (c.isEmpty() || episodio == null) return
        val alvo = if (delta > 0) Capitulos.proximo(c, posicaoMs) else Capitulos.anterior(c, posicaoMs)
        alvo?.let { irPara(it) }
    }

    /** Antes de trocar de rádio/episódio: grava onde o episódio parou e solta o player. */
    private fun largarAtual() {
        if (episodio != null) salvarPosicao()
        ui.removeCallbacks(salvarPeriodico)
        soltarPlayer()
        radio = null; episodio = null; musica = ""; tentativas = 0; pausouEm = 0L; capitulos = emptyList(); texto = emptyList(); textoCarregando = false
        textoGerando = false; textoErro = ""; textoGerado = false
        resumo = ""; resumindo = false; propagandas = emptyList(); puladas.clear()
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
                    if (t != musica) {
                        musica = t
                        tocadas[r.id] = Radios.comTocada(tocadas[r.id].orEmpty(), t, System.currentTimeMillis())
                        publicar()
                    }
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
        // Atrasado e a leitura da gravação falhou (formato que não começa no meio, gravação caiu): volta ao ao vivo.
        if (leitor != null && player === p) { Log.w(TAG, "atraso falhou: ao vivo"); ui.post { aoVivo() }; return }
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

    /**
     * Velocidade, pular silêncios e voz clara mudados no player: com o podcast tocando que tem ajuste próprio, muda o
     * ajuste dele; senão, o geral do Ouvir (vale para todos os outros).
     */
    fun definirVelocidade(v: Float) {
        if (v !in VELOCIDADES) return
        if (!mudarProprio { it.copy(velocidade = v) }) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putFloat(CHAVE_VELOCIDADE, v).apply()
        if (episodio != null) player?.setPlaybackSpeed(v)
        publicar()
    }

    fun definirPularSilencio(sim: Boolean) {
        if (!mudarProprio { it.copy(pularSilencio = sim) }) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CHAVE_SILENCIO, sim).apply()
        if (episodio != null) player?.skipSilenceEnabled = sim
        publicar()
    }

    fun definirVozClara(sim: Boolean) {
        if (!mudarProprio { it.copy(vozClara = sim) }) getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(CHAVE_VOZ, sim).apply()
        aplicarEfeito()
        publicar()
    }

    /** O podcast tocando tem ajuste próprio: aplica [f] nele e devolve true. Sem ajuste próprio (ou rádio): false. */
    private fun mudarProprio(f: (AjustesPodcast.Ajuste) -> AjustesPodcast.Ajuste): Boolean {
        val feed = episodio?.feed ?: return false
        val a = ajuste(this, feed) ?: return false
        gravarAjuste(this, feed, f(a)); return true
    }

    /**
     * Liga ou desliga o ajuste próprio do podcast tocando. Ligar copia o que vale agora (o geral), para nada mudar de
     * repente; desligar volta ao geral na hora.
     */
    fun definirProprio(sim: Boolean) {
        val e = episodio ?: return
        if (sim) {
            if (ajuste(this, e.feed) != null) return
            gravarAjuste(this, e.feed, AjustesPodcast.Ajuste(velocidade(this), vozClara(this), pularSilencio(this)))
        } else gravarAjuste(this, e.feed, null)
        reaplicar()
    }

    /** Muda abertura/fim do podcast tocando (cria o ajuste próprio se não houver). */
    fun definirAberturaFim(aberturaS: Int? = null, fimS: Int? = null) {
        val e = episodio ?: return
        val a = ajuste(this, e.feed) ?: AjustesPodcast.Ajuste(velocidade(this), vozClara(this), pularSilencio(this))
        gravarAjuste(this, e.feed, a.copy(aberturaS = aberturaS ?: a.aberturaS, fimS = fimS ?: a.fimS))
        reaplicar()
    }

    /** Aplica de novo no player tudo o que vale para o podcast tocando. */
    private fun reaplicar() {
        val e = episodio ?: return
        val ef = efetivo(this, e.feed)
        player?.setPlaybackSpeed(ef.velocidade); player?.skipSilenceEnabled = ef.pularSilencio
        aplicarEfeito()
        ui.removeCallbacks(vigiaFim); ui.postDelayed(vigiaFim, 1_000)
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
        // Meu jornal: acabou um, vem o próximo da fila (do começo).
        fila.firstOrNull()?.let { prox -> fila = fila.drop(1); tocarEp(prox, 0L, daFila = true); return }
        parar()
    }

    /** Para de vez: solta o player e a conexão, some da HOME e do protetor, e o serviço acaba. */
    fun parar() {
        fila = emptyList()
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

    private fun soltarPlayer() { pararGravador(); efeitos.soltar(); ui.removeCallbacks(vigiaFim); player?.let { runCatching { it.release() } }; player = null }

    /** Liga ou solta a voz clara no player da vez, conforme o ajuste do podcast (ou o geral, na rádio). */
    private fun aplicarEfeito() {
        val p = player ?: return
        val voz = episodio?.let { efetivo(this, it.feed).vozClara } ?: vozClara(this)
        // Voz clara = equalizador da fala + compressor (como o Voice Boost do Overcast): a fala sobe, o grito não estoura.
        efeitos.aplicar(p.audioSessionId, volumeIgual = voz, vozClara = voz)
    }

    /** Podcast com "pular o fim": confere a cada segundo se já chegou nele (aí conta como ouvido e para). */
    private val vigiaFim = object : Runnable {
        override fun run() {
            val e = episodio ?: return
            val fim = efetivo(this@OuvirServico, e.feed).fimS
            val dur = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
            if (tocando && AjustesPodcast.noFim(posicaoMs, dur, fim)) { Log.i(TAG, "pulei o fim (${fim}s)"); terminouEpisodio(); return }
            // Pular propaganda (texto da fala + Sofy): cada uma uma vez só; ◀ para voltar a ela não pula de novo.
            if (tocando) TranscricaoNuvem.propagandaEm(propagandas, posicaoMs, puladas)?.let { ad ->
                puladas += ad.first
                Log.i(TAG, "pulei propaganda (${(ad.last - ad.first) / 1000}s)")
                irPara(ad.last)
                AvisoTv.rapido(this@OuvirServico, getString(R.string.tx_pulei), getString(R.string.tx_pulei_det, ((ad.last - ad.first) / 1000).toInt()), 3)
            }
            ui.postDelayed(this, 1_000)
        }
    }

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
            (if (ep) PlaybackState.ACTION_SEEK_TO or PlaybackState.ACTION_FAST_FORWARD or PlaybackState.ACTION_REWIND else 0L) or
            (if (ep && capitulos.isNotEmpty()) PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS else 0L)
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
                // ⏭ ⏮ (controles que têm): próximo capítulo / começo do capítulo.
                override fun onSkipToNext() = irCapitulo(1)
                override fun onSkipToPrevious() = irCapitulo(-1)
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
