package br.paulo.tvlauncher

import org.json.JSONObject

/**
 * Lista curada de add-ons (18/11/2026, decisão do Paulo: "lista curada nossa + adicionar fácil"). Lógica pura
 * (RecommendedAddonsTest).
 *
 * Por que curada e curta: conferimos os 97 add-ons da lista oficial do Stremio em 18/11/2026 e nenhum entrega vídeo por
 * link direto legal (os de vídeo de lá são torrent, que a Sofaly recusa). Os que funcionam aqui são catálogos: mostram
 * o que há em cada serviço e, sem fonte para tocar, a tela oferece "Onde assistir" (busca do Google TV).
 * Critérios para entrar: sem configuração, responde, conteúdo legal, nada de IMDb/Cinemeta/Rotten Tomatoes (licença,
 * ver docs/addons.md) e nada com "torrent" no nome.
 *
 * A lista vem embutida ([BUILT_IN]) e o servidor (`GET https://api.sofaly.tv/v1/addons/recommended`, campos em inglês)
 * pode trocá-la sem Release. O que vem do servidor também passa pelas travas da instalação (AddonsProtocolo): a lista
 * só sugere endereços, não libera nada.
 */
object RecommendedAddons {
    const val URL = "https://api.sofaly.tv/v1/addons/recommended"
    const val MAX = 30
    /** De quanto em quanto tempo a TV pergunta ao servidor (a lista muda raramente). */
    const val REFRESH_MS = 24L * 60 * 60 * 1000

    /** [id] = id do manifesto (chave do texto traduzido `ad_sug_<chave>`); [kind] = "catalog" ou "subtitles". */
    data class Entry(val id: String, val url: String, val kind: String)

    val BUILT_IN = listOf(
        Entry("pw.ers.netflix-catalog", "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json", "catalog"),
        Entry("tmdb-addon", "https://94c8cb9f702d-tmdb-addon.baby-beamup.club/manifest.json", "catalog"),
        Entry("com.stremio.mubi_app", "https://mubi2stremio.adiba.ro/manifest.json", "catalog"),
        Entry("community.anime.kitsu", "https://anime-kitsu.strem.fun/manifest.json", "catalog"),
        Entry("animes-season-addon", "https://victorgveloso.github.io/animes-season-addon/manifest.json", "catalog"),
    )

    /** Texto curto traduzido para os add-ons que conhecemos (chave do recurso `ad_sug_<isto>`); null = usar o do manifesto. */
    fun textKey(id: String): String? = when (id) {
        "pw.ers.netflix-catalog" -> "streaming"
        "tmdb-addon" -> "tmdb"
        "com.stremio.mubi_app" -> "mubi"
        "community.anime.kitsu" -> "kitsu"
        "animes-season-addon" -> "animeseason"
        else -> null
    }

    /** Nome que aparece antes de instalar (o manifesto só é lido na instalação). */
    fun name(id: String): String? = when (id) {
        "pw.ers.netflix-catalog" -> "Streaming Catalogs"
        "tmdb-addon" -> "The Movie Database"
        "com.stremio.mubi_app" -> "MUBI"
        "community.anime.kitsu" -> "Anime Kitsu"
        "animes-season-addon" -> "Anime Seasons"
        else -> null
    }

    /**
     * Resposta do servidor: `{"addons":[{"id","url","kind","name"?}]}`. Só https terminando em manifest.json, sem
     * repetir id, no máximo [MAX]. Resposta estragada ou vazia = null (fica a lista anterior/embutida).
     */
    fun parse(text: String?): List<Entry>? = runCatching {
        val a = JSONObject(text ?: return null).optJSONArray("addons") ?: return null
        val out = ArrayList<Entry>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val id = o.optString("id").trim(); val url = o.optString("url").trim()
            val kind = o.optString("kind").trim().ifEmpty { "catalog" }
            if (id.isEmpty() || id.length > 100 || out.any { it.id == id }) continue
            if (!url.startsWith("https://") || !url.endsWith("/manifest.json") || url.length > 500) continue
            if (url.contains("torrent", ignoreCase = true) || id.contains("torrent", ignoreCase = true)) continue
            if (kind !in setOf("catalog", "subtitles", "stream")) continue
            out += Entry(id, url, kind)
            if (out.size >= MAX) break
        }
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /** O que mostrar: a lista menos o que já está instalado (pelo id do manifesto ou pelo mesmo endereço). */
    fun toShow(list: List<Entry>, installedIds: Set<String>, installedBases: Set<String>): List<Entry> =
        list.filter { e -> e.id !in installedIds && AddonsProtocolo.base(e.url) !in installedBases }
}
