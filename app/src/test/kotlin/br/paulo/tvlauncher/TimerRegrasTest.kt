package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideia 31: até 3 timers com nome e um cronômetro. */
class TimerRegrasTest {
    private fun t(id: Long, fim: Long, nome: String = "", inicio: Long = id) = TimerRegras.Item(id, inicio, fim, nome)

    @Test fun ateTresESaiOMaisAntigo() {
        var l = emptyList<TimerRegras.Item>()
        l = TimerRegras.adicionar(l, t(1, 900, "a"), 0)
        l = TimerRegras.adicionar(l, t(2, 500, "b"), 0)
        l = TimerRegras.adicionar(l, t(3, 700, "c"), 0)
        assertEquals(listOf("b", "c", "a"), l.map { it.nome })   // em ordem de fim
        l = TimerRegras.adicionar(l, t(4, 800, "d"), 0)
        assertEquals(3, l.size)
        assertTrue(l.none { it.nome == "a" })   // "a" começou primeiro
    }

    @Test fun mesmoNomeRecomecaEVencidoSai() {
        var l = listOf(t(1, 500, "Arroz"), t(2, 100, "Velho"))
        l = TimerRegras.adicionar(l, t(3, 900, "arroz "), 200)
        assertEquals(listOf(3L), l.map { it.id })
    }

    @Test fun semNomeNaoTrocaOutroSemNome() {
        val l = TimerRegras.adicionar(listOf(t(1, 500)), t(2, 600), 0)
        assertEquals(2, l.size)
    }

    @Test fun vencidosENomes() {
        val (venc, ficam) = TimerRegras.separarVencidos(listOf(t(1, 1000, "Arroz"), t(2, 1500, "Feijão"), t(3, 9000, "Bolo")), 1000)
        assertEquals(listOf("Arroz", "Feijão"), venc.map { it.nome })   // 1 s de folga
        assertEquals(listOf("Bolo"), ficam.map { it.nome })
        assertEquals("Arroz e Feijão", TimerRegras.nomes(venc, " e "))
        assertEquals("", TimerRegras.nomes(listOf(t(1, 1)), " e "))
    }

    @Test fun pilulaDaHome() {
        val l = listOf(t(1, 272_000, "Arroz"), t(2, 600_000))
        assertEquals("4:32  ·  Arroz  +1", TimerRegras.pilula(l, 0, 0))
        assertEquals("↑ 12:04", TimerRegras.pilula(emptyList(), 1, 724_001))
        assertEquals("4:32  ·  Arroz  +1   ↑ 0:00", TimerRegras.pilula(l, 1, 0))
        assertNull(TimerRegras.pilula(listOf(t(1, 10)), 0, 20))   // tudo vencido: a pílula some
    }

    @Test fun relogioECronometro() {
        assertEquals("4:05", TimerRegras.relogio(245_000))
        assertEquals("1:04:05", TimerRegras.relogio(3_845_000))
        assertEquals("1:00:00", TimerRegras.cronometro(0, 3_600_999))
    }

    @Test fun lerEscrever() {
        val l = listOf(t(1, 500, "Arroz", 10), t(2, 600))
        assertEquals(l, TimerRegras.ler(TimerRegras.escrever(l)))
        assertEquals(emptyList<TimerRegras.Item>(), TimerRegras.ler("lixo"))
        assertEquals(emptyList<TimerRegras.Item>(), TimerRegras.ler(null))
    }
}
