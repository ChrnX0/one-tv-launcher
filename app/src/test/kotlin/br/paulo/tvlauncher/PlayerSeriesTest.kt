package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.Episodio
import br.paulo.tvlauncher.player.FichaSerie
import br.paulo.tvlauncher.player.Posicao
import br.paulo.tvlauncher.player.ProgressoSerie
import br.paulo.tvlauncher.player.Serie
import br.paulo.tvlauncher.player.Series
import br.paulo.tvlauncher.player.UltimoEpisodio
import br.paulo.tvlauncher.player.Vod
import br.paulo.tvlauncher.player.Xtream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Séries do Xtream: catálogo, episódios nos dois formatos, próximo episódio e "continuar". Dados inventados. */
class PlayerSeriesTest {
    private val conta = Xtream.Conta("http://servidor.invalid", "ana", "s&nh@")
    private val ficha = Series.lerFicha("""{"info":{"plot":"Sinopse","genre":"Drama"},
        "episodes":{"2":[{"id":"21","episode_num":1,"title":"Volta","container_extension":"mkv","info":{"duration_secs":2700}}],
                    "1":[{"id":"12","episode_num":2,"title":"Dois"},{"id":"11","episode_num":1,"title":"Um","season":1},{"title":"sem id"}]}}""")!!

    @Test fun leCatalogo() {
        val s = Series.lerSeries("""[{"series_id":5,"name":"A Série (2021)","cover":"http://img.invalid/c.jpg","rating":"8","category_id":"1","last_modified":"1700000000"},
            {"series_id":6,"name":""},{"name":"sem id"}]""", "a1", listOf(Xtream.Categoria("1", "Drama")))
        assertEquals(1, s.size)
        assertEquals("Drama", s[0].grupo); assertEquals(2021, s[0].ano); assertEquals(8f, s[0].nota); assertEquals("a1:s5", s[0].chave)
        assertEquals(s, Series.seriesDeJson(Series.seriesParaJson(s), "a1"))
    }

    @Test fun leEpisodiosEmOrdem() {
        assertEquals(listOf(1, 2), ficha.temporadas.keys.toList())
        assertEquals(listOf("11", "12", "21"), Series.emOrdem(ficha).map { it.id })
        assertEquals(45, ficha.temporadas[2]!![0].duracaoMin)
        assertEquals("T2E1", ficha.temporadas[2]!![0].rotulo)
        // Formato em lista de listas.
        val l = Series.lerFicha("""{"info":{},"episodes":[[{"id":"1","season":1,"episode_num":1}],[{"id":"2","season":2,"episode_num":1}]]}""")!!
        assertEquals(listOf(1, 2), l.temporadas.keys.toList())
        assertNull(Series.lerFicha("<html>"))
    }

    @Test fun enderecoSemSenhaNoCache() {
        assertEquals("http://servidor.invalid/series/ana/s%26nh%40/21.mkv", Series.urlEpisodio(conta, "21", "mkv"))
        val u = mapOf("a1:s5" to UltimoEpisodio("21", 2, 1, "Volta", "mkv", 10))
        val json = Series.ultimosParaJson(u)
        assertFalse(json.contains("ana"))
        assertEquals(u, Series.ultimosDeJson(json))
    }

    // Dublada x legendada são duas séries no catálogo: o mesmo episódio é achado por temporada e número, não pelo id.
    @Test fun mesmoEpisodioNaOutraVersao() {
        val todos = Series.emOrdem(ficha)
        val outra = FichaSerie(temporadas = mapOf(1 to listOf(Episodio("901", 1, 1, "Um"), Episodio("902", 1, 2, "Dois")),
            2 to listOf(Episodio("951", 2, 1, "Volta"))))
        assertEquals("902", Series.mesmoEpisodio(outra, todos[1])?.id)
        assertEquals("951", Series.mesmoEpisodio(outra, todos[2])?.id)
        assertNull(Series.mesmoEpisodio(FichaSerie(temporadas = mapOf(1 to listOf(Episodio("901", 1, 1, "Um")))), todos[2]))
    }

    @Test fun proximoESugestao() {
        val todos = Series.emOrdem(ficha)
        assertEquals("21", Series.proximo(ficha, todos[1])?.id)   // fim da temporada 1 -> T2E1
        assertNull(Series.proximo(ficha, todos[2]))
        assertEquals("11" to false, Series.sugestao(ficha, "a1", null, emptyMap())?.let { it.first.id to it.second })
        val ult = UltimoEpisodio("12", 1, 2, "Dois", "mp4", 5)
        val meio = mapOf("a1:e12" to Posicao(600_000, 2_700_000, 5))
        assertEquals("12" to true, Series.sugestao(ficha, "a1", ult, meio)?.let { it.first.id to it.second })
        val fim = mapOf("a1:e12" to Posicao(2_700_000, 2_700_000, 5))
        assertEquals("21" to false, Series.sugestao(ficha, "a1", ult, fim)?.let { it.first.id to it.second })
    }

    @Test fun continuarEOrdem() {
        val s = listOf(Serie("1", "a", "Um", atualizada = 5), Serie("2", "a", "Dois", atualizada = 9))
        val u = mapOf("a:s1" to UltimoEpisodio("x", 1, 1, "", "mp4", 50))
        assertEquals(listOf("1"), Series.continuar(s, u).map { it.id })
        assertEquals(listOf("2", "1"), Series.ordenar(s, Vod.Ordem.RECENTES).map { it.id })
        assertEquals(listOf("2"), Series.buscar(s, "dois").map { it.id })
        val muitas = (1..205).associate { "k$it" to UltimoEpisodio("e", 1, 1, "", "mp4", it.toLong()) }
        assertTrue(Series.registrarUltimo(muitas, "novo", UltimoEpisodio("e", 1, 1, "", "mp4", 999)).size == 200)
    }

