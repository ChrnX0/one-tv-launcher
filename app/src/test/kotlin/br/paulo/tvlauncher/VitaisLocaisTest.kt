package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.VitaisLocais
import br.paulo.tvlauncher.observer.VitaisLocais.Saida
import br.paulo.tvlauncher.observer.VitaisLocais.Tipo
import org.junit.Assert.assertEquals
import org.junit.Test

/** "Vitals" locais (degrau 2, item 8): quedas e travadas do launcher por dia. */
class VitaisLocaisTest {
    private val saidas = listOf(
        Saida(1_000, "2026-11-15", Tipo.QUEDA, naTela = true),
        Saida(2_000, "2026-11-16", Tipo.TRAVADA, naTela = false),
    )

    @Test fun somaSoUmaVez() {
        var e = VitaisLocais.abriu(VitaisLocais.Estado(), "2026-11-16")
        e = VitaisLocais.somarSaidas(e, saidas)
        e = VitaisLocais.somarSaidas(e, saidas)   // o Android devolve a mesma lista no processo seguinte
        val r = VitaisLocais.resumo(e, "2026-11-16", "2026-11-10")
        assertEquals(1, r.quedas); assertEquals(1, r.travadas); assertEquals(1, r.percebidas)
        assertEquals(2, r.diasComProblema); assertEquals(2, r.diasComUso); assertEquals(1, r.aberturas)
        assertEquals(2, r.nivel)
    }

    @Test fun travadaEmSegundoPlanoEhAtencao() {
        val e = VitaisLocais.somarSaidas(VitaisLocais.Estado(), listOf(saidas[1]))
        assertEquals(1, VitaisLocais.resumo(e, "2026-11-16", "2026-11-10").nivel)
        assertEquals(0, VitaisLocais.resumo(VitaisLocais.Estado(), "2026-11-16", "2026-11-10").nivel)
    }

    @Test fun guardaSo30DiasEIdaEVolta() {
        var e = VitaisLocais.Estado()
        for (d in 1..31) e = VitaisLocais.abriu(e, "2026-10-%02d".format(d))
        for (d in 1..5) e = VitaisLocais.abriu(e, "2026-11-%02d".format(d))
        assertEquals(VitaisLocais.DIAS, e.dias.size)
        assertEquals("2026-11-05", e.dias.last().data)
        val volta = VitaisLocais.ler(VitaisLocais.texto(VitaisLocais.somarSaidas(e, saidas)))
        assertEquals(VitaisLocais.somarSaidas(e, saidas), volta)
        assertEquals(VitaisLocais.Estado(), VitaisLocais.ler("lixo"))
    }

    @Test fun janelaDosSeteDias() {
        val e = VitaisLocais.somarSaidas(VitaisLocais.Estado(), listOf(Saida(1, "2026-11-01", Tipo.QUEDA, true)))
        assertEquals(0, VitaisLocais.resumo(e, "2026-11-16", "2026-11-10").quedas)
    }
}
