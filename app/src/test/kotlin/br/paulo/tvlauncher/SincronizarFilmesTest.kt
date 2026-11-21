package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.InfoExterna
import br.paulo.tvlauncher.player.Posicao
import br.paulo.tvlauncher.player.ServicosFilmes
import br.paulo.tvlauncher.player.SincronizarFilmes
import br.paulo.tvlauncher.player.SincronizarFilmes.AcaoFim
import br.paulo.tvlauncher.player.SincronizarFilmes.Ep
import br.paulo.tvlauncher.player.SincronizarFilmes.Ids
import br.paulo.tvlauncher.player.SincronizarFilmes.Item
import br.paulo.tvlauncher.player.SincronizarFilmes.Tipo
import br.paulo.tvlauncher.player.SincronizarFilmes.Titulo
import br.paulo.tvlauncher.player.Vod
import br.paulo.tvlauncher.player.item
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Respostas no formato das documentações do Trakt (trakt.docs.apiary.io), Simkl (api.simkl.org) e MDBList (schema). */
class SincronizarFilmesTest {
    private fun filme(tmdb: Int, nome: String = "", ano: Int? = null) = Item(Tipo.FILME, Ids(tmdb = tmdb), nome, ano)

    @Test fun watchlistDoTrakt() {
        val itens = SincronizarFilmes.traktLerItens("""[
            {"rank":1,"id":101,"listed_at":"2014-09-01T09:10:11.000Z","type":"movie","movie":{"title":"TRON: Legacy","year":2010,"ids":{"trakt":1,"slug":"tron-legacy-2010","imdb":"tt1104001","tmdb":20526}}},
            {"rank":2,"type":"show","show":{"title":"Breaking Bad","year":2008,"ids":{"trakt":1,"tvdb":81189,"imdb":"tt0903747","tmdb":1396}}},
            {"rank":3,"type":"season","season":{"number":1,"ids":{"tmdb":3572}}},
            {"rank":4,"type":"person","person":{"name":"x","ids":{"tmdb":1}}}]""")
        assertEquals(2, itens!!.size)
        assertEquals(Item(Tipo.FILME, Ids(20526, "tt1104001", 1), "TRON: Legacy", 2010), itens[0])
        assertEquals(Tipo.SERIE, itens[1].tipo); assertEquals(1396, itens[1].ids.tmdb)
        assertNull(SincronizarFilmes.traktLerItens("lixo"))   // ilegível: null (vazia apagaria os Interesses)
        assertNull(SincronizarFilmes.traktLerVistos("[{\"plays\":1", Tipo.FILME))   // cortado no meio
        assertTrue(SincronizarFilmes.traktLerItens("[]")!!.isEmpty())
    }

    @Test fun vistosDoTrakt() {
        val f = SincronizarFilmes.traktLerVistos("""[{"plays":4,"last_watched_at":"2014-10-11T17:00:54.000Z","movie":{"title":"Batman Begins","year":2005,"ids":{"trakt":6,"imdb":"tt0372784","tmdb":272}}}]""", Tipo.FILME)
        assertEquals(272, f!!.single().item.ids.tmdb); assertEquals(1413046854000, f.single().quandoMs)
        val s = SincronizarFilmes.traktLerVistos("""[{"plays":56,"last_watched_at":"2014-10-11T17:00:54.000Z","reset_at":"2014-10-01T00:00:00.000Z",
            "show":{"title":"Breaking Bad","year":2008,"ids":{"trakt":1,"tmdb":1396}},
            "seasons":[{"number":1,"episodes":[{"number":1,"plays":1,"last_watched_at":"2014-10-11T17:00:54.000Z"},
                                              {"number":2,"plays":1,"last_watched_at":"2014-09-11T17:00:54.000Z"}]}]}]""", Tipo.SERIE)
        assertEquals(setOf(Ep(1, 1)), s!!.single().eps)   // o episódio 2 foi visto antes do recomeço (reset_at)
    }

