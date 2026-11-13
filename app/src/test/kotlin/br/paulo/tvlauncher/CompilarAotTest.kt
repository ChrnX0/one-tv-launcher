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
}
