package br.paulo.tvlauncher.alarm

import br.paulo.tvlauncher.Gatilhos
import java.util.Calendar
import java.util.TimeZone

/**
 * Os gatilhos que são HORA (elevação 10.6): nascer e pôr do sol, aniversário e compromisso da agenda. Puro
 * (RelogioGatilhosTest). Um relógio confere a cada minuto (Gatilhos.iniciar) se a hora de um deles caiu entre a
 * conferência anterior e agora: assim nenhum dispara duas vezes, e uma TV que ficou travada um minuto não perde.
 * Conferência atrasada demais (TV dormindo horas) não dispara o que passou: o evento é de "agora", não de ontem.
 */
object RelogioGatilhos {
    /** Mais que isso entre duas conferências: perdeu-se a hora (TV dormindo), nada dispara por atraso. */
    const val ATRASO_MAX_MS = 10 * 60_000L
    /** Hora do dia em que o aniversário dispara (9h: a casa já acordou). */
    const val HORA_ANIVERSARIO = 9

    /** A hora [quando] caiu em (antes, agora]? */
    fun caiu(antes: Long, agora: Long, quando: Long?): Boolean =
        quando != null && agora - antes in 1..ATRASO_MAX_MS && quando > antes && quando <= agora

    /** 9h de hoje, se hoje é aniversário de alguém da lista; senão null. */
    fun aniversarioHoje(pessoas: List<Aniversarios.Pessoa>, agora: Long, fuso: TimeZone = TimeZone.getDefault()): Long? {
        val c = Calendar.getInstance(fuso).apply { timeInMillis = agora }
        val dia = c.get(Calendar.DAY_OF_MONTH); val mes = c.get(Calendar.MONTH) + 1
        val ano = c.get(Calendar.YEAR)
        val bissexto = (ano % 4 == 0 && ano % 100 != 0) || ano % 400 == 0
        val ha = pessoas.any { p -> (p.dia == dia && p.mes == mes) || (!bissexto && p.dia == 29 && p.mes == 2 && dia == 1 && mes == 3) }
        if (!ha) return null
        c.set(Calendar.HOUR_OF_DAY, HORA_ANIVERSARIO); c.set(Calendar.MINUTE, 0); c.set(Calendar.SECOND, 0); c.set(Calendar.MILLISECOND, 0)
        return c.timeInMillis
    }

    /** Algum compromisso (não de dia inteiro) começou em (antes, agora]? [inicios] = (início, dia inteiro). */
    fun compromissoComecou(antes: Long, agora: Long, inicios: List<Pair<Long, Boolean>>): Boolean =
        inicios.any { (ini, diaInteiro) -> !diaInteiro && caiu(antes, agora, ini) }
}

/**
 * Internet caiu / voltou (elevação 10.6), puro (RelogioGatilhosTest). "Caiu" só depois de [esperaMs] sem rede (o Wi-Fi
 * que pisca não vira gatilho); "voltou" só se antes tinha dito "caiu".
 */
class FiltroRede(private val esperaMs: Long = 60_000L) {
    private var semRedeDesde: Long? = null
    private var disseCaiu = false

    fun perdeu(agora: Long) { if (semRedeDesde == null) semRedeDesde = agora }

    /** Devolve INTERNET_VOLTOU se tinha caído. */
    fun voltou(): Gatilhos.Evento? {
        semRedeDesde = null
        return if (disseCaiu) { disseCaiu = false; Gatilhos.Evento.INTERNET_VOLTOU } else null
    }

    /** Conferência periódica: devolve INTERNET_CAIU uma vez, depois da espera. */
    fun conferir(agora: Long): Gatilhos.Evento? {
        val d = semRedeDesde ?: return null
        if (disseCaiu || agora - d < esperaMs) return null
        disseCaiu = true
        return Gatilhos.Evento.INTERNET_CAIU
    }
}
