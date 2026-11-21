package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.Canal
import br.paulo.tvlauncher.player.CanalReserva
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Elevação 5.2: caiu 3 vezes, toca a outra variante (ou o mesmo canal de outra conta); fonte falsa, sem rede. */
class CanalReservaTest {
    private fun c(id: String, nome: String, n: Int, url: String = "http://fonte-falsa/$id") = Canal(id, nome, n, url = url)
    private val lista = listOf(
        c("1", "BR: Globo HD", 1), c("2", "Globo FHD", 2), c("3", "Globo SD", 3), c("4", "Globo HD [Alt]", 4),
        c("5", "Globo News HD", 5), c("6", "SBT HD", 6), c("7", "Globo HD", 7, url = "http://outra-conta/7")   // o mesmo canal na 2ª conta,
    )

    @Test fun mesmaBaseIgnoraQualidadePaisECopia() {
        assertEquals("globo", CanalReserva.base("BR: Globo HD"))
        assertEquals("globo", CanalReserva.base("Globo FHD"))
        assertEquals("globo", CanalReserva.base("Globo HD [Alt]"))
        assertEquals("globo", CanalReserva.base("Globo Backup"))
        assertEquals("globo news", CanalReserva.base("Globo News HD"))   // outro canal: não é reserva
    }

    @Test fun preferaMesmaQualidadeEDepoisAMaisLeve() {
        val atual = lista[0]   // Globo HD
        assertEquals("4", CanalReserva.proxima(lista, atual, setOf("1"))?.id)              // HD [Alt]: mesma qualidade
        assertEquals("3", CanalReserva.proxima(lista, atual, setOf("1", "4", "7"))?.id)    // depois SD (mais leve)
        assertEquals("2", CanalReserva.proxima(lista, atual, setOf("1", "4", "7", "3"))?.id)
        assertNull(CanalReserva.proxima(lista, atual, setOf("1", "2", "3", "4", "7")))     // acabou: aí "sem sinal"
    }

    @Test fun mesmoEnderecoNaoEhReserva() {
        val l = listOf(c("a", "Globo HD", 1, url = "http://x/1"), c("b", "Globo SD", 2, url = "http://x/1"))
        assertNull(CanalReserva.proxima(l, l[0], setOf("a", "http://x/1")))
    }

    @Test fun nuncaTrocaParaBloqueado() {
        val r = CanalReserva.proxima(lista, lista[0], setOf("1")) { it.id != "4" }
        assertEquals("7", r?.id)   // o HD da outra conta
    }

    @Test fun canalSemVarianteNaoTroca() {
        assertNull(CanalReserva.proxima(lista, lista[5], setOf("6")))
    }
}
