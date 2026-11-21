package br.paulo.tvlauncher

import br.paulo.tvlauncher.ControleLatencia.Veredito
import org.junit.Assert.assertEquals
import org.junit.Test

class ControleLatenciaTest {
    private fun cada(ms: Long, n: Int) = (0 until n).map { it * ms }

    @Test fun controleRapido() {
        val r = ControleLatencia.resumo(cada(4, 2500))
        assertEquals(Veredito.BOM, r.veredito); assertEquals(4, r.media)
    }

    @Test fun jogavel() = assertEquals(Veredito.JOGAVEL, ControleLatencia.resumo(cada(16, 600)).veredito)

    /** Parecido com o medido na Sala (21/11/2026): ~40 ms entre sinais e várias pausas de 200 ms+. */
    @Test fun lentoComoNaSala() {
        val t = ArrayList<Long>(); var h = 0L
        repeat(300) { i -> h += if (i % 20 == 19) 300 else 40; t += h }
        val r = ControleLatencia.resumo(t)
        assertEquals(Veredito.LENTO, r.veredito); assertEquals(15, r.pausas); assertEquals(40, r.media)
    }

    @Test fun naoGirou() = assertEquals(Veredito.PARADO, ControleLatencia.resumo(cada(50, 10)).veredito)
}
