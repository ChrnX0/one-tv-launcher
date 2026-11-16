package br.paulo.tvlauncher

import br.paulo.tvlauncher.FamiliaConectada.Regras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Família conectada (degrau 3): o link, a rota e o limite do dia. */
class FamiliaConectadaTest {
    private val tok = "0123456789abcdef0123456789abcdef"

    @Test fun rotas() {
        assertEquals(tok to "", Regras.rota("/familia/$tok"))
        assertEquals(tok to "", Regras.rota("/familia/$tok/"))
        assertEquals(tok to "recado", Regras.rota("/familia/$tok/recado"))
        assertEquals(tok to "foto", Regras.rota("/familia/$tok/foto"))
        assertNull(Regras.rota("/familia/$tok/apagar"))
        assertNull(Regras.rota("/mcp"))
        assertNull(Regras.rota("/familia"))
    }

    @Test fun tokenConfere() {
        assertTrue(Regras.confere(tok, tok))
        assertFalse(Regras.confere(tok, null))                      // link desligado
        assertFalse(Regras.confere(tok.replace('0', '1'), tok))
        assertFalse(Regras.confere("../../etc", tok))
        assertFalse(Regras.confere(tok.uppercase(), tok.uppercase()))   // só o formato que a TV gera
    }

    @Test fun nomeLimpo() {
        assertEquals("Ana Maria script", Regras.nome("  Ana   Maria <script>"))   // sem < >: vira texto comum na TV
        assertEquals("", Regras.nome(null))
        assertEquals(FamiliaConectada.MAX_NOME, Regras.nome("a".repeat(100)).length)
    }

    @Test fun limiteDoDia() {
        assertEquals(true to 1, Regras.contar("2026-11-16", null, 0))
        assertEquals(false to FamiliaConectada.MAX_POR_DIA, Regras.contar("2026-11-16", "2026-11-16", FamiliaConectada.MAX_POR_DIA))
        assertEquals(true to 1, Regras.contar("2026-11-17", "2026-11-16", FamiliaConectada.MAX_POR_DIA))   // dia novo zera
    }
}
