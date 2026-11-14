package br.paulo.tvlauncher

import org.json.JSONObject

/**
 * Roteiro de conferência na TV (14/11/2026, ideia 63). Puro (só org.json), com teste JVM.
 *
 * Cada versão traz uma lista curta do que conferir com o controle na mão (res/values/strings_versoes.xml e values-pt,
 * `roteiro_ids`/`roteiro_titulos`/`roteiro_perguntas`). A tela ConferirTvActivity grava aqui o "sim"/"não" de cada
 * item; as conversas leem pelo adb (EXTRAS "roteiro"). Respostas de outra versão não valem: trocou a versão, zera.
 */
object RoteiroConferencia {
    data class Estado(val versao: Long, val respostas: Map<String, Boolean> = emptyMap())

    /** Lê o estado gravado; se for de outra versão (ou ilegível), começa vazio para [versaoAtual]. */
    fun ler(json: String?, versaoAtual: Long): Estado {
        if (json.isNullOrBlank()) return Estado(versaoAtual)
        return try {
            val o = JSONObject(json)
            if (o.optLong("versao") != versaoAtual) return Estado(versaoAtual)
            val r = o.optJSONObject("respostas") ?: JSONObject()
            Estado(versaoAtual, r.keys().asSequence().associateWith { r.optBoolean(it) })
        } catch (e: Exception) { Estado(versaoAtual) }
    }

    fun responder(e: Estado, id: String, sim: Boolean): Estado = e.copy(respostas = e.respostas + (id to sim))

    /** Quantos dos [ids] desta versão já têm resposta (ids antigos que saíram da lista não contam). */
    fun conferidos(e: Estado, ids: List<String>): Int = ids.count { it in e.respostas }

    fun gravar(e: Estado): String = JSONObject().put("versao", e.versao)
        .put("respostas", JSONObject().apply { e.respostas.forEach { (k, v) -> put(k, v) } }).toString()

    /** Resumo para o adb: versão, total, conferidos e cada item como "sim", "nao" ou "falta". */
    fun relatorio(e: Estado, ids: List<String>): String = JSONObject().put("versao", e.versao).put("total", ids.size)
        .put("conferidos", conferidos(e, ids))
        .put("itens", JSONObject().apply { ids.forEach { put(it, when (e.respostas[it]) { true -> "sim"; false -> "nao"; null -> "falta" }) } })
        .toString()
}
