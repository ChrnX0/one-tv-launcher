package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.Zapeio
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elevação 5.14: segurar ▲▼ passa a faixa pelos canais sem trocar o vídeo; troca uma vez ao soltar. */
class ZapeioTest {
    @Test fun toqueCurtoTrocaUmAoSoltar() {
        val z = Zapeio()
        assertNull(z.apertou(+1, 0, 0))
        assertFalse(z.segurando)
        assertEquals(1, z.soltou())
    }

    @Test fun segurandoAndaSemTrocarETrocaUmaVez() {
        val z = Zapeio(intervaloMs = 300)
        assertNull(z.apertou(+1, 0, 0))
        assertEquals(1, z.apertou(+1, 1, 500))      // 1ª repetição: mostra o próximo
        assertNull(z.apertou(+1, 2, 550))           // repetição rápida demais para ler: não anda
        assertEquals(2, z.apertou(+1, 3, 800))
        assertEquals(3, z.apertou(+1, 4, 1_100))
        assertTrue(z.segurando)
        assertEquals(3, z.soltou())                  // troca uma vez só, no canal da faixa
        assertNull(z.soltou())                       // nada sobra
    }

    @Test fun voltarAoMesmoCanalNaoTroca() {
        val z = Zapeio(intervaloMs = 0)
        z.apertou(-1, 0, 0); z.apertou(-1, 1, 1)
        z.apertou(+1, 2, 2)                          // segurou ▼ e depois ▲ (controle que manda as duas)
        assertNull(z.soltou())
    }

    @Test fun cancelarEsquece() {
        val z = Zapeio()
        z.apertou(+1, 0, 0); z.cancelar()
        assertNull(z.soltou())
    }
}
