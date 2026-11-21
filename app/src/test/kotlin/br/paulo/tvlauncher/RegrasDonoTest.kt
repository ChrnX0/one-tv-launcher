package br.paulo.tvlauncher

import br.paulo.tvlauncher.privilege.RegrasDono
import br.paulo.tvlauncher.privilege.RegrasDono.Arg
import br.paulo.tvlauncher.privilege.RegrasDono.Parado
import br.paulo.tvlauncher.privilege.RegrasDono.Veredito
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Empréstimo do Device Owner (Dhizuku): quem passa e o que nunca passa. */
class RegrasDonoTest {
    private val nosso = "br.paulo.tvlauncher"
    private val dpm = RegrasDono.DPM

    @Test fun paradoNaOrdem() {
        assertEquals(Parado.DESLIGADO, RegrasDono.parado(ligado = false, planoPermite = true, somosDono = true))
        assertEquals(Parado.PLANO, RegrasDono.parado(ligado = true, planoPermite = false, somosDono = true))
        assertEquals(Parado.SEM_DONO, RegrasDono.parado(ligado = true, planoPermite = true, somosDono = false))
        assertEquals(Parado.NAO, RegrasDono.parado(ligado = true, planoPermite = true, somosDono = true))
    }

    @Test fun poderTotalSoPorApp() {
        assertFalse(RegrasDono.permite(null, autorizado = false, poderTotal = true))
        assertTrue(RegrasDono.permite("remote_transact", autorizado = true, poderTotal = false))
        assertTrue(RegrasDono.permite(null, autorizado = true, poderTotal = false))
        for (f in listOf("remote_process", "bind_user_service", "unbind_user_service", "set_delegated_scopes")) {
            assertFalse(f, RegrasDono.permite(f, autorizado = true, poderTotal = false))
            assertTrue(f, RegrasDono.permite(f, autorizado = true, poderTotal = true))
        }
    }

    @Test fun nuncaTirarODonoNemApagar() {
        for (m in listOf("wipeDataWithReason", "clearDeviceOwner", "transferOwnership", "removeActiveAdmin", "setDeviceOwner"))
            for (total in listOf(false, true))
                assertEquals(m, Veredito.BLOQUEIA, RegrasDono.transacao(dpm, m, emptyList(), nosso, total))
    }

    @Test fun semPoderTotalSoODpm() {
        assertEquals(Veredito.BLOQUEIA, RegrasDono.transacao("android.content.pm.IPackageInstaller", null, null, nosso, false))
        assertEquals(Veredito.BLOQUEIA, RegrasDono.transacao(null, null, null, nosso, false))
        assertEquals(Veredito.PASSA, RegrasDono.transacao("android.content.pm.IPackageInstaller", null, null, nosso, true))
        assertEquals(Veredito.BLOQUEIA, RegrasDono.transacao(dpm, "setDelegatedScopes", listOf("x"), nosso, false))
    }

    @Test fun depuracao17_11SemPoderTotal() {
        // senha com apagamento e usuários novos: nunca, nem com poder total
        for (m in listOf("setResetPasswordToken", "resetPasswordWithToken", "setMaximumFailedPasswordsForWipe", "createAndManageUser", "switchUser"))
            for (total in listOf(false, true))
                assertEquals(m, Veredito.BLOQUEIA, RegrasDono.transacao(dpm, m, emptyList(), nosso, total))
        // trocam a lista inteira (ou VPN/HOME fixas): só com poder total, mesmo sem o launcher nos alvos
        for (m in listOf("setUserControlDisabledPackages", "setPermittedAccessibilityServices", "setAlwaysOnVpnPackage",
                "addPersistentPreferredActivity", "setApplicationRestrictionsManagingPackage")) {
            assertEquals(m, Veredito.BLOQUEIA, RegrasDono.transacao(dpm, m, listOf("a.b"), nosso, false))
            assertEquals(m, Veredito.PASSA, RegrasDono.transacao(dpm, m, listOf("a.b"), nosso, true))
        }
        // setGlobalSetting(admin, setting, value): adb_enabled derrubaria o shell próprio
        val adb = RegrasDono.textos(listOf(Arg.Componente(nosso), Arg.Texto("adb_enabled"), Arg.Texto("0")))
        assertEquals(Veredito.BLOQUEIA, RegrasDono.transacao(dpm, "setGlobalSetting", null, nosso, false, adb))
        val outro = RegrasDono.textos(listOf(Arg.Componente(nosso), Arg.Texto("auto_time"), Arg.Texto("1")))
        assertEquals(Veredito.PASSA, RegrasDono.transacao(dpm, "setGlobalSetting", null, nosso, false, outro))
        assertEquals(Veredito.BLOQUEIA_SEM_MAPA, RegrasDono.transacao(dpm, "setGlobalSetting", null, nosso, false, null))
        val restricao = RegrasDono.textos(listOf(Arg.Componente(nosso), Arg.Texto(nosso), Arg.Texto("no_debugging_features"), Arg.Outro))
        assertEquals(Veredito.BLOQUEIA, RegrasDono.transacao(dpm, "addUserRestriction", null, nosso, false, restricao))
        assertEquals(Veredito.PASSA, RegrasDono.transacao(dpm, "addUserRestriction", null, nosso, true, restricao))
    }

