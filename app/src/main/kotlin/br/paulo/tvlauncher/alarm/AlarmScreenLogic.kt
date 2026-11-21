package br.paulo.tvlauncher.alarm

import br.paulo.tvlauncher.AlarmesActivity

/**
 * As contas da tela do despertador ([AlarmesActivity]) sem Android, para testar na JVM (redesenho no jeito da cruz,
 * 18/11/2026; Paulo: "a tela de configuração de alarme tem que melhorar, UI e UX").
 */
object AlarmScreenLogic {

    /** Os 4 jeitos prontos de repetir, na ordem da lista "Repetir": uma vez, todo dia, segunda a sexta, fim de semana. */
    val PRONTOS = intArrayOf(0, 0b1111111, 0b0111110, 0b1000001)

    /** Hora do rolete: ▲ soma 1, ▼ tira 1, dando a volta (23 -> 0). */
    fun girarHora(hora: Int, delta: Int): Int = ((hora + delta) % 24 + 24) % 24

    /**
     * Minuto do rolete. Um toque anda 1; segurando ([rapido]) anda até o próximo múltiplo de 5 naquela direção
     * (07 -> 10 -> 15...), para "7:30" sair em poucos segundos sem passar do ponto.
     */
    fun girarMinuto(minuto: Int, sobe: Boolean, rapido: Boolean): Int {
        if (!rapido) return ((minuto + if (sobe) 1 else -1) % 60 + 60) % 60
        return if (sobe) (minuto / 5 + 1) * 5 % 60
        else { val m = if (minuto % 5 == 0) minuto - 5 else minuto / 5 * 5; (m + 60) % 60 }
    }

    /** Intervalo mínimo entre passos com a seta SEGURADA (o controle repete a cada ~50 ms). */
    const val RITMO_SEGURANDO_MS = 250L

    /** Aceita a tecla? O 1º aperto sempre; as repetições de quem segura, só a cada [RITMO_SEGURANDO_MS]. */
    fun passaRepeticao(repeticao: Int, ultimaMs: Long, agoraMs: Long): Boolean =
        repeticao == 0 || agoraMs - ultimaMs >= RITMO_SEGURANDO_MS

    /** Índice em [PRONTOS] dos dias marcados, ou -1 quando é uma escolha à mão (ex.: só terça e quinta). */
    fun pronto(dias: Int): Int = PRONTOS.indexOf(dias and 0b1111111)

    /** Liga/desliga o dia [i] (0 = domingo .. 6 = sábado). */
    fun alternarDia(dias: Int, i: Int): Int = dias xor (1 shl i)

    /**
     * A lista "Repetir" junta os 4 prontos e os 7 dias. Devolve os dias novos depois do OK na linha [linha], e se a
     * lista deve abrir de novo (dia avulso: a pessoa costuma marcar mais de um).
     */
    fun escolherRepetir(dias: Int, linha: Int): Pair<Int, Boolean> =
        if (linha < PRONTOS.size) PRONTOS[linha] to false
        else alternarDia(dias, linha - PRONTOS.size) to true

    /** Linhas marcadas (✓) na lista "Repetir": o pronto igual aos dias, ou cada dia marcado. */
    fun marcadasRepetir(dias: Int): List<Int> {
        val p = pronto(dias)
        val avulsos = (0..6).filter { dias and (1 shl it) != 0 }.map { it + PRONTOS.size }
        return if (p >= 0) listOf(p) + avulsos else avulsos
    }

    /** Valores do editor que contam para "mudou alguma coisa" (VOLTAR só salva alarme novo se a pessoa mexeu). */
    data class Valores(val hora: Int, val minuto: Int, val dias: Int, val nome: String, val ativo: Boolean,
                       val som: Int, val rampa: Int, val bomDia: Boolean, val cena: String)

    fun mudou(antes: Valores, agora: Valores): Boolean = antes != agora.copy(nome = agora.nome.trim())

}
