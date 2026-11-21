package br.paulo.tvlauncher.observer

import org.json.JSONArray
import org.json.JSONObject

/**
 * Observador, vigia de pacotes, parte 1: inventário (pedido do Paulo, 20/11/2026: "observar todos os pacotes instalados,
 * descobrir o que eles fazem ... para sabermos se podemos desinstalar, congelar ou não mexer"; e 13:50: "ele também
 * precisa estudar e entender os pacotes de sistema").
 *
 * Porquê: as fichas (`assets/fichas-apps.json`, mais as da comunidade) dizem o que cada pacote faz e o risco de mexer,
 * mas só valem para os pacotes que alguém estudou. Um app novo, ou uma atualização do Google que traz um pacote de
 * sistema novo, ficava sem ficha e ninguém sabia. Para quê: o Observador aponta os pacotes DESCONHECIDOS (sem ficha),
 * pela ferramenta MCP `observer`; o Claude pesquisa e escreve a ficha, que volta pelo app ou pela comunidade. A TV não
 * pesquisa na internet sozinha (nada da casa sai dela, e fica dentro das regras da Play).
 * Como: [inventory] cruza a lista de pacotes do aparelho com as fichas. Contagem por risco, desconhecidos de sistema
 * primeiro (são os que pedem mais cuidado). Também conta os congelados. Pura (sem Android): ObserverPackagesTest.
 */
object ObserverPackages {

    data class Pkg(val name: String, val system: Boolean, val frozen: Boolean)

    data class Inventory(
        val total: Int,
        val system: Int,
        val frozen: List<String>,
        val byRisk: Map<String, Int>,
        /** Sem ficha: sistema primeiro, depois por nome. */
        val unknown: List<Pkg>,
        /** false = o app não vê todos os pacotes (versão da Play, sem QUERY_ALL_PACKAGES): os números são só dos visíveis. */
        val complete: Boolean = true,
    ) {
        fun toJson(max: Int = 60): JSONObject = JSONObject()
            .put("complete", complete).put("total", total).put("system", system)
            .put("frozen", JSONArray(frozen))
            .put("by_risk", JSONObject().apply { byRisk.forEach { (k, n) -> put(k, n) } })
            .put("unknown_total", unknown.size)
            .put("unknown", JSONArray().apply { unknown.take(max).forEach { put(JSONObject().put("package", it.name).put("system", it.system).put("frozen", it.frozen)) } })
    }

    fun inventory(installed: List<Pkg>, fichas: JSONObject, complete: Boolean = true): Inventory {
        val porRisco = sortedMapOf<String, Int>()
        val desconhecidos = ArrayList<Pkg>()
        for (p in installed) {
            val r = fichas.optJSONObject(p.name)?.optString("r")?.takeIf { it.isNotEmpty() }
            if (r == null) desconhecidos += p else porRisco[r] = (porRisco[r] ?: 0) + 1
        }
        return Inventory(
            total = installed.size,
            system = installed.count { it.system },
            frozen = installed.filter { it.frozen }.map { it.name }.sorted(),
            byRisk = porRisco,
            unknown = desconhecidos.sortedWith(compareBy<Pkg>({ !it.system }, { it.name })),
            complete = complete,
        )
    }
}
