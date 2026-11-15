package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RotinasTest {
    @Test fun idaEVoltaPeloJson() {
        val l = listOf(
            Rotinas.Rotina(2, 23, 0, 0b1111111, Rotinas.Acao.DESLIGAR, ocioso = 60),
            Rotinas.Rotina(1, 7, 30, 0b0111110, Rotinas.Acao.ABRIR, pacote = "com.exemplo.jornal"),
        )
        assertEquals(l.sortedBy { it.hora }, Rotinas.deJson(Rotinas.paraJson(l)))
    }

    @Test fun itemEstragadoSaiSozinho() {
        val l = Rotinas.deJson("""[{"id":1,"hora":7,"minuto":0,"dias":62,"acao":"LIGAR"},{"id":2,"hora":8,"acao":"VOAR"},"lixo"]""")
        assertEquals(1, l.size)
        assertEquals(Rotinas.Acao.LIGAR, l[0].acao)
    }

    @Test fun naoQuebraComTextoInvalido() = assertEquals(emptyList<Rotinas.Rotina>(), Rotinas.deJson("{nada"))

    @Test fun desligarComOcio() {
        assertTrue(Rotinas.podeDesligar(0, 0, false))          // sem condição: sempre
        assertFalse(Rotinas.podeDesligar(60, 120, false))      // sem ver as teclas não arrisca
        assertFalse(Rotinas.podeDesligar(60, 10, true))        // alguém mexeu há 10 min
        assertTrue(Rotinas.podeDesligar(60, 61, true))
    }

    /** 15/11/2026: duas rotinas no mesmo minuto rodam juntas (antes a segunda pulava para o dia seguinte, todo dia). */
    @Test fun rotinasNoMesmoMinutoRodamJuntas() {
        val fuso = java.util.TimeZone.getDefault()
        val c = java.util.Calendar.getInstance(fuso).apply { set(2026, 10, 16, 22, 0, 0); set(java.util.Calendar.MILLISECOND, 0) }
        val previsto = c.timeInMillis
        val todos = 0b1111111
        val a = Rotinas.Rotina(1, 22, 0, todos, Rotinas.Acao.CENA, cena = "Noite")
        val b = Rotinas.Rotina(2, 22, 0, todos, Rotinas.Acao.DESLIGAR)
        val outra = Rotinas.Rotina(3, 22, 1, todos, Rotinas.Acao.DESLIGAR)
        val desligada = Rotinas.Rotina(4, 22, 0, todos, Rotinas.Acao.LIGAR, ativa = false)
        assertEquals(previsto, Rotinas.horaPrevista(a, previsto + 40_000))   // alarme chegou 40 s atrasado
        assertEquals(listOf(b), Rotinas.mesmoHorario(listOf(a, b, outra, desligada), a, previsto))
    }
}
