package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.ElencoTmdb
import br.paulo.tvlauncher.player.Episodio
import br.paulo.tvlauncher.player.Posicao
import br.paulo.tvlauncher.player.UltimoEpisodio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        val cred = ElencoTmdb.Creditos(c, emptySet())
        val cat = listOf(ElencoTmdb.NaLista(false, 10, "Filme A", 2001), ElencoTmdb.NaLista(false, 10, "Filme A [L]", 2001),
            ElencoTmdb.NaLista(true, 20, "Série B", 2019), ElencoTmdb.NaLista(true, 10, "Série com id igual", 2020),
            ElencoTmdb.NaLista(false, 99, "Outro", 2024))
        val r = ElencoTmdb.naLista(cred, cat, fora = null)
        assertEquals(listOf("Série B", "Filme A"), r.map { it.nome })
        assertTrue(ElencoTmdb.naLista(cred, cat, fora = true to 20).none { it.serie && it.tmdb == 20 })
    }

    // Sala 17/11/2026: o Chalamet não achava Duna: Parte Dois porque o filme da lista não tinha id do TMDB.
    @Test fun semIdAchaPeloNomeEAno() {
        val cred = ElencoTmdb.lerCreditosCompletos("""{"cast":[
            {"id":693134,"media_type":"movie","title":"Duna: Parte Dois","original_title":"Dune: Part Two","release_date":"2024-02-27"},
            {"id":438631,"media_type":"movie","title":"Duna","original_title":"Dune","release_date":"2021-09-15"},
            {"id":1,"media_type":"tv","name":"Série X","first_air_date":"2018-01-01"}]}""")!!
        val cat = listOf(ElencoTmdb.NaLista(false, null, "Duna: Parte Dois [L]", 2024), ElencoTmdb.NaLista(false, null, "Duna - Parte Dois", 2024),
            ElencoTmdb.NaLista(false, null, "Duna", 2021), ElencoTmdb.NaLista(false, null, "Dune: Part Two", null),
            ElencoTmdb.NaLista(true, null, "Série X", 2018), ElencoTmdb.NaLista(false, null, "Série X", 2018))
        val r = ElencoTmdb.naLista(cred, cat, fora = false to 438631, foraNome = "Duna")
        assertEquals(listOf("Duna: Parte Dois [L]", "Série X"), r.map { it.nome })   // versões viram uma; sem ano não arrisca
        assertTrue(r.last().serie)
        assertEquals(null, ElencoTmdb.lerCreditosCompletos("{"))
    }

    @Test fun nomeEmOutraEscritaViraLatino() {
        assertTrue(ElencoTmdb.precisaLatino("張震", "pt-BR"))
        assertFalse(ElencoTmdb.precisaLatino("張震", "zh-CN"))
        assertFalse(ElencoTmdb.precisaLatino("Timothée Chalamet", "pt-BR"))
        assertFalse(ElencoTmdb.precisaLatino("Zendaya", "en-US"))
        val json = """{"also_known_as":["チャン・チェン","Chang Chen","Zhang Zhen"]}"""
        assertEquals("Chang Chen", ElencoTmdb.lerNomeLatino(json))
        assertNull(ElencoTmdb.lerNomeLatino("""{"also_known_as":["チャン・チェン"]}"""))
        assertNull(ElencoTmdb.lerNomeLatino("lixo"))
    }
}
