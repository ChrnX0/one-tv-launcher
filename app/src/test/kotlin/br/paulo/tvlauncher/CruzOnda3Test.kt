package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.CruzLogica
import br.paulo.tvlauncher.home.CruzOnda3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CruzOnda3Test {
    @Test fun ambienteSoComACruzParadaEmFocoESemNadaTocando() {
        assertTrue(CruzOnda3.entraAmbiente(true, cruzEmFoco = true, todosAbertos = false, menuAberto = false, tocando = false))
        assertFalse("desligado", CruzOnda3.entraAmbiente(false, true, false, false, false))
        assertFalse("foco na barra de cima", CruzOnda3.entraAmbiente(true, false, false, false, false))
        assertFalse("todos os apps", CruzOnda3.entraAmbiente(true, true, true, false, false))
        assertFalse("menu aberto", CruzOnda3.entraAmbiente(true, true, false, true, false))
        assertFalse("rádio tocando", CruzOnda3.entraAmbiente(true, true, false, false, true))
    }

    @Test fun arteVaiParaOLadoOpostoDaTecla() {
        assertEquals(1 to 0, CruzOnda3.paralaxe(CruzLogica.Tecla.ESQUERDA))
        assertEquals(-1 to 0, CruzOnda3.paralaxe(CruzLogica.Tecla.DIREITA))
        assertEquals(0 to 1, CruzOnda3.paralaxe(CruzLogica.Tecla.CIMA))
        assertEquals(0 to -1, CruzOnda3.paralaxe(CruzLogica.Tecla.BAIXO))
        assertEquals(0 to 0, CruzOnda3.paralaxe(CruzLogica.Tecla.OK))
    }

    @Test fun quemEstaVendoSoComDoisPerfisDepoisDeDormir() {
        val hora = 60 * 60_000L
        assertTrue(CruzOnda3.perguntaQuemVe(true, contas = 2, dormiuMs = 8 * hora, desdeQueLigouMs = 3000, visita = false))
        assertFalse("um perfil só", CruzOnda3.perguntaQuemVe(true, 1, 8 * hora, 3000, false))
        assertFalse("cochilo de 10 min", CruzOnda3.perguntaQuemVe(true, 2, 10 * 60_000L, 3000, false))
        assertFalse("HOME muito depois de ligar", CruzOnda3.perguntaQuemVe(true, 2, 8 * hora, 10 * 60_000L, false))
        assertFalse("modo visita", CruzOnda3.perguntaQuemVe(true, 2, 8 * hora, 3000, true))
        assertFalse("desligado", CruzOnda3.perguntaQuemVe(false, 3, 8 * hora, 3000, false))
    }

    @Test fun dormirSoANoiteOuComTimerMarcado() {
        for (h in listOf(22, 23, 0, 3)) assertTrue("$h h", CruzOnda3.mostraDormir(true, h, false))
        for (h in listOf(4, 12, 21)) assertFalse("$h h", CruzOnda3.mostraDormir(true, h, false))
        assertFalse(CruzOnda3.mostraDormir(false, 23, false))
        assertTrue("timer marcado aparece sempre (para cancelar)", CruzOnda3.mostraDormir(false, 15, true))
    }
}
