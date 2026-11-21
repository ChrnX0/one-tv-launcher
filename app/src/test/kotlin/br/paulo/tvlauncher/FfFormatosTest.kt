package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.FfFormatos
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfFormatosTest {
    private fun flv(cab: ByteArray, codec: Int): ByteArray {
        val b = ByteArray(64)
        b[0] = 'F'.code.toByte(); b[1] = 'L'.code.toByte(); b[2] = 'V'.code.toByte()
        System.arraycopy(cab, 0, b, 5, 4)
        val p = 9 + 4
        b[p] = 9                      // bloco de vídeo
        b[p + 11] = (0x10 or codec).toByte()
        return b
    }

    @Test fun flvAntigoVaiProFfmpegEH264Nao() {
        assertTrue(FfFormatos.flvVideoAntigo(flv(byteArrayOf(0, 0, 0, 9), 2)))    // Sorenson
        assertFalse(FfFormatos.flvVideoAntigo(flv(byteArrayOf(0, 0, 0, 9), 7)))   // H.264: Media3
    }

    @Test fun cabecalhoComByteAltoNaoEstoura() {
        // Antes: deslocamento negativo e ArrayIndexOutOfBounds dentro do sniff.
        assertFalse(FfFormatos.flvVideoAntigo(flv(byteArrayOf(0x80.toByte(), 0, 0, 9), 2)))
        assertFalse(FfFormatos.flvVideoAntigo(flv(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xF0.toByte()), 2)))
        assertFalse(FfFormatos.flvVideoAntigo(flv(byteArrayOf(0, 0, 0, 3), 2)))
    }
}
