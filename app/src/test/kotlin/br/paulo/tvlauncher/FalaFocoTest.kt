package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FalaFocoTest {
    @Test fun juntaTiraVaziosERepetidos() =
        assertEquals("Wi-Fi. Casa 5G", FalaFoco.frase(listOf("  Wi-Fi ", null, "", "Casa   5G", "Wi-Fi")))

    @Test fun tiraSetasESimbolos() =
        assertEquals("Automações", FalaFoco.frase(listOf("Automações ›")))

    @Test fun cortaFraseLongaNumaPalavra() {
        val f = FalaFoco.frase(listOf("palavra ".repeat(40)))
        assertTrue(f.length <= FalaFoco.MAX)
        assertTrue(f.endsWith("palavra"))
    }

    @Test fun nadaParaDizer() = assertEquals("", FalaFoco.frase(listOf(null, " ", "›")))
}
