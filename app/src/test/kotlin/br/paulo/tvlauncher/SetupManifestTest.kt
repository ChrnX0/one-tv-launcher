package br.paulo.tvlauncher

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APK de instalação (-Psetup, 21/11/2026): o Play Protect bloqueia, sem saída, o APK vindo do navegador/Downloader que
 * declara acessibilidade, ouvinte de notificações ou SMS. Todo serviço assim no manifesto principal tem que sair no
 * src/setup/AndroidManifest.xml; um serviço novo esquecido ali volta a bloquear a instalação nas TVs de fora.
 */
class SetupManifestTest {
    private fun ler(caminho: String) = listOf(File(caminho), File("app/$caminho")).first { it.exists() }.readText()

    @Test fun servicosSensiveisSaemNoApkDeInstalacao() {
        val principal = ler("src/main/AndroidManifest.xml").replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
        val setup = ler("src/setup/AndroidManifest.xml")
        val sensiveis = Regex("<service\\b[^>]*>", RegexOption.DOT_MATCHES_ALL).findAll(principal).map { it.value }
            .filter { "BIND_ACCESSIBILITY_SERVICE" in it || "BIND_NOTIFICATION_LISTENER_SERVICE" in it }
            .map { Regex("android:name=\"([^\"]+)\"").find(it)!!.groupValues[1] }.toList()
        assertTrue("achou os dois serviços conhecidos: $sensiveis", sensiveis.size >= 2)
        for (nome in sensiveis)
            assertTrue("$nome precisa de tools:node=\"remove\" em src/setup/AndroidManifest.xml",
                Regex("<service\\s+android:name=\"${Regex.escape(nome)}\"\\s+tools:node=\"remove\"").containsMatchIn(setup))
        for (p in listOf("RECEIVE_SMS", "READ_SMS"))
            assertTrue("$p bloqueia a instalação pelo Play Protect", "android.permission.$p\"" !in principal)
    }
}
