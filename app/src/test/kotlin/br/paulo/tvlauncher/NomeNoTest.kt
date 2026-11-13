package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NomeNoTest {
    @Test fun nomesComuns() {
        assertEquals("onetv-sala", NomeNo.montar("Sala"))
        assertEquals("onetv-quarto", NomeNo.montar("Quarto"))
        assertEquals("onetv-sala-de-estar", NomeNo.montar("  Sala de Estar "))
        assertEquals("onetv-cozinha-n-2", NomeNo.montar("Cozinha Nº 2"))
        assertEquals("onetv-escritorio-do-joao", NomeNo.montar("Escritório do João"))
    }

    @Test fun semNomeOuSoSimbolos() {
        assertEquals("onetv-tv", NomeNo.montar(null))
        assertEquals("onetv-tv", NomeNo.montar("★★"))
    }

    @Test fun cabeNumRotuloDns() {
        val n = NomeNo.montar("a".repeat(57) + " b" + "c".repeat(30))
        assertTrue(n.length <= 63)
        assertTrue(Regex("^[a-z0-9]([a-z0-9-]*[a-z0-9])?$").matches(n))
    }
}
