package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.AssLogica
import br.paulo.tvlauncher.player.FfCodecs
import br.paulo.tvlauncher.player.FfExtratores
import br.paulo.tvlauncher.player.FfFormatos
import br.paulo.tvlauncher.player.FimDoVideo
import br.paulo.tvlauncher.player.LeitoresLegendaSemAss
import br.paulo.tvlauncher.player.MixDialogo
import br.paulo.tvlauncher.player.RenderizadoresPlayer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contas puras do player sem NextLib (15/11/2026): codecs, ASS, diálogo, formatos antigos, atraso. */
class PlayerProprioTest {
    @Test fun codecsPorTipo() {
        assertEquals("ac3", FfCodecs.audio("audio/ac3"))
        assertEquals("truehd", FfCodecs.audio("audio/true-hd"))
        assertEquals("dca", FfCodecs.audio("audio/vnd.dts"))
        assertNull(FfCodecs.audio("audio/desconhecido"))
        assertEquals("wmav2", FfCodecs.audio(FfCodecs.MIME_AUDIO_FF, "wmav2"))
        assertNull(FfCodecs.audio(FfCodecs.MIME_AUDIO_FF, " "))
        assertEquals("libdav1d", FfCodecs.video("video/av01"))
        assertEquals("libdav1d", FfCodecs.video(FfCodecs.MIME_VIDEO_FF, "av1"))
        assertEquals("rv40", FfCodecs.video(FfCodecs.MIME_VIDEO_FF, "rv40"))
    }

    @Test fun extraAnexoBEVorbis() {
        val sps = byteArrayOf(0x67, 1, 2); val pps = byteArrayOf(0, 0, 0, 1, 0x68, 3)
        assertArrayEquals(byteArrayOf(0, 0, 0, 1, 0x67, 1, 2, 0, 0, 0, 1, 0x68, 3), FfCodecs.extra("video/avc", listOf(sps, pps)))
        val v = FfCodecs.extra("audio/vorbis", listOf(ByteArray(3) { 7 }, ByteArray(2) { 9 }))!!
        assertArrayEquals(byteArrayOf(0, 3, 7, 7, 7, 0, 0, 0, 2, 9, 9), v)
        assertNull(FfCodecs.extra("audio/vorbis", listOf(ByteArray(3))))
        assertNull(FfCodecs.extra("audio/ac3", emptyList()))
    }

    @Test fun flacStreaminfo() {
        val info = ByteArray(34) { it.toByte() }
        assertArrayEquals(info, FfCodecs.flacInfo(info))
        assertArrayEquals(info, FfCodecs.flacInfo("fLaC".toByteArray() + byteArrayOf(0, 0, 0, 34) + info))
        assertNull(FfCodecs.flacInfo(ByteArray(10)))
    }

    @Test fun falaAss() {
        val a = "Dialogue: 0:00:01.50,0:00:04.00,7,0,Default,,0,0,0,,Oi".toByteArray()
        val f = AssLogica.fala(a)!!
        assertEquals(2_500L, f.duracaoMs)
        assertEquals("7,0,Default,,0,0,0,,Oi", String(f.bloco))
        assertNull(AssLogica.fala("Comment: qualquer coisa que não é fala".toByteArray()))
        assertEquals(3_723_450L, AssLogica.tempo("1:02:03:45"))
        assertNull(AssLogica.tempo("1:02"))
    }

    @Test fun arquivoAssEReducao() {
        assertTrue(AssLogica.ehArquivo(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "\r\n[script info]\n".toByteArray()))
        assertFalse(AssLogica.ehArquivo("1\n00:00:01,000 --> 00:00:02,000\nOi".toByteArray()))
        assertEquals(1280 to 720, AssLogica.tamanho(1920, 1080))
        assertEquals(960 to 540, AssLogica.tamanho(960, 540))
        assertEquals(1280 to 720, AssLogica.tamanho(0, 0))
    }

    @Test fun dialogoSo51E71() {
        assertNull(MixDialogo.coeficientes(2))
        val m = MixDialogo.coeficientes(6)!!
        // Centro igual nas duas saídas e mais forte que a frente; LFE (3) fica de fora.
        assertEquals(m[0][2], m[1][2], 1e-6f)
        assertTrue(m[0][2] > m[0][0])
        assertEquals(0f, m[0][3], 0f); assertEquals(0f, m[1][3], 0f)
        // Frente + centro no máximo cabem em 1.
        assertEquals(1f, m[0][0] + m[0][2], 1e-5f)
        val (l, r) = MixDialogo.misturar(floatArrayOf(1f, 1f, 1f, 1f, 1f, 1f), m)
        assertEquals(1f, l, 0f); assertEquals(1f, r, 0f)   // corte no limite
        assertEquals(8, MixDialogo.coeficientes(8)!![0].size)
    }

