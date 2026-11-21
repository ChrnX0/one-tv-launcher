package br.paulo.tvlauncher.player

import android.os.Handler
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import br.paulo.tvlauncher.FfNativo

/**
 * Vídeo por software pelo FFmpeg (15/11/2026, nativo/cola/video.cc). Último recurso: só quando o chip da TV recusa o
 * formato (AVI com DivX/Xvid, WMV, RealVideo, H.264 10 bits...). Na Sala (4 núcleos de 32 bits) dá conta de SD e 720p;
 * 1080p depende do formato.
 *
 * Uma entrada pode não dar quadro (o decodificador segura quadros B) ou dar dois; a fila fica no C++, e cada chamada
 * aqui entrega no máximo um quadro. Os quadros que sobram no fim do arquivo (uns 0,2 s) não aparecem: aceitável.
 */
@OptIn(UnstableApi::class)
class FfVideoDecodificador(formato: Format, tamanhoEntrada: Int, threads: Int) :
    SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, FfErro>(
        Array(BUFFERS) { DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT) },
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<VideoDecoderOutputBuffer>(BUFFERS) as Array<VideoDecoderOutputBuffer>)) {

    companion object { private const val BUFFERS = 8 }

    /** Falha na criação: solta a thread que o SimpleDecoder já começou (ver FfAudioDecodificador.falhar). */
    private fun falhar(motivo: String): Nothing { super.release(); throw FfErro(motivo) }

    private val codec = FfCodecs.video(formato.sampleMimeType, formato.codecs) ?: falhar("sem codec para ${formato.sampleMimeType}")
    @Volatile private var modo = C.VIDEO_OUTPUT_MODE_NONE
    private var ctx: Long

    init {
        if (!FfNativo.disponivel) falhar("FFmpeg não carregou")
        ctx = FfNativo.videoAbrir(codec, FfCodecs.extra(formato.sampleMimeType, formato.initializationData),
            maxOf(formato.width, 0), maxOf(formato.height, 0), threads)
        if (ctx == 0L) falhar("FFmpeg não abriu $codec")
        setInitialInputBufferSize(tamanhoEntrada)
    }

    fun modoSaida(m: Int) { modo = m }

    override fun getName() = "ffmpeg-${FfNativo.versaoFfmpeg}-$codec"
    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, 64)
    override fun createOutputBuffer() = VideoDecoderOutputBuffer { releaseOutputBuffer(it) }
    override fun createUnexpectedDecodeException(error: Throwable) = FfErro("erro inesperado no vídeo", error)

    override fun decode(entrada: DecoderInputBuffer, saida: VideoDecoderOutputBuffer, reset: Boolean): FfErro? {
        if (reset) FfNativo.videoLimpar(ctx)
        val dados = Util.castNonNull(entrada.data)
        val prontos = FfNativo.videoDecodificar(ctx, dados, dados.limit(), entrada.timeUs)
        if (prontos == FfNativo.ERRO_OUTRO) return FfErro("FFmpeg falhou no vídeo ($codec)")
        if (prontos <= 0) { saida.shouldBeSkipped = true; return null }
        // O quadro que sai pode ser de outro pacote (ordem de exibição ≠ ordem do arquivo): vale o tempo DELE.
        val tempo = FfNativo.videoTempo(ctx)
        saida.timeUs = tempo
        saida.shouldBeSkipped = !isAtLeastOutputStartTimeUs(tempo)
        if (saida.shouldBeSkipped || modo != C.VIDEO_OUTPUT_MODE_SURFACE_YUV) { FfNativo.videoPular(ctx); saida.shouldBeSkipped = true; return null }
        val w = FfNativo.videoLargura(ctx); val h = FfNativo.videoAltura(ctx)
        val passoY = (w + 15) and 15.inv()
        val passoUV = ((w + 1) / 2 + 15) and 15.inv()
        saida.init(tempo, modo, null)
        if (!saida.initForYuvFrame(w, h, passoY, passoUV, FfNativo.videoCores(ctx))) { FfNativo.videoPular(ctx); return FfErro("quadro grande demais") }
        if (!FfNativo.videoCopiar(ctx, saida.data!!, passoY, passoUV)) return FfErro("falha ao copiar o quadro")
        return null
    }

    fun desenhar(b: VideoDecoderOutputBuffer, surface: Surface) {
        val d = b.data ?: return
        if (!FfNativo.videoDesenhar(surface, d, b.width, b.height, b.yStride, b.uvStride)) throw FfErro("falha ao desenhar o quadro")
    }

    override fun release() {
        super.release()
        if (ctx != 0L) { FfNativo.videoFechar(ctx); ctx = 0 }
    }
}

/** Renderizador de vídeo por software. Entra depois do vídeo do chip (RenderizadoresPlayer). */
@OptIn(UnstableApi::class)
class FfVideoRenderer(juntarMs: Long, handler: Handler?, ouvinte: VideoRendererEventListener?, maxPerdidos: Int) :
    DecoderVideoRenderer(juntarMs, handler, ouvinte, maxPerdidos) {

    private var decodificador: FfVideoDecodificador? = null
    // 4 núcleos na Sala: 4 threads; em aparelho maior, até 6 (mais que isso não ajuda em 1080p).
    private val threads = Runtime.getRuntime().availableProcessors().coerceIn(1, 6)

    override fun getName() = "FfVideoRenderer"

    override fun supportsFormat(formato: Format): Int {
        val mime = formato.sampleMimeType ?: return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        if (!MimeTypes.isVideo(mime) && mime != FfCodecs.MIME_VIDEO_FF) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)
        val codec = FfCodecs.video(mime, formato.codecs)
        if (codec == null || !FfNativo.tem(codec)) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_SUBTYPE)
        if (formato.cryptoType != C.CRYPTO_TYPE_NONE) return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        return RendererCapabilities.create(C.FORMAT_HANDLED, RendererCapabilities.ADAPTIVE_SEAMLESS, RendererCapabilities.TUNNELING_NOT_SUPPORTED)
    }

    override fun createDecoder(formato: Format, cripto: CryptoConfig?): FfVideoDecodificador {
        val entrada = if (formato.maxInputSize != Format.NO_VALUE) formato.maxInputSize else 1024 * 1024
        return FfVideoDecodificador(formato, entrada, threads).also { decodificador = it }
    }

    override fun renderOutputBufferToSurface(b: VideoDecoderOutputBuffer, surface: Surface) {
        try { (decodificador ?: throw FfErro("sem decodificador")).desenhar(b, surface) } finally { b.release() }
    }

    override fun setDecoderOutputMode(modo: Int) { decodificador?.modoSaida(modo) }
}
