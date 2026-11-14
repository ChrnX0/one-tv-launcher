package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Volta do login do Box para a TV (RetornoNuvem): só aceita /box com o número certo. */
class RetornoNuvemTest {
    private val n = "0123456789abcdef0123456789abcdef"

    @Test fun codigoComNumeroCerto() {
        val c = RetornoNuvem.lerPedido("GET /box?code=Ab%2B9z&state=$n HTTP/1.1\r", n)
        assertEquals("Ab+9z", (c as RetornoNuvem.Chegada.Codigo).codigo)
    }

    @Test fun recusaNumeroErradoCaminhoOuMetodo() {
        assertNull(RetornoNuvem.lerPedido("GET /box?code=X&state=outro HTTP/1.1", n))
        assertNull(RetornoNuvem.lerPedido("GET /favicon.ico HTTP/1.1", n))
        assertNull(RetornoNuvem.lerPedido("POST /box?code=X&state=$n HTTP/1.1", n))
        assertNull(RetornoNuvem.lerPedido("GET /box?state=$n HTTP/1.1", n))
        assertNull(RetornoNuvem.lerPedido("", n))
    }

    @Test fun pessoaRecusouNoBox() {
        assertTrue(RetornoNuvem.lerPedido("GET /box?error=access_denied&state=$n HTTP/1.1", n) is RetornoNuvem.Chegada.Erro)
    }

    @Test fun numeroNovoTem32Hex() {
        val a = RetornoNuvem.novoNumero()
        assertEquals(32, a.length)
        assertTrue(a.all { it in '0'..'9' || it in 'a'..'f' })
    }
}
