package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoDesligarRegraTest {
    private val min = 60_000L

    @Test fun semMidiaContaSoATecla() {
        assertEquals(30 * min, AutoDesligarRegra.prazo(0, 0, 30))
    }

    @Test fun filmeTocandoAdiaOPrazo() {
        // tecla no minuto 0, filme visto tocando no minuto 100: desliga 30 min depois do último som
        assertEquals(130 * min, AutoDesligarRegra.prazo(0, 100 * min, 30))
    }

    @Test fun tvAoVivoEsquecidaApagaNoTeto() {
        // som tocando o tempo todo (visto agora, minuto 300): o teto de 4 h desde a última tecla vence
        assertEquals(240 * min, AutoDesligarRegra.prazo(0, 300 * min, 30))
    }

    @Test fun tempoEscolhidoMaiorQueOTetoVale() {
        assertEquals(300 * min, AutoDesligarRegra.prazo(0, 400 * min, 300))
    }

    @Test fun teclaDepoisDaMidiaReiniciaTudo() {
        assertEquals(80 * min, AutoDesligarRegra.prazo(50 * min, 20 * min, 30))
    }
}
