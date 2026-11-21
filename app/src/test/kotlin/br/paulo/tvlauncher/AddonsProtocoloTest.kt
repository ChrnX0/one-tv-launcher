package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.AddonsProtocolo
import br.paulo.tvlauncher.player.AddonsTmdb
import br.paulo.tvlauncher.player.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AddonsProtocoloTest {
    private val manifesto = """{"id":"org.exemplo","version":"1.0.0","name":"Exemplo","types":["movie","series"],
        "idPrefixes":["tt"],"resources":["catalog",{"name":"stream","types":["movie","series"],"idPrefixes":["tt","ex:"]},"subtitles"],
        "catalogs":[{"type":"movie","id":"top","name":"Populares","extra":[{"name":"genre","options":["Ação","Drama"]},{"name":"skip"}]},
                    {"type":"series","id":"busca","extra":[{"name":"search","isRequired":true}]}]}"""

    private fun ok(t: String) = (AddonsProtocolo.lerManifesto(t) as AddonsProtocolo.Leitura.Ok).m

    @Test fun endereco() {
        assertEquals("https://a.b/c", AddonsProtocolo.base("https://a.b/c/manifest.json"))
        assertEquals("https://a.b/c", AddonsProtocolo.base(" stremio://a.b/c/manifest.json "))
        assertEquals("https://a.b", AddonsProtocolo.base("https://a.b/"))
        assertNull(AddonsProtocolo.base("magnet:?xt=urn:btih:abc"))
        assertNull(AddonsProtocolo.base("ftp://a.b/manifest.json"))
        assertNull(AddonsProtocolo.base("https:///manifest.json"))
        assertEquals("https://a.b/catalog/movie/top/genre=A%C3%A7%C3%A3o&skip=100.json",
            AddonsProtocolo.url("https://a.b", "catalog", "movie", "top", listOf("genre" to "Ação", "skip" to "100")))
        assertEquals("https://a.b/stream/series/tt1%3A1%3A2.json", AddonsProtocolo.url("https://a.b", "stream", "series", "tt1:1:2"))
    }

    @Test fun manifestoLido() {
        val m = ok(manifesto)
        assertEquals(2, m.catalogos.size)
        assertEquals(listOf("Ação", "Drama"), m.catalogos[0].generos)
        assertTrue(m.catalogos[0].listavel)
        assertFalse(m.catalogos[1].listavel)                         // só busca: não vira linha
        assertTrue(m.catalogos[1].temBusca)
        assertTrue(m.atende("stream", "movie", "ex:9"))              // prefixos do recurso ganham dos gerais
        assertFalse(m.atende("stream", "tv", "tt1"))
        assertTrue(m.atende("subtitles", "series", "tt1:1:1"))
        assertFalse(m.atende("meta", "movie", "tt1"))
        // Guardar e reler dá o mesmo manifesto.
        val lista = listOf(AddonsProtocolo.Instalado("https://a.b", m))
        assertEquals(lista, AddonsProtocolo.deJson(AddonsProtocolo.paraJson(lista)))
    }

    @Test fun recusas() {
        fun motivo(t: String) = (AddonsProtocolo.lerManifesto(t) as AddonsProtocolo.Leitura.Recusado).motivo
        assertEquals(AddonsProtocolo.Motivo.P2P, motivo("""{"id":"x","name":"X","resources":["stream"],"behaviorHints":{"p2p":true}}"""))
        assertEquals(AddonsProtocolo.Motivo.ADULTO, motivo("""{"id":"x","name":"X","resources":["catalog"],"behaviorHints":{"adult":true}}"""))
        assertEquals(AddonsProtocolo.Motivo.PRECISA_CONFIGURAR, motivo("""{"id":"x","name":"X","resources":["stream"],"behaviorHints":{"configurationRequired":true}}"""))
        assertEquals(AddonsProtocolo.Motivo.SEM_RECURSO_UTIL, motivo("""{"id":"x","name":"X","resources":["player","library"]}"""))
        assertEquals(AddonsProtocolo.Motivo.INVALIDO, motivo("""<html>"""))
    }

    @Test fun soFontesHttp() {
        val t = """{"streams":[
            {"infoHash":"abc","fileIdx":1,"name":"Torrent"},
            {"url":"magnet:?xt=urn:btih:abc","name":"Magnet"},
            {"nzbUrl":"https://x/a.nzb","name":"Usenet"},
            {"ytId":"abc","name":"YouTube"},
            {"externalUrl":"https://netflix.com/x","name":"Netflix"},
            {"url":"https://cdn.x/filme.mp4","name":"HD","description":"1080p",
             "behaviorHints":{"bingeGroup":"x-1080","filename":"filme.mp4","proxyHeaders":{"request":{"User-Agent":"UA","Cookie":"s=1"}}}},
            {"url":"https://cdn.x/filme.mp4","name":"repetida"}]}"""
        val f = AddonsProtocolo.lerFontes(t)
        assertEquals(1, f.size)
        assertEquals("https://cdn.x/filme.mp4", f[0].url)
        assertEquals(mapOf("User-Agent" to "UA"), f[0].cabecalhos)   // Cookie não passa
        assertEquals("x-1080", f[0].grupoMaratona)
        assertEquals(f[0], AddonsProtocolo.mesmaMaratona(f, "x-1080"))
        assertNull(AddonsProtocolo.mesmaMaratona(f, ""))
    }

    @Test fun catalogoFichaLegendas() {
        val cat = AddonsProtocolo.lerCatalogo("""{"metas":[{"id":"tt1","type":"movie","name":"Um","poster":"https://p/1.jpg","releaseInfo":"2020"},
            {"id":"tt2","name":"Dois","poster":"javascript:x"},{"name":"sem id"},{"id":"tt1","name":"repetido"}]}""", "movie")
        assertEquals(listOf("tt1", "tt2"), cat.map { it.id })
        assertEquals("", cat[1].poster)                              // só http(s)
        assertEquals("movie", cat[1].tipo)
        val ficha = AddonsProtocolo.lerFicha("""{"meta":{"id":"tt9","type":"series","name":"S","videos":[
            {"id":"tt9:2:1","title":"B","season":2,"episode":1},{"id":"tt9:0:1","title":"Extra","season":0,"episode":1},
            {"id":"tt9:1:1","title":"A","season":1,"episode":1}]}}""", "series")!!
        assertEquals(listOf("tt9:1:1", "tt9:2:1", "tt9:0:1"), ficha.videos.map { it.id })   // especiais no fim
        assertEquals("tt9:1:2", AddonsProtocolo.idEpisodio("tt9", 1, 2))
        assertEquals("tt9:2:1", AddonsProtocolo.proximo(ficha.videos, "tt9:1:1")?.id)
        assertNull(AddonsProtocolo.proximo(ficha.videos, "tt9:2:1"))          // não emenda nos especiais
        assertNull(AddonsProtocolo.proximo(ficha.videos, "outro"))
        val legs = AddonsProtocolo.lerLegendas("""{"subtitles":[{"id":"1","url":"https://s/1.srt","lang":"por"},{"url":"file:///x","lang":"eng"}]}""")
        assertEquals(listOf("por"), legs.map { it.idioma })
    }

    @Test fun limite() {
        val m = ok(manifesto)
        var l = emptyList<AddonsProtocolo.Instalado>()
        repeat(AddonsProtocolo.MAX_ADDONS) { i -> l = AddonsProtocolo.instalar(l, AddonsProtocolo.Instalado("https://a$i.b", m.copy(id = "id$i")))!! }
        assertNull(AddonsProtocolo.instalar(l, AddonsProtocolo.Instalado("https://z.b", m.copy(id = "novo"))))
        // Reinstalar o mesmo (mesma base) troca, não soma.
        assertEquals(l.size, AddonsProtocolo.instalar(l, AddonsProtocolo.Instalado("https://a0.b", m.copy(id = "id0", versao = "2")))!!.size)
    }

    @Test fun generoObrigatorioComOpcoesViraLinha() {
        // Formato do Live Channels: um catálogo por país, gênero obrigatório com opções ("All" primeiro).
        val m = ok("""{"id":"community.live-channels","version":"1.0.0","name":"Live Channels","types":["channels"],
            "resources":["catalog","meta","stream"],"catalogs":[
            {"type":"channels","id":"br","name":"Brazil","extra":[{"name":"genre","isRequired":true,"options":["All","Filmes"]}]},
            {"type":"channels","id":"busca","name":"Busca","extra":[{"name":"search","isRequired":true}]}]}""")
        val br = m.catalogos.first { it.id == "br" }
        assertTrue(br.listavel)
        assertEquals("All", br.generoPadrao())
        assertFalse(m.catalogos.first { it.id == "busca" }.listavel)
    }
}

