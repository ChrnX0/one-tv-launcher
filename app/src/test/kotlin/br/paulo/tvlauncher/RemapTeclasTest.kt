package br.paulo.tvlauncher

import android.view.KeyEvent
import br.paulo.tvlauncher.RemapTeclas.Gesto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Ideia 6: teclas livres, JSON do remap e a máquina dos gestos. */
class RemapTeclasTest {
    @Test fun soTeclasLivres() {
        assertTrue(RemapTeclas.livre(KeyEvent.KEYCODE_BUTTON_4))
        assertTrue(RemapTeclas.livre(KeyEvent.KEYCODE_PROG_RED))
        assertTrue(RemapTeclas.livre(KeyEvent.KEYCODE_7))
        for (k in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_VOLUME_UP,
                KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) assertFalse(RemapTeclas.livre(k))
        assertFalse(RemapTeclas.soNaHome(KeyEvent.KEYCODE_BUTTON_3))
        assertTrue(RemapTeclas.soNaHome(KeyEvent.KEYCODE_5))
    }

    @Test fun jsonIdaEVoltaEFiltraLixo() {
        val m = mapOf(KeyEvent.KEYCODE_PROG_RED to mapOf(Gesto.TOQUE to "app:com.netflix.ninja", Gesto.DUPLO to "cena:Noite"))
        assertEquals(m, RemapTeclas.lerJson(RemapTeclas.paraJson(m)))
        val lixo = """{"${KeyEvent.KEYCODE_DPAD_UP}":{"toque":"app:a.b"},"${KeyEvent.KEYCODE_1}":{"toque":"sh:reboot","segurar":"cena:ok"}}"""
        assertEquals(mapOf(KeyEvent.KEYCODE_1 to mapOf(Gesto.SEGURAR to "cena:ok")), RemapTeclas.lerJson(lixo))
        assertEquals(emptyMap<Int, Map<Gesto, String>>(), RemapTeclas.lerJson("estragado"))
        assertFalse(RemapTeclas.atalhoValido("app:semponto"))
        assertFalse(RemapTeclas.atalhoValido("cena:"))
    }

    @Test fun toqueSemEsperaQuandoSoHaToque() {
        val g = Gestos(700, 350, temSegurar = false, temDuplo = false)
        assertNull(g.desce(0)); assertNull(g.prazo)
        assertEquals(Gesto.TOQUE, g.sobe(100))
    }

    @Test fun segurarDisparaNoPrazoEIgnoraOSoltar() {
        val g = Gestos(700, 350, temSegurar = true, temDuplo = false)
        g.desce(0)
        assertEquals(700L, g.prazo)
        assertNull(g.venceu(600))
        assertEquals(Gesto.SEGURAR, g.venceu(700))
        assertNull(g.sobe(900))
        assertTrue(g.livre)
        g.desce(1000)
        assertEquals(Gesto.TOQUE, g.sobe(1100))   // toque curto com segurar configurado
        assertNull(g.venceu(1700))                // prazo velho não faz nada
    }

    @Test fun duploEToqueComEspera() {
        val g = Gestos(700, 350, temSegurar = false, temDuplo = true)
        g.desce(0); assertNull(g.sobe(80))
        assertEquals(Gesto.DUPLO, g.desce(200))
        assertNull(g.sobe(260))
        g.desce(1000); g.sobe(1080)
        assertNull(g.venceu(1300))
        assertEquals(Gesto.TOQUE, g.venceu(1430))
    }

    @Test fun outraTeclaSoltaOToquePendente() {
        val g = Gestos(700, 350, temSegurar = false, temDuplo = true)
        g.desce(0); g.sobe(80)
        assertEquals(Gesto.TOQUE, g.interromper())
        assertNull(g.interromper())
    }
}
