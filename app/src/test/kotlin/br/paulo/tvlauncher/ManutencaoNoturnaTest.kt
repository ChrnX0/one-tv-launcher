package br.paulo.tvlauncher

import br.paulo.tvlauncher.ManutencaoRegras.Decisao
import br.paulo.tvlauncher.ManutencaoRegras.Passo
import br.paulo.tvlauncher.ManutencaoRegras.Relatorio
import br.paulo.tvlauncher.ManutencaoRegras.Resultado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class ManutencaoNoturnaTest {
    private val hora = 3_600_000L
    private val dia = 24 * hora
    private val rotulo = { n: String -> mapOf("acesso" to "acesso remoto", "permissoes" to "permissões", "espaco" to "espaço livre")[n] ?: n }

    @Test fun proximaEhAs4hDeHojeOuDeAmanha() {
        val tz = TimeZone.getTimeZone("America/Sao_Paulo")
        val c = Calendar.getInstance(tz).apply { set(2026, Calendar.NOVEMBER, 10, 2, 30, 0); set(Calendar.MILLISECOND, 0) }
        val p = ManutencaoRegras.proxima(c.timeInMillis, 4, tz)
        assertEquals(90 * 60_000L, p - c.timeInMillis)
        c.set(Calendar.HOUR_OF_DAY, 4); c.set(Calendar.MINUTE, 0)   // exatamente 4h: a próxima é amanhã
        assertEquals(dia, ManutencaoRegras.proxima(c.timeInMillis, 4, tz) - c.timeInMillis)
    }

    @Test fun decideRodarAdiarOuDesistir() {
        assertEquals(Decisao.RODAR, ManutencaoRegras.decidir(4, telaAcesa = false, minutosSemTecla = 0, soHomeNaFrente = false))
        assertEquals(Decisao.RODAR, ManutencaoRegras.decidir(4, true, 90, true))     // TV esquecida ligada na HOME
        assertEquals(Decisao.ADIAR, ManutencaoRegras.decidir(4, true, 90, false))    // app na frente: alguém vendo
        assertEquals(Decisao.ADIAR, ManutencaoRegras.decidir(4, true, 5, true))      // mexeu no controle há pouco
        assertEquals(Decisao.DESISTIR, ManutencaoRegras.decidir(5, true, 5, true))   // última chance passou
        assertEquals(Decisao.DESISTIR, ManutencaoRegras.decidir(14, false, 0, true)) // fora da madrugada
    }

    @Test fun soApagaRestosConhecidosEVelhos() {
        assertTrue(ManutencaoRegras.apagavel("cache/loja123.apk", 7 * hora))
        assertFalse(ManutencaoRegras.apagavel("cache/loja123.apk", hora))            // pode estar instalando
        assertTrue(ManutencaoRegras.apagavel("cache/painel-instalar9.apk", 7 * hora))
        assertFalse(ManutencaoRegras.apagavel("cache/atualizacao.apk", dia))
        assertTrue(ManutencaoRegras.apagavel("cache/atualizacao.apk", 3 * dia))
        assertFalse(ManutencaoRegras.apagavel("cache/logos/abc.png", 20 * dia))
        assertTrue(ManutencaoRegras.apagavel("cache/logos/abc.png", 31 * dia))
        assertTrue(ManutencaoRegras.apagavel("files/fotos-protetor/.envio123.tmp", 2 * hora))
        assertFalse(ManutencaoRegras.apagavel("files/fotos-protetor/praia.jpg", 365 * dia))   // foto do dono: nunca
        assertTrue(ManutencaoRegras.apagavel("files/backups/backup-20261105.json.tmp", 2 * hora))
        assertFalse(ManutencaoRegras.apagavel("files/backups/backup-20261105.json", 365 * dia))
        assertFalse(ManutencaoRegras.apagavel("files/tunel/tailscaled.state", 365 * dia))
        assertFalse(ManutencaoRegras.apagavel("cache/x", -1))                        // relógio estranho: não apaga
    }

    @Test fun alarmeFaltando() {
        assertFalse(ManutencaoRegras.alarmeFaltando(null, null, false))
        assertTrue(ManutencaoRegras.alarmeFaltando(1_000_000, null, false))
        assertFalse(ManutencaoRegras.alarmeFaltando(1_000_000, 1_000_000 + 30_000, true))
        assertTrue(ManutencaoRegras.alarmeFaltando(1_000_000, 1_000_000 + dia, true))
        // O próximo é de outro (timer, outro app) e vem depois: o nosso não está lá.
        assertTrue(ManutencaoRegras.alarmeFaltando(1_000_000, 1_000_000 + dia, false))
        // O nosso, mas com outra hora (mais cedo): agenda de novo.
        assertTrue(ManutencaoRegras.alarmeFaltando(1_000_000 + dia, 1_000_000, true))
    }

    @Test fun timerAntesDoDespertadorNaoEConserto() {
        // nextAlarmClock devolve o timer (antes do despertador): não dá para saber, então não "falta".
        assertFalse(ManutencaoRegras.alarmeFaltando(1_000_000 + dia, 1_000_000, false))
    }

    @Test fun resumoSoComConsertoOuFalha() {
        val tudoOk = Relatorio(0, listOf(Passo("acesso", Resultado.OK), Passo("limpeza", Resultado.OK, "3 arquivos"), Passo("permissoes", Resultado.PULADO)))
        assertNull(ManutencaoRegras.resumo(tudoOk, rotulo, "Consertei:", "Não consegui:"))
        val misto = Relatorio(0, listOf(Passo("acesso", Resultado.FALHOU, "shell sem resposta"), Passo("permissoes", Resultado.CONSERTADO),
            Passo("espaco", Resultado.CONSERTADO)))
        assertEquals("Consertei: permissões, espaço livre.\nNão consegui: acesso remoto.",
            ManutencaoRegras.resumo(misto, rotulo, "Consertei:", "Não consegui:"))
    }

    @Test fun relatorioIdaEVoltaEmJson() {
        val r = Relatorio(123L, listOf(Passo("acesso", Resultado.CONSERTADO, "GET_USAGE_STATS"), Passo("espaco", Resultado.OK, "2 GB")))
        val volta = ManutencaoRegras.deJson(ManutencaoRegras.paraJson(r).toString())
        assertEquals(r, volta)
        assertEquals("consertado", ManutencaoRegras.paraJson(r).getJSONArray("passos").getJSONObject(0).getString("resultado"))
        assertNull(ManutencaoRegras.deJson(null))
        assertNull(ManutencaoRegras.deJson("{estragado"))
        assertNull(ManutencaoRegras.deJson("""{"em":1,"passos":[{"nome":"x","resultado":"talvez"}]}"""))
    }

    @Test fun mostraOResumoUmaVezDeManhaComAHomeNaFrente() {
        val r = Relatorio(1_000L, listOf(Passo("acesso", Resultado.FALHOU)))
        val manha = 1_000L + 4 * hora
        assertTrue(ManutencaoRegras.deveMostrarResumo(r, manha, true, true, true))
        assertFalse(ManutencaoRegras.deveMostrarResumo(r.copy(mostrado = true), manha, true, true, true))   // uma vez só
        assertFalse(ManutencaoRegras.deveMostrarResumo(r, manha, false, true, true))   // nada a dizer
        assertFalse(ManutencaoRegras.deveMostrarResumo(r, manha, true, false, true))   // tela apagada
        assertFalse(ManutencaoRegras.deveMostrarResumo(r, manha, true, true, false))   // filme/app na frente
        assertFalse(ManutencaoRegras.deveMostrarResumo(r, 1_000L + 21 * hora, true, true, true))   // velho demais
        assertFalse(ManutencaoRegras.deveMostrarResumo(null, manha, true, true, true))
    }
}
