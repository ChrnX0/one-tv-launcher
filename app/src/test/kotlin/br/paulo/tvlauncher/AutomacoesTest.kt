package br.paulo.tvlauncher

import android.view.KeyEvent
import br.paulo.tvlauncher.Automacoes.Quando
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ideia 1: os quatro tipos numa lista só, com um formato só. */
class AutomacoesTest {
    @Test fun juntaOsQuatroTiposNaOrdem() {
        val rotinas = listOf(
            Rotinas.Rotina(1, 7, 0, 0b0111110, Rotinas.Acao.CENA, cena = "Manhã"),
            Rotinas.Rotina(2, 23, 30, 0, Rotinas.Acao.DESLIGAR, ocioso = 60, ativa = false),
        )
        val remap = mapOf(KeyEvent.KEYCODE_PROG_RED to mapOf(RemapTeclas.Gesto.SEGURAR to "cena:Noite", RemapTeclas.Gesto.TOQUE to "app:a.b"))
        val r = Automacoes.montar(rotinas, remap, mapOf(Gatilhos.Evento.CHUVA to "Fechar"), listOf("Cinema"))
        assertEquals(listOf("cena:Manhã", "desligar", "app:a.b", "cena:Noite", "cena:Fechar", "cena:Cinema"), r.map { it.faz })
        assertEquals(listOf("rotina:1", "rotina:2", "tecla:${KeyEvent.KEYCODE_PROG_RED}", "tecla:${KeyEvent.KEYCODE_PROG_RED}",
            "evento:chuva", "link:Cinema"), r.map { it.ref })
        assertEquals(Quando.Horario(23, 30, 0), r[1].quando)
        assertEquals(false, r[1].ativa)
        assertEquals(Quando.Botao(KeyEvent.KEYCODE_PROG_RED, RemapTeclas.Gesto.TOQUE), r[2].quando)   // toque antes de segurar
    }

    @Test fun jsonDizOTipoDeCadaGatilho() {
        val r = Automacoes.montar(listOf(Rotinas.Rotina(1, 7, 5, 0, Rotinas.Acao.LIGAR)), emptyMap(), mapOf(Gatilhos.Evento.TV_LIGOU to "Luz"), emptyList())
        val j = Automacoes.json(r)
        assertEquals("horario", j.getJSONObject(0).getJSONObject("quando").getString("tipo"))
        assertEquals("07:05", j.getJSONObject(0).getJSONObject("quando").getString("hora"))
        assertEquals("tv_ligou", j.getJSONObject(1).getJSONObject("quando").getString("evento"))
        assertEquals("cena:Luz", j.getJSONObject(1).getString("faz"))
    }
}
