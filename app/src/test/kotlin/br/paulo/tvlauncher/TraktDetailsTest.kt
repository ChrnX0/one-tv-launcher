package br.paulo.tvlauncher

import br.paulo.tvlauncher.family.Classificacao
import br.paulo.tvlauncher.player.InfoExterna
import br.paulo.tvlauncher.player.ServicosFilmes
import br.paulo.tvlauncher.player.TraktDetails
import br.paulo.tvlauncher.player.item
import java.util.Locale
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TraktDetails (plano Básico, 19/11/2026). Amostras no formato da documentação do Trakt (developer.trakt.tv), não
 * respostas reais.
 */
class TraktDetailsTest {
    private val busca = """[
        {"type":"movie","score":1000,"movie":{"title":"The Godfather","year":1972,"ids":{"trakt":1,"slug":"the-godfather-1972","imdb":"tt0068646","tmdb":238}}},
        {"type":"movie","score":500,"movie":{"title":"The Godfather Part II","year":1974,"ids":{"trakt":2,"imdb":"tt0071562","tmdb":240}}},
        {"type":"movie","score":100,"movie":{"title":"Sem TMDB","year":1972,"ids":{"trakt":3,"tmdb":null}}}
    ]"""

    @Test fun buscaSoComIdDoTmdb() {
        val l = TraktDetails.lerBusca(busca, serie = false)
        assertEquals(listOf(238, 240), l.map { it.tmdb })
        assertEquals(1, l[0].trakt)
        assertEquals(1972, l[0].ano)
        val c = InfoExterna.escolher(TraktDetails.candidatos(l), "The Godfather", 1972)
        assertEquals(238, c?.id)
        assertTrue(TraktDetails.lerBusca("lixo", false).isEmpty())
    }

    @Test fun nomesDublaoEOriginal() {
        val pt = "Lei e Ordem: Unidade de Vítimas Especiais"
        assertEquals(listOf(
            TraktDetails.Busca("$pt - Law & Order", "$pt - Law & Order", true),
            TraktDetails.Busca(pt, pt, true),
            TraktDetails.Busca("Law & Order", pt, false),   // o original só casa pela tradução da parte dublada
        ), TraktDetails.nomesDeBusca("$pt - Law & Order"))
        assertEquals(listOf(TraktDetails.Busca("Duna", "Duna", true)), TraktDetails.nomesDeBusca("Duna"))
        assertEquals(1, TraktDetails.nomesDeBusca("X - Y").size)   // parte curta demais: só o inteiro
    }

    @Test fun tituloComEComercial() {
        assertTrue(TraktDetails.mesmoTitulo("Lei & Ordem: Unidade de Vítimas Especiais", "Lei e Ordem: Unidade de Vítimas Especiais"))
        assertTrue(TraktDetails.mesmoTitulo("Law & Order", "Law and Order"))
        assertFalse(TraktDetails.mesmoTitulo("Lei & Ordem", "Lei e Ordem: Unidade de Vítimas Especiais"))
        assertFalse(TraktDetails.mesmoTitulo("&", "e"))
    }

    @Test fun enderecos() {
        val u = TraktDetails.urlBusca("O Poderoso Chefão", 1972, false)
        assertTrue(u, u.startsWith(ServicosFilmes.TRAKT_API + "/search/movie?query=O%20Poderoso%20Chef%C3%A3o"))
        assertTrue(u, u.endsWith("&years=1971-1973"))
        assertEquals(ServicosFilmes.TRAKT_API + "/search/tmdb/238?type=movie&extended=full", TraktDetails.urlPorTmdb(238, false))
        assertEquals(ServicosFilmes.TRAKT_API + "/shows/9/translations/pt", TraktDetails.urlTraducao(9, true, "pt"))
        assertEquals(("pt" to "br"), TraktDetails.idioma(Locale("pt", "BR")))
    }

    @Test fun traducaoDoPais() {
        val j = """[{"title":"O Padrinho","overview":"PT-PT","language":"pt","country":"pt"},
                    {"title":"O Poderoso Chefão","overview":"Resumo BR","language":"pt","country":"br"}]"""
        assertEquals("O Poderoso Chefão" to "Resumo BR", TraktDetails.lerTraducao(j, "br"))
        assertEquals("O Padrinho" to "PT-PT", TraktDetails.lerTraducao(j, "ao"))
        assertNull(TraktDetails.lerTraducao("[]", "br"))
    }

