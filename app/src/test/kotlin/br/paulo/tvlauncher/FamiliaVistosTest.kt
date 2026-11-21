package br.paulo.tvlauncher

import br.paulo.tvlauncher.FamiliaVistos.Item
import br.paulo.tvlauncher.FamiliaVistos.Regras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** "❤ Vi" (3a, 17/11/2026): regras puras do "visto" dos recados da família. */
class FamiliaVistosTest {
    private val a = "0123456789abcdef0123456789abcdef"
    private val b = "fedcba9876543210"
    private fun item(id: String, rid: String = a) = Item(id, rid, "Ana", 1L, "oi", false)

    @Test fun ridSoHexMinusculoDe16a32() {
        assertTrue(Regras.ridValido(a)); assertTrue(Regras.ridValido(b))
        assertFalse(Regras.ridValido(null)); assertFalse(Regras.ridValido(""))
        assertFalse(Regras.ridValido("0123456789abcde"))            // 15
        assertFalse(Regras.ridValido(a + "0"))                       // 33
        assertFalse(Regras.ridValido("0123456789ABCDEF"))           // maiúscula
        assertFalse(Regras.ridValido("../../0123456789ab"))
    }

    @Test fun resumoCurtoEFoto() {
        assertEquals("📷", Regras.resumo(null, true))
        assertEquals("oi tudo bem", Regras.resumo("  oi\n tudo   bem ", false))
        val r = Regras.resumo("x".repeat(100), false)
        assertEquals(40, r.length); assertTrue(r.endsWith("…"))
    }

    @Test fun registrarCortaPorRemetenteENoTotal() {
        var l = emptyList<Item>()
        repeat(15) { l = Regras.registrar(l, item("a$it")) }
        assertEquals(FamiliaVistos.POR_REMETENTE, l.size)
        assertEquals("a14", l.first().id)                            // o mais novo fica na frente
        // muitos remetentes: o total não passa de MAX
        repeat(100) { l = Regras.registrar(l, item("x$it", "%016x".format(it))) }
        assertEquals(FamiliaVistos.MAX, l.size)
    }

    @Test fun marcarSoOProprioERespostaSemNomeNemId() {
        val l = Regras.marcar(listOf(item("1"), item("2", b)), "2")
        assertFalse(l[0].visto); assertTrue(l[1].visto)
        assertEquals(listOf("1"), Regras.doRemetente(l, a).map { it.id })
        val o = Regras.resposta(Regras.doRemetente(l, b)).getJSONObject(0)
        assertTrue(o.getBoolean("visto"))
        assertFalse(o.has("id")); assertFalse(o.has("quem")); assertFalse(o.has("q"))
    }

    @Test fun jsonIdaEVoltaDescartaLixo() {
        val l = listOf(item("1"), item("2", b).copy(visto = true))
        assertEquals(l, Regras.deJson(Regras.paraJson(l)))
        assertEquals(emptyList<Item>(), Regras.deJson("não é json"))
        assertEquals(emptyList<Item>(), Regras.deJson("""[{"id":"1","r":"RUIM"}]"""))
    }

    /** A página manda o mesmo número nos três envios e pergunta pelos vistos. */
    @Test fun paginaMandaORemetente() {
        val h = File("src/main/assets/familia/index.html").readText()
        assertTrue(h.contains("JSON.stringify({ nome, texto, rid })"))
        assertEquals(2, Regex("""URLSearchParams\(\{ nome, texto, rid \}\)""").findAll(h).count())
        assertTrue(h.contains("/vistos?"))
        assertFalse(h.contains("#0b7a6a"))                         // nada do verde antigo
    }
}
