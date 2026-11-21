package br.paulo.tvlauncher.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.annotation.OptIn
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.MediaClock
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.video.VideoRendererEventListener
import br.paulo.tvlauncher.FfNativo

/**
 * Fábrica de renderizadores do player (15/11/2026), no lugar da NextRenderersFactory (GPL):
 *  - áudio: primeiro o chip da TV; depois o FFmpeg (Dolby, DTS, TrueHD, MP2, formatos antigos);
 *  - vídeo: primeiro o chip; depois o FFmpeg por software;
 *  - legenda: ASS/SSA pelo libass na frente; o resto (SRT, WebVTT, PGS, VobSub, DVB) com o Media3;
 *  - ideia 4: atraso de áudio ([atrasoMs], + = o som chega atrasado na caixa, então o vídeo espera).
 *
 * O atraso funciona no relógio: o vídeo segue a posição do áudio; devolvendo a posição deslocada, cada quadro sai
 * mais cedo ou mais tarde que o som, sem mexer no som (que poderia estar indo direto para a TV em Dolby).
 */
@OptIn(UnstableApi::class)
open class RenderizadoresPlayer(ctx: Context, private val atrasoMs: Int = 0, private val ass: AssAjustes? = null,
                                private val segunda: Pair<SegundaLegenda, TextOutput>? = null) :
    DefaultRenderersFactory(ctx) {

    init {
        // ON = o do chip vem primeiro e o nosso fica de reserva. Os renderizadores "de extensão" do Media3 (achados por
        // nome de classe) não existem neste app; os nossos entram pelos build* abaixo.
        setExtensionRendererMode(EXTENSION_RENDERER_MODE_ON)
        setEnableDecoderFallback(true)   // chip falhou ao abrir? tenta o próximo (o FFmpeg)
    }

    override fun buildAudioRenderers(context: Context, modo: Int, seletor: MediaCodecSelector, fallback: Boolean,
                                     saida: AudioSink, handler: Handler, ouvinte: AudioRendererEventListener, out: ArrayList<Renderer>) {
        val antes = out.size
        super.buildAudioRenderers(context, modo, seletor, fallback, saida, handler, ouvinte, out)
        if (FfNativo.disponivel) out.add(antes + 1, FfAudioRenderer(handler, ouvinte, saida))
        if (atrasoMs != 0) for (i in antes until out.size) out[i] = ComAtraso(out[i], atrasoMs * 1000L)
    }

    override fun buildVideoRenderers(context: Context, modo: Int, seletor: MediaCodecSelector, fallback: Boolean,
                                     handler: Handler, ouvinte: VideoRendererEventListener, juntarMs: Long, out: ArrayList<Renderer>) {
        val antes = out.size
        super.buildVideoRenderers(context, modo, seletor, fallback, handler, ouvinte, juntarMs, out)
        if (FfNativo.disponivel) out.add(antes + 1, FfVideoRenderer(juntarMs, handler, ouvinte, MAX_QUADROS_PERDIDOS))
    }

    override fun buildTextRenderers(context: Context, saida: TextOutput, looper: Looper, modo: Int, out: ArrayList<Renderer>) {
        // A segunda legenda (ideia 6) vem primeiro: no empate o Media3 manda a faixa escolhida para ela.
        segunda?.let { (s, o) -> out.add(s.renderizador(o, Looper.getMainLooper())) }
        if (FfNativo.disponivel) out.add(AssRenderer(context, saida, looper, ass ?: AssAjustes()))
        super.buildTextRenderers(context, saida, looper, modo, out)
    }

    /** Renderizador de áudio com o relógio deslocado (ideia 4). */
    private class ComAtraso(r: Renderer, private val atrasoUs: Long) : ForwardingRenderer(r) {
        override fun getMediaClock(): MediaClock? {
            val base = super.getMediaClock() ?: return null
            return object : MediaClock {
                override fun getPositionUs() = base.positionUs - atrasoUs
                override fun setPlaybackParameters(p: PlaybackParameters) = base.setPlaybackParameters(p)
                override fun getPlaybackParameters(): PlaybackParameters = base.playbackParameters
                override fun hasSkippedSilenceSinceLastCall() = base.hasSkippedSilenceSinceLastCall()
            }
        }
    }

    companion object {
        private const val MAX_QUADROS_PERDIDOS = 50
        const val PREF_ATRASO = "atraso_audio_ms"   // prefs "player", -500..+500 em passos de 50, um valor por saída de som
        const val ATRASO_MAX = 500
        const val ATRASO_PASSO = 50

        /**
         * Chave do atraso conforme a saída de som: cada caixa Bluetooth tem o seu atraso (é ela que costuma atrasar);
         * sem Bluetooth, vale o da TV/soundbar pelo HDMI. [bluetooth] = nome da caixa Bluetooth ligada, ou null.
         */
        fun chaveAtraso(bluetooth: String?): String =
            if (bluetooth.isNullOrBlank()) "$PREF_ATRASO:tv" else "$PREF_ATRASO:bt:" + bluetooth.trim().take(40)

        /** Valores oferecidos no menu, de -500 a +500 ms. */
        val ATRASOS: List<Int> = (-ATRASO_MAX..ATRASO_MAX step ATRASO_PASSO).toList()

        /** Próximo valor do atraso ao apertar ◀/▶ no menu (para no limite). */
        fun proximoAtraso(atual: Int, mais: Boolean): Int =
            (atual + if (mais) ATRASO_PASSO else -ATRASO_PASSO).coerceIn(-ATRASO_MAX, ATRASO_MAX)
    }
}
