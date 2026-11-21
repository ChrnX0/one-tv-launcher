package br.paulo.tvlauncher.alarm

import org.json.JSONArray

/**
 * Taxa de acerto do despertador (elevação 10.2, onda 0, 17/11/2026): "tocou na hora em N de N", contando também os
 * perdidos. Só a conta (AcertoAlarmeTest); quem anota é o AlarmeGarantia, nos mesmos pontos que já decidem se um
 * toque tocou ou foi perdido.
 *
 * Cada toque devido entra UMA vez, pela hora prevista (o Android pode entregar o mesmo pedido duas vezes):
 *  - NA_HORA: a tela do alarme abriu até [NA_HORA_MS] depois do previsto;
 *  - ATRASADO: tocou, mas depois disso (a TV reiniciou na hora e o AlarmeGarantia tocou ao voltar, até 15 min);
 *  - PERDIDO: não tocou (TV fora da tomada, relógio pulou, tarde demais para tocar).
 * Se o mesmo toque aparecer de novo com outro resultado, vale o PIOR (perdido > atrasado > na hora): o placar nunca
 * pode ficar mais bonito que a verdade.
 */
object AcertoAlarme {
    /** Até 1 min depois do previsto conta como "na hora" (o AlarmManager exato entrega em segundos). */
    const val NA_HORA_MS = 60_000L
    /** Guarda um ano de toques diários com folga; o placar mostra o total guardado. */
    const val GUARDAR = 400

    enum class Resultado { NA_HORA, ATRASADO, PERDIDO }

    data class Toque(val previsto: Long, val resultado: Resultado)

    fun classificar(previsto: Long, tocouEm: Long): Resultado =
        if (tocouEm - previsto <= NA_HORA_MS) Resultado.NA_HORA else Resultado.ATRASADO

    fun anotar(l: List<Toque>, t: Toque): List<Toque> {
        if (t.previsto <= 0) return l
        val antes = l.firstOrNull { it.previsto == t.previsto }
        if (antes != null && antes.resultado.ordinal >= t.resultado.ordinal) return l   // já anotado igual ou pior
        return (l.filter { it.previsto != t.previsto } + t).sortedBy { it.previsto }.takeLast(GUARDAR)
    }

    data class Resumo(val naHora: Int, val atrasados: Int, val perdidos: Int, val ultimoEm: Long?) {
        val total get() = naHora + atrasados + perdidos
    }

    fun resumo(l: List<Toque>): Resumo = Resumo(
        l.count { it.resultado == Resultado.NA_HORA }, l.count { it.resultado == Resultado.ATRASADO },
        l.count { it.resultado == Resultado.PERDIDO }, l.maxOfOrNull { it.previsto })

    fun texto(l: List<Toque>): String = JSONArray().apply { l.forEach { put(JSONArray().put(it.previsto).put(it.resultado.ordinal)) } }.toString()

    fun ler(t: String?): List<Toque> = try {
        val a = JSONArray(t ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val p = a.optJSONArray(i) ?: return@mapNotNull null
            val r = Resultado.entries.getOrNull(p.optInt(1, -1)) ?: return@mapNotNull null
            Toque(p.optLong(0), r).takeIf { it.previsto > 0 }
        }.sortedBy { it.previsto }.takeLast(GUARDAR)
    } catch (_: Exception) { emptyList() }
}