class AddonsTmdbTest {
    @Test fun findETemporadas() {
        assertNull(AddonsTmdb.urlFind("k", "nm123", "pt-BR"))
        assertTrue(AddonsTmdb.urlFind("k", "tt0111161", "pt-BR")!!.contains("/find/tt0111161?external_source=imdb_id"))
        val find = """{"movie_results":[{"id":278,"title":"Um Sonho de Liberdade","release_date":"1994-09-23","poster_path":"/a.jpg"}],
            "tv_results":[{"id":1,"name":"Outra"}]}"""
        val a = AddonsTmdb.lerFind(find, "tt0111161", "movie")!!
        assertEquals(278, a.tmdb); assertFalse(a.serie)
        assertEquals("https://image.tmdb.org/t/p/w342/a.jpg", a.item.poster)
        assertEquals("1994", a.item.ano)
        assertTrue(AddonsTmdb.lerFind(find, "tt0111161", "series")!!.serie)
        assertNull(AddonsTmdb.lerFind("""{"movie_results":[]}""", "tt1", "movie"))
        assertEquals(listOf(1, 2, 0), AddonsTmdb.lerTemporadas("""{"seasons":[{"season_number":0},{"season_number":2},{"season_number":1}]}"""))
        val eps = AddonsTmdb.lerEpisodios("""{"episodes":[{"episode_number":1,"name":"Piloto"},{"episode_number":0}]}""", "tt9", 3)
        assertEquals(listOf("tt9:3:1"), eps.map { it.id })
    }
}
