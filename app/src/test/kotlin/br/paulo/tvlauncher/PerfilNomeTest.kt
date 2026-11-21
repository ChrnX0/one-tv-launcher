package br.paulo.tvlauncher

import br.paulo.tvlauncher.family.Perfil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Nome do perfil (Sala 17/11: aparecia "solbadiali"): limpeza do que vem do painel ou do login Google. */
class PerfilNomeTest {
    @Test fun limpa() {
        assertEquals("Solange", Perfil.limparNome("  Solange "))
        assertEquals("Ana Maria", Perfil.limparNome("Ana \n  Maria"))
        assertEquals("Ana", Perfil.limparNome("A\u0000na"))
        assertNull(Perfil.limparNome("   "))
        assertNull(Perfil.limparNome(null))
        assertEquals(Perfil.MAX_NOME, Perfil.limparNome("x".repeat(80))!!.length)
    }

    @Test fun usuarioContinuaSendoOFallback() = assertEquals("solbadiali", Perfil.usuario("solbadiali@gmail.com"))
}
