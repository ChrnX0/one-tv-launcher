package br.paulo.tvlauncher

import br.paulo.tvlauncher.SofyCore.Kind
import br.paulo.tvlauncher.SofyCore.Level
import br.paulo.tvlauncher.SofyCore.Reading
import br.paulo.tvlauncher.SofyCore.Severity
import br.paulo.tvlauncher.SofyCore.Signal
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SofyCoreTest {
    private val agora = 1_795_000_000_000L   // nov/2026
    private fun snap(vararg p: Pair<Signal, Long>, idade: Long = 0) = p.associate { (s, v) -> s to Reading(agora - idade, v) }
    private fun kinds(vararg p: Pair<Signal, Long>, idade: Long = 0) = SofyCore.judge(snap(*p, idade = idade), agora).map { it.kind }

    // ---- caixa-preta

    @Test fun caixa_preta_guarda_so_a_janela_e_o_teto() {
        val b = SofyCore.BlackBox()
        b.put(Signal.LATENCY_MS, 10, agora - SofyCore.WINDOW_MS - 1)
        b.put(Signal.LATENCY_MS, 20, agora)
        assertEquals(listOf(20L), b.series(Signal.LATENCY_MS).map { it.value })   // a velha saiu pela janela
        for (i in 1..SofyCore.MAX_PER_SIGNAL + 10) b.put(Signal.INTERNET, i.toLong(), agora + i)
        assertEquals(SofyCore.MAX_PER_SIGNAL, b.series(Signal.INTERNET).size)
        assertEquals((SofyCore.MAX_PER_SIGNAL + 10).toLong(), b.latest(Signal.INTERNET)!!.value)
        assertEquals(setOf(Signal.LATENCY_MS, Signal.INTERNET), b.snapshot().keys)
    }

    // ---- juiz

    @Test fun tudo_normal_nada_aparece() {
        assertTrue(kinds(Signal.INTERNET to 1, Signal.LATENCY_MS to 30, Signal.WIFI_DBM to -55, Signal.MEMORY_AVAILABLE_MB to 600,
            Signal.SLOW_FRAMES_PCT to 3, Signal.DISK_FREE_MB to 2000, Signal.MEMORY_PRESSURE to 5).isEmpty())
    }

    @Test fun sem_internet_e_critico_e_nao_vira_internet_lenta() {
        val f = SofyCore.judge(snap(Signal.INTERNET to 0, Signal.LATENCY_MS to 900), agora)
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
        assertTrue(kinds(Signal.INTERNET to 0, idade = SofyCore.STALE_MS + 1).isEmpty())
        assertEquals(listOf(Kind.NO_INTERNET), kinds(Signal.INTERNET to 0, idade = SofyCore.STALE_MS))
    }

    @Test fun mais_grave_primeiro() {
        val f = kinds(Signal.SLOW_FRAMES_PCT to 50, Signal.MEMORY_AVAILABLE_MB to 50, Signal.DISK_FREE_MB to 10)
        assertEquals(listOf(Kind.LOW_DISK, Kind.LOW_MEMORY, Kind.SLOW_HOME), f)
    }

    @Test fun diff_so_o_que_mudou() {
        val agoraF = SofyCore.judge(snap(Signal.MEMORY_AVAILABLE_MB to 50, Signal.INTERNET to 0), agora)
        val c = SofyCore.diff(listOf(Kind.NO_INTERNET, Kind.WEAK_WIFI), agoraF)
        assertEquals(listOf(Kind.LOW_MEMORY), c.appeared.map { it.kind })
        assertEquals(listOf(Kind.WEAK_WIFI), c.cleared)
        val igual = SofyCore.diff(agoraF.map { it.kind }, agoraF)
        assertTrue(igual.appeared.isEmpty() && igual.cleared.isEmpty())
    }

    // ---- degraus e freios

    private fun ctx(tela: Boolean = false, som: Boolean = false, tecla: Long = 0, mesma: Long = 0) =
        SofyCore.Context(agora, tela, som, tecla, mesma)

    @Test fun degrau_acima_do_maximo_bloqueia() {
        assertNotNull(SofyCore.blockedBy(Level.ACT, Level.SUGGEST, ctx()))
        assertNull(SofyCore.blockedBy(Level.SUGGEST, Level.SUGGEST, ctx(tela = true, som = true, tecla = agora)))   // sugerir nunca é freado
    }

    @Test fun poder_total_ainda_respeita_quem_esta_usando() {
        assertNull(SofyCore.blockedBy(Level.FULL, Level.FULL, ctx()))   // tela apagada, sem som: pode reiniciar
        assertNotNull(SofyCore.blockedBy(Level.FULL, Level.FULL, ctx(som = true)))
        assertNotNull(SofyCore.blockedBy(Level.FULL, Level.FULL, ctx(tela = true, tecla = agora - 10 * 60_000)))
        assertNull(SofyCore.blockedBy(Level.FULL, Level.FULL, ctx(tela = true, tecla = agora - SofyCore.IDLE_WITH_SCREEN_MS)))
        // ação reversível (degrau 2) não liga para som nem controle, só para o intervalo
        assertNull(SofyCore.blockedBy(Level.ACT, Level.FULL, ctx(tela = true, som = true, tecla = agora)))
    }

    @Test fun nada_entra_em_ciclo() {
        assertNotNull(SofyCore.blockedBy(Level.FULL, Level.FULL, ctx(mesma = agora - 5 * 3_600_000L)))
        assertNull(SofyCore.blockedBy(Level.FULL, Level.FULL, ctx(mesma = agora - SofyCore.MIN_INTERVAL_INTERRUPTING_MS)))
        assertNotNull(SofyCore.blockedBy(Level.ACT, Level.FULL, ctx(mesma = agora - 10 * 60_000)))
        assertNull(SofyCore.blockedBy(Level.ACT, Level.FULL, ctx(mesma = agora - SofyCore.MIN_INTERVAL_REVERSIBLE_MS)))
    }

    // ---- registro

    @Test fun registro_guarda_o_porque_e_os_sinais_e_corta() {
        val s = snap(Signal.MEMORY_AVAILABLE_MB to 90)
        val e = SofyCore.logEntry(agora, "appeared", Kind.LOW_MEMORY, Severity.WARNING, "só 90 MB", s)
        assertEquals(90L, e.getJSONObject("signals").getLong("MEMORY_AVAILABLE_MB"))
        var log: String? = "isto não é json"
        log = SofyCore.appendLog(log, e, agora)
        assertEquals(1, JSONArray(log).length())
        val velho = SofyCore.logEntry(agora - 8 * 86_400_000L, "appeared", Kind.LOW_DISK, null, "x", emptyMap())
        val comVelho = JSONArray().put(velho).toString()
        assertEquals(1, JSONArray(SofyCore.appendLog(comVelho, e, agora)).length())   // passou de 7 dias: saiu
        var muitos: String? = null
        repeat(SofyCore.LOG_MAX + 5) { muitos = SofyCore.appendLog(muitos, e, agora) }
        assertEquals(SofyCore.LOG_MAX, JSONArray(muitos).length())
    }

    // ---- recados

    @Test fun recado_nao_repete_e_vence() {
        val f = SofyCore.Finding(Kind.NO_INTERNET, Severity.CRITICAL, "x")
        var fila = SofyCore.enqueue(null, SofyCore.noteFor(f, agora), agora)
        fila = SofyCore.enqueue(fila, SofyCore.noteFor(f, agora + 1000), agora + 1000)
        assertEquals(1, SofyCore.readNotes(fila, agora + 1000).size)   // o mesmo tipo, ainda não visto: não duplica
        val id = SofyCore.readNotes(fila, agora).single().id
        fila = SofyCore.markSeen(fila, id, agora + 2000)
        fila = SofyCore.enqueue(fila, SofyCore.noteFor(f, agora + 3000), agora + 3000)
        assertEquals(2, SofyCore.readNotes(fila, agora + 3000).size)   // já visto: um novo pode entrar
        assertTrue(SofyCore.readNotes(fila, agora + 3000 + SofyCore.NOTE_TTL_MS + 1).isEmpty())   // venceram
        assertTrue(SofyCore.readNotes("lixo", agora).isEmpty())
    }
}
