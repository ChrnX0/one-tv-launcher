package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

class AlarmePerdidoTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private val fuso = TimeZone.getTimeZone(sp)
    private fun ms(texto: String) = LocalDateTime.parse(texto).atZone(sp).toInstant().toEpochMilli()
    private fun texto(ms: Long) = java.time.Instant.ofEpochMilli(ms).atZone(sp).toLocalDateTime().toString()

    private val TODO_DIA = 0b1111111
    private val SEG_A_SEX = 0b0111110
    private val diario = AlarmePerdido.Item(1, 6, 30, TODO_DIA, true)

    private fun avaliar(itens: List<AlarmePerdido.Item>, devido: String, id: Int, agora: String, resolvido: Long = 0) =
        AlarmePerdido.avaliar(itens, ms(devido), id, resolvido, ms(agora), fuso)

    // ---------------------------------------------------------------- alarme perdido

    @Test fun tvDesligadaNaHoraViraAviso() {   // 10/11/2026 é terça; ligou às 08:00, o das 06:30 não tocou
        val a = avaliar(listOf(diario), "2026-11-10T06:30", 1, "2026-11-10T08:00")
        assertNull(a.tocarAgora)
        assertEquals(listOf("2026-11-10T06:30"), a.perdidos.map { texto(it.em) })
        assertTrue(a.desligar.isEmpty())   // repete todo dia: continua ligado
    }

    @Test fun reiniciouNaHoraTocaJa() {   // reiniciou às 06:29 e voltou às 06:33: ainda dá para acordar
        val a = avaliar(listOf(diario), "2026-11-10T06:30", 1, "2026-11-10T06:33")
        assertEquals(ms("2026-11-10T06:30"), a.tocarAgora!!.em)
        assertTrue(a.perdidos.isEmpty())
    }

    @Test fun ateQuinzeMinutosAindaToca() {
        assertEquals(1, avaliar(listOf(diario), "2026-11-10T06:30", 1, "2026-11-10T06:45").tocarAgora?.id)
        assertNull(avaliar(listOf(diario), "2026-11-10T06:30", 1, "2026-11-10T06:46").tocarAgora)
    }

    @Test fun devidoNoFuturoNadaAFazer() =   // o relógio voltou (boot com hora velha): ainda não é hora de nada
        assertTrue(avaliar(listOf(diario), "2026-11-10T06:30", 1, "2026-11-10T06:00").vazia)

    @Test fun jaResolvidoNaoAvisaDeNovo() =   // tocou (ou já avisou): conferir de novo não repete o aviso
        assertTrue(avaliar(listOf(diario), "2026-11-10T06:30", 1, "2026-11-10T08:00", resolvido = ms("2026-11-10T06:30")).vazia)

    @Test fun nadaAnotadoNadaAFazer() =
        assertTrue(AlarmePerdido.avaliar(listOf(diario), 0, 0, 0, ms("2026-11-10T08:00"), fuso).vazia)

    @Test fun alarmeDesligadoNaoContaComoPerdido() =
        assertTrue(avaliar(listOf(diario.copy(ativo = false)), "2026-11-10T06:30", 1, "2026-11-10T08:00").perdidos.isEmpty())

    @Test fun variosAlarmesNaMesmaManha() {
        val itens = listOf(diario, AlarmePerdido.Item(2, 7, 0, TODO_DIA, true), AlarmePerdido.Item(3, 9, 0, TODO_DIA, true))
        val a = avaliar(itens, "2026-11-10T06:30", 1, "2026-11-10T07:30")
        assertEquals(listOf(1, 2), a.perdidos.map { it.id })   // o das 9:00 ainda não chegou
        assertEquals("6:30 e 7:00", AlarmePerdido.juntarHoras(a.perdidos))
    }

    @Test fun umaVezSoPerdidoEDesligado() {   // não pode ir sozinho para amanhã
        val unico = AlarmePerdido.Item(5, 6, 30, 0, true)
        val a = avaliar(listOf(unico), "2026-11-10T06:30", 5, "2026-11-10T09:00")
        assertEquals(setOf(5), a.desligar)
        assertEquals(1, a.perdidos.size)
    }

    @Test fun umaVezSoTocandoAgoraNaoEDesligadoAqui() {   // vai tocar agora; quem desliga é o "Parar" (Despertador.tocou)
        val unico = AlarmePerdido.Item(5, 6, 30, 0, true)
        val a = avaliar(listOf(unico), "2026-11-10T06:30", 5, "2026-11-10T06:31")
        assertEquals(5, a.tocarAgora?.id)
        assertTrue(a.desligar.isEmpty())
    }

    @Test fun tvDesligadaVariosDiasAvisaSoAsUltimas24h() {   // devido de sábado, ligou terça 08:00
        val a = avaliar(listOf(diario), "2026-11-07T06:30", 1, "2026-11-10T08:00")
        assertEquals(listOf("2026-11-10T06:30"), a.perdidos.map { texto(it.em) })   // um aviso por alarme, o mais recente
    }

    @Test fun diasDaSemanaRespeitados() {   // seg a sex; desligada do sábado 22h ao domingo 10h: nada perdido
        val util = AlarmePerdido.Item(1, 6, 30, SEG_A_SEX, true)
        assertTrue(avaliar(listOf(util), "2026-11-09T06:30", 1, "2026-11-08T10:00").vazia)   // devido segunda, ainda domingo
        val a = avaliar(listOf(util), "2026-11-09T06:30", 1, "2026-11-09T10:00")
        assertEquals(listOf("2026-11-09T06:30"), a.perdidos.map { texto(it.em) })
    }

    @Test fun sonecaPerdidaAvisa() {   // o devido era a soneca das 06:40 (não é um horário de alarme) e a TV desligou
        val a = avaliar(listOf(AlarmePerdido.Item(1, 6, 30, TODO_DIA, true)), "2026-11-10T06:40", 1, "2026-11-10T08:00")
        assertEquals(listOf("2026-11-10T06:40"), a.perdidos.map { texto(it.em) })   // amanhã 6:30 ainda não chegou
        assertNull(a.tocarAgora)
        assertTrue(a.desligar.isEmpty())   // repete todo dia: continua ligado
    }

    @Test fun sonecaPerdidaDeUmaVezSoDesliga() {   // revisão: sem isso o alarme de uma vez só tocaria amanhã sozinho
        val a = avaliar(listOf(AlarmePerdido.Item(1, 6, 30, 0, true)), "2026-11-10T06:40", 1, "2026-11-10T08:00")
        assertEquals(setOf(1), a.desligar)
        assertEquals(listOf("2026-11-10T06:40"), a.perdidos.map { texto(it.em) })
    }

    @Test fun sonecaDentroDaToleranciaToca() {
        val a = avaliar(listOf(AlarmePerdido.Item(1, 6, 30, 0, true)), "2026-11-10T06:40", 1, "2026-11-10T06:50")
        assertEquals(ms("2026-11-10T06:40"), a.tocarAgora!!.em)
        assertTrue(a.perdidos.isEmpty() && a.desligar.isEmpty())   // a tela toca; o Parar é que desliga
    }

    @Test fun juntarHorasTextos() {
        fun t(h: Int, m: Int, em: Long) = AlarmePerdido.Toque(1, h, m, em)
        assertEquals("", AlarmePerdido.juntarHoras(emptyList()))
        assertEquals("6:30", AlarmePerdido.juntarHoras(listOf(t(6, 30, 1))))
        assertEquals("6:00, 6:30 e 7:00", AlarmePerdido.juntarHoras(listOf(t(7, 0, 3), t(6, 0, 1), t(6, 30, 2))))
        assertEquals("6:30", AlarmePerdido.juntarHoras(listOf(t(6, 30, 1), t(6, 30, 1))))   // dois alarmes no mesmo horário
        assertEquals("6:30 and 7:00", AlarmePerdido.juntarHoras(listOf(t(6, 30, 1), t(7, 0, 2)), "and"))
    }

    // ---------------------------------------------------------------- volume garantido

    @Test fun sobeAteOMinimo() {
        assertEquals(8, VolumeAlarme.alvo(2, 15, 50))   // 7,5 arredonda para cima
        assertEquals(15, VolumeAlarme.alvo(0, 15, 100))
        assertEquals(1, VolumeAlarme.alvo(0, 15, 1))
    }

    @Test fun naoAbaixaNemMexeSemMinimo() {
        assertNull(VolumeAlarme.alvo(12, 15, 50))   // já está alto: fica
        assertNull(VolumeAlarme.alvo(8, 15, 50))    // exatamente no mínimo
        assertNull(VolumeAlarme.alvo(0, 15, 0))     // "não mexer"
        assertNull(VolumeAlarme.alvo(0, 0, 50))     // volume fixo/sem escala
    }

    @Test fun devolveSoSeNinguemMexeu() {
        assertEquals(3, VolumeAlarme.devolver(agora = 8, definido = 8, original = 3))
        assertNull(VolumeAlarme.devolver(agora = 10, definido = 8, original = 3))   // a pessoa ajustou no controle
        assertNull(VolumeAlarme.devolver(agora = 8, definido = null, original = 8))  // só tirou do mudo
    }
}
