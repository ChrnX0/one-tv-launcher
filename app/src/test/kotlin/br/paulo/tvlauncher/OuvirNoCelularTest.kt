package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.OuvirNoCelular
import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.listen.Radios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Ouvir no celular": o painel continua o mais recente entre a última rádio e o último episódio. */
class OuvirNoCelularTest {
    private val ep = PodcastFeed.Episodio("g", "Episódio", "", 0, 600, "https://x/g.mp3", "audio/mpeg", 0, "", false, 0, 0, emptyList(), "")
    private val radio = Radios.Radio("r", "Rádio X", "https://r/stream", logo = "https://r/logo.png")
    private fun pos(em: Long, ouvido: Boolean = false) = Podcasts.Posicao("f", "g", 125_000, 600_000, ouvido, em, "Episódio", "Pod")

    @Test fun maisRecenteGanha() {
        val a = OuvirNoCelular.escolher(radio to 100L, pos(200), ep, "c")
        assertEquals("podcast", a.getString("tipo")); assertEquals(125_000L, a.getLong("ms")); assertEquals("Pod", a.getString("de"))
        val b = OuvirNoCelular.escolher(radio to 300L, pos(200), ep, "c")
        assertEquals("radio", b.getString("tipo")); assertEquals("https://r/stream", b.getString("url"))
    }

    @Test fun casos() {
        assertTrue(OuvirNoCelular.escolher(null, null, null, "").optBoolean("nada"))
        assertEquals(0L, OuvirNoCelular.escolher(null, pos(1, ouvido = true), ep, "").getLong("ms"))   // ouvido: do começo
        assertEquals("radio", OuvirNoCelular.escolher(radio to 1L, pos(5), null, "").getString("tipo"))   // episódio sumiu do feed
    }
}
