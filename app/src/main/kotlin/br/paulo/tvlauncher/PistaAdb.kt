package br.paulo.tvlauncher

import java.util.concurrent.ExecutorService

/**
 * Fila do adb com DUAS PISTAS (melhoria de 10/11/2026).
 *
 * O problema: tudo que fala com o adb passava por uma fila de UMA thread. Um comando longo (executar_shell com 120 s,
 * `pm trim-caches`, as esperas de 10..120 s da reconexão do guardião) segurava a fila, e uma tecla do painel ou do
 * controle ficava esperando atrás dele até desistir.
 *
 * A solução:
 *  - Pista LENTA: a fila de sempre do guardião. É a ÚNICA que conecta, reconecta e derruba a conexão. Também leva os
 *    comandos longos.
 *  - Pista RÁPIDA: outra thread só para coisas curtas (teclas, `appops`, consultas). Ela NUNCA conecta nem derruba a
 *    conexão: só abre um fluxo novo na conexão que JÁ existe. Assim continua havendo uma conexão adb só.
 *
 * Por que dois fluxos ao mesmo tempo funcionam: o adb foi feito para isso (cada comando é um "fluxo" dentro da mesma
 * conexão; a tela ao vivo do painel já usa assim). A libadb tranca as trocas de conexão por dentro (lido no bytecode
 * da 3.1.1: autoConnect, openStream e pair usam a mesma trava).
 *
 * Puro (sem Android): testado em PistaAdbTest.
 */
object PistaAdb {
    enum class Pista { RAPIDA, LENTA }

    /** O que se quer fazer no adb. */
    enum class Pedido {
        COMANDO,     // um comando no shell
        PAREAR,      // o pareamento (código de 6 dígitos): não mexe na conexão e o código vence em segundos
        MANUTENCAO,  // conectar, reconectar, subir o servidor: só a pista lenta
    }

    /** Acima disso o comando vai para a pista lenta, mesmo que pareça curto (é o prazo padrão de GuardiaoShizuku.comando). */
    const val TETO_RAPIDA_MS = 20_000L

    /**
     * Começos de comando que costumam demorar (instalar, limpar cache, varrer disco, logs). Vale para qualquer pedaço de
     * um comando composto (`cd x && pm install ...`). Os outros, com prazo curto, vão na rápida.
     */
    private val LENTOS = listOf(
        "pm install", "pm install-create", "pm install-write", "pm install-commit", "cmd package install",
        "pm trim-caches", "pm compile", "cmd package compile", "cmd package bg-dexopt-job", "pm clear",
        "am kill-all", "dumpsys meminfo", "dumpsys activity", "bugreport", "bugreportz", "logcat",
        "sleep", "find", "du", "cp", "mv", "tar", "dd", "md5sum", "sha1sum", "sha256sum", "screenrecord", "monkey",
    )

    /**
     * Escolhe a pista.
     * [conectado]: se a conexão adb parece estar de pé. Sem conexão, só a pista lenta resolve (é ela que conecta).
     */
    fun escolher(pedido: Pedido, cmd: String = "", limiteMs: Long = TETO_RAPIDA_MS, conectado: Boolean = true): Pista = when (pedido) {
        Pedido.MANUTENCAO -> Pista.LENTA
        Pedido.PAREAR -> Pista.RAPIDA   // usa a chave, não a conexão: não pode esperar uma reconexão de 2 min
        Pedido.COMANDO -> when {
            !conectado -> Pista.LENTA
            limiteMs > TETO_RAPIDA_MS -> Pista.LENTA
            ehLento(cmd) -> Pista.LENTA
            else -> Pista.RAPIDA
        }
    }

    /** Lentos só quando vêm sozinhos: `dumpsys package` inteiro demora; `dumpsys package <pacote>` é curto. */
    private val LENTOS_SO_SOZINHOS = listOf("dumpsys package", "dumpsys")

    /** Prefixos que só embrulham o comando de verdade (`timeout 60 pm install`, `nice -n 5 find`, `env A=1 du`). */
    private val EMBRULHO = Regex("^(?:sh -c|exec|nohup|time|busybox|toybox|timeout(?: -[a-z]+(?: \\S+)?)* \\S+|nice(?: -n ?-?\\d+| -?\\d+)?|env(?: \\w+=\\S*)*) ")

    /**
     * true se algum pedaço do comando começa com um dos [LENTOS]. Separa também em parênteses, crases e aspas, para
     * achar o comando dentro de `sh -c '...'`, `(...)` e `$(...)`, e tira os prefixos de [EMBRULHO].
     */
    fun ehLento(cmd: String): Boolean =
        cmd.split(';', '&', '|', '\n', '(', ')', '`', '\'', '"', '{', '}')
            .map { tirarEmbrulho(it.trim().lowercase().replace(Regex("\\s+"), " ").removePrefix("$")) }
            .filter { it.isNotEmpty() }
            .any { pedaco ->
                LENTOS.any { pedaco == it || pedaco.startsWith("$it ") } || LENTOS_SO_SOZINHOS.any { pedaco == it }
            }

    private fun tirarEmbrulho(pedaco: String): String {
        var p = pedaco.trim()
        while (true) {
            val m = EMBRULHO.find("$p ") ?: return p
            val resto = p.substring(minOf(m.value.length, p.length)).trim()
            if (resto == p) return p
            p = resto
        }
    }
}

/**
 * As duas filas com prazo (cada uma de UMA thread). Ver [PistaAdb].
 * Pura: os executores e o relógio vêm de fora (teste JVM).
 */
class FilaDuasPistas(
    private val execLenta: ExecutorService,
    private val execRapida: ExecutorService,
    private val relogio: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val pLenta = FilaComPrazo(execLenta, relogio)
    private val pRapida = FilaComPrazo(execRapida, relogio)

    /** Resultado da pista rápida: [Feito] (com ou sem saída) ou [Desviar] (a conexão não estava de pé). */
    sealed class Rapida<out T> {
        data class Feito<T>(val valor: T?) : Rapida<T>()
        object Desviar : Rapida<Nothing>()
    }

    /**
     * Roda na [pista] e devolve o resultado (ou null). Na rápida, se [naRapida] pedir [Rapida.Desviar], o pedido vai
     * para a lenta com o que SOBROU do prazo (o chamador nunca espera mais que [prazoMs] no total).
     * [minimoMs]: só começa se ainda sobrar esse tempo (ver FilaComPrazo).
     */
    fun <T> rodar(
        pista: PistaAdb.Pista, prazoMs: Long, minimoMs: Long = 0, aoFalhar: (Exception) -> Unit = {},
        aoDesviar: () -> Unit = {}, naRapida: () -> Rapida<T>, naLenta: () -> T?,
    ): T? {
        if (pista == PistaAdb.Pista.LENTA) return pLenta.rodar(prazoMs, aoFalhar, minimoMs, naLenta)
        val inicio = relogio()
        val r = pRapida.rodar(prazoMs, aoFalhar, minimoMs, naRapida) ?: return null
        return when (r) {
            is Rapida.Feito -> r.valor
            Rapida.Desviar -> {
                aoDesviar()
                val sobra = prazoMs - (relogio() - inicio)
                if (sobra <= 0) null else pLenta.rodar(sobra, aoFalhar, minimoMs, naLenta)
            }
        }
    }

    /** Põe na pista sem esperar resposta (ex.: o pareamento vindo do adb do PC, num receiver que não pode travar). */
    fun soltar(pista: PistaAdb.Pista, tarefa: () -> Unit) = (if (pista == PistaAdb.Pista.RAPIDA) execRapida else execLenta).execute(tarefa)
}
