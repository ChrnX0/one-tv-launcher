package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ideia 5: rotina que roda cena (JSON guarda a cena) e o girar dos editores. */
class CenaNaRotinaTest {
    @Test fun rotinaDeCenaSobreviveAoJson() {
        val r = Rotinas.Rotina(3, 7, 30, 0b0011111, Rotinas.Acao.CENA, cena = "Bom dia")
        assertEquals(listOf(r), Rotinas.deJson(Rotinas.paraJson(listOf(r))))
    }

    @Test fun rotinaAntigaSemCenaFicaVazia() {
        val l = Rotinas.deJson("""[{"id":1,"hora":8,"minuto":0,"dias":0,"acao":"LIGAR"}]""")
        assertEquals("", l.single().cena)
    }

    @Test fun girarDaVoltaComESemNenhuma() {
        val n = listOf("Noite", "Cinema")
        assertEquals("Noite", Cenas.girar(n, "", 1, comNenhuma = false))
        assertEquals("Cinema", Cenas.girar(n, "Noite", 1, comNenhuma = false))
        assertEquals("Noite", Cenas.girar(n, "Cinema", 1, comNenhuma = false))
        assertEquals("Cinema", Cenas.girar(n, "Noite", -1, comNenhuma = false))
        assertEquals("", Cenas.girar(n, "Cinema", 1, comNenhuma = true))
        assertEquals("Cinema", Cenas.girar(n, "", -1, comNenhuma = true))
        assertEquals("Noite", Cenas.girar(n, "apagada", 1, comNenhuma = false))
        assertEquals("", Cenas.girar(emptyList(), "", 1, comNenhuma = false))
    }
}

/** Ideia 4: a linha da tela Cenas conta as ações. */
class TelaCenasTest {
    @Test fun contaAcoesNosDoisFormatos() {
        assertEquals(2, Cenas.contarAcoes("""{"acoes":[{"tipo":"volume","nivel":5},{"tipo":"midia","comando":"pausar"}]}"""))
        assertEquals(1, Cenas.contarAcoes("""[{"tipo":"volume","nivel":5}]"""))
        assertEquals(0, Cenas.contarAcoes("estragado"))
        assertEquals(0, Cenas.contarAcoes("{}"))
    }
}