    @Test fun pausasDoTrakt() {
        val p = SincronizarFilmes.traktLerPausas("""[
            {"progress":10.0,"paused_at":"2015-01-25T22:01:32.000Z","id":13,"type":"movie","movie":{"title":"Batman Begins","year":2005,"ids":{"trakt":1,"imdb":"tt0372784","tmdb":272}}},
            {"progress":65.5,"paused_at":"2015-01-25T22:01:32.000Z","id":37,"type":"episode","episode":{"season":0,"number":1,"ids":{"tmdb":62131}},
             "show":{"title":"Breaking Bad","year":2008,"ids":{"trakt":1,"imdb":"tt0903747","tmdb":1396}}}]""")
        assertEquals(2, p.size)
        assertEquals(10f, p[0].progresso); assertNull(p[0].ep); assertEquals(13L, p[0].id)
        assertEquals(Ep(0, 1), p[1].ep); assertEquals(1396, p[1].item.ids.tmdb)
    }

    @Test fun atividades() {
        val a = SincronizarFilmes.traktLerAtividades("""{"all":"2014-11-20T07:01:32.000Z","movies":{"watched_at":"A","paused_at":"B","watchlisted_at":"C"},
            "episodes":{"watched_at":"D"},"shows":{"watchlisted_at":"E"},"watchlist":{"updated_at":"F"},"lists":{"updated_at":"G"}}""")
        assertEquals("A", a["movies.watched_at"]); assertEquals("F", a["watchlist.updated_at"])
        assertTrue(SincronizarFilmes.mudou(null, a, "movies.watched_at"))
        assertFalse(SincronizarFilmes.mudou(a, a, "movies.watched_at"))
        assertTrue(SincronizarFilmes.mudou(a, a + ("movies.watched_at" to "Z"), "movies.watched_at", "episodes.watched_at"))
        val s = SincronizarFilmes.simklLerAtividades("""{"all":"2026-05-08T14:23:11Z","movies":{"plantowatch":"X","completed":"Y"},"tv_shows":{"watching":"W"}}""")
        assertEquals("2026-05-08T14:23:11Z", s["all"]); assertEquals("X", s["movies.plantowatch"]); assertEquals("W", s["tv_shows.watching"])
    }

    @Test fun listasDoTrakt() {
        val l = SincronizarFilmes.traktLerListas("""[{"name":"Star Wars in machete order","item_count":5,"ids":{"trakt":55,"slug":"star-wars-in-machete-order"}},{"name":"","ids":{"trakt":2}}]""")
        assertEquals(listOf(SincronizarFilmes.ListaFora("trakt", "55", "Star Wars in machete order", 5)), l)
        val c = SincronizarFilmes.traktLerCurtidas("""[{"liked_at":"2015-03-31T23:18:42.000Z","type":"list","list":{"name":"Vampires FTW","item_count":5,
            "ids":{"trakt":52,"slug":"vampires-ftw"},"user":{"username":"sean","ids":{"slug":"sean"}}}}]""")
        assertEquals("sean", c.single().dono); assertEquals("trakt:52", c.single().chave)
    }

    @Test fun todosDoSimkl() {
        val e = SincronizarFilmes.simklLerTodos("""{"movies":[{"last_watched_at":null,"status":"plantowatch","movie":{"title":"Inception","year":2010,"ids":{"simkl":53536,"slug":"inception","imdb":"tt1375666","tmdb":"27205"}}}],
            "shows":[{"status":"watching","show":{"title":"The Boys","year":2019,"ids":{"simkl":967226,"tmdb":76479}}}],
            "anime":[{"status":"completed","last_watched_at":"2020-01-01T00:00:00Z","show":{"title":"One Piece","ids":{"simkl":38636}}}]}""")!!
        assertEquals(3, e.size)
        assertEquals(27205, e[0].item.ids.tmdb); assertEquals("plantowatch", e[0].status)   // tmdb como texto também vale
        assertEquals(Tipo.SERIE, e[2].item.tipo); assertEquals(38636, e[2].item.ids.simkl)
    }

    @Test fun listasEItensDoMdblist() {
        val l = SincronizarFilmes.mdblistLerListas("""[{"id":2194,"name":"Top 250 IMDb","slug":"imdb-top-250","items":250,"likes":10}]""")
        assertEquals(SincronizarFilmes.ListaFora("mdblist", "2194", "Top 250 IMDb", 250), l.single())
        assertEquals(1, SincronizarFilmes.mdblistLerListas("""{"lists":[{"id":3,"name":"Oscar","items":96,"user_name":"ana"}]}""").size)
        val i = SincronizarFilmes.mdblistLerItens("""{"movies":[{"rank":2,"title":"The Godfather","imdb_id":"tt0068646","release_year":1972,"ids":{"imdb":"tt0068646","tmdb":238}},
            {"rank":1,"title":"The Shawshank Redemption","imdb_id":"tt0111161","release_year":1994,"ids":{"tmdb":278}}],"shows":[]}""")
        assertEquals(listOf(278, 238), i.map { it.ids.tmdb })
        assertEquals("tt0111161", i[0].ids.imdb)   // veio só em imdb_id
    }

