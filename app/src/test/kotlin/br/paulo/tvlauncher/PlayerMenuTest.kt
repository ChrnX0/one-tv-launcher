package br.paulo.tvlauncher

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
}
