package br.paulo.tvlauncher.player

/**
 * Qual decodificador do FFmpeg serve para cada formato que o Media3 entrega (15/11/2026). Conta pura, com teste
 * (FfCodecsTest). Os nomes à direita são os do FFmpeg (lista do que foi compilado em nativo/compilar.sh).
 *
 * Faixas que vêm do nosso leitor de formatos antigos (FfExtrator: AVI, WMV, RMVB...) usam um tipo próprio
 * ([MIME_AUDIO_FF]/[MIME_VIDEO_FF]) com o nome do FFmpeg no campo `codecs`: o Media3 não conhece esses formatos e
 * só os nossos renderizadores os aceitam.
 */
object FfCodecs {
    const val MIME_AUDIO_FF = "audio/x-onetv-ffmpeg"
    const val MIME_VIDEO_FF = "video/x-onetv-ffmpeg"

    private val AUDIO = mapOf(
        "audio/ac3" to "ac3",
        "audio/eac3" to "eac3",
        "audio/eac3-joc" to "eac3",
        "audio/true-hd" to "truehd",
        "audio/vnd.dts" to "dca",
        "audio/vnd.dts.hd" to "dca",
        "audio/vnd.dts.hd;profile=lbr" to "dca",
        "audio/mpeg-L1" to "mp1",
        "audio/mpeg-L2" to "mp2",
        "audio/mpeg" to "mp3float",
        "audio/mp4a-latm" to "aac",
        "audio/vorbis" to "vorbis",
        "audio/opus" to "opus",
        "audio/flac" to "flac",
        "audio/alac" to "alac",
        "audio/3gpp" to "amrnb",
        "audio/amr-wb" to "amrwb",
        "audio/g711-mlaw" to "pcm_mulaw",
        "audio/g711-alaw" to "pcm_alaw",
    )

    private val VIDEO = mapOf(
        "video/avc" to "h264",
        "video/hevc" to "hevc",
        "video/mpeg2" to "mpeg2video",
        "video/mpeg" to "mpeg1video",
        "video/mp4v-es" to "mpeg4",
        "video/3gpp" to "h263",
        "video/x-vnd.on2.vp8" to "vp8",
        "video/x-vnd.on2.vp9" to "vp9",
        "video/av01" to "libdav1d",
        "video/wvc1" to "vc1",
        "video/divx" to "mpeg4",
        "video/x-flv" to "flv",
        "video/mjpeg" to "mjpeg",
    )

    /** Decodificador do FFmpeg para um áudio, ou null. [codecs] só importa nas faixas do FfExtrator. */
    fun audio(mime: String?, codecs: String? = null): String? =
        if (mime == MIME_AUDIO_FF) codecs?.takeIf { it.isNotBlank() } else AUDIO[mime]

    /** Decodificador do FFmpeg para um vídeo, ou null. AV1 vai para o dav1d (mais rápido que o do FFmpeg). */
    fun video(mime: String?, codecs: String? = null): String? =
        if (mime == MIME_VIDEO_FF) codecs?.let { if (it == "av1") "libdav1d" else it }?.takeIf { it.isNotBlank() }
        else VIDEO[mime]

    /**
     * O que o Media3 espera receber como "dados de início" (extradata) de cada formato. AAC/Opus: o primeiro bloco
     * como veio. Vorbis: os dois cabeçalhos com o tamanho de cada um na frente (formato de "xiph" que o FFmpeg lê).
     * ALAC: o "magic cookie" dentro de um átomo 'alac'. FLAC: só os 34 bytes do STREAMINFO.
     * H.264/HEVC: o Media3 dá os NALs (SPS, PPS...) separados; o FFmpeg aceita tudo junto com o prefixo 00 00 00 01.
     */
    fun extra(mime: String?, dados: List<ByteArray>): ByteArray? {
        if (dados.isEmpty()) return null
        return when (mime) {
            "audio/mp4a-latm", "audio/opus" -> dados[0]
            "audio/vorbis" -> if (dados.size < 2) null else vorbis(dados[0], dados[1])
            "audio/alac" -> alac(dados[0])
            "audio/flac" -> dados.firstNotNullOfOrNull { flacInfo(it) }
            "video/avc", "video/hevc" -> anexoB(dados)
            MIME_AUDIO_FF, MIME_VIDEO_FF -> dados[0].takeIf { it.isNotEmpty() }
            else -> dados[0]
        }
    }

