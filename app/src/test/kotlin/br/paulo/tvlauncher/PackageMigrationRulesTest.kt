package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Troca de pacote br.paulo.tvlauncher -> tv.sofaly.launcher (PackageMigrationRules.kt / PackageMigration.kt). */
class PackageMigrationRulesTest {

    @Test fun pacoteTrocaMasClasseEAcaoFicam() {
        val r = PackageMigrationRules::rewritePackage
        assertEquals("tv.sofaly.launcher", r("br.paulo.tvlauncher", AppIdentity.LEGACY_PACKAGE, AppIdentity.NEW_PACKAGE))
        // Componente "pacote/classe": troca só o pacote; a classe continua no namespace do código.
        assertEquals("tv.sofaly.launcher/br.paulo.tvlauncher.AutorizaShizukuService",
            r("br.paulo.tvlauncher/br.paulo.tvlauncher.AutorizaShizukuService", AppIdentity.LEGACY_PACKAGE, AppIdentity.NEW_PACKAGE))
        assertEquals("tv.sofaly.launcher/.ProtetorTela", r("br.paulo.tvlauncher/.ProtetorTela", AppIdentity.LEGACY_PACKAGE, AppIdentity.NEW_PACKAGE))
        // Classe e ação: ficam.
        assertEquals("br.paulo.tvlauncher.MainActivity", r("br.paulo.tvlauncher.MainActivity", AppIdentity.LEGACY_PACKAGE, AppIdentity.NEW_PACKAGE))
        assertEquals("br.paulo.tvlauncher.PAREAR", r("br.paulo.tvlauncher.PAREAR", AppIdentity.LEGACY_PACKAGE, AppIdentity.NEW_PACKAGE))
        // Dentro de XML de preferências e de JSON escapado.
        assertEquals("<string name=\"vpn\">tv.sofaly.launcher</string>",
            r("<string name=\"vpn\">br.paulo.tvlauncher</string>", AppIdentity.LEGACY_PACKAGE, AppIdentity.NEW_PACKAGE))
        assertEquals("[&quot;com.netflix.ninja&quot;,&quot;tv.sofaly.launcher&quot;]",
            r("[&quot;com.netflix.ninja&quot;,&quot;br.paulo.tvlauncher&quot;]", AppIdentity.LEGACY_PACKAGE, AppIdentity.NEW_PACKAGE))
        // Prefixo/sufixo de outro nome: fica.
        assertEquals("xbr.paulo.tvlauncher", r("xbr.paulo.tvlauncher", AppIdentity.LEGACY_PACKAGE, AppIdentity.NEW_PACKAGE))
        assertEquals("br.paulo.tvlauncher2", r("br.paulo.tvlauncher2", AppIdentity.LEGACY_PACKAGE, AppIdentity.NEW_PACKAGE))
    }

    @Test fun caminhosPerigososNuncaPassam() {
        for (ruim in listOf("", "/etc/passwd", "../x", "files/../../x", "files/./a", "files//a", "files\\a", "shared_prefs/a\u0000b"))
            assertFalse(ruim, PackageMigrationRules.safePath(ruim))
        assertTrue(PackageMigrationRules.safePath("files/player/favoritos.json"))
    }

    @Test fun oQueEntraNaCopia() {
        val c = PackageMigrationRules::shouldCopy
        assertTrue(c("shared_prefs/addons.xml"))
        assertTrue(c("shared_prefs/plugins.xml"))
        assertTrue(c("files/player/favoritos.json"))
        assertTrue(c("files/adb_key.pk8"))               // o shell continua sem parear de novo
        assertTrue(c("files/tunel/tailscaled.state"))     // o mesmo endereço .ts.net
        assertTrue(c("databases/x.db"))
        assertTrue(c("external/perfil-ana.jpg"))
        // Fora: a chave do certificado (vai à parte, trancada de novo), o estado da migração, lixo de escrita e caches.
        assertFalse(c("no_backup/painel-tls/chave.bin"))
        assertFalse(c("shared_prefs/package_migration.xml"))
        assertFalse(c("shared_prefs/addons.xml.bak"))
        assertFalse(c("files/player/favoritos.json.tmp"))
        assertFalse(c("cache/x"))
        assertFalse(c("code_cache/x"))
        assertFalse(c("app_webview/x"))
        assertFalse(c("files"))
        assertFalse(c("files/../shared_prefs/a.xml"))
    }

