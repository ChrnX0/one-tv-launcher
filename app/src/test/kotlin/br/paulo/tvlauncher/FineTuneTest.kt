package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.ClockStyle
import br.paulo.tvlauncher.settings.FontChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ajuste fino (21/11/2026): fonte baixada só se for fonte de verdade; formato da hora. */
class FineTuneTest {
    @Test fun cabecalhoDeFonte() {
        assertTrue(FontChoice.looksLikeFont(byteArrayOf(0, 1, 0, 0, 9)))
        assertTrue(FontChoice.looksLikeFont("OTTO....".toByteArray()))
        assertTrue(FontChoice.looksLikeFont("true....".toByteArray()))
        assertFalse(FontChoice.looksLikeFont("<!DOCTYPE html>".toByteArray()))   // página de erro nunca vira fonte
        assertFalse(FontChoice.looksLikeFont(byteArrayOf(0, 1)))
    }

    @Test fun listaSemRepetidosEComNunitoPrimeiro() {
        assertEquals(FontChoice.DEFAULT, FontChoice.FONTS.first().id)
        assertEquals(FontChoice.FONTS.size, FontChoice.FONTS.map { it.id }.toSet().size)
        assertTrue(FontChoice.FONTS.drop(1).all { it.path!!.startsWith("ofl/") && it.path!!.endsWith(".ttf") })
        assertEquals(FontChoice.FONTS[0], FontChoice.byId("nao-existe"))
    }

    @Test fun pesos() {
        assertEquals(500, FontChoice.weight(400))
        assertEquals(600, FontChoice.weight(500))
        assertEquals(800, FontChoice.weight(700))
    }

    @Test fun formatoDaHora() {
        assertEquals("HH:mm", ClockStyle.pattern(ClockStyle.Format.AUTO, true))
        assertEquals("h:mm", ClockStyle.pattern(ClockStyle.Format.AUTO, false))
        assertEquals("HH:mm", ClockStyle.pattern(ClockStyle.Format.H24, false))
        assertEquals("h:mm a", ClockStyle.pattern(ClockStyle.Format.H12_AMPM, true))
        assertEquals("HH:mm:ss", ClockStyle.pattern(ClockStyle.Format.SECONDS, true))
    }
}
