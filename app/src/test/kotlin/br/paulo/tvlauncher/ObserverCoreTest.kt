package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.ObserverCore
import br.paulo.tvlauncher.observer.ObserverCore.Kind
import br.paulo.tvlauncher.observer.ObserverCore.Level
import br.paulo.tvlauncher.observer.ObserverCore.Reading
import br.paulo.tvlauncher.observer.ObserverCore.Severity
import br.paulo.tvlauncher.observer.ObserverCore.Signal
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverCoreTest {
    private val agora = 1_795_000_000_000L   // nov/2026
    private fun snap(vararg p: Pair<Signal, Long>, idade: Long = 0) = p.associate { (s, v) -> s to Reading(agora - idade, v) }
    private fun kinds(vararg p: Pair<Signal, Long>, idade: Long = 0) = ObserverCore.judge(snap(*p, idade = idade), agora).map { it.kind }

    // ---- caixa-preta

    @Test fun caixa_preta_guarda_so_a_janela_e_o_teto() {
        val b = ObserverCore.BlackBox()
        b.put(Signal.LATENCY_MS, 10, agora - ObserverCore.WINDOW_MS - 1)
        b.put(Signal.LATENCY_MS, 20, agora)
        assertEquals(listOf(20L), b.series(Signal.LATENCY_MS).map { it.value })   // a velha saiu pela janela
        for (i in 1..ObserverCore.MAX_PER_SIGNAL + 10) b.put(Signal.INTERNET, i.toLong(), agora + i)
        assertEquals(ObserverCore.MAX_PER_SIGNAL, b.series(Signal.INTERNET).size)
        assertEquals((ObserverCore.MAX_PER_SIGNAL + 10).toLong(), b.latest(Signal.INTERNET)!!.value)
        assertEquals(setOf(Signal.LATENCY_MS, Signal.INTERNET), b.snapshot().keys)
    }

    // ---- juiz

    @Test fun tudo_normal_nada_aparece() {
        assertTrue(kinds(Signal.INTERNET to 1, Signal.LATENCY_MS to 30, Signal.WIFI_DBM to -55, Signal.MEMORY_AVAILABLE_MB to 600,
            Signal.SLOW_FRAMES_PCT to 3, Signal.DISK_FREE_MB to 2000, Signal.MEMORY_PRESSURE to 5).isEmpty())
    }

    @Test fun sem_internet_e_critico_e_nao_vira_internet_lenta() {
        val f = ObserverCore.judge(snap(Signal.INTERNET to 0, Signal.LATENCY_MS to 900), agora)
        assertEquals(listOf(Kind.NO_INTERNET), f.map { it.kind })
        assertEquals(Severity.CRITICAL, f[0].severity)
    }

    @Test fun cada_limite() {
        assertEquals(listOf(Kind.SLOW_INTERNET), kinds(Signal.INTERNET to 1, Signal.LATENCY_MS to 401))
        assertEquals(listOf(Kind.WEAK_WIFI), kinds(Signal.WIFI_DBM to -81))
        assertTrue(kinds(Signal.WIFI_DBM to -127).isEmpty())   // -127 = sem Wi-Fi (cabo): não é sinal fraco
        assertEquals(listOf(Kind.LOW_MEMORY), kinds(Signal.MEMORY_AVAILABLE_MB to 149))
        assertEquals(listOf(Kind.MEMORY_PRESSURE), kinds(Signal.MEMORY_PRESSURE to 15))
        assertTrue(kinds(Signal.MEMORY_PRESSURE to 20).isEmpty())   // UI_HIDDEN: só a tela saiu da frente
        assertEquals(listOf(Kind.SLOW_HOME), kinds(Signal.SLOW_FRAMES_PCT to 21))
        assertEquals(listOf(Kind.LOW_DISK), kinds(Signal.DISK_FREE_MB to 299))
    }

    @Test fun leitura_velha_nao_julga() {
        assertTrue(kinds(Signal.INTERNET to 0, idade = ObserverCore.STALE_MS + 1).isEmpty())
        assertEquals(listOf(Kind.NO_INTERNET), kinds(Signal.INTERNET to 0, idade = ObserverCore.STALE_MS))
    }

    @Test fun mais_grave_primeiro() {
        val f = kinds(Signal.SLOW_FRAMES_PCT to 50, Signal.MEMORY_AVAILABLE_MB to 50, Signal.DISK_FREE_MB to 10)
        assertEquals(listOf(Kind.LOW_DISK, Kind.LOW_MEMORY, Kind.SLOW_HOME), f)
    }

    @Test fun diff_so_o_que_mudou() {
        val agoraF = ObserverCore.judge(snap(Signal.MEMORY_AVAILABLE_MB to 50, Signal.INTERNET to 0), agora)
        val c = ObserverCore.diff(listOf(Kind.NO_INTERNET, Kind.WEAK_WIFI), agoraF)
        assertEquals(listOf(Kind.LOW_MEMORY), c.appeared.map { it.kind })
        assertEquals(listOf(Kind.WEAK_WIFI), c.cleared)
        val igual = ObserverCore.diff(agoraF.map { it.kind }, agoraF)
        assertTrue(igual.appeared.isEmpty() && igual.cleared.isEmpty())
    }

    // ---- degraus e freios

    private fun ctx(tela: Boolean = false, som: Boolean = false, tecla: Long = 0, mesma: Long = 0) =
        ObserverCore.Context(agora, tela, som, tecla, mesma)

    @Test fun degrau_acima_do_maximo_bloqueia() {
        assertNotNull(ObserverCore.blockedBy(Level.ACT, Level.SUGGEST, ctx()))
        assertNull(ObserverCore.blockedBy(Level.SUGGEST, Level.SUGGEST, ctx(tela = true, som = true, tecla = agora)))   // sugerir nunca é freado
    }

    @Test fun poder_total_ainda_respeita_quem_esta_usando() {
        assertNull(ObserverCore.blockedBy(Level.FULL, Level.FULL, ctx()))   // tela apagada, sem som: pode reiniciar
        assertNotNull(ObserverCore.blockedBy(Level.FULL, Level.FULL, ctx(som = true)))
        assertNotNull(ObserverCore.blockedBy(Level.FULL, Level.FULL, ctx(tela = true, tecla = agora - 10 * 60_000)))
        assertNull(ObserverCore.blockedBy(Level.FULL, Level.FULL, ctx(tela = true, tecla = agora - ObserverCore.IDLE_WITH_SCREEN_MS)))
        // ação reversível (degrau 2) não liga para som nem controle, só para o intervalo
        assertNull(ObserverCore.blockedBy(Level.ACT, Level.FULL, ctx(tela = true, som = true, tecla = agora)))
    }

    @Test fun nada_entra_em_ciclo() {
        assertNotNull(ObserverCore.blockedBy(Level.FULL, Level.FULL, ctx(mesma = agora - 5 * 3_600_000L)))
        assertNull(ObserverCore.blockedBy(Level.FULL, Level.FULL, ctx(mesma = agora - ObserverCore.MIN_INTERVAL_INTERRUPTING_MS)))
        assertNotNull(ObserverCore.blockedBy(Level.ACT, Level.FULL, ctx(mesma = agora - 10 * 60_000)))
        assertNull(ObserverCore.blockedBy(Level.ACT, Level.FULL, ctx(mesma = agora - ObserverCore.MIN_INTERVAL_REVERSIBLE_MS)))
    }

    // ---- registro

    @Test fun registro_guarda_o_porque_e_os_sinais_e_corta() {
        val s = snap(Signal.MEMORY_AVAILABLE_MB to 90)
        val e = ObserverCore.logEntry(agora, "appeared", Kind.LOW_MEMORY, Severity.WARNING, "só 90 MB", s)
        assertEquals(90L, e.getJSONObject("signals").getLong("MEMORY_AVAILABLE_MB"))
        var log: String? = "isto não é json"
        log = ObserverCore.appendLog(log, e, agora)
        assertEquals(1, JSONArray(log).length())
        val velho = ObserverCore.logEntry(agora - 8 * 86_400_000L, "appeared", Kind.LOW_DISK, null, "x", emptyMap())
        val comVelho = JSONArray().put(velho).toString()
        assertEquals(1, JSONArray(ObserverCore.appendLog(comVelho, e, agora)).length())   // passou de 7 dias: saiu
        var muitos: String? = null
        repeat(ObserverCore.LOG_MAX + 5) { muitos = ObserverCore.appendLog(muitos, e, agora) }
        assertEquals(ObserverCore.LOG_MAX, JSONArray(muitos).length())
    }

    // ---- recados

    @Test fun recado_nao_repete_e_vence() {
        val f = ObserverCore.Finding(Kind.NO_INTERNET, Severity.CRITICAL, "x")
        var fila = ObserverCore.enqueue(null, ObserverCore.noteFor(f, agora), agora)
        fila = ObserverCore.enqueue(fila, ObserverCore.noteFor(f, agora + 1000), agora + 1000)
        assertEquals(1, ObserverCore.readNotes(fila, agora + 1000).size)   // o mesmo tipo, ainda não visto: não duplica
        val id = ObserverCore.readNotes(fila, agora).single().id
        fila = ObserverCore.markSeen(fila, id, agora + 2000)
        fila = ObserverCore.enqueue(fila, ObserverCore.noteFor(f, agora + 3000), agora + 3000)
        assertEquals(2, ObserverCore.readNotes(fila, agora + 3000).size)   // já visto: um novo pode entrar
        assertTrue(ObserverCore.readNotes(fila, agora + 3000 + ObserverCore.NOTE_TTL_MS + 1).isEmpty())   // venceram
        assertTrue(ObserverCore.readNotes("lixo", agora).isEmpty())
    }

    @Test fun recado_leva_o_contrato_do_rosto() {
        val f = ObserverCore.Finding(Kind.LOW_MEMORY, Severity.WARNING, "só 90 MB")
        val n = ObserverCore.noteFor(f, agora, listOf("90"))
        assertEquals("observer_low_memory_title", n.titleKey)
        assertEquals("observer_low_memory_text", n.textKey)
        assertEquals(ObserverCore.Step.OBSERVED, n.step)
        assertEquals(ObserverCore.State.DONE, n.state)
        assertNull(n.undoKey)
        val volta = ObserverCore.readNotes(ObserverCore.toJson(listOf(n)).toString(), agora).single()
        assertEquals(n, volta)   // ida e volta pelo JSON sem perder nada
        assertEquals(listOf("90"), ObserverCore.argsFor(Kind.LOW_MEMORY, snap(Signal.MEMORY_AVAILABLE_MB to 90)))
        assertTrue(ObserverCore.argsFor(Kind.NO_INTERNET, snap(Signal.INTERNET to 0)).isEmpty())
    }

    @Test fun recadoDeCongelamentoDesfeito() {
        val n = ObserverCore.freezeUndoneNote(1000L, "Fila de impressão", "o Cast")
        assertEquals(ObserverCore.Step.ACTED_FELT, n.step)
        assertEquals(ObserverCore.State.DONE, n.state)
        assertEquals("observer_freeze_undone_title", n.titleKey)
        assertEquals(listOf("Fila de impressão", "o Cast"), n.args)
        assertEquals(n, ObserverCore.readNotes(ObserverCore.enqueue(null, n, 1000L), 1000L).first())
    }

    @Test fun ligouDeNovoNoRegistro() {
        val boot = 1_000_000_000L
        assertEquals("ligou de novo (motivo: reboot,deviceowner); sem sinal desde 8 min antes de ligar",
            ObserverCore.bootWhy("reboot,deviceowner", boot - 8 * 60_000L - 5_000L, boot))
        assertEquals("ligou de novo (motivo: desconhecido)", ObserverCore.bootWhy("", 0L, boot))
        assertEquals("ligou de novo (motivo: desconhecido)", ObserverCore.bootWhy(null, boot + 1, boot))   // relógio torto: sem tempo fora
    }
    // 21/11/2026 (teste da Sala): a internet voltou e a faixa da Início do painel seguia dizendo "Sem internet".
    @Test fun recado_de_estado_que_acabou_sai_da_fila() {
        val agora = 1_000_000L
        val sem = ObserverCore.Finding(ObserverCore.Kind.NO_INTERNET, ObserverCore.Severity.CRITICAL, "x")
        var fila = ObserverCore.enqueue(null, ObserverCore.noteFor(sem, agora), agora)
        fila = ObserverCore.enqueue(fila, ObserverCore.freezeUndoneNote(agora, "App", "a voz"), agora)
        fila = ObserverCore.dropCleared(fila, listOf(ObserverCore.Kind.NO_INTERNET, ObserverCore.Kind.FREEZE_UNDONE), agora + 1)
        val ficou = ObserverCore.readNotes(fila, agora + 1)
        assertEquals(listOf(ObserverCore.Kind.FREEZE_UNDONE), ficou.map { it.kind })   // o que a Sofy FEZ continua
    }
}