    @Test fun corpos() {
        val tron = Item(Tipo.FILME, Ids(20526, "tt1104001"), "TRON: Legacy", 2010)
        val bb = Item(Tipo.SERIE, Ids(tmdb = 1396), "Breaking Bad")
        val o = JSONObject(SincronizarFilmes.corpoItens(listOf(tron, bb), "plantowatch"))
        assertEquals(20526, o.getJSONArray("movies").getJSONObject(0).getJSONObject("ids").getInt("tmdb"))
        assertEquals("plantowatch", o.getJSONArray("shows").getJSONObject(0).getString("to"))
        val h = JSONObject(SincronizarFilmes.corpoHistorico(listOf(tron to 0L), listOf(Triple(bb, Ep(1, 2), 0L), Triple(bb, Ep(1, 1), 0L))))
        assertEquals("1970-01-01T00:00:00Z", h.getJSONArray("movies").getJSONObject(0).getString("watched_at"))
        val eps = h.getJSONArray("shows").getJSONObject(0).getJSONArray("seasons").getJSONObject(0).getJSONArray("episodes")
        assertEquals(1, eps.getJSONObject(0).getInt("number")); assertEquals(2, eps.length())
        val s = JSONObject(SincronizarFilmes.corpoScrobble(bb, Ep(2, 5), 42.123f))
        assertEquals(42.12, s.getDouble("progress"), 0.001); assertEquals(5, s.getJSONObject("episode").getInt("number"))
        assertTrue(JSONObject(SincronizarFilmes.corpoScrobble(tron, null, 1f)).has("movie"))
    }

    @Test fun decideOFimPelaNossaRegua() {
        assertEquals(AcaoFim.PARAR to 92f, SincronizarFilmes.decidirFim(concluido = true, progresso = 92f))
        assertEquals(AcaoFim.HISTORICO, SincronizarFilmes.decidirFim(true, 70f).first)   // créditos longos: concluído com 70%
        assertEquals(AcaoFim.PAUSAR to 85f, SincronizarFilmes.decidirFim(false, 85f))     // 85% sem concluir: pausa, nunca stop
        assertEquals(AcaoFim.PAUSAR to 99f, SincronizarFilmes.decidirFim(false, 100f))
        assertEquals(AcaoFim.NADA, SincronizarFilmes.decidirFim(false, 0.5f).first)
        assertEquals(50f, SincronizarFilmes.progresso(30_000, 60_000)); assertEquals(0f, SincronizarFilmes.progresso(5, 0))
    }

    @Test fun visitaEInfantilNuncaMandam() {
        assertTrue(SincronizarFilmes.podeEnviar(true, false, false))
        assertFalse(SincronizarFilmes.podeEnviar(true, true, false))
        assertFalse(SincronizarFilmes.podeEnviar(true, false, true))
        assertFalse(SincronizarFilmes.podeEnviar(false, false, false))
    }

    @Test fun interessesPrimeiraVezEUniao() {
        val u = setOf(1, 2, 3, 4)
        val m = SincronizarFilmes.mesclarInteresses(setOf(1, 2), setOf(2, 3), null, enviar = true, universo = u)
        assertEquals(setOf(1, 2, 3), m.local); assertEquals(setOf(1), m.adicionarFora); assertTrue(m.tirarFora.isEmpty())
        assertEquals(setOf(1, 2, 3), m.base)
        // Sem poder mandar: a TV recebe, nada sai, e o 1 continua "só daqui" para subir quando ligar.
        val n = SincronizarFilmes.mesclarInteresses(setOf(1, 2), setOf(2, 3), null, enviar = false, universo = u)
        assertEquals(setOf(1, 2, 3), n.local); assertTrue(n.adicionarFora.isEmpty()); assertEquals(setOf(2, 3), n.base)
    }

