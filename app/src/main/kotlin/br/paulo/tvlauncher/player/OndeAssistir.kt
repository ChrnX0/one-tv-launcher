package br.paulo.tvlauncher.player

import org.json.JSONObject

/**
 * Ideia 82 "Onde assistir" (14/11/2026), só lógica pura (testada em OndeAssistirTest):
 *  - na ficha, uma linha discreta "Também em: Netflix · Prime Video";
 *  - na Busca, o que NÃO está na lista de canais: "Não está na sua lista · está na Netflix", com OK abrindo a busca do app.
 * Dados do TMDB (watch/providers, que vêm do JustWatch: a atribuição aparece junto) no país da TV, Brasil por padrão.
 * Só assinatura e grátis (flatrate, free, ads); aluguel e compra ficam de fora.
 */
object OndeAssistir {
    /**
     * DESLIGADO desde 16/11/2026 (app vai ser vendido). Os provedores do TMDB vêm da JustWatch, e a JustWatch só libera
     * os dados para parceiros com contrato (apis.justwatch.com/docs/api); o TMDB exige atribuição e não diz que o uso
     * comercial está coberto (developer.themoviedb.org/reference/movie-watch-providers). Ligar só com resposta POR
     * ESCRITO do TMDB (no contrato comercial, plano Ultra) ou da JustWatch. Desligado: a ficha não mostra "Também em" e a
     * Busca não mostra "Em outros lugares"; a Busca continua oferecendo procurar no YouTube, Netflix e Prime Video.
     */
    const val JUSTWATCH_LIBERADO = false
    const val MAX_PROVEDORES = 4
    const val MAX_FORA = 5

    fun urlProvedores(chave: String, id: Int, serie: Boolean) =
        InfoExterna.tmdbUrl(if (serie) "/tv/$id/watch/providers" else "/movie/$id/watch/providers", chave)

    fun urlBusca(chave: String, termo: String, idioma: String) =
        InfoExterna.tmdbUrl("/search/multi", chave, linkedMapOf("query" to termo, "language" to idioma, "include_adult" to "false"))

    /** Os serviços de assinatura/grátis no [pais], na ordem do TMDB (display_priority), sem repetir. */
    fun lerProvedores(json: String, pais: String): List<String> = runCatching {
        val p = JSONObject(json).optJSONObject("results")?.optJSONObject(pais) ?: return emptyList()
        val todos = ArrayList<Pair<Int, String>>()
        for (tipo in listOf("flatrate", "free", "ads")) {
            val a = p.optJSONArray(tipo) ?: continue
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
                o.optString("provider_name").trim().takeIf { it.isNotEmpty() }?.let { todos += o.optInt("display_priority", 999) to limparNome(it) }
            }
        }
        todos.sortedBy { it.first }.map { it.second }.distinct().take(MAX_PROVEDORES)
    }.getOrDefault(emptyList())

    /**
     * "Netflix basic with Ads" e "Amazon Prime Video with Ads" viram o nome do serviço; "Apple TV Amazon Channel" (o canal
     * de um serviço dentro de outro) vira o serviço, para não repetir "Apple TV · Apple TV Amazon" (visto em 16/11/2026).
     */
    // "Netflix Standard with Ads" virava "Netflix Standard" ao lado de "Netflix" (Busca, Sala 16/11/2026): o plano sai também.
    fun limparNome(n: String): String = n.replace(Regex("(?i)\\s+((basic|standard)\\s+)?with\\s+ads$"), "")
        .replace(Regex("(?i)^netflix\\s+(basic|standard|premium)$"), "Netflix")
        .replace(Regex("(?i)\\s+(amazon|apple\\s*tv|roku\\s+premium)\\s+channels?$"), "")
        .replace(Regex("(?i)^amazon\\s+prime video$"), "Prime Video").trim()

    /**
     * O app de TV de cada serviço, para o OK abrir a busca nele. Só os pacotes conferidos (os mesmos da busca que continua
     * nos apps, BuscaOutrosApps); os outros serviços aparecem só como texto.
     */
    fun pacote(nome: String): String? = when (nome.lowercase()) {
        "netflix" -> "com.netflix.ninja"
        "prime video", "amazon prime video", "amazon video" -> "com.amazon.amazonvideo.livingroom"
        "youtube", "youtube premium" -> "com.google.android.youtube.tv"
        else -> null
    }

    data class Achado(val serie: Boolean, val tmdb: Int, val nome: String, val ano: Int?)

    /** /search/multi: só filmes e séries, na ordem do TMDB. */
    fun lerBusca(json: String): List<Achado> = runCatching {
        val r = JSONObject(json).optJSONArray("results") ?: return emptyList()
        (0 until r.length()).mapNotNull { i ->
            val o = r.optJSONObject(i) ?: return@mapNotNull null
            val serie = when (o.optString("media_type")) { "tv" -> true; "movie" -> false; else -> return@mapNotNull null }
            val id = o.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val nome = o.optString(if (serie) "name" else "title").trim().ifEmpty { return@mapNotNull null }
            val ano = o.optString(if (serie) "first_air_date" else "release_date").take(4).toIntOrNull()
            Achado(serie, id, nome, ano)
        }
    }.getOrDefault(emptyList())

    /** Os achados que não estão na lista de canais (pelo id do TMDB), no máximo [MAX_FORA]. */
    fun foraDaLista(achados: List<Achado>, filmes: Set<Int>, series: Set<Int>): List<Achado> =
        achados.filter { if (it.serie) it.tmdb !in series else it.tmdb !in filmes }.distinctBy { it.serie to it.tmdb }.take(MAX_FORA)

    /** O país dos provedores: o da TV, se for um código de 2 letras; senão Brasil. */
    fun pais(regiao: String?): String = regiao?.uppercase()?.takeIf { it.matches(Regex("[A-Z]{2}")) } ?: "BR"
}
