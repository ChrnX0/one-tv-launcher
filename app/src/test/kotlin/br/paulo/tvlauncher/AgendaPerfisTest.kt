package br.paulo.tvlauncher

import br.paulo.tvlauncher.alarm.AgendaPerfis
import br.paulo.tvlauncher.alarm.AgendaPerfis.Entrada
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Agenda da família (degrau 2, 16/11/2026). 16/11/2026 é uma segunda-feira. */
class AgendaPerfisTest {
    private val sp = TimeZone.getTimeZone("America/Sao_Paulo")
    private fun em(dia: Int, h: Int, m: Int = 0) = Calendar.getInstance(sp).apply { clear(); set(2026, Calendar.NOVEMBER, dia, h, m) }.timeInMillis

    private val pedro = Entrada("pedro@x.com", AgendaPerfis.UTEIS, 14 * 60)
    private val pais = Entrada("pais@x.com", AgendaPerfis.TODOS, 20 * 60)
    private val agenda = listOf(pedro, pais)

    @Test fun textoIdaEVolta() {
        assertEquals(agenda, AgendaPerfis.ler(AgendaPerfis.escrever(agenda)))
        assertEquals(emptyList<Entrada>(), AgendaPerfis.ler("lixo"))
        assertEquals(emptyList<Entrada>(), AgendaPerfis.ler("""[{"conta":"a","dias":0,"min":60},{"conta":"","dias":1,"min":60},{"conta":"b","dias":1,"min":9999}]"""))
    }

    @Test fun proximaTroca() {
        assertEquals(pedro to em(16, 14), AgendaPerfis.proxima(agenda, em(16, 9), sp))
        assertEquals(pais to em(16, 20), AgendaPerfis.proxima(agenda, em(16, 15), sp))
        // Sexta 21 h: a próxima do Pedro é segunda; antes vem a dos pais no sábado às 20 h.
        assertEquals(pais to em(21, 20), AgendaPerfis.proxima(agenda, em(20, 21), sp))
        assertNull(AgendaPerfis.proxima(emptyList(), em(16, 9), sp))
    }

    @Test fun pedroNaoEntraNoFimDeSemana() {
        val so = listOf(pedro)
        assertEquals(pedro to em(23, 14), AgendaPerfis.proxima(so, em(21, 9), sp))   // sábado -> segunda
    }

    @Test fun vigenteEAplicar() {
        assertEquals(pedro to em(16, 14), AgendaPerfis.vigente(agenda, em(16, 15), sp))
        assertEquals(pais to em(15, 20), AgendaPerfis.vigente(agenda, em(16, 9), sp))
        // Já aplicada às 14 h: às 15 h não troca de novo (a família pode ter trocado à mão).
        assertNull(AgendaPerfis.deveAplicar(agenda, em(16, 15), sp, em(16, 14)))
        // TV ficou desligada das 13 h às 21 h: ao voltar, aplica a dos pais (a última que começou).
        assertEquals(pais, AgendaPerfis.deveAplicar(agenda, em(16, 21), sp, em(15, 20)))
    }
}
