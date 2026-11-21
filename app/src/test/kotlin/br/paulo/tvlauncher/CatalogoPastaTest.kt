package br.paulo.tvlauncher

import br.paulo.tvlauncher.input.LeitorNomes
import br.paulo.tvlauncher.player.CatalogoPasta
import br.paulo.tvlauncher.player.InfoExterna
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elevação 8.2: o catálogo da pasta (busca, correção, ordem) e o pôster/temporadas da ficha do TMDB. */
class CatalogoPastaTest {

    @Test fun buscaUsaNomeLidoOuCorrigido() {
        val l = LeitorNomes.ler("Duna.2021.1080p.mkv")
        val b = CatalogoPasta.busca(l, null)!!
        assertEquals("Duna", b.nome); assertEquals(2021, b.ano); assertFalse(b.serie)
        // Correção da pessoa: o nome e o ano digitados mandam; sem ano digitado, sem ano.
        val c = CatalogoPasta.busca(l, "Dune Part One 2021")!!
        assertEquals("Dune Part One", c.nome); assertEquals(2021, c.ano)
        assertEquals(null, CatalogoPasta.busca(l, "Dune")!!.ano)
        assertTrue(b.chave != c.chave)
        // Série: a chave é da série (todos os episódios dividem a ficha da série).
        val e1 = CatalogoPasta.busca(LeitorNomes.ler("Dark.S01E01.mkv"), null)!!
        val e2 = CatalogoPasta.busca(LeitorNomes.ler("Dark.S02E05.mkv"), null)!!
        assertEquals(e1.chave, e2.chave); assertTrue(e1.serie)
        // Amostra não vai ao TMDB.
        assertNull(CatalogoPasta.busca(LeitorNomes.ler("Duna.2021.sample.mkv"), null))
    }

    @Test fun soMostraNomeLidoQuandoLeuAlgo() {
        assertTrue(CatalogoPasta.leuAlgo("Duna.2021.1080p.mkv", LeitorNomes.ler("Duna.2021.1080p.mkv")))
        assertFalse(CatalogoPasta.leuAlgo("Aniversario vovo.mp4", LeitorNomes.ler("Aniversario vovo.mp4")))
        assertFalse(CatalogoPasta.leuAlgo("Duna.2021.Trailer.mp4", LeitorNomes.ler("Duna.2021.Trailer.mp4")))
    }

    @Test fun ordemDeCatalogo() {
        val nomes = listOf("[pasta]Extras", "Dark.S02E01.mkv", "Dark.S01E10.mkv", "Dark.S01E02.mkv", "Aniversario.mp4", "Dark.S01E01.mkv")
        val lidos = nomes.associateWith { n -> if (n.startsWith("[pasta]") || n.startsWith("Aniv")) null else LeitorNomes.ler(n) }
        val r = CatalogoPasta.ordenar(nomes, { it.startsWith("[pasta]") }, { lidos[it] })
        assertEquals(listOf("[pasta]Extras", "Dark.S01E01.mkv", "Dark.S01E02.mkv", "Dark.S01E10.mkv", "Dark.S02E01.mkv", "Aniversario.mp4"), r)
    }

    @Test fun posterETemporadasDaFicha() {
        val json = """{"id":1399,"overview":"Nove famílias...","poster_path":"/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg","number_of_seasons":8}"""
        val d = InfoExterna.lerDetalhe(json, serie = true)!!
        assertEquals("https://image.tmdb.org/t/p/w342/1XS1oqL89opfnbLl8WnZY1O1uJx.jpg", d.poster)
        assertEquals(8, d.temporadas)
        // Volta do disco igual.
        val g = InfoExterna.guardadoDeJson(InfoExterna.guardadoParaJson(InfoExterna.Guardado(tmdb = 1399, detalhe = d, detalheEm = 1)))
        assertEquals(d.poster, g.detalhe!!.poster); assertEquals(8, g.detalhe!!.temporadas)
        // Caminho estranho nunca vira endereço.
        assertEquals("", InfoExterna.lerDetalhe("""{"id":1,"poster_path":"//evil.example/x.jpg"}""", false)!!.poster)
        assertEquals("", InfoExterna.urlPoster("/../x.jpg"))
        assertEquals(0, InfoExterna.lerDetalhe(JSONObject().put("id", 2).put("number_of_seasons", 3).toString(), serie = false)!!.temporadas)
    }
}
