package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ideia 32: agenda de todas as contas juntas, sem repetir o mesmo compromisso. */
class AgendaTest {
    private fun ev(t: String, i: Long, f: Long = i + 3_600_000) = Agenda.Evento(t, i, f, false, 0)

    @Test fun mesmoCompromissoEmDuasContasApareceUmaVez() {
        val l = Agenda.juntar(listOf(ev("Dentista", 2000), ev("Aniversário", 1000), ev("dentista ", 2000)))
        assertEquals(listOf("Aniversário", "Dentista"), l.map { it.titulo })
    }

    @Test fun mesmoTituloEmHorarioDiferenteFica() {
        assertEquals(2, Agenda.juntar(listOf(ev("Futebol", 1000), ev("Futebol", 5000))).size)
        assertEquals(2, Agenda.juntar(listOf(ev("Futebol", 1000, 2000), ev("Futebol", 1000, 3000))).size)
    }
}
