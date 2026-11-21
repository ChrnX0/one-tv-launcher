package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.StartupApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** "Ao ligar a TV" (StartupApp.decidir): abre só quando a frente é a tela inicial; nunca rouba Cast nem alarme. */
class StartupAppTest {
    private val nosso = "tv.sofaly.launcher"
    private val main = MainActivity::class.java.name
    private val netflix = "com.netflix.ninja"

    @Test fun telaInicialNaoFazNada() {
        assertNull(StartupApp.decidir(StartupApp.HOME, netflix, nosso, main, nosso))
    }

    @Test fun abreOAppSobreANossaHome() {
        assertEquals(netflix, StartupApp.decidir(netflix, null, nosso, main, nosso))
        assertEquals(netflix, StartupApp.decidir(netflix, null, null, null, nosso))   // ninguém na frente ainda (boot)
        assertEquals(netflix, StartupApp.decidir(netflix, null, "com.google.android.apps.tv.launcherx", null, nosso))
    }

    @Test fun naoRoubaCastNemAlarmeNemAppAberto() {
        assertNull(StartupApp.decidir(netflix, null, "com.google.android.youtube.tv", null, nosso))   // Cast/app na frente
        assertNull(StartupApp.decidir(netflix, null, nosso, "br.paulo.tvlauncher.AlarmeTocandoActivity", nosso))   // alarme
        assertNull(StartupApp.decidir(netflix, null, netflix, null, nosso))   // já está nele
    }

    @Test fun ultimoApp() {
        assertEquals(netflix, StartupApp.decidir(StartupApp.LAST, netflix, nosso, main, nosso))
        assertNull(StartupApp.decidir(StartupApp.LAST, null, nosso, main, nosso))
        assertNull(StartupApp.decidir(StartupApp.LAST, nosso, nosso, main, nosso))   // nunca "abrir a si mesmo"
    }

    @Test fun entradaHdmi() {
        assertEquals("entrada:hdmi1", StartupApp.decidir("entrada:hdmi1", null, nosso, main, nosso))
    }

    @Test fun lembrarUltimoIgnoraSistemaELaunchers() {
        assertEquals(netflix, StartupApp.lembrarUltimo(netflix, nosso))
        assertNull(StartupApp.lembrarUltimo(nosso, nosso))
        assertNull(StartupApp.lembrarUltimo("com.google.android.apps.tv.launcherx", nosso))
        assertNull(StartupApp.lembrarUltimo("com.android.systemui", nosso))
        assertNull(StartupApp.lembrarUltimo("com.android.tv.settings", nosso))
        assertNull(StartupApp.lembrarUltimo(null, nosso))
    }

    @Test fun atrasoDaVolta() {
        assertEquals(5, StartupApp.nextDelay(0))
        assertEquals(0, StartupApp.nextDelay(30))
        assertEquals(0, StartupApp.nextDelay(7))   // fora da lista: recomeça
    }
}
