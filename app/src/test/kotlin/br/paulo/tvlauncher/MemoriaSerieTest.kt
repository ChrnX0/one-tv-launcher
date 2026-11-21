package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.EstiloLegenda
import br.paulo.tvlauncher.player.MemoriaSerie
import br.paulo.tvlauncher.player.MemoriaSerie.Escolha
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Elevação 6.4 (lembrar por série) e 6.5 (três estilos de legenda). */
class MemoriaSerieTest {
    @Test fun lembraCadaCoisaSemApagarAsOutras() {
        var m = MemoriaSerie.lembrar(emptyMap(), "s1", 1) { it.copy(velocidade = 1.25f) }
        m = MemoriaSerie.lembrar(m, "s1", 2) { it.copy(legenda = "en") }
        m = MemoriaSerie.lembrar(m, "s1", 3) { it.copy(atrasoLegendaMs = -500) }
        assertEquals(Escolha("en", 1.25f, -500, 3), m["s1"])
        assertNull(m["s2"])
        assertEquals(m, MemoriaSerie.deJson(MemoriaSerie.paraJson(m)))
    }

    @Test fun legendaLembradaPeloIdioma() {
        val idiomas = listOf("eng", "pt-BR", null)
        assertEquals(1, MemoriaSerie.legenda(Escolha(legenda = "pt"), idiomas))
        assertEquals(0, MemoriaSerie.legenda(Escolha(legenda = "en"), idiomas))
        assertEquals(-1, MemoriaSerie.legenda(Escolha(legenda = ""), idiomas))      // tirou a legenda: continua sem
        assertNull(MemoriaSerie.legenda(Escolha(legenda = "ja"), idiomas))           // não tem neste episódio: não mexe
        assertNull(MemoriaSerie.legenda(Escolha(), idiomas))                          // nunca escolheu: automático
        assertNull(MemoriaSerie.legenda(null, idiomas))
    }

    @Test fun velocidadeSoDasOferecidas() {
        val v = listOf(0.75f, 1f, 1.25f, 1.5f)
        assertEquals(1.25f, MemoriaSerie.velocidadeValida(1.25f, v))
        assertNull(MemoriaSerie.velocidadeValida(8f, v))
        assertNull(MemoriaSerie.velocidadeValida(null, v))
    }

    @Test fun guardaAsMaisRecentes() {
        var m = emptyMap<String, Escolha>()
        for (i in 1..MemoriaSerie.MAX + 5) m = MemoriaSerie.lembrar(m, "s$i", i.toLong()) { it.copy(velocidade = 1f) }
        assertEquals(MemoriaSerie.MAX, m.size)
        assertNull(m["s1"]); assertEquals(1f, m["s${MemoriaSerie.MAX + 5}"]?.velocidade)
    }

    @Test fun serieSemChaveNaoGuarda() {
        assertEquals(emptyMap<String, Escolha>(), MemoriaSerie.lembrar(emptyMap(), "", 1) { it.copy(velocidade = 2f) })
        assertEquals(emptyMap<String, Escolha>(), MemoriaSerie.deJson("{x"))
    }

    @Test fun tresEstilosEmCiclo() {
        val e = EstiloLegenda.Estilo.entries
        assertEquals(3, e.size)
        assertEquals(EstiloLegenda.Estilo.ALTO_CONTRASTE, EstiloLegenda.proximo(EstiloLegenda.Estilo.PADRAO))
        assertEquals(EstiloLegenda.Estilo.PADRAO, EstiloLegenda.proximo(EstiloLegenda.Estilo.AMARELA))
        for (x in e) assertEquals(x, EstiloLegenda.de(x.fundoPreto, x.amarela))   // as chaves de antes dizem o estilo
        assertEquals(EstiloLegenda.Estilo.AMARELA, EstiloLegenda.de(fundoPreto = false, amarela = true))
    }
}
