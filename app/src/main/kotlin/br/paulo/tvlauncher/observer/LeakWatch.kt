package br.paulo.tvlauncher.observer

import android.app.Activity
import android.os.SystemClock
import java.lang.ref.WeakReference

/**
 * Vigia de vazamento (20/11/2026, rodada de desempenho): o `dumpsys meminfo` da Sala mostrava 3 telas vivas no processo
 * principal com só 1 aberta, e não dá para tirar um retrato da memória do app na TV. Aqui cada tela fechada vira uma
 * referência fraca (não segura nada); o comando adb `EXTRAS --es o vazamentos` força a coleta de lixo e lista as que
 * continuam vivas, com há quanto tempo fecharam. Tela fechada há mais de alguns segundos e ainda viva = alguém segura
 * ela (um ouvinte, uma lista global, uma tarefa em fundo). Não roda nada sozinho: só anota no fechar e conta quando pedem.
 */
object LeakWatch {
    private class Fechada(val tela: WeakReference<Activity>, val nome: String, val quando: Long)

    private const val MAX = 60
    private val fechadas = ArrayList<Fechada>()

    fun aoDestruir(a: Activity) = synchronized(fechadas) {
        fechadas.removeAll { it.tela.get() == null }
        if (fechadas.size >= MAX) fechadas.removeAt(0)
        fechadas += Fechada(WeakReference(a), a.javaClass.simpleName, SystemClock.elapsedRealtime())
    }

    /** Força a coleta (duas voltas, com os finalizadores no meio) e conta as telas fechadas que ainda estão na memória. */
    fun relatorio(): String {
        repeat(2) { Runtime.getRuntime().gc(); System.runFinalization() }
        val agora = SystemClock.elapsedRealtime()
        val vivas = synchronized(fechadas) {
            fechadas.removeAll { it.tela.get() == null }
            fechadas.map { "${it.nome} (fechou há ${(agora - it.quando) / 1000} s)" }
        }
        return if (vivas.isEmpty()) "nenhuma tela fechada presa na memória" else vivas.joinToString("\n", "${vivas.size} presa(s):\n")
    }
}
