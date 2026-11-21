package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.ObserverBaseline
import br.paulo.tvlauncher.observer.ObserverCore
import br.paulo.tvlauncher.observer.ObserverCore.Kind
import br.paulo.tvlauncher.observer.ObserverCore.Reading
import br.paulo.tvlauncher.observer.ObserverCore.Signal
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObserverBaselineTest {
    private val terca21 = 2 * 24 + 21

    private fun learned(values: List<Long>, s: Signal = Signal.LATENCY_MS, hw: Int = terca21, hd: Int = 21) =
        ObserverBaseline().apply { values.forEach { learn(s, it, hw, hd) } }

    @Test fun gavetaImaturaNaoJulga() {
        val b = learned(List(ObserverBaseline.MATURE - 1) { 30L })
        assertNull(b.badness(Signal.LATENCY_MS, 900, terca21, 21))
    }

    @Test fun aprendeOValorDaCasa() {
        val b = learned(List(40) { 28L + it % 5 })
        val c = b.expected(Signal.LATENCY_MS, terca21, 21)!!
        assertEquals(30.0, c.mean, 2.0)
    }

    @Test fun latenciaAltaParaEstaCasaEhAnormalMesmoAbaixoDoLimiteFixo() {
        val b = learned(List(40) { 28L + it % 5 })
        val z = b.badness(Signal.LATENCY_MS, 250, terca21, 21)!!
        assertTrue("z=$z", z >= ObserverBaseline.UNUSUAL_Z)
        assertTrue(b.badness(Signal.LATENCY_MS, 45, terca21, 21)!! < ObserverBaseline.UNUSUAL_Z)
    }

    @Test fun ladoBomNaoEhAnormal() {
        val b = learned(List(40) { 300L })
        assertTrue(b.badness(Signal.LATENCY_MS, 5, terca21, 21)!! < 0)
        val m = learned(List(40) { 500L }, Signal.MEMORY_AVAILABLE_MB)
        assertTrue(m.badness(Signal.MEMORY_AVAILABLE_MB, 1500, terca21, 21)!! < 0)
        assertTrue(m.badness(Signal.MEMORY_AVAILABLE_MB, 200, terca21, 21)!! >= ObserverBaseline.UNUSUAL_Z)
    }

    @Test fun pisoImpedeAlarmeComSinalConstante() {
        val b = learned(List(40) { 30L })   // desvio zero: o piso (20 ms) segura
        assertTrue(b.badness(Signal.LATENCY_MS, 60, terca21, 21)!! < ObserverBaseline.UNUSUAL_Z)
    }

    @Test fun umaQuedaLongaNaoViraONovoNormal() {
        val b = learned(List(40) { 30L } + List(20) { 3000L })
        val c = b.expected(Signal.LATENCY_MS, terca21, 21)!!
        assertTrue("média ${c.mean}", c.mean < 200)
    }

    @Test fun gavetaDoDiaCobreEnquantoADaSemanaEnche() {
        val b = ObserverBaseline()
        // 12 leituras às 21h espalhadas por dias diferentes: a do dia amadurece, nenhuma da semana.
        repeat(12) { b.learn(Signal.LATENCY_MS, 30, (it % 7) * 24 + 21, 21) }
        assertNotNull(b.expected(Signal.LATENCY_MS, 3 * 24 + 21, 21))
        assertNull(b.expected(Signal.LATENCY_MS, 3 * 24 + 20, 20))
    }

    @Test fun sinalQueNaoAprendeEhIgnorado() {
        val b = learned(List(40) { 1L }, Signal.INTERNET)
        assertNull(b.expected(Signal.INTERNET, terca21, 21))
    }

    @Test fun idaEVoltaPeloJson() {
        val b = learned(List(40) { 28L + it % 5 })
        val volta = ObserverBaseline.fromJson(JSONObject(b.toJson().toString()))
        assertEquals(b.expected(Signal.LATENCY_MS, terca21, 21)!!.mean, volta.expected(Signal.LATENCY_MS, terca21, 21)!!.mean, 0.1)
        assertNull(ObserverBaseline.fromJson(JSONObject().put("v", 99)).expected(Signal.LATENCY_MS, terca21, 21))
        assertNull(ObserverBaseline.fromJson(null).expected(Signal.LATENCY_MS, terca21, 21))
    }

    @Test fun juizAnotaForaDoNormalSoComoInfoESemRepetirOLimiteFixo() {
        val now = 1_000_000_000L
        val b = learned(List(40) { 28L + it % 5 })
        val snap = mapOf(Signal.INTERNET to Reading(now, 1), Signal.LATENCY_MS to Reading(now, 250))
        val f = ObserverCore.judge(snap, now, b, terca21, 21)
        assertEquals(listOf(Kind.UNUSUAL_LATENCY), f.map { it.kind })
        assertEquals(ObserverCore.Severity.INFO, f[0].severity)
        // Acima do limite fixo: só o SLOW_INTERNET (o "fora do normal" seria repetição).
        val alto = ObserverCore.judge(snap + (Signal.LATENCY_MS to Reading(now, 900)), now, b, terca21, 21)
        assertEquals(listOf(Kind.SLOW_INTERNET), alto.map { it.kind })
        // Sem internet: latência não é julgada contra a base.
        val fora = ObserverCore.judge(snap + (Signal.INTERNET to Reading(now, 0)), now, b, terca21, 21)
        assertEquals(listOf(Kind.NO_INTERNET), fora.map { it.kind })
        // Sem base: só os limites fixos (igual à fase 1).
        assertTrue(ObserverCore.judge(snap, now, null, terca21, 21).isEmpty())
    }

    @Test fun registroVelhoNaoApareceMesmoSemLinhaNova() {
        val dia = 86_400_000L
        val now = 100 * dia
        var log: String? = null
        log = ObserverCore.appendLog(log, JSONObject().put("at", now - 10 * dia).put("event", "appeared"), now - 10 * dia)
        log = ObserverCore.appendLog(log, JSONObject().put("at", now - dia).put("event", "cleared"), now - dia)
        val lido = ObserverCore.readLog(log, now)
        assertEquals(1, lido.length())
        assertEquals("cleared", lido.getJSONObject(0).getString("event"))
        assertEquals(0, ObserverCore.readLog("lixo", now).length())
    }
}
