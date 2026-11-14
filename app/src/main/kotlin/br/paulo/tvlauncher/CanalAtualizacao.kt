package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Canal de atualização (14/11/2026, ideia 62c). Puro (só org.json), com teste JVM.
 *
 * O workflow publica com canal "teste" como pré-lançamento (prerelease). O `/releases/latest` do GitHub ignora
 * pré-lançamentos e rascunhos (li na documentação da API de Releases), então as TVs no canal normal nem enxergam a
 * versão de teste. A TV com o canal de teste ligado lê a lista `/releases` e fica com a MAIOR versão publicada,
 * pré-lançamento ou não. Depois de uns dias sem queda nela, o dono roda "Promover para todos" (promover.yml).
 */
object CanalAtualizacao {
    /** Endereço da API que o Atualizador pede, conforme o canal. */
    fun url(repo: String, canalTeste: Boolean): String =
        if (canalTeste) "https://api.github.com/repos/$repo/releases?per_page=$POR_PAGINA"
        else "https://api.github.com/repos/$repo/releases/latest"

    const val POR_PAGINA = 15

    /** versionCode lido da tag "v<número>", ou null se a tag estiver fora do formato. */
    fun codigo(tag: String?): Long? = tag?.takeIf { it.startsWith("v") }?.drop(1)?.toLongOrNull()

    /**
     * Escolhe o Release a usar a partir da resposta da API: objeto (`/latest`) ou lista (`/releases`).
     * Na lista: ignora rascunhos e tags fora do formato e fica com o maior versionCode. null = nenhum serve.
     */
    fun escolher(resposta: String): JSONObject? {
        val t = resposta.trimStart()
        if (t.startsWith("{")) return JSONObject(t)
        val lista = JSONArray(t)
        var melhor: JSONObject? = null
        var melhorCodigo = Long.MIN_VALUE
        for (i in 0 until lista.length()) {
            val r = lista.optJSONObject(i) ?: continue
            if (r.optBoolean("draft")) continue
            val c = codigo(r.optString("tag_name")) ?: continue
            if (c > melhorCodigo) { melhor = r; melhorCodigo = c }
        }
        return melhor
    }
}
