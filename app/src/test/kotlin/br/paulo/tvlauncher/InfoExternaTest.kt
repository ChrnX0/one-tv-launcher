package br.paulo.tvlauncher

import br.paulo.tvlauncher.InfoExterna.Candidato
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.util.Locale

/** Respostas gravadas no formato das documentações do TMDB e do MDBList (schema de api.mdblist.com/schema). */
class InfoExternaTest {
    @Test fun idDoXtream() {
        assertEquals(438631, InfoExterna.tmdbDoXtream(JSONObject("""{"tmdb":"438631"}""")))
        assertEquals(1396, InfoExterna.tmdbDoXtream(JSONObject("""{"tmdb_id":1396}""")))
        assertNull(InfoExterna.tmdbDoXtream(JSONObject("""{"tmdb":""}""")))
        assertNull(InfoExterna.tmdbDoXtream(JSONObject("""{"tmdb":"abc"}""")))
        assertNull(InfoExterna.tmdbDoXtream(JSONObject("""{"tmdb":0}""")))
    }

    @Test fun chaveV3NoEnderecoV4NoCabecalho() {
        val v3 = "0123456789abcdef0123456789abcdef"
        assertTrue(InfoExterna.urlBusca(v3, "Duna", 2021, false, "pt-BR").contains("api_key=$v3"))
        assertTrue(InfoExterna.urlBusca(v3, "Duna", 2021, false, "pt-BR").contains("year=2021"))
        assertTrue(InfoExterna.urlBusca(v3, "Round 6", 2021, true, "pt-BR").contains("first_air_date_year=2021"))
        val v4 = "eyJ" + "a".repeat(200)
        assertFalse(InfoExterna.urlBusca(v4, "Duna", null, false, "pt-BR").contains("api_key"))
        assertEquals("Bearer $v4", InfoExterna.tmdbCabecalhos(v4, "x")["Authorization"])
        assertNull(InfoExterna.tmdbCabecalhos(v3, "x")["Authorization"])
        assertEquals("pt-BR", InfoExterna.idiomaTmdb(Locale("pt", "BR")))
        assertEquals("pt-BR", InfoExterna.idiomaTmdb(Locale("pt")))
        assertEquals("en-US", InfoExterna.idiomaTmdb(Locale("en")))
    }

    private val busca = """{"page":1,"results":[
        {"id":841,"title":"Duna","original_title":"Dune","release_date":"1984-12-14","popularity":20.1},
        {"id":438631,"title":"Duna","original_title":"Dune","release_date":"2021-09-15","popularity":150.3},
        {"id":999,"title":"Dunas de Areia","original_title":"Sand","release_date":"2021-01-01","popularity":900.0}]}"""

    @Test fun escolheOMesmoNomeEAno() {
        val c = InfoExterna.lerBusca(busca, false)
        assertEquals(3, c.size)
        assertEquals(438631, InfoExterna.escolher(c, "Duna (2021) [L]", 2021)?.id)
        assertEquals(841, InfoExterna.escolher(c, "Duna", 1984)?.id)
        assertEquals(438631, InfoExterna.escolher(c, "Dune", 2022)?.id)     // pelo original, ano com 1 de diferença
        assertNull(InfoExterna.escolher(c, "Duna", 2000))                   // ano longe: melhor sem ficha
        assertEquals(438631, InfoExterna.escolher(c, "Duna", null)?.id)     // sem ano: o mais popular que casa
        assertEquals(841, InfoExterna.escolher(c, "Duna", null, recusados = setOf(438631))?.id)
        assertNull(InfoExterna.escolher(c, "Outro filme", 2021))
    }

    @Test fun detalheDoFilmeEDaSerie() {
        val f = InfoExterna.lerDetalhe("""{"id":438631,"imdb_id":"tt1160419","overview":"Paul Atreides...","genres":[{"id":1,"name":"Ficção científica"}],
            "belongs_to_collection":{"id":726871,"name":"Duna: Coleção"},"credits":{"cast":[{"name":"Timothée Chalamet"},{"name":"Rebecca Ferguson"}]},
            "external_ids":{"imdb_id":"tt1160419"}}""", serie = false)!!
        assertEquals("tt1160419", f.imdb); assertEquals(listOf("Timothée Chalamet", "Rebecca Ferguson"), f.elenco)
        assertEquals(726871, f.colecao?.id); assertFalse(f.noAr)
        val s = InfoExterna.lerDetalhe("""{"id":1396,"overview":"","in_production":true,"external_ids":{"imdb_id":"tt0903747"},
            "next_episode_to_air":{"air_date":"2026-11-21","season_number":2,"episode_number":5},"aggregate_credits":{"cast":[{"name":"A"}]}}""", serie = true)!!
        assertEquals(InfoExterna.ProximoEp("2026-11-21", 2, 5), s.proximo); assertTrue(s.noAr); assertEquals(listOf("A"), s.elenco)
        assertNull(InfoExterna.lerDetalhe("{}", false))
    }

