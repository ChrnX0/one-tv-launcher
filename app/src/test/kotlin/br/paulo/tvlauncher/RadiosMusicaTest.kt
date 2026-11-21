package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.Radios
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadiosMusicaTest {
    @Test
    fun textoDeEsperaNaoViraMusica() {
        assertFalse(Radios.musicaUtil("Now Playing info goes here", "Sertaneja 106.7"))   // visto na Sala 16/11/2026
        assertFalse(Radios.musicaUtil("Unknown", "X"))
        assertFalse(Radios.musicaUtil(" - ", "X"))
        assertFalse(Radios.musicaUtil("", "X"))
        assertFalse(Radios.musicaUtil(null, "X"))
        assertFalse(Radios.musicaUtil("sertaneja 106.7 ", "Sertaneja 106.7"))
        assertTrue(Radios.musicaUtil("Henrique & Juliano - Liberdade Provisória", "Sertaneja 106.7"))
        assertTrue(Radios.musicaUtil("Now Playing - Live Session", "X"))   // título de verdade que só começa parecido
    }
}
