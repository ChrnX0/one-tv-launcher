package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.PodcastStarter
import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Os podcasts que vêm no app ([PodcastStarter]): leitura, idioma, o que mostrar e o que seguir sozinho. */
class PodcastStarterTest {
    private val json = """{"languages":{
        "pt":[{"url":"https://a/1","title":"Um","follow":true},{"url":"https://a/2","title":"Dois","explicit":true,"follow":true},
              {"url":"https://a/3","title":"Três"},{"url":"ftp://x","title":"Ruim"},{"url":"https://a/4","title":""},{"url":"https://a/1","title":"Repetido"}],
        "pt-pt":[{"url":"https://p/1","title":"Portugal"}],
        "en":[{"url":"https://e/1","title":"One","follow":true}],
        "xx":[]}}"""

    @Test fun lerIgnoraRuinsERepetidos() {
        val m = PodcastStarter.ler(json)
        assertEquals(listOf("Um", "Dois", "Três"), m.getValue("pt").map { it.titulo })
        assertEquals(setOf("pt", "pt-pt", "en"), m.keys)   // idioma vazio some
        assertEquals(emptyMap<String, Any>(), PodcastStarter.ler(null))
        assertEquals(emptyMap<String, Any>(), PodcastStarter.ler("não é json"))
    }

    @Test fun idiomaComPaisDepoisSoLinguaDepoisIngles() {
        val m = PodcastStarter.ler(json)
        assertEquals("Portugal", PodcastStarter.doIdioma(m, Locale.forLanguageTag("pt-PT")).first().titulo)
        assertEquals("Um", PodcastStarter.doIdioma(m, Locale.forLanguageTag("pt-BR")).first().titulo)
        assertEquals("One", PodcastStarter.doIdioma(m, Locale.forLanguageTag("ja-JP")).first().titulo)
        assertEquals(emptyList<Any>(), PodcastStarter.doIdioma(emptyMap(), Locale.US))
    }

    @Test fun mostrarTiraSeguidosEExplicitoProtegido() {
        val l = PodcastStarter.ler(json).getValue("pt")
        assertEquals(listOf("Dois", "Três"), PodcastStarter.paraMostrar(l, setOf("https://a/1"), false).map { it.titulo })
        assertEquals(listOf("Três"), PodcastStarter.paraMostrar(l, setOf("https://a/1"), true).map { it.titulo })
    }

    @Test fun seguirSoNaPrimeiraVezComPoucoSeguidoENuncaExplicito() {
        val l = PodcastStarter.ler(json).getValue("pt")
        assertEquals(listOf("Um"), PodcastStarter.paraSeguir(l, emptySet(), jaFez = false).map { it.titulo })
        assertEquals(emptyList<Any>(), PodcastStarter.paraSeguir(l, setOf("https://a/1"), jaFez = false))
        assertEquals(emptyList<Any>(), PodcastStarter.paraSeguir(l, emptySet(), jaFez = true))
        assertEquals(emptyList<Any>(), PodcastStarter.paraSeguir(l, setOf("x", "y", "z"), jaFez = false))
    }

    /** O arquivo de verdade: cada idioma com lista boa, 4 para seguir sozinho, nada explícito entre eles, tudo https. */
    @Test fun arquivoDoApp() {
        val arq = listOf(File("src/main/assets/${PodcastStarter.ARQUIVO}"), File("app/src/main/assets/${PodcastStarter.ARQUIVO}")).first { it.exists() }
        val m = PodcastStarter.ler(arq.readText())
        assertTrue("tem ${m.keys}", m.keys.containsAll(listOf("pt", "en", "es", PodcastStarter.RESERVA)))
        for ((idioma, l) in m) {
            assertTrue("$idioma tem ${l.size}", l.size >= 7)
            val seguir = l.filter { it.seguir }
            assertEquals("$idioma: 4 para seguir", 4, seguir.size)
            assertTrue("$idioma: nenhum explícito para seguir sozinho", seguir.none { it.explicito })
            for (s in l) {
                assertTrue("${s.titulo}: feed https", s.url.startsWith("https://"))
                assertTrue("${s.titulo}: capa https", s.capa.startsWith("https://"))
                assertTrue("${s.titulo}: autor", s.autor.isNotBlank())
            }
        }
        assertEquals("sem feed repetido entre idiomas", m.values.flatten().size, m.values.flatten().distinctBy { it.url }.size)
    }
}