    @Test fun interessesNaoRessuscitam() {
        val u = (1..10).toSet()
        val base = setOf(1, 2, 3)
        // Tirei o 1 na TV e alguém tirou o 2 no celular; pus o 4 aqui e o 5 lá.
        val m = SincronizarFilmes.mesclarInteresses(local = setOf(2, 3, 4), remoto = setOf(1, 3, 5), base = base, enviar = true, universo = u)
        assertEquals(setOf(3, 4, 5), m.local)
        assertEquals(setOf(4), m.adicionarFora); assertEquals(setOf(1), m.tirarFora)
        assertEquals(setOf(3, 4, 5), m.base)
        // Na rodada seguinte, sem mudanças, nada se mexe.
        val r2 = SincronizarFilmes.mesclarInteresses(m.local, setOf(3, 4, 5), m.base, true, u)
        assertEquals(m.local, r2.local); assertTrue(r2.adicionarFora.isEmpty() && r2.tirarFora.isEmpty())
    }

    @Test fun interessesForaDoCatalogoFicamQuietos() {
        // O 9 saiu da lista de IPTV (fora do universo): continua nos Interesses e não é tirado lá fora.
        val m = SincronizarFilmes.mesclarInteresses(local = setOf(1, 9), remoto = setOf(1, 9, 50), base = setOf(1, 9), enviar = true, universo = setOf(1, 2))
        assertTrue(m.tirarFora.isEmpty()); assertEquals(setOf(1, 9), m.local); assertTrue(9 in m.base)
    }

    @Test fun vistoEUniao() {
        assertEquals(setOf(3), SincronizarFilmes.vistosParaTrazer(setOf(1, 3), setOf(1, 2)))
        assertEquals(setOf(2), SincronizarFilmes.vistosParaEnviar(setOf(1, 2), setOf(1, 3)))
    }

    @Test fun pausaDeFora() {
        val p = SincronizarFilmes.Pausa(filme(1), null, 40f, 10 * 60_000L)
        assertTrue(SincronizarFilmes.pausaVale(p, null, false))
        assertFalse(SincronizarFilmes.pausaVale(p, null, true))                                  // concluído aqui: ignora
        assertFalse(SincronizarFilmes.pausaVale(p, Posicao(1, 2, 10 * 60_000L), false))           // a nossa é tão nova quanto
        assertTrue(SincronizarFilmes.pausaVale(p, Posicao(1, 2, 0), false))
        assertFalse(SincronizarFilmes.pausaVale(p.copy(progresso = 98f), null, false))           // praticamente no fim
        assertEquals(3_600_000L, SincronizarFilmes.posicaoDaPausa(50f, 7_200_000L))
    }

    @Test fun intervalo() {
        assertTrue(SincronizarFilmes.deveSincronizar(0, 1, false))
        assertFalse(SincronizarFilmes.deveSincronizar(1_000, 1_000 + 60_000, false))
        assertTrue(SincronizarFilmes.deveSincronizar(1_000, 1_000 + SincronizarFilmes.INTERVALO_MS, false))
        assertTrue(SincronizarFilmes.deveSincronizar(1_000, 2_000, true))
        assertTrue(SincronizarFilmes.deveSincronizar(10_000, 5_000, false))   // relógio voltou
    }

    @Test fun cruzaListaComCatalogo() {
        val cat = listOf(Titulo("a", 278, InfoExterna.chaveNomeAno("Um Sonho de Liberdade", 1994)),
            Titulo("b", null, InfoExterna.chaveNomeAno("The Godfather", 1972)), Titulo("c", 999, "x|0"), Titulo("d", 278, "dup|0"))
        val lista = listOf(filme(278, "The Shawshank Redemption", 1994), filme(238, "The Godfather", 1972), filme(1, "Nada", 2000),
            Item(Tipo.SERIE, Ids(tmdb = 999), "Série"))
        val (chaves, casaram) = SincronizarFilmes.cruzar(lista, cat, Tipo.FILME)
        assertEquals(listOf("a", "b"), chaves); assertEquals(2, casaram)
        assertEquals(listOf("c"), SincronizarFilmes.cruzar(lista, cat, Tipo.SERIE).first)
    }

