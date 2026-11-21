package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.Radios
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Já tocou" e "Rádios parecidas" da tela Rádio (proposta C do Ouvir, 21/11/2026). */
class RadiosParecidasTest {
    private fun r(id: String, nome: String, tags: String, pais: String = "BR") = Radios.Radio(id, nome, "http://x/$id", pais, tags)

    @Test fun jaTocou() {
        var l = Radios.comTocada(emptyList(), "A - Um", 1)
        l = Radios.comTocada(l, "A - Um", 2)   // a rádio reenviou o mesmo título
        l = Radios.comTocada(l, "", 3)
        l = Radios.comTocada(l, "B - Dois", 4)
        assertEquals(listOf("B - Dois", "A - Um"), l.map { it.texto })
        repeat(40) { l = Radios.comTocada(l, "m$it", 10L + it) }
        assertEquals(Radios.MAX_TOCADAS, l.size)
        assertEquals("m39", l.first().texto)
    }

    @Test fun tags() {
        assertEquals(listOf("pop", "mpb"), Radios.tagsDe(r("1", "X", "Pop, radio,MPB, fm,pop")))
    }

    @Test fun parecidas() {
        val base = r("b", "Rádio Base", "pop,mpb")
        val c = listOf(
            r("b", "Rádio Base", "pop,mpb"),                 // ela mesma
            r("x", "rádio base ", "pop"),                    // a mesma com outro id
            r("1", "Só pop lá fora", "pop", "US"),
            r("2", "Pop e MPB aqui", "mpb,pop"),
            r("3", "Rock", "rock"),
            r("4", "Pop aqui", "pop"),
        )
        assertEquals(listOf("2", "4", "1"), Radios.parecidas(base, c).map { it.id })
        assertTrue(Radios.parecidas(r("z", "Sem", "radio,fm"), c).isEmpty())
    }
}
