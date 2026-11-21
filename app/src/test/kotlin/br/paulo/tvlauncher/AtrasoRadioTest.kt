package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.AtrasoRadio
import br.paulo.tvlauncher.listen.Radios
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Voltar 30 s na rádio: o anel de bytes, a taxa e para onde voltar. */
class AtrasoRadioTest {
    @Test fun anelDaVolta() {
        val a = AtrasoRadio.Anel(10)
        a.escrever(ByteArray(7) { it.toByte() }, 0, 7)          // 0..6
        a.escrever(ByteArray(6) { (it + 7).toByte() }, 0, 6)    // 7..12: sobrescreve 0..2
        assertEquals(13L, a.escrito); assertEquals(3L, a.maisVelho)
        val b = ByteArray(10)
        // Pede da posição 0 (já sobrescrita): lê do mais velho (3), até o fim do anel físico.
        val n = a.ler(0, b, 0, 10, 0)
        assertEquals(3L + n, a.ondeFicou)
        assertArrayEquals(ByteArray(n) { (it + 3).toByte() }, b.copyOf(n))
        val m = a.ler(a.ondeFicou, b, 0, 10, 0)
        assertEquals(13L, a.ondeFicou); assertEquals(10 - n, m)
        assertEquals(0, a.ler(13, b, 0, 10, 1))   // nada novo ainda
        a.fechar()
        assertEquals(-1, a.ler(13, b, 0, 10, 1))
    }

    @Test fun contas() {
        assertEquals(16_000L, AtrasoRadio.taxa(128, 0))
        assertEquals(12_000L, AtrasoRadio.taxa(0, 12_000))
        assertEquals(16_000L, AtrasoRadio.taxa(0, 0))
        assertEquals(1_000_000L - 480_000, AtrasoRadio.destino(1_000_000, 30, 16_000, 0))
        assertEquals(900_000L, AtrasoRadio.destino(1_000_000, 30, 16_000, 900_000))   // não passa do mais velho
        assertEquals(30, AtrasoRadio.atrasoS(520_000, 1_000_000, 16_000))
    }

    @Test fun formatos() {
        assertTrue(AtrasoRadio.suporta(Radios.Radio("a", "A", "http://x/live", codec = "MP3")))
        assertTrue(AtrasoRadio.suporta(Radios.Radio("a", "A", "http://x/live", codec = "AAC+")))
        assertFalse(AtrasoRadio.suporta(Radios.Radio("a", "A", "http://x/live.m3u8")))
        assertFalse(AtrasoRadio.suporta(Radios.Radio("a", "A", "http://x/live", codec = "OGG")))
    }
}
