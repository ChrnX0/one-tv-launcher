package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Botão Timer do painel rápido: cada OK avança uma opção a partir da ESCOLHIDA, não do tempo que falta. */
class TimerPainelTest {
    private fun p(escolhido: Int?, faltam: Int? = null) = PainelRapidoActivity.proximoTimer(escolhido, faltam)

    @Test fun cicloCompleto() {
        assertEquals(15, p(null))
        assertEquals(30, p(15))
        assertEquals(60, p(30))
        assertEquals(90, p(60))
        assertEquals(120, p(90))
        assertNull(p(120))   // depois do último, desliga o timer
    }

    @Test fun minutoDepoisNaoVoltaA15() {
        // Sala 17/11: marcou 15, 1 min depois faltavam 14 e o OK marcava 15 de novo.
        assertEquals(30, p(15, faltam = 14))
        assertEquals(30, p(15, faltam = 3))
    }

    @Test fun timerAntigoSemEscolhidoUsaOQueFalta() {
        assertEquals(30, p(null, faltam = 20))
        assertEquals(15, p(null, faltam = 10))
    }
}
