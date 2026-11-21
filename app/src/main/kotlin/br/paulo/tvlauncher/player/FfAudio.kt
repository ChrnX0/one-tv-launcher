package br.paulo.tvlauncher.player

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.SimpleDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DecoderAudioRenderer
import br.paulo.tvlauncher.FfNativo
import br.paulo.tvlauncher.home.abrir

/** Falha do FFmpeg (áudio ou vídeo). */
@OptIn(UnstableApi::class)
class FfErro(msg: String, causa: Throwable? = null) : DecoderException(msg, causa)

/**
 * Decodificador de áudio pelo FFmpeg (15/11/2026, nativo/cola/audio.cc). Uma instância por faixa tocando; o Media3 o
 * chama numa thread própria. Sai PCM 16 bits (ou float, se a saída pedir) com os canais do arquivo.
 */
@OptIn(UnstableApi::class)
class FfAudioDecodificador(formato: Format, tamanhoEntrada: Int, private val pcmFloat: Boolean) :
    SimpleDecoder<DecoderInputBuffer, SimpleDecoderOutputBuffer, FfErro>(
        Array(BUFFERS) { DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT) },
        @Suppress("UNCHECKED_CAST") (arrayOfNulls<SimpleDecoderOutputBuffer>(BUFFERS) as Array<SimpleDecoderOutputBuffer>)) {

    companion object { private const val BUFFERS = 16 }

    /**
     * Falha na criação: o SimpleDecoder já deu start na thread dele no construtor; sem este release a thread e os
     * buffers ficavam vivos para sempre (um vazamento a cada nova tentativa do player).
     */
    private fun falhar(motivo: String): Nothing { super.release(); throw FfErro(motivo) }

    private val codec = FfCodecs.audio(formato.sampleMimeType, formato.codecs) ?: falhar("sem codec para ${formato.sampleMimeType}")
    private val extra = FfCodecs.extra(formato.sampleMimeType, formato.initializationData)
    private val taxaDeclarada = formato.sampleRate
    private val canaisDeclarados = formato.channelCount
    private val bitrate = maxOf(formato.averageBitrate, 0)
    private val contener = FfCodecs.parametrosAudio(formato.sampleMimeType, formato.initializationData)   // bloco, bits
    private var ctx = abrir()
    @Volatile var canais = 0; private set
    @Volatile var taxa = 0; private set

    init { setInitialInputBufferSize(tamanhoEntrada) }

    private fun abrir(): Long {
        if (!FfNativo.disponivel) falhar("FFmpeg não carregou")
        val c = FfNativo.audioAbrir(codec, extra, maxOf(taxaDeclarada, 0), maxOf(canaisDeclarados, 0), pcmFloat,
            contener[0], bitrate, contener[1])
        if (c == 0L) falhar("FFmpeg não abriu $codec")
        return c
    }

    val encoding: Int get() = if (pcmFloat) C.ENCODING_PCM_FLOAT else C.ENCODING_PCM_16BIT

    override fun getName() = "ffmpeg-${FfNativo.versaoFfmpeg}-$codec"

    override fun createInputBuffer() = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT, 64)   // 64 = folga que o FFmpeg lê além do fim
    override fun createOutputBuffer() = SimpleDecoderOutputBuffer { releaseOutputBuffer(it) }
    override fun createUnexpectedDecodeException(error: Throwable) = FfErro("erro inesperado no áudio", error)

    override fun decode(entrada: DecoderInputBuffer, saida: SimpleDecoderOutputBuffer, reset: Boolean): FfErro? {
        if (reset) FfNativo.audioLimpar(ctx)
        val dados = Util.castNonNull(entrada.data)
        val n = FfNativo.audioDecodificar(ctx, dados, dados.limit())
        when {
            n == FfNativo.ERRO_OUTRO -> return FfErro("FFmpeg falhou no áudio ($codec)")
            n <= 0 -> { saida.shouldBeSkipped = true; return null }   // pacote estragado ou ainda sem som: segue
        }
        val buf = saida.init(entrada.timeUs, n)
        FfNativo.audioCopiar(ctx, buf)
        buf.position(0); buf.limit(n)
        if (canais == 0) { canais = FfNativo.audioCanais(ctx); taxa = FfNativo.audioTaxa(ctx) }
        return null
    }

    override fun release() {
        super.release()
        if (ctx != 0L) { FfNativo.audioFechar(ctx); ctx = 0 }
    }
}

/**
 * Renderizador de áudio pelo FFmpeg. Entra DEPOIS do áudio do chip da TV (RenderizadoresPlayer): só pega o que a TV
 * recusa (Dolby, DTS, TrueHD, MP2 e os formatos antigos), ou tudo quando o PCM é obrigatório (efeitos de som ligados).
 */
@OptIn(UnstableApi::class)
class FfAudioRenderer(handler: Handler?, ouvinte: AudioRendererEventListener?, saida: AudioSink) :
    DecoderAudioRenderer<FfAudioDecodificador>(handler, ouvinte, saida) {

    override fun getName() = "FfAudioRenderer"

    override fun supportsFormatInternal(formato: Format): Int {
        val mime = formato.sampleMimeType ?: return C.FORMAT_UNSUPPORTED_TYPE
        if (!MimeTypes.isAudio(mime) && mime != FfCodecs.MIME_AUDIO_FF) return C.FORMAT_UNSUPPORTED_TYPE
        val codec = FfCodecs.audio(mime, formato.codecs)
        if (codec == null || !FfNativo.tem(codec)) return C.FORMAT_UNSUPPORTED_SUBTYPE
        if (!saidaAceita(formato, C.ENCODING_PCM_16BIT) && !saidaAceita(formato, C.ENCODING_PCM_FLOAT)) return C.FORMAT_UNSUPPORTED_SUBTYPE
        if (formato.cryptoType != C.CRYPTO_TYPE_NONE) return C.FORMAT_UNSUPPORTED_DRM
        return C.FORMAT_HANDLED
    }

    override fun supportsMixedMimeTypeAdaptation() = RendererCapabilities.ADAPTIVE_NOT_SEAMLESS

    override fun createDecoder(formato: Format, cripto: CryptoConfig?): FfAudioDecodificador {
        val entrada = if (formato.maxInputSize != Format.NO_VALUE) formato.maxInputSize else 960 * 6   // ~1 quadro Dolby
        // Float só quando a saída não aceita 16 bits (mais de 2 canais em algumas TVs); 16 bits é o que o resto usa.
        return FfAudioDecodificador(formato, entrada, !saidaAceita(formato, C.ENCODING_PCM_16BIT))
    }

    override fun getOutputFormat(d: FfAudioDecodificador): Format = Format.Builder()
        .setSampleMimeType(MimeTypes.AUDIO_RAW).setChannelCount(d.canais).setSampleRate(d.taxa).setPcmEncoding(d.encoding).build()

    private fun saidaAceita(f: Format, enc: Int): Boolean {
        val canais = if (f.channelCount > 0) minOf(f.channelCount, 8) else 2
        val taxa = if (f.sampleRate > 0) f.sampleRate else 48_000
        return sinkSupportsFormat(Util.getPcmFormat(enc, canais, taxa))
    }
}
