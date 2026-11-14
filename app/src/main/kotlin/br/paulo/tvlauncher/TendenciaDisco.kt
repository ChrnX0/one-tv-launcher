package br.paulo.tvlauncher

import java.time.LocalDate

/**
 * Ideia 41 do estudo (14/11/2026): Saúde com tendência. A aba Saúde mostrava só o agora; aqui guardamos UMA medida
 * de espaço livre por dia ([GUARDAR_DIAS] dias) e calculamos quando o armazenamento enche, para avisar antes
 * ("o armazenamento enche em umas 3 semanas"). Sem Android: testado no PC em TendenciaDiscoTest.
 *
 * Conta: reta de mínimos quadrados sobre os últimos [JANELA_DIAS] dias (livre x dia). Só vale com [MIN_PONTOS]
 * medidas espalhadas por pelo menos [MIN_ESPALHADO] dias; reta subindo ou plana = não enche. "Cheio" = livre abaixo
 * da [reserva] (o Android começa a falhar instalações e o cache dos apps some antes de chegar a zero).
 * Formato guardado (prefs, string): `aaaa-mm-dd:livreBytes,` repetido.
 */
object TendenciaDisco {
    const val GUARDAR_DIAS = 60
    const val JANELA_DIAS = 21
    const val MIN_PONTOS = 5
    const val MIN_ESPALHADO = 6
    /** Acima disso de dias a previsão não é dita (reta longa demais para confiar). */
    const val MAX_DIAS = 120
    /** Abaixo disso de dias manda o aviso no celular. */
    const val AVISAR_DIAS = 21

    class Ponto(val dia: LocalDate, val livre: Long)

    fun reserva(total: Long): Long = minOf(500L shl 20, total / 20)

    fun ler(texto: String?): List<Ponto> = (texto ?: "").split(',').mapNotNull { p ->
        val (d, v) = p.split(':').takeIf { it.size == 2 } ?: return@mapNotNull null
        val dia = runCatching { LocalDate.parse(d) }.getOrNull() ?: return@mapNotNull null
        v.toLongOrNull()?.takeIf { it >= 0 }?.let { Ponto(dia, it) }
    }.associateBy { it.dia }.values.sortedBy { it.dia }

    fun escrever(l: List<Ponto>) = l.joinToString(",") { "${it.dia}:${it.livre}" }

    /** Acrescenta (ou troca) a medida de [hoje] e joga fora o que passou de [GUARDAR_DIAS]. */
    fun registrar(l: List<Ponto>, hoje: LocalDate, livre: Long): List<Ponto> =
        (l.filter { it.dia != hoje && it.dia > hoje.minusDays(GUARDAR_DIAS.toLong()) } + Ponto(hoje, livre)).sortedBy { it.dia }

    /** Dias até o livre chegar à reserva, contados de [hoje]. null = não enche no horizonte, ou faltam medidas. */
    fun diasAteEncher(l: List<Ponto>, hoje: LocalDate, total: Long): Int? {
        val janela = l.filter { it.dia > hoje.minusDays(JANELA_DIAS.toLong()) && it.dia <= hoje }
        if (janela.size < MIN_PONTOS) return null
        val x = janela.map { (it.dia.toEpochDay() - hoje.toEpochDay()).toDouble() }
        val y = janela.map { it.livre.toDouble() }
        if (x.max() - x.min() < MIN_ESPALHADO) return null
        val mx = x.average(); val my = y.average()
        val den = x.sumOf { (it - mx) * (it - mx) }
        if (den == 0.0) return null
        val inclinacao = x.indices.sumOf { (x[it] - mx) * (y[it] - my) } / den   // bytes por dia
        if (inclinacao >= 0) return null
        val livreHoje = my - inclinacao * mx   // valor da reta em x = 0 (hoje)
        val dias = (livreHoje - reserva(total)) / -inclinacao
        if (dias.isNaN()) return null
        return dias.toInt().coerceAtLeast(0).takeIf { it <= MAX_DIAS }
    }
}