    @Test fun opcoesEListasIdaEVolta() {
        val o = SincronizarFilmes.Opcoes(interesses = true, contar = true)
        assertEquals(o, SincronizarFilmes.opcoesDeJson(SincronizarFilmes.opcoesParaJson(o)))
        assertEquals(SincronizarFilmes.Opcoes(), SincronizarFilmes.opcoesDeJson(null))
        val l = listOf(SincronizarFilmes.ListaFora("trakt", "55", "Machete", 5, "sean"), SincronizarFilmes.ListaFora("mdblist", "2194", "Top 250", 250))
        assertEquals(l, SincronizarFilmes.listasDeJson(SincronizarFilmes.listasParaJson(l)))
        // Nada estranho do disco vira endereço: serviço desconhecido ou id que não é número ficam de fora.
        val ruim = org.json.JSONArray("""[{"s":"x","id":"1"},{"s":"trakt","id":"../a"}]""")
        assertTrue(SincronizarFilmes.listasDeJson(ruim).isEmpty())
        val itens = listOf(filme(1, "A", 2000), Item(Tipo.SERIE, Ids(imdb = "tt0903747"), "B"))
        assertEquals(itens, SincronizarFilmes.itensDeJson(SincronizarFilmes.itensParaJson(itens)))
    }

    // ---------------------------------------------------------------- Simkl: cópia local + delta (guia /sync, 18/11/2026)

    private fun ent(tipo: Tipo, simkl: Int?, tmdb: Int?, status: String) =
        SincronizarFilmes.EntradaSimkl(Item(tipo, Ids(tmdb = tmdb, simkl = simkl)), status, 1L)

    @Test fun simklPlanoSegueOGuia() {
        val foto = mapOf("all" to "A", "movies.removed_from_list" to "R", "movies.playback" to "P")
        // Sem cópia local: fase 1 (tudo), uma vez.
        assertTrue(SincronizarFilmes.simklPlano(null, foto, false, false, false).completo)
        assertTrue(SincronizarFilmes.simklPlano(foto, foto, false, false, false).completo)
        // "all" igual: nada a buscar.
        val nada = SincronizarFilmes.simklPlano(foto, foto, true, true, true)
        assertFalse(nada.completo); assertNull(nada.desde); assertFalse(nada.conferirTirados); assertFalse(nada.pausas)
        // "all" mudou: date_from = o "all" SALVO (não o novo).
        val so = SincronizarFilmes.simklPlano(foto, foto + ("all" to "B"), true, false, false)
        assertEquals("A", so.desde); assertFalse(so.conferirTirados); assertFalse(so.pausas)
        // removed_from_list andou: confere a lista de ids.
        assertTrue(SincronizarFilmes.simklPlano(foto, foto + mapOf("all" to "B", "movies.removed_from_list" to "R2"), true, false, false).conferirTirados)
        // Pausas: só com "continuar", quando o playback andou ou nunca vieram.
        assertTrue(SincronizarFilmes.simklPlano(foto, foto + mapOf("all" to "B", "movies.playback" to "P2"), true, true, true).pausas)
        assertFalse(SincronizarFilmes.simklPlano(foto, foto + mapOf("all" to "B", "movies.playback" to "P2"), true, false, true).pausas)
        assertTrue(SincronizarFilmes.simklPlano(foto, foto, true, true, false).pausas)
    }

    @Test fun simklLerIdsDoSimklIdsOnly() {
        val ids = SincronizarFilmes.simklLerIds("""{"movies":[{"movie":{"ids":{"simkl":53536}}}],"shows":[{"show":{"ids":{"simkl":967226}}}],"anime":[{"show":{"ids":{"simkl":38636}}}]}""")
        assertEquals(setOf(53536, 967226, 38636), ids)
        assertEquals(emptySet<Int>(), SincronizarFilmes.simklLerIds("{}"))
        assertNull(SincronizarFilmes.simklLerIds("<html>"))
    }

    @Test fun simklMesclarTrocaProvisorioEStatus() {
        var c = SincronizarFilmes.simklMesclar(emptyMap(), listOf(ent(Tipo.FILME, 1, 10, "plantowatch"), ent(Tipo.SERIE, 2, 10, "watching")))
        assertEquals(setOf("s1", "s2"), c.keys)   // mesmo tmdb, tipos diferentes: dois títulos
        // Nós pusemos o tmdb 30 na lista: entra provisório; o date_from traz o id do Simkl e o provisório sai.
        c = SincronizarFilmes.simklMarcar(c, Item(Tipo.FILME, Ids(tmdb = 30)), "plantowatch", 5L)
        assertTrue("t30:FILME" in c.keys)
        c = SincronizarFilmes.simklMesclar(c, listOf(ent(Tipo.FILME, 3, 30, "plantowatch"), ent(Tipo.FILME, 1, 10, "completed")))
        assertEquals(setOf("s1", "s2", "s3"), c.keys)
        assertEquals("completed", c.getValue("s1").status)
    }