    /**
     * Áudio do FfExtrator: o 2º bloco de [dados] leva o que o Format do Media3 não tem campo para levar (tamanho do
     * bloco e bits por amostra do contêiner, que WMA, cook e ADPCM exigem). [empacotarAudio] faz, este lê. [bloco, bits].
     */
    fun parametrosAudio(mime: String?, dados: List<ByteArray>): IntArray {
        val p = dados.getOrNull(1)
        if (mime != MIME_AUDIO_FF || p == null || p.size != 8) return IntArray(2)
        val b = java.nio.ByteBuffer.wrap(p)
        return intArrayOf(b.int, b.int)
    }

    fun empacotarAudio(bloco: Int, bits: Int): ByteArray = java.nio.ByteBuffer.allocate(8).putInt(bloco).putInt(bits).array()

    private fun vorbis(h0: ByteArray, h1: ByteArray): ByteArray {
        val r = ByteArray(h0.size + h1.size + 6)
        r[0] = (h0.size shr 8).toByte(); r[1] = h0.size.toByte()
        h0.copyInto(r, 2)
        // [2 + h0] e [3 + h0] ficam 0: o bloco de comentário não é usado pelo decodificador.
        r[h0.size + 4] = (h1.size shr 8).toByte(); r[h0.size + 5] = h1.size.toByte()
        h1.copyInto(r, h0.size + 6)
        return r
    }

    private fun alac(cookie: ByteArray): ByteArray {
        val n = 12 + cookie.size
        val r = ByteArray(n)
        r[0] = (n ushr 24).toByte(); r[1] = (n ushr 16).toByte(); r[2] = (n ushr 8).toByte(); r[3] = n.toByte()
        "alac".toByteArray(Charsets.US_ASCII).copyInto(r, 4)
        cookie.copyInto(r, 12)
        return r
    }

    /** STREAMINFO do FLAC: com ou sem "fLaC" na frente, com ou sem o cabeçalho de bloco de 4 bytes. */
    fun flacInfo(d: ByteArray): ByteArray? {
        val ini = if (d.size >= 4 && d[0] == 'f'.code.toByte() && d[1] == 'L'.code.toByte() && d[2] == 'a'.code.toByte() && d[3] == 'C'.code.toByte()) 4 else 0
        if (d.size - ini == 34) return d.copyOfRange(ini, ini + 34)
        if (d.size >= ini + 4 + 34) {
            val tipo = d[ini].toInt() and 0x7F
            val tam = ((d[ini + 1].toInt() and 0xFF) shl 16) or ((d[ini + 2].toInt() and 0xFF) shl 8) or (d[ini + 3].toInt() and 0xFF)
            if (tipo == 0 && tam == 34) return d.copyOfRange(ini + 4, ini + 4 + 34)
        }
        return null
    }

    private val INICIO_NAL = byteArrayOf(0, 0, 0, 1)

    private fun anexoB(nals: List<ByteArray>): ByteArray {
        val saida = java.io.ByteArrayOutputStream()
        for (n in nals) {
            val temPrefixo = n.size >= 4 && n[0] == 0.toByte() && n[1] == 0.toByte() && (n[2] == 1.toByte() || (n[2] == 0.toByte() && n[3] == 1.toByte()))
            if (!temPrefixo) saida.write(INICIO_NAL)
            saida.write(n)
        }
        return saida.toByteArray()
    }
}
