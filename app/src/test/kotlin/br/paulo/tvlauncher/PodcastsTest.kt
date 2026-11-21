package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.player.Episodio
import br.paulo.tvlauncher.player.Posicao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ouvir parte 2 (17/11/2026): assinaturas, posições, continuar e episódios novos. */
class PodcastsTest {
    private val f = "https://a.org/feed"
    private fun pos(guid: String, ms: Long, dur: Long = 3_600_000, em: Long = 1, feed: String = f) =
        Podcasts.Posicao(feed, guid, ms, dur, false, em)

    @Test
    fun assinaturasIdaEVolta() {
        val l = listOf(Podcasts.Assinatura(f, "Café", "Autor", "https://a.org/c.jpg", true, "\"e1\"", "Tue, 17 Nov 2026 09:00:00 GMT", 5, 4))
        assertEquals(l, Podcasts.assinaturasDeJson(Podcasts.assinaturasParaJson(l)))
        assertTrue(Podcasts.assinaturasDeJson("lixo").isEmpty())
        assertTrue(Podcasts.assinaturasDeJson("""{"assinaturas":[{"url":"javascript:x","titulo":"y"}]}""").isEmpty())
    }

    @Test
    fun assinarDeNovoNaoDuplica() {
        val a = Podcasts.Assinatura(f, "Café", desde = 10)
        val l = Podcasts.assinar(Podcasts.assinar(emptyList(), a), a.copy(titulo = "Café novo", desde = 99))
        assertEquals(1, l.size)
        assertEquals("Café novo", l[0].titulo)
        assertEquals(10L, l[0].desde)   // a data de assinatura não muda
    }

    @Test
    fun ouvidoAos95PorCento() {
        assertFalse(Podcasts.ehOuvido(3_419_000, 3_600_000))
        assertTrue(Podcasts.ehOuvido(3_420_000, 3_600_000))
        assertFalse(Podcasts.ehOuvido(10_000, 0))   // sem duração não decide
        val m = Podcasts.guardar(emptyMap(), pos("g", 3_500_000))
        assertTrue(m.values.single().ouvido)
        // Reabrir do começo um ouvido não o desmarca.
        assertTrue(Podcasts.guardar(m, pos("g", 0)).values.single().ouvido)
    }

    @Test
    fun posicaoVelhaNaoPassaPorCimaDaNova() {
        // Gravação periódica (10:00, tirada antes) chegando depois da pausa (11:30): não volta o relógio.
        val m = Podcasts.guardar(emptyMap(), pos("g", 690_000, em = 20))
        assertEquals(690_000L, Podcasts.guardar(m, pos("g", 600_000, em = 10)).values.single().ms)
        assertEquals(700_000L, Podcasts.guardar(m, pos("g", 700_000, em = 30)).values.single().ms)
    }

    @Test
    fun arquivoEstragadoNaoViraListaVazia() {
        assertNull(Podcasts.posicoesDeJsonOuNull("[{\"feed\":"))
        assertNull(Podcasts.assinaturasDeJsonOuNull("{\"assinaturas\":[{"))
        assertEquals(emptyList<Podcasts.Assinatura>(), Podcasts.assinaturasDeJsonOuNull(null))
        assertEquals(emptyMap<String, Podcasts.Posicao>(), Podcasts.posicoesDeJsonOuNull("[]"))
    }

    @Test
    fun retomar() {
        assertEquals(0L, Podcasts.retomarEm(null))
        assertEquals(0L, Podcasts.retomarEm(pos("g", 8_000)))
        assertEquals(55_000L, Podcasts.retomarEm(pos("g", 60_000)))
        assertEquals(0L, Podcasts.retomarEm(pos("g", 60_000).copy(ouvido = true)))
    }

    @Test
    fun continuarSoComecadosEAssinados() {
        var m = emptyMap<String, Podcasts.Posicao>()
        m = Podcasts.guardar(m, pos("a", 120_000, em = 1))
        m = Podcasts.guardar(m, pos("b", 240_000, em = 3))
        m = Podcasts.guardar(m, pos("c", 5_000, em = 4))            // só abriu
        m = Podcasts.guardar(m, pos("d", 3_590_000, em = 5))        // terminou
        m = Podcasts.guardar(m, pos("e", 120_000, em = 6, feed = "https://outro/x"))   // desassinado
        assertEquals(listOf("b", "a"), Podcasts.continuar(m, setOf(f)).map { it.guid })
    }

    @Test
    fun posicoesCortadas() {
        var m = emptyMap<String, Podcasts.Posicao>()
        for (i in 1..(Podcasts.MAX_POSICOES + 20)) m = Podcasts.guardar(m, pos("g$i", 60_000, em = i.toLong()))
        assertEquals(Podcasts.MAX_POSICOES, m.size)
        assertNull(m[Podcasts.chave(f, "g1")])                  // a mais velha saiu
        assertEquals(m, Podcasts.posicoesDeJson(Podcasts.posicoesParaJson(m)))
    }

    private fun ep(guid: String, data: Long, explicito: Boolean = false) = PodcastFeed.Episodio(guid, "t$guid", "", data, 60,
        "https://a.org/$guid.mp3", "audio/mpeg", 0, "", explicito, 0, 0, emptyList(), "")

    @Test
    fun novosDosUltimos14Dias() {
        val dia = 86_400_000L; val agora = 100 * dia
        val feed = PodcastFeed.Feed("x", "", "", "", "", "", false, listOf(ep("hoje", agora - 1000), ep("ontem", agora - dia),
            ep("velho", agora - 20 * dia), ep("comecado", agora - 2 * dia)))
        val m = Podcasts.guardar(emptyMap(), pos("comecado", 60_000))
        assertEquals(listOf("hoje", "ontem"), Podcasts.novos(mapOf(f to feed), m, agora).map { it.second.guid })
    }

    @Test
    fun explicitoSomeComCrianca() {
        assertFalse(Podcasts.visivel(ep("x", 0, explicito = true), protegido = true))
        assertTrue(Podcasts.visivel(ep("x", 0, explicito = true), protegido = false))
        assertTrue(Podcasts.visivel(ep("x", 0), protegido = true))
    }

    @Test
    fun copiaDoFeedIdaEVolta() {
        val feed = PodcastFeed.Feed("Café", "A", "d", "https://a.org/c.jpg", "https://a.org", "pt", false,
            listOf(ep("1", 5).copy(transcricoes = listOf(PodcastFeed.Transcricao("https://a.org/1.vtt", "text/vtt", "pt")), capitulos = "https://a.org/c.json")))
        assertEquals(feed, Podcasts.feedDeJson(Podcasts.feedParaJson(feed)))
    }
}
