package br.paulo.tvlauncher

import br.paulo.tvlauncher.family.UsoTv
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Como esta TV vai ser usada" (degrau 2, 16/11/2026). */
class UsoTvTest {
    @Test fun completoMostraTodasAsAbas() {
        for (i in 0..8) assertTrue(UsoTv.mostraAba(UsoTv.COMPLETO, i))
    }

    @Test fun simplesECuidaEscondemSoAsTecnicas() {
        for (uso in listOf(UsoTv.SIMPLES, UsoTv.CUIDA)) for (i in 0..8)
            assertEquals("$uso aba $i", i !in setOf(4, 5, 8), UsoTv.mostraAba(uso, i))
    }

    @Test fun tecnicoSoNoCompleto() {
        assertTrue(UsoTv.ofereceTecnico(UsoTv.COMPLETO))
        assertFalse(UsoTv.ofereceTecnico(UsoTv.SIMPLES))
        assertFalse(UsoTv.ofereceTecnico(UsoTv.CUIDA))
    }

    @Test fun proximoDaAVolta() {
        assertEquals(UsoTv.COMPLETO, UsoTv.proximo(UsoTv.SIMPLES))
        assertEquals(UsoTv.CUIDA, UsoTv.proximo(UsoTv.COMPLETO))
        assertEquals(UsoTv.SIMPLES, UsoTv.proximo(UsoTv.CUIDA))
    }
}
