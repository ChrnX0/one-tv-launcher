package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideia 26: Filmes e Séries tirados de uma lista M3U. */
class M3uVodTest {
    private fun c(id: String, nome: String, url: String, grupo: String = "", logo: String = "") = Canal(id, nome, 0, grupo, logo, url)

    @Test fun vodPeloCaminhoOuPelaExtensao() {
        assertTrue(M3uVod.ehVod("http://srv:8080/movie/u/p/123.mkv"))
        assertTrue(M3uVod.ehVod("http://srv/series/u/p/9.mp4"))
        assertTrue(M3uVod.ehVod("https://cdn.x/filmes/Matrix.MP4?token=abc"))
        assertFalse(M3uVod.ehVod("http://srv:8080/live/u/p/1.ts"))
        assertFalse(M3uVod.ehVod("http://srv/canal/index.m3u8"))
        assertFalse(M3uVod.ehVod("http://srv/u/p/1"))
        assertFalse(M3uVod.ehVod("http://srv/movie.php?id=1"))   // "movie" sem barras não é o caminho /movie/
        assertEquals("mkv", M3uVod.extensao("http://srv/a/b.mkv#x"))
        assertNull(M3uVod.extensao("http://srv/a/b"))
    }

    @Test fun episodioPeloNome() {
        assertEquals(Triple("Breaking Bad", 2, 5), M3uVod.episodio("Breaking Bad S02E05 - Fly"))
        assertEquals(Triple("The Office (US)", 1, 12), M3uVod.episodio("The Office (US) S01 E12"))
        assertEquals(Triple("Dark", 3, 8), M3uVod.episodio("Dark.S03E08"))
        assertNull(M3uVod.episodio("Matrix (1999)"))
        assertNull(M3uVod.episodio("S01E01"))                // sem nome de série
        assertNull(M3uVod.episodio("Superman Returns"))      // "S" no começo de palavra não é temporada
        assertEquals("Segredos", M3uVod.tituloEpisodio("Dark S01E01 - Segredos"))
        assertEquals("", M3uVod.tituloEpisodio("Dark S01E02"))
    }

    @Test fun montaFilmesESeriesSemMexerNosCanais() {
        val canais = listOf(
            c("c1", "Globo", "http://srv/live/u/p/1.ts", "Abertos"),
            c("f1", "Matrix (1999)", "http://srv/movie/u/p/10.mkv", "Ação", "http://img/m.jpg"),
            c("e2", "Dark S01E02", "http://srv/series/u/p/22.mp4", "Séries", "http://img/d.jpg"),
            c("e1", "Dark S01E01 - Segredos", "http://srv/series/u/p/21.mp4", "Séries"),
            c("e3", "Dark S02E01", "http://srv/series/u/p/31.mp4", "Séries"),
            c("e1b", "Dark S01E01 - Segredos", "http://srv/series/u/p/21b.mp4", "Séries"),   // repetido: fica um
            c("x1", "Avulso", "http://srv/series/u/p/77.mkv", "Novela X"),
        )
        val cat = M3uVod.montar(canais, "fonte")
        assertEquals(listOf("f1"), cat.filmes.map { it.id })
        val f = cat.filmes[0]
        assertEquals(1999, f.ano); assertEquals("mkv", f.extensao); assertEquals("http://img/m.jpg", f.capa); assertEquals("Ação", f.grupo)
        assertEquals(listOf("Dark", "Novela X"), cat.series.map { it.nome })
        val dark = cat.series[0]
        assertEquals("http://img/d.jpg", dark.capa)
        val t = cat.episodios.getValue(dark.id)
        assertEquals(listOf(1, 2), t.keys.toList())
        assertEquals(listOf(1, 2), t.getValue(1).map { it.numero })
        assertEquals("Segredos", t.getValue(1)[0].titulo)
        assertEquals("e1", t.getValue(1)[0].id)
        assertEquals(1, cat.episodios.getValue(cat.series[1].id).getValue(1).size)
    }

    @Test fun idDaSerieEstavelEPorGrupo() {
        assertEquals(M3uVod.idSerie("Dark", "Séries"), M3uVod.idSerie("DARK", "series"))
        assertTrue(M3uVod.idSerie("Dark", "Séries") != M3uVod.idSerie("Dark", "Kids"))
    }

    @Test fun listaSoDeCanaisNaoTemCatalogo() {
        assertTrue(M3uVod.montar(listOf(c("a", "Canal", "http://s/1.ts")), "f").vazio)
    }
}
