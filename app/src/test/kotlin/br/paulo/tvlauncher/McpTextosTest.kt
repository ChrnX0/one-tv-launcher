package br.paulo.tvlauncher

import br.paulo.tvlauncher.mcp.McpTextos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Páginas de login do MCP no idioma do navegador (15/11/2026). */
class McpTextosTest {
    @Test fun idiomaPeloNavegador() {
        assertFalse(McpTextos.de("pt-BR,pt;q=0.9,en;q=0.8").en)
        assertFalse(McpTextos.de("pt").en)
        assertTrue(McpTextos.de("en-US,en;q=0.9,pt-BR;q=0.8").en)
        assertTrue(McpTextos.de("es-ES").en)
        assertTrue(McpTextos.de(null).en)
        assertTrue(McpTextos.de("").en)
    }

    @Test fun mensagensDeFora() {
        val en = McpTextos(true)
        assertEquals("the code expired; start again", en.mensagem("o código venceu; comece de novo"))
        assertEquals("the account a@b.com is not in any profile of this TV", en.mensagem("a conta a@b.com não está em nenhum perfil desta TV"))
        assertEquals("algo novo", en.mensagem("algo novo"))
        assertEquals("o código venceu; comece de novo", McpTextos(false).mensagem("o código venceu; comece de novo"))
        assertEquals("Allow", en.permitir)
        assertEquals("Permitir", McpTextos(false).permitir)
    }
}
