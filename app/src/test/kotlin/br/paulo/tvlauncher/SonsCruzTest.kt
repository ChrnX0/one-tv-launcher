package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.CruzLogica
import br.paulo.tvlauncher.home.SonsCruz
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

    @Test fun cadaCategoriaTemSuaNotaNoGrupoEAbrir() {
        val notas = CruzLogica.Categoria.entries.map { SonsCruz.tom(SonsCruz.Som.GRUPO, it) }
        assertEquals("notas distintas", notas.size, notas.toSet().size)
        assertTrue("dentro do que o SoundPool aceita (0,5 a 2)", notas.all { it in 0.5f..2f })
        assertEquals(SonsCruz.tom(SonsCruz.Som.GRUPO, CruzLogica.Categoria.OUVIR), SonsCruz.tom(SonsCruz.Som.ABRIR, CruzLogica.Categoria.OUVIR))
        // andar e bater na borda não mudam de tom; sem categoria também não
        assertEquals(1f, SonsCruz.tom(SonsCruz.Som.ANDAR, CruzLogica.Categoria.JOGAR))
        assertEquals(1f, SonsCruz.tom(SonsCruz.Som.BORDA, CruzLogica.Categoria.JOGAR))
        assertEquals(1f, SonsCruz.tom(SonsCruz.Som.GRUPO, null))
    }

    @Test fun deNoiteSaiMaisBaixo() {
        val dia = SonsCruz.volume(15)
        for (h in listOf(22, 23, 0, 3, 6)) assertTrue("$h h", SonsCruz.volume(h) < dia)
        for (h in listOf(7, 12, 21)) assertEquals("$h h", dia, SonsCruz.volume(h))
    }
}
