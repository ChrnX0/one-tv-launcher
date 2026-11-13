package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Auditoria 10/11/2026 (privilegios-1): comando cujo prazo venceu na fila NÃO pode rodar depois. */
class FilaComPrazoTest {
    @Test fun respondeDentroDoPrazo() {
        val fila = Executors.newSingleThreadExecutor()
        assertEquals("ok", FilaComPrazo(fila).rodar(2_000) { "ok" })
        fila.shutdown()
    }

    @Test fun prazoVencidoNaFilaDescartaOComando() {
        val fila = Executors.newSingleThreadExecutor()
        val solta = CountDownLatch(1)
        fila.execute { solta.await() }   // a fila presa (ex.: as esperas da reconexão do guardião)
        val rodou = AtomicBoolean(false)
        var falha: Exception? = null
        assertNull(FilaComPrazo(fila).rodar(100, { falha = it }) { rodou.set(true); "tecla" })
        assertTrue(falha is java.util.concurrent.TimeoutException)
        solta.countDown()                // a fila anda
        fila.shutdown(); assertTrue(fila.awaitTermination(2, TimeUnit.SECONDS))
        assertFalse("o comando rodou depois de o chamador desistir", rodou.get())
    }

    @Test fun tarefaQueComecaDepoisDoPrazoNaoRoda() {
        // Corrida: a fila libera ANTES do get desistir, mas o relógio já passou do limite (o cancel não pega).
        // Relógio falso: a 1ª leitura (o pedido) é 0; quando a tarefa começa, já se passaram 1000 s.
        val leituras = AtomicLong(0)
        val fila = Executors.newSingleThreadExecutor()
        val rodou = AtomicBoolean(false)
        assertNull(FilaComPrazo(fila) { if (leituras.getAndIncrement() == 0L) 0L else 1_000_000L }.rodar(5_000) { rodou.set(true); "x" })
        fila.shutdown(); fila.awaitTermination(2, TimeUnit.SECONDS)
        assertFalse(rodou.get())
    }

    @Test fun erroDaTarefaViraNull() {
        val fila = Executors.newSingleThreadExecutor()
        var falha: Exception? = null
        assertNull(FilaComPrazo(fila).rodar<String>(2_000, { falha = it }) { throw IllegalStateException("x") })
        assertTrue(falha is java.util.concurrent.ExecutionException)
        fila.shutdown()
    }

    @Test fun naoComecaSeNaoSobraTempoParaTerminar() {
        // Revisão da auditoria: a tarefa sai da fila faltando 5 s de um prazo de 10 s, mas precisa de 8 s: não roda.
        val leituras = AtomicLong(0)
        val fila = Executors.newSingleThreadExecutor()
        val rodou = AtomicBoolean(false)
        assertNull(FilaComPrazo(fila) { if (leituras.getAndIncrement() == 0L) 0L else 5_000L }.rodar(10_000, minimoMs = 8_000) { rodou.set(true); "x" })
        fila.shutdown(); fila.awaitTermination(2, TimeUnit.SECONDS)
        assertFalse(rodou.get())
    }
}
