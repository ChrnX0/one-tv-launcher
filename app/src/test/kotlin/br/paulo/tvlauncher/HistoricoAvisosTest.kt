package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Ideia 35: a central de avisos guarda os últimos 20, sem repetir, e some com o tempo. */
class HistoricoAvisosTest {
    private val min = 60_000L
    private fun item(t: String, q: Long, x: String = "") = HistoricoAvisos.Item(t, x, q)

    @Test fun maisNovoPrimeiroEAte20() {
        var l = emptyList<HistoricoAvisos.Item>()
        for (i in 1..25) l = HistoricoAvisos.acrescentar(l, item("aviso $i", i * 20 * min))
        assertEquals(HistoricoAvisos.MAX, l.size)
        assertEquals("aviso 25", l.first().titulo)
        assertEquals("aviso 6", l.last().titulo)
    }

    @Test fun repetidoDentroDe10MinNaoDuplica() {
        var l = HistoricoAvisos.acrescentar(emptyList(), item("Chuva", 0, "70%"))
        l = HistoricoAvisos.acrescentar(l, item("Chuva", 5 * min, "70%"))
        assertEquals(1, l.size)
        assertEquals(5 * min, l[0].quando)
        l = HistoricoAvisos.acrescentar(l, item("Chuva", 30 * min, "70%"))
        assertEquals(2, l.size)
        l = HistoricoAvisos.acrescentar(l, item("Chuva", 31 * min, "80%"))
        assertEquals(3, l.size)
    }

    @Test fun velhoSaiERemoverPeloHorario() {
        val agora = 10L * 24 * 60 * min
        val l = listOf(item("novo", agora - min), item("velho", agora - HistoricoAvisos.VALIDADE_MS - 1))
        assertEquals(listOf("novo"), HistoricoAvisos.validos(l, agora).map { it.titulo })
        assertEquals(listOf("velho"), HistoricoAvisos.remover(l, agora - min).map { it.titulo })
    }

    @Test fun jsonIdaEVolta() {
        val l = listOf(item("Recado", 123, "Dentista 15h"), item("Chuva", 100))
        val lido = HistoricoAvisos.deJson(HistoricoAvisos.paraJson(l))
        assertEquals(l.map { Triple(it.titulo, it.texto, it.quando) }, lido.map { Triple(it.titulo, it.texto, it.quando) })
        assertEquals(0, HistoricoAvisos.deJson("lixo").size)
        assertEquals(0, HistoricoAvisos.deJson("""[{"t":"","x":"","q":5},{"t":"x","q":0}]""").size)
    }
}
