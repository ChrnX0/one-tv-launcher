package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Os sons da cruz são gerados em código: curtos, sem estourar e sem clique no começo nem no fim. */
class SonsCruzTest {
    @Test fun cadaSomEhCurtoLimpoEBaixo() {
        for (s in SonsCruz.Som.entries) {
            val a = SonsCruz.amostras(s)
            val ms = a.size * 1000 / 44100
            assertTrue("$s dura $ms ms", ms in 30..160)
            assertTrue("$s não estoura", a.all { it in -1f..1f })
            assertTrue("$s começa do silêncio", kotlin.math.abs(a[0]) < 0.01f)
            assertTrue("$s termina no silêncio", kotlin.math.abs(a.last()) < 0.01f)
            assertTrue("$s se ouve", a.maxOf { kotlin.math.abs(it) } > 0.2f)
        }
    }

    @Test fun wavTemCabecalhoCerto() {
        val a = SonsCruz.amostras(SonsCruz.Som.ANDAR)
        val w = SonsCruz.wav(a)
        assertEquals(44 + a.size * 2, w.size)
        assertEquals("RIFF", String(w, 0, 4)); assertEquals("WAVE", String(w, 8, 4)); assertEquals("data", String(w, 36, 4))
    }
}
