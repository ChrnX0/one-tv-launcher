package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

class UsoLocalRegrasTest {
    @Test fun somaEOrdena() {
        var j: String? = null
        j = UsoLocalRegras.somar(j, "tela:Filmes", 1000)
        j = UsoLocalRegras.somar(j, "tela:Casa", 2000)
        j = UsoLocalRegras.somar(j, "tela:Filmes", 3000)
        val itens = UsoLocalRegras.itens(j)
        assertEquals(listOf("tela:Filmes", "tela:Casa"), itens.map { it.nome })
        assertEquals(2, itens[0].vezes); assertEquals(3000L, itens[0].ultimo)
    }

    @Test fun jsonEstragadoRecomeca() {
        assertEquals(1, UsoLocalRegras.itens(UsoLocalRegras.somar("{quebrado", "x", 1)).size)
        assertEquals(0, UsoLocalRegras.itens("lixo").size)
    }

    @Test fun tetoDeNomes() {
        var j: String? = null
        for (i in 0..UsoLocalRegras.MAX_NOMES + 5) j = UsoLocalRegras.somar(j, "n$i", i.toLong())
        assertEquals(UsoLocalRegras.MAX_NOMES, JSONObject(j!!).length())
    }

    @Test fun nomesEDias() {
        assertEquals("Filmes", UsoLocalRegras.nomeDaTela("br.paulo.tvlauncher.FilmesActivity"))
        assertEquals("MainActivity".removeSuffix("Activity"), UsoLocalRegras.nomeDaTela("br.paulo.tvlauncher.MainActivity"))
        assertEquals(2L, UsoLocalRegras.diasDesde(0, 2 * 86_400_000L + 5))
        assertEquals(0L, UsoLocalRegras.diasDesde(10, 0))
    }
}
