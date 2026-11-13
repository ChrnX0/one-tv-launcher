package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Auditoria 10/11/2026 (privilegios-5): a versão gravada pelo RegistroErros é "nome (código)" e precisa ser lida. */
class ErrosFiltradosTest {
    @Test fun versaoRealDoRegistro() {
        val t = RegistroErros.montarTexto(0L, "0.2 (12345)", "Android 14 · X", "main", RuntimeException("x"))
        val f = ErrosFiltrados.filtrar(t)
        assertEquals("0.2 (12345)", f.versao)
        assertEquals(12345L, ErrosFiltrados.codigo(f.versao))
    }

    @Test fun contaSoAMesmaBuild() {
        fun erro(v: String) = ErrosFiltrados.filtrar(RegistroErros.montarTexto(0L, v, "A", "main", RuntimeException("x")))
        val todos = listOf(erro("0.2 (100)"), erro("0.2 (100)"), erro("0.2 (99)"), erro("?"))
        assertEquals(2, ErrosFiltrados.nestaVersao(todos, 100L))
        assertNull(ErrosFiltrados.codigo("?"))
    }
}
