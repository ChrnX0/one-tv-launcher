package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Auditoria de 10/11/2026 (grupo "tempo"): rotinas, hora de dormir, Home Assistant, fala do boletim e backup completo. */
class AuditoriaTempoTest {
    @Test fun ligarNaoInterrompeQuemEstaVendo() {
        assertFalse(Rotinas.deveAcordar(Rotinas.Acao.LIGAR, telaAcesa = true, protetorNaTela = false, appNaFrente = "com.netflix.ninja"))
        // HOME na frente (nossa ou a do Google) ou app desconhecido: a TV pode estar desligada pelo controle dela; acorda (CEC)
        assertTrue(Rotinas.deveAcordar(Rotinas.Acao.LIGAR, telaAcesa = true, protetorNaTela = false, appNaFrente = "br.paulo.tvlauncher"))
        assertTrue(Rotinas.deveAcordar(Rotinas.Acao.LIGAR, telaAcesa = true, protetorNaTela = false, appNaFrente = Home.LAUNCHERX))
        assertTrue(Rotinas.deveAcordar(Rotinas.Acao.LIGAR, telaAcesa = true, protetorNaTela = false, appNaFrente = null))
        assertTrue(Rotinas.deveAcordar(Rotinas.Acao.LIGAR, telaAcesa = false, protetorNaTela = false))
        assertTrue(Rotinas.deveAcordar(Rotinas.Acao.LIGAR, telaAcesa = true, protetorNaTela = true))
        assertTrue(Rotinas.deveAcordar(Rotinas.Acao.ABRIR, telaAcesa = true, protetorNaTela = false))
    }

    @Test fun tecladoEJanelasDoGoogleNaoMandamParaHome() {
        for (p in listOf("com.google.android.inputmethod.latin", "com.google.android.tvkeyboard", "com.google.android.gms",
                "com.google.android.permissioncontroller", "com.android.tv.settings"))
            assertFalse(p, HoraDormir.bloqueia(true, 22 * 60, 21 * 60, 6 * 60 + 30, p, emptySet()))
        assertTrue(HoraDormir.bloqueia(true, 22 * 60, 21 * 60, 6 * 60 + 30, "com.netflix.ninja", emptySet()))
    }

    @Test fun httpSoDentroDeCasa() {
        assertNull(CasaHA.enderecoValido("http://ha.exemplo.com:8123"))   // o token iria aberto pela internet
        assertNull(CasaHA.enderecoValido("http://8.8.8.8:8123"))
        assertNull(CasaHA.enderecoValido("http://172.32.0.1:8123"))
        assertNotNull(CasaHA.enderecoValido("http://192.168.1.10:8123"))
        assertNotNull(CasaHA.enderecoValido("http://10.0.0.5"))
        assertNotNull(CasaHA.enderecoValido("http://172.20.1.1:8123"))
        assertNotNull(CasaHA.enderecoValido("http://homeassistant.local:8123"))
        assertNotNull(CasaHA.enderecoValido("http://ha.lan"))
        assertNotNull(CasaHA.enderecoValido("http://homeassistant:8123"))   // nome sem ponto, do DNS do roteador
        assertNotNull(CasaHA.enderecoValido("http://ha.home.arpa"))
        assertNull(CasaHA.enderecoValido("http://100.64.1.2:8123"))          // CGNAT da operadora: fora de casa
        assertNotNull(CasaHA.enderecoValido("https://ha.exemplo.com"))
    }

    @Test fun campainhaAvisaUmaVezSo() {
        val c = CasaHA.Campainha()
        assertTrue(c.tocou("on"))
        assertFalse(c.tocou("on"))   // a volta seguinte (3 s depois) não repete o aviso
        assertFalse(c.tocou("off"))
        assertTrue(c.tocou("on"))
    }

    @Test fun bomDiaSemRedeAindaFala() {
        assertEquals("Bom dia! Sem previsão. Agenda X",
            FalaBoletim.frase(true, "Bom dia!", null, "Agenda X", "Sem previsão."))
        assertEquals("Sem previsão.", FalaBoletim.frase(false, "Bom dia!", null, null, "Sem previsão."))
        assertEquals("Bom dia! Sol.", FalaBoletim.frase(true, "Bom dia!", "Sol.", null, "Sem previsão."))
    }

    @Test(expected = IllegalStateException::class) fun backupGrandeDemaisFalhaAoExportar() {
        BackupCompleto.conferirTamanho("x".repeat(BackupCompleto.LIMITE_BYTES + 1))
    }

    @Test fun backupDoTamanhoCertoPassa() { BackupCompleto.conferirTamanho("x".repeat(1000)) }

    /** Varre o código: toda preferência do app vai no backup completo, menos as que ficam de fora de propósito. */
    @Test fun nenhumaPreferenciaEsquecidaNoBackupCompleto() {
        val pasta = File("src/main/kotlin/br/paulo/tvlauncher")
        assertTrue("rodar a partir da pasta app/", pasta.isDirectory)
        val nomes = HashSet<String>()
        val padroes = listOf(Regex("""getSharedPreferences\(\s*"([a-z_]+)""""), Regex("""const val (?:PREFS|ARQUIVO) = "([a-z_]+)""""))
        pasta.listFiles { f -> f.name.endsWith(".kt") }!!.forEach { f ->
            val t = f.readText()
            for (r in padroes) r.findAll(t).forEach { nomes.add(it.groupValues[1]) }
        }
        val cobertas = BackupConfig.ARQUIVOS + BackupCompleto.PREFS_EXTRAS + BackupCompleto.FORA_DE_PROPOSITO
        val esquecidas = nomes - cobertas.toSet()
        assertTrue("fora do backup completo: $esquecidas", esquecidas.isEmpty())
        assertFalse("confirmacoes fica de fora de propósito", "confirmacoes" in BackupCompleto.PREFS_EXTRAS)
    }
}
