package br.paulo.tvlauncher

import br.paulo.tvlauncher.PodcastAssuntos.Assunto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Filtro de assunto dos podcasts (21/11/2026). */
class PodcastAssuntosTest {
    @Test fun nomesDoFeedViramAssuntos() {
        assertEquals(listOf(Assunto.NOTICIAS, Assunto.CULTURA), PodcastAssuntos.de(listOf("News", "Daily News", "Society &amp; Culture")))
        assertEquals(listOf(Assunto.CULTURA), PodcastAssuntos.de(listOf(" society  and culture ")))
        assertEquals(emptyList<Assunto>(), PodcastAssuntos.de(listOf("Algo que não existe", "")))
        assertTrue(PodcastAssuntos.tem(listOf("Health & Fitness", "Mental Health"), Assunto.SAUDE))
        assertFalse(PodcastAssuntos.tem(listOf("Comedy"), Assunto.SAUDE))
    }

    @Test fun idsEstaveisESemRepetir() {
        assertEquals(Assunto.entries.size, Assunto.entries.map { it.id }.toSet().size)
        assertEquals(Assunto.CIENCIA, Assunto.deId("science"))
        // O servidor aceita até 5 nomes, só letras, espaço, & e hífen (server/api cleanCats).
        for (a in Assunto.entries) {
            val cat = PodcastAssuntos.paraServidor(a).split(',')
            assertTrue(cat.size in 1..5)
            assertTrue(a.id, cat.all { Regex("^[A-Za-z][A-Za-z &-]{1,39}$").matches(it) })
        }
    }

    @Test fun emAltaComAssunto() {
        assertEquals("https://s/v1/podcasts/trending?lang=pt&max=30&cat=Comedy%2CComedy+Interviews%2CImprov%2CStand-Up",
            PodcastBusca.urlEmAlta("https://s", "pt", Assunto.HUMOR))
        val r = PodcastBusca.ler("""{"feeds":[{"url":"https://a/x","title":"X","explicit":false,"categories":["News","Politics"]}]}""")
        assertEquals(listOf("News", "Politics"), r.single().categorias)
    }
}
