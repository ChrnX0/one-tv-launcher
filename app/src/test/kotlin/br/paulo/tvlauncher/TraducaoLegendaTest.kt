package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.TraducaoLegenda
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraducaoLegendaTest {
    private val srt = "﻿1\r\n00:00:01,000 --> 00:00:03,500\r\n<i>Where were you</i>\r\nlast night?\r\n\r\n" +
        "2\n00:09:59,000 --> 00:10:02,000\n- Get in the car.\n- Now?\n\n" +
        "3\n00:10:05.000 --> 00:10:06,000\n\n\n" +
        "4\n00:20:00,000 --> 00:20:01,000\n{\\an8}Run!\n"

    @Test fun leEscreve() {
        val f = TraducaoLegenda.ler(srt)
        assertEquals(3, f.size)   // a fala 3 não tem texto
        assertEquals(1000L, f[0].inicio); assertEquals(3500L, f[0].fim)
        assertEquals(599_000L, f[1].inicio)
        assertEquals("<i>Where were you</i>\nlast night?", f[0].texto)
        val de = TraducaoLegenda.ler(TraducaoLegenda.escrever(f))
        assertEquals(f, de)
        assertTrue(TraducaoLegenda.escrever(f).startsWith("1\n00:00:01,000 --> 00:00:03,500\n"))
    }

    @Test fun textoParaOTradutor() {
        assertEquals(listOf("Where were you last night?"), TraducaoLegenda.paraTraduzir("<i>Where were you</i>\nlast night?"))
        assertEquals(listOf("- Get in the car.", "- Now?"), TraducaoLegenda.paraTraduzir("- Get in the car.\n- Now?"))
        assertEquals(listOf("Run!"), TraducaoLegenda.paraTraduzir("{\\an8}Run!"))
        assertEquals(emptyList<String>(), TraducaoLegenda.paraTraduzir("<i></i>"))
    }

    @Test fun quebraEmDuasLinhas() {
        assertEquals("Curta.", TraducaoLegenda.quebrar("Curta."))
        val q = TraducaoLegenda.quebrar("Eu não posso acreditar que você manteve isso escondido de mim por tanto tempo.")
        assertEquals(2, q.lines().size)
        assertTrue(q.lines().all { it.length <= 45 })
        assertEquals("- Entre no carro.\n- Agora?", TraducaoLegenda.montar(listOf("- Entre no carro. ", "- Agora?")))
    }

    @Test fun ordemComecaOndeOVideoEsta() {
        val f = TraducaoLegenda.ler(srt)   // 0:01, 9:59, 20:00
        val (primeiro, resto) = TraducaoLegenda.ordem(f, 0)
        assertEquals(listOf(0, 1), primeiro); assertEquals(listOf(2), resto)
        // Retomando aos 15 min: o bloco é 15–25 min; o que ficou para trás vem por último.
        val (p2, r2) = TraducaoLegenda.ordem(f, 15 * 60_000L)
        assertEquals(listOf(2), p2); assertEquals(listOf(0, 1), r2)
    }

    @Test fun parcialSoComAsTraduzidas() {
        val f = TraducaoLegenda.ler(srt)
        val p = TraducaoLegenda.parcial(f, mapOf(2 to "Corra!", 0 to "Onde você estava ontem à noite?", 1 to " "))
        assertEquals(listOf("Onde você estava ontem à noite?", "Corra!"), p.map { it.texto })
        assertEquals(f[2].tempo, p[1].tempo)
    }
}
