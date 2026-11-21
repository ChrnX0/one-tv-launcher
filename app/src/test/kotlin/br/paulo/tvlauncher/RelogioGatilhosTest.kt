package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.Aniversarios
import br.paulo.tvlauncher.alarm.FiltroRede
import br.paulo.tvlauncher.alarm.RelogioGatilhos
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RelogioGatilhosTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private fun ms(a: Int, m: Int, d: Int, h: Int, min: Int = 0) =
        Calendar.getInstance(utc).apply { clear(); set(a, m - 1, d, h, min) }.timeInMillis

    @Test fun nascer_e_por_do_sol_caem_uma_vez() {
        val por = ms(2026, 11, 17, 18, 30)
        assertTrue(RelogioGatilhos.caiu(por - 30_000, por + 30_000, por))
        assertFalse(RelogioGatilhos.caiu(por, por + 60_000, por))                    // já tinha caído na conferência anterior
        assertFalse(RelogioGatilhos.caiu(por - 120_000, por - 60_000, por))          // ainda não
        assertFalse(RelogioGatilhos.caiu(por - 3_600_000, por + 60_000, por))        // TV dormindo 1 h: não dispara atrasado
        assertFalse(RelogioGatilhos.caiu(por - 30_000, por + 30_000, null))          // sem localização
    }

    @Test fun aniversario() {
        val l = listOf(Aniversarios.Pessoa("Vó", 17, 11, 1950))
        assertEquals(ms(2026, 11, 17, 9), RelogioGatilhos.aniversarioHoje(l, ms(2026, 11, 17, 8), utc))
        assertNull(RelogioGatilhos.aniversarioHoje(l, ms(2026, 11, 18, 8), utc))
        // 29/02 em ano que não é bissexto: comemora em 01/03
        assertEquals(ms(2027, 3, 1, 9), RelogioGatilhos.aniversarioHoje(listOf(Aniversarios.Pessoa("Léo", 29, 2)), ms(2027, 3, 1, 7), utc))
    }

    @Test fun compromisso() {
        val ini = ms(2026, 11, 17, 15)
        assertTrue(RelogioGatilhos.compromissoComecou(ini - 30_000, ini + 30_000, listOf(ini to false)))
        assertFalse(RelogioGatilhos.compromissoComecou(ini - 30_000, ini + 30_000, listOf(ini to true)))   // dia inteiro não
        assertFalse(RelogioGatilhos.compromissoComecou(ini + 30_000, ini + 90_000, listOf(ini to false)))
    }

    @Test fun internet_caiu_e_voltou() {
        val f = FiltroRede(60_000)
        f.perdeu(0)
        assertNull(f.conferir(30_000))                                     // Wi-Fi piscou: ainda não
        assertNull(f.voltou())                                             // voltou antes de "cair": nada
        f.perdeu(100_000)
        assertEquals(Gatilhos.Evento.INTERNET_CAIU, f.conferir(161_000))
        assertNull(f.conferir(300_000))                                    // uma vez só
        assertEquals(Gatilhos.Evento.INTERNET_VOLTOU, f.voltou())
        assertNull(f.voltou())
    }

    @Test fun eventos_novos_gravam_e_voltam() {
        val m = mapOf(Gatilhos.Evento.POR_DO_SOL to "Noite", Gatilhos.Evento.CAMPAINHA to "Porta", Gatilhos.Evento.FILME_ACABOU to "Luz",
            Gatilhos.Evento.PROGRAMA_COMECOU to "Jogo", Gatilhos.Evento.ALGUEM_CHEGOU to "Oi", Gatilhos.Evento.RECADO_CHEGOU to "Pisca",
            Gatilhos.Evento.ANIVERSARIO to "Festa", Gatilhos.Evento.COMPROMISSO to "Lembrar", Gatilhos.Evento.INTERNET_CAIU to "Aviso",
            Gatilhos.Evento.INTERNET_VOLTOU to "Ok", Gatilhos.Evento.NASCER_DO_SOL to "Bom dia")
        assertEquals(m, Gatilhos.lerJson(Gatilhos.paraJson(m)))
        assertEquals(16, Gatilhos.Evento.entries.size)
    }
}
