package br.paulo.tvlauncher

import android.os.Process
import android.util.Log
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Trabalho em segundo plano do launcher. Nasceu (12/11/2026) como um pool de threads no lugar das ~120
 * `Thread { }.start()` soltas; desde 21/11/2026 (refatoração C) roda em corrotinas.
 *
 * Para quem chama, nada muda: `Fundo.rodar("nome") { ... }`. O que as corrotinas dão a mais:
 * - um único escopo ([escopo], com SupervisorJob: a falha de uma tarefa não derruba as outras);
 * - [juntos]: várias buscas em paralelo esperando todas acabarem (antes: N Threads + join à mão);
 * - [Job] de volta, para cancelar o que não importa mais (ex.: a tela fechou).
 * O que continua igual: prioridade de segundo plano, o [nome] na thread enquanto roda (aparece no log e no ANR) e
 * erro comum (Exception) vira linha no log em vez de derrubar o processo da HOME.
 *
 * Tarefas que vivem para sempre (servidores no accept(), leitores de processo) continuam com thread própria e NÃO
 * vêm para cá: prenderiam uma thread do Dispatchers.IO para sempre.
 */
object Fundo {
    private const val TAG = "TvLauncher.Fundo"

    /** Escopo do processo. Dispatchers.IO: disco e rede bloqueiam, e é para isso que ele existe (até 64 threads). */
    val escopo = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** Roda [tarefa] fora da thread da tela. [nome] aparece no nome da thread enquanto ela roda (para o log e o ANR). */
    fun rodar(nome: String = "fundo", tarefa: () -> Unit): Job =
        escopo.launch(CoroutineName(nome)) { protegido(nome, tarefa) }

    /**
     * Roda as [tarefas] em paralelo e espera todas acabarem, no máximo [limiteMs] (passou disso, segue sem as
     * atrasadas, que terminam sozinhas). Bloqueia quem chama: use só de dentro de outra tarefa de fundo. Erro numa
     * tarefa não atrapalha as outras. Antes: N `Thread { }` + `join(ms)` à mão em cada lugar.
     */
    fun juntos(limiteMs: Long, vararg tarefas: () -> Unit) = runBlocking {
        val jobs = tarefas.map { t -> escopo.launch { runCatching(t) } }
        withTimeoutOrNull(limiteMs) { jobs.joinAll() }
        Unit
    }

    private fun protegido(nome: String, tarefa: () -> Unit) {
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