    @Test fun sagaNaLista() {
        val partes = InfoExterna.lerColecao("""{"parts":[{"id":2,"title":"Duna: Parte Dois","original_title":"Dune: Part Two","release_date":"2024-02-27"},
            {"id":438631,"title":"Duna","original_title":"Dune","release_date":"2021-09-15"},{"id":3,"title":"Duna: Messias","release_date":"2026-12-16"}]}""")
        assertEquals(listOf(438631, 2, 3), partes.map { it.id })
        // Um pelo id (a lista mandou), outro pelo nome + ano.
        assertEquals(2 to 3, InfoExterna.contarNaLista(partes, setOf(438631), setOf(InfoExterna.chaveNomeAno("Duna: Parte Dois", 2024))))
    }

    @Test fun notasDoMdblist() {
        val lote = """[{"title":"Dune","ids":{"imdb":"tt1160419","tmdb":438631},"ratings":[
            {"source":"imdb","value":8.0,"score":80,"votes":900000},{"source":"tomatoes","value":83},{"source":"metacritic","value":74},
            {"source":"letterboxd","value":3.9},{"source":"trakt","value":82},{"source":"rogerebert","value":null}]},
            {"title":"Sem notas","ids":{"tmdb":5},"ratings":[]},{"title":"sem id","ratings":[]}]"""
        val m = InfoExterna.lerLote(lote)
        assertEquals(setOf(438631, 5), m.keys)
        val n = m[438631]!!
        assertEquals(8.0f, n.imdb); assertEquals(83, n.rt); assertEquals(74, n.metacritic); assertEquals(3.9f, n.letterboxd)
        assertTrue(m[5]!!.vazia)
        assertEquals("IMDb 8,0  ·  Rotten Tomatoes 83%  ·  Metacritic 74  ·  Letterboxd 3,9", InfoExterna.textoNotas(n, Locale("pt", "BR")))
        assertEquals(8.0f, n.de0a10)
        assertEquals(7.8f, InfoExterna.Notas(letterboxd = 3.9f).de0a10!!, 0.001f)
        assertEquals("""{"ids":[1,2]}""", InfoExterna.corpoLoteMdblist(listOf(1, 2)))
        assertEquals("https://api.mdblist.com/tmdb/show/?apikey=k", InfoExterna.urlLoteMdblist("k", true))
    }

    @Test fun validade() {
        val dia = 86_400_000L
        assertTrue(InfoExterna.vencido(0, 10 * dia))
        assertFalse(InfoExterna.vencido(dia, 10 * dia))
        assertTrue(InfoExterna.vencido(dia, 40 * dia))
        assertTrue(InfoExterna.vencido(dia, 5 * dia, noAr = true))
        assertTrue(InfoExterna.vencido(10 * dia, dia))   // relógio voltou: pede de novo
    }

    @Test fun guardadoIdaEVolta() {
        val g = InfoExterna.Guardado(tmdb = 10, imdb = "tt0111161", imdbNota = 9.3f, recusados = setOf(3, 4),
            detalhe = InfoExterna.Detalhe(10, "tt1", "sinopse", listOf("A", "B"), listOf("Drama"), InfoExterna.Colecao(7, "Saga"), InfoExterna.ProximoEp("2026-11-21", 1, 2), true),
            detalheEm = 100, notas = InfoExterna.Notas(null, 90, null, 3.5f), notasEm = 200)
        assertEquals(9.3f, InfoExterna.notasDe(g).imdb)   // sem IMDb do MDBList, vale o do arquivo público
        assertEquals(7f, InfoExterna.notasDe(g.copy(notas = InfoExterna.Notas(imdb = 7f))).imdb)
        assertEquals(g, InfoExterna.guardadoDeJson(InfoExterna.guardadoParaJson(g)))
        val vazio = InfoExterna.Guardado(recusados = setOf(9), buscaEm = 5)
        assertEquals(vazio, InfoExterna.guardadoDeJson(InfoExterna.guardadoParaJson(vazio)))
    }

