package br.paulo.tvlauncher

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
        assertEquals("estéreo", InfoTecnica.canais(2, r))
        assertEquals("5.1", InfoTecnica.canais(6, r))
    }

    @Test fun linhas() {
        val e = InfoTecnica.Estado("video/avc", null, 1920, 1080, 23.976f, "c2.amlogic.avc.decoder", 3,
            "audio/ac3", null, 6, 48_000, "ffmpeg-8-ac3", 6_000_000, 12_300, "8.0", 150, true)
        val l = InfoTecnica.linhas(e, r)
        assertTrue(l[0], l[0].startsWith("Vídeo: H.264 1920×1080 24.0 qps · chip"))
        assertEquals("Perdidos: 3", l[1])
        assertEquals("Áudio: Dolby Digital 5.1 48 kHz · FFmpeg", l[2])
        assertTrue(l.contains("Taxa: 6.0 Mb/s"))
        assertTrue(l.contains("Reserva: 12.3 s"))
        assertTrue(l.contains("Atraso: +150 ms"))
        assertTrue(l.contains("Diálogo"))
    }
}
