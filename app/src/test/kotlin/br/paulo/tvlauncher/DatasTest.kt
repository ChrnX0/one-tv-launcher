package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.TimeZone

/** Auditoria de 10/11/2026: rótulo "hoje/amanhã" do despertador e eventos de dia inteiro da agenda. */
class DatasTest {
    private val sp = ZoneId.of("America/Sao_Paulo")
    private val fusoSp = TimeZone.getTimeZone(sp)
    private fun ms(texto: String, z: ZoneId = sp) = LocalDateTime.parse(texto).atZone(z).toInstant().toEpochMilli()
    private fun utc(texto: String) = Instant.parse(texto).toEpochMilli()

    @Test fun amanhaNaoViraHojePorCausaDosSegundos() {
        // A conta antiga dava 0 ("hoje às 07:00"): os 45,123 s de agora sobravam na subtração.
        val agora = ms("2026-11-10T22:10:45.123")
        val alvo = ProximoAlarme.calcular(7, 0, 0, agora, fusoSp)!!
        assertEquals(1L, Datas.diasAte(alvo, agora, fusoSp))
    }

    @Test fun daquiADoisDias() {
        assertEquals(2L, Datas.diasAte(ms("2026-11-12T07:00"), ms("2026-11-10T22:00:01"), fusoSp))   // a antiga dava 1
        assertEquals(0L, Datas.diasAte(ms("2026-11-10T23:00"), ms("2026-11-10T22:00:01"), fusoSp))
    }

    @Test fun diaDe23HorasNoHorarioDeVerao() {
        // 08/03/2026 em Nova York tem 23 h: dividir por 24 h dava 0 ("hoje") para o alarme do dia seguinte.
        val ny = ZoneId.of("America/New_York")
        assertEquals(1L, Datas.diasAte(ms("2026-03-09T00:10", ny), ms("2026-03-08T00:30", ny), TimeZone.getTimeZone(ny)))
    }

    /** Simula a agenda do Android (filtro bruto, em UTC no dia inteiro) + o ajuste e o recorte do Agenda.eventos. */
    private fun hoje(inicioUtc: Long, fimUtc: Long, de: Long, ate: Long): Boolean {
        val (dc, ac) = Datas.janelaConsulta(de, ate)
        if (!(inicioUtc <= ac && fimUtc >= dc)) return false   // a agenda nem devolveria
        val (i, f) = Datas.ajustarDiaInteiro(inicioUtc, fimUtc, fusoSp)
        return Datas.dentro(i, f, de, ate) && f > de   // f > de: o mesmo filtro do resumoHoje (fim > agora)
    }

    @Test fun diaInteiroDeAmanhaNaoEntraEmHoje() {
        val agora = ms("2026-11-10T10:00"); val fimDoDia = ms("2026-11-10T23:59:59")
        assertFalse(hoje(utc("2026-11-11T00:00:00Z"), utc("2026-11-12T00:00:00Z"), agora, fimDoDia))
        assertTrue(hoje(utc("2026-11-10T00:00:00Z"), utc("2026-11-11T00:00:00Z"), agora, fimDoDia))
    }

    @Test fun diaInteiroDeHojeNaoSomeDepoisDas21h() {
        // O fim em UTC (00:00Z de amanhã) é 21:00 aqui: sem a folga na consulta, às 22h o evento sumia.
        val agora = ms("2026-11-10T22:00"); val fimDoDia = ms("2026-11-10T23:59:59")
        assertTrue(hoje(utc("2026-11-10T00:00:00Z"), utc("2026-11-11T00:00:00Z"), agora, fimDoDia))
    }

    @Test fun ajusteLevaParaMeiaNoiteLocal() {
        val (i, f) = Datas.ajustarDiaInteiro(utc("2026-11-10T00:00:00Z"), utc("2026-11-11T00:00:00Z"), fusoSp)
        assertEquals(ms("2026-11-10T00:00"), i)
        assertEquals(ms("2026-11-11T00:00"), f)
    }
}
