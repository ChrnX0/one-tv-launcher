package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.ObserverTrial
import br.paulo.tvlauncher.observer.ObserverTrial.Check
import br.paulo.tvlauncher.observer.ObserverTrial.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ObserverTrialTest {

    private val tudoOk = Check.entries.associateWith { true }
    private val depois = ObserverTrial.WAIT_MS + 1

    @Test fun esperaAntesDeConferir() {
        val t = ObserverTrial.start("com.x", 0, tudoOk)
        assertEquals(Verdict.Wait, ObserverTrial.decide(t, ObserverTrial.WAIT_MS - 1, tudoOk))
    }

    @Test fun nadaQuebrouFicaCongelado() {
        assertEquals(Verdict.Keep, ObserverTrial.decide(ObserverTrial.start("com.x", 0, tudoOk), depois, tudoOk))
    }

    @Test fun castQuebradoDesfaz() {
        // O caso real de 06/11: congelar o setupwraith tira o Cast.
        val t = ObserverTrial.start("com.google.android.tungsten.setupwraith", 0, tudoOk)
        assertEquals(Verdict.Undo(listOf(Check.CAST)), ObserverTrial.decide(t, depois, tudoOk + (Check.CAST to false)))
    }

    @Test fun conferenciaDoProprioPacoteNaoConta() {
        // Quem congela a loja quer a loja fora.
        val t = ObserverTrial.start("com.android.vending", 0, tudoOk)
        assertEquals(Verdict.Keep, ObserverTrial.decide(t, depois, tudoOk + (Check.PLAY_STORE to false)))
    }

    @Test fun oQueJaNaoFuncionavaAntesNaoConta() {
        val t = ObserverTrial.start("com.x", 0, tudoOk + (Check.VOICE to false))
        assertEquals(Verdict.Keep, ObserverTrial.decide(t, depois, tudoOk + (Check.VOICE to false)))
    }

    @Test fun internetSozinhaNaoDesfazMasJuntoDeOutraSim() {
        val t = ObserverTrial.start("com.x", 0, tudoOk)
        assertEquals(Verdict.Keep, ObserverTrial.decide(t, depois, tudoOk + (Check.INTERNET to false)))
        assertEquals(Verdict.Undo(listOf(Check.VOICE, Check.INTERNET)),
            ObserverTrial.decide(t, depois, tudoOk + (Check.INTERNET to false) + (Check.VOICE to false)))
    }

    @Test fun guardaEUmaProvaPorPacote() {
        var l = ObserverTrial.add(emptyList(), ObserverTrial.start("a.b", 1, tudoOk))
        l = ObserverTrial.add(l, ObserverTrial.start("c.d", 2, mapOf(Check.CAST to false)))
        l = ObserverTrial.add(l, ObserverTrial.start("a.b", 3, tudoOk))
        val volta = ObserverTrial.fromJson(ObserverTrial.toJson(l))
        assertEquals(listOf("c.d", "a.b"), volta.map { it.pkg })
        assertEquals(3L, volta.last().at)
        assertEquals(mapOf(Check.CAST to false), volta.first().before)
        assertEquals(0, ObserverTrial.fromJson("lixo").size)
    }
}
