package br.paulo.tvlauncher

import br.paulo.tvlauncher.family.Perfil
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * "Só se" das automações por evento (elevação 10.5, onda 1, 17/11/2026): a regra, pura (SoSeTest). A automação só roda
 * se TODAS as condições dela valem agora. Condição que a TV não sabe responder (presença desligada, sem localização
 * para o pôr do sol, nenhuma conta) NÃO vale: na dúvida, a luz não acende sozinha.
 *
 * Gravado nas prefs "launcher", chave "gatilhos_so_se" ({"midia_tocou": ["em_casa","noite"]}), ao lado dos gatilhos
 * (Gatilhos.kt); vai no backup completo junto.
 */
object SoSe {
    sealed class Condicao(val chave: String) {
        object AlguemEmCasa : Condicao("em_casa")
        object DepoisDoPorDoSol : Condicao("noite")
        object DiaUtil : Condicao("dia_util")
        /** O perfil (conta Google) ativo na TV é [conta]. */
        data class Perfil(val conta: String) : Condicao("perfil:$conta")

        companion object {
            fun de(t: String): Condicao? = when {
                t == "em_casa" -> AlguemEmCasa
                t == "noite" -> DepoisDoPorDoSol
                t == "dia_util" -> DiaUtil
                t.startsWith("perfil:") && t.length in 9..120 -> Perfil(t.removePrefix("perfil:"))
                else -> null
            }
        }
    }

    /**
     * O que a TV sabe agora. [emCasa] null = não sei. [nascer]/[por] = hoje, em ms (null = sem localização).
     * [diaDaSemana] como Calendar (1 = domingo). [feriado] = hoje é feriado (dia útil não vale).
     */
    data class Estado(val agora: Long, val emCasa: Boolean? = null, val nascer: Long? = null, val por: Long? = null,
                      val diaDaSemana: Int = Calendar.MONDAY, val feriado: Boolean = false, val perfil: String? = null)

    fun vale(c: Condicao, e: Estado): Boolean = when (c) {
        Condicao.AlguemEmCasa -> e.emCasa == true
        Condicao.DepoisDoPorDoSol -> e.por != null && e.nascer != null && (e.agora >= e.por || e.agora < e.nascer)
        Condicao.DiaUtil -> e.diaDaSemana in Calendar.MONDAY..Calendar.FRIDAY && !e.feriado
        is Condicao.Perfil -> e.perfil != null && e.perfil.equals(c.conta, ignoreCase = true)
    }

    fun todas(l: List<Condicao>, e: Estado): Boolean = l.all { vale(it, e) }

    fun diaDaSemana(ms: Long, fuso: TimeZone = TimeZone.getDefault()): Int = Calendar.getInstance(fuso).apply { timeInMillis = ms }.get(Calendar.DAY_OF_WEEK)

    // ---------------------------------------------------------------- gravar

    fun lerJson(t: String?): Map<Gatilhos.Evento, List<Condicao>> = runCatching {
        val o = JSONObject(t ?: return emptyMap())
        Gatilhos.Evento.entries.mapNotNull { ev ->
            val a = o.optJSONArray(ev.chave) ?: return@mapNotNull null
            val l = (0 until a.length()).mapNotNull { Condicao.de(a.optString(it)) }.distinct()
            if (l.isEmpty()) null else ev to l
        }.toMap()
    }.getOrDefault(emptyMap())

    fun paraJson(m: Map<Gatilhos.Evento, List<Condicao>>): String = JSONObject().apply {
        for ((e, l) in m) if (l.isNotEmpty()) put(e.chave, JSONArray(l.map { it.chave }))
    }.toString()

    /** As opções do ◀ ▶ na tela Automações: nenhuma, cada condição sozinha (e cada perfil). Combinar: pelo painel. */
    fun opcoes(contas: List<String>): List<List<Condicao>> =
        listOf(emptyList<Condicao>(), listOf(Condicao.AlguemEmCasa), listOf(Condicao.DepoisDoPorDoSol), listOf(Condicao.DiaUtil)) +
            contas.map { listOf(Condicao.Perfil(it)) }
}
