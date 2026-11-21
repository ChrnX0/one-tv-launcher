package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.AberturaSerie
import br.paulo.tvlauncher.player.Capitulos
import br.paulo.tvlauncher.player.EpisodeSegments
import br.paulo.tvlauncher.player.mostrarProximo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeSegmentsTest {
    // Resposta real do IntroDB (Breaking Bad T1E2, lida em 20/11/2026), com um resumo acrescentado.
    private val json = """{"imdb_id":"tt0903747","intro":{"start_ms":314500,"end_ms":331000,"confidence":1},
        "recap":{"start_ms":0,"end_ms":45000,"confidence":0.9},
        "outro":{"start_ms":2843000,"end_ms":2901000,"confidence":1},"post_credits":null,
        "no_intro":false,"no_recap":false,"no_outro":false}"""

    @Test fun leOsTresTrechos() {
        val t = EpisodeSegments.lerIntroDb(json)!!
        assertEquals(AberturaSerie.Marca(314_500, 331_000), t.abertura)
        assertEquals(AberturaSerie.Marca(0, 45_000), t.resumo)
        assertEquals(2_843_000L, t.creditos)
    }

    @Test fun cenaDepoisDosCreditosNaoOfereceProximo() {
        val comCena = json.replace("\"post_credits\":null", "\"post_credits\":{\"start_ms\":2890000,\"end_ms\":2901000}")
        assertNull(EpisodeSegments.lerIntroDb(comCena)!!.creditos)
    }

    @Test fun poucaConfiancaOuSemTrechoNaoVale() {
        val fraco = json.replace("\"confidence\":0.9", "\"confidence\":0.3")
        assertNull(EpisodeSegments.lerIntroDb(fraco)!!.resumo)
        assertNull(EpisodeSegments.lerIntroDb("{\"no_outro\":true,\"outro\":{\"start_ms\":1,\"end_ms\":2}}")!!.creditos)
        assertNull(EpisodeSegments.lerIntroDb("não é json"))
    }

    @Test fun creditosSoNoFimDoVideo() {
        val dur = 2_901_000L
        assertTrue(EpisodeSegments.creditosValidos(2_843_000, dur))
        assertFalse(EpisodeSegments.creditosValidos(600_000, dur))          // no meio do episódio
        assertFalse(EpisodeSegments.creditosValidos(dur - 5_000, dur))      // colado no fim: nada a ganhar
        assertFalse(EpisodeSegments.creditosValidos(dur - 13 * 60_000, 10 * 3_600_000L)) // longe demais
        assertFalse(EpisodeSegments.creditosValidos(100, 0))                // sem duração
    }

    @Test fun capitulosDoArquivo() {
        val caps = listOf(
            Capitulos.Capitulo(0, 60_000, "Recap"),
            Capitulos.Capitulo(60_000, 150_000, "Opening Credits"),
            Capitulos.Capitulo(150_000, 2_500_000, "Episode"),
            Capitulos.Capitulo(2_500_000, 2_600_000, "Ending"))
        assertEquals(AberturaSerie.Marca(0, 60_000), EpisodeSegments.resumoDoCapitulo(caps))
        assertEquals(2_500_000L, EpisodeSegments.creditosDoCapitulo(caps, 2_600_000))
        // "Opening Credits" no começo não é o fim
        assertNull(EpisodeSegments.creditosDoCapitulo(caps.take(3), 2_500_000))
        // o banco vence o capítulo; banco inválido cai no capítulo
        assertEquals(2_450_000L, EpisodeSegments.inicioCreditos(2_450_000, caps, 2_600_000))
        assertEquals(2_500_000L, EpisodeSegments.inicioCreditos(10_000, caps, 2_600_000))
    }

    @Test fun pilulaProximoSoNosCreditos() {
        assertTrue(EpisodeSegments.mostrarProximo(2_843_000, 2_850_000, 2_901_000))
        assertFalse(EpisodeSegments.mostrarProximo(2_843_000, 2_800_000, 2_901_000))
        assertFalse(EpisodeSegments.mostrarProximo(2_843_000, 2_900_000, 2_901_000))   // a 1 s do fim: o fim normal cuida
        assertFalse(EpisodeSegments.mostrarProximo(null, 2_850_000, 2_901_000))
    }

    @Test fun resumoAntesDaAbertura() {
        val resumo = AberturaSerie.Marca(0, 45_000)
        val abertura = AberturaSerie.Marca(40_000, 90_000)
        assertEquals(resumo, EpisodeSegments.pularAgora(resumo, abertura, 42_000))
        assertEquals(abertura, EpisodeSegments.pularAgora(resumo, abertura, 60_000))
        assertNull(EpisodeSegments.pularAgora(resumo, abertura, 200_000))
    }

    @Test fun terminaAsNaVelocidade() {
        assertEquals(1_000_000L + 600_000, EpisodeSegments.terminaEm(1_000_000, 0, 600_000, 1f))
        assertEquals(1_000_000L + 300_000, EpisodeSegments.terminaEm(1_000_000, 0, 600_000, 2f))
        assertNull(EpisodeSegments.terminaEm(1_000_000, 0, 0, 1f))
    }

    @Test fun repetirNaoPassaDoComeco() {
        assertEquals(50_000L, EpisodeSegments.repetirDe(60_000))
        assertEquals(0L, EpisodeSegments.repetirDe(4_000))
    }
}
