package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ElencoTmdbTest {
    private val dia = 24 * 3_600_000L
    private val eps = listOf(Episodio("1", 1, 1, "Piloto"), Episodio("2", 1, 2, "Dois"), Episodio("3", 2, 1, "Volta"))

    @Test fun anteriorSoDepoisDeTresSemanas() {
        val agora = 100 * dia
        assertNull(ElencoTmdb.anterior(eps, eps[2], agora - 20 * dia, agora))
        assertEquals("2", ElencoTmdb.anterior(eps, eps[2], agora - 22 * dia, agora)?.id)
    }

    @Test fun anteriorNadaNoPrimeiroNemSemHistorico() {
        val agora = 100 * dia
        assertNull(ElencoTmdb.anterior(eps, eps[0], agora - 60 * dia, agora))
        assertNull(ElencoTmdb.anterior(eps, eps[1], 0, agora))
    }

    @Test fun ultimaVezPegaAMaiorData() {
        val pos = mapOf("f:e1" to Posicao(10, 10, 500), "f:e2" to Posicao(5, 10, 900))
        assertEquals(900L, ElencoTmdb.ultimaVez(eps, "f", pos, UltimoEpisodio("1", 1, 1, "", "mp4", 700)))
        assertEquals(1200L, ElencoTmdb.ultimaVez(eps, "f", pos, UltimoEpisodio("1", 1, 1, "", "mp4", 1200)))
        assertEquals(0L, ElencoTmdb.ultimaVez(eps, "x", emptyMap(), null))
    }

    @Test fun lerEpisodioSemResumoEhNulo() {
        assertEquals("Piloto" to "Tudo começa.", ElencoTmdb.lerEpisodio("""{"name":"Piloto","overview":" Tudo começa. "}"""))
        assertNull(ElencoTmdb.lerEpisodio("""{"name":"Piloto","overview":""}"""))
        assertNull(ElencoTmdb.lerEpisodio("lixo"))
    }

    @Test fun elencoDeFilmeEDeSerie() {
        val filme = ElencoTmdb.lerElenco("""{"cast":[{"id":1,"name":"Ana","character":"Rainha","profile_path":"/a1.jpg"},
            {"id":1,"name":"Ana","character":"Outra"},{"id":2,"name":"","character":"x"},{"id":3,"name":"Bia","profile_path":null}]}""")
        assertEquals(listOf(1, 3), filme.map { it.id })
        assertEquals("https://image.tmdb.org/t/p/w185/a1.jpg", filme[0].foto)
        assertEquals("", filme[1].foto)
        val serie = ElencoTmdb.lerElenco("""{"cast":[{"id":7,"name":"Caio","roles":[{"character":"Detetive"}]}]}""")
        assertEquals("Detetive", serie[0].personagem)
    }

    @Test fun fotoSoComCaminhoLimpo() {
        assertEquals("", ElencoTmdb.foto("/../x.jpg"))
        assertEquals("", ElencoTmdb.foto("https://outro/x.jpg"))
        assertEquals("", ElencoTmdb.foto(null))
    }

    @Test fun creditosENaLista() {
        val c = ElencoTmdb.lerCreditos("""{"cast":[{"id":10,"media_type":"movie"},{"id":20,"media_type":"tv"},{"id":30,"media_type":"person"}]}""")
        assertEquals(setOf(false to 10, true to 20), c)
        val cat = listOf(ElencoTmdb.NaLista(false, 10, "Filme A", 2001), ElencoTmdb.NaLista(false, 10, "Filme A [L]", 2001),
            ElencoTmdb.NaLista(true, 20, "Série B", 2019), ElencoTmdb.NaLista(true, 10, "Série com id igual", 2020),
            ElencoTmdb.NaLista(false, 99, "Outro", 2024))
        val r = ElencoTmdb.naLista(c, cat, fora = null)
        assertEquals(listOf("Série B", "Filme A"), r.map { it.nome })
        assertTrue(ElencoTmdb.naLista(c, cat, fora = true to 20).none { it.serie && it.tmdb == 20 })
    }
}
