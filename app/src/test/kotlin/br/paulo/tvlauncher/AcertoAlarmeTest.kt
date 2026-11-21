package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.AcertoAlarme
import br.paulo.tvlauncher.alarm.AcertoAlarme.Resultado
import br.paulo.tvlauncher.alarm.AcertoAlarme.Toque
import org.junit.Assert.assertEquals
import org.junit.Test

/** Elevação 10.2: "tocou na hora em N de N", contando também os perdidos. */
class AcertoAlarmeTest {
    @Test fun classificaPeloAtraso() {
        assertEquals(Resultado.NA_HORA, AcertoAlarme.classificar(1_000_000, 1_000_000 + 59_000))
        assertEquals(Resultado.ATRASADO, AcertoAlarme.classificar(1_000_000, 1_000_000 + 61_000))
    }

    @Test fun mesmoToqueContaUmaVezEValeOPior() {
        var l = AcertoAlarme.anotar(emptyList(), Toque(10, Resultado.NA_HORA))
        l = AcertoAlarme.anotar(l, Toque(10, Resultado.NA_HORA))   // o Android entregou duas vezes
        assertEquals(1, l.size)
        l = AcertoAlarme.anotar(l, Toque(10, Resultado.PERDIDO))
        assertEquals(Resultado.PERDIDO, l.single().resultado)
        l = AcertoAlarme.anotar(l, Toque(10, Resultado.NA_HORA))   // nunca melhora depois
        assertEquals(Resultado.PERDIDO, l.single().resultado)
    }

    @Test fun resumoContaTodos() {
        var l = emptyList<Toque>()
        l = AcertoAlarme.anotar(l, Toque(1, Resultado.NA_HORA))
        l = AcertoAlarme.anotar(l, Toque(2, Resultado.ATRASADO))
        l = AcertoAlarme.anotar(l, Toque(3, Resultado.PERDIDO))
        l = AcertoAlarme.anotar(l, Toque(0, Resultado.NA_HORA))   // sem hora prevista (timer): fora
        val r = AcertoAlarme.resumo(l)
        assertEquals(1, r.naHora); assertEquals(3, r.total); assertEquals(3L, r.ultimoEm)
    }

    @Test fun guardaSoOsUltimosEIdaEVolta() {
        var l = emptyList<Toque>()
        for (i in 1..AcertoAlarme.GUARDAR + 10) l = AcertoAlarme.anotar(l, Toque(i.toLong(), Resultado.NA_HORA))
        assertEquals(AcertoAlarme.GUARDAR, l.size)
        assertEquals(11L, l.first().previsto)
        assertEquals(l, AcertoAlarme.ler(AcertoAlarme.texto(l)))
        assertEquals(emptyList<Toque>(), AcertoAlarme.ler("{"))
    }
}
