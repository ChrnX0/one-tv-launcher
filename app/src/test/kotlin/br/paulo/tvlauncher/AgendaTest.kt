package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.Agenda
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

    @Test fun feriadosEmDoisIdiomasFicaOdaTv() {
        val pt = "pt-br.brazilian#holiday@group.v.calendar.google.com"; val en = "en.brazilian#holiday@group.v.calendar.google.com"
        val l = listOf(Agenda.Evento("Proclamação da República", 1000, 2000, true, 0, pt),
            Agenda.Evento("Republic Proclamation Day", 1000, 2000, true, 0, en), ev("Dentista", 3000))
        assertEquals(listOf("Proclamação da República", "Dentista"), Agenda.juntar(l, "pt").map { it.titulo })
        assertEquals(listOf("Republic Proclamation Day", "Dentista"), Agenda.juntar(l, "en").map { it.titulo })
        assertEquals(2, Agenda.juntar(l, "es").size)   // idioma sem agenda própria: fica só a 1ª (pt)
    }

    @Test fun idiomaDaAgendaDeFeriados() {
        assertEquals("pt", Agenda.idiomaFeriados("pt-br.brazilian#holiday@group.v.calendar.google.com"))
        assertEquals("en", Agenda.idiomaFeriados("en.brazilian#holiday@group.v.calendar.google.com"))
        assertEquals(null, Agenda.idiomaFeriados("alguem@gmail.com"))
    }
}
