package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.Falhas
import br.paulo.tvlauncher.player.item
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

class FalhasTest {
    private val agora = 1_795_000_000_000L   // nov/2026
    private fun d(s: Falhas.Sinais) = Falhas.detectar(s)

    @Test fun nada_errado_nada_aparece() { assertTrue(d(Falhas.Sinais(agora, livreMb = 4000, horaDaInternet = agora + 1000, shellOk = true)).isEmpty()) }

    @Test fun canal_caiu_e_lista_venceu() {
        assertEquals(Falhas.Tipo.CANAL_CAIU, Falhas.doPlayer(404, false))
        assertEquals(Falhas.Tipo.CANAL_CAIU, Falhas.doPlayer(null, false))
        assertEquals(Falhas.Tipo.LISTA_VENCEU, Falhas.doPlayer(403, false))
        assertEquals(Falhas.Tipo.LISTA_VENCEU, Falhas.doPlayer(null, true))
        assertEquals(listOf(Falhas.Tipo.LISTA_VENCEU), d(Falhas.Sinais(agora, listasVencidas = 1)))
    }

    @Test fun relogio_errado() {
        assertEquals(listOf(Falhas.Tipo.RELOGIO_ERRADO), d(Falhas.Sinais(agora, horaDaInternet = agora + 10 * 60_000)))
        assertTrue(d(Falhas.Sinais(agora, horaDaInternet = agora + 60_000)).isEmpty())          // 1 min: normal
        assertEquals(listOf(Falhas.Tipo.RELOGIO_ERRADO), d(Falhas.Sinais(86_400_000L)))          // 1970
        assertEquals(listOf(Falhas.Tipo.RELOGIO_ERRADO), d(Falhas.Sinais(agora, certificadoForaDaData = true)))
        assertEquals(agora - agora % 1000, Falhas.dataHttp(java.text.SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", java.util.Locale.US)
            .apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.format(java.util.Date(agora))))
    }

    @Test fun disco_shell_tunel_backup() {
        assertEquals(listOf(Falhas.Tipo.DISCO_CHEIO), d(Falhas.Sinais(agora, livreMb = 120)))
        assertEquals(listOf(Falhas.Tipo.SHELL_PARADO), d(Falhas.Sinais(agora, shellOk = false)))
        assertTrue(d(Falhas.Sinais(agora, shellOk = null)).isEmpty())                          // nunca pareado: não é falha
        assertEquals(listOf(Falhas.Tipo.TUNEL_SEM_LOGIN), d(Falhas.Sinais(agora, tunelFaltaLogin = true)))
        assertEquals(listOf(Falhas.Tipo.BACKUP_FALHOU), d(Falhas.Sinais(agora, backupLigado = true, ultimoBackupOk = agora - 3 * 86_400_000L)))
        assertTrue(d(Falhas.Sinais(agora, backupLigado = true, ultimoBackupOk = agora - 3_600_000L)).isEmpty())
        // Acabou de ligar o backup: ainda não falhou.
        assertTrue(d(Falhas.Sinais(agora, backupLigado = true, backupDesde = agora - 3_600_000L)).isEmpty())
    }

    @Test fun prioridade() {
        val t = d(Falhas.Sinais(agora, livreMb = 1, shellOk = false, listasVencidas = 2, tunelFaltaLogin = true))
        assertEquals(listOf(Falhas.Tipo.LISTA_VENCEU, Falhas.Tipo.DISCO_CHEIO, Falhas.Tipo.SHELL_PARADO, Falhas.Tipo.TUNEL_SEM_LOGIN), t)
    }

    @Test fun cada_falha_tem_conserto() { Falhas.Tipo.entries.forEach { Falhas.conserto(it) } ; assertEquals(7, Falhas.Tipo.entries.size) }

    @Test fun detecta_codigo_cru() {
        assertFalse(Falhas.semCodigo("Erro HTTP 403"))
        assertFalse(Falhas.semCodigo("SocketTimeoutException"))
        assertFalse(Falhas.semCodigo("falhou (errno 28)"))
        assertTrue(Falhas.semCodigo("A conta da lista venceu. Renove com quem vende a lista."))
    }

    /** Cada estado tem frase, causa e botão em todas as línguas, sem código cru. */
    @Test fun textos_de_todas_as_linguas() {
        val res = listOf("src/main/res", "app/src/main/res").map { File(it) }.first { it.exists() }
        val pastas = res.listFiles()!!.filter { it.name == "values" || it.name.startsWith("values-") && File(it, "strings_elevacao.xml").exists() }
        assertTrue(pastas.size >= 2)
        for (p in pastas) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(p, "strings_elevacao.xml"))
            val nomes = (0 until doc.getElementsByTagName("string").length).associate {
                val e = doc.getElementsByTagName("string").item(it) as Element; e.getAttribute("name") to e.textContent
            }
            for (t in Falhas.Tipo.entries) for (parte in listOf("frase", "causa", "botao")) {
                val k = "elv_f_${t.name.lowercase()}_$parte"
                val v = nomes[k]
                assertTrue("${p.name}: falta $k", !v.isNullOrBlank())
                assertTrue("${p.name}: $k tem código cru: $v", Falhas.semCodigo(v!!))
            }
        }
    }
}
