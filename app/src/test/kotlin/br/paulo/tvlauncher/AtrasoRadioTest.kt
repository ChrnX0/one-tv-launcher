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

    @Test fun fitaNoArquivo() {
        // A mesma volta do anel, gravada num arquivo (o pendrive): cresce sem pré-alocar e some ao soltar.
        val f = java.io.File.createTempFile("fita", ".bin")
        val a = AtrasoRadio.Anel(AtrasoRadio.Arquivo(f, 10))
        a.escrever(ByteArray(13) { it.toByte() }, 0, 13)
        assertEquals(10L, f.length()); assertEquals(3L, a.maisVelho)
        val b = ByteArray(10)
        val n = a.ler(0, b, 0, 10, 0)
        assertArrayEquals(ByteArray(n) { (it + 3).toByte() }, b.copyOf(n))
        a.soltar()
        assertFalse(f.exists())
    }

    @Test fun tamanhoNoPendrive() {
        assertEquals(57_600_000L, AtrasoRadio.capacidadePendrive(16_000, 1L shl 40))              // 1 h a 128 kbps
        assertEquals(AtrasoRadio.PENDRIVE_MAX, AtrasoRadio.capacidadePendrive(80_000, 1L shl 40))  // 640 kbps: teto
        assertEquals(AtrasoRadio.PENDRIVE_MIN, AtrasoRadio.capacidadePendrive(1_000, 1L shl 40))   // piso
        assertEquals(20_000_000L, AtrasoRadio.capacidadePendrive(16_000, 40_000_000))              // metade do livre
        assertEquals(0L, AtrasoRadio.capacidadePendrive(16_000, 10_000_000))                       // não cabe: RAM
    }

    @Test fun icySeparaTituloDoAudio() {
        val titulos = ArrayList<String>()
        val icy = AtrasoRadio.Icy(4) { titulos.add(it) }
        val meta = "StreamTitle='Caetano - Sozinho';".toByteArray()
        val bloco = ByteArray(32).also { meta.copyInto(it) }   // 2 × 16 bytes, completado com zeros
        val fluxo = byteArrayOf(1, 2, 3, 4) + byteArrayOf(2) + bloco + byteArrayOf(5, 6, 7, 8) + byteArrayOf(0) + byteArrayOf(9)
        val audio = java.io.ByteArrayOutputStream()
        // Em pedaços de 3 bytes: o bloco de metadados chega partido, como na rede.
        var i = 0
        while (i < fluxo.size) { val k = minOf(3, fluxo.size - i); val pedaco = fluxo.copyOfRange(i, i + k); icy.filtrar(pedaco, k) { b, o, n -> audio.write(b, o, n) }; i += k }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9), audio.toByteArray())
        assertEquals(listOf("Caetano - Sozinho"), titulos)
        assertEquals("Ação", AtrasoRadio.Icy.titulo("StreamTitle='Ação';".toByteArray(Charsets.ISO_8859_1)))
    }

    @Test fun formatos() {
        assertTrue(AtrasoRadio.suporta(Radios.Radio("a", "A", "http://x/live", codec = "MP3")))
        assertTrue(AtrasoRadio.suporta(Radios.Radio("a", "A", "http://x/live", codec = "AAC+")))
        assertFalse(AtrasoRadio.suporta(Radios.Radio("a", "A", "http://x/live.m3u8")))
        assertFalse(AtrasoRadio.suporta(Radios.Radio("a", "A", "http://x/live", codec = "OGG")))
    }
}
