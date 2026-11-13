package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.Calendar
import java.util.TimeZone

class BackupNoturnoTest {
    private val sp = TimeZone.getTimeZone("America/Sao_Paulo")
    private val Rg = BackupNoturnoRegras

    private fun em(ano: Int, mes: Int, dia: Int, h: Int, m: Int): Long = Calendar.getInstance(sp).apply {
        clear(); set(ano, mes - 1, dia, h, m, 0)
    }.timeInMillis

    // ---------------------------------------------------------------- agendamento

    @Test fun proximaVezHojeSeAindaNaoPassou() {
        assertEquals(em(2026, 11, 11, 3, 30), Rg.proximaVez(em(2026, 11, 11, 1, 0), 3 * 60 + 30, sp))
    }

    @Test fun proximaVezAmanhaSeJaPassouOuEAgora() {
        assertEquals(em(2026, 11, 11, 3, 30), Rg.proximaVez(em(2026, 11, 10, 18, 12), 210, sp))
        assertEquals(em(2026, 11, 12, 3, 30), Rg.proximaVez(em(2026, 11, 11, 3, 30), 210, sp))   // na hora exata: amanhã
    }

    @Test fun proximaVezViraOMes() {
        assertEquals(em(2026, 12, 1, 3, 30), Rg.proximaVez(em(2026, 11, 30, 23, 0), 210, sp))
    }

    @Test fun atrasadoSoDepoisDe48hLigado() {
        val agora = em(2026, 11, 10, 12, 0)
        val h = 60 * 60 * 1000L
        assertFalse(Rg.atrasado(0, 0, agora))                        // nunca ligado
        assertFalse(Rg.atrasado(0, agora - 2 * h, agora))            // acabou de ligar: espera a noite
        assertTrue(Rg.atrasado(0, agora - 49 * h, agora))            // ligado há 49 h e nenhuma cópia
        assertFalse(Rg.atrasado(agora - 10 * h, agora - 100 * h, agora))
        assertTrue(Rg.atrasado(agora - 50 * h, agora - 100 * h, agora))
    }

    @Test fun quandoAgendarAdiantaSoSeAtrasado() {
        val agora = em(2026, 11, 10, 12, 0)
        val h = 60 * 60 * 1000L
        assertEquals(em(2026, 11, 11, 3, 30), Rg.quandoAgendar(agora, 210, agora - 5 * h, agora - 100 * h, sp))
        assertEquals(agora + 15 * 60_000L, Rg.quandoAgendar(agora, 210, agora - 60 * h, agora - 100 * h, sp))
        // Atrasado, mas a noite chega antes dos 15 min: fica a noite.
        val quase = em(2026, 11, 11, 3, 25)
        assertEquals(em(2026, 11, 11, 3, 30), Rg.quandoAgendar(quase, 210, quase - 60 * h, quase - 100 * h, sp))
    }

    @Test fun atrasadoAdiantaSoUmaVezPorDia() {
        val agora = em(2026, 11, 10, 12, 0)
        val h = 60 * 60 * 1000L
        // O alarme rodou há 2 h (TV em uso, desistiu): mesmo atrasado, fica a noite (sem laço de 15 min).
        assertEquals(em(2026, 11, 11, 3, 30), Rg.quandoAgendar(agora, 210, agora - 60 * h, agora - 100 * h, sp, ultimoAlarme = agora - 2 * h))
        // Último alarme há 25 h: pode adiantar de novo.
        assertEquals(agora + 15 * 60_000L, Rg.quandoAgendar(agora, 210, agora - 60 * h, agora - 100 * h, sp, ultimoAlarme = agora - 25 * h))
    }

    @Test fun adiamentoGuardadoSoValeNoFuturo() {
        assertTrue(Rg.adiamentoValido(2_000L, 1_000L))
        assertFalse(Rg.adiamentoValido(1_000L, 1_000L))
        assertFalse(Rg.adiamentoValido(0L, 1_000L))
    }

