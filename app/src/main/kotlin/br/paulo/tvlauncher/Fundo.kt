package br.paulo.tvlauncher

import android.os.Process
import android.util.Log
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Fila de trabalho em segundo plano do launcher (12/11/2026), no lugar das ~120 `Thread { }.start()` soltas.
 *
 * O que muda para quem chama: nada além do nome (`Fundo.rodar { ... }` em vez de `Thread { ... }.start()`).
 * O que ganha:
 * - threads reaproveitadas (criar uma thread custa ~1 ms e 1 MB de pilha reservada; a HOME criava várias por volta);
 * - prioridade de segundo plano: o trabalho de disco e rede não disputa a CPU com o desenho da tela;
 * - um erro numa tarefa vira uma linha no log em vez de derrubar o processo inteiro (exceção não tratada numa Thread
 *   solta mata o app, e no processo da HOME isso é a tela piscando).
 *
 * Sem fila de espera de propósito (SynchronousQueue): até [MAX] tarefas rodam juntas, como antes; passou disso, a
 * tarefa ganha uma thread própria na hora (nunca espera atrás de um download lento). Tarefas que vivem para sempre
 * (servidores no accept(), leitores de processo) continuam com thread própria e NÃO devem vir para cá.
 */
object Fundo {
    private const val TAG = "TvLauncher.Fundo"
    private const val MAX = 8
    private val contador = AtomicInteger()

    private val pool = ThreadPoolExecutor(0, MAX, 30, TimeUnit.SECONDS, SynchronousQueue(),
        { r -> Thread(r, "fundo-${contador.incrementAndGet()}").apply { isDaemon = true } },
        { r, _ -> Thread(r, "fundo-extra").apply { isDaemon = true }.start() })

    /** Roda [tarefa] fora da thread da tela. [nome] aparece no nome da thread enquanto ela roda (para o log e o ANR). */
    fun rodar(nome: String = "fundo", tarefa: () -> Unit) {
        pool.execute {
            val t = Thread.currentThread()
            val antes = t.name
            t.name = nome
            try {
                runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND) }
                tarefa()
            } catch (e: Exception) {
                Log.w(TAG, "$nome: ${e.javaClass.simpleName}: ${e.message?.take(120)}")
            } finally {
                t.name = antes
            }
        }
    }
}
