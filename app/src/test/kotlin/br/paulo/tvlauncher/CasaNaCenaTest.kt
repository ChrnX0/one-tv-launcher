package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Casa nas cenas e no pedir_acoes (ComandosIa tipo "casa") e a mídia no sensor da TV (CasaHA.atributosMidia). 11/11/2026. */
class CasaNaCenaTest {
    private fun conferir(acoes: String) = ComandosIa.conferir("""{"acoes":[$acoes],"resposta":""}""", emptyMap())

    @Test fun cenaApagaALuzEAbreApp() {
        val p = ComandosIa.conferir("""{"acoes":[{"tipo":"casa","entidade":"light.sala_teto","acao":"desligar"},
            {"tipo":"abrir_app","pacote":"com.netflix.ninja"}],"resposta":""}""", mapOf("com.netflix.ninja" to "Netflix"))
        assertEquals(2, p.acoes.size)
        val c = p.acoes[0] as ComandosIa.Acao.Casa
        assertEquals("light.sala_teto", c.entidade)
        assertEquals("Desligar sala teto (casa)", c.descricao)
    }

    @Test fun cenaECortinaSeguemOCatalogoDoCasaHA() {
        assertEquals(1, conferir("""{"tipo":"casa","entidade":"scene.cinema","acao":"ligar"}""").acoes.size)
        assertEquals(1, conferir("""{"tipo":"casa","entidade":"cover.sala","acao":"desligar"}""").acoes.size)
        // cena só "ligar"
        assertTrue(conferir("""{"tipo":"casa","entidade":"scene.cinema","acao":"desligar"}""").acoes.isEmpty())
    }

    @Test fun recusaForaDoCatalogo() {
        for (a in listOf(
            """{"tipo":"casa","entidade":"lock.porta","acao":"desligar"}""",          // fechadura: fora dos domínios
            """{"tipo":"casa","entidade":"alarm_control_panel.casa","acao":"desligar"}""",
            """{"tipo":"casa","entidade":"light.sala","acao":"turn_on"}""",            // só ligar/desligar/alternar
            """{"tipo":"casa","entidade":"light.sala/../x","acao":"ligar"}""",
            """{"tipo":"casa","entidade":"","acao":"ligar"}""",
            """{"tipo":"casa","acao":"ligar"}""")) {
            val p = conferir(a)
            assertTrue(a, p.acoes.isEmpty())
            assertEquals(a, 1, p.recusadas.size)
        }
    }

    @Test fun esquemaOfereceOTipoCasa() {
        val s = ComandosIa.esquema(false).toString()
        assertTrue(s.contains("\"casa\""))
        assertTrue(s.contains("entidade"))
    }

    @Test fun midiaNoSensor() {
        fun at(m: String) = CasaHA.atributosMidia(JSONObject(m), JSONObject())
        val tocando = at("""{"ligado":true,"app":"Netflix","titulo":"Filme","artista":"","tocando":true}""")
        assertEquals("tocando", tocando.getString("midia"))
        assertEquals("Filme", tocando.getString("midia_titulo"))
        assertEquals("Netflix", tocando.getString("midia_app"))
        assertEquals("pausado", at("""{"ligado":true,"app":"YouTube","titulo":"x","tocando":false}""").getString("midia"))
        val nada = at("""{"ligado":true,"nada":true,"acesso":true}""")
        assertEquals("nada", nada.getString("midia"))
        assertFalse(nada.has("midia_titulo"))
        assertEquals("desconhecido", at("""{"ligado":true,"nada":true,"acesso":false}""").getString("midia"))
        assertEquals("desconhecido", at("""{"ligado":false}""").getString("midia"))
        assertEquals(120, at("""{"ligado":true,"titulo":"${"a".repeat(500)}","tocando":true}""").getString("midia_titulo").length)
    }
}
