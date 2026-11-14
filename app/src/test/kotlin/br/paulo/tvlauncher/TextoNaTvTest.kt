package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextoNaTvTest {
    @Test fun painelAceitaAcentoMasNaoSimboloDeShell() {
        for (ok in listOf("ação", "São João 2", "café@x.com", "Ñandú", "netflix"))
            assertTrue(ok, TextoNaTv.validoPainel(ok))
        for (ruim in listOf(null, "", "a'b", "a;reboot", "\$(id)", "`id`", "a\nb", "a|b", "ç".repeat(101), "a\"b", "😀"))
            assertFalse(ruim.toString(), TextoNaTv.validoPainel(ruim))
    }

    @Test fun mcpAceitaTudoMenosControle() {
        assertTrue(TextoNaTv.validoMcp("Olá! (teste) 😀", 500))
        assertFalse(TextoNaTv.validoMcp("a\u0000b", 500))
        assertFalse(TextoNaTv.validoMcp("", 500))
        assertFalse(TextoNaTv.validoMcp("abc", 2))
    }

    @Test fun caminho() {
        assertFalse(TextoNaTv.precisaAcessibilidade("netflix 123"))
        assertTrue(TextoNaTv.precisaAcessibilidade("ação"))
    }

    @Test fun semAcentoEJuntar() {
        assertEquals("acao Sao Joao", TextoNaTv.semAcento("ação São João"))
        assertEquals("cafe ", TextoNaTv.semAcento("café 😀"))
        assertEquals("ação", TextoNaTv.juntar("Buscar", true, "ação"))
        assertEquals("busca ação", TextoNaTv.juntar("busca ", false, "ação"))
        assertEquals("á", TextoNaTv.juntar(null, false, "á"))   // NFC: letra + acento = uma letra só
    }

    @Test fun avisoDoCelular() {
        assertTrue(TextoNaTv.deveAvisarCelular(true, "app.a", "", 0, 1000))
        assertFalse(TextoNaTv.deveAvisarCelular(false, "app.a", "", 0, 1000))
        assertFalse(TextoNaTv.deveAvisarCelular(true, "app.a", "app.a", 1000, 30_000))
        assertTrue(TextoNaTv.deveAvisarCelular(true, "app.b", "app.a", 1000, 30_000))
        assertTrue(TextoNaTv.deveAvisarCelular(true, "app.a", "app.a", 1000, 70_000))
    }
}
