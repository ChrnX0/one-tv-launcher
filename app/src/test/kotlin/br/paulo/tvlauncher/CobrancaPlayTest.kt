package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.CobrancaPlay
import br.paulo.tvlauncher.settings.Planos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Cobrança da Play: qual plano cada compra libera e até quando o plano guardado vale sem falar com a loja. */
class CobrancaPlayTest {
    @Test fun maiorPlanoVence() {
        assertEquals(Planos.Plano.BASICO, CobrancaPlay.planoDasCompras(emptyList()))
        assertEquals(Planos.Plano.PRO, CobrancaPlay.planoDasCompras(listOf(CobrancaPlay.PRODUTO_PRO)))
        assertEquals(Planos.Plano.ULTRA, CobrancaPlay.planoDasCompras(listOf(CobrancaPlay.PRODUTO_PRO, CobrancaPlay.PRODUTO_ULTRA)))
        assertEquals(Planos.Plano.BASICO, CobrancaPlay.planoDasCompras(listOf("outro")))
    }

    @Test fun guardadoValeDuasSemanas() {
        val agora = 1_800_000_000_000L
        assertFalse(CobrancaPlay.guardadoValido(0, agora))
        assertTrue(CobrancaPlay.guardadoValido(agora - 1000, agora))
        assertTrue(CobrancaPlay.guardadoValido(agora - CobrancaPlay.VALIDADE_MS, agora))
        assertFalse(CobrancaPlay.guardadoValido(agora - CobrancaPlay.VALIDADE_MS - 1, agora))
        assertFalse("relógio que voltou não estende", CobrancaPlay.guardadoValido(agora + 60_000, agora))
    }

    /** Venda só no build da Play; o APK do site (Sala) nunca cobra. */
    @Test fun vendaSoNaPlay() = assertEquals(BuildConfig.PLAY, Planos.VENDA_ATIVA)
}