    @Test fun formatosAntigos() {
        assertTrue(FfFormatos.aceita("avi:100", ByteArray(0)))
        assertFalse(FfFormatos.aceita("avi:10", ByteArray(0)))       // chute pela extensão
        assertFalse(FfFormatos.aceita("matroska,webm:100", ByteArray(0)))
        assertFalse(FfFormatos.aceita(null, ByteArray(0)))
        fun flv(codec: Int) = byteArrayOf('F'.code.toByte(), 'L'.code.toByte(), 'V'.code.toByte(), 1, 5, 0, 0, 0, 9, 0, 0, 0, 0,
            9, 0, 0, 1, 0, 0, 0, 0, 0, 0, 0, codec.toByte())
        assertTrue(FfFormatos.aceita("flv:100", flv(2)))   // Sorenson: FFmpeg
        assertFalse(FfFormatos.aceita("flv:100", flv(7)))  // H.264: chip
    }

    @Test fun atrasoDoAudio() {
        assertEquals(50, RenderizadoresPlayer.proximoAtraso(0, true))
        assertEquals(500, RenderizadoresPlayer.proximoAtraso(500, true))
        assertEquals(-500, RenderizadoresPlayer.proximoAtraso(-500, false))
        assertEquals(21, RenderizadoresPlayer.ATRASOS.size)
        assertEquals("atraso_audio_ms:tv", RenderizadoresPlayer.chaveAtraso(null))
        assertEquals("atraso_audio_ms:tv", RenderizadoresPlayer.chaveAtraso("  "))
        assertEquals("atraso_audio_ms:bt:JBL Flip", RenderizadoresPlayer.chaveAtraso(" JBL Flip "))
    }

    @Test fun escalaDaLegendaAss() {
        assertEquals(1.0, FimDoVideo.escalaAss(FimDoVideo.LEGENDA_PADRAO), 1e-9)
        assertTrue(FimDoVideo.escalaAss(46) > 1.0)
        assertTrue(FimDoVideo.escalaAss(24) < 1.0)
    }
}

/** O embrulho dos leitores passa os ajustes adiante (bug visto na Sala: a ASS virava texto simples). */
class FfExtratoresTest {
    @Test fun repassaLeitorDeLegendas() {
        var recebido: androidx.media3.extractor.text.SubtitleParser.Factory? = null
        var transcodificar: Boolean? = null
        val base = object : androidx.media3.extractor.ExtractorsFactory {
            override fun createExtractors() = emptyArray<androidx.media3.extractor.Extractor>()
            override fun setSubtitleParserFactory(f: androidx.media3.extractor.text.SubtitleParser.Factory) = apply { recebido = f }
            override fun experimentalSetTextTrackTranscodingEnabled(l: Boolean) = apply { transcodificar = l }
        }
        val e = FfExtratores(base, androidx.media3.datasource.DataSource.Factory { throw IllegalStateException() })
        val leitor = LeitoresLegendaSemAss()
        e.setSubtitleParserFactory(leitor).experimentalSetTextTrackTranscodingEnabled(true)
        assertEquals(leitor, recebido)
        assertEquals(true, transcodificar)
    }
}

/** WMA/cook pedem o bloco do contêiner (visto na Sala: "FFmpeg não abriu wmav2"). */
class ParametrosAudioTest {
    @Test fun idaEVolta() {
        val d = listOf(ByteArray(0), FfCodecs.empacotarAudio(4459, 16))
        assertArrayEquals(intArrayOf(4459, 16), FfCodecs.parametrosAudio(FfCodecs.MIME_AUDIO_FF, d))
        assertNull(FfCodecs.extra(FfCodecs.MIME_AUDIO_FF, d))                  // extradata vazio = nenhum
        assertArrayEquals(intArrayOf(0, 0), FfCodecs.parametrosAudio("audio/ac3", d))
        assertArrayEquals(intArrayOf(0, 0), FfCodecs.parametrosAudio(FfCodecs.MIME_AUDIO_FF, listOf(ByteArray(3))))
    }
}
