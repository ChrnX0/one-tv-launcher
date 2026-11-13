package br.paulo.tvlauncher

import android.os.Handler
import android.os.Process
import android.util.Log
import java.util.concurrent.Executors

/**
 * As contas pesadas das telas de catálogo (Filmes, Séries) fora da thread da tela (13/11/2026).
 *
 * Medido na Sala: com 20.975 filmes, ordenar, filtrar e contar as categorias na thread da tela travou Filmes por 112 s
 * (ordem por nome) e deixou ~2,5 s de tela parada ao abrir. Agora quem chama descreve a conta ([pedir]) e o que fazer
 * com o resultado; a conta roda numa fila de UMA thread e só o resultado do pedido MAIS NOVO chega à tela. Trocar de
 * categoria no meio da conta descarta a velha (se ainda não começou, nem roda).
 *
 * Regra para quem usa: a conta só lê o que recebeu (listas e mapas imutáveis, copiados da tela na hora do pedido) e
 * nunca toca em View. O resultado é aplicado na thread da tela.
 */
class CalculoTela(private val tela: Handler, nome: String) {
    private val fila = Executors.newSingleThreadExecutor { r ->
        Thread({ runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY + 4) }; r.run() }, nome)
            .apply { isDaemon = true }
    }

    /** Número do pedido mais novo. Só a thread da tela escreve; a fila lê para pular pedidos velhos. */
    @Volatile private var geracao = 0

    /** Há uma conta em andamento (para quem quer saber se a grade na tela ainda é a velha). */
    val calculando: Boolean get() = pendente != 0
    private var pendente = 0

    /**
     * Roda [calcular] na fila e entrega o resultado a [aplicar] na thread da tela, se nenhum pedido mais novo chegou.
     * [demorou] roda na tela se a conta passar de [avisoMs] (para mostrar "Carregando"); [fim] roda sempre que o pedido
     * mais novo termina (com resultado ou erro), para esconder esse aviso.
     */
    fun <R> pedir(
        calcular: () -> R,
        aplicar: (R) -> Unit,
        demorou: (() -> Unit)? = null,
        fim: (() -> Unit)? = null,
        avisoMs: Long = 400,
    ) {
        val g = ++geracao
        pendente = g
        val aviso = demorou?.let { d -> Runnable { if (g == geracao) d() } }
        aviso?.let { tela.postDelayed(it, avisoMs) }
        try {
            fila.execute {
                if (g != geracao) return@execute   // já tem pedido mais novo: nem começa
                val r = runCatching(calcular)
                tela.post {
                    aviso?.let { tela.removeCallbacks(it) }
                    if (g != geracao) return@post
                    pendente = 0
                    fim?.invoke()
                    r.onSuccess(aplicar).onFailure { Log.w("TvLauncher.Calculo", "conta da tela falhou: $it") }
                }
            }
        } catch (e: java.util.concurrent.RejectedExecutionException) {
            aviso?.let { tela.removeCallbacks(it) }   // tela fechando
        }
    }

    /** Tela fechada: descarta o que estiver na fila e não aceita mais nada. */
    fun fechar() {
        geracao++
        fila.shutdownNow()
    }
}
