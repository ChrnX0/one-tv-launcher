package br.paulo.tvlauncher.alarm

import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * Agenda da família (degrau 2, 16/11/2026): o perfil da TV troca sozinho no horário marcado. Exemplo: segunda a sexta,
 * 14:00 -> perfil do Pedro (que é de criança: liga o modo infantil e a hora de dormir); 20:00 -> perfil dos pais.
 * As REGRAS, sem Android (AgendaPerfisTest). Quem agenda o alarme e troca o perfil é o AgendaPerfisTv.kt.
 *
 * Configurar fica nas Configurações > Família, atrás do PIN dos pais: por isso a troca automática não pede PIN, nem
 * para sair de um perfil de criança (foi o adulto quem marcou o horário).
 */
object AgendaPerfis {
    const val TODOS = 0b1111111
    const val UTEIS = 0b0111110      // segunda (bit 1) a sexta (bit 5); bit 0 = domingo, como Calendar.DAY_OF_WEEK - 1
    const val FIM_DE_SEMANA = 0b1000001
    const val MAX = 12

    data class Entrada(val conta: String, val dias: Int, val minuto: Int)

    fun ler(texto: String?): List<Entrada> = try {
        val a = JSONArray(texto ?: "[]")
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val conta = o.optString("conta").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val dias = o.optInt("dias", 0) and TODOS
            val min = o.optInt("min", -1)
            if (dias == 0 || min !in 0 until 24 * 60) null else Entrada(conta, dias, min)
        }.take(MAX)
    } catch (_: Exception) { emptyList() }

    fun escrever(l: List<Entrada>): String = JSONArray().apply {
        l.take(MAX).forEach { put(JSONObject().put("conta", it.conta).put("dias", it.dias).put("min", it.minuto)) }
    }.toString()

    /** Em ordem de horário (para a lista na tela). */
    fun ordenar(l: List<Entrada>) = l.sortedWith(compareBy({ it.minuto }, { -it.dias }, { it.conta }))

    private fun cal(agora: Long, zona: TimeZone) = Calendar.getInstance(zona).apply { timeInMillis = agora }

    /** Os instantes em que [e] começa, de [diasAtras] dias atrás até [diasFrente] dias à frente. */
    private fun inicios(e: Entrada, agora: Long, zona: TimeZone, diasAtras: Int, diasFrente: Int): List<Long> =
        (-diasAtras..diasFrente).mapNotNull { d ->
            val c = cal(agora, zona).apply {
                add(Calendar.DAY_OF_YEAR, d)
                set(Calendar.HOUR_OF_DAY, e.minuto / 60); set(Calendar.MINUTE, e.minuto % 60); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            if (e.dias and (1 shl (c.get(Calendar.DAY_OF_WEEK) - 1)) != 0) c.timeInMillis else null
        }

    /** A próxima troca depois de [agora] (para o alarme), ou null se a agenda está vazia. */
    fun proxima(l: List<Entrada>, agora: Long, zona: TimeZone): Pair<Entrada, Long>? =
        l.flatMap { e -> inicios(e, agora, zona, 0, 7).filter { it > agora }.map { e to it } }.minByOrNull { it.second }

    /** A troca que vale agora: a última que começou (até 7 dias atrás). Para acertar o perfil se o alarme se perdeu. */
    fun vigente(l: List<Entrada>, agora: Long, zona: TimeZone): Pair<Entrada, Long>? =
        l.flatMap { e -> inicios(e, agora, zona, 7, 0).filter { it <= agora }.map { e to it } }.maxByOrNull { it.second }

    /**
     * A HOME voltou ou o processo abriu: o perfil deve trocar agora? Só se a troca vigente começou DEPOIS da última
     * que aplicamos ([aplicadaEm]): se a família trocou o perfil à mão depois disso, a escolha dela fica.
     */
    fun deveAplicar(l: List<Entrada>, agora: Long, zona: TimeZone, aplicadaEm: Long): Entrada? =
        vigente(l, agora, zona)?.takeIf { it.second > aplicadaEm }?.first

    /** O próximo horário da lista de escolha: de 30 em 30 min. */
    val HORARIOS: List<Int> = (0 until 48).map { it * 30 }
}
