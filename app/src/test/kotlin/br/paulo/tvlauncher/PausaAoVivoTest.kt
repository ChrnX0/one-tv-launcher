package br.paulo.tvlauncher

import br.paulo.tvlauncher.PausaAoVivo.Decisao.CONTINUAR_ATRASADO
import br.paulo.tvlauncher.PausaAoVivo.Decisao.VOLTAR_AO_VIVO
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PausaAoVivoTest {
    private val mb = 1024L * 1024

    @Test fun tetoDeBytesPeloHeap() {
        assertEquals(72 * mb, PausaAoVivo.tetoBytes(192 * mb))    // heapgrowthlimit comum de TV de 2 GB
        assertEquals(96 * mb, PausaAoVivo.tetoBytes(256 * mb))    // 3/8 de 256 = 96: bate no teto
        assertEquals(96 * mb, PausaAoVivo.tetoBytes(512 * mb))    // heap grande não passa de 96 MB
        assertEquals(16 * mb, PausaAoVivo.tetoBytes(20 * mb))     // heap minúsculo: piso
    }

    @Test fun tetoDeTempoPeloBitrate() {
        assertEquals(96_000L, PausaAoVivo.tetoMs(8_000_000, 96_000_000))           // 8 Mbps = 1 MB/s
        assertEquals(PausaAoVivo.TETO_MS, PausaAoVivo.tetoMs(3_000_000, 96 * mb))  // canal leve: o teto é 2 min
        assertEquals(PausaAoVivo.tetoMs(8_000_000, 72 * mb), PausaAoVivo.tetoMs(0, 72 * mb))   // desconhecido = 8 Mbps
        assertEquals(PausaAoVivo.tetoMs(8_000_000, 72 * mb), PausaAoVivo.tetoMs(-1, 72 * mb))
        val a8 = PausaAoVivo.tetoMs(8_000_000, 72 * mb)
        assertTrue("72 MiB a 8 Mbps ≈ 75 s, deu $a8", a8 in 75_000L..76_000L)
    }

    @Test fun bitrateDoFormato() {
        assertEquals(5_128_000L, PausaAoVivo.bitrate(5_000_000, 128_000))
        assertEquals(5_000_000L, PausaAoVivo.bitrate(5_000_000, -1))   // Format.NO_VALUE no áudio
        assertEquals(0L, PausaAoVivo.bitrate(-1, 128_000))             // sem o vídeo, não dá para estimar
    }

    @Test fun estouraDescontandoOQueJaEstavaNaMemoria() {
        assertEquals(110_000L, PausaAoVivo.estouraEm(10_000, 120_000))
        assertEquals(0L, PausaAoVivo.estouraEm(130_000, 120_000))
    }

    @Test fun continuaAtrasadoQuandoCoube() {
        assertEquals(CONTINUAR_ATRASADO, PausaAoVivo.decidir(60_000, 8_000, 68_000, 120_000))
        // HLS chega em pedaços: o buffer pode estar até 15 s atrás do esperado
        assertEquals(CONTINUAR_ATRASADO, PausaAoVivo.decidir(60_000, 8_000, 54_000, 120_000))
    }

    @Test fun voltaAoVivoQuandoPassouDoTeto() {
        assertEquals(VOLTAR_AO_VIVO, PausaAoVivo.decidir(115_000, 8_000, 123_000, 120_000))
        assertEquals(VOLTAR_AO_VIVO, PausaAoVivo.decidir(10 * 60_000, 8_000, 0, 120_000))   // o player já soltou a memória
    }

    @Test fun voltaAoVivoQuandoOBufferNaoAcompanhou() {
        // rede caiu ou bateu no teto de bytes antes da hora: o que falta não está na memória
        assertEquals(VOLTAR_AO_VIVO, PausaAoVivo.decidir(60_000, 8_000, 30_000, 120_000))
        assertEquals(VOLTAR_AO_VIVO, PausaAoVivo.decidir(-5, 8_000, 30_000, 120_000))
    }

    @Test fun textoDoAtraso() {
        val min = "ATRASADO %1\$d MIN"
        val seg = "ATRASADO %1\$d S"
        assertEquals("", PausaAoVivo.textoAtraso(500, min, seg))
        assertEquals("ATRASADO 5 S", PausaAoVivo.textoAtraso(1_000, min, seg))
        assertEquals("ATRASADO 40 S", PausaAoVivo.textoAtraso(37_000, min, seg))
        assertEquals("ATRASADO 55 S", PausaAoVivo.textoAtraso(59_000, min, seg))
        assertEquals("ATRASADO 1 MIN", PausaAoVivo.textoAtraso(60_000, min, seg))
        assertEquals("ATRASADO 1 MIN", PausaAoVivo.textoAtraso(119_000, min, seg))
        assertEquals("ATRASADO 2 MIN", PausaAoVivo.textoAtraso(120_000, min, seg))
    }
}
