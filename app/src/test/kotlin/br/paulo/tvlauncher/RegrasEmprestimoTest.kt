package br.paulo.tvlauncher

import br.paulo.tvlauncher.privilege.RegrasEmprestimo
import br.paulo.tvlauncher.privilege.RegrasEmprestimo.Modo
import br.paulo.tvlauncher.privilege.RegrasEmprestimo.Parado
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RegrasEmprestimoTest {
    @Test fun desligadoDeFabrica() {
        assertEquals(Modo.DESLIGADO, RegrasEmprestimo.modoDe(null))
        assertEquals(Modo.DESLIGADO, RegrasEmprestimo.modoDe("LIGADO_DEMAIS"))   // texto de versão futura
        assertEquals(Modo.SEMPRE, RegrasEmprestimo.modoDe("SEMPRE"))
    }

    @Test fun quandoFicaParado() {
        assertEquals(Parado.DESLIGADO, RegrasEmprestimo.parado(Modo.DESLIGADO, true, false))
        assertEquals(Parado.PLANO, RegrasEmprestimo.parado(Modo.SEMPRE, false, false))
        assertEquals(Parado.SHIZUKU_OFICIAL, RegrasEmprestimo.parado(Modo.SOB_DEMANDA, true, true))
        assertEquals(Parado.NAO, RegrasEmprestimo.parado(Modo.SOB_DEMANDA, true, false))
    }

    @Test fun clienteEhQuemPedeAPermissao() {
        assertTrue(RegrasEmprestimo.ehCliente(arrayOf("android.permission.INTERNET", "moe.shizuku.manager.permission.API_V23")))
        assertFalse(RegrasEmprestimo.ehCliente(arrayOf("android.permission.INTERNET")))
        assertFalse(RegrasEmprestimo.ehCliente(null))
    }

    @Test fun comandoDePartida() {
        val c = RegrasEmprestimo.comandoPartida("/data/app/~~a1==/br.paulo.tvlauncher-b2==/base.apk", "/data/app/~~a1==/br.paulo.tvlauncher-b2==/lib/arm")
        assertTrue(c.contains("--nice-name=shizuku_server rikka.shizuku.server.ShizukuService"))
        assertTrue(c.contains("CLASSPATH=/data/app/~~a1==/br.paulo.tvlauncher-b2==/base.apk"))
        assertTrue(c.endsWith("sleep 2; echo disparado"))
    }

    @Test(expected = IllegalArgumentException::class) fun caminhoEstranhoNaoViraComando() {
        RegrasEmprestimo.comandoPartida("/data/app/x;reboot/base.apk", "/lib")
    }

    /** O motivo da saída em 12/11/2026: definir permissão do Shizuku impede instalar onde o app Shizuku existe. */
    @Test fun manifestoNaoDefinePermissaoDoShizuku() {
        val m = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.isFile }.readText()
        assertFalse("o launcher não pode DEFINIR <permission> do Shizuku", Regex("""<permission(-group)?\s[^>]*moe\.shizuku""").containsMatchIn(m))
    }
}
