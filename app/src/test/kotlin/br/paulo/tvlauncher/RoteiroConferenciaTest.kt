package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoteiroConferenciaTest {
    private val ids = listOf("home", "home_segurar", "filmes_voltar")

    @Test fun vazioQuandoNadaGravado() {
        assertEquals(RoteiroConferencia.Estado(10), RoteiroConferencia.ler(null, 10))
        assertEquals(RoteiroConferencia.Estado(10), RoteiroConferencia.ler("lixo{", 10))
    }

    @Test fun gravaELeDeVoltaNaMesmaVersao() {
        val e = RoteiroConferencia.responder(RoteiroConferencia.responder(RoteiroConferencia.Estado(10), "home", true), "home_segurar", false)
        val lido = RoteiroConferencia.ler(RoteiroConferencia.gravar(e), 10)
        assertEquals(mapOf("home" to true, "home_segurar" to false), lido.respostas)
        assertEquals(2, RoteiroConferencia.conferidos(lido, ids))
    }

    @Test fun outraVersaoZera() {
        val e = RoteiroConferencia.responder(RoteiroConferencia.Estado(10), "home", true)
        assertTrue(RoteiroConferencia.ler(RoteiroConferencia.gravar(e), 11).respostas.isEmpty())
    }

    @Test fun mudarDeIdeiaTrocaAResposta() {
        val e = RoteiroConferencia.responder(RoteiroConferencia.responder(RoteiroConferencia.Estado(1), "home", false), "home", true)
        assertEquals(true, e.respostas["home"])
    }

    @Test fun idQueSaiuDaListaNaoConta() {
        val e = RoteiroConferencia.responder(RoteiroConferencia.Estado(1), "velho", true)
        assertEquals(0, RoteiroConferencia.conferidos(e, ids))
    }

    @Test fun relatorioParaOAdb() {
        val e = RoteiroConferencia.responder(RoteiroConferencia.responder(RoteiroConferencia.Estado(7), "home", true), "filmes_voltar", false)
        val j = JSONObject(RoteiroConferencia.relatorio(e, ids))
        assertEquals(7L, j.getLong("versao")); assertEquals(3, j.getInt("total")); assertEquals(2, j.getInt("conferidos"))
        assertEquals("sim", j.getJSONObject("itens").getString("home"))
        assertEquals("falta", j.getJSONObject("itens").getString("home_segurar"))
        assertEquals("nao", j.getJSONObject("itens").getString("filmes_voltar"))
    }
}
