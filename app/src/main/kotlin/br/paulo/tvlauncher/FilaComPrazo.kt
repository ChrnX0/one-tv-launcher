package br.paulo.tvlauncher

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit

/**
 * Pôr uma tarefa numa fila de UMA thread e esperar no máximo [prazoMs] pela resposta (auditoria de 10/11/2026).
 *
 * Por que existe: o `GuardiaoShizuku.comando` fazia `fila.submit(...).get(prazo)` e, quando o prazo acabava, só
 * desistia de esperar. A tarefa continuava na fila e rodava MINUTOS depois (a fila pode ficar presa nas esperas de
 * 10+30+60+120 s da reconexão): uma tecla do painel, um `executar_shell` do MCP que o chamador já dava como falho.
 * Agora quem desiste também descarta: o comando roda dentro do prazo ou não roda.
 *
 * Pura (sem Android): o executor e o relógio vêm de fora, para o teste JVM (FilaComPrazoTest).
 */
class FilaComPrazo(
    private val fila: ExecutorService,
    private val relogio: () -> Long = { System.nanoTime() / 1_000_000 },   // ms que só andam para a frente
) {
    /**
     * Roda [tarefa] na fila e devolve o resultado, ou null se o prazo acabou, se deu erro ou se a espera foi
     * interrompida. [aoFalhar] recebe a exceção (para anotar no log). [minimoMs]: a tarefa só começa se ainda sobrar
     * pelo menos esse tempo do prazo (uma tarefa que precisa de 20 s não adianta começar faltando 1 s: o chamador
     * desistiria no meio e ela rodaria assim mesmo).
     */
    fun <T> rodar(prazoMs: Long, aoFalhar: (Exception) -> Unit = {}, minimoMs: Long = 0, tarefa: () -> T?): T? {
        val limite = relogio() + prazoMs
        val futuro = fila.submit(Callable {
            // A tarefa pode começar DEPOIS que o chamador desistiu (corrida entre o get expirar e o cancel abaixo):
            // nesse caso não roda, porque ninguém mais espera o resultado.
            if (limite - relogio() < minimoMs || relogio() > limite) null else tarefa()
        })
        return try {
            futuro.get(prazoMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // cancel(false): tira da fila a tarefa que ainda não começou. Uma que já está rodando segue até o fim
            // (o próprio comando tem o seu vigia de tempo, ver ServidorShizuku.executar).
            futuro.cancel(false)
            if (e is InterruptedException) Thread.currentThread().interrupt()   // devolve o aviso de interrupção
            aoFalhar(e)
            null
        }
    }
}
