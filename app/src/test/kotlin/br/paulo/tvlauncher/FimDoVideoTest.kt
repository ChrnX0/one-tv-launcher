package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.FimDoVideo
import br.paulo.tvlauncher.player.FimDoVideo.Passo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideias 19 a 22 (14/11/2026): regras puras do player de filmes e séries. */
class FimDoVideoTest {

    // 19 ------------------------------------------------------------------

    @Test fun saiuAntesDoFimNaoFazNada() {
        assertEquals(Passo.NADA, FimDoVideo.depois(terminou = false, temProximo = true, desligarAoFim = true, seguidosSemTecla = 9))
    }

    @Test fun desligarAoFimGanhaDoProximo() {
        assertEquals(Passo.DESLIGAR, FimDoVideo.depois(true, temProximo = true, desligarAoFim = true, seguidosSemTecla = 0))
        assertEquals(Passo.DESLIGAR, FimDoVideo.depois(true, temProximo = false, desligarAoFim = true, seguidosSemTecla = 0))
    }

    @Test fun ultimoEpisodioNaoSegue() {
        assertEquals(Passo.NADA, FimDoVideo.depois(true, temProximo = false, desligarAoFim = false, seguidosSemTecla = 1))
    }

    @Test fun contaAteOTerceiroEDepoisPergunta() {
        var n = 0
        val passos = (1..4).map {
            n = FimDoVideo.seguidos(n, apertouTecla = false)
            FimDoVideo.depois(true, true, false, n)
        }
        assertEquals(listOf(Passo.PROXIMO_CONTANDO, Passo.PROXIMO_CONTANDO, Passo.PERGUNTAR, Passo.PERGUNTAR), passos)
    }

    @Test fun teclaZeraAConta() {
        assertEquals(0, FimDoVideo.seguidos(2, apertouTecla = true))
        assertEquals(3, FimDoVideo.seguidos(2, apertouTecla = false))
    }

    // 20 ------------------------------------------------------------------

    @Test fun novaTentativaSoParaRedeComEsperaDobrando() {
        assertEquals(listOf(2_000L, 4_000L, 8_000L), (1..3).map { FimDoVideo.esperaAntes(it, deRede = true) })
        assertNull(FimDoVideo.esperaAntes(4, deRede = true))
        assertNull(FimDoVideo.esperaAntes(1, deRede = false))
        assertNull(FimDoVideo.esperaAntes(0, deRede = true))
    }

    @Test fun errosDeRedeDoMedia3() {
        assertTrue(FimDoVideo.erroDeRede(2001))   // ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        assertTrue(FimDoVideo.erroDeRede(2002))   // ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        assertTrue(FimDoVideo.erroDeRede(2004))   // ERROR_CODE_IO_BAD_HTTP_STATUS
        assertTrue(FimDoVideo.erroDeRede(1003))   // ERROR_CODE_TIMEOUT
        assertFalse(FimDoVideo.erroDeRede(3001))  // ERROR_CODE_PARSING_CONTAINER_MALFORMED
        assertFalse(FimDoVideo.erroDeRede(4003))  // ERROR_CODE_DECODING_FAILED
    }

    // 21 ------------------------------------------------------------------

    @Test fun tamanhoDaLegendaValidaOSalvo() {
        assertEquals(38, FimDoVideo.tamanhoLegenda(38))
        assertEquals(FimDoVideo.LEGENDA_PADRAO, FimDoVideo.tamanhoLegenda(0))
        assertEquals(FimDoVideo.LEGENDA_PADRAO, FimDoVideo.tamanhoLegenda(31))
        assertTrue(FimDoVideo.LEGENDA_PADRAO in FimDoVideo.TAMANHOS_LEGENDA)
    }

    // 22 ------------------------------------------------------------------

    @Test fun nomeDaFichaSemCaracterPerigoso() {
        assertEquals("conta1.f123.json", FimDoVideo.nomeFicha("conta1", serie = false, id = "123"))
        assertEquals("c_d.s______x.json", FimDoVideo.nomeFicha("c/d", serie = true, id = "../../x"))
        assertFalse(FimDoVideo.nomeFicha("a", true, "../../etc/passwd").contains("/"))
    }

    @Test fun podarApagaAsMaisVelhas() {
        val arqs = mapOf("a" to 10L, "b" to 30L, "c" to 20L, "d" to 40L)
        assertEquals(listOf("c", "a"), FimDoVideo.podar(arqs, max = 2))
        assertTrue(FimDoVideo.podar(arqs, max = 4).isEmpty())
    }

    @Test fun tunelSoVoltaComErroDeDecodificadorOuAudio() {
        assertTrue(FimDoVideo.erroDoTunel(4001))   // ERROR_CODE_DECODER_INIT_FAILED
        assertTrue(FimDoVideo.erroDoTunel(5002))   // ERROR_CODE_AUDIO_TRACK_WRITE_FAILED
        assertFalse(FimDoVideo.erroDoTunel(2001))  // rede: tenta de novo, não mexe no túnel
        assertFalse(FimDoVideo.erroDoTunel(3001))  // arquivo estragado
    }

    @Test fun preparaProximoFaltando10MinMasNaoAntesDaMetade() {
        val h = 3_600_000L
        assertFalse(FimDoVideo.horaDePreparar(49 * 60_000L, h))
        assertTrue(FimDoVideo.horaDePreparar(50 * 60_000L, h))
        assertFalse(FimDoVideo.horaDePreparar(9 * 60_000L, 20 * 60_000L))   // episódio de 20 min: só da metade
        assertTrue(FimDoVideo.horaDePreparar(10 * 60_000L, 20 * 60_000L))
        assertFalse(FimDoVideo.horaDePreparar(10_000, 0))                   // duração desconhecida (ao vivo)
    }

    @Test fun lerProximoSoComOsTresCampos() {
        assertEquals(Triple("123", 1, 2), FimDoVideo.lerProximo("123|1|2"))
        assertNull(FimDoVideo.lerProximo("123|1"))
        assertNull(FimDoVideo.lerProximo("|1|2"))
        assertNull(FimDoVideo.lerProximo("123|x|2"))
        assertNull(FimDoVideo.lerProximo(null))
    }

    @Test fun tunelPresoSoComMuitoBaixado() {
        assertTrue(FimDoVideo.tunelPreso(30_000))    // o caso visto na Sala: 30 s baixados e parado
        assertFalse(FimDoVideo.tunelPreso(2_000))    // rede lenta de verdade: espera
    }
}
