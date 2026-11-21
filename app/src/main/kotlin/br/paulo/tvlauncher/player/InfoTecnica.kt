package br.paulo.tvlauncher.player

import java.util.Locale

/**
 * Ideia 8 (15/11/2026): informações técnicas por cima do vídeo, para entender uma travada ("o vídeo está no chip ou
 * no FFmpeg?", "quantos quadros perdeu?"). Conta pura, com teste (InfoTecnicaTest); a tela só mostra o texto.
 */
object InfoTecnica {
    const val PREF = "info_tecnica"   // prefs "player"

    data class Estado(
        val videoMime: String?, val videoCodecs: String?, val largura: Int, val altura: Int, val qps: Float,
        val decodVideo: String?, val perdidos: Int,
        val audioMime: String?, val audioCodecs: String?, val canais: Int, val taxa: Int, val decodAudio: String?,
        val bitrate: Int, val bufferMs: Long, val ffmpeg: String,
        val atrasoAudioMs: Int, val dialogoAlto: Boolean,
    )

    /** Nome curto do formato para gente: "H.264", "Dolby Digital", "DTS"... (cai no tipo cru se não conhecer). */
    fun formato(mime: String?, codecs: String?): String {
        val m = mime ?: return "—"
        if (m == FfCodecs.MIME_VIDEO_FF || m == FfCodecs.MIME_AUDIO_FF) return (codecs ?: "?").uppercase(Locale.ROOT)
        return NOMES[m] ?: m.substringAfter('/')
    }

    private val NOMES = mapOf(
        "video/avc" to "H.264", "video/hevc" to "HEVC", "video/av01" to "AV1", "video/x-vnd.on2.vp9" to "VP9",
        "video/x-vnd.on2.vp8" to "VP8", "video/mpeg2" to "MPEG-2", "video/mp4v-es" to "MPEG-4", "video/dolby-vision" to "Dolby Vision",
        "audio/mp4a-latm" to "AAC", "audio/ac3" to "Dolby Digital", "audio/eac3" to "Dolby Digital Plus",
        "audio/eac3-joc" to "Dolby Atmos (DD+)", "audio/true-hd" to "Dolby TrueHD", "audio/vnd.dts" to "DTS",
        "audio/vnd.dts.hd" to "DTS-HD", "audio/mpeg" to "MP3", "audio/mpeg-L2" to "MP2", "audio/opus" to "Opus",
        "audio/vorbis" to "Vorbis", "audio/flac" to "FLAC", "audio/raw" to "PCM",
    )

    /**
     * Quem está decodificando: "chip da TV" (MediaCodec do fabricante), "Android" (decodificador de software do sistema:
     * c2.android.*, OMX.google.*, android.*; 17/11/2026 o Vorbis aparecia como "chip da TV"), "FFmpeg" (nosso).
     */
    fun quem(decodificador: String?, rotuloChip: String, rotuloFfmpeg: String): String = when {
        decodificador == null -> "—"
        decodificador.startsWith("ffmpeg") || decodificador.startsWith("Ff") -> rotuloFfmpeg
        decodificador.startsWith("c2.android.") || decodificador.startsWith("OMX.google.") || decodificador.startsWith("android.") ->
            "Android (${decodificador.removePrefix("OMX.google.").removePrefix("c2.android.").removePrefix("android.").take(28)})"
        else -> "$rotuloChip (${decodificador.removePrefix("OMX.").removePrefix("c2.").take(28)})"
    }

    fun canais(n: Int, r: Rotulos): String = when (n) {
        1 -> r.mono; 2 -> r.estereo; 6 -> "5.1"; 8 -> "7.1"; 0 -> "—"; else -> "$n ch"
    }

    /** As linhas do quadro, já prontas. Rótulos traduzidos chegam de fora ([r]). */
    fun linhas(e: Estado, r: Rotulos, local: Locale = Locale.getDefault()): List<String> {
        // Números no formato da TV (17/11/2026: "Reserva: 13.3 s" com a TV em português).
        val l = ArrayList<String>()
        val qps = if (e.qps > 0) String.format(local, " %.3g %s", e.qps, r.qps) else ""
        if (e.videoMime != null) {
            l += "${r.video}: ${formato(e.videoMime, e.videoCodecs)} ${e.largura}×${e.altura}$qps · ${quem(e.decodVideo, r.chip, r.ffmpeg)}"
            l += "${r.perdidos}: ${e.perdidos}"
        }
        if (e.audioMime != null) {
            val taxa = if (e.taxa > 0) " ${String.format(local, "%.1f", e.taxa / 1000.0)} kHz".replace(Regex("[.,]0 kHz"), " kHz") else ""
            l += "${r.audio}: ${formato(e.audioMime, e.audioCodecs)} ${canais(e.canais, r)}$taxa · ${quem(e.decodAudio, r.chip, r.ffmpeg)}"
        }
        if (e.bitrate > 0) l += "${r.taxaBits}: ${String.format(local, "%.1f", e.bitrate / 1_000_000.0)} Mb/s"
        l += "${r.buffer}: ${String.format(local, "%.1f", e.bufferMs / 1000.0)} s"
        if (e.atrasoAudioMs != 0) l += "${r.atraso}: ${if (e.atrasoAudioMs > 0) "+" else ""}${e.atrasoAudioMs} ms"
        if (e.dialogoAlto) l += r.dialogo
        l += "FFmpeg ${e.ffmpeg}"
        return l
    }

    data class Rotulos(val video: String, val audio: String, val perdidos: String, val taxaBits: String, val buffer: String,
                       val atraso: String, val dialogo: String, val chip: String, val ffmpeg: String,
                       val qps: String, val mono: String, val estereo: String)
}
