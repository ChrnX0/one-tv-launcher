package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.Transcricao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** O "Texto da fala" do player: lê os 3 formatos do podcast:transcript, junta pedaços e acha a frase da hora. */
class TranscricaoTest {

    @Test fun json() {
        val t = """{"version":"1.0.0","segments":[
            {"speaker":"Ana","startTime":0.5,"endTime":2.0,"body":"Bom dia."},
            {"speaker":"Ana","startTime":2.1,"endTime":4.0,"body":"Hoje falamos de"},
            {"speaker":"Ana","startTime":4.1,"endTime":5.0,"body":"vacinação."},
            {"speaker":"Beto","startTime":5.5,"endTime":7.0,"body":"Oi, Ana!"}]}"""
        val l = Transcricao.ler(t, "application/json")
        // "Bom dia." termina frase: não junta. As duas do meio juntam (mesma pessoa, curtas, sem ponto).
        assertEquals(listOf("Bom dia.", "Hoje falamos de vacinação.", "Oi, Ana!"), l.map { it.texto })
        assertEquals("Beto", l[2].quem)
        assertEquals(2_100L, l[1].inicioMs)
        assertEquals(5_000L, l[1].fimMs)
    }

    @Test fun vtt() {
        val t = "WEBVTT\n\n00:00:01.000 --> 00:00:03.000\n<v Carla>Olá <b>todos</b>.\n\n00:01:02.500 --> 00:01:04.000\nSegunda frase.\n"
        val l = Transcricao.ler(t, "text/vtt")
        assertEquals(2, l.size)
        assertEquals("Olá todos.", l[0].texto)
        assertEquals("Carla", l[0].quem)
        assertEquals(62_500L, l[1].inicioMs)
    }

    @Test fun srt() {
        val t = "1\r\n00:00:01,000 --> 00:00:02,500\r\nLinha um.\r\n\r\n2\r\n00:00:03,000 --> 00:00:04,000\r\nLinha dois.\r\n"
        val l = Transcricao.ler(t, "application/x-subrip")
        assertEquals(listOf("Linha um.", "Linha dois."), l.map { it.texto })
        assertEquals(1_000L, l[0].inicioMs)
    }

    @Test fun lixo() {
        assertTrue(Transcricao.ler(null).isEmpty())
        assertTrue(Transcricao.ler("{quebrado", "application/json").isEmpty())
        assertTrue(Transcricao.ler("<html>texto sem tempo</html>", "text/html").isEmpty())
    }

    @Test fun escolher() {
        val l = listOf(
            PodcastFeed.Transcricao("a", "text/html", ""),
            PodcastFeed.Transcricao("b", "application/srt", "pt"),
            PodcastFeed.Transcricao("c", "application/json", "en"),
            PodcastFeed.Transcricao("d", "text/vtt", "pt-BR"),
        )
        // A língua da TV pesa mais que o formato: VTT em português ganha do JSON em inglês.
        assertEquals("d", Transcricao.escolher(l, "pt")?.url)
        assertEquals("c", Transcricao.escolher(l, "en")?.url)
        assertEquals(null, Transcricao.escolher(listOf(PodcastFeed.Transcricao("a", "text/html", "")), "pt"))
    }

    @Test fun atualEBusca() {
        val l = listOf(
            Transcricao.Trecho(1_000, 2_000, "A Vacinação começou."),
            Transcricao.Trecho(5_000, 6_000, "São Paulo amanhã."),
            Transcricao.Trecho(9_000, 9_500, "Fim."),
        )
        assertEquals(-1, Transcricao.atual(l, 500))
        assertEquals(0, Transcricao.atual(l, 1_000))
        assertEquals(1, Transcricao.atual(l, 8_999))
        assertEquals(2, Transcricao.atual(l, 60_000))
        assertEquals(listOf(0), Transcricao.buscar(l, "vacinacao"))
        assertEquals(listOf(1), Transcricao.buscar(l, "sao  PAULO"))
        assertTrue(Transcricao.buscar(l, "x").isEmpty())
    }
}
