package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Ideia 58: a ferramenta MCP "diario_tv" ("o que aconteceu na TV?"). Só leitura. Arquivo próprio, como o McpIdeias. */
object McpDiario {
    val LISTA: List<JSONObject> = listOf(
        JSONObject().put("name", "diario_tv")
            .put("description", "O que aconteceu na TV: linha do tempo do que mexeu nela (painel do celular, Claude pelo MCP, " +
                "adb e a própria TV, como o backup), da mais nova à mais velha, até 7 dias.")
            .put("inputSchema", JSONObject().put("type", "object").put("properties", JSONObject()
                .put("horas", JSONObject().put("type", "integer").put("minimum", 1).put("maximum", 168).put("description", "padrão 24"))
                .put("origem", JSONObject().put("type", "string").put("enum", JSONArray(DiarioRegras.ORIGENS)))))
            .put("annotations", JSONObject().put("readOnlyHint", true)))

    fun conhece(nome: String) = LISTA.any { it.optString("name") == nome }

    fun chamar(ctx: Context, nome: String, a: JSONObject): String = when (nome) {
        "diario_tv" -> JSONObject().put("itens", Diario.ler(ctx, a.optInt("horas", 24),
            a.optString("origem").takeIf { it in DiarioRegras.ORIGENS })).toString()
        else -> throw McpProtocolo.ErroFerramenta("ferramenta desconhecida")
    }
}
