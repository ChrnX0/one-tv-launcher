package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class AvisoRegrasTest {
    private val min = 60_000L

    @Test fun passageiroSomeEmDoisMinutos() {
        assertEquals(min, AvisoRegras.restanteMs(0, "PASSAGEIRO", min))
        assertEquals(0, AvisoRegras.restanteMs(0, "PASSAGEIRO", 2 * min))
    }

    @Test fun atencaoFicaSeisHoras() {
        assertEquals(min, AvisoRegras.restanteMs(0, "ATENCAO", 359 * min))
        assertEquals(0, AvisoRegras.restanteMs(0, "ATENCAO", 360 * min))
    }

    @Test fun chuvaFicaDuasHoras() = assertEquals(0, AvisoRegras.restanteMs(0, "CHUVA", 120 * min))

    @Test fun avisoAntigoSemClasseEPassageiro() {
        // O "O Claude olhou a tela" guardado antes desta versão não tem classe: tem de sumir, não ficar 6 h.
        assertEquals(0, AvisoRegras.restanteMs(0, null, 3 * min))
    }

    @Test fun relogioVoltandoNaoPrendeCartao() = assertEquals(0, AvisoRegras.restanteMs(10 * min, "ATENCAO", 0))
}
