package br.paulo.tvlauncher

import br.paulo.tvlauncher.network.CastigoIp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CastigoIpTest {
    @Test fun `depois do nao o endereco espera sem perguntar`() {
        val c = CastigoIp(duracaoMs = 1000)
        assertFalse(c.emCastigo("192.168.1.9", 0))
        c.castigar("192.168.1.9", 0)
        assertTrue(c.emCastigo("192.168.1.9", 999))
        assertFalse(c.emCastigo("192.168.1.10", 999))   // outro computador continua podendo pedir
        assertFalse(c.emCastigo("192.168.1.9", 1000))
    }

    @Test fun `teto de enderecos guardados`() {
        val c = CastigoIp(duracaoMs = 1000, maximo = 3)
        for (i in 1..5) c.castigar("10.0.0.$i", 0)
        assertFalse(c.emCastigo("10.0.0.1", 1))
        assertTrue(c.emCastigo("10.0.0.5", 1))
    }
}
