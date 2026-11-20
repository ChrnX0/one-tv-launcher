package br.paulo.tvlauncher

import br.paulo.tvlauncher.MenuApp.Acao
import org.junit.Assert.assertEquals
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

    @Test fun menuDeAppDesinstalavel() =
        assertEquals(listOf(Acao.MOVER, Acao.OCULTAR, Acao.DESINSTALAR), MenuApp.acoes(naPastaOcultos = false, desinstalavel = true))

    @Test fun menuDeAppDeSistemaSemDesinstalar() =
        assertEquals(listOf(Acao.MOVER, Acao.OCULTAR), MenuApp.acoes(naPastaOcultos = false, desinstalavel = false))

    @Test fun menuNaPastaOcultos() {
        assertEquals(listOf(Acao.MOSTRAR, Acao.DESINSTALAR), MenuApp.acoes(naPastaOcultos = true, desinstalavel = true))
        assertEquals(listOf(Acao.MOSTRAR), MenuApp.acoes(naPastaOcultos = true, desinstalavel = false))
        // Sob demanda (20/11/2026): entra antes de Desinstalar; some na pasta Ocultos e quando o app não pode.
        assertEquals(listOf(Acao.MOVER, Acao.OCULTAR, Acao.SOB_DEMANDA, Acao.DESINSTALAR), MenuApp.acoes(false, true, sobDemanda = false))
        assertEquals(listOf(Acao.MOVER, Acao.OCULTAR, Acao.TIRAR_SOB_DEMANDA), MenuApp.acoes(false, false, sobDemanda = true))
        assertEquals(listOf(Acao.MOSTRAR), MenuApp.acoes(true, false, sobDemanda = true))
    }

    @Test fun pilhaFrentePrimeiroESoOsQuePodem() = assertEquals(
        listOf("com.netflix.ninja", "com.disney.plus"),
        MenuApp.desinstalaveisDaPilha(listOf("com.disney.plus", "com.google.android.youtube.tv", "com.netflix.ninja"), 2) {
            it != "com.google.android.youtube.tv"
        })

    @Test fun pilhaSemNinguemDesinstalavel() =
        assertEquals(emptyList<String>(), MenuApp.desinstalaveisDaPilha(listOf("a", "b"), 0) { false })

    @Test fun ocultarSoSemPinOQueAFichaDizSeguro() {
        org.junit.Assert.assertFalse(MenuApp.ocultarPedePin("seguro"))
        for (r in listOf("cuidado", "essencial", "sem informação", "")) org.junit.Assert.assertTrue(r, MenuApp.ocultarPedePin(r))
    }

    @Test fun liberarArquivosAntesDeDesinstalar() {
        assertEquals(listOf(Acao.MOVER, Acao.OCULTAR, Acao.LIBERAR_ARQUIVOS, Acao.DESINSTALAR),
            MenuApp.acoes(naPastaOcultos = false, desinstalavel = true, arquivosBloqueados = true))
        assertEquals(listOf(Acao.MOSTRAR), MenuApp.acoes(naPastaOcultos = true, desinstalavel = false, arquivosBloqueados = true))
    }
}
