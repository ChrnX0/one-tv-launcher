package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.CanalTv
import br.paulo.tvlauncher.privilege.Confirmacoes
import br.paulo.tvlauncher.privilege.Confirmacoes.Modo
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pedido de fora com o protetor aberto sempre pergunta na TV (dono, 15/11/2026). */
class PedidoProtetorTest {
    @Test fun deForaComProtetorPergunta() {
        for (origem in listOf("mcp", "painel", "link", "adb", "cena/ia"))
            assertEquals(origem, Modo.PERGUNTAR, Confirmacoes.comProtetor(Modo.PERMITIR, true, origem, abreAlgo = true))
    }

    @Test fun semProtetorOuDaPropriaTvSegueAConfiguracao() {
        assertEquals(Modo.PERMITIR, Confirmacoes.comProtetor(Modo.PERMITIR, false, "mcp", true))
        for (origem in Confirmacoes.ORIGENS_NA_TV) assertEquals(origem, Modo.PERMITIR, Confirmacoes.comProtetor(Modo.PERMITIR, true, origem, true))
        assertEquals(CanalTv.ORIGEM_VOZ, "voz")   // a voz conta como "na TV"
    }

    @Test fun bloqueadoContinuaBloqueadoESemAbrirNadaNaoMuda() {
        assertEquals(Modo.BLOQUEAR, Confirmacoes.comProtetor(Modo.BLOQUEAR, true, "mcp", true))
        assertEquals(Modo.PERMITIR, Confirmacoes.comProtetor(Modo.PERMITIR, true, "mcp", abreAlgo = false))
    }
}
