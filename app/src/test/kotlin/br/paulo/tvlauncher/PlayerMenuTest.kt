package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.PlayerMenu
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A regra do menu do ▼ (20/11/2026): o ✓ só diz "esta é a escolhida"; chave e "abre outra lista" nunca levam ✓. */
class PlayerMenuTest {
    private val nada: () -> Unit = {}

    @Test fun checkSoNasEscolhasValendo() {
        val itens = listOf(
            PlayerMenu.escolha("Desligada", false, nada),
            PlayerMenu.escolha("Português", true, nada),
            PlayerMenu.Item("Voz mais clara", PlayerMenu.Tipo.CHAVE, "Ligado", escolhido = true, acao = nada),
            PlayerMenu.abre("Ajustes da legenda", "Normal", nada),
            PlayerMenu.acao("Buscar legenda na internet", acao = nada),
        )
        assertEquals(listOf(1), PlayerMenu.marcadas(itens))
    }

    @Test fun soAChaveReabreALista() {
        assertTrue(PlayerMenu.reabre(PlayerMenu.Item("Voz", PlayerMenu.Tipo.CHAVE, "Ligado", acao = nada)))
        assertFalse(PlayerMenu.reabre(PlayerMenu.escolha("Português", false, nada)))
        assertFalse(PlayerMenu.reabre(PlayerMenu.abre("Legenda", "Desligada", nada)))
        assertFalse(PlayerMenu.reabre(PlayerMenu.acao("Assistir legendado", acao = nada)))
    }

    @Test fun listaSemEscolhaNaoTemCheck() {
        val itens = listOf(PlayerMenu.abre("Áudio", "Português", nada), PlayerMenu.abre("Legenda", "Desligada", nada))
        assertTrue(PlayerMenu.marcadas(itens).isEmpty())
    }

    @Test fun valorADireita() {
        assertEquals("Desligada ›", PlayerMenu.valorNaLinha(PlayerMenu.abre("Legenda", "Desligada", nada)))
        assertEquals("›", PlayerMenu.valorNaLinha(PlayerMenu.abre("Mais opções", null, nada)))
        assertEquals("Ligado", PlayerMenu.valorNaLinha(PlayerMenu.Item("Voz", PlayerMenu.Tipo.CHAVE, "Ligado", acao = nada)))
        assertEquals(null, PlayerMenu.valorNaLinha(PlayerMenu.escolha("Português", true, nada)))
        assertEquals(null, PlayerMenu.valorNaLinha(PlayerMenu.acao("Assistir legendado", "Continua deste ponto", nada)))
    }

    @Test fun valorComMaiuscula() {
        assertEquals("Português ›", PlayerMenu.valorNaLinha(PlayerMenu.abre("Áudio", "português", nada)))
        assertEquals("Alto contraste", PlayerMenu.valorNaLinha(PlayerMenu.Item("Estilo", PlayerMenu.Tipo.CHAVE, "alto contraste", acao = nada)))
    }

    @Test fun voltarCaiNoItemDeOndeSaiu() {
        val itens = listOf(PlayerMenu.abre("Áudio", "Português", nada), PlayerMenu.abre("Legenda", "Desligada", nada))
        PlayerMenu.escolheu("Menu", itens[1])
        assertEquals(null, PlayerMenu.focoAoAbrir("Legenda", itens, null))   // outra lista não usa o retorno
        assertEquals(1, PlayerMenu.focoAoAbrir("Menu", itens, null))
        assertEquals(null, PlayerMenu.focoAoAbrir("Menu", itens, null))      // usado uma vez só
        PlayerMenu.escolheu("Menu", itens[1]); PlayerMenu.escolheu("Legenda", PlayerMenu.escolha("Desligada", true, nada))
        assertEquals(null, PlayerMenu.focoAoAbrir("Menu", itens, null))      // escolha que fecha esquece o caminho
        PlayerMenu.escolheu("Menu", itens[1])
        assertEquals(0, PlayerMenu.focoAoAbrir("Menu", itens, 0))            // foco pedido vence
    }
}
