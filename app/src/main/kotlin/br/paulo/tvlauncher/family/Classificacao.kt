package br.paulo.tvlauncher.family

import br.paulo.tvlauncher.player.IdadesTmdb
import br.paulo.tvlauncher.player.InfoExterna
import br.paulo.tvlauncher.player.PlayerFiltro
import org.json.JSONObject

/**
 * Ideia 91 (14/11/2026): no modo infantil, filmes e séries pela classificação indicativa, além das categorias infantis.
 * Só lógica pura (testada em ClassificacaoTest); quem pede ao TMDB e guarda é o [IdadesTmdb].
 *
 * A regra, com uma idade máxima escolhida (0 = Livre; [DESLIGADA] = como antes, só categorias):
 *  - categoria escondida, adulta ou bloqueada no controle parental: nunca aparece;
 *  - título COM classificação conhecida: aparece se ela for até a idade máxima (de qualquer categoria, e some mesmo de
 *    uma categoria infantil se passar dela);
 *  - título SEM classificação (o TMDB não tem, ou ainda não perguntamos): só aparece se a categoria for infantil.
 *
 * A classificação é a do Brasil no TMDB; sem ela, a dos EUA convertida para a idade brasileira mais próxima PARA CIMA
 * (na dúvida, mais restrita).
 */
object Classificacao {
    const val DESLIGADA = -1
    /** As idades que o dono escolhe (as faixas da classificação indicativa do Brasil). */
    val OPCOES = listOf(DESLIGADA, 0, 10, 12, 14, 16)
    /** Sem classificação no TMDB (já perguntado): vale como desconhecida. */
    const val SEM = -1

    fun urlFilme(chave: String, id: Int) = InfoExterna.tmdbUrl("/movie/$id/release_dates", chave)
    fun urlSerie(chave: String, id: Int) = InfoExterna.tmdbUrl("/tv/$id/content_ratings", chave)

    private val EUA = mapOf(
        "G" to 0, "PG" to 10, "PG-13" to 14, "R" to 16, "NC-17" to 18,
        "TV-Y" to 0, "TV-G" to 0, "TV-Y7" to 10, "TV-Y7-FV" to 10, "TV-PG" to 10, "TV-14" to 14, "TV-MA" to 18,
    )

    /** "L", "AL", "10", "A12"... para a idade; nulo se não for uma classificação brasileira. */
    fun idadeBr(c: String): Int? {
        val t = c.trim().uppercase()
        if (t == "L" || t == "AL" || t == "LIVRE") return 0
        return t.removePrefix("A").toIntOrNull()?.takeIf { it in setOf(10, 12, 14, 16, 18) }
    }

    fun idadeEua(c: String): Int? = EUA[c.trim().uppercase()]

    private fun escolher(porPais: Map<String, List<String>>): Int {
        porPais["BR"]?.mapNotNull { idadeBr(it) }?.maxOrNull()?.let { return it }
        porPais["US"]?.mapNotNull { idadeEua(it) }?.maxOrNull()?.let { return it }
        return SEM
    }

    /** /movie/{id}/release_dates: results[{iso_3166_1, release_dates[{certification}]}]. */
    fun lerFilme(json: String): Int? = runCatching {
        val r = JSONObject(json).optJSONArray("results") ?: return null
        val m = HashMap<String, List<String>>()
        for (i in 0 until r.length()) {
            val o = r.optJSONObject(i) ?: continue
            val d = o.optJSONArray("release_dates") ?: continue
            m[o.optString("iso_3166_1")] = (0 until d.length()).mapNotNull { d.optJSONObject(it)?.optString("certification")?.takeIf { c -> c.isNotBlank() } }
        }
        escolher(m)
    }.getOrNull()

    /** /tv/{id}/content_ratings: results[{iso_3166_1, rating}]. */
    fun lerSerie(json: String): Int? = runCatching {
        val r = JSONObject(json).optJSONArray("results") ?: return null
        val m = HashMap<String, List<String>>()
        for (i in 0 until r.length()) {
            val o = r.optJSONObject(i) ?: continue
            o.optString("rating").takeIf { it.isNotBlank() }?.let { m[o.optString("iso_3166_1")] = m[o.optString("iso_3166_1")].orEmpty() + it }
        }
        escolher(m)
    }.getOrNull()

    /**
     * O título aparece no modo infantil com idade máxima [max]? [duras] = categorias que nunca aparecem (escondidas,
     * adultas, controle parental); [infantis] = as marcadas como infantis; [idade] = a classificação (nulo ou [SEM]: não se sabe).
     */
    fun visivel(grupo: String, idade: Int?, max: Int, infantis: Set<String>, duras: Set<String>): Boolean {
        val g = grupo.trim()
        if (g in duras || PlayerFiltro.ehAdulto(g)) return false
        if (idade != null && idade >= 0) return idade <= max
        return g in infantis
    }
}
