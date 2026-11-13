package br.paulo.tvlauncher

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.TimeZone

/**
 * Contas de data sem nada do Android, para testar no computador (DatasTest). Auditoria de 10/11/2026.
 * Quem usa: AlarmesActivity (rótulo "hoje/amanhã") e Agenda (eventos de dia inteiro).
 */
object Datas {
    const val DIA_MS = 86_400_000L

    /**
     * Quantos dias de calendário vão de [agoraMs] até [alvoMs] no [fuso]: 0 = hoje, 1 = amanhã...
     * Por que contar DATAS e não dividir milissegundos por 24 h: a conta antiga zerava só hora e minuto, os segundos
     * de "agora" sobravam e "amanhã às 07:00" virava "hoje" (e um dia de 23 h no horário de verão também errava).
     */
    fun diasAte(alvoMs: Long, agoraMs: Long, fuso: TimeZone = TimeZone.getDefault()): Long {
        val z = fuso.toZoneId()
        return ChronoUnit.DAYS.between(Instant.ofEpochMilli(agoraMs).atZone(z).toLocalDate(),
            Instant.ofEpochMilli(alvoMs).atZone(z).toLocalDate())
    }

    /**
     * Janela que se pede à agenda do Android: 1 dia a mais de cada lado. Por quê: a agenda filtra os eventos de dia
     * inteiro pelo horário em UTC (meia-noite de Greenwich = 21:00 aqui). Sem a folga, o "dia inteiro de hoje" sumia
     * depois das 21h. O recorte certo é feito depois, com [dentro], já no horário local.
     */
    fun janelaConsulta(de: Long, ate: Long): Pair<Long, Long> = (de - DIA_MS) to (ate + DIA_MS)

    /** Evento de dia inteiro vem em UTC: leva início e fim para a meia-noite LOCAL (senão cai no dia anterior). */
    fun ajustarDiaInteiro(inicioUtc: Long, fimUtc: Long, fuso: TimeZone = TimeZone.getDefault()): Pair<Long, Long> =
        (inicioUtc - fuso.getOffset(inicioUtc)) to (fimUtc - fuso.getOffset(fimUtc))

    /**
     * O evento [inicio, fim] encosta no intervalo [de, ate]? Mesma regra da agenda do Android (início <= ate e
     * fim >= de), só que aplicada DEPOIS do ajuste de fuso: o dia inteiro de amanhã não entra mais como "hoje".
     */
    fun dentro(inicio: Long, fim: Long, de: Long, ate: Long): Boolean = inicio <= ate && fim >= de
}
