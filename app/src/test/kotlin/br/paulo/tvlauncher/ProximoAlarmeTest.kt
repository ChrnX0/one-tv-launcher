package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class ProximoAlarmeTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private val fusoSp = TimeZone.getTimeZone(sp)

    /** "2026-11-10T06:00" no fuso [z] -> ms. */
    private fun ms(texto: String, z: ZoneId = sp) = LocalDateTime.parse(texto).atZone(z).toInstant().toEpochMilli()
    private fun texto(ms: Long, z: ZoneId = sp) = java.time.Instant.ofEpochMilli(ms).atZone(z).toLocalDateTime().toString()

    private val SEG_A_SEX = 0b0111110
    private val TODO_DIA = 0b1111111

    @Test fun hojeSeAindaNaoPassou() =   // 10/11/2026 é terça
        assertEquals("2026-11-10T07:00", texto(ProximoAlarme.calcular(7, 0, TODO_DIA, ms("2026-11-10T06:59"), fusoSp)!!))

    @Test fun amanhaSeJaPassou() =
        assertEquals("2026-11-11T07:00", texto(ProximoAlarme.calcular(7, 0, TODO_DIA, ms("2026-11-10T07:00"), fusoSp)!!))

    @Test fun naHoraExataJaContaComoPassado() =   // o alarme tocou às 07:00 e reagenda: não pode tocar de novo
        assertEquals("2026-11-11T07:00", texto(ProximoAlarme.calcular(7, 0, TODO_DIA, ms("2026-11-10T07:00"), fusoSp)!!))

    @Test fun diasUteisPulamOFimDeSemana() =   // sexta 13/11 às 08:00 -> segunda 16/11
        assertEquals("2026-11-16T07:00", texto(ProximoAlarme.calcular(7, 0, SEG_A_SEX, ms("2026-11-13T08:00"), fusoSp)!!))

    @Test fun soDomingoEspera6Dias() =   // segunda 16/11 -> domingo 22/11
        assertEquals("2026-11-22T09:30", texto(ProximoAlarme.calcular(9, 30, 0b0000001, ms("2026-11-16T10:00"), fusoSp)!!))

    @Test fun mesmoDiaDaSemanaJaPassadoVaiParaASemanaQueVem() =   // terça 10/11 às 08:00, alarme só de terça às 07:00
        assertEquals("2026-11-17T07:00", texto(ProximoAlarme.calcular(7, 0, 0b0000100, ms("2026-11-10T08:00"), fusoSp)!!))

    @Test fun umaVezSoEhOProximoHorario() {
        assertEquals("2026-11-10T23:00", texto(ProximoAlarme.calcular(23, 0, 0, ms("2026-11-10T22:00"), fusoSp)!!))
        assertEquals("2026-11-11T05:00", texto(ProximoAlarme.calcular(5, 0, 0, ms("2026-11-10T22:00"), fusoSp)!!))
    }

    @Test fun viradaDeAnoEDeMes() =
        assertEquals("2027-01-01T00:05", texto(ProximoAlarme.calcular(0, 5, TODO_DIA, ms("2026-12-31T23:59"), fusoSp)!!))

    @Test fun semDiaValidoDevolveNull() =   // bit 7 não é dia da semana
        assertNull(ProximoAlarme.calcular(7, 0, 0b10000000, ms("2026-11-10T06:00"), fusoSp))

    @Test fun tocaNoDia() {
        assertTrue(ProximoAlarme.tocaNoDia(0, 3))
        assertTrue(ProximoAlarme.tocaNoDia(SEG_A_SEX, 1))
        assertFalse(ProximoAlarme.tocaNoDia(SEG_A_SEX, 0))
        assertFalse(ProximoAlarme.tocaNoDia(SEG_A_SEX, 6))
    }

    // --- Horário de verão (o Brasil não tem desde 2019, mas o launcher é para qualquer casa) ---

    /** Nova York adianta em 08/03/2026 (domingo), 02:00 -> 03:00. Alarme 02:30 de segunda a sexta, visto no sábado. */
    @Test fun horaQueNaoExisteNoDomingoNaoAtrasaASegunda() {
        val ny = ZoneId.of("America/New_York")
        val r = ProximoAlarme.calcular(2, 30, SEG_A_SEX, ms("2026-03-07T12:00", ny), TimeZone.getTimeZone(ny))!!
        assertEquals("2026-03-09T02:30", texto(r, ny))   // a versão antiga dava 03:30
    }

    /** Brasil em 2018: 04/11 (domingo) 00:00 -> 01:00. Alarme diário 00:30 visto no sábado à noite. */
    @Test fun horarioDeVeraoAntigoDoBrasil() {
        val r = ProximoAlarme.calcular(0, 30, TODO_DIA, ms("2018-11-03T23:00"), fusoSp)!!
        // 00:30 de 04/11 não existe: o Calendar empurra para 01:30, que é o 1º instante "depois" daquela hora.
        assertEquals("2018-11-04T01:30", texto(r))
        // E o dia seguinte volta para 00:30 (a versão antiga carregava o 01:30 adiante).
        val depois = ProximoAlarme.calcular(0, 30, TODO_DIA, r, fusoSp)!!
        assertEquals("2018-11-05T00:30", texto(depois))
    }

    /** Quando o relógio atrasa (01:00 acontece duas vezes), o alarme toca uma vez só, na primeira. */
    @Test fun horaRepetidaTocaUmaVez() {
        val ny = ZoneId.of("America/New_York")   // 01/11/2026: 02:00 -> 01:00
        val tz = TimeZone.getTimeZone(ny)
        val r = ProximoAlarme.calcular(1, 30, TODO_DIA, ms("2026-10-31T12:00", ny), tz)!!
        assertEquals("2026-11-01T01:30", texto(r, ny))
        assertEquals("2026-11-02T01:30", texto(ProximoAlarme.calcular(1, 30, TODO_DIA, r, tz)!!, ny))
    }
}
