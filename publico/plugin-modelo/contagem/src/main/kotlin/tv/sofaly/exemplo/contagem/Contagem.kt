package tv.sofaly.exemplo.contagem

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A regra do cartão, sem Android (fácil de testar): quantos dias faltam e o texto que vai no cartão. */
object Contagem {
    /** Dias de [hoje] até [alvo]. Negativo = já passou. */
    fun diasAte(hoje: LocalDate, alvo: LocalDate): Long = ChronoUnit.DAYS.between(hoje, alvo)

    /** O número grande do cartão (até 12 letras, regra do launcher). */
    fun valor(dias: Long): String = when {
        dias == 0L -> "0"
        dias > 0 -> dias.toString()
        else -> "+" + (-dias)
    }.take(12)
}
