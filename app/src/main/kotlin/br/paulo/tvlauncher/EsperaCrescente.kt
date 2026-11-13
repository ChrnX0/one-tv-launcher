package br.paulo.tvlauncher

/**
 * Esperas e relógios do adb próprio (melhoria de 10/11/2026). Puro (sem Android): testado em EsperaCrescenteTest.
 */
object EsperaCrescente {
    /**
     * Religar a Depuração sem fio (Privilegio): o Android às vezes a desliga de novo logo depois que religamos (Wi-Fi
     * oscilando no boot, "Network disconnected. Disabling adbwifi"). Antes, cada desligada era religada em 3 s, para
     * sempre: um pingue-pongue com o Android. Agora a espera dobra a cada falha seguida (3 s, 6 s, 12 s...) até o teto.
     */
    const val RELIGAR_BASE_MS = 3_000L
    const val RELIGAR_TETO_MS = 5 * 60_000L
    /** Se a depuração ficou ligada por esse tempo depois da última religada, deu certo: a contagem volta a zero. */
    const val RELIGAR_ESTAVEL_MS = 2 * 60_000L

    /** Espera antes da religada número [falhas] (0 = a primeira). Dobra a cada falha, sem passar de [tetoMs]. */
    fun esperaMs(falhas: Int, baseMs: Long = RELIGAR_BASE_MS, tetoMs: Long = RELIGAR_TETO_MS): Long {
        if (falhas <= 0) return baseMs.coerceAtMost(tetoMs)
        // shl com limite: 2^20 já passa de qualquer teto razoável e não estoura o Long.
        val fator = 1L shl falhas.coerceAtMost(20)
        return if (baseMs > tetoMs / fator) tetoMs else (baseMs * fator).coerceAtMost(tetoMs)
    }

    /**
     * Quantas falhas seguidas contar agora. [msDesdeUltimaReligada]: tempo desde a última vez que religamos
     * (null = nunca religamos neste processo). Se a última religada "segurou" por [estavelMs], zera.
     */
    fun falhasAgora(falhas: Int, msDesdeUltimaReligada: Long?, estavelMs: Long = RELIGAR_ESTAVEL_MS): Int =
        if (msDesdeUltimaReligada == null || msDesdeUltimaReligada >= estavelMs) 0 else falhas.coerceAtLeast(0)

    /**
     * Relógio do guardião (a cada 10 min): o alarme do Android é "inexato" e cai dentro de uma janela. Janela de metade
     * do período (10 min -> entre 10 e 15 min), no mínimo 1 min: o Android junta o nosso alarme com os de outros apps
     * e a TV acorda menos vezes. [agoraMs] e o início devolvido são do relógio que conta o tempo dormindo.
     * Atenção: desde o Android 12 o AlarmManager estica janelas menores que 10 min para 10 min (documentação do
     * setWindow, não medido); na TV (Android 14) o tique de 10 min cai entre 10 e 20 min.
     */
    fun janelaRelogio(agoraMs: Long, periodoMs: Long): Pair<Long, Long> =
        Pair(agoraMs + periodoMs, (periodoMs / 2).coerceAtLeast(60_000L))
}
