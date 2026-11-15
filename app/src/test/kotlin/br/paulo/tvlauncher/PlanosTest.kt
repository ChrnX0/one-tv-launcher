package br.paulo.tvlauncher

import br.paulo.tvlauncher.Planos.Plano
import br.paulo.tvlauncher.Planos.Recurso
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanosTest {
    @Test fun vendaDesligadaNaoBloqueiaNada() = assertFalse("ligar a venda é decisão do Paulo", Planos.VENDA_ATIVA)

    @Test fun ultraTemTudoEBasicoNadaPago() {
        Recurso.values().forEach { assertTrue(Planos.permite(Plano.ULTRA, it)); assertFalse(it.name, Planos.permite(Plano.BASICO, it)) }
    }

    @Test fun divisaoDoDoc() {
        assertTrue(Planos.permite(Plano.PRO, Recurso.IPTV_VARIAS_LISTAS))
        assertFalse(Planos.permite(Plano.PRO, Recurso.FICHAS_TMDB))
        assertEquals(1, Planos.listasIptv(Plano.BASICO)); assertEquals(3, Planos.tvsPorCompra(Plano.ULTRA))
        assertEquals(Recurso.values().size, Planos.MINIMO.size)   // todo recurso tem dono
    }
}