    @Test fun simklTirarSumidosPoupaProvisorios() {
        var c = SincronizarFilmes.simklMesclar(emptyMap(), listOf(ent(Tipo.FILME, 1, 10, "plantowatch"), ent(Tipo.FILME, 2, 20, "completed")))
        c = SincronizarFilmes.simklMarcar(c, Item(Tipo.FILME, Ids(tmdb = 30)), "plantowatch", 5L)
        c = SincronizarFilmes.simklTirarSumidos(c, setOf(2))
        assertEquals(setOf("s2", "t30:FILME"), c.keys)
    }

    @Test fun simklMarcarTiraEMudaStatus() {
        var c = SincronizarFilmes.simklMesclar(emptyMap(), listOf(ent(Tipo.FILME, 1, 10, "plantowatch"), ent(Tipo.SERIE, 2, 10, "plantowatch")))
        c = SincronizarFilmes.simklMarcar(c, Item(Tipo.FILME, Ids(tmdb = 10)), "completed", 9L)
        assertEquals("completed", c.getValue("s1").status); assertEquals(9L, c.getValue("s1").quandoMs)
        assertEquals("plantowatch", c.getValue("s2").status)   // a série com o mesmo tmdb não muda
        c = SincronizarFilmes.simklMarcar(c, Item(Tipo.SERIE, Ids(tmdb = 10)), null, 9L)
        assertEquals(setOf("s1"), c.keys)
    }

    @Test fun simklBibliotecaIdaEVolta() {
        val c = SincronizarFilmes.simklMesclar(emptyMap(), listOf(
            SincronizarFilmes.EntradaSimkl(Item(Tipo.FILME, Ids(tmdb = 27205, imdb = "tt1375666", simkl = 53536), "Inception", 2010), "plantowatch", 7L),
            ent(Tipo.SERIE, 38636, null, "completed")))
        val volta = SincronizarFilmes.simklBibliotecaDeJson(org.json.JSONArray(SincronizarFilmes.simklBibliotecaParaJson(c).toString()))
        assertEquals(c, volta)
    }

    @Test fun simklFotoAposTirarSoAndaORemoved() {
        val salva = mapOf("all" to "A", "movies.removed_from_list" to "R", "movies.plantowatch" to "P")
        val nova = mapOf("all" to "B", "movies.removed_from_list" to "R2", "movies.plantowatch" to "P2")
        assertEquals(mapOf("all" to "A", "movies.removed_from_list" to "R2", "movies.plantowatch" to "P"), SincronizarFilmes.simklFotoAposTirar(salva, nova))
    }

    @Test fun simklAgenteNomeBarraVersao() {
        assertEquals("sofaly/1.0.457800", ServicosFilmes.simklCabecalhos("abc", 457800, "t")["User-Agent"])
        assertEquals("sofaly/1.0.457800", ServicosFilmes.simklCabecalhosForm(457800)["User-Agent"])
    }

    @Test fun simklLerTodosIlegivelEhNull() {
        assertNull(SincronizarFilmes.simklLerTodos("""{"movies":[{"status":"plantowatch","movie":{"ids":{"simkl":1"""))   // cortado em 2 MB
        assertTrue(SincronizarFilmes.simklLerTodos("{}")!!.isEmpty())
    }

