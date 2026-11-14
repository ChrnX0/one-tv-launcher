package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/** Ideias 36 e 37: dia fechado no arquivo mensal, meses, e o aviso "a internet voltou". */
class RedeMensalTest {
    private val zona = ZoneId.of("America/Sao_Paulo")
    private val min = 60_000L
    private fun t(d: String, h: Int, m: Int = 0) = LocalDate.parse(d).atTime(h, m).atZone(zona).toInstant().toEpochMilli()
    private fun ok(t: Long, dbm: Int = -60) = ResumoRede.Amostra(t, true, 20, 10, dbm, false, true)
    private fun fora(t: Long, dbm: Int = -60) = ResumoRede.Amostra(t, false, -1, -1, dbm, false, false)

    /** Um dia inteiro medido a cada 5 min, com uma queda de [ini] a [fim] (minutos do dia). */
    private fun dia(d: String, ini: Int = -1, fim: Int = -1) = (0 until 24 * 12).map {
        val m = it * 5; val tt = t(d, 0) + m * min
        if (m in ini until fim) fora(tt) else ok(tt)
    }

    @Test fun hojeNaoFechaSoOsDiasTerminados() {
        val a = dia("2026-11-12", 600, 630) + dia("2026-11-13") + dia("2026-11-14").take(10)
        val novos = RedeMensal.diasNovos(a, emptySet(), t("2026-11-14", 1), zona)
        assertEquals(listOf("2026-11-12", "2026-11-13"), novos.map { it.data.toString() })
        assertEquals(1, novos[0].quedas)
        assertEquals(30 * min, novos[0].foraMs)
        assertEquals(0, novos[1].quedas)
        assertEquals(24 * 60 * min, novos[1].coberturaMs)
    }

    @Test fun diaJaGravadoNaoEntraDeNovo() {
        val a = dia("2026-11-12") + dia("2026-11-13")
        val novos = RedeMensal.diasNovos(a, setOf(LocalDate.parse("2026-11-12")), t("2026-11-14", 1), zona)
        assertEquals(listOf("2026-11-13"), novos.map { it.data.toString() })
    }

    @Test fun quedaQueAtravessaMeiaNoiteContaSoOPedacoDoDia() {
        // Cai às 23:40 do dia 12 e volta às 00:20 do dia 13.
        val a = dia("2026-11-12", 23 * 60 + 40, 24 * 60) + dia("2026-11-13", 0, 20)
        val novos = RedeMensal.diasNovos(a, emptySet(), t("2026-11-14", 1), zona)
        assertEquals(20 * min, novos[0].foraMs)
        assertEquals(1, novos[1].quedas)
        assertEquals(20 * min, novos[1].foraMs)
    }

    @Test fun arquivoIdaEVoltaEMeses() {
        val d = listOf(
            RedeMensal.Dia(LocalDate.parse("2026-10-30"), 2, 1, 10 * min, 1000),
            RedeMensal.Dia(LocalDate.parse("2026-10-31"), 1, 0, 5 * min, 1000),
            RedeMensal.Dia(LocalDate.parse("2026-11-01"), 0, 0, 0, 1000),
        )
        val lido = RedeMensal.ler(d.map { RedeMensal.linha(it) } + "lixo" + "2026-13-01\t1\t1\t1\t1")
        assertEquals(3, lido.size)
        val m = RedeMensal.meses(lido)
        assertEquals(listOf("2026-11", "2026-10"), m.map { it.mes })
        assertEquals(3, m[1].quedas)
        assertEquals(15 * min, m[1].foraMs)
        assertEquals(2, m[1].dias)
    }

    @Test fun apararGuarda400Dias() {
        val hoje = LocalDate.parse("2026-11-14")
        val d = listOf(RedeMensal.Dia(hoje.minusDays(401), 0, 0, 0, 0), RedeMensal.Dia(hoje.minusDays(3), 0, 0, 0, 0))
        assertEquals(1, RedeMensal.aparar(d, t("2026-11-14", 12), zona).size)
    }

    @Test fun internetVoltouDepoisDe5Min() {
        val b = t("2026-11-14", 10)
        val a = listOf(ok(b), fora(b + 5 * min), fora(b + 5 * min + 30_000), fora(b + 9 * min), ok(b + 10 * min))
        val q = RedeMensal.quedaQueAcabou(a)
        assertNotNull(q)
        assertEquals(5 * min, q!!.duracao)
        assertEquals(RedeMensal.Causa.PROVEDOR_OU_ROTEADOR, RedeMensal.causa(a, q))
    }

    @Test fun quedaCurtaOuSemQuedaNaoAvisa() {
        val b = t("2026-11-14", 10)
        assertNull(RedeMensal.quedaQueAcabou(listOf(ok(b), fora(b + min), ok(b + 2 * min))))
        assertNull(RedeMensal.quedaQueAcabou(listOf(ok(b), ok(b + 5 * min))))
        assertNull(RedeMensal.quedaQueAcabou(listOf(ok(b), fora(b + 5 * min))))   // ainda fora
        assertNull(RedeMensal.quedaQueAcabou(emptyList()))
    }

    @Test fun buracoSemDadosNaoViraAviso() {
        // Falhou, e a TV ficou desligada 2 h: a amostra boa depois do buraco não "fecha" queda nenhuma.
        val b = t("2026-11-14", 10)
        assertNull(RedeMensal.quedaQueAcabou(listOf(ok(b), fora(b + 5 * min), ok(b + 125 * min))))
    }

    @Test fun semWifiNaQuedaEhOWifiDaTv() {
        val b = t("2026-11-14", 10)
        val a = listOf(ok(b), fora(b + 5 * min, dbm = 0), fora(b + 6 * min, dbm = 0), fora(b + 7 * min), ok(b + 10 * min))
        val q = RedeMensal.quedaQueAcabou(a)!!
        assertEquals(RedeMensal.Causa.WIFI_DA_TV, RedeMensal.causa(a, q))
        // TV no cabo (dBm sempre 0): não dá para culpar o Wi-Fi.
        val cabo = listOf(ok(b, 0), fora(b + 5 * min, 0), fora(b + 6 * min, 0), ok(b + 10 * min, 0))
        assertTrue(RedeMensal.causa(cabo, RedeMensal.quedaQueAcabou(cabo)!!) == RedeMensal.Causa.PROVEDOR_OU_ROTEADOR)
    }
}
