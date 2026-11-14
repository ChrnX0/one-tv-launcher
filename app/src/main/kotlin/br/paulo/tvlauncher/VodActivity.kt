package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.CueGroup
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import java.util.Locale

/**
 * Player universal, filmes e séries (06/11/2026): toca um vídeo sob demanda (filme ou episódio), com pausa, avançar,
 * voltar, escolha de áudio/legenda e "continuar de onde parou".
 *
 * Roda no processo ":player", como a TV ao vivo. Recebe só os ids (conta, filme, extensão), e o endereço com a senha é
 * montado aqui dentro: senha não viaja em Intent.
 *
 * Controle:
 *   OK / ⏯          pausa e continua             ◀ ▶ (ou ⏪ ⏩)  volta 10 s / avança 30 s (segurado acelera)
 *   ▼                áudio, legenda, velocidade e proporção da imagem      VOLTAR  sai (a posição fica salva)
 */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
class VodActivity : Activity() {
    companion object {
        private const val EXTRA_FONTE = "fonte"
        private const val EXTRA_ID = "id"
        private const val EXTRA_EXT = "ext"
        private const val EXTRA_TITULO = "titulo"
        private const val EXTRA_TIPO = "tipo"
        private const val EXTRA_URL = "url"
        // De quem é a escolha de áudio (Idioma): o filme, ou a SÉRIE inteira (o próximo episódio segue a mesma escolha).
        private const val EXTRA_IDIOMA = "idioma"
        // O que procurar no OpenSubtitles (LegendaExterna): nome limpo, ano, e temporada/episódio nas séries.
        private const val EXTRA_LEG_NOME = "leg_nome"
        private const val EXTRA_LEG_ANO = "leg_ano"
        private const val EXTRA_LEG_TEMP = "leg_temp"
        private const val EXTRA_LEG_EP = "leg_ep"
        private val ATRASOS = longArrayOf(-3000, -2000, -1500, -1000, -500, 0, 500, 1000, 1500, 2000, 3000)

        /** Põe no Intent o que a busca de legenda precisa (sem nada, a busca usa o título da tela). */
        fun comBusca(i: Intent, b: LegendaExterna.Busca?): Intent = if (b == null) i else i.putExtra(EXTRA_LEG_NOME, b.nome)
            .putExtra(EXTRA_LEG_ANO, b.ano ?: 0).putExtra(EXTRA_LEG_TEMP, b.temporada ?: 0).putExtra(EXTRA_LEG_EP, b.episodio ?: 0)
        const val TIPO_FILME = "filme"
        const val TIPO_EPISODIO = "episodio"
        const val RESULTADO_TERMINOU = "terminou"
        /** Alguma tecla foi apertada durante o vídeo (ideia 19: conta dos episódios seguidos sem ninguém mexer). */
        const val RESULTADO_TECLA = "tecla"
        /** "Desligar quando este acabar" estava marcado: a tela apagou e a série não segue para o próximo. */
        const val RESULTADO_DESLIGOU = "desligou"
        private const val SALVAR_MS = 15_000L
        private const val ESCONDER_MS = 4_000L

        fun abrir(ctx: Context, tipo: String, fonteId: String, id: String, extensao: String, titulo: String, doComeco: Boolean = false,
                  busca: LegendaExterna.Busca? = null) {
            ctx.startActivity(comBusca(intent(ctx, tipo, fonteId, id, extensao, titulo, doComeco), busca))
        }

        /** Mídia da rede (07/11/2026): toca um endereço da rede (http do DLNA, smb://, jellyfin://, plex://, webdav://; ver FontesMidia). */
        fun abrirUrl(ctx: Context, url: String, titulo: String) {
            ctx.startActivity(Intent(ctx, VodActivity::class.java)
                .putExtra(EXTRA_TIPO, TIPO_FILME).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITULO, titulo))
        }

        /** Endereço da rede com resultado (ideia 29: a pasta do "Na rede" emenda o próximo arquivo). */
        fun abrirUrlComResultado(act: Activity, codigo: Int, url: String, titulo: String) {
            act.startActivityForResult(Intent(act, VodActivity::class.java)
                .putExtra(EXTRA_TIPO, TIPO_FILME).putExtra(EXTRA_URL, url).putExtra(EXTRA_TITULO, titulo), codigo)
        }

