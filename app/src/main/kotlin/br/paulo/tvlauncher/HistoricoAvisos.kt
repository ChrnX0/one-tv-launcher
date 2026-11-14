package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Ideia 35 do estudo (14/11/2026): central de avisos na TV. Antes era um aviso por vez e sem histórico: o novo
 * substituía o anterior, e quem chegava depois não via o recado nem o aviso de chuva. Agora o [AvisoTv] guarda os
 * últimos [MAX] avisos do próprio launcher (chuva, recado, lembrete, manutenção...) e o painel rápido lista abaixo
 * das notificações dos apps. As confirmações curtas ([AvisoTv.rapido]: "Despertador salvo") não entram.
 *
 * Sem Android (testado no PC em HistoricoAvisosTest). Guardado como JSON nas prefs "aviso_tv_historico", fora do
 * backup (é passageiro).
 */
object HistoricoAvisos {
    const val MAX = 20
    /** O mesmo aviso repetido dentro disso (chuva conferida de novo, recado mandado duas vezes) não vira outra linha. */
    const val REPETIDO_MS = 10 * 60_000L
    /** Aviso mais velho que isso sai da lista. */
    const val VALIDADE_MS = 3L * 24 * 60 * 60_000L

    class Item(val titulo: String, val texto: String, val quando: Long)

    fun acrescentar(l: List<Item>, novo: Item): List<Item> {
        val semRepetido = l.filterNot { it.titulo == novo.titulo && it.texto == novo.texto && novo.quando - it.quando in 0 until REPETIDO_MS }
        return (listOf(novo) + semRepetido).take(MAX)
    }

    /** Os que ainda valem, do mais novo para o mais velho. */
    fun validos(l: List<Item>, agora: Long): List<Item> = l.filter { agora - it.quando in 0 until VALIDADE_MS }.sortedByDescending { it.quando }

    fun remover(l: List<Item>, quando: Long): List<Item> = l.filterNot { it.quando == quando }

    fun paraJson(l: List<Item>): String = JSONArray().apply {
        l.forEach { put(JSONObject().put("t", it.titulo).put("x", it.texto).put("q", it.quando)) }
    }.toString()

    fun deJson(s: String?): List<Item> {
        val a = try { JSONArray(s ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val q = o.optLong("q", -1).takeIf { it > 0 } ?: return@mapNotNull null
            Item(o.optString("t").take(120), o.optString("x").take(400), q).takeIf { it.titulo.isNotBlank() || it.texto.isNotBlank() }
        }.take(MAX)
    }
}