    @Test fun trailerSoDoYoutube() {
        assertEquals("sY1S34973zA", TraktDetails.trailer("https://youtube.com/watch?v=sY1S34973zA"))
        assertEquals("sY1S34973zA", TraktDetails.trailer("https://youtu.be/sY1S34973zA"))
        assertEquals("", TraktDetails.trailer("https://vimeo.com/123"))
        assertEquals("", TraktDetails.trailer(null))
    }

    @Test fun pessoas() {
        val j = """{"cast":[{"character":"Don","person":{"name":"Marlon Brando"}},{"characters":["Michael"],"person":{"name":"Al Pacino"}}],
                    "crew":{"directing":[{"jobs":["Director"],"person":{"name":"Francis Ford Coppola"}},{"job":"Assistant Director","person":{"name":"X"}}]}}"""
        val (elenco, diretor) = TraktDetails.lerPessoas(j)
        assertEquals(listOf("Marlon Brando", "Al Pacino"), elenco)
        assertEquals("Francis Ford Coppola", diretor)
        assertEquals(emptyList<String>() to "", TraktDetails.lerPessoas("lixo"))
    }

    @Test fun proximoEpisodioNoDiaLocal() {
        val j = """{"season":2,"number":5,"title":"x","first_aired":"2026-11-20T01:00:00.000Z"}"""
        val p = TraktDetails.lerProximo(j, TimeZone.getTimeZone("America/Sao_Paulo"))!!
        assertEquals("2026-11-19", p.data)   // 01:00 UTC = 22:00 do dia anterior em Brasília
        assertEquals(2, p.temporada); assertEquals(5, p.numero)
        assertNull(TraktDetails.lerProximo(""))
        assertEquals(2, TraktDetails.lerTemporadas("""[{"number":0},{"number":1},{"number":2}]"""))
    }

    @Test fun detalheComTraducao() {
        val item = JSONObject("""{"title":"Severance","overview":"English","status":"returning series","trailer":"https://youtube.com/watch?v=xEQP4VVuyrY","ids":{"trakt":9,"imdb":"tt11280740","tmdb":95396}}""")
        val d = TraktDetails.detalhe(95396, item, true, "Ruptura" to "Resumo em português", listOf("Adam Scott") to "", null, 2)
        assertEquals("Resumo em português", d.sinopse)
        assertEquals("xEQP4VVuyrY", d.trailer)
        assertEquals("tt11280740", d.imdb)
        assertTrue(d.noAr)
        assertEquals(2, d.temporadas)
        assertEquals("", d.poster)   // o Trakt proíbe carregar a imagem do CDN dele
        val f = TraktDetails.detalhe(238, JSONObject("""{"overview":"EN","status":"released"}"""), false, null, emptyList<String>() to "Coppola", null, 0)
        assertEquals("EN", f.sinopse); assertEquals("Coppola", f.diretor); assertFalse(f.noAr)
    }

    @Test fun idadeAmericana() {
        assertEquals(14, TraktDetails.idade("""[{"type":"movie","movie":{"certification":"PG-13"}}]""", false))
        assertEquals(18, TraktDetails.idade("""[{"type":"show","show":{"certification":"TV-MA"}}]""", true))
        assertEquals(Classificacao.SEM, TraktDetails.idade("[]", false))
        assertNull(TraktDetails.idade("lixo", false))
    }

    @Test fun episodio() {
        assertEquals("Título PT" to "Resumo PT", TraktDetails.lerEpisodio("""{"title":"EN","overview":"Sum"}""", "Título PT" to "Resumo PT"))
        assertEquals("EN" to "Sum", TraktDetails.lerEpisodio("""{"title":"EN","overview":"Sum"}""", null))
        assertNull(TraktDetails.lerEpisodio("""{"title":"EN","overview":""}""", null))
    }

    @Test fun campoNullNaoViraPalavraNull() {
        val o = org.json.JSONObject("""{"overview":null,"title":"T"}""")
        assertEquals("", TraktDetails.txt(o, "overview"))
        assertEquals("T", TraktDetails.txt(o, "title"))
        assertEquals("", TraktDetails.txt(null, "title"))
        assertEquals("Título" to "", TraktDetails.lerTraducao("""[{"country":"br","title":"Título","overview":null}]""", "br"))
    }
}
