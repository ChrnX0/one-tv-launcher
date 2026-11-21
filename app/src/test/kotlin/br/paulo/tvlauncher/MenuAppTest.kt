package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.MenuApp
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MenuAppTest {
    private val nosso = "br.paulo.tvlauncher"

    @Test fun appDeUsuarioPodeDesinstalar() =
        assertTrue(MenuApp.podeDesinstalar("com.netflix.ninja", nosso, sistema = false, protegido = false))

    @Test fun appDeFabricaNaoDesinstala() =
        assertFalse(MenuApp.podeDesinstalar("com.google.android.youtube.tv", nosso, sistema = true, protegido = false))

    @Test fun protegidoNaoDesinstala() =
        assertFalse(MenuApp.podeDesinstalar("com.adguard.vpn", nosso, sistema = false, protegido = true))

    // O próprio launcher (HOME, Device Owner, ADB embutido, acessibilidade) nunca, mesmo fora da lista de protegidos.
    @Test fun launcherNuncaDesinstala() =
        assertFalse(MenuApp.podeDesinstalar(nosso, nosso, sistema = false, protegido = false))

    @Test fun launcherEstaNosProtegidosFixos() = assertTrue(nosso in MenuApp.PROTEGIDOS_FIXOS)

    @Test fun launcherDoGoogleEInstaladorProtegidos() = assertTrue(
        listOf("com.google.android.apps.tv.launcherx", "com.android.packageinstaller", "com.google.android.gms")
            .all { it in MenuApp.PROTEGIDOS_FIXOS })

    @Test fun ocultarSoSemPinOQueAFichaDizSeguro() {
        org.junit.Assert.assertFalse(MenuApp.ocultarPedePin("seguro"))
        for (r in listOf("cuidado", "essencial", "sem informação", "")) org.junit.Assert.assertTrue(r, MenuApp.ocultarPedePin(r))
    }
}
