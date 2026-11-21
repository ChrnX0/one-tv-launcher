package br.paulo.tvlauncher.player

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

    // Nome em outra escrita (Sala, 17/11/2026: o Elenco de Duna mostrava "張震" numa TV em português). O TMDB guarda o
    // nome original; os outros ("Chang Chen") ficam em also_known_as da pessoa.
    private val IDIOMAS_OUTRA_ESCRITA = setOf("ja", "ko", "zh", "ru", "ar", "hi", "uk", "el", "he", "th", "bg", "sr")
    private fun letraLatina(c: Char) = c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN

    /** O nome precisa de uma versão em letras latinas? (TV num idioma de escrita latina e nome sem letra latina.) Puro. */
    fun precisaLatino(nome: String, idioma: String): Boolean =
        idioma.substringBefore('-').lowercase() !in IDIOMAS_OUTRA_ESCRITA && nome.any { it.isLetter() } && nome.none(::letraLatina)

    fun urlPessoa(chave: String, pessoa: Int): String = InfoExterna.tmdbUrl("/person/$pessoa", chave, linkedMapOf())

    /** O 1º "also_known_as" só com letras latinas, de /person/{id}. null se não houver. Puro. */
    fun lerNomeLatino(json: String): String? = runCatching {
        val a = JSONObject(json).optJSONArray("also_known_as") ?: return null
        (0 until a.length()).map { a.optString(it).trim() }
            .firstOrNull { n -> n.length in 2..60 && n.any(::letraLatina) && n.filter { it.isLetter() }.all(::letraLatina) }
    }.getOrNull()

    /** Os títulos em que a pessoa atuou: (é série, id TMDB). */
    fun lerCreditos(json: String): Set<Pair<Boolean, Int>> = runCatching {
        val c = JSONObject(json).optJSONArray("cast") ?: return emptySet()
        (0 until c.length()).mapNotNull { i ->
            val o = c.optJSONObject(i) ?: return@mapNotNull null
            val serie = when (o.optString("media_type")) { "tv" -> true; "movie" -> false; else -> return@mapNotNull null }
            o.optInt("id").takeIf { it > 0 }?.let { serie to it }
        }.toSet()
    }.getOrDefault(emptySet())

    /**
     * Os créditos da pessoa: ids e também nome+ano de cada título. Só pelo id quase nada aparecia ("Nada mais com esta
     * pessoa" para o Timothée Chalamet com Duna: Parte Dois na lista, Sala 17/11/2026): a maioria dos títulos da lista
     * não traz o id do TMDB. O nome+ano é o mesmo par que a coleção da ficha já usa.
     */
    data class Creditos(val ids: Set<Pair<Boolean, Int>>, val nomes: Set<String>)

    fun lerCreditosCompletos(json: String): Creditos? = runCatching {
        val c = JSONObject(json).optJSONArray("cast") ?: return null
        val ids = HashSet<Pair<Boolean, Int>>()
        val nomes = HashSet<String>()
        for (i in 0 until c.length()) {
            val o = c.optJSONObject(i) ?: continue
            val serie = when (o.optString("media_type")) { "tv" -> true; "movie" -> false; else -> continue }
            o.optInt("id").takeIf { it > 0 }?.let { ids += serie to it }
            val ano = o.optString(if (serie) "first_air_date" else "release_date").take(4).toIntOrNull() ?: continue
            for (campo in if (serie) listOf("name", "original_name") else listOf("title", "original_title"))
                o.optString(campo).takeIf { it.isNotBlank() }?.let { nomes += chaveNome(serie, it, ano) }
        }
        Creditos(ids, nomes).takeIf { ids.isNotEmpty() || nomes.isNotEmpty() }
    }.getOrNull()

    fun chaveNome(serie: Boolean, nome: String, ano: Int): String = (if (serie) "s|" else "f|") + Idioma.chaveTitulo(nome) + "|" + ano

    /** Um título da lista de canais (com o id do TMDB quando a lista ou o cache sabem). */
    data class NaLista(val serie: Boolean, val tmdb: Int?, val nome: String, val ano: Int?)

    /**
     * Os títulos da lista que a pessoa fez, pelo id ou, sem id, pelo nome+ano (sem ano não arrisca: refilmagens), sem o
     * que está aberto agora ([fora], e [foraNome] para a versão dele sem id) e sem repetir versões (dublado e legendado
     * do mesmo título viram um só). Mais novos primeiro.
     */
    fun naLista(creditos: Creditos, catalogo: List<NaLista>, fora: Pair<Boolean, Int>?, foraNome: String? = null): List<NaLista> {
        val foraChave = foraNome?.let { Idioma.chaveTitulo(it) }
        return catalogo.filter { t ->
            val id = t.tmdb
            val achou = (id != null && (t.serie to id) in creditos.ids) ||
                (t.ano != null && chaveNome(t.serie, t.nome, t.ano) in creditos.nomes)
            achou && (id == null || (t.serie to id) != fora) && !(t.serie == fora?.first && foraChave != null && Idioma.chaveTitulo(t.nome) == foraChave)
        }.distinctBy { t -> t.serie to (Idioma.chaveTitulo(t.nome) + "|" + (t.ano ?: 0)) }
            .distinctBy { t -> t.tmdb?.let { t.serie to it } ?: t }
            .sortedByDescending { it.ano ?: 0 }
    }
}
