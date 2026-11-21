package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.AjustesPodcast
import br.paulo.tvlauncher.listen.AjustesPodcast.Ajuste
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AjustesPodcastTest {
    @Test fun idaEVolta() {
        val a = Ajuste(1.5f, true, null, 30, 45)
        assertEquals(a, AjustesPodcast.deJson(AjustesPodcast.paraJson(a)))
        assertNull(AjustesPodcast.deJson(AjustesPodcast.paraJson(Ajuste())))   // vazio = sem ajuste próprio
        assertNull(AjustesPodcast.deJson("lixo"))
    }

    @Test fun efetivoUsaOGeralNoQueFalta() {
        val e = AjustesPodcast.efetivo(Ajuste(vozClara = true), 1.2f, false, true)
        assertEquals(1.2f, e.velocidade); assertTrue(e.vozClara); assertTrue(e.pularSilencio)
        assertEquals(1f, AjustesPodcast.efetivo(null, 1f, false, false).velocidade)
    }

    @Test fun aberturaSoDoZero() {
        assertEquals(30_000L, AjustesPodcast.inicio(0, 30, 3_600_000))
        assertEquals(30_000L, AjustesPodcast.inicio(1_500, 30, 3_600_000))
        assertEquals(600_000L, AjustesPodcast.inicio(600_000, 30, 3_600_000))   // retomar do meio não pula
        assertEquals(0L, AjustesPodcast.inicio(0, 30, 70_000))                  // episódio curto não pula
        assertEquals(30_000L, AjustesPodcast.inicio(0, 30, 0))                   // duração desconhecida: pula
    }

    @Test fun fim() {
        assertTrue(AjustesPodcast.noFim(3_560_000, 3_600_000, 45))
        assertFalse(AjustesPodcast.noFim(3_500_000, 3_600_000, 45))
        assertFalse(AjustesPodcast.noFim(50_000, 90_000, 45))   // curto demais
        assertFalse(AjustesPodcast.noFim(3_599_000, 0, 45))     // sem duração
    }
}
