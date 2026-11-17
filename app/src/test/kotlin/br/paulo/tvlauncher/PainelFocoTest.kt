package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Foco do painel rápido depois de redesenhar os avisos (Sala, 17/11/2026: o foco pulava para o 1º aviso). */
class PainelFocoTest {
    // Lista típica: título de seção (não focável), 2 notificações, título, 3 avisos da TV.
    private val tags = listOf(null, "n:a", "n:b", null, "n:tv:1", "n:tv:2", "n:tv:3")
    private val focaveis = tags.map { it != null }

    @Test fun mesmaLinhaContinuaComFoco() {
        assertEquals(5, PainelRapidoActivity.linhaDoFoco(tags, focaveis, "n:tv:2", 5))
        // A lista ganhou uma notificação no topo: o foco segue a linha, não o número dela.
        val novas = listOf(null, "n:z", "n:a", "n:b", null, "n:tv:1", "n:tv:2", "n:tv:3")
        assertEquals(6, PainelRapidoActivity.linhaDoFoco(novas, novas.map { it != null }, "n:tv:2", 5))
    }

    @Test fun linhaDispensadaPassaParaAQueFicouNoLugar() {
        val semTv2 = listOf(null, "n:a", "n:b", null, "n:tv:1", "n:tv:3")
        assertEquals(5, PainelRapidoActivity.linhaDoFoco(semTv2, semTv2.map { it != null }, "n:tv:2", 5))
    }

    @Test fun ultimaDispensadaVoltaParaAAnterior() {
        val semTv3 = listOf(null, "n:a", "n:b", null, "n:tv:1", "n:tv:2")
        assertEquals(5, PainelRapidoActivity.linhaDoFoco(semTv3, semTv3.map { it != null }, "n:tv:3", 6))
    }

    @Test fun pulaTituloDeSecao() {
        // Saiu a última notificação: no lugar dela ficou o título "AVISOS DA TV"; o foco vai ao 1º aviso abaixo.
        val l = listOf(null, "n:a", null, "n:tv:1")
        assertEquals(3, PainelRapidoActivity.linhaDoFoco(l, l.map { it != null }, "n:b", 2))
    }

    @Test fun semFocoNaListaNaoEscolhe() {
        assertNull(PainelRapidoActivity.linhaDoFoco(tags, focaveis, null, -1))
        assertNull(PainelRapidoActivity.linhaDoFoco(emptyList(), emptyList(), "n:a", 1))
        val soTitulo = listOf<String?>(null)
        assertNull(PainelRapidoActivity.linhaDoFoco(soTitulo, listOf(false), "n:a", 1))
    }
}