    @Test fun semMapaNega() {
        assertEquals(Veredito.BLOQUEIA_SEM_MAPA, RegrasDono.transacao(dpm, null, null, nosso, false))
        assertEquals(Veredito.BLOQUEIA_SEM_MAPA, RegrasDono.transacao(dpm, "setApplicationHidden", null, nosso, false))
        assertEquals(Veredito.PASSA, RegrasDono.transacao(dpm, "isDeviceOwnerApp", null, nosso, false))
    }

    @Test fun naoMexeNoLauncher() {
        // setApplicationHidden(admin, callerPackage, packageName, hidden, parent)
        fun esconder(alvo: String) = RegrasDono.alvos(listOf(Arg.Componente(nosso), Arg.Texto(nosso), Arg.Texto(alvo), Arg.Outro, Arg.Outro))
        assertEquals(Veredito.BLOQUEIA, RegrasDono.transacao(dpm, "setApplicationHidden", esconder(nosso), nosso, false))
        assertEquals(Veredito.PASSA, RegrasDono.transacao(dpm, "setApplicationHidden", esconder("com.netflix.ninja"), nosso, false))
        // clearApplicationUserData(admin, packageName, callback): sem callerPackage, o 2º é o alvo
        val limpar = RegrasDono.alvos(listOf(Arg.Componente(nosso), Arg.Texto(nosso), Arg.Outro))
        assertEquals(Veredito.BLOQUEIA, RegrasDono.transacao(dpm, "clearApplicationUserData", limpar, nosso, false))
        // setPackagesSuspended(admin, callerPackage, String[], suspended)
        val suspender = RegrasDono.alvos(listOf(Arg.Componente(nosso), Arg.Texto(nosso), Arg.Textos(listOf("a.b", nosso)), Arg.Outro))
        assertEquals(Veredito.BLOQUEIA, RegrasDono.transacao(dpm, "setPackagesSuspended", suspender, nosso, false))
        val suspenderOutro = RegrasDono.alvos(listOf(Arg.Componente(nosso), Arg.Texto(nosso), Arg.Textos(listOf("a.b")), Arg.Outro))
        assertEquals(Veredito.PASSA, RegrasDono.transacao(dpm, "setPackagesSuspended", suspenderOutro, nosso, false))
        // callerPackage null (o sistema usa o admin): o alvo continua sendo o 3º
        val semCaller = RegrasDono.alvos(listOf(Arg.Componente(nosso), Arg.Texto(null), Arg.Texto(nosso), Arg.Outro))
        assertEquals(listOf(nosso), semCaller)
    }

    @Test fun manifestoNaoDefinePermissaoDoDhizuku() {
        val m = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        assertFalse(Regex("<permission[^>]+com\\.rosan\\.dhizuku").containsMatchIn(m))
        assertTrue(m.contains("dhizuku_server.provider"))
    }

    @Test fun pacotesDeQueATvDependeFicamDeFora() {
        val dpm = RegrasDono.DPM; val nosso = "br.paulo.tvlauncher"
        val prot = setOf("com.google.android.apps.tv.launcherx", "com.google.android.gms")
        assertEquals(Veredito.BLOQUEIA, RegrasDono.transacao(dpm, "setApplicationHidden", listOf("com.google.android.apps.tv.launcherx"), nosso, false, protegidos = prot))
        assertEquals(Veredito.BLOQUEIA, RegrasDono.transacao(dpm, "clearApplicationUserData", listOf("com.google.android.gms"), nosso, false, protegidos = prot))
        assertEquals(Veredito.PASSA, RegrasDono.transacao(dpm, "setApplicationHidden", listOf("com.exemplo.jogo"), nosso, false, protegidos = prot))
    }
}
