package br.paulo.tvlauncher

import br.paulo.tvlauncher.HomeDesvio.Desvio
import org.junit.Assert.assertEquals
import org.junit.Test

/** Trava contra laço da troca da HOME do launcherx pela nossa (bug de 14/11/2026: 2ª aparição ignorada). */
class HomeDesvioTest {
    @Test fun primeiraVezTrocaJa() {
        assertEquals(Desvio.Agora, HomeDesvio.decidir(10_000, null, 0))
    }

    @Test fun segundaAparicaoLogoDepoisConfereQuandoATravaVencer() {
        // Caso da Sala: nossa HOME aberta em t=0 e a do Google volta 800 ms depois. Antes: ignorada para sempre.
        assertEquals(Desvio.Depois(700), HomeDesvio.decidir(10_800, 10_000, 1))
    }

    @Test fun depoisDoIntervaloTrocaJa() {
        assertEquals(Desvio.Agora, HomeDesvio.decidir(11_500, 10_000, 1))
    }

    @Test fun algoReabrindoSemParar_desiste() {
        assertEquals(Desvio.Desiste, HomeDesvio.decidir(50_000, 40_000, HomeDesvio.MAX_DESVIOS_NA_JANELA))
    }
}