    @Test fun midiaTocandoNaoEOciosa() {
        assertFalse(Rg.tvOciosa(true, false, 999, true, tocandoMidia = true))   // filme sem apertar tecla
        assertTrue(Rg.tvOciosa(false, false, 0, false, tocandoMidia = true))    // tela apagada
        assertTrue(Rg.tvOciosa(true, true, 0, false, tocandoMidia = true))      // protetor na frente
    }

    @Test fun tentativasAte4VezesDe30Min() {
        val agora = 1_000_000L
        assertEquals(agora + 30 * 60_000L, Rg.proximaTentativa(agora, 0))
        assertEquals(agora + 30 * 60_000L, Rg.proximaTentativa(agora, 3))
        assertNull(Rg.proximaTentativa(agora, 4))
    }

    @Test fun ocioSoSemNinguemVendo() {
        assertTrue(Rg.tvOciosa(telaAcesa = false, protetorNaTela = false, minutosSemTecla = 0, vejoTeclas = false))
        assertTrue(Rg.tvOciosa(true, true, 0, false))
        assertTrue(Rg.tvOciosa(true, false, 30, true))
        assertFalse(Rg.tvOciosa(true, false, 29, true))
        assertFalse(Rg.tvOciosa(true, false, 999, false))   // sem acessibilidade não dá para saber: não arrisca
    }

    @Test fun horarioDaAVolta() {
        assertEquals(0, Rg.mudarHorario(23 * 60 + 30, 30))
        assertEquals(23 * 60 + 30, Rg.mudarHorario(0, -30))
        assertEquals("03:30", Rg.hhmm(210))
    }

    // ---------------------------------------------------------------- nomes, rotação e destino

    @Test fun nomeDoDiaUsaOFuso() {
        assertEquals("onetv-backup-20261111.json", Rg.nomeDoDia(em(2026, 11, 11, 3, 30), sp))
        assertTrue(Rg.nomeValido("onetv-backup-20261111.json"))
        assertFalse(Rg.nomeValido("onetv-backup-20261111.json.tmp"))
        assertFalse(Rg.nomeValido("../onetv-backup-20261111.json"))
        assertFalse(Rg.nomeValido("backup-20261111.json"))   // as do BackupAutomatico não entram na conta
    }

    @Test fun rotacaoSoApagaNossasCopiasAlemDas7() {
        val nomes = (1..10).map { "onetv-backup-202611%02d.json".format(it) } + listOf("fotos.jpg", "onetv-backup-20261111.json.tmp")
        assertEquals((1..3).map { "onetv-backup-202611%02d.json".format(it) }, Rg.quaisApagar(nomes).sorted())
        assertTrue(Rg.quaisApagar(nomes.take(7)).isEmpty())
    }

    @Test fun gravarLocalSubstituiNoMesmoDiaERoda() {
        val dir = Files.createTempDirectory("bkn").toFile()
        try {
            for (d in 1..8) BackupNoturno.gravarLocal(dir, "onetv-backup-202611%02d.json".format(d), "copia $d".toByteArray())
            File(dir, "outra.txt").writeText("fica")
            BackupNoturno.gravarLocal(dir, "onetv-backup-20261108.json", "de novo".toByteArray())
            val restam = dir.list()!!.sorted()
            assertEquals((2..8).map { "onetv-backup-202611%02d.json".format(it) } + "outra.txt", restam)
            assertEquals("de novo", File(dir, "onetv-backup-20261108.json").readText())
        } finally { dir.deleteRecursively() }
    }

    @Test fun destinoDaAVoltaEConhecidoSumido() {
        val ids = listOf("a1", "b2")
        assertEquals("smb:a1", Rg.proximoDestino("", ids))
        assertEquals("smb:b2", Rg.proximoDestino("smb:a1", ids))
        assertEquals("pendrive", Rg.proximoDestino("smb:b2", ids))
        assertEquals("", Rg.proximoDestino("pendrive", ids))
        assertEquals("", Rg.proximoDestino("smb:apagada", ids))
        assertEquals("pendrive", Rg.proximoDestino("", emptyList()))
        assertEquals("a1", Rg.idSmb("smb:a1")); assertNull(Rg.idSmb("pendrive")); assertNull(Rg.idSmb("smb:"))
    }
}
