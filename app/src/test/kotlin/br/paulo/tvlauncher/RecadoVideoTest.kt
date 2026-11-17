package br.paulo.tvlauncher

import br.paulo.tvlauncher.RecadoVideo.Regras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Recado em vídeo (degrau 4): tipo pelo cabeçalho, limites, contagem do dia, faxina dos antigos e o cartão. */
class RecadoVideoTest {
    private fun cab(marca: String) = byteArrayOf(0, 0, 0, 0x20) + "ftyp".toByteArray() + marca.toByteArray()

    @Test fun tipoPeloCabecalho() {
        assertEquals(Regras.Tipo.MP4, Regras.tipo(cab("isom")))
        assertEquals(Regras.Tipo.MP4, Regras.tipo(cab("mp42")))
        assertEquals(Regras.Tipo.MOV, Regras.tipo(cab("qt  ")))   // iPhone
        assertEquals(Regras.Tipo.WEBM, Regras.tipo(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte(), 1, 2)))
        assertNull(Regras.tipo(cab("heic")))                       // foto HEIF também é ftyp: recusa
        assertNull(Regras.tipo("<html><body>xx".toByteArray()))
        assertNull(Regras.tipo(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0, 0, 0, 0, 0, 0, 0)))   // JPEG
        assertNull(Regras.tipo(ByteArray(3)))
    }

    @Test fun limites() {
        assertTrue(Regras.tamanhoOk(1))
        assertTrue(Regras.tamanhoOk(RecadoVideo.MAX_BYTES))
        assertFalse(Regras.tamanhoOk(RecadoVideo.MAX_BYTES + 1))
        assertFalse(Regras.tamanhoOk(0))
        assertFalse(Regras.tamanhoOk(-1))
        assertTrue(Regras.duracaoOk(30_400))
        assertTrue(Regras.duracaoOk(31_000))
        assertFalse(Regras.duracaoOk(31_001))
        assertFalse(Regras.duracaoOk(0))
        assertFalse(Regras.duracaoOk(null))
    }

    @Test fun cincoPorDia() {
        var n = 0; var dia: String? = null
        repeat(RecadoVideo.MAX_POR_DIA) { val (ok, q) = Regras.contar("2026-11-17", dia, n); assertTrue(ok); n = q; dia = "2026-11-17" }
        assertFalse(Regras.contar("2026-11-17", dia, n).first)
        assertEquals(true to 1, Regras.contar("2026-11-18", dia, n))
    }

    @Test fun faxinaApagaVencidosEDepoisOsMaisAntigos() {
        val dia = 86_400_000L
        val agora = 100 * dia
        val mb = 1024L * 1024
        val lista = listOf(
            Regras.Guardado("a", 10 * mb, agora - 40 * dia),   // venceu (30 dias)
            Regras.Guardado("b", 90 * mb, agora - 20 * dia),
            Regras.Guardado("c", 90 * mb, agora - 10 * dia),
            Regras.Guardado("d", 10 * mb, agora - 1 * dia),
        )
        // b+c+d = 190 MB; +20 novo = 210 > 200: sai o vencido e o mais antigo (b).
        assertEquals(listOf("a", "b"), Regras.apagar(lista, 20 * mb, agora))
        // Cabe: só o vencido.
        assertEquals(listOf("a"), Regras.apagar(lista, 5 * mb, agora))
        assertEquals(emptyList<String>(), Regras.apagar(emptyList(), 20 * mb, agora))
    }

    @Test fun cartaoSoNaHomeEForaDaMadrugada() {
        assertTrue(Regras.podeAbrirCartao(10, naHome = true, telaAcesa = true))
        assertFalse(Regras.podeAbrirCartao(10, naHome = false, telaAcesa = true))   // vendo filme: não interrompe
        assertFalse(Regras.podeAbrirCartao(10, naHome = true, telaAcesa = false))
        assertFalse(Regras.podeAbrirCartao(2, naHome = true, telaAcesa = true))     // madrugada
        assertFalse(Regras.podeAbrirCartao(22, naHome = true, telaAcesa = true))
        assertTrue(Regras.podeAbrirCartao(7, naHome = true, telaAcesa = true))
    }

    @Test fun nomeDeArquivoSoONosso() {
        assertTrue(Regras.nomeValido("v-20261117-031500-0a1b2c3d.mp4"))
        assertFalse(Regras.nomeValido("../../x.mp4"))
        assertFalse(Regras.nomeValido("v-20261117-031500-0a1b2c3d.mp4/../a"))
        assertFalse(Regras.nomeValido(null))
    }

    @Test fun rotasDoLink() {
        val t = "0123456789abcdef0123456789abcdef"
        assertEquals(t to "video", FamiliaConectada.Regras.rota("/familia/$t/video"))
        assertEquals(t to "estado", FamiliaConectada.Regras.rota("/familia/$t/estado"))
        assertNull(FamiliaConectada.Regras.rota("/familia/$t/apagar"))
    }
}
