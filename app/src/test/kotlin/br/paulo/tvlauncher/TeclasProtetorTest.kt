package br.paulo.tvlauncher

import android.view.KeyEvent
import br.paulo.tvlauncher.TeclasProtetor.Acao
import br.paulo.tvlauncher.TeclasProtetor.Tipo
import org.junit.Assert.assertEquals
import org.junit.Test

class TeclasProtetorTest {
    private fun a(tecla: Int, cartoes: Int = 0, escolhido: Tipo? = null, musica: Boolean = false) =
        TeclasProtetor.acao(tecla, cartoes, escolhido, musica)

    @Test fun setasAndamSoComCartoes() {
        assertEquals(Acao.PROXIMO, a(KeyEvent.KEYCODE_DPAD_RIGHT, cartoes = 2))
        assertEquals(Acao.ANTERIOR, a(KeyEvent.KEYCODE_DPAD_LEFT, cartoes = 1))
        assertEquals(Acao.FECHAR, a(KeyEvent.KEYCODE_DPAD_RIGHT, cartoes = 0))
    }

    @Test fun okDispensaOuAlternaOuFecha() {
        assertEquals(Acao.DISPENSAR, a(KeyEvent.KEYCODE_DPAD_CENTER, 2, Tipo.RECADO))
        assertEquals(Acao.DISPENSAR, a(KeyEvent.KEYCODE_ENTER, 2, Tipo.NOTIFICACAO))
        assertEquals(Acao.ALTERNAR_MUSICA, a(KeyEvent.KEYCODE_DPAD_CENTER, 2, Tipo.TOCANDO, true))
        assertEquals(Acao.ALTERNAR_MUSICA, a(KeyEvent.KEYCODE_DPAD_CENTER, 2, null, true))
        assertEquals(Acao.FECHAR, a(KeyEvent.KEYCODE_DPAD_CENTER, 2, null, false))
        assertEquals(Acao.FECHAR, a(KeyEvent.KEYCODE_DPAD_CENTER, 2, Tipo.OUTRO, true))
    }

    @Test fun volumeEMidiaNaoFecham() {
        assertEquals(Acao.DEIXAR_PASSAR, a(KeyEvent.KEYCODE_VOLUME_UP))
        assertEquals(Acao.ALTERNAR_MUSICA, a(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, musica = true))
        assertEquals(Acao.DEIXAR_PASSAR, a(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, musica = false))
    }

    @Test fun outrasTeclasFecham() {
        assertEquals(Acao.FECHAR, a(KeyEvent.KEYCODE_BACK, 3, Tipo.RECADO, true))
        assertEquals(Acao.FECHAR, a(KeyEvent.KEYCODE_DPAD_UP, 3))
        assertEquals(Acao.FECHAR, a(KeyEvent.KEYCODE_DPAD_DOWN, 3))
    }

    @Test fun moverParaNasPontas() {
        assertEquals(0, TeclasProtetor.mover(-1, 1, 3))
        assertEquals(2, TeclasProtetor.mover(-1, -1, 3))
        assertEquals(2, TeclasProtetor.mover(2, 1, 3))
        assertEquals(0, TeclasProtetor.mover(0, -1, 3))
        assertEquals(-1, TeclasProtetor.mover(0, 1, 0))
        assertEquals(1, TeclasProtetor.mover(5, -1, 2))   // índice velho (cartão sumiu) recomeça pela ponta
    }

    @Test fun janelaMantemEscolhidoAVista() {
        assertEquals(0, TeclasProtetor.inicioJanela(1, 5, 3))
        assertEquals(1, TeclasProtetor.inicioJanela(3, 5, 3))
        assertEquals(2, TeclasProtetor.inicioJanela(4, 5, 3))
        assertEquals(0, TeclasProtetor.inicioJanela(-1, 5, 3))
        assertEquals(0, TeclasProtetor.inicioJanela(2, 3, 3))
    }
}
