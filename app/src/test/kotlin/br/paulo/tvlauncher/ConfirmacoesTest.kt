package br.paulo.tvlauncher

import br.paulo.tvlauncher.Confirmacoes.Acao
import br.paulo.tvlauncher.Confirmacoes.Modo
import br.paulo.tvlauncher.Confirmacoes.Regras
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConfirmacoesTest {
    @Test fun instalacaoNovaUsaPadroesProtegidos() {
        val r = Confirmacoes.iniciais(instalacaoNova = true)
        assertFalse(r.liberarTudo)
        assertEquals(Modo.BLOQUEAR, Confirmacoes.modo(r, Acao.MCP_SHELL))
        assertEquals(Modo.PERGUNTAR, Confirmacoes.modo(r, Acao.INSTALAR))
        assertEquals(Modo.PERMITIR, Confirmacoes.modo(r, Acao.ATUALIZACAO))
    }

    @Test fun tvQueJaTinhaOLauncherComecaLiberada() {
        val r = Confirmacoes.iniciais(instalacaoNova = false)
        for (a in Acao.entries) assertEquals(Modo.PERMITIR, Confirmacoes.modo(r, a))
    }

    @Test fun liberarTudoVenceAsLinhas() {
        val r = Regras(true, mapOf(Acao.MCP_SHELL to Modo.BLOQUEAR))
        assertEquals(Modo.PERMITIR, Confirmacoes.modo(r, Acao.MCP_SHELL))
    }

    @Test fun modoQueALinhaNaoAceitaCaiNoPadrao() {
        val r = Regras(false, mapOf(Acao.ATUALIZACAO to Modo.PERGUNTAR))
        assertEquals(Modo.PERMITIR, Confirmacoes.modo(r, Acao.ATUALIZACAO))
    }

    @Test fun pinSoParaLiberarMais() {
        val base = Regras()
        assertTrue(Confirmacoes.pedePin(base, base.copy(liberarTudo = true)))
        assertFalse(Confirmacoes.pedePin(base.copy(liberarTudo = true), base))
        assertTrue(Confirmacoes.pedePin(base, Regras(false, mapOf(Acao.INSTALAR to Modo.PERMITIR))))
        assertFalse(Confirmacoes.pedePin(base, Regras(false, mapOf(Acao.INSTALAR to Modo.BLOQUEAR))))
        assertTrue(Confirmacoes.pedePin(base, Regras(false, mapOf(Acao.MCP_SHELL to Modo.PERGUNTAR))))
    }

    @Test fun proximoGiraSoNosModosDaLinha() {
        assertEquals(Modo.PERMITIR, Confirmacoes.proximo(Acao.INSTALAR, Modo.PERGUNTAR))
        assertEquals(Modo.BLOQUEAR, Confirmacoes.proximo(Acao.INSTALAR, Modo.PERMITIR))
        assertEquals(Modo.PERGUNTAR, Confirmacoes.proximo(Acao.INSTALAR, Modo.BLOQUEAR))
        assertEquals(Modo.BLOQUEAR, Confirmacoes.proximo(Acao.ATUALIZACAO, Modo.PERMITIR))
        assertEquals(Modo.PERMITIR, Confirmacoes.proximo(Acao.ATUALIZACAO, Modo.BLOQUEAR))
    }

    @Test fun jsonIdaEVolta() {
        val r = Regras(false, mapOf(Acao.INSTALAR to Modo.PERMITIR, Acao.MCP_SHELL to Modo.PERGUNTAR))
        assertEquals(r, Confirmacoes.deJson(Confirmacoes.paraJson(r)))
    }

    @Test fun jsonRuimOuDesconhecido() {
        assertNull(Confirmacoes.deJson(null))
        assertNull(Confirmacoes.deJson("{lixo"))
        val r = Confirmacoes.deJson("""{"liberarTudo":false,"modos":{"xyz":"permitir","instalar":"talvez","atualizacao":"perguntar","remover":"bloquear"}}""")!!
        assertEquals(mapOf(Acao.REMOVER to Modo.BLOQUEAR), r.modos)
    }
}
