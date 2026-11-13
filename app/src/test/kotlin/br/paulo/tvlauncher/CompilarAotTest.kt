package br.paulo.tvlauncher

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompilarAotTest {
    @Test fun versaoNovaCompila() = assertTrue(CompilarAot.deveCompilar(200, 100, 0))
    @Test fun versaoJaFeitaNao() = assertFalse(CompilarAot.deveCompilar(200, 200, 0))
    @Test fun desisteDepoisDoLimite() = assertFalse(CompilarAot.deveCompilar(200, 100, CompilarAot.MAX_TENTATIVAS))
    @Test fun sucessoSoComLinhaSuccess() {
        assertTrue(CompilarAot.deuCerto("Success\n"))
        assertFalse(CompilarAot.deuCerto("Failure: package not found"))
        assertFalse(CompilarAot.deuCerto(null))
    }

    // Compilada no protetor (13/11/2026): reinicia no próximo apagar, uma vez por versão.
    @Test fun compiladaSemReiniciarReinicia() = assertTrue(CompilarAot.deveReiniciar(200, 200, 100))
    @Test fun jaReiniciadaNao() = assertFalse(CompilarAot.deveReiniciar(200, 200, 200))
    @Test fun naoCompiladaNaoReinicia() = assertFalse(CompilarAot.deveReiniciar(200, 100, 0))
}
