package br.paulo.tvlauncher

import br.paulo.tvlauncher.mcp.McpPoder
import br.paulo.tvlauncher.mcp.McpPoder2
import br.paulo.tvlauncher.mcp.McpProtocolo
import br.paulo.tvlauncher.player.AddonsProtocolo
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Versão da Play enxuta (18/11/2026): o que sai da Play e a garantia de que a Sala não muda. */
class PlayPolicyTest {
    private val k = File("src/main/kotlin/br/paulo/tvlauncher")
    private fun fonte(nome: String) = CodeSource.file(nome).readText()

    @Test fun naPlayAsFerramentasTiradasSomem() {
        for (n in PlayPolicy.PLAY_REMOVED_MCP_TOOLS) {
            assertFalse(n, PlayPolicy.mcpToolAllowed(n, play = true))
            assertTrue(n, PlayPolicy.mcpToolAllowed(n, play = false))   // Sala e site: como sempre
        }
        assertTrue(PlayPolicy.PLAY_REMOVED_MCP_TOOLS.containsAll(PlayPolicy.AI_ACCESSIBILITY_TOOLS))
        assertTrue(PlayPolicy.mcpToolAllowed("controle_remoto", play = true))
        assertTrue(PlayPolicy.mcpToolAllowed("ver_tela", play = true))
    }

    @Test fun naPlayAIaNaoUsaAcessibilidade() {
        assertFalse(PlayPolicy.aiMayUseAccessibility(play = true))
        assertTrue(PlayPolicy.aiMayUseAccessibility(play = false))
    }

    @Test fun poderesSoForaDaPlay() {
        for (p in PlayPolicy.Power.values()) {
            assertFalse(p.name, PlayPolicy.allowed(p, play = true))
            assertTrue(p.name, PlayPolicy.allowed(p, play = false))
        }
    }

    @Test fun asFerramentasTiradasExistemDeVerdade() {
        // Se alguém renomear a ferramenta, o filtro deixaria de pegar: o nome tem que continuar numa lista do MCP.
        val nomes = (McpPoder.LISTA + McpPoder2.LISTA).map { it.optString("name") }.toSet()
        // executar_shell é montada em McpFerramentas (não nas listas): confere pelo código-fonte.
        assertTrue(fonte("McpFerramentas.kt").contains("ferramenta(\"executar_shell\""))
        assertTrue(nomes.containsAll(PlayPolicy.PLAY_REMOVED_MCP_TOOLS - "executar_shell"))
    }

    @Test fun instrucoesDaPlayNaoCitamOQueSaiu() {
        for (ok in listOf(true, false)) {
            val play = McpProtocolo.instrucoes(ok, play = true)
            for (n in PlayPolicy.PLAY_REMOVED_MCP_TOOLS) assertFalse(n, play.contains(n))
            val sala = McpProtocolo.instrucoes(ok, play = false)
            assertTrue(sala.contains("tocar_elemento") && sala.contains("instalar_apk"))
        }
    }

    @Test fun cadaPortaConsultaARegra() {
        // Onde cada pedido chegaria ao poder: todas consultam a PlayPolicy (a lógica de cada uma é Android, sem teste JVM).
        assertTrue(fonte("McpFerramentas.kt").contains("if (!PlayPolicy.mcpToolAllowed(nome, BuildConfig.PLAY)) throw"))
        assertTrue(fonte("McpPoder.kt").contains("PlayPolicy.aiMayUseAccessibility(BuildConfig.PLAY) && DigitarNaTv.escrever"))
        assertTrue(fonte("PainelControle.kt").contains("!pelaIa || PlayPolicy.aiMayUseAccessibility(BuildConfig.PLAY)"))
        val usos = mapOf(
            PlayPolicy.Power.INSTALL_APK to listOf("PainelApps.kt"),
            PlayPolicy.Power.FREEZE_APPS to listOf("Congelar.kt"),
            PlayPolicy.Power.LEND_POWERS to listOf("ShizukuEmbutido.kt", "DonoEmprestado.kt"),
            PlayPolicy.Power.READ_PAIRING_CODE to listOf("PareamentoNaTv.kt"),
            PlayPolicy.Power.LIVE_TV_LISTS to listOf("PlayerArmazem.kt", "ConfigAccountTab.kt", "PainelWeb.kt"),
            PlayPolicy.Power.ADDON_STREAMS to listOf("AddonsTv.kt", "AddonsProtocolo.kt"),
        )
        assertTrue(usos.keys == PlayPolicy.Power.values().toSet())
        for ((p, arquivos) in usos) for (a in arquivos) assertTrue("$a sem $p", fonte(a).contains("PlayPolicy.Power.${p.name}"))
    }

    @Test fun manifestoDaPlayTiraOsServidoresDeEmprestimo() {
        val m = File("src/play/AndroidManifest.xml").readText()
        for (c in listOf(".ProvedorShizuku", ".PedidoShizukuActivity", ".ProvedorDhizuku", ".PedidoDhizukuActivity", ".ServicoUsuarioDhizuku"))
            assertTrue(c, Regex("""android:name="${Regex.escape(c)}"\s+tools:node="remove"""").containsMatchIn(m))
    }

    @Test fun addonsNaPlaySoCatalogoFichaELegenda() {
        assertTrue("stream" !in AddonsProtocolo.recursosUteis(play = true))
        assertTrue(AddonsProtocolo.recursosUteis(play = true).containsAll(listOf("catalog", "meta", "subtitles")))
        assertTrue("stream" in AddonsProtocolo.recursosUteis(play = false))
    }

    @Test fun painelEscondeATvAoVivoNaPlay() {
        val p = File("src/main/assets/painel")
        assertTrue(File(p, "app.js").readText().contains("r.existe === false"))
        assertTrue(File(p, "primeira.js").readText().contains("pularTvSeNaoExiste"))
    }
}