    @Test fun continuarSobeComEpisodioNovo() {
        // A foi vista agora há pouco; B foi vista há muito tempo, mas o servidor publicou episódio depois da conta.
        val a = Serie("1", "a", "A", atualizada = 100)
        val b = Serie("2", "a", "B", atualizada = 2_000_000)                       // segundos
        val u = mapOf(a.chave to UltimoEpisodio("x", 1, 1, "", "mp4", 1_500_000_000L), // ms
                      b.chave to UltimoEpisodio("y", 1, 1, "", "mp4", 10L))
        val semNovidade = mapOf(b.chave to ProgressoSerie(2, 5, 2_000_000, 0, 0, 1))
        assertEquals(listOf("1", "2"), Series.continuar(listOf(a, b), u, semNovidade).map { it.id })
        val comNovidade = mapOf(b.chave to ProgressoSerie(2, 5, 1_000_000, 0, 0, 1))
        assertEquals(listOf("2", "1"), Series.continuar(listOf(a, b), u, comNovidade).map { it.id })
        // Nada visto (vistos = 0): a publicação não conta, fica pela última vez aberta.
        assertEquals(10L, Series.ordemContinuar(b, u[b.chave], ProgressoSerie(0, 5, 1_000_000, 0, 0, 1)))
    }

    @Test fun assistidosEFinalizado() {
        val serie = Serie("5", "a1", "A Série", atualizada = 100)
        val vista = mapOf("a1:e11" to Posicao(2_700_000, 2_700_000, 1))                 // T1E1 até o fim
        val p1 = Series.progressoDaFicha(serie, ficha, vista, null)!!
        assertEquals(Triple(1, 3, 100L), Triple(p1.vistos, p1.total, p1.atualizada))
        assertEquals(0, p1.concluidaCom)                                                  // ainda não concluída
        assertEquals(33, Series.percentual(serie, p1))
        assertNull(Series.percentual(serie, Series.progressoDaFicha(serie, ficha, emptyMap(), null)))   // nada visto
        // 14/11/2026: episódio conta como visto só concluído (faltando menos de 3 min), não mais com 1 min.
        val todas = vista + ("a1:e12" to Posicao(2_560_000, 2_700_000, 2)) + ("a1:e21" to Posicao(2_600_000, 2_700_000, 3))
        assertEquals(2, Series.progressoDaFicha(serie, ficha, vista + ("a1:e12" to Posicao(120_000, 2_700_000, 2)) + ("a1:e21" to Posicao(2_600_000, 2_700_000, 3)), null)!!.vistos)
        val p3 = Series.progressoDaFicha(serie, ficha, todas, p1)!!
        assertEquals(100, Series.percentual(serie, p3))                                  // 3 de 3: Finalizado
        // Posições velhas sumiram do arquivo: o visto continua visto (total igual).
        assertEquals(3, Series.progressoDaFicha(serie, ficha, emptyMap(), p3)!!.vistos)
        // Sala 17/11/2026: conta velha "1 de 3" com só o T1E1 começado (não visto) e nada antes dele: volta a 0.
        val velha = ProgressoSerie(1, 3, 100)
        val soComecou = mapOf("a1:e11" to Posicao(1_000_000, 2_700_000, 5))
        assertEquals(0, Series.progressoDaFicha(serie, ficha, soComecou, velha)!!.vistos)
        // Assistiu T1E1 e T1E2 (posições sumiram), está no T2E1 começado: mantém os 2.
        val noT2 = mapOf("a1:e21" to Posicao(60_000, 2_700_000, 9))
        assertEquals(2, Series.progressoDaFicha(serie, ficha, noT2, ProgressoSerie(2, 3, 100))!!.vistos)
        // Episódio novo no servidor depois da conta: deixa de ser Finalizado.
        assertEquals(99, Series.percentual(serie.copy(atualizada = 200), p3))
        // Finalizada sai do "continuar" e fica nos "assistidos".
        val u = mapOf(serie.chave to UltimoEpisodio("21", 2, 1, "", "mp4", 9))
        val prog = mapOf(serie.chave to p3)
        assertTrue(Series.continuar(listOf(serie), u, prog).isEmpty())
        assertEquals(listOf("5"), Series.assistidos(listOf(serie), u, todas, prog).map { it.id })
        // 14/11/2026: sem conta (nunca concluída), não entra nos assistidos; fica no Continuar.
        assertTrue(Series.assistidos(listOf(serie), u, todas, emptyMap()).isEmpty())
        assertEquals(prog, Series.progressoDeJson(Series.progressoParaJson(prog)))
    }

    // Rodada 14 da depuração: ficha com episódios demais não estoura a memória; título gigante é cortado.
    @Test fun fichaComEpisodiosDemaisPara() {
        val eps = org.json.JSONArray()
        repeat(Series.MAX_EPISODIOS + 50) { eps.put(org.json.JSONObject().put("id", "$it").put("episode_num", it + 1).put("title", "x".repeat(1000))) }
        val f = Series.lerFicha(org.json.JSONObject().put("info", org.json.JSONObject()).put("episodes", org.json.JSONObject().put("1", eps)).toString())!!
        assertEquals(Series.MAX_EPISODIOS, Series.emOrdem(f).size)
        assertEquals(300, Series.emOrdem(f).first().titulo.length)
    }
}
