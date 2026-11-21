package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.Vod
import br.paulo.tvlauncher.player.Xtream
import br.paulo.tvlauncher.player.item
import java.io.IOException
import java.io.Reader
import java.io.StringReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class JsonEmFluxoTest {
    private fun lista(t: String) = JsonEmFluxo.objetos(StringReader(t)).map { it.optString("n") }.toList()

    @Test fun leObjetosUmPorVez() {
        assertEquals(listOf("a", "b}", "c"), lista("""﻿ [ {"n":"a"}, {"n":"b}","x":{"y":[1,{"z":"]"}]}} ,{"n":"c\"q"}]""").map { it.substringBefore('"') })
        assertEquals(emptyList<String>(), lista("[]"))
        assertEquals(emptyList<String>(), lista(" [ ] "))
    }

    @Test fun valoresSoltosEObjetosRuinsFicamDeFora() {
        assertEquals(listOf("a", "b"), lista("""[null, 3, "texto", [1,2], {"n":"a"}, {ruim}, {"n":"b"}, true]"""))
        assertEquals(listOf("a"), lista("""[{"n":"a"},7]"""))
    }

    @Test fun listaCortadaOuQueNaoEhListaEhErro() {
        for (t in listOf("""[{"n":"a"},{"n":"b"""", """[{"n":"a"},""", """[{"n":"a"}""", "<html>erro</html>", "", """{"n":"a"}""")) {
            try { lista(t); fail("devia falhar: $t") } catch (_: IOException) {}
        }
    }

    @Test fun paraNoMaximoSemLerOResto() {
        var lidos = 0
        // Um leitor sem fim: se a sequência lesse tudo, o teste não acabaria.
        val infinito = object : Reader() {
            val item = """{"stream_id":1,"name":"F"},""".toCharArray()
            var i = -1
            override fun read(b: CharArray, off: Int, len: Int): Int {
                var n = 0
                while (n < len) {
                    val c = if (i < 0) '[' else item[i % item.size]
                    i++; b[off + n] = c; n++
                }
                lidos += n
                return n
            }
            override fun close() {}
        }
        val filmes = Vod.lerFilmes(JsonEmFluxo.objetos(infinito), "f")
        assertEquals(Vod.MAX_FILMES, filmes.size)
        assertTrue(lidos < 50 * 1024 * 1024)
    }

    @Test fun objetoGiganteEhPulado() {
        val grande = "x".repeat(JsonEmFluxo.MAX_OBJETO + 10)
        assertEquals(listOf("a", "b"), lista("""[{"n":"a"},{"n":"g","p":"$grande"},{"n":"b"}]"""))
    }

    @Test fun mesmoResultadoQueOLeitorAntigo() {
        val json = """[{"stream_id":"10","name":"Filme (2020)","category_id":"3","stream_icon":"https://a/b.jpg","rating":"7.5"},
            {"stream_id":"11","name":"Outro"},{"name":"sem id"}]"""
        val cats = listOf(Xtream.Categoria("3", "Ação"))
        assertEquals(Vod.lerFilmes(json, "f", cats), Vod.lerFilmes(JsonEmFluxo.objetos(StringReader(json)), "f", cats))
    }
}
