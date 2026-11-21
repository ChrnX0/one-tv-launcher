package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.PainelTls
import java.security.KeyPairGenerator
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Chave e certificado de pares diferentes (troca que parou no meio) não podem ser usados juntos. */
class PainelTlsParTest {
    @Test fun conferePar() {
        val g = KeyPairGenerator.getInstance("EC").apply { initialize(256) }
        val a = g.generateKeyPair(); val b = g.generateKeyPair()
        assertTrue(PainelTls.mesmoPar(a.private, a.public))
        assertFalse(PainelTls.mesmoPar(a.private, b.public))
    }
}
