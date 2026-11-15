package br.paulo.tvlauncher

import org.json.JSONObject

/**
 * Ideias 83 e 84 (14/11/2026), só lógica pura (testada em ElencoTmdbTest); a rede fica no InfoExternaCache.
 *  - 83 "Anteriormente": ao voltar a uma série parada há semanas, o resumo do último episódio visto antes do próximo.
 *  - 84 "Quem é esse ator?": elenco com foto e personagem; para cada pessoa, os outros títulos dela que estão na lista
 *    de canais do dono (cruzando o id do TMDB).
 */
object ElencoTmdb {
    // ================================================================ 83: "Anteriormente"

    /** Parada há mais que isto: mostra o resumo. Menos: a pessoa ainda lembra. */
    const val PARADA_MS = 21L * 24 * 3_600_000

    fun urlEpisodio(chave: String, serie: Int, temporada: Int, episodio: Int, idioma: String): String =
        InfoExterna.tmdbUrl("/tv/$serie/season/$temporada/episode/$episodio", chave, linkedMapOf("language" to idioma))

    /** (título, resumo) do episódio no TMDB; nulo sem resumo (o TMDB devolve "" quando não há no idioma). */
    fun lerEpisodio(json: String): Pair<String, String>? = runCatching {
        val o = JSONObject(json)
        val resumo = o.optString("overview").trim()
        if (resumo.isEmpty()) null else o.optString("name").trim() to resumo.take(900)
    }.getOrNull()

    /**
     * O episódio a resumir antes de tocar [vai]: o anterior a ele na ordem (o último que a pessoa viu inteiro). Nulo se
     * [vai] é o primeiro, ou se a série foi vista há menos de [PARADA_MS] ([ultimaVez] = 0: nunca vista, nada a lembrar).
     */
    fun anterior(todos: List<Episodio>, vai: Episodio, ultimaVez: Long, agora: Long): Episodio? {
        if (ultimaVez <= 0 || agora - ultimaVez < PARADA_MS) return null
        val i = todos.indexOfFirst { it.id == vai.id }
        return if (i <= 0) null else todos[i - 1]
    }

    /** A última vez que algum episódio da série foi visto: a maior data entre as posições dela e o último aberto. */
    fun ultimaVez(todos: List<Episodio>, fonteId: String, posicoes: Map<String, Posicao>, ultimo: UltimoEpisodio?): Long =
        maxOf(todos.maxOfOrNull { posicoes[it.chave(fonteId)]?.quando ?: 0L } ?: 0L, ultimo?.quando ?: 0L)

    // ================================================================ 84: "Quem é esse ator?"

    data class Pessoa(val id: Int, val nome: String, val personagem: String, val foto: String)

    const val MAX_ELENCO = 16

    fun urlElenco(chave: String, id: Int, serie: Boolean, idioma: String): String =
        InfoExterna.tmdbUrl(if (serie) "/tv/$id/aggregate_credits" else "/movie/$id/credits", chave, linkedMapOf("language" to idioma))

    fun urlCreditos(chave: String, pessoa: Int, idioma: String): String =
        InfoExterna.tmdbUrl("/person/$pessoa/combined_credits", chave, linkedMapOf("language" to idioma))

    /** Foto pequena (185 px de largura) do TMDB; vazio se a pessoa não tem foto. */
    fun foto(caminho: String?): String =
        caminho?.takeIf { it.matches(Regex("/[A-Za-z0-9_-]+\\.(jpg|png)")) }?.let { "https://image.tmdb.org/t/p/w185$it" }.orEmpty()

    /** O elenco na ordem dos créditos. Série (aggregate_credits): o personagem vem em roles[0]. */
    fun lerElenco(json: String): List<Pessoa> = runCatching {
        val c = JSONObject(json).optJSONArray("cast") ?: return emptyList()
        (0 until c.length()).mapNotNull { i ->
            val o = c.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val nome = o.optString("name").trim().ifEmpty { return@mapNotNull null }
            val papel = o.optString("character").trim().ifEmpty { o.optJSONArray("roles")?.optJSONObject(0)?.optString("character")?.trim().orEmpty() }
            Pessoa(id, nome, papel.take(60), foto(o.optString("profile_path")))
        }.distinctBy { it.id }.take(MAX_ELENCO)
    }.getOrDefault(emptyList())

    /** Os títulos em que a pessoa atuou: (é série, id TMDB). */
    fun lerCreditos(json: String): Set<Pair<Boolean, Int>> = runCatching {
        val c = JSONObject(json).optJSONArray("cast") ?: return emptySet()
        (0 until c.length()).mapNotNull { i ->
            val o = c.optJSONObject(i) ?: return@mapNotNull null
            val serie = when (o.optString("media_type")) { "tv" -> true; "movie" -> false; else -> return@mapNotNull null }
            o.optInt("id").takeIf { it > 0 }?.let { serie to it }
        }.toSet()
    }.getOrDefault(emptySet())

    /** Um título da lista de canais com id TMDB conhecido. */
    data class NaLista(val serie: Boolean, val tmdb: Int, val nome: String, val ano: Int?)

    /**
     * Os títulos da lista que a pessoa fez, sem o que está aberto agora ([fora]) e sem repetir versões (dublado e
     * legendado do mesmo id viram um só). Mais novos primeiro.
     */
    fun naLista(creditos: Set<Pair<Boolean, Int>>, catalogo: List<NaLista>, fora: Pair<Boolean, Int>?): List<NaLista> =
        catalogo.filter { (it.serie to it.tmdb) in creditos && (it.serie to it.tmdb) != fora }
            .distinctBy { it.serie to it.tmdb }.sortedByDescending { it.ano ?: 0 }
}
