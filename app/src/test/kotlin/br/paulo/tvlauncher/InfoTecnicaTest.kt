package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.FfCodecs
import br.paulo.tvlauncher.player.InfoTecnica
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InfoTecnicaTest {
    private val r = InfoTecnica.Rotulos("Vídeo", "Áudio", "Perdidos", "Taxa", "Reserva", "Atraso", "Diálogo", "chip", "FFmpeg", "qps", "mono", "estéreo")

    @Test fun nomes() {
        assertEquals("H.264", InfoTecnica.formato("video/avc", null))
        assertEquals("WMV2", InfoTecnica.formato(FfCodecs.MIME_VIDEO_FF, "wmv2"))
        assertEquals("x-novo", InfoTecnica.formato("video/x-novo", null))
        assertEquals("FFmpeg", InfoTecnica.quem("ffmpeg-8.0-ac3", "chip", "FFmpeg"))
        assertEquals("chip (amlogic.avc.decoder)", InfoTecnica.quem("OMX.amlogic.avc.decoder", "chip", "FFmpeg"))
        // Sala, 17/11/2026: o Vorbis de software do sistema aparecia como "chip da TV".
        assertEquals("Android (vorbis.decoder)", InfoTecnica.quem("android.vorbis.decoder", "chip", "FFmpeg"))
        assertEquals("Android (aac.decoder)", InfoTecnica.quem("c2.android.aac.decoder", "chip", "FFmpeg"))
        assertEquals("estéreo", InfoTecnica.canais(2, r))
        assertEquals("5.1", InfoTecnica.canais(6, r))
    }

    @Test fun linhas() {
        val e = InfoTecnica.Estado("video/avc", null, 1920, 1080, 23.976f, "c2.amlogic.avc.decoder", 3,
            "audio/ac3", null, 6, 48_000, "ffmpeg-8-ac3", 6_000_000, 12_300, "8.0", 150, true)
        val l = InfoTecnica.linhas(e, r, java.util.Locale.ROOT)
        assertTrue(l[0], l[0].startsWith("Vídeo: H.264 1920×1080 24.0 qps · chip"))
        assertEquals("Perdidos: 3", l[1])
        assertEquals("Áudio: Dolby Digital 5.1 48 kHz · FFmpeg", l[2])
        assertTrue(l.contains("Taxa: 6.0 Mb/s"))
        assertTrue(l.contains("Reserva: 12.3 s"))
        assertTrue(l.contains("Atraso: +150 ms"))
        assertTrue(l.contains("Diálogo"))
        // Em português, vírgula decimal (Sala, 17/11/2026: "Reserva: 13.3 s").
        val pt = InfoTecnica.linhas(e.copy(taxa = 44_100), r, java.util.Locale.forLanguageTag("pt-BR"))
        assertTrue(pt.toString(), pt.contains("Reserva: 12,3 s"))
        assertTrue(pt.toString(), pt.any { it.contains("44,1 kHz") })
    }
}
