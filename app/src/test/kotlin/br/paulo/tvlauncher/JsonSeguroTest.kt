package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JsonSeguro (auditoria 10/11/2026, painel-1): os contornos que enganavam o ChecagensPainel.jsonRaso. */
class JsonSeguroTest {
    @Test fun aceitaJsonNormal() {
        assertTrue(JsonSeguro.valido("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"x","arguments":{"a":[1,-2.5e3,true,false,null]}}}"""))
        assertTrue(JsonSeguro.valido("""{"redirect_uris":["https://claude.ai/cb"],"client_name":"Cláudio [x] {y} 'z' /* #"}"""))
        assertTrue(JsonSeguro.valido(" [ \"a\\\"b\" ]\r\n"))
        assertTrue(JsonSeguro.valido("[".repeat(32) + "]".repeat(32)))
    }

    @Test fun recusaOsContornos() {
        assertFalse(JsonSeguro.valido("{'\"':" + "[".repeat(5000)))           // aspa simples
        assertFalse(JsonSeguro.valido("/*\"*/" + "[".repeat(5000)))           // comentário
        assertFalse(JsonSeguro.valido("# \"\n" + "[".repeat(5000)))
        assertFalse(JsonSeguro.valido("]".repeat(5000) + "[".repeat(5000)))   // nível negativo
        assertFalse(JsonSeguro.valido("[".repeat(33) + "]".repeat(33)))       // fundo demais
        assertFalse(JsonSeguro.valido("[}"))                                  // par errado
        assertFalse(JsonSeguro.valido("{\"a\":1"))                            // não fechou
        assertFalse(JsonSeguro.valido("\"sem fim"))
        assertFalse(JsonSeguro.valido("{a:1}"))                               // palavra sem aspas
        assertFalse(JsonSeguro.valido("{\"a\":e}"))
        assertFalse(JsonSeguro.valido("[nul]"))
        assertTrue(JsonSeguro.valido("[1e5,2.5E-3,0.5e+2]"))
        assertFalse(JsonSeguro.valido("{\"a\":1;\"b\":2}"))
        assertFalse(JsonSeguro.valido("[\"quebra\ncrua\"]"))
        assertFalse(JsonSeguro.valido(""))
        assertFalse(JsonSeguro.valido("   "))
    }

    @Test fun lerMontaOuDevolveNull() {
        assertEquals("x", JsonSeguro.objeto("""{"a":"x"}""")!!.getString("a"))
        assertNull(JsonSeguro.objeto("[" .repeat(5000)))
        assertNull(JsonSeguro.objeto("""{"a":tru}"""))                        // passa na conta, o org.json recusa
    }

    /** Mesmo que um parser estoure a pilha, o erro (Error, não Exception) não escapa: vira null. */
    @Test fun pegaStackOverflowError() {
        fun desce(n: Int): Int = desce(n + 1) + 1
        assertNull(JsonSeguro.ler("{}") { desce(0) })
    }
}
