package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.ConfortoRegras
import br.paulo.tvlauncher.player.FimDoVideo
import br.paulo.tvlauncher.player.Idioma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Confortos do estudo do Stremio (16/11/2026): segundo idioma, esconder spoiler e contagem do próximo episódio. */
class ConfortoRegrasTest {
    private fun f(l: String?, forcada: Boolean = false) = Idioma.Faixa(l, forcada = forcada)

    @Test fun opcoesValidas() {
        assertEquals(10, ConfortoRegras.contagemValida(7))           // valor estragado volta ao padrão
        assertEquals(0, ConfortoRegras.contagemValida(0))
        assertEquals("en", ConfortoRegras.segundoValido(" EN "))
        assertNull(ConfortoRegras.segundoValido("xx"))
        assertNull(ConfortoRegras.segundoUtil("pt", "pt"))            // igual ao da TV: não serve de segundo
        assertEquals("en", ConfortoRegras.segundoUtil("en", "pt"))
    }

    @Test fun spoiler() {
        assertTrue(ConfortoRegras.esconderResumo(ligado = true, comecado = false))
        assertFalse(ConfortoRegras.esconderResumo(ligado = true, comecado = true))
        assertFalse(ConfortoRegras.esconderResumo(ligado = false, comecado = false))
    }

    @Test fun audioNoSegundoIdioma() {
        val faixas = listOf(f("ko"), f("en"))
        assertNull(Idioma.escolherAudio(faixas, Idioma.Pref.DUBLADO, "pt"))              // sem segundo: deixa o player
        assertEquals(1, Idioma.escolherAudio(faixas, Idioma.Pref.DUBLADO, "pt", "en"))   // sem português: inglês
        assertEquals(0, Idioma.escolherAudio(listOf(f("pt"), f("en")), Idioma.Pref.DUBLADO, "pt", "en"))   // português ganha
    }

    @Test fun legendaNoSegundoIdioma() {
        val legs = listOf(f("en", forcada = true), f("en"), f("es"))
        assertNull(Idioma.escolherLegenda(legs, audioPortugues = false, idioma = "pt"))
        assertEquals(1, Idioma.escolherLegenda(legs, false, "pt", "en"))                 // a completa, não a forçada
        assertNull(Idioma.escolherLegenda(legs, false, "pt", "en", audioNoSegundo = true)) // áudio já em inglês
        assertEquals(0, Idioma.escolherLegenda(listOf(f("pt"), f("en")), false, "pt", "en"))   // a do idioma da TV ganha
    }

    @Test fun contagemZeroNaoSegueSozinho() {
        assertEquals(FimDoVideo.Passo.NADA, FimDoVideo.depois(true, true, false, 0, contagem = 0))
        assertEquals(FimDoVideo.Passo.DESLIGAR, FimDoVideo.depois(true, true, true, 0, contagem = 0))
        assertEquals(FimDoVideo.Passo.PROXIMO_CONTANDO, FimDoVideo.depois(true, true, false, 0, contagem = 20))
    }
}
