package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Auditoria 10/11/2026: teto da saída do shell (privilegios-4) e saída cortada por tempo marcada (privilegios-10). */
class ServidorShizukuTest {
    @Test fun saidaInfinitaParaNoTeto() {
        var leituras = 0L
        val lido = ServidorShizuku.lerComTeto(10_000) { b -> leituras++; b.fill('a'.code.toByte()); b.size }   // "cat /dev/zero"
        assertTrue(lido.cortado)
        assertEquals(10_000, lido.texto.length)
        assertTrue(leituras < 10)
    }

    @Test fun saidaPequenaVemInteira() {
        val partes = ArrayDeque(listOf("uid=2000", "(shell)"))
        val lido = ServidorShizuku.lerComTeto(ServidorShizuku.MAX_SAIDA) { b ->
            val p = partes.removeFirstOrNull() ?: return@lerComTeto -1
            p.toByteArray().copyInto(b); p.length
        }
        assertFalse(lido.cortado)
        assertEquals("uid=2000(shell)", lido.texto)
    }

    @Test fun pendingCloseEncerraALeitura() {
        val lido = ServidorShizuku.lerComTeto(100, acabou = { true }) { error("não devia ler") }
        assertEquals("", lido.texto)
    }

    @Test fun tempoEsgotadoNaoPareceSucesso() {
        val parcial = ServidorShizuku.Lido("linha 1\nlinha 2", cortado = false)
        assertEquals("linha 1\nlinha 2", ServidorShizuku.resultado(parcial, estourou = false, limiteMs = 20_000))
        val r = ServidorShizuku.resultado(parcial, estourou = true, limiteMs = 20_000)!!
        assertTrue(r, r.startsWith("[saída cortada: o comando passou de 20 s]"))
        assertTrue(r.endsWith("linha 2"))
        // Nada lido e o tempo acabou = não respondeu (null), como os chamadores já tratam.
        assertNull(ServidorShizuku.resultado(ServidorShizuku.Lido("  ", false), estourou = true, limiteMs = 20_000))
    }

    @Test fun tetoMarcaASaida() {
        val r = ServidorShizuku.resultado(ServidorShizuku.Lido("aaa", cortado = true), estourou = false, limiteMs = 20_000)!!
        assertTrue(r, r.startsWith("[saída cortada: passou de 1 MB]"))
    }
}
