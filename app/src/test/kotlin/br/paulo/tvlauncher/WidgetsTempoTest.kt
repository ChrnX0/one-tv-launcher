package br.paulo.tvlauncher

import br.paulo.tvlauncher.screensaver.Protetor
import br.paulo.tvlauncher.screensaver.WidgetsTempo
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetsTempoTest {
    private val fuso = TimeZone.getTimeZone("GMT-3")
    private val hora = 60 * 60_000L

    /** 18 horas a partir das 21h de 14/11, com a chance de chuva dada. */
    private fun horas(vararg chuva: Int): String = chuva.mapIndexed { i, c ->
        val h = 21 + i
        val dia = if (h < 24) "14" else "15"
        "2026-11-${dia}T%02d:00|${20 - i % 3}|61|$c".format(h % 24)
    }.joinToString(";")

    private val base = WidgetsTempo.lerHoras(horas(0), fuso).first().ms   // 21:00

    @Test fun leAsHorasEPulaLinhaQuebrada() {
        val l = WidgetsTempo.lerHoras("2026-11-14T21:00|22|3|40;lixo;2026-11-14T22:00|x|3|10;2026-11-14T23:00|21|61|-1", fuso)
        assertEquals(2, l.size)
        assertEquals(WidgetsTempo.Marca(base, 22, 3, 40), l[0])
        assertEquals(-1, l[1].chuva)
        assertTrue(WidgetsTempo.lerHoras("", fuso).isEmpty())
    }

    @Test fun marcasDeDuasEmDuasSoDoFuturo() {
        val l = WidgetsTempo.lerHoras(horas(*IntArray(18)), fuso)
        val m = WidgetsTempo.deDuasEmDuas(l, base + 30 * 60_000L)   // 21:30: a primeira é 22h
        assertEquals(5, m.size)
        assertEquals(listOf(1L, 3L, 5L, 7L, 9L).map { base + it * hora }, m.map { it.ms })
    }

    @Test fun chovendoParaQuandoAChanceCai() {
        val l = WidgetsTempo.lerHoras(horas(90, 80, 70, 50, 20, 10), fuso)
        assertEquals(WidgetsTempo.Frase.ParaPorVolta(base + 4 * hora), WidgetsTempo.frase(true, l, base))
    }

    @Test fun chovendoSemParadaVisivel() {
        val l = WidgetsTempo.lerHoras(horas(90, 80, 70, 60), fuso)
        assertEquals(WidgetsTempo.Frase.ChuvaSegue, WidgetsTempo.frase(true, l, base))
    }

    @Test fun secoAvisaQuandoAChuvaChega() {
        val l = WidgetsTempo.lerHoras(horas(0, 10, 40, 70, 90), fuso)
        assertEquals(WidgetsTempo.Frase.ChegaPorVolta(base + 3 * hora), WidgetsTempo.frase(false, l, base))
    }

    @Test fun secoSemChuvaNaoDizNada() {
        assertNull(WidgetsTempo.frase(false, WidgetsTempo.lerHoras(horas(0, 10, 40, 50), fuso), base))
    }

    @Test fun chuvaDepoisDe12HorasNaoConta() {
        val c = IntArray(16); c[14] = 90   // 21h + 14h = 11h de amanhã: fora das 12 h
        assertNull(WidgetsTempo.frase(false, WidgetsTempo.lerHoras(horas(*c), fuso), base))
    }

    @Test fun horaSemDadoNaoDecide() {
        assertNull(WidgetsTempo.frase(true, WidgetsTempo.lerHoras(horas(-1, -1, -1), fuso), base))
    }

    @Test fun alturasDaCurva() {
        assertEquals(listOf(1f, 0.5f, 0f), WidgetsTempo.alturas(listOf(22, 20, 18)))
        assertEquals(listOf(0.5f, 0.5f), WidgetsTempo.alturas(listOf(19, 19)))
        assertTrue(WidgetsTempo.alturas(emptyList()).isEmpty())
    }

    @Test fun solNaCurva() {
        val m = WidgetsTempo.lerHoras(horas(0, 0, 0, 0, 0, 0, 0, 0, 0), fuso).let { WidgetsTempo.deDuasEmDuas(it, base - 1) }   // 21h..5h
        assertEquals(0.5f, WidgetsTempo.posicaoNaCurva(m, base + 4 * hora)!!, 0.001f)
        assertNull(WidgetsTempo.posicaoNaCurva(m, base + 9 * hora))
        assertNull(WidgetsTempo.posicaoNaCurva(m, null))
    }

    @Test fun estiloDoWidget() {
        assertEquals(Protetor.WIDGET_FAIXA, Protetor.normalizarWidget(null))
        assertEquals(Protetor.WIDGET_FAIXA, Protetor.normalizarWidget("horas"))     // escolha antiga (12/11)
        assertEquals(Protetor.WIDGET_FAIXA, Protetor.normalizarWidget("resumo"))
        assertEquals(Protetor.WIDGET_SOLTO, Protetor.normalizarWidget("solto"))
        assertEquals(Protetor.WIDGET_CARTOES, Protetor.proximoWidget(Protetor.WIDGET_FAIXA))
        assertEquals(Protetor.WIDGET_SOLTO, Protetor.proximoWidget(Protetor.WIDGET_CARTOES))
        // 17/11/2026: depois do "solto" vêm "linha" e "grande" (variações do Paulo); do último volta à faixa.
        assertEquals(Protetor.WIDGET_LINHA, Protetor.proximoWidget(Protetor.WIDGET_SOLTO))
        assertEquals(Protetor.WIDGET_GRANDE, Protetor.proximoWidget(Protetor.WIDGET_LINHA))
        assertEquals(Protetor.WIDGET_FAIXA, Protetor.proximoWidget(Protetor.WIDGET_GRANDE))
    }

    @Test fun rotuloDoSolNaoCobreBarra() {
        // Caso da foto (15/11): pôr do sol logo depois da barra de 320; o texto não cabe de nenhum lado do risco
        // sem cobrir a barra de 320 ou a de 420, então vai para o meio do vão entre elas.
        val barras = listOf(20f, 120f, 220f, 320f, 420f)
        val meia = 7f; val larg = 80f; val w = 440f
        fun cobre(tx: Float) = barras.any { it + meia > tx && it - meia < tx + larg }
        val tx = WidgetsTempo.xRotuloSol(330f, larg, barras, meia, 6f, w)
        assertEquals(330f, tx, 0.01f)
        assertTrue(!cobre(tx) && tx >= 0f && tx + larg <= w)
        // Lado direito livre: fica colado ao risco, como antes.
        assertEquals(156f, WidgetsTempo.xRotuloSol(150f, 50f, listOf(100f, 220f), meia, 6f, w), 0.01f)
        // Direita bate na barra, esquerda livre: vai para a esquerda do risco.
        assertEquals(144f, WidgetsTempo.xRotuloSol(200f, 50f, listOf(100f, 240f), meia, 6f, w), 0.01f)
        // Sem barra nenhuma: direita, e esquerda quando não cabe no fim.
        assertEquals(374f, WidgetsTempo.xRotuloSol(430f, 50f, emptyList(), meia, 6f, w), 0.01f)
    }
}
