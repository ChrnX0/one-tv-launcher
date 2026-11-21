package br.paulo.tvlauncher.alarm

/**
 * Regra pura do "Desligar a TV sem uso" (sem Android, testada em AutoDesligarRegraTest).
 *
 * 15/11/2026, reclamação da família: com o tempo em 30 min a TV avisava e desligava no meio do filme, da novela ou do
 * YouTube, porque só a tecla do controle contava como uso. Agora som tocando (qualquer app: Netflix, YouTube, o
 * nosso player, TV ao vivo) também conta: o prazo corre a partir do ÚLTIMO sinal de uso, tecla ou mídia.
 *
 * Teto: a TV ao vivo toca sem parar; sem um limite, a TV esquecida ligada de madrugada nunca apagaria (que é o motivo
 * do recurso existir). Por isso, com mídia tocando e nenhuma tecla há [TETO_MIDIA_MIN] (ou o tempo escolhido, se for
 * maior), o aviso aparece mesmo assim, e qualquer botão o cancela.
 */
object AutoDesligarRegra {
    const val TETO_MIDIA_MIN = 180

    /**
     * Hora (no mesmo relógio dos argumentos) em que a TV deve desligar.
     * @param ultimaTecla última tecla do controle/painel
     * @param ultimaMidia última vez que se viu som tocando (0 = nunca)
     * @param minutos tempo escolhido (> 0)
     */
    fun prazo(ultimaTecla: Long, ultimaMidia: Long, minutos: Int): Long {
        val porUso = maxOf(ultimaTecla, ultimaMidia) + minutos * 60_000L
        val teto = ultimaTecla + maxOf(minutos, TETO_MIDIA_MIN) * 60_000L
        return minOf(porUso, teto)
    }
}
