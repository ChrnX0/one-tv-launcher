package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/** Perfis de verdade (ideia 11): nomes por perfil, leitura com fallback para o compartilhado e o backup. */
class PerfilDadosTest {
    @Test fun sanitizaOUsuario() {
        assertEquals("ana", PerfilDados.sanitizar("Ana@gmail.com"))
        assertEquals("ana.maria_tv", PerfilDados.sanitizar("Ana.Maria+tv@gmail.com"))
        assertEquals("jo_o", PerfilDados.sanitizar("joão@x.com"))
        assertEquals("__", PerfilDados.sanitizar("..@x.com"))
        assertEquals("_etc_passwd", PerfilDados.sanitizar("/etc/passwd"))
        assertEquals("", PerfilDados.sanitizar(null))
        assertEquals("", PerfilDados.sanitizar("@x.com"))
        assertEquals(PerfilDados.MAX_CHAVE, PerfilDados.sanitizar("a".repeat(100) + "@x.com").length)
    }

    @Test fun semContaOuVisitaEhCompartilhado() {
        assertEquals("", PerfilDados.chaveDoPerfil(null, visita = false))
        assertEquals("", PerfilDados.chaveDoPerfil("ana@gmail.com", visita = true))
        assertEquals("ana", PerfilDados.chaveDoPerfil("ana@gmail.com", visita = false))
    }

    @Test fun lerChaveDoArquivo() {
        assertEquals("ana", PerfilDados.lerChave("ana\n"))
        assertEquals("", PerfilDados.lerChave(null))
        assertEquals("", PerfilDados.lerChave(""))
        assertEquals("", PerfilDados.lerChave("../x"))
        assertEquals("", PerfilDados.lerChave("ANA"))   // gravado sempre em minúsculas: diferente disso é arquivo mexido
    }

    @Test fun nomesPorPerfil() {
        assertEquals("favoritos@ana.json", PerfilDados.nomeArquivo("favoritos.json", "ana"))
        assertEquals("favoritos.json", PerfilDados.nomeArquivo("favoritos.json", ""))
        assertEquals("ordem@ana", PerfilDados.chavePrefs("ordem", "ana"))
        assertEquals("ordem", PerfilDados.chavePrefs("ordem", ""))
        assertEquals("series_ultimos@ana.json", PerfilDados.nomeEscrita("series_ultimos.json", "ana"))
    }

    @Test fun leituraComFallback() {
        val existem = mutableSetOf("favoritos.json")
        // Perfil sem arquivo próprio: lê o compartilhado (nada some na TV com uma conta só).
        assertEquals("favoritos.json", PerfilDados.nomeLeitura("favoritos.json", "ana") { it in existem })
        // Depois da 1ª gravação do perfil, lê o dele.
        existem += "favoritos@ana.json"
        assertEquals("favoritos@ana.json", PerfilDados.nomeLeitura("favoritos.json", "ana") { it in existem })
        // Outro perfil continua no compartilhado; sem perfil, sempre o compartilhado.
        assertEquals("favoritos.json", PerfilDados.nomeLeitura("favoritos.json", "beto") { it in existem })
        assertEquals("favoritos.json", PerfilDados.nomeLeitura("favoritos.json", "") { true })
        // Mesma regra para a ordem dos apps nas preferências; ordem vazia gravada no perfil vale (= automática).
        assertEquals("ordem", PerfilDados.chaveLeitura("ordem", "ana") { it == "ordem" })
        assertEquals("ordem@ana", PerfilDados.chaveLeitura("ordem", "ana") { true })
    }

    @Test fun copiasDePerfilNoBackup() {
        assertTrue(PerfilDados.ehCopiaDePerfil("favoritos@ana.json"))
        assertTrue(PerfilDados.ehCopiaDePerfil("posicoes@ana.maria_tv.json"))
        assertTrue(PerfilDados.ehCopiaDePerfil("interesses_series@beto.json"))
        assertFalse(PerfilDados.ehCopiaDePerfil("favoritos.json"))
        assertFalse(PerfilDados.ehCopiaDePerfil("contas@ana.json"))           // só os arquivos que são por perfil
        assertFalse(PerfilDados.ehCopiaDePerfil("parental@ana.json"))
        assertFalse(PerfilDados.ehCopiaDePerfil("favoritos@.json"))
        assertFalse(PerfilDados.ehCopiaDePerfil("favoritos@../x.json"))
        assertFalse(PerfilDados.ehCopiaDePerfil("favoritos@...json"))
        assertFalse(PerfilDados.ehCopiaDePerfil("favoritos@a@b.json"))
        assertFalse(PerfilDados.ehCopiaDePerfil("favoritos@ANA.json"))
        assertFalse(PerfilDados.ehCopiaDePerfil("favoritos@ana.json.tmp"))
    }

    /** O ensaio do backup noturno aceita as cópias por perfil com a mesma regra do importar (BackupCompleto.caminhoPermitido). */
    @Test fun ensaioAceitaCopiasDePerfil() {
        val b64 = Base64.getEncoder()
        fun permitido(c: String) = c == "tunel/tailscaled.state" ||
            (c.startsWith("player/") && (c.removePrefix("player/") == "favoritos.json" || PerfilDados.ehCopiaDePerfil(c.removePrefix("player/"))))
        fun backup(arquivo: String) = JSONObject().put("versao", 3).put("pacote", "p")
            .put("preferencias", JSONObject().put("launcher", JSONObject()))
            .put("arquivos", JSONObject().put(arquivo, b64.encodeToString("[]".toByteArray()))).toString()
        assertTrue(EnsaioRestauracao.conferir(backup("player/favoritos@ana.json"), "p", 1 shl 20, ::permitido).ok)
        assertFalse(EnsaioRestauracao.conferir(backup("player/favoritos@../x.json"), "p", 1 shl 20, ::permitido).ok)
        // A versão com a lista fixa continua igual.
        assertFalse(EnsaioRestauracao.conferir(backup("player/favoritos@ana.json"), "p", setOf("player/favoritos.json"), 1 shl 20).ok)
    }
}
