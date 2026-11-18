package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Elevação 5.1 (tecla até o primeiro quadro) e 20.4 (primeira abertura até o primeiro canal). */
class CronometrosTest {
    @Test fun medeDaTeclaAoPrimeiroQuadroUmaVez() {
        val c = Cronometros.TrocaCanal()
        c.tecla("a", 1_000)
        assertEquals(800L, c.primeiroQuadro("a", 1_800))
        assertNull(c.primeiroQuadro("a", 1_900))   // o segundo quadro não é troca
        assertFalse(c.medindo)
    }

    @Test fun zapearRapidoMedeSoOUltimo() {
        val c = Cronometros.TrocaCanal()
        c.tecla("a", 0); c.tecla("b", 300); c.tecla("c", 600)
        assertNull(Cronometros.TrocaCanal().primeiroQuadro("a", 1))
        assertEquals(900L, c.primeiroQuadro("c", 1_500))
    }

    @Test fun quadroDeOutroCanalOuCanceladoNaoConta() {
        val c = Cronometros.TrocaCanal()
        c.tecla("a", 0)
        assertNull(c.primeiroQuadro("b", 500))
        c.tecla("a", 0); c.cancelar()
        assertNull(c.primeiroQuadro("a", 500))
    }

    @Test fun canalQueNaoAbriuNaoEntraNaMediana() {
        val c = Cronometros.TrocaCanal()
        c.tecla("a", 0)
        assertNull(c.primeiroQuadro("a", Cronometros.LIMITE_MS + 1))
    }

    @Test fun instalacao() {
        assertEquals(240_000L, Cronometros.Instalacao.duracao(1_000, 241_000, jaMedida = false))
        assertNull(Cronometros.Instalacao.duracao(0, 241_000, jaMedida = false))         // não cronometrada (TV antiga)
        assertNull(Cronometros.Instalacao.duracao(1_000, 241_000, jaMedida = true))      // só a primeira vez
        assertNull(Cronometros.Instalacao.duracao(5_000, 1_000, jaMedida = false))       // relógio voltou
        assertNull(Cronometros.Instalacao.duracao(1, 2 + Cronometros.Instalacao.LIMITE_MS, jaMedida = false))
    }
}
