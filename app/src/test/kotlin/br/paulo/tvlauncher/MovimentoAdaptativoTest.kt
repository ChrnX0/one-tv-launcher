package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.IconeCruz
import br.paulo.tvlauncher.home.MovimentoAdaptativo
import br.paulo.tvlauncher.home.MovimentoAdaptativo.Nivel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovimentoAdaptativoTest {

    @Test fun palpitePeloAparelho() {
        assertEquals(Nivel.EQUILIBRADO, MovimentoAdaptativo.peloAparelho(1936, 4, false))   // Chromecast da Sala (2 GB, 4 núcleos)
        assertEquals(Nivel.ECONOMICO, MovimentoAdaptativo.peloAparelho(1024, 4, false))
        assertEquals(Nivel.ECONOMICO, MovimentoAdaptativo.peloAparelho(4096, 8, poucaMemoria = true))
        assertEquals(Nivel.ECONOMICO, MovimentoAdaptativo.peloAparelho(4096, 2, false))
        assertEquals(Nivel.COMPLETO, MovimentoAdaptativo.peloAparelho(3072, 8, false))      // Shield, TVs boas
        assertEquals(Nivel.EQUILIBRADO, MovimentoAdaptativo.peloAparelho(8192, 4, false))
        assertEquals(Nivel.COMPLETO, MovimentoAdaptativo.peloAparelho(0, 0, false))          // sem leitura: não pune
    }

    @Test fun quadroLentoEDuasBatidasPerdidas() {
        assertEquals(34.33f, MovimentoAdaptativo.limiteQuadroMs(60f), 0.01f)
        assertEquals(17.67f, MovimentoAdaptativo.limiteQuadroMs(120f), 0.01f)
        assertEquals(34.33f, MovimentoAdaptativo.limiteQuadroMs(0f), 0.01f)   // tela que não diz a frequência
    }

    @Test fun rodadaPesada() {
        assertFalse(MovimentoAdaptativo.pesado(100, 200))                       // rodada incompleta não julga
        assertFalse(MovimentoAdaptativo.pesado(19, 240))                        // 7,9 %
        assertTrue(MovimentoAdaptativo.pesado(20, 240))                         // 8,3 %
    }

    @Test fun desceUmDegrauENuncaParaSozinho() {
        assertEquals(Nivel.EQUILIBRADO, MovimentoAdaptativo.descer(Nivel.COMPLETO))
        assertEquals(Nivel.ECONOMICO, MovimentoAdaptativo.descer(Nivel.EQUILIBRADO))
        assertEquals(Nivel.ECONOMICO, MovimentoAdaptativo.descer(Nivel.ECONOMICO))
        assertEquals(Nivel.PARADO, MovimentoAdaptativo.descer(Nivel.PARADO))
    }

    @Test fun rebaixamentoValeUmaSemana() {
        val dia = 24 * 3_600_000L
        assertEquals(Nivel.ECONOMICO, MovimentoAdaptativo.efetivo(Nivel.EQUILIBRADO, Nivel.ECONOMICO, 0L, 3 * dia, false))
        assertEquals(Nivel.EQUILIBRADO, MovimentoAdaptativo.efetivo(Nivel.EQUILIBRADO, Nivel.ECONOMICO, 0L, 8 * dia, false))
        assertEquals(Nivel.EQUILIBRADO, MovimentoAdaptativo.efetivo(Nivel.EQUILIBRADO, null, 0L, 0L, false))
        // aparelho trocado por um mais fraco: vale o menor
        assertEquals(Nivel.ECONOMICO, MovimentoAdaptativo.efetivo(Nivel.ECONOMICO, Nivel.EQUILIBRADO, 0L, dia, false))
        assertEquals(Nivel.PARADO, MovimentoAdaptativo.efetivo(Nivel.COMPLETO, null, 0L, 0L, semAnimacao = true))
    }

    @Test fun quantoCadaNivelAnima() {
        assertEquals(IconeCruz.DESCANSO_ESCOLHIDO_MS, MovimentoAdaptativo.descansoEscolhido(Nivel.COMPLETO))
        assertEquals(IconeCruz.DESCANSO_CATEGORIA_MS, MovimentoAdaptativo.descansoCategoria(Nivel.COMPLETO))
        assertEquals(MovimentoAdaptativo.UMA_VEZ_MS, MovimentoAdaptativo.descansoCategoria(Nivel.EQUILIBRADO))   // uma vez ao chegar
        assertEquals(MovimentoAdaptativo.UMA_VEZ_MS, MovimentoAdaptativo.descansoEscolhido(Nivel.ECONOMICO))
        assertNull(MovimentoAdaptativo.descansoCategoria(Nivel.ECONOMICO))
        assertNull(MovimentoAdaptativo.descansoEscolhido(Nivel.PARADO))
        // uma passada e depois o "descanso" de um dia: parada o resto do tempo
        assertEquals(-1f, IconeCruz.faseEm(IconeCruz.DURACAO_MS + 60_000L, IconeCruz.DURACAO_MS, MovimentoAdaptativo.UMA_VEZ_MS))
        assertTrue(MovimentoAdaptativo.atrasoCategoria(Nivel.EQUILIBRADO, 4) < 2000L)                     // em onda, logo
    }
}
