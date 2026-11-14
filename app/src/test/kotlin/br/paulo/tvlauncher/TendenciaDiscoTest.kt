package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Ideia 41: quando o armazenamento enche, por uma medida de espaço livre por dia. */
class TendenciaDiscoTest {
    private val gb = 1L shl 30
    private val mb = 1L shl 20
    private val hoje = LocalDate.parse("2026-11-14")
    private val total = 8 * gb

    private fun serie(dias: Int, livreHoje: Long, porDia: Long) =
        (0 until dias).map { TendenciaDisco.Ponto(hoje.minusDays(it.toLong()), livreHoje + it * porDia) }.reversed()

    @Test fun caindo100MbPorDiaComUmGigaLivre() {
        // 1 GB livre, cai 100 MB/dia, reserva de 409 MB (5% de 8 GB): (1024 - 409.6) / 100 = 6 dias.
        val d = TendenciaDisco.diasAteEncher(serie(10, gb, 100 * mb), hoje, total)
        assertEquals(6, d)
    }

    @Test fun estavelOuSubindoNaoEnche() {
        assertNull(TendenciaDisco.diasAteEncher(serie(10, gb, 0), hoje, total))
        assertNull(TendenciaDisco.diasAteEncher(serie(10, gb, -50 * mb), hoje, total))
    }

    @Test fun poucasMedidasNaoPreveem() {
        assertNull(TendenciaDisco.diasAteEncher(serie(4, gb, 100 * mb), hoje, total))
        // 5 medidas, mas em 5 dias seguidos (menos de 6 dias de espalhamento)
        assertNull(TendenciaDisco.diasAteEncher(serie(5, gb, 100 * mb), hoje, total))
    }

    @Test fun longeDemaisNaoDiz() {
        assertNull(TendenciaDisco.diasAteEncher(serie(10, 4 * gb, 10 * mb), hoje, total))
    }

    @Test fun jaAbaixoDaReservaEhZero() {
        assertEquals(0, TendenciaDisco.diasAteEncher(serie(10, 300 * mb, 10 * mb), hoje, total))
    }

    @Test fun registrarTrocaOMesmoDiaEJogaForaOVelho() {
        var l = serie(70, gb, mb)
        l = TendenciaDisco.registrar(l, hoje, 5L)
        assertEquals(TendenciaDisco.GUARDAR_DIAS, l.size)
        assertEquals(5L, l.last().livre)
        assertTrue(l.first().dia > hoje.minusDays(TendenciaDisco.GUARDAR_DIAS.toLong()))
    }

    @Test fun textoIdaEVolta() {
        val l = serie(3, gb, mb)
        val lido = TendenciaDisco.ler(TendenciaDisco.escrever(l) + ",lixo,2026-99-01:3")
        assertEquals(l.map { it.dia to it.livre }, lido.map { it.dia to it.livre })
        assertEquals(0, TendenciaDisco.ler(null).size)
    }
}
