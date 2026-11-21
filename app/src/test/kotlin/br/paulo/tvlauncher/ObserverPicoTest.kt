package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.ObserverCore
import br.paulo.tvlauncher.observer.ObserverPico
import br.paulo.tvlauncher.observer.ObserverPico.Answer
import br.paulo.tvlauncher.observer.ObserverPico.Task
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverPicoTest {
    private val t = Task.WORTH_TELLING
    // severity, unusual_z, habit_use, open_hours, told_24h
    private val graveNova = doubleArrayOf(2.0, 0.0, 0.5, 0.0, 0.0)
    private val leveRepetida = doubleArrayOf(0.0, 4.0, 0.5, 0.0, 4.0)

    @Test fun pesosDePartidaSeguemARegra() {
        val p = ObserverPico()
        assertTrue(p.score(t, graveNova) > 0.7)
        assertTrue(p.score(t, leveRepetida) < 0.3)
        // A regra de hoje avisa todo WARNING novo e não avisa INFO: o pico de partida concorda (Codex, #331).
        assertTrue(p.score(t, doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0)) >= ObserverPico.MIN_CONFIDENCE)
        assertTrue(p.score(t, doubleArrayOf(0.0, 0.0, 1.0, 0.0, 0.0)) < 0.5)
        assertEquals(ObserverPico.FEATURES.getValue(t).size + 1, ObserverPico.priors(t).size)
        assertEquals(ObserverPico.FEATURES.getValue(Task.ACT_NOW).size + 1, ObserverPico.priors(Task.ACT_NOW).size)
    }

    @Test fun aprendeComOResultado() {
        val p = ObserverPico()
        val antes = p.score(t, leveRepetida)
        repeat(200) { p.learn(t, leveRepetida, yes = true) }   // a casa sempre quis saber disso
        assertTrue("antes $antes, depois ${p.score(t, leveRepetida)}", p.score(t, leveRepetida) > 0.6)
        assertEquals(200, p.samples(t))
    }

    @Test fun naoSeiQuandoEmpataOuFaltaConfianca() {
        val p = ObserverPico()
        assertTrue(p.choose(t, mapOf("a" to graveNova, "b" to graveNova)) is Answer.NotSure)
        assertTrue(p.choose(t, mapOf("a" to leveRepetida)) is Answer.NotSure)
        assertTrue(p.choose(t, emptyMap()) is Answer.NotSure)
        val certo = p.choose(t, mapOf("grave" to graveNova, "leve" to leveRepetida))
        assertEquals("grave", (certo as Answer.Sure).option)
    }

    @Test fun explicaOPesoDeCadaMedida() {
        val e = ObserverPico().explain(t, graveNova)
        assertEquals("severity", e.first().first)
    }

    @Test fun idaEVoltaPeloJsonEMedidasNovasVoltamARegra() {
        val p = ObserverPico()
        repeat(50) { p.learn(t, leveRepetida, true) }
        val volta = ObserverPico.fromJson(JSONObject(p.toJson().toString()))
        assertEquals(p.score(t, leveRepetida), volta.score(t, leveRepetida), 1e-3)
        val velho = JSONObject().put("v", 1).put(t.name, JSONObject().put("n", 9).put("w", org.json.JSONArray(listOf(1.0, 2.0))))
        assertEquals(ObserverPico().score(t, graveNova), ObserverPico.fromJson(velho).score(t, graveNova), 1e-9)
    }

    @Test fun medidasDoAchado() {
        val now = 1_000L
        val f = ObserverCore.Finding(ObserverCore.Kind.SLOW_INTERNET, ObserverCore.Severity.WARNING, "x")
        val snap = mapOf(ObserverCore.Signal.LATENCY_MS to ObserverCore.Reading(now, 900))
        val x = ObserverCore.tellingFeatures(f, snap, null, 0, 0, 1.5, 9.0, 7)
        assertEquals(listOf(1.0, 0.0, 1.0, 6.0, 5.0), x.toList())
    }
}
