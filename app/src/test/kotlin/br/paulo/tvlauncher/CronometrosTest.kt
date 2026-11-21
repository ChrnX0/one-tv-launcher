package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.Cronometros
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

    @Test fun vodPrimeiroQuadroUmaVez() {
        val s = Cronometros.SessaoVod()
        assertNull(s.primeiroQuadro(500))                 // sem "abriu": nada a medir
        s.abriu(1_000); s.abriu(9_000)                    // voltar de outra tela não reinicia o relógio
        assertEquals(1_400L, s.primeiroQuadro(2_400))
        assertNull(s.primeiroQuadro(3_000))
    }

    @Test fun vodTravadaNaoContaPuloNemAntesDoPrimeiroQuadro() {
        val s = Cronometros.SessaoVod()
        s.abriu(1)
        s.carregando(querTocar = true)                    // carregando o começo: não é travada
        s.primeiroQuadro(1_000)
        s.pulou(); s.carregando(querTocar = true)         // depois de ◀ ▶: não é travada
        s.carregando(querTocar = false)                   // pausado: não é travada
        s.carregando(querTocar = true)                    // tocando e parou: travada
        assertEquals(1, s.travadas)
    }

    @Test fun vodTaxasSoComTempoBastante() {
        val s = Cronometros.SessaoVod()
        s.abriu(1); s.primeiroQuadro(1_000)
        s.tocando(true, 1_000); s.carregando(true); s.tocando(false, 1_000 + 3 * 60_000)
        assertNull(s.fechar(1_000 + 3 * 60_000, perdidos = 30))     // 3 min: pouco para virar taxa
        s.tocando(true, 1); s.carregando(true); s.carregando(true)
        val r = s.fechar(1 + 30 * 60_000, perdidos = 60)!!               // 30 min, 2 travadas, 60 quadros
        assertEquals(4L, r.travadasPorHora)
        assertEquals(2L, r.perdidosPorMin)
        assertEquals(0, s.travadas)                                 // trecho novo começa do zero
    }
}
