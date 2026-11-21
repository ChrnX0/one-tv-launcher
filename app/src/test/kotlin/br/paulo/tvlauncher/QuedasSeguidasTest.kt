package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.QuedasSeguidas
import br.paulo.tvlauncher.observer.QuedasSeguidas.Estado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Modo de segurança da HOME (degrau 2, 16/11/2026). */
class QuedasSeguidasTest {
    private val min = 60_000L
    private val t0 = 1_800_000_000_000L

    private fun cair(e: Estado, t: Long, estavel: Boolean = false) = QuedasSeguidas.aoCair(e, 100, t, estavel)

    @Test fun tresQuedasSeguidasLigam() {
        var e = Estado()
        e = cair(e, t0); assertFalse(QuedasSeguidas.ativo(e, 100))
        e = cair(e, t0 + min); assertFalse(QuedasSeguidas.ativo(e, 100))
        e = cair(e, t0 + 2 * min)
        assertTrue(QuedasSeguidas.ativo(e, 100))
        assertTrue(e.quedas.isEmpty())
    }

    @Test fun quedasEspalhadasNaoLigam() {
        var e = Estado()
        e = cair(e, t0)
        e = cair(e, t0 + 10 * min)
        e = cair(e, t0 + 20 * min)   // a 1ª saiu da janela de 15 min
        assertFalse(QuedasSeguidas.ativo(e, 100))
        assertEquals(2, e.quedas.size)
    }

    @Test fun homeEstavelZeraASequencia() {
        var e = cair(cair(Estado(), t0), t0 + min)
        e = cair(e, t0 + 2 * min, estavel = true)   // caiu outra tela, com a HOME de pé: não é laço
        assertFalse(QuedasSeguidas.ativo(e, 100))
        assertTrue(e.quedas.isEmpty())
        e = cair(cair(Estado(), t0), t0 + min)
        assertTrue(QuedasSeguidas.aoEstabilizar(e).quedas.isEmpty())
    }

    @Test fun versaoNovaSaiDoModo() {
        val e = Estado(seguroVersao = 100)
        assertTrue(QuedasSeguidas.ativo(e, 100))
        assertFalse(QuedasSeguidas.ativo(e, 101))
        assertFalse(QuedasSeguidas.ativo(QuedasSeguidas.sair(), 100))
    }

    @Test fun estabilizarNaoDesligaOModo() {
        val e = Estado(seguroVersao = 100)
        assertEquals(e, QuedasSeguidas.aoEstabilizar(e))
    }

    @Test fun arquivoIdaEVolta() {
        val e = Estado(listOf(t0, t0 + min), 100)
        assertEquals(e, QuedasSeguidas.ler(QuedasSeguidas.texto(e)))
        assertEquals(Estado(), QuedasSeguidas.ler(null))
        assertEquals(Estado(), QuedasSeguidas.ler("lixo"))
        assertEquals(Estado(listOf(5L), 0L), QuedasSeguidas.ler("seguro=x;q=5,y"))
    }
}
