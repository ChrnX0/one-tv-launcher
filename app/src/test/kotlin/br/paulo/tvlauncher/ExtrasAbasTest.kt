package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtrasAbasTest {
    private val nomes = Extras.Chave.values().map { it.name }

    /** Chave nova sem aba sumiria da TV (a tela Extras não lista mais as chaves): toda chave tem aba. */
    @Test fun todaChaveTemAba() {
        for (n in nomes) assertTrue("$n sem aba em ExtrasAbas.MAPA", n in ExtrasAbas.MAPA)
    }

    @Test fun mapaSoTemChavesQueExistem() {
        for (n in ExtrasAbas.MAPA.keys) assertTrue("$n não é uma Extras.Chave", n in nomes)
    }

    @Test fun cadaChaveEmUmaAbaSoEOrdemDoMapa() {
        val todas = ExtrasAbas.Aba.values().flatMap { ExtrasAbas.daAba(it, nomes) }
        assertEquals(todas.toSet().size, todas.size)
        assertEquals(nomes.toSet(), todas.toSet())
        assertEquals(listOf("HORA_DORMIR", "TEMPO_TELA", "PIN_HOME"), ExtrasAbas.daAba(ExtrasAbas.Aba.PARENTAL, nomes))
    }

    @Test fun nomeQueNaoExisteNestaVersaoNaoAparece() =
        assertEquals(listOf("PIN_HOME"), ExtrasAbas.daAba(ExtrasAbas.Aba.PARENTAL, listOf("PIN_HOME")))
}
