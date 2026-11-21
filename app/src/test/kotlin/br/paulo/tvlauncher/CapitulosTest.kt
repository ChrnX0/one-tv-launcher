package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.Capitulos
import br.paulo.tvlauncher.listen.PodcastFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapitulosTest {
    private val json = """{"version":"1.2.0","chapters":[{"startTime":0,"title":"Abertura"},{"startTime":95.5,"title":"Notícias"},
        {"startTime":600,"title":"marca interna","toc":false},{"startTime":1200,"title":"Entrevista"}]}"""

    @Test fun json() {
        val c = Capitulos.doJson(json)
        assertEquals(listOf("Abertura", "Notícias", "Entrevista"), c.map { it.titulo })
        assertEquals(95_500L, c[1].inicioMs)
        assertTrue(Capitulos.doJson("quebrado").isEmpty())
        assertTrue(Capitulos.doJson("""{"chapters":[{"startTime":0,"title":"Só um"}]}""").isEmpty())   // 1 não é índice
    }

    @Test fun tempos() {
        assertEquals(3_723_500L, Capitulos.tempoMs("1:02:03.5"))
        assertEquals(750_000L, Capitulos.tempoMs("12:30"))
        assertEquals(75_000L, Capitulos.tempoMs("75"))
        assertNull(Capitulos.tempoMs("x"))
    }

    @Test fun navegar() {
        val c = Capitulos.doJson(json)
        assertEquals(1, Capitulos.atual(c, 100_000))
        assertEquals(1_200_000L, Capitulos.proximo(c, 100_000))
        assertNull(Capitulos.proximo(c, 1_300_000))
        assertEquals(95_500L, Capitulos.anterior(c, 200_000))   // dentro do capítulo: volta ao começo dele
        assertEquals(0L, Capitulos.anterior(c, 96_000))         // logo no começo: vai ao anterior
    }

    @Test fun podloveNoFeed() {
        val xml = """<rss xmlns:psc="http://podlove.org/simple-chapters"><channel><title>P</title><item><title>E1</title>
            <enclosure url="https://ex.com/a.mp3" type="audio/mpeg"/><psc:chapters><psc:chapter start="00:00:00" title="Início"/>
            <psc:chapter start="00:05:10.250" title="Bloco 2"/></psc:chapters></item></channel></rss>"""
        val p = org.kxml2.io.KXmlParser().apply { setInput(xml.reader()) }
        val ep = PodcastFeed.ler(p)!!.episodios.first()
        assertTrue(!Capitulos.precisaBaixar(ep.capitulos))
        assertEquals(listOf(0L, 310_250L), Capitulos.doCampo(ep.capitulos).map { it.inicioMs })
    }
}