        /** Para quem quer saber se o vídeo foi até o fim (séries: próximo episódio). */
        fun abrirComResultado(act: Activity, codigo: Int, tipo: String, fonteId: String, id: String, extensao: String, titulo: String,
                              doComeco: Boolean = false, chaveIdioma: String = "", busca: LegendaExterna.Busca? = null) {
            act.startActivityForResult(comBusca(intent(act, tipo, fonteId, id, extensao, titulo, doComeco).putExtra(EXTRA_IDIOMA, chaveIdioma), busca), codigo)
        }

        private fun intent(ctx: Context, tipo: String, fonteId: String, id: String, extensao: String, titulo: String, doComeco: Boolean) =
            Intent(ctx, VodActivity::class.java)
                .putExtra(EXTRA_TIPO, tipo).putExtra(EXTRA_FONTE, fonteId).putExtra(EXTRA_ID, id)
                .putExtra(EXTRA_EXT, extensao).putExtra(EXTRA_TITULO, titulo).putExtra("do_comeco", doComeco)
                // Aberto de fora de uma tela (pedido do catálogo de ações, 14/11/2026): o Android exige tarefa nova.
                .apply { if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }

        /** Chave da posição salva (a mesma que Filme.chave / Episodio.chave usam). */
        /**
         * Trailer no app do YouTube (11/11/2026). Só links que o Vod.linkTrailer montou (youtube.com/watch?v=...).
         * Sem o app do YouTube para TV, o Android escolhe quem abre o link.
         */
        fun abrirTrailer(ctx: Context, link: String) {
            if (!link.startsWith("https://www.youtube.com/watch?v=")) return
            val i = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(link)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val noApp = Intent(i).setPackage("com.google.android.youtube.tv")
            runCatching { ctx.startActivity(noApp) }.recoverCatching { ctx.startActivity(i) }
                .onFailure { android.widget.Toast.makeText(ctx, R.string.trailer_sem_app, android.widget.Toast.LENGTH_SHORT).show() }
        }

