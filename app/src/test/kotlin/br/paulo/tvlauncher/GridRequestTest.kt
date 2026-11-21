package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.GridRequest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GridRequestTest {
    @Test fun semPendenteValeOPedido() {
        val n = GridRequest.Need(0, false, null)
        assertTrue(GridRequest.merge(null, n) === n)
    }

    /** O caso da Sala: grade nova + inicial + foco, passada para trás por um "só a coluna". */
    @Test fun pedidoFracoHerdaOForte() {
        var focou = 0
        val m = GridRequest.merge(GridRequest.Need(2, true) { focou++ }, GridRequest.Need(0, false, null))
        assertEquals(2, m.strength)
        assertTrue(m.initial)
        m.after!!()
        assertEquals(1, focou)
    }

    @Test fun asDuasAcoesRodamNaOrdem() {
        val ordem = StringBuilder()
        val m = GridRequest.merge(GridRequest.Need(1, false) { ordem.append("a") }, GridRequest.Need(2, false) { ordem.append("b") })
        assertEquals(2, m.strength)
        m.after!!()
        assertEquals("ab", ordem.toString())
    }

    @Test fun semAcoesFicaSemAcao() {
        assertNull(GridRequest.merge(GridRequest.Need(1, false, null), GridRequest.Need(0, false, null)).after)
    }
}
