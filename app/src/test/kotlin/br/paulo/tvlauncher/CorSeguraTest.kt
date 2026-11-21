package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.CorSegura
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elevação 16.3/16.4: as contas de contraste e de daltonismo. */
class CorSeguraTest {
    private val branco = 0xFFFFFFFF.toInt()
    private val preto = 0xFF000000.toInt()

    @Test fun contrasteDaWcag() {
        assertEquals(21.0, CorSegura.contraste(branco, preto), 0.01)
        assertEquals(1.0, CorSegura.contraste(Estilo.ACENTO, Estilo.ACENTO), 0.0001)
        assertEquals(4.48, CorSegura.contraste(0xFF777777.toInt(), branco), 0.02)   // o cinza clássico do limite AA
    }

    @Test fun cinzaContinuaCinzaParaQuemNaoVeCor() {
        for (t in CorSegura.Daltonismo.values()) {
            val c = CorSegura.simular(0xFF808080.toInt(), t)
            assertTrue("$t: $c", CorSegura.distancia(c, 0xFF808080.toInt()) < 4)
        }
    }

    @Test fun vermelhoEVerdeSeAproximamNaDeuteranopia() {
        val vermelho = 0xFFE53935.toInt(); val verde = 0xFF43A047.toInt()
        val antes = CorSegura.distancia(vermelho, verde)
        val depois = CorSegura.distancia(CorSegura.simular(vermelho, CorSegura.Daltonismo.DEUTERANOPIA),
            CorSegura.simular(verde, CorSegura.Daltonismo.DEUTERANOPIA))
        assertTrue("$antes -> $depois", depois < antes / 2)
    }

    /** O motivo do 16.4: a falha (vermelho) e a marca (laranja) quase se confundem para quem tem protanopia. */
    @Test fun falhaEMarcaPrecisamDeSinal() {
        val p = CorSegura.Daltonismo.PROTANOPIA
        val d = CorSegura.distancia(CorSegura.simular(Estilo.ERRO, p), CorSegura.simular(Estilo.ACENTO, p))
        assertTrue("distância $d", d < 60)
    }

    @Test fun comporAlfa() {
        assertEquals(0xFF808080.toInt(), CorSegura.compor(0x80FFFFFF.toInt(), preto))
        assertEquals(Estilo.FUNDO, CorSegura.compor(0x00FFFFFF, Estilo.FUNDO))
    }

    @Test fun sinalOuPalavra() {
        assertFalse(CorSegura.temSinalOuPalavra("●"))
        assertFalse(CorSegura.temSinalOuPalavra("● "))
        assertTrue(CorSegura.temSinalOuPalavra(CorSegura.SINAL_OK))
        assertTrue(CorSegura.temSinalOuPalavra("! "))
        assertTrue(CorSegura.temSinalOuPalavra("● Ao vivo"))
        assertTrue(CorSegura.temSinalOuPalavra("● ライブ"))
    }

    @Test fun coresDeEstado() {
        assertTrue(CorSegura.ehCorDeEstado(Estilo.ERRO))
        assertTrue(CorSegura.ehCorDeEstado(Estilo.ATENCAO))
        assertFalse(CorSegura.ehCorDeEstado(Estilo.TEXTO))
    }
}