        fun chave(tipo: String, fonteId: String, id: String) = if (tipo == TIPO_EPISODIO) "$fonteId:e$id" else "$fonteId:f$id"
    }

    private val tela = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    private var url: String? = null
    private var chave = ""
    private var retomarEm = 0L
    private var visivel = false
    private var legendaDecidida = false
    // Preferência de áudio deste título (Idioma), lida do disco fora da thread da tela antes de o vídeo começar.
    @Volatile private var prefIdioma = Idioma.Pref.DUBLADO
    private var chaveIdioma = ""
    // Legenda baixada da internet para este vídeo (LegendaBaixar), se houver, e o atraso aplicado nela.
    private var legendaArquivo: java.io.File? = null
    private var atrasoLegenda = 0L
    private lateinit var legenda: TextView
    // Jellyfin, Emby ou Plex: a quem contar onde parou (é assim que o "Continuar assistindo" deles anda).
    private var relato: FontesMidia.Relato? = null
    private var avisouInicio = false
    // Cabeçalhos extras do pedido (WebDAV: a senha vai no cabeçalho, nunca no endereço).
    private var cabecalhos: Map<String, String> = emptyMap()
    private lateinit var capaAudio: TextView
    // Ideia 19: tecla apertada neste vídeo, e "desligar quando este acabar" (vale só para este vídeo, nada fica salvo).
    private var apertouTecla = false
    private var desligarAoFim = false
    // Ideia 20: novas tentativas depois de erro de rede (zera quando o vídeo volta a tocar).
    private var tentativas = 0
    private var tamLegendaSp = FimDoVideo.LEGENDA_PADRAO   // ideia 21

    private val papel = Estilo.TEXTO       // cores do sistema de design (Estilo)
    private val suave = Estilo.TEXTO_2
    private val coral = 0xFFFF5A4E.toInt()

    private lateinit var video: SurfaceView
    private lateinit var barra: LinearLayout
    private lateinit var titulo: TextView
    private lateinit var tempo: TextView
    private lateinit var progresso: ProgressBar
    private lateinit var aviso: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val tipo = intent.getStringExtra(EXTRA_TIPO) ?: TIPO_FILME
        val fonte = intent.getStringExtra(EXTRA_FONTE).orEmpty()
        val id = intent.getStringExtra(EXTRA_ID).orEmpty()
        val ext = intent.getStringExtra(EXTRA_EXT).orEmpty()
        // Endereço da rede (ver FontesMidia: nenhum leva senha nem token). A posição salva usa o próprio endereço como chave.
        val direto = intent.getStringExtra(EXTRA_URL)?.takeIf { FontesMidia.aceito(it) }
        chave = if (direto != null) "rede:${direto.hashCode()}" else chave(tipo, fonte, id)
        chaveIdioma = intent.getStringExtra(EXTRA_IDIOMA)?.takeIf { it.isNotEmpty() } ?: chave

        val raiz = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        video = SurfaceView(this)
        modoImagem = PlayerImagem.modo(this)
        raiz.addView(video, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
        raiz.addView(montarBarra(intent.getStringExtra(EXTRA_TITULO).orEmpty()),
            FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(32), 0, dp(32), dp(26)) })
        aviso = TextView(this).apply {
            textSize = 18f; setTextColor(papel); gravity = Gravity.CENTER; background = vidro(16)
            setPadding(dp(24), dp(16), dp(24), dp(16)); text = getString(R.string.player_carregando_canal)
        }
        raiz.addView(aviso, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        // Legenda (11/11/2026): sem o media3-ui ninguém desenhava as falas, e a legenda escolhida no ▼ não aparecia.
        // Texto branco com contorno escuro, embaixo e centrado; acima da barra quando ela aparece.
        legenda = TextView(this).apply {
            textSize = FimDoVideo.LEGENDA_PADRAO.toFloat(); setTextColor(Color.WHITE); gravity = Gravity.CENTER; visibility = View.GONE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setShadowLayer(8f, 0f, 2f, Color.BLACK)
            setPadding(dp(18), dp(6), dp(18), dp(6))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(0x8C000000.toInt()) }
        }
        raiz.addView(legenda, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { setMargins(dp(96), 0, dp(96), dp(56)) })
        // Música não tem imagem: sem isto a tela ficava toda preta. Aparece só quando o arquivo não tem faixa de vídeo.
        capaAudio = TextView(this).apply {
            textSize = 30f; setTextColor(papel); gravity = Gravity.CENTER; visibility = View.GONE
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text = "♪\n" + intent.getStringExtra(EXTRA_TITULO).orEmpty()
        }
        raiz.addView(capaAudio, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(64), 0, dp(64), dp(80)) })
        setContentView(raiz)

        // O endereço (com a senha) e a posição salva saem do disco: fora da thread da tela.
        Fundo.rodar {
            // O endereço com o token (ou o cabeçalho com a senha) só nasce aqui, na hora de tocar.
            val tocavel = direto?.let { FontesMidia.resolver(this, it) }
            val endereco = tocavel?.url ?: if (direto != null) null else PlayerArmazem.fontes(this).firstOrNull { it.id == fonte }?.xtream?.let { conta ->
                if (tipo == TIPO_EPISODIO) Series.urlEpisodio(conta, id, ext) else Vod.urlFilme(conta, Filme(id, fonte, "", extensao = ext))
            }
            prefIdioma = IdiomaPlayer.prefs(this).para(chaveIdioma)
            val leg = LegendaBaixar.guardada(this, chave)
            val atraso = getSharedPreferences("player", MODE_PRIVATE).getLong("atraso:$chave", 0L)
            // Ideia 21: tamanho da legenda escolhido no ▼ (vale para todos os vídeos).
            val tamLeg = FimDoVideo.tamanhoLegenda(getSharedPreferences("player", MODE_PRIVATE).getInt("legenda_sp", FimDoVideo.LEGENDA_PADRAO))
            val pos = if (intent.getBooleanExtra("do_comeco", false)) 0L else Vod.inicio(PlayerArmazem.posicoes(this)[chave])
            tela.post {
                if (isDestroyed) return@post
                if (endereco == null) { mostrarAviso(getString(R.string.vod_sem_conta)); return@post }
                url = endereco; retomarEm = pos; legendaArquivo = leg; atrasoLegenda = atraso
                tamLegendaSp = tamLeg; legenda.textSize = tamLeg.toFloat()
                relato = tocavel?.relato; cabecalhos = tocavel?.cabecalhos.orEmpty()
                tocar()
            }
        }
    }

    override fun onStart() { super.onStart(); visivel = true; if (url != null && player == null) tocar() }

    override fun onStop() {
        avanco.cancelar()
        super.onStop()
        visivel = false
        salvar()
        player?.let { avisar(FontesMidia.Momento.PAROU, it.currentPosition) }
        avisouInicio = false
        player?.let { retomarEm = it.currentPosition; it.release() }
        player = null
    }

    override fun onDestroy() { tela.removeCallbacksAndMessages(null); super.onDestroy() }

    // ---------------------------------------------------------------- vídeo

    private fun tocar() {
        val u = url ?: return
        if (!visivel) return
        val http0 = DefaultHttpDataSource.Factory().setUserAgent("TvLauncher-Player/1.0")
            .setAllowCrossProtocolRedirects(cabecalhos.isEmpty())   // com senha no cabeçalho, não seguir para http (a senha iria em claro)
            .setConnectTimeoutMs(10_000).setReadTimeoutMs(20_000).setDefaultRequestProperties(cabecalhos)
        // DefaultDataSource: http continua com a fábrica acima, e file:// (a legenda baixada, no disco da TV) também abre.
        val http = DefaultDataSource.Factory(this, http0)
        val p = ExoPlayer.Builder(this).setMediaSourceFactory(DefaultMediaSourceFactory(Smb.Fabrica(this, http)))
            .setSeekBackIncrementMs(10_000).setSeekForwardIncrementMs(30_000).build()
        p.setVideoSurfaceView(video)
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
        // Dublado: já pede o áudio em português. Original: o áudio e a legenda são escolhidos quando as faixas chegam
        // (onTracksChanged + IdiomaPlayer.aplicar). Nos dois casos, a legenda em português só liga se o áudio não for português.
        p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
            .setPreferredAudioLanguage(if (prefIdioma == Idioma.Pref.DUBLADO) "pt" else null).build()
        legendaDecidida = false   // player novo (voltou de outra tela): as faixas são escolhidas de novo
        p.addListener(ouvinte)
        p.setMediaItem(item(u), retomarEm)
        p.prepare()
        p.playWhenReady = true
        player = p
        tela.removeCallbacks(salvarDepois); tela.postDelayed(salvarDepois, SALVAR_MS)
        mostrarBarra()
    }

    /** O vídeo e, se houver, a legenda baixada junto (o Media3 a trata como mais uma faixa de legenda). */
    private fun item(u: String): MediaItem {
        val b = MediaItem.Builder().setUri(u)
        legendaArquivo?.let { f ->
            b.setSubtitleConfigurations(listOf(MediaItem.SubtitleConfiguration.Builder(android.net.Uri.fromFile(f))
                .setMimeType(MimeTypes.APPLICATION_SUBRIP).setLanguage("pt-BR").setId(IdiomaPlayer.ID_EXTERNA)
                .setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()))
        }
        return b.build()
    }

    /** Troca a legenda com o vídeo andando: refaz o item no mesmo ponto (o Media3 não aceita faixa nova no meio). */
    private fun recarregarComLegenda() {
        val p = player ?: return
        val u = url ?: return
        val pos = p.currentPosition
        legendaDecidida = false
        p.setMediaItem(item(u), pos)
        p.prepare()
    }

    private val ouvinte = object : Player.Listener {
        override fun onCues(cues: CueGroup) {
            val t = cues.cues.mapNotNull { it.text }.joinToString("\n")
            legenda.text = t
            legenda.visibility = if (t.isBlank()) View.GONE else View.VISIBLE
        }
        override fun onPlaybackStateChanged(estado: Int) {
            when (estado) {
                Player.STATE_READY -> {
                    esconderAviso()
                    tentativas = 0
                    if (!avisouInicio) { avisouInicio = true; avisar(FontesMidia.Momento.COMECOU, player?.currentPosition ?: 0) }
                }
                Player.STATE_BUFFERING -> mostrarAviso(getString(R.string.player_carregando_canal))
                Player.STATE_ENDED -> {
                    salvar(terminou = true)
                    // Posição = duração: o servidor marca como visto e tira do "Continuar assistindo".
                    player?.let { p -> avisar(FontesMidia.Momento.PAROU, p.duration.takeIf { it > 0 } ?: p.currentPosition) }
                    avisouInicio = false   // o fim já foi avisado; o onStop não repete
                    // A tela de séries usa isto para decidir o próximo episódio (FimDoVideo.depois).
                    setResult(RESULT_OK, Intent().putExtra(RESULTADO_TERMINOU, true).putExtra(RESULTADO_TECLA, apertouTecla)
                        .putExtra(RESULTADO_DESLIGOU, desligarAoFim))
                    // Ideia 19: "desligar quando este acabar". Este processo (:player) não tem o serviço de acessibilidade;
                    // o apagarTela cai no lockNow do Device Owner, que só apaga a tela (nada se perde).
                    if (desligarAoFim) Desligamento.apagarTela(applicationContext)
                    finish()
                }
                else -> {}
            }
            atualizarBarra()
        }
        override fun onIsPlayingChanged(tocando: Boolean) { Gatilhos.avisarDoPlayer(this@VodActivity, tocando); atualizarBarra(); if (!tocando) mostrarBarra(fixa = true) else mostrarBarra() }
        override fun onPlayerError(erro: PlaybackException) {
            // Ideia 20: erro de rede tenta de novo no mesmo ponto (2, 4 e 8 s), como a TV ao vivo; o resto mostra a mensagem.
            val p = player ?: return
            val espera = FimDoVideo.esperaAntes(tentativas + 1, FimDoVideo.erroDeRede(erro.errorCode))
            if (espera == null) { mostrarAviso(getString(R.string.vod_erro)); return }
            tentativas++
            mostrarAviso(getString(R.string.vod_tentando, tentativas, FimDoVideo.TENTATIVAS))
            val pos = p.currentPosition
            tela.postDelayed({
                if (player !== p || !visivel) return@postDelayed
                p.setMediaItem(item(url ?: return@postDelayed), pos)
                p.prepare()
            }, espera)
        }
        override fun onVideoSizeChanged(t: androidx.media3.common.VideoSize) {
            tamanhoVideo = t
            PlayerImagem.aplicar(video, modoImagem, t.width, t.height, t.pixelWidthHeightRatio)
        }

        override fun onTracksChanged(faixas: Tracks) {
            if (faixas.groups.isNotEmpty()) {
                val soAudio = faixas.groups.none { it.type == C.TRACK_TYPE_VIDEO }
                capaAudio.visibility = if (soAudio) View.VISIBLE else View.GONE
            }
            if (legendaDecidida || faixas.groups.none { it.type == C.TRACK_TYPE_AUDIO }) return
            legendaDecidida = true
            val p = player ?: return
            IdiomaPlayer.aplicar(faixas, p.trackSelectionParameters, prefIdioma)?.let { p.trackSelectionParameters = it }
        }
    }

    private val salvarDepois: Runnable = object : Runnable {
        override fun run() { salvar(); tela.postDelayed(this, SALVAR_MS) }
    }

    /** Grava onde parou. Terminou = posição igual à duração (sai do "continuar assistindo"). */
    private fun salvar(terminou: Boolean = false) {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        val ms = if (terminou) dur else p.currentPosition
        if (ms <= 0 && !terminou) return
        if (!terminou) avisar(FontesMidia.Momento.TOCANDO, ms, pausado = !p.isPlaying)
        val agora = System.currentTimeMillis()
        Fundo.rodar {
            val antes = PlayerArmazem.posicoes(this)
            PlayerArmazem.definirPosicoes(this, Vod.registrar(antes, chave, Posicao(ms, dur, agora)))
        }
    }

    /** Conta ao servidor (Jellyfin, Emby, Plex), se o vídeo é de lá, onde está. Fora da thread da tela; erro é ignorado. */
    private fun avisar(momento: FontesMidia.Momento, ms: Long, pausado: Boolean = false) {
        if (momento == FontesMidia.Momento.PAROU && !avisouInicio) return
        val dur = player?.duration?.takeIf { it != C.TIME_UNSET && it > 0 } ?: 0L
        FontesMidia.informar(this, relato, momento, ms, dur, pausado)
    }

    // ---------------------------------------------------------------- controle

    private val avanco = AvancoSegurado()   // ◀ ▶ segurados: posição escolhida enquanto a tecla está apertada

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val p = player
        apertouTecla = true   // ideia 19: alguém está mexendo, então não é hora de perguntar "ainda está assistindo?"
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_SPACE -> {
                if (event.repeatCount == 0) p?.let { if (it.isPlaying) it.pause() else it.play() }
                return true
            }
            KeyEvent.KEYCODE_MEDIA_PLAY -> { p?.play(); return true }
            KeyEvent.KEYCODE_MEDIA_PAUSE -> { p?.pause(); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { pular(p, -10_000L, event); return true }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { pular(p, +30_000L, event); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_INFO -> { mostrarBarra(); return true }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_CAPTIONS -> { if (event.repeatCount == 0) escolherFaixas(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    /** Toque: pula o passo na hora. Segurado: a barra anda acelerando aos poucos e o vídeo pula ao soltar (AvancoSegurado). */
    private fun pular(p: ExoPlayer?, passo: Long, event: KeyEvent) {
        p ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET } ?: Long.MAX_VALUE
        val ir = avanco.apertou(p.currentPosition, dur, passo, event.repeatCount, event.eventTime, event.downTime)
        if (ir >= 0) p.seekTo(ir)
        mostrarBarra()
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                val ir = avanco.soltou()
                if (ir >= 0) { player?.seekTo(ir); mostrarBarra() }
                return true
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    /** Áudio e legenda: lista as faixas que o arquivo tem (dublado, legendado, etc.). */
    private fun escolherFaixas() {
        val p = player ?: return
        data class Opcao(val rotulo: String, val marcada: Boolean = false, val aplicar: () -> Unit)
        val opcoes = ArrayList<Opcao>()
        val semLegenda = getString(R.string.vod_sem_legenda)
        for (grupo in p.currentTracks.groups) {
            val tipo = grupo.type
            if (tipo != C.TRACK_TYPE_AUDIO && tipo != C.TRACK_TYPE_TEXT) continue
            for (i in 0 until grupo.length) {
                if (!grupo.isTrackSupported(i)) continue
                val f = grupo.getTrackFormat(i)
                val idioma = f.language?.let { Locale.forLanguageTag(it).getDisplayLanguage(Locale.getDefault()).ifEmpty { it } } ?: getString(R.string.vod_faixa)
                val nome = listOfNotNull(idioma, f.label?.takeIf { it.isNotBlank() && it != idioma }).joinToString(" · ")
                val prefixo = getString(if (tipo == C.TRACK_TYPE_AUDIO) R.string.vod_audio else R.string.vod_legenda)
                val g = grupo.mediaTrackGroup; val indice = i
                opcoes += Opcao("$prefixo: $nome", grupo.isTrackSelected(i)) {
                    // Trocar o áudio na mão vira a escolha deste filme (ou da série toda): da próxima vez já abre assim.
                    if (tipo == C.TRACK_TYPE_AUDIO) Idioma.prefDaEscolha(IdiomaPlayer.faixa(f))?.let { pref ->
                        prefIdioma = pref
                        Fundo.rodar { IdiomaPlayer.lembrar(this, chaveIdioma, pref) }
                    }
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(tipo, false)
                        .setOverrideForType(TrackSelectionOverride(g, indice)).build()
                }
            }
        }
        // Passada na TV (12/11): sem legenda ligada, nada da lista aparecia marcado na parte da legenda.
        val comLegenda = legendaArquivo != null || p.currentTracks.groups.any { it.type == C.TRACK_TYPE_TEXT && it.isSelected }
        opcoes += Opcao(semLegenda, marcada = !comLegenda) {
            p.trackSelectionParameters = p.trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true).build()
        }
        // Legenda da internet (OpenSubtitles, com a conta do dono) e, se já tem uma baixada, o ajuste de atraso.
        opcoes += Opcao(getString(R.string.legenda_buscar)) { buscarLegenda() }
        if (legendaArquivo != null) opcoes += Opcao(getString(R.string.legenda_atraso_opcao, rotuloAtraso(atrasoLegenda))) { escolherAtraso() }
        // 11/11/2026 (visto no 1 Play): velocidade e proporção da imagem, no mesmo menu do ▼ para não criar outra tecla
        opcoes += Opcao(getString(R.string.velocidade_opcao, PlayerImagem.rotuloVelocidade(p.playbackParameters.speed))) { escolherVelocidade() }
        opcoes += Opcao(getString(R.string.imagem_opcao, PlayerImagem.rotulo(this, modoImagem))) { escolherImagem() }
        // Ideias 21 e 19: tamanho da legenda (vale para todos os vídeos) e desligar a TV quando este vídeo acabar.
        opcoes += Opcao(getString(R.string.vod_legenda_tamanho_opcao, rotuloTamanho(tamLegendaSp))) { escolherTamanhoLegenda() }
        opcoes += Opcao(getString(R.string.vod_desligar_ao_fim), marcada = desligarAoFim) {
            desligarAoFim = !desligarAoFim
            avisoBreve(getString(if (desligarAoFim) R.string.vod_desligar_ao_fim_ligado else R.string.vod_desligar_ao_fim_desligado))
        }
        Estilo.lista(this, getString(R.string.vod_faixas), opcoes.map { it.rotulo },
            opcoes.indices.filter { opcoes[it].marcada }) { i -> opcoes[i].aplicar() }
    }

    private fun buscaLegenda(): LegendaExterna.Busca {
        val nome = intent.getStringExtra(EXTRA_LEG_NOME)?.takeIf { it.isNotBlank() }
            ?: LegendaExterna.nomeDeBusca(intent.getStringExtra(EXTRA_TITULO).orEmpty().substringBefore(" · "))
        fun n(k: String) = intent.getIntExtra(k, 0).takeIf { it > 0 }
        return LegendaExterna.Busca(nome, n(EXTRA_LEG_ANO), n(EXTRA_LEG_TEMP), n(EXTRA_LEG_EP))
    }

    private val esconderAvisoDepois = Runnable { esconderAviso() }

    private fun avisoBreve(t: String) {
        mostrarAviso(t); tela.removeCallbacks(esconderAvisoDepois); tela.postDelayed(esconderAvisoDepois, 4_000)
    }

    /** Procura no OpenSubtitles e mostra a lista (a pessoa escolhe: cada download gasta do limite diário da conta). */
    private fun buscarLegenda() {
        val b = buscaLegenda()
        mostrarAviso(getString(R.string.legenda_procurando))
        Fundo.rodar {
            val r = LegendaBaixar.buscar(this, b)
            tela.post {
                if (isDestroyed) return@post
                esconderAviso()
                r.onFailure { avisoBreve(it.message ?: getString(R.string.legenda_erro)) }
                r.onSuccess { lista ->
                    if (lista.isEmpty()) { avisoBreve(getString(R.string.legenda_nenhuma)); return@onSuccess }
                    val rotulos = lista.map { c ->
                        listOfNotNull(c.idioma.uppercase(), c.nome, "↓ ${c.baixadas}", if (c.surdos) getString(R.string.legenda_surdos) else null)
                            .joinToString("  ·  ")
                    }
                    Estilo.lista(this, getString(R.string.legenda_escolher, b.nome), rotulos) { i -> baixarLegenda(lista[i]) }
                }
            }
        }
    }

    private fun baixarLegenda(c: LegendaExterna.Candidata) {
        mostrarAviso(getString(R.string.legenda_baixando))
        val k = chave
        Fundo.rodar {
            val r = LegendaBaixar.baixar(this, k, c)
            getSharedPreferences("player", MODE_PRIVATE).edit().remove("atraso:$k").apply()
            tela.post {
                if (isDestroyed) return@post
                r.onFailure { avisoBreve(it.message ?: getString(R.string.legenda_erro)) }
                r.onSuccess { f -> legendaArquivo = f; atrasoLegenda = 0; avisoBreve(getString(R.string.legenda_pronta)); recarregarComLegenda() }
            }
        }
    }

    private fun rotuloAtraso(ms: Long): String = when {
        ms == 0L -> getString(R.string.legenda_atraso_zero)
        else -> (if (ms > 0) "+" else "−") + "%.1f s".format(Locale.getDefault(), kotlin.math.abs(ms) / 1000.0)
    }

    /** "Atraso" positivo: a fala aparece depois (para legenda adiantada em relação à voz). */
    private fun escolherAtraso() {
        val atual = ATRASOS.indexOfFirst { it == atrasoLegenda }
        Estilo.lista(this, getString(R.string.legenda_atraso_titulo), ATRASOS.map { rotuloAtraso(it) }, listOf(atual)) { i ->
            val ms = ATRASOS[i]; val k = chave
            Fundo.rodar {
                val f = LegendaBaixar.ajustar(this, k, ms)
                getSharedPreferences("player", MODE_PRIVATE).edit().putLong("atraso:$k", ms).apply()
                tela.post { if (!isDestroyed && f != null) { legendaArquivo = f; atrasoLegenda = ms; recarregarComLegenda() } }
            }
        }
    }

    private fun escolherVelocidade() {
        val p = player ?: return
        val v = PlayerImagem.VELOCIDADES
        Estilo.lista(this, getString(R.string.velocidade_titulo), v.map { PlayerImagem.rotuloVelocidade(it) },
            listOf(v.indexOfFirst { it == p.playbackParameters.speed })) { i -> p.setPlaybackSpeed(v[i]) }
    }

    private fun rotuloTamanho(sp: Int): String {
        val i = FimDoVideo.TAMANHOS_LEGENDA.indexOf(sp).takeIf { it >= 0 } ?: 1
        return resources.getStringArray(R.array.vod_legenda_tamanhos)[i]
    }

    private fun escolherTamanhoLegenda() {
        val t = FimDoVideo.TAMANHOS_LEGENDA
        val atual = t.indexOf(tamLegendaSp)
        Estilo.lista(this, getString(R.string.vod_legenda_tamanho_titulo), resources.getStringArray(R.array.vod_legenda_tamanhos).toList(),
            listOf(atual)) { i ->
            tamLegendaSp = t[i]; legenda.textSize = t[i].toFloat()
            getSharedPreferences("player", MODE_PRIVATE).edit().putInt("legenda_sp", t[i]).apply()
        }
    }

    private fun escolherImagem() = PlayerImagem.escolher(this, modoImagem) { m ->
        modoImagem = m
        tamanhoVideo?.let { t -> PlayerImagem.aplicar(video, modoImagem, t.width, t.height, t.pixelWidthHeightRatio) }
    }

    private var modoImagem = PlayerImagem.Modo.AJUSTAR
    private var tamanhoVideo: androidx.media3.common.VideoSize? = null

    // ---------------------------------------------------------------- barra de baixo

    private fun montarBarra(t: String): View {
        barra = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; background = vidro(18); setPadding(dp(20), dp(16), dp(20), dp(14))
        }
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        titulo = TextView(this).apply {
            text = t; textSize = 20f; typeface = Fontes.manrope(500); setTextColor(papel)
            maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        linha.addView(titulo, LinearLayout.LayoutParams(0, -2, 1f))
        tempo = TextView(this).apply { textSize = 14f; setTextColor(suave) }
        linha.addView(tempo)
        barra.addView(linha)
        progresso = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; progressTintList = ColorStateList.valueOf(coral)
            progressBackgroundTintList = ColorStateList.valueOf(Estilo.VIDRO_BORDA); secondaryProgressTintList = ColorStateList.valueOf(0x66FFFFFF)
        }
        barra.addView(progresso, LinearLayout.LayoutParams(-1, dp(5)).apply { topMargin = dp(12) })
        barra.addView(TextView(this).apply { text = getString(R.string.vod_teclas); textSize = 11.5f; setTextColor(suave) },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        return barra
    }

    private val esconderBarra = Runnable { if (player?.isPlaying == true) { barra.visibility = View.GONE; posicionarLegenda() } }

    /**
     * A legenda fica a 56 dp do fundo; a barra ocupa ~130 dp. Sem isto, com a barra na tela (pausa, ◀ ▶) a fala ficava
     * por cima da barra (o comentário do onCreate prometia "acima da barra", mas nada a subia).
     */
    private fun posicionarLegenda() {
        if (!::legenda.isInitialized) return
        if (barra.visibility != View.VISIBLE) { legenda.translationY = 0f; return }
        barra.post {
            if (barra.visibility != View.VISIBLE) return@post
            // topo da barra (altura + margem de 26 dp) mais uma folga de 12 dp, menos onde a legenda já está (56 dp)
            legenda.translationY = -(barra.height + dp(26) + dp(12) - dp(56)).coerceAtLeast(0).toFloat()
        }
    }
    private val tique: Runnable = object : Runnable {
        override fun run() { atualizarBarra(); if (barra.visibility == View.VISIBLE) tela.postDelayed(this, 1_000) }
    }

    private fun mostrarBarra(fixa: Boolean = false) {
        barra.visibility = View.VISIBLE
        posicionarLegenda()
        atualizarBarra()
        tela.removeCallbacks(tique); tela.postDelayed(tique, 1_000)
        tela.removeCallbacks(esconderBarra)
        if (!fixa) tela.postDelayed(esconderBarra, ESCONDER_MS)
    }

    private fun atualizarBarra() {
        val p = player ?: return
        val dur = p.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        val pos = avanco.alvo.takeIf { it >= 0 } ?: p.currentPosition   // segurando ◀ ▶: mostra para onde vai
        val pausa = if (p.isPlaying) "" else "⏸  "
        tempo.text = pausa + (dur?.let { "${relogio(pos)} / ${relogio(it)}   −${relogio(it - pos)}" } ?: relogio(pos))
        progresso.progress = dur?.let { (pos * 1000 / it).toInt() } ?: 0
        progresso.secondaryProgress = dur?.let { (p.bufferedPosition * 1000 / it).toInt() } ?: 0
    }

    private fun relogio(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun mostrarAviso(t: String) { aviso.text = t; aviso.visibility = View.VISIBLE }
    private fun esconderAviso() { aviso.visibility = View.GONE }

    private fun vidro(raio: Int) = GradientDrawable().apply {
        cornerRadius = dp(raio).toFloat(); setColor(0xD90B0F17.toInt()); setStroke(dp(1), Estilo.SUPERFICIE_ALTA)
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
