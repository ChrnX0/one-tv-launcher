package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.PodcastBusca
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Busca de podcasts pelo servidor nosso (17/11/2026; ligada 19/11/2026). */
class PodcastBuscaTest {
    @Test
    fun ligadaNoNossoServidor() {
        assertTrue(PodcastBusca.disponivel())                   // api.sofaly.tv com a chave do Podcast Index (19/11/2026)
        assertTrue(PodcastBusca.disponivel("https://x.sofaly.tv"))
        assertFalse(PodcastBusca.disponivel("http://x"))       // só https
    }

    @Test
    fun enderecos() {
        assertEquals("https://s/v1/podcasts/search?q=caf%C3%A9+da+manh%C3%A3&lang=pt&max=30", PodcastBusca.urlBuscar("https://s/", " café da manhã ", "pt"))
        assertEquals("https://s/v1/podcasts/trending?lang=pt&max=30", PodcastBusca.urlEmAlta("https://s", "pt"))
    }

    @Test
    fun leResposta() {
        val j = """{"feeds":[{"url":"https://a.org/f","title":"Café <b>com</b> Notícia","author":"X","image":"https://a.org/c.jpg","explicit":false},
            {"url":"https://a.org/f","title":"dup"},{"url":"ftp://x","title":"ruim"},{"url":"https://b.org/f","title":""}]}"""
        val l = PodcastBusca.ler(j)
        assertEquals(1, l.size)
        assertEquals("Café com Notícia", l[0].titulo)
        assertTrue(PodcastBusca.ler("lixo").isEmpty())
    }

    @Test fun semCampoExplicitContaComoExplicito() {
        val r = PodcastBusca.ler("""{"feeds":[{"url":"https://a.org/f","title":"A"},{"url":"https://a.org/g","title":"B","explicit":null},{"url":"https://a.org/h","title":"C","explicit":false}]}""")
        assertEquals(listOf(true, true, false), r.map { it.explicito })
    }
}
