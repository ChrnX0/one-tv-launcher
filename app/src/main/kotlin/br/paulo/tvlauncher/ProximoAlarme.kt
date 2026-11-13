package br.paulo.tvlauncher

import java.util.Calendar
import java.util.TimeZone

/**
 * Quando um alarme (ou uma rotina) toca da próxima vez. Só a conta, sem nada do Android, para testar no computador
 * (ProximoAlarmeTest). Quem usa: Despertador.proximaVez e, por ele, Rotinas.proximaVez.
 *
 * Correção de 10/11/2026: a versão antiga ajustava a hora UMA vez e depois somava 1 dia por volta. No dia em que o
 * relógio adianta (horário de verão), um alarme marcado dentro da hora que "não existe" (ex.: 00:30 quando 00:00 vira
 * 01:00) era empurrado para 01:30, e esse 01:30 seguia para os dias seguintes: o alarme de segunda tocava 1 h atrasado.
 * Agora cada dia monta a hora do zero, a partir da data.
 */
object ProximoAlarme {
    /** Dias da semana em bits: bit 0 = domingo ... bit 6 = sábado (o mesmo de Despertador.Alarme.dias). 0 = uma vez só. */
    fun tocaNoDia(dias: Int, diaDaSemana: Int): Boolean = dias == 0 || dias and (1 shl diaDaSemana) != 0

    /**
     * Primeiro instante depois de [agoraMs] em que [hora]:[minuto] cai num dia marcado em [dias] (0 = qualquer dia,
     * para o alarme de uma vez só). Olha hoje e os 7 dias seguintes; null se nenhum servir (não acontece com [dias]
     * válido). [fuso]: o da TV; o teste passa um fixo.
     */
    fun calcular(hora: Int, minuto: Int, dias: Int, agoraMs: Long, fuso: TimeZone = TimeZone.getDefault()): Long? {
        for (dia in 0..7) {
            val c = Calendar.getInstance(fuso).apply {
                timeInMillis = agoraMs
                // Meio-dia antes de somar: longe da meia-noite, a soma de dias nunca pula nem repete uma data por causa
                // do horário de verão (a troca acontece de madrugada).
                set(Calendar.HOUR_OF_DAY, 12); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
                add(Calendar.DAY_OF_MONTH, dia)
            }
            val diaDaSemana = c.get(Calendar.DAY_OF_WEEK) - 1   // Calendar: domingo = 1
            if (!tocaNoDia(dias, diaDaSemana)) continue
            c.set(Calendar.HOUR_OF_DAY, hora); c.set(Calendar.MINUTE, minuto)
            if (c.timeInMillis > agoraMs) return c.timeInMillis
        }
        return null
    }
}
