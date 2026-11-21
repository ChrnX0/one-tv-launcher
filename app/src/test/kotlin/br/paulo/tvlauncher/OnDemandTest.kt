package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.OnDemand
import br.paulo.tvlauncher.player.OnDemand.Entry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandTest {
    private val min = 60_000L

    @Test fun soAppComTelaENaoProtegido() {
        assertTrue(OnDemand.eligible(hasScreen = true, protected = false, ours = false, system = false, risk = null))
        assertFalse(OnDemand.eligible(hasScreen = false, protected = false, ours = false, system = false, risk = "seguro"))   // serviço
        assertFalse(OnDemand.eligible(hasScreen = true, protected = true, ours = false, system = false, risk = "seguro"))
        assertFalse(OnDemand.eligible(hasScreen = true, protected = false, ours = true, system = false, risk = "seguro"))
        assertFalse(OnDemand.eligible(hasScreen = true, protected = false, ours = false, system = false, risk = "essencial"))
        // De sistema: só com ficha "seguro".
        assertTrue(OnDemand.eligible(hasScreen = true, protected = false, ours = false, system = true, risk = "seguro"))
        assertFalse(OnDemand.eligible(hasScreen = true, protected = false, ours = false, system = true, risk = "cuidado"))
        assertFalse(OnDemand.eligible(hasScreen = true, protected = false, ours = false, system = true, risk = null))
    }

    @Test fun saiuDaFrenteEVoltaACongelarDepoisDoPrazo() {
        var l = listOf(Entry("a.b", "AB", thawedAt = 1000), Entry("c.d", "CD"))
        l = OnDemand.onWindow(l, "a.b", 2000)
        assertEquals(0L, l[0].leftAt)
        l = OnDemand.onWindow(l, "tv.sofaly.launcher", 10 * min)
        assertEquals(10 * min, l[0].leftAt)
        assertEquals(0L, l[1].leftAt)   // congelado não ganha hora de saída
        // Outra tela depois não muda a hora em que saiu.
        l = OnDemand.onWindow(l, "x.y", 11 * min)
        assertEquals(10 * min, l[0].leftAt)
        assertEquals(emptyList<String>(), OnDemand.due(l, "x.y", emptySet(), 11 * min))
        assertEquals(listOf("a.b"), OnDemand.due(l, "x.y", emptySet(), 12 * min))
    }

    @Test fun voltarParaOAppZeraESistemaNaoConta() {
        var l = listOf(Entry("a.b", "AB", thawedAt = 1, leftAt = 5 * min))
        l = OnDemand.onWindow(l, "com.android.systemui", 6 * min)
        assertEquals(5 * min, l[0].leftAt)
        l = OnDemand.onWindow(l, "a.b", 6 * min)
        assertEquals(0L, l[0].leftAt)
        assertEquals(emptyList<String>(), OnDemand.due(l, "x.y", emptySet(), 60 * min))
    }

    @Test fun naFrenteOuTocandoNaoCongela() {
        val l = listOf(Entry("a.b", "AB", thawedAt = 1, leftAt = min))
        assertEquals(emptyList<String>(), OnDemand.due(l, "a.b", emptySet(), 10 * min))
        assertEquals(emptyList<String>(), OnDemand.due(l, "x.y", setOf("a.b"), 10 * min))
        assertEquals(listOf("a.b"), OnDemand.due(l, "x.y", setOf("e.f"), 10 * min))
    }

    @Test fun jsonIdaEVoltaEEstragado() {
        val l = listOf(Entry("a.b", "Á B", 3, 4), Entry("c.d", "CD"))
        assertEquals(l, OnDemand.fromJson(OnDemand.toJson(l)))
        assertEquals(emptyList<Entry>(), OnDemand.fromJson("estragado"))
        assertEquals(1, OnDemand.fromJson("""[{"pkg":"semponto"},{"pkg":"a.b"},{"pkg":"a.b"}]""").size)
        assertEquals(listOf("c.d", "a.b"), OnDemand.put(l, Entry("a.b", "novo")).map { it.pkg })
    }
}
