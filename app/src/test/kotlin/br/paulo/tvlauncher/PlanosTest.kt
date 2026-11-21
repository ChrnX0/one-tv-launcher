package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.Planos
import br.paulo.tvlauncher.settings.Planos.Plano
import br.paulo.tvlauncher.settings.Planos.Recurso
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
        assertFalse(Planos.permite(Plano.PRO, Recurso.CLIMA_3_MODELOS))
        assertFalse(Planos.permite(Plano.BASICO, Recurso.FICHAS_TMDB))
        assertEquals(1, Planos.listasIptv(Plano.BASICO)); assertEquals(3, Planos.tvsPorCompra(Plano.ULTRA))
        assertEquals(Recurso.values().size, Planos.MINIMO.size)   // todo recurso tem dono
    }

    /** Degraus 3 e 4 (Paulo, 17/11/2026): Pro o que a TV faz sozinha; Ultra o que tem custo no servidor. */
    @Test fun divisaoDosDegraus3e4() {
        for (r in listOf(Recurso.FAMILIA_LINK, Recurso.RECADO_VIDEO, Recurso.SOM_NO_FONE, Recurso.CONTINUAR_OUTRA_TV, Recurso.PLUGINS))
            assertEquals(r.name, Plano.PRO, Planos.MINIMO[r])
        for (r in listOf(Recurso.VARIAS_CASAS, Recurso.SESSAO_JUNTO, Recurso.SERVIDOR_SOFALY))
            assertEquals(r.name, Plano.ULTRA, Planos.MINIMO[r])
    }

    /** Fontes de dados (Paulo, 19/11/2026): grátis no Básico, pago a partir do Pro, o mais especial no Ultra. */
    @Test fun fontesPagas() {
        for (r in listOf(Recurso.FICHAS_TMDB, Recurso.CLIMA_PRO, Recurso.SIMKL_NOTA_TODOS)) assertEquals(r.name, Plano.PRO, Planos.MINIMO[r])
        assertEquals(Plano.ULTRA, Planos.MINIMO[Recurso.CLIMA_3_MODELOS])
    }
}
