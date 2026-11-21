package br.paulo.tvlauncher.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.RenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import br.paulo.tvlauncher.mcp.Tunel

/**
 * Motor único dos players (proposta C, item 10, 20/11/2026): a TV ao vivo e os filmes e séries montam o ExoPlayer do
 * mesmo jeito. Antes cada tela tinha a sua receita, e o que um ganhava (formatos antigos pelo FFmpeg, túnel com volta
 * automática) o outro não tinha. As telas continuam separadas; só a máquina por baixo é a mesma.
 */
@OptIn(UnstableApi::class)
object PlayerEngine {
    const val USER_AGENT = "TvLauncher-Player/1.0"

    /**
     * De onde o player lê: http(s) com o User-Agent e os cabeçalhos pedidos, file:// (legenda baixada) e, pela
     * FontePastas, smb://, arq:// (Na rede e nuvem) e gravando:// (o canal que está gravando).
     * Com cabeçalhos (senha de addon), não segue redirecionamento para http: a senha iria em claro.
     */
    fun data(ctx: Context, userAgent: String = USER_AGENT, headers: Map<String, String> = emptyMap(), readTimeoutMs: Int = 20_000): DataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent.ifEmpty { USER_AGENT })
            .setAllowCrossProtocolRedirects(headers.isEmpty())
            .setConnectTimeoutMs(10_000)
            .setReadTimeoutMs(readTimeoutMs)
            .setDefaultRequestProperties(headers)
        return FontePastas.Fabrica(ctx, DefaultDataSource.Factory(ctx, http))
    }

    /**
     * O player: renderizadores (EfeitosSom: FFmpeg para áudio, libass; sem eles, os do Media3), o tipo de som e o que
     * cada tela acrescentar. [audioFocus] = true: o ExoPlayer pausa quando outro app toca e abaixa quando o Assistente
     * fala. O despertador passa false (nada para disputar às 6 da manhã). 21/11/2026: rádio, podcast, despertador e a
     * matriz de amostras passaram a nascer aqui também (antes cada um tinha o seu ExoPlayer.Builder).
     */
    fun build(
        ctx: Context,
        renderers: RenderersFactory = DefaultRenderersFactory(ctx),
        contentType: Int = C.AUDIO_CONTENT_TYPE_MOVIE,
        audioFocus: Boolean = true,
        configure: ExoPlayer.Builder.() -> Unit = {},
    ): ExoPlayer = ExoPlayer.Builder(ctx, renderers).apply(configure).build().also { p ->
        p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(contentType).build(), audioFocus)
    }

    /** .m3u8 = HLS. Sem extensão conhecida, o Media3 descobre sozinho pelo conteúdo (ex.: .ts do Xtream). */
    fun isHls(url: String) = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true)

    /** O item de um endereço, com o tipo HLS quando a extensão diz. */
    fun item(url: String): MediaItem.Builder = MediaItem.Builder().setUri(url).apply { if (isHls(url)) setMimeType(MimeTypes.APPLICATION_M3U8) }
}

/**
 * Modo túnel com volta automática, igual nos dois players. O vídeo vai do decodificador direto para a tela, no tempo do
 * áudio (os decodificadores da Sala anunciam "tunneled-playback"). Se der erro de decodificador/áudio, ou se ficar
 * "carregando" com bastante coisa já baixada (visto na Sala 20/11: preso depois de pular, sem erro), desliga o túnel
 * neste aparelho (Tunel.desistir) e [reload] continua no modo comum, no mesmo ponto.
 */
@OptIn(UnstableApi::class)
class TunnelGuard(private val ctx: Context, private val handler: Handler, private val tag: String, private val reload: (ExoPlayer) -> Unit) {
    var on = false
        private set
    private var player: ExoPlayer? = null
    private var logged = false

    /** Pede o túnel (se [allowed] e se este aparelho não falhou com ele). Só vale com SurfaceView. */
    fun request(p: ExoPlayer, allowed: Boolean) {
        player = p; logged = false
        on = allowed && Tunel.permitido(ctx)
        apply(p, on)
    }

    fun onState(p: ExoPlayer, state: Int) {
        handler.removeCallbacks(watch)
        if (on && state == Player.STATE_BUFFERING) handler.postDelayed(watch, FimDoVideo.TUNEL_PRESO_MS)
        if (state == Player.STATE_READY && !logged) { logged = true; Log.i(tag, "túnel=${p.isTunnelingEnabled} (pedido=$on)") }
    }

    /** true = era o túnel, já voltou ao modo comum (a tela não mostra erro). */
    fun onError(p: ExoPlayer, code: Int, name: String): Boolean {
        if (!on || !FimDoVideo.erroDoTunel(code)) return false
        Log.w(tag, "túnel falhou ($name): volta ao modo comum")
        Tunel.desistir(ctx)
        turnOff(p, reloadNow = true)
        return true
    }

    /** Desliga o túnel neste player (ex.: a tela passou para TextureView). [reloadNow] recarrega no mesmo ponto. */
    fun turnOff(p: ExoPlayer, reloadNow: Boolean) {
        on = false
        handler.removeCallbacks(watch)
        apply(p, false)
        if (reloadNow) reload(p)
    }

    fun release() { handler.removeCallbacks(watch); player = null }

    private val watch = Runnable {
        val p = player ?: return@Runnable
        if (!on || p.playbackState != Player.STATE_BUFFERING) return@Runnable
        val ahead = p.bufferedPosition - p.currentPosition
        if (!FimDoVideo.tunelPreso(ahead)) return@Runnable
        Log.w(tag, "túnel preso carregando com ${ahead / 1000} s baixados: volta ao modo comum")
        Tunel.desistir(ctx)
        turnOff(p, reloadNow = true)
    }

    private fun apply(p: ExoPlayer, enabled: Boolean) {
        val tp = p.trackSelectionParameters as? DefaultTrackSelector.Parameters ?: return
        if (tp.tunnelingEnabled != enabled) p.trackSelectionParameters = tp.buildUpon().setTunnelingEnabled(enabled).build()
    }
}
