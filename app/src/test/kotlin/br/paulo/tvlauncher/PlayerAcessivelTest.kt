package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.PlayerAcessivel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAcessivelTest {
    private val a = PlayerAcessivel

    @Test fun legendaPadraoIgualAntes() {
        val l = a.legenda(fundoPreto = false, amarela = false)
        assertEquals(a.BRANCO, l.texto); assertEquals(a.FUNDO_LEVE, l.fundo); assertTrue(l.sombra)
    }

    @Test fun legendaFundoPretoEAmarela() {
        val l = a.legenda(fundoPreto = true, amarela = true)
        assertEquals(a.AMARELO, l.texto); assertEquals(a.FUNDO_PRETO, l.fundo); assertFalse(l.sombra)
        assertEquals(0xFF, a.FUNDO_PRETO ushr 24)   // opaco de verdade
    }

    @Test fun faixasDaVozEmOrdemEAteOFim() {
        val f = a.FAIXAS_VOZ
        assertTrue(f.zipWithNext().all { (x, y) -> x.ateHz < y.ateHz })
        assertEquals(20_000f, f.last().ateHz)
        // A faixa das consoantes (1 a 4 kHz) é a que mais sobe; o grave desce.
        assertEquals(f.maxOf { it.ganhoDb }, f.first { it.ateHz == 4_000f }.ganhoDb)
        assertTrue(f.first().ganhoDb < 0)
    }

    @Test fun compressorAproximaCanalBaixoDoAlto() {
        val alto = -12f; val baixo = -22f                  // 10 dB de diferença na entrada
        val dif = a.saidaComprimida(alto) - a.saidaComprimida(baixo)
        assertTrue("diferença $dif", dif < 4f)             // 10 dB viram 2,5 dB
        assertTrue(a.saidaComprimida(baixo) > baixo + 6f)  // o baixo sobe
        assertEquals(alto, a.saidaComprimida(alto), 1f)     // o normal fica onde estava
    }

    @Test fun compressorNuncaPassaDoLimite() {
        for (db in -60..0) assertTrue("$db", a.saidaComprimida(db.toFloat()) <= a.LIMITE_DB)
        assertEquals(-50f + 14f, a.saidaComprimida(-50f), 0.001f)   // abaixo do limiar só ganha
    }

    @Test fun semChaveSemEfeito() {
        assertFalse(a.precisaEfeito(false, false))
        assertTrue(a.precisaEfeito(true, false)); assertTrue(a.precisaEfeito(false, true))
    }

    @Test fun blocoSimplesTiraFiltroGuiaEAssistidos() {
        val acoes = listOf("buscar", "filtro", "fav", "rec", "guia")
        assertEquals(listOf("buscar", "fav", "rec"), a.blocoSimples(acoes, { it }, setOf("buscar", "fav", "rec")))
    }

    @Test fun entradaSimplesPrefereOQueTemConteudo() {
        assertEquals("fav", a.entradaSimples(listOf("fav" to 3, "rec" to 5), "todos"))
        assertEquals("rec", a.entradaSimples(listOf("fav" to 0, "rec" to 5), "todos"))
        assertEquals("todos", a.entradaSimples(listOf("fav" to 0, "rec" to 0), "todos"))
    }

    @Test fun escala() {
        assertEquals(1f, a.escala(false)); assertEquals(1.2f, a.escala(true))
    }
}
