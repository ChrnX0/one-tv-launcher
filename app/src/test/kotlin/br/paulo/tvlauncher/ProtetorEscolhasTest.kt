package br.paulo.tvlauncher

import br.paulo.tvlauncher.screensaver.Protetor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escolhas do protetor v2 (17/11/2026): listas fechadas, padrões, "só a hora" e o liga/desliga de cada elemento. */
class ProtetorEscolhasTest {
    @Test fun escolhaDesconhecidaViraPadrao() {
        assertEquals(Protetor.FUNDO_TEMA, Protetor.FUNDO.normalizar("xyz"))
        assertEquals(Protetor.MOSTRAR_SO_HORA, Protetor.MOSTRAR.normalizar(null))
        assertEquals(Protetor.POS_ALTO, Protetor.POSICAO.normalizar(""))
        assertEquals(Protetor.REL_FINO, Protetor.RELOGIO.padrao)
    }

    @Test fun okPassaParaOProximoEDaAVolta() {
        assertEquals(Protetor.FUNDO_TERRA, Protetor.FUNDO.proximo(Protetor.FUNDO_TEMA))
        assertEquals(Protetor.FUNDO_TEMA, Protetor.FUNDO.proximo(Protetor.FUNDO_TERRA))
        assertEquals(Protetor.MOSTRAR_SEMPRE, Protetor.MOSTRAR.proximo(Protetor.MOSTRAR_DE_VEZ))
    }

    @Test fun relogioPulaOPorExtensoForaDoPortugues() {
        assertEquals(Protetor.REL_EMPILHADO, Protetor.proximoRelogio(Protetor.REL_PONTEIROS, "en"))
        assertEquals(Protetor.REL_EXTENSO, Protetor.proximoRelogio(Protetor.REL_PONTEIROS, "pt"))
        assertEquals(Protetor.REL_FINO, Protetor.proximoRelogio(Protetor.REL_EMPILHADO, "pt"))
        assertEquals(Protetor.REL_FINO, Protetor.proximoRelogio(Protetor.REL_EXTENSO, "en"))
    }

    @Test fun soAHoraMostraSoDepoisDaTecla() {
        val abriu = 1_000_000L
        assertFalse(Protetor.infoVisivel(Protetor.MOSTRAR_SO_HORA, abriu + 5_000, 0, abriu))
        val tecla = abriu + 60_000
        assertTrue(Protetor.infoVisivel(Protetor.MOSTRAR_SO_HORA, tecla + 1, tecla + Protetor.TECLA_MS, abriu))
        assertFalse(Protetor.infoVisivel(Protetor.MOSTRAR_SO_HORA, tecla + Protetor.TECLA_MS + 1, tecla + Protetor.TECLA_MS, abriu))
        assertTrue(Protetor.infoVisivel(Protetor.MOSTRAR_SEMPRE, abriu, 0, abriu))
    }

    @Test fun deVezEmQuandoMostra15sACada30s() {
        val abriu = 0L
        assertTrue(Protetor.infoVisivel(Protetor.MOSTRAR_DE_VEZ, 1_000, 0, abriu))
        assertFalse(Protetor.infoVisivel(Protetor.MOSTRAR_DE_VEZ, 20_000, 0, abriu))
        assertTrue(Protetor.infoVisivel(Protetor.MOSTRAR_DE_VEZ, 31_000, 0, abriu))
        assertTrue(Protetor.infoVisivel(Protetor.MOSTRAR_DE_VEZ, 20_000, 25_000, abriu))   // a tecla vale também aqui
    }

    @Test fun ocultosIdaEVolta() {
        val s = setOf(Protetor.Elemento.CURIOSIDADES, Protetor.Elemento.SAUDACAO)
        assertEquals("saud,cur", Protetor.escreverOcultos(s))
        assertEquals(s, Protetor.lerOcultos("cur, saud,coisa_nova"))
        assertTrue(Protetor.lerOcultos(null).isEmpty())
    }
}
