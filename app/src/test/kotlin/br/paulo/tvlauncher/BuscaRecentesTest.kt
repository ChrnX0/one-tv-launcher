package br.paulo.tvlauncher

import br.paulo.tvlauncher.search.BuscaRecentes
import org.junit.Assert.assertEquals
import org.junit.Test

class BuscaRecentesTest {
    @Test fun maisNovaPrimeiroSemRepetir() {
        assertEquals(listOf("Globo", "Friends"), BuscaRecentes.juntar(listOf("Friends", "globo"), " Globo "))
    }

    @Test fun cortaNoMaximo() {
        val l = (1..6).map { "t$it" }
        assertEquals(listOf("novo", "t1", "t2", "t3", "t4", "t5"), BuscaRecentes.juntar(l, "novo"))
    }

    @Test fun termoCurtoNaoEntra() {
        assertEquals(listOf("abc"), BuscaRecentes.juntar(listOf("abc"), "a"))
    }
}