    @Test fun proximoEpisodio() {
        val hoje = LocalDate.of(2026, 11, 14)   // sábado
        val pt = Locale("pt", "BR")
        val m = "Novo episódio %1\$s (T%2\$dE%3\$d)"
        assertEquals("Novo episódio sexta-feira, 20/11 (T2E5)", InfoExterna.textoProximo(InfoExterna.ProximoEp("2026-11-20", 2, 5), hoje, m, "hoje", "amanhã", pt))
        assertEquals("Novo episódio hoje (T1E1)", InfoExterna.textoProximo(InfoExterna.ProximoEp("2026-11-14", 1, 1), hoje, m, "hoje", "amanhã", pt))
        assertEquals("Novo episódio amanhã (T1E1)", InfoExterna.textoProximo(InfoExterna.ProximoEp("2026-11-15", 1, 1), hoje, m, "hoje", "amanhã", pt))
        assertNull(InfoExterna.textoProximo(InfoExterna.ProximoEp("2026-11-01", 1, 1), hoje, m, "hoje", "amanhã", pt))
        // Versão curta da etiqueta do pôster
        assertEquals("hoje", InfoExterna.quandoCurto(InfoExterna.ProximoEp("2026-11-14", 1, 1), hoje, "hoje", "amanhã", pt))
        assertEquals("amanhã", InfoExterna.quandoCurto(InfoExterna.ProximoEp("2026-11-15", 1, 1), hoje, "hoje", "amanhã", pt))
        assertEquals("sex., 20/11", InfoExterna.quandoCurto(InfoExterna.ProximoEp("2026-11-20", 2, 5), hoje, "hoje", "amanhã", pt))
        assertEquals("Fri, 11/20", InfoExterna.quandoCurto(InfoExterna.ProximoEp("2026-11-20", 2, 5), hoje, "today", "tomorrow", java.util.Locale.US))
        assertNull(InfoExterna.quandoCurto(InfoExterna.ProximoEp("2026-11-01", 1, 1), hoje, "hoje", "amanhã", pt))
        assertNull(InfoExterna.quandoCurto(InfoExterna.ProximoEp("lixo", 1, 1), hoje, "hoje", "amanhã", pt))
    }

    @Test fun filtroUsaANotaDeFora() {
        val a = Filme("1", "f", "A", nota = 5f); val b = Filme("2", "f", "B", nota = 9f)
        val c = FilmesFiltro.Criterios(notaMin = 7)
        assertEquals(listOf(b), FilmesFiltro.filtrar(listOf(a, b), c, emptyMap()))
        assertEquals(listOf(a), FilmesFiltro.filtrar(listOf(a, b), c, emptyMap(), mapOf(a.chave to 8f, b.chave to 6f)))
        assertEquals(listOf(a, b), Vod.ordenar(listOf(b, a), Vod.Ordem.NOTA, mapOf(a.chave to 9.5f)))
    }

    @Test fun arquivoDoImdb() {
        val linhas = sequenceOf("tconst\taverageRating\tnumVotes", "tt0000001\t5.7\t2240", "tt0111161\t9.3\t3000000", "tt0000009\t5.0\t10", "lixo")
        assertEquals(mapOf("tt0111161" to 9.3f), InfoExterna.filtrarImdb(linhas, setOf("tt0111161", "tt0000009")))   // 10 votos: fora
        assertNull(InfoExterna.lerLinhaImdb("tconst\taverageRating\tnumVotes"))
        assertTrue(InfoExterna.idImdbValido("tt0111161")); assertFalse(InfoExterna.idImdbValido("-"))
        assertEquals("tt0111161", InfoExterna.lerIdImdb("""{"id":278,"imdb_id":"tt0111161"}"""))
        assertNull(InfoExterna.lerIdImdb("""{"id":1,"imdb_id":null}"""))
    }

    @Test fun candidatoSemTitulo() {
        assertNull(InfoExterna.escolher(listOf(Candidato(1, "", "", 2020, 1.0)), "", 2020))
    }
}
