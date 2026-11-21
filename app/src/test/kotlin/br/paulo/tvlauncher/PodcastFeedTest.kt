package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.PodcastFeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser

/** Ouvir parte 2 (17/11/2026): leitura do feed RSS de podcast e do OPML. */
class PodcastFeedTest {
    private fun parser(xml: String) = KXmlParser().apply { setInput(xml.reader()) }

    private val feed = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd" xmlns:podcast="https://podcastindex.org/namespace/1.0">
<channel>
  <title>Café com Notícia</title>
  <link>https://exemplo.org/cafe</link>
  <language>pt-br</language>
  <itunes:author>Rádio Exemplo</itunes:author>
  <description><![CDATA[<p>O jornal da manhã &amp; mais.</p>]]></description>
  <itunes:image href="https://exemplo.org/capa.jpg"/>
  <itunes:explicit>false</itunes:explicit>
  <item>
    <title>Episódio velho</title>
    <guid>ep-1</guid>
    <pubDate>Mon, 16 Nov 2026 06:00:00 -0300</pubDate>
    <itunes:duration>59:30</itunes:duration>
    <enclosure url="https://cdn.exemplo.org/ep1.mp3" type="audio/mpeg" length="1234"/>
  </item>
  <item>
    <title>Episódio novo</title>
    <guid>ep-2</guid>
    <pubDate>Tue, 17 Nov 2026 06:00:00 -0300</pubDate>
    <itunes:duration>1:02:03</itunes:duration>
    <itunes:explicit>yes</itunes:explicit>
    <itunes:season>2</itunes:season><itunes:episode>14</itunes:episode>
    <itunes:image href="https://exemplo.org/ep2.jpg"/>
    <description>Hoje &lt;b&gt;tem&lt;/b&gt; previsão</description>
    <enclosure url="https://cdn.exemplo.org/ep2.mp3" type="audio/mpeg" length="99"/>
    <podcast:transcript url="https://exemplo.org/ep2.vtt" type="text/vtt" language="pt"/>
    <podcast:chapters url="https://exemplo.org/ep2.json" type="application/json+chapters"/>
  </item>
  <item>
    <title>Repetido</title><guid>ep-2</guid>
    <enclosure url="https://cdn.exemplo.org/ep2b.mp3" type="audio/mpeg"/>
  </item>
  <item>
    <title>Só um PDF</title><guid>ep-pdf</guid>
    <enclosure url="https://exemplo.org/a.pdf" type="application/pdf"/>
  </item>
  <item>
    <title>Sem arquivo</title><guid>ep-0</guid>
  </item>
  <item>
    <title>Com vídeo</title><guid>ep-v</guid><pubDate>Sun, 15 Nov 2026 06:00:00 GMT</pubDate>
    <enclosure url="https://cdn.exemplo.org/ep.mp4" type="video/mp4"/>
  </item>
  <item>
    <title>Endereço ruim</title><guid>ep-js</guid>
    <enclosure url="javascript:alert(1)" type="audio/mpeg"/>
  </item>
</channel>
</rss>"""

    @Test
    fun leOCanalEOsEpisodios() {
        val f = PodcastFeed.ler(parser(feed))!!
        assertEquals("Café com Notícia", f.titulo)
        assertEquals("Rádio Exemplo", f.autor)
        assertEquals("O jornal da manhã & mais.", f.descricao)
        assertEquals("https://exemplo.org/capa.jpg", f.capa)
        assertEquals("pt-br", f.idioma)
        assertFalse(f.explicito)
        assertEquals(listOf("ep-2", "ep-1", "ep-v"), f.episodios.map { it.guid })   // mais novo primeiro; repetido, PDF, sem arquivo e javascript fora
        val novo = f.episodios[0]
        assertEquals(3723, novo.duracaoS)
        assertTrue(novo.explicito)
        assertEquals(2, novo.temporada); assertEquals(14, novo.numero)
        assertEquals("https://exemplo.org/ep2.jpg", novo.capa)
        assertEquals("Hoje tem previsão", novo.resumo)
        assertEquals("https://exemplo.org/ep2.vtt", novo.transcricoes.single().url)
        assertEquals("https://exemplo.org/ep2.json", novo.capitulos)
        assertFalse(novo.video)
        assertTrue(f.episodios[2].video)
        assertEquals(3570, f.episodios[1].duracaoS)
        assertFalse(f.episodios[1].explicito)   // herda do canal
    }

    @Test
    fun naoEPodcast() {
        assertNull(PodcastFeed.ler(parser("<html><body>oi</body></html>")))
        assertNull(PodcastFeed.ler(parser("<rss><channel><title>x</title></channel></rss>")))   // sem episódio
    }

    @Test
    fun feedGrandeECortado() {
        val itens = (1..1000).joinToString("") { "<item><title>e$it</title><guid>g$it</guid><enclosure url=\"https://a.b/$it.mp3\" type=\"audio/mpeg\"/></item>" }
        val f = PodcastFeed.ler(parser("<rss><channel><title>x</title>$itens</channel></rss>"))!!
        assertEquals(PodcastFeed.MAX_EPISODIOS, f.episodios.size)
    }

    @Test
    fun feedDoMaisVelhoParaOMaisNovoGuardaOsMaisNovos() {
        // Revisão 17/11/2026: antes parava em 600 lidos e ficava com os 300 MAIS VELHOS.
        val itens = (1..1000).joinToString("") { i ->
            val d = java.time.Instant.ofEpochSecond(1_600_000_000L + i * 86_400L).atOffset(java.time.ZoneOffset.UTC)
                .format(java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME)
            "<item><title>e$i</title><guid>g$i</guid><pubDate>$d</pubDate><enclosure url=\"https://a.b/$i.mp3\" type=\"audio/mpeg\"/></item>"
        }
        val f = PodcastFeed.ler(parser("<rss><channel><title>x</title>$itens</channel></rss>"))!!
        assertEquals(PodcastFeed.MAX_EPISODIOS, f.episodios.size)
        assertEquals("g1000", f.episodios.first().guid)
        assertEquals("g701", f.episodios.last().guid)
    }

    @Test
    fun textoGiganteECortado() {
        val grande = "a".repeat(200_000)
        val f = PodcastFeed.ler(parser("<rss><channel><title>$grande</title><item><title>e</title><enclosure url=\"https://a.b/1.mp3\"/></item></channel></rss>"))!!
        assertEquals(200, f.titulo.length)
    }

    @Test
    fun entradaRecusaUtf7() {
        // Em UTF-7 "<!ENTITY" vira "+ADwAIQ-ENTITY": a busca byte a byte não via.
        val bomba = "<?xml version=\"1.0\" encoding=\"UTF-7\"?>+ADw-rss+AD4-"
        val en = PodcastFeed.Entrada(bomba.byteInputStream(), PodcastFeed.MAX_BYTES)
        assertTrue(runCatching { en.readBytes() }.isFailure)
        assertTrue(en.recusou)
        val ok = PodcastFeed.Entrada(feed.byteInputStream(), PodcastFeed.MAX_BYTES)
        ok.readBytes(); assertFalse(ok.recusou)
    }

    @Test
    fun datasComFormatosGuardados() {
        val a = PodcastFeed.data("Wed, 18 Nov 2026 06:00:00 +0000")
        assertEquals(a, PodcastFeed.data("Wed, 18 Nov 2026 06:00:00 +0000"))
        assertTrue(a > 0)
        assertEquals(PodcastFeed.data("2026-11-18T06:00:00Z"), a)
        assertEquals(0L, PodcastFeed.data("ontem"))
    }

    @Test
    fun entradaRecusaEntidadesDoDtd() {
        val bomba = "<?xml version=\"1.0\"?><!DOCTYPE rss [<!ENTITY a \"aaaaaaaaaa\"><!ENTITY b \"&a;&a;&a;&a;\">]><rss><channel><title>&b;</title></channel></rss>"
        val en = PodcastFeed.Entrada(bomba.byteInputStream(), PodcastFeed.MAX_BYTES)
        assertNull(PodcastFeed.ler(KXmlParser().apply { setInput(en, "UTF-8") }))
        assertTrue(en.recusou)
        assertTrue(PodcastFeed.temEntidade(bomba))
        assertFalse(PodcastFeed.temEntidade(feed))
        // O padrão partido entre duas leituras também é visto.
        val partido = PodcastFeed.Entrada(object : java.io.InputStream() {
            val b = "<!DOCTYPE x [<!ENTITY y \"z\">]>".toByteArray(); var i = 0
            override fun read() = if (i < b.size) b[i++].toInt() else -1
            override fun read(d: ByteArray, o: Int, l: Int): Int { if (i >= b.size) return -1; d[o] = b[i++]; return 1 }
        }, 1000)
        runCatching { partido.readBytes() }
        assertTrue(partido.recusou)
    }

    /** Rodada 3 (18/11/2026): em UTF-16 o "<!ENTITY" vem com zeros no meio e passava pelo filtro. */
    @Test
    fun entradaRecusaEntidadesEmUtf16() {
        val bomba = "<?xml version=\"1.0\" encoding=\"UTF-16\"?><!DOCTYPE rss [<!ENTITY a \"aaaa\">]><rss/>"
        for (cs in listOf(Charsets.UTF_16BE, Charsets.UTF_16LE, Charsets.UTF_16)) {
            val en = PodcastFeed.Entrada(bomba.toByteArray(cs).inputStream(), PodcastFeed.MAX_BYTES)
            runCatching { en.readBytes() }
            assertTrue("$cs", en.recusou)
        }
    }

    @Test
    fun opmlQuebradoNoMeioFicaComOQueVeioAntes() {
        val opml = "<opml><body><outline xmlUrl=\"https://a.b/1.xml\" text=\"Um\"/><outline xmlUrl=\"https://a.b/2.xml\" text=\"Dois\"/><outline <<< quebrado"
        val itens = PodcastFeed.lerOpml(KXmlParser().apply { setInput(opml.reader()) })
        assertEquals(listOf("https://a.b/1.xml", "https://a.b/2.xml"), itens.map { it.url })
    }

    @Test
    fun entradaComPrazoEComLimite() {
        var agora = 0L
        val lento = PodcastFeed.Entrada(ByteArray(10_000).inputStream(), 100_000, prazoMs = 60_000, relogio = { agora })
        assertEquals(10, lento.read(ByteArray(10), 0, 10))
        agora = 61_000
        assertTrue(runCatching { lento.read(ByteArray(10), 0, 10) }.isFailure)
        assertTrue(lento.estourou)
        val limitado = PodcastFeed.Entrada(ByteArray(10_000).inputStream(), 4_000)
        assertEquals(4_000, limitado.readBytes().size)
        assertFalse(limitado.estourou || limitado.recusou)
    }

    @Test
    fun feedCortadoNoMeioValeComOQueLeu() {
        // Feed real maior que o limite chega cortado (medido 17/11/2026: 20,7 MB). Os episódios inteiros já lidos valem.
        val cortado = feed.substring(0, feed.indexOf("<title>Repetido"))
        assertEquals(listOf("ep-2", "ep-1"), PodcastFeed.ler(parser(cortado))!!.episodios.map { it.guid })
    }

    @Test
    fun duracoes() {
        assertEquals(3600, PodcastFeed.duracao("3600"))
        assertEquals(90, PodcastFeed.duracao("1:30"))
        assertEquals(3723, PodcastFeed.duracao("01:02:03.500"))
        assertEquals(0, PodcastFeed.duracao("abc"))
        assertEquals(0, PodcastFeed.duracao(""))
        assertEquals(0, PodcastFeed.duracao("1:2:3:4"))
        assertEquals(48 * 3600, PodcastFeed.duracao("999999"))
    }

    @Test
    fun datas() {
        assertEquals(1_763_370_000_000L, PodcastFeed.data("Mon, 17 Nov 2025 09:00:00 GMT"))
        assertEquals(1_763_370_000_000L, PodcastFeed.data("Mon, 17 Nov 2025 06:00:00 -0300"))
        assertEquals(1_763_370_000_000L, PodcastFeed.data("2025-11-17T09:00:00Z"))
        assertEquals(0L, PodcastFeed.data("ontem"))
        assertTrue(PodcastFeed.data("Mon, 17 Nov 2025") > 0)
    }

    @Test
    fun opmlIdaEVolta() {
        val opml = """<?xml version="1.0"?><opml version="1.0"><head><title>Meus</title></head><body>
            <outline text="feeds"><outline type="rss" text="Café" xmlUrl="https://exemplo.org/cafe.xml"/>
            <outline type="rss" title="Outro &amp; mais" xmlUrl="https://b.org/f"/>
            <outline type="rss" text="dup" xmlUrl="https://b.org/f"/>
            <outline type="rss" text="ruim" xmlUrl="file:///sdcard/x"/></outline></body></opml>"""
        val l = PodcastFeed.lerOpml(parser(opml))
        assertEquals(listOf("https://exemplo.org/cafe.xml", "https://b.org/f"), l.map { it.url })
        assertEquals("Outro & mais", l[1].titulo)
        assertEquals(l, PodcastFeed.lerOpml(parser(PodcastFeed.paraOpml(l))))
    }

    @Test
    fun limparTexto() {
        assertEquals("a b", PodcastFeed.limpar("<p>a</p>\n\n<p>b</p>", 100))
        assertEquals("abc…", PodcastFeed.limpar("abcdefgh", 4))
        assertEquals("é ✓", PodcastFeed.limpar("&#233; &#10003;", 10))
        assertNotNull(PodcastFeed.limpar("", 10))
    }
}