    @Test fun baseSoComOQueOServicoConfirmou() {
        // 300 Interesses aqui, nada lá, sem base: a mescla manda pôr os 300, mas só sobe um lote de 100.
        val local = (1..300).toSet()
        val m = SincronizarFilmes.mesclarInteresses(local, emptySet(), null, true, local)
        val base = SincronizarFilmes.baseAposEnvio(m, (1..100).toSet(), emptySet())
        assertEquals((1..100).toSet(), base)
        // Na volta (lá só os 100): os outros 200 continuam aqui e sobem; nada some da TV.
        val m2 = SincronizarFilmes.mesclarInteresses(local, (1..100).toSet(), base, true, local)
        assertEquals(local, m2.local); assertEquals((101..300).toSet(), m2.adicionarFora)
        // Tirar que falhou (429) fica na base: na próxima vez tenta tirar de novo, não volta à TV.
        val m3 = SincronizarFilmes.mesclarInteresses(setOf(1), setOf(1, 2), setOf(1, 2), true, setOf(1, 2))
        assertEquals(setOf(2), m3.tirarFora)
        val b3 = SincronizarFilmes.baseAposEnvio(m3, emptySet(), emptySet())
        val m4 = SincronizarFilmes.mesclarInteresses(setOf(1), setOf(1, 2), b3, true, setOf(1, 2))
        assertEquals(setOf(1), m4.local); assertEquals(setOf(2), m4.tirarFora)
        // Tudo aceito: igual à base da mescla.
        assertEquals(m.base, SincronizarFilmes.baseAposEnvio(m, m.adicionarFora, m.tirarFora))
    }

    @Test fun idsPorNomeEnsinaOsTitulosSemId() {
        // Catálogo do IPTV: as 3 versões do Oppenheimer vieram sem tmdb; Duna já tem id (não pode mudar).
        val ch = { nome: String, ano: Int? -> InfoExterna.chaveNomeAno(nome, ano) }
        val cat = listOf(Titulo("op", null, ch("Oppenheimer", 2023)), Titulo("op4k", null, ch("Oppenheimer", 2023)),
            Titulo("opleg", null, ch("Oppenheimer", 2023)), Titulo("duna", 438631, ch("Duna", 2021)),
            Titulo("outro", null, ch("Oppenheimer", 1980)))
        val fora = listOf(Item(Tipo.FILME, SincronizarFilmes.Ids(tmdb = 872585), "Oppenheimer", 2023),
            Item(Tipo.FILME, SincronizarFilmes.Ids(tmdb = 999), "Duna", 2021),          // catálogo já tem id: não mexe
            Item(Tipo.FILME, SincronizarFilmes.Ids(simkl = 5), "Sem Id", 2020),         // sem tmdb lá fora: nada a ensinar
            Item(Tipo.FILME, SincronizarFilmes.Ids(tmdb = 438631), "Duna", 2021))       // id que o catálogo já usa: não repete
        val r = SincronizarFilmes.idsPorNome(cat, fora)
        assertEquals(mapOf("op" to 872585, "op4k" to 872585, "opleg" to 872585), r)
        // O nome do catálogo (com ano e marca de versão) dá a mesma chave que o título de fora.
        for (n in listOf("Oppenheimer (2023)", "Oppenheimer (2023) 4K", "Oppenheimer (2023) LEG"))
            assertEquals(n, ch("Oppenheimer", 2023), ch(Vod.nomeLimpo(n), 2023))
        // Catálogo todo com id: nada a fazer.
        assertEquals(emptyMap<String, Int>(), SincronizarFilmes.idsPorNome(listOf(cat[3]), fora))
    }

    @Test fun idsDoSimklSemRedirect() {
        val bib = listOf(
            SincronizarFilmes.EntradaSimkl(Item(Tipo.FILME, Ids(tmdb = 872585, simkl = 1727566), "Oppenheimer", 2023), "plantowatch", 0),
            SincronizarFilmes.EntradaSimkl(Item(Tipo.SERIE, Ids(tmdb = 1396, simkl = 11121), "Breaking Bad", 2008), "completed", 0),
            SincronizarFilmes.EntradaSimkl(Item(Tipo.FILME, Ids(simkl = 5), "Sem tmdb", 2020), "completed", 0))
        val daBib = SincronizarFilmes.simklIdsPorTmdb(bib)
        assertEquals(mapOf("F872585" to 1727566, "S1396" to 11121), daBib)
        // Catálogo: o 4K e o LEG dividem o id achado para a versão comum; filme e série com o mesmo tmdb não se misturam.
        val cat = listOf(Triple(27205, false, 53536), Triple(27205, false, null), Triple(27205, true, null), Triple(1396, true, 999))
        val k = InfoExterna.simklConhecidos(cat, daBib)
        assertEquals(53536, k["F27205"]); assertNull(k["S27205"])
        assertEquals(11121, k["S1396"])   // a biblioteca da pessoa vale mais que o guardado
        assertEquals(1727566, k[InfoExterna.chaveTmdb(872585, false)])
    }
}
