package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.Filme
import br.paulo.tvlauncher.player.FilmesFiltro
import br.paulo.tvlauncher.player.InfoExterna
import br.paulo.tvlauncher.player.InfoExterna.Candidato
import br.paulo.tvlauncher.player.OndeAssistir
import br.paulo.tvlauncher.player.ServicosFilmes
import br.paulo.tvlauncher.player.Vod
import java.time.LocalDate
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun diretorVemDoTmdbEFicaGuardado() {
        // Getúlio (2014): o provedor dizia "Gigi Soares"; o TMDB tem João Jardim no crew.
        val f = InfoExterna.lerDetalhe("""{"id":10,"credits":{"cast":[{"name":"Tony Ramos"}],"crew":[
            {"name":"George Moura","job":"Screenplay"},{"name":"João Jardim","job":"Director"},{"name":"João Jardim","job":"Director"}]}}""", false)!!
        assertEquals("João Jardim", f.diretor)
        val g = InfoExterna.guardadoDeJson(InfoExterna.guardadoParaJson(InfoExterna.Guardado(tmdb = 10, detalhe = f, detalheEm = 1L)))
        assertEquals("João Jardim", g.detalhe?.diretor)
        assertEquals("", InfoExterna.lerDetalhe("""{"id":1,"credits":{"crew":[{"name":"X","job":"Director"}]}}""", serie = true)!!.diretor)
    }

    @Test fun trailerDoTmdb() {
        val v = org.json.JSONArray("""[
            {"site":"YouTube","type":"Featurette","key":"abcdefgh1","iso_639_1":"pt"},
            {"site":"Vimeo","type":"Trailer","key":"vimeo12345","iso_639_1":"pt"},
            {"site":"YouTube","type":"Teaser","key":"teaserPT01","iso_639_1":"pt","official":true},
            {"site":"YouTube","type":"Trailer","key":"trailerEN1","iso_639_1":"en","official":true},
            {"site":"YouTube","type":"Trailer","key":"trailerPTf","iso_639_1":"pt","official":false},
            {"site":"YouTube","type":"Trailer","key":"bad key/../x","iso_639_1":"pt","official":true}]""")
        // Trailer em português (mesmo de fã) antes do inglês oficial; Teaser e Featurette depois; chave estranha fora.
        assertEquals("trailerPTf", InfoExterna.escolherTrailer(v, "pt-BR"))
        assertEquals("trailerEN1", InfoExterna.escolherTrailer(v, "en-US"))
        assertEquals("", InfoExterna.escolherTrailer(null, "pt-BR"))
        val d = InfoExterna.lerDetalhe("""{"id":5,"videos":{"results":[{"site":"YouTube","type":"Trailer","key":"trailerEN1","iso_639_1":"en"}]}}""", false)!!
        assertEquals("https://www.youtube.com/watch?v=trailerEN1", d.linkTrailer)
        // Ficha guardada antes do trailer: os dados ficam, mas a data zera para pedir de novo.
        val antigo = org.json.JSONObject("""{"t":5,"d":{"i":"","s":"x"},"de":1000}""")
        assertEquals(0L, InfoExterna.guardadoDeJson(antigo).detalheEm)
        assertEquals("x", InfoExterna.guardadoDeJson(antigo).detalhe?.sinopse)
    }

    @Test fun sagaNaLista() {
        val partes = InfoExterna.lerColecao("""{"parts":[{"id":2,"title":"Duna: Parte Dois","original_title":"Dune: Part Two","release_date":"2024-02-27"},
            {"id":438631,"title":"Duna","original_title":"Dune","release_date":"2021-09-15"},{"id":3,"title":"Duna: Messias","release_date":"2026-12-16"}]}""")
        assertEquals(listOf(438631, 2, 3), partes.map { it.id })
        // Um pelo id (a lista mandou), outro pelo nome + ano.
        assertEquals(2 to 3, InfoExterna.contarNaLista(partes, setOf(438631), setOf(InfoExterna.chaveNomeAno("Duna: Parte Dois", 2024))))
    }

    /** Formatos de api.simkl.org/api-reference/trending (lista direta) e /calendar (v2: calendar + metadata). */
    @Test fun arquivosProntosDoSimkl() {
        val trending = """[{"title":"The Drama","ids":{"simkl_id":2533163,"slug":"the-drama","imdb":"tt33071426","tmdb":"1148540"},
            "ratings":{"simkl":{"rating":7.37,"votes":136},"imdb":{"rating":7.4,"votes":42928}}},
            {"title":"Sem TMDB","ids":{"simkl_id":5}},
            {"title":"Poucos votos","ids":{"simkl_id":6,"tmdb":"77"},"ratings":{"simkl":{"rating":9.9,"votes":2}}}]"""
        val f = InfoExterna.lerArquivoSimkl(trending, serie = false)
        assertEquals(2, f.size)
        assertEquals(InfoExterna.SimklDoArquivo(2533163, 7.37f), f[false to 1148540])
        assertEquals(InfoExterna.SimklDoArquivo(6, null), f[false to 77])
        val calendario = """{"calendar":[{"simkl_id":3437,"date":"2026-07-20T04:00:00Z"}],"metadata":{
            "3437":{"title":"King of the Hill","ids":{"simkl_id":3437,"tmdb":"1434","tvdb":"73141"},"ratings":{"simkl":{"rating":8.1,"votes":942}}},
            "9":{"title":"Filme de anime","anime_type":"movie","ids":{"simkl_id":9,"tmdb":"500"},"ratings":{"simkl":{"rating":null,"votes":null}}}}}"""
        val s = InfoExterna.lerArquivoSimkl(calendario, serie = true)
        assertEquals(InfoExterna.SimklDoArquivo(3437, 8.1f), s[true to 1434])
        assertEquals(InfoExterna.SimklDoArquivo(9, null), s[false to 500])   // anime_type movie = filme
        assertTrue(InfoExterna.lerArquivoSimkl("lixo", serie = true).isEmpty())
        assertEquals("https://data.simkl.in/calendar/v2/tv.json?client_id=c&app-name=sofaly&app-version=1.0.1",
            ServicosFilmes.simklUrlArquivo("calendar/v2/tv.json", "c", 1))
    }

    @Test fun notaDoSimkl() {
        val ficha = """{"title":"Inception","ratings":{"simkl":{"rating":8.6,"votes":15265},"imdb":{"rating":8.8,"votes":2889060}}}"""
        assertEquals(8.6f, InfoExterna.lerNotaSimkl(ficha)!!, 0.001f)
        assertNull(InfoExterna.lerNotaSimkl("""{"ratings":{"imdb":{"rating":8.8,"votes":2889060}}}"""))   // o do IMDb nunca entra
        assertNull(InfoExterna.lerNotaSimkl("""{"ratings":{"simkl":{"rating":9.9,"votes":3}}}"""))   // poucos votos
        assertNull(InfoExterna.lerNotaSimkl("lixo"))
        assertEquals(472214, InfoExterna.idSimklDoLocal("https://simkl.com/movies/472214/inception", serie = false))
        assertEquals(17465, InfoExterna.idSimklDoLocal("https://simkl.com/tv/17465/game-of-thrones", serie = true))
        assertEquals(37226, InfoExterna.idSimklDoLocal("https://simkl.com/anime/37226/sword-art-online", serie = true))
        assertNull(InfoExterna.idSimklDoLocal("https://simkl.com/tv/17465/x", serie = false))   // pediu filme, veio série
        assertNull(InfoExterna.idSimklDoLocal("https://simkl.com/movies/1/x", serie = true))
        assertNull(InfoExterna.idSimklDoLocal(null, serie = false)); assertNull(InfoExterna.idSimklDoLocal("https://simkl.com/", serie = false))
        val u = InfoExterna.urlSimklRedirect(1399, serie = true, clienteId = "c", versao = 1)
        assertTrue(u, u.startsWith("https://api.simkl.com/redirect?client_id=c&app-name=sofaly&app-version=1.0.1&"))
        assertTrue(u, u.endsWith("&to=simkl&type=tv&tmdb=1399"))
        assertTrue(InfoExterna.urlSimklFicha(27205, serie = false, clienteId = "c", versao = 1).startsWith("https://api.simkl.com/movies/27205?"))
        // As duas notas: estrela = média; texto como em cada site.
        val n = InfoExterna.Notas(trakt = 8.0f, simkl = 8.6f)
        assertEquals("Trakt 80%  ·  Simkl 8,6", InfoExterna.textoNotas(n, Locale("pt", "BR")))
        assertEquals("Trakt 80%  ·  Simkl 8.6", InfoExterna.textoNotas(n, Locale.US))
        assertEquals(8.3f, n.media!!, 0.001f)
        assertEquals("Simkl 7.0", InfoExterna.textoNotas(InfoExterna.Notas(simkl = 7f), Locale.US))
    }

    @Test fun notasSoDoTrakt() {
        // Rotten Tomatoes, Metacritic e Letterboxd saíram (16/11/2026, licença comercial): Trakt (e Simkl, acima).
        val n = InfoExterna.Notas(trakt = 8.0f)
        assertEquals("Trakt 80%", InfoExterna.textoNotas(n, Locale("pt", "BR")))
        assertEquals(8.0f, n.de0a10!!, 0.001f)
        assertTrue(InfoExterna.Notas().vazia); assertNull(InfoExterna.Notas().media)
        assertEquals("", InfoExterna.textoNotas(InfoExterna.Notas()))
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
        val g = InfoExterna.Guardado(tmdb = 10, imdb = "tt0111161", traktNota = 8.7f, traktEm = 300, simkl = 55, simklNota = 7.9f, simklEm = 400, recusados = setOf(3, 4),
            detalhe = InfoExterna.Detalhe(10, "tt1", "sinopse", listOf("A", "B"), listOf("Drama"), InfoExterna.Colecao(7, "Saga"), InfoExterna.ProximoEp("2026-11-21", 1, 2), true, "abcdefgh12"),
            detalheEm = 100)
        assertEquals(8.7f, InfoExterna.notasDe(g).trakt); assertEquals(7.9f, InfoExterna.notasDe(g).simkl)
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

    @Test fun notaDoTrakt() {
        val filme = """[{"type":"movie","score":1000,"movie":{"title":"Fight Club","year":1999,"rating":8.6,"votes":40000,"ids":{"trakt":432,"tmdb":550}}}]"""
        assertEquals(8.6f, InfoExterna.lerNotaTrakt(filme, serie = false)!!, 0.001f)
        assertNull(InfoExterna.lerNotaTrakt(filme, serie = true))   // pediu série, veio filme
        assertNull(InfoExterna.lerNotaTrakt("""[{"type":"show","show":{"rating":9.0,"votes":3}}]""", serie = true))   // poucos votos
        assertNull(InfoExterna.lerNotaTrakt("""[{"type":"show","show":{"rating":0,"votes":300}}]""", serie = true))
        assertNull(InfoExterna.lerNotaTrakt("[]", serie = false)); assertNull(InfoExterna.lerNotaTrakt("lixo", serie = false))
        assertEquals("https://api.trakt.tv/search/tmdb/1399?type=show&extended=full", InfoExterna.urlTraktPorTmdb(1399, serie = true))
        assertTrue(InfoExterna.idImdbValido("tt0111161")); assertFalse(InfoExterna.idImdbValido("-"))
        // Guardado de versão antiga, com a nota do arquivo do IMDb ("iv") e as do MDBList ("n"): nada disso volta.
        val velho = InfoExterna.guardadoDeJson(org.json.JSONObject("""{"t":10,"im":"tt0111161","iv":9.3,"n":{"i":8.0,"r":90},"ne":5}"""))
        assertNull(velho.traktNota); assertTrue(InfoExterna.notasDe(velho).vazia)
        val json = InfoExterna.guardadoParaJson(velho).toString()
        assertFalse(json.contains("\"iv\"")); assertFalse(json.contains("\"n\""))
    }

    @Test fun ondeAssistirDesligado() {
        // JustWatch (via TMDB) só com contrato: fica desligado até ter resposta por escrito (16/11/2026).
        assertFalse(OndeAssistir.JUSTWATCH_LIBERADO)
    }

    @Test fun candidatoSemTitulo() {
        assertNull(InfoExterna.escolher(listOf(Candidato(1, "", "", 2020, 1.0)), "", 2020))
    }
}
