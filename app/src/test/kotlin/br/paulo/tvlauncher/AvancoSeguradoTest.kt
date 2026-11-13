package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AvancoSeguradoTest {
    private val duas_horas = 2 * 3_600_000L

    /** Simula segurar a tecla por [segundos], com repetição a cada 50 ms (o padrão do Android), e devolve o alvo. */
    private fun segurar(a: AvancoSegurado, pos: Long, dur: Long, passo: Long, segundos: Double): Long {
        val inicio = 1_000_000L
        assertEquals(AvancoSegurado.limitar(pos + passo, dur), a.apertou(pos, dur, passo, 0, inicio, inicio))
        val posDepoisDoToque = AvancoSegurado.limitar(pos + passo, dur)
        var t = inicio + 500   // a 1ª repetição do Android chega ~0,5 s depois de apertar
        var n = 1
        while (t <= inicio + (segundos * 1000).toLong()) {
            assertEquals(-1L, a.apertou(posDepoisDoToque, dur, passo, n++, t, inicio))
            t += 50
        }
        return a.soltou()
    }

    @Test fun toqueSimplesPulaOPassoNaHora() {
        val a = AvancoSegurado()
        assertEquals(70_000L, a.apertou(40_000, duas_horas, 30_000, 0, 100, 100))
        assertEquals(-1L, a.soltou())   // toque simples: nada para pular ao soltar
    }

    @Test fun segurar5sAndaPoucosMinutos() {
        val alvo = segurar(AvancoSegurado(), 0, duas_horas, 30_000, 5.0)
        // antes: ~1 h 40 nesses 5 s. Agora: 30 s do toque + ~3 min
        assertTrue("alvo=$alvo", alvo in 150_000L..240_000L)
    }

    @Test fun segurar20sNaoPassaDeUmaHoraEMeia() {
        val alvo = segurar(AvancoSegurado(), 0, 10 * duas_horas, 30_000, 20.0)
        assertTrue("alvo=$alvo", alvo in 3_000_000L..5_400_000L)
    }

    @Test fun naoPassaDoFim() {
        assertEquals(duas_horas - 1_000, segurar(AvancoSegurado(), duas_horas - 60_000, duas_horas, 30_000, 10.0))
    }

    @Test fun voltarNaoPassaDoComeco() {
        assertEquals(0L, segurar(AvancoSegurado(), 60_000, duas_horas, -10_000, 10.0))
    }

    @Test fun velocidadeSobeEmDegrausAteOTeto() {
        var anterior = 0L
        for (ms in 0L..30_000L step 500) {
            val v = AvancoSegurado.velocidade(ms)
            assertTrue(v >= anterior); assertTrue(v <= AvancoSegurado.TETO_MS_POR_S); anterior = v
        }
        assertEquals(AvancoSegurado.TETO_MS_POR_S, AvancoSegurado.velocidade(60_000))
    }

    @Test fun repeticaoAtrasadaNaoDaSalto() {
        // TV engasgou 3 s entre duas repetições: anda no máximo 200 ms de tecla, não 3 s
        assertEquals(AvancoSegurado.TETO_MS_POR_S / 5, AvancoSegurado.deslocamento(60_000, 3_000))
    }

    @Test fun duracaoDesconhecidaNaoQuebra() {
        val a = AvancoSegurado()
        assertEquals(30_000L, a.apertou(0, Long.MAX_VALUE, 30_000, 0, 0, 0))
    }
}