    @Test fun reescreveSoTexto() {
        assertTrue(PackageMigrationRules.shouldRewrite("shared_prefs/a.xml"))
        assertTrue(PackageMigrationRules.shouldRewrite("files/player/ultimo.json"))
        assertFalse(PackageMigrationRules.shouldRewrite("files/adb_key.pk8"))
        assertFalse(PackageMigrationRules.shouldRewrite("files/tunel/tailscaled.state"))
    }

    @Test fun acessibilidadeTrocaDeDono() {
        val novo = "tv.sofaly.launcher/br.paulo.tvlauncher.AutorizaShizukuService"
        assertEquals("com.x/.A:$novo",
            PackageMigrationRules.swapAccessibility("com.x/.A:br.paulo.tvlauncher/.AutorizaShizukuService", "br.paulo.tvlauncher", novo))
        assertEquals(novo, PackageMigrationRules.swapAccessibility(null, "br.paulo.tvlauncher", novo))
        assertEquals(novo, PackageMigrationRules.swapAccessibility("$novo", "br.paulo.tvlauncher", novo))   // sem repetir
        // A volta: tira o novo e põe o antigo.
        assertEquals("br.paulo.tvlauncher/br.paulo.tvlauncher.AutorizaShizukuService",
            PackageMigrationRules.swapAccessibility(novo, "tv.sofaly.launcher", "br.paulo.tvlauncher/br.paulo.tvlauncher.AutorizaShizukuService"))
    }

    @Test fun apkDoReleaseSoComSha() {
        val n = setOf("one-tv-launcher.apk", "one-tv-launcher.apk.sha256", "sofaly-launcher.apk", "sofaly-launcher.apk.sha256",
            "sofaly-launcher-arm64.apk")
        assertEquals("sofaly-launcher.apk", PackageMigrationRules.pickAsset(n, is64 = false))
        assertNull(PackageMigrationRules.pickAsset(n, is64 = true))   // sem o .sha256 não instala
        assertNull(PackageMigrationRules.pickAsset(setOf("one-tv-launcher.apk", "one-tv-launcher.apk.sha256"), false))
    }

    @Test fun nossosPacotes() {
        assertTrue(AppIdentity.ours("tv.sofaly.launcher"))
        assertTrue(AppIdentity.ours("br.paulo.tvlauncher"))
        assertFalse(AppIdentity.ours("com.google.android.apps.tv.launcherx"))
        assertFalse(AppIdentity.ours(null))
        assertEquals("br.paulo.tvlauncher.MainActivity", AppIdentity.className("MainActivity"))
    }

    /** Sem estas linhas a transferência do dono falha (IllegalArgumentException) e a migração nem começa. */
    @Test fun manifestoPermiteATransferencia() {
        val raiz = listOf(File("src/main"), File("app/src/main")).first { it.isDirectory }
        val admin = File(raiz, "res/xml/device_admin.xml").readText()
        assertTrue(admin.contains("<support-transfer-ownership"))
        val m = File(raiz, "AndroidManifest.xml").readText()
        assertTrue(Regex("""<permission\s+android:name="tv\.sofaly\.permission\.MIGRATION"\s+android:protectionLevel="signature"""").containsMatchIn(m))
        val prov = Regex("""<provider[^>]*\.MigrationProvider[^>]*>""").find(m)?.value ?: error("sem MigrationProvider")
        assertTrue(prov.contains("android:permission=\"tv.sofaly.permission.MIGRATION\""))
        assertTrue(prov.contains("android:process=\":migration\""))
        assertTrue(m.contains("android.app.action.TRANSFER_OWNERSHIP_COMPLETE"))
    }
}
