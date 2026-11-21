package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.PlayPolicy
import org.json.JSONObject

/**
 * Lista curada de add-ons (18/11/2026, decisão do Paulo: "lista curada nossa + adicionar fácil"). Lógica pura
 * (RecommendedAddonsTest).
 *
 * Por que curada e curta: conferimos os 97 add-ons da lista oficial do Stremio em 18/11/2026 e nenhum entrega vídeo por
 * link direto legal (os de vídeo de lá são torrent, que a Sofaly recusa). Os que funcionam aqui são catálogos: mostram
 * o que há em cada serviço e, sem fonte para tocar, a tela oferece "Onde assistir" (busca do Google TV).
 * 19/11/2026 (escolha do Paulo): entram OpenSubtitles (legendas), Live Channels (canais grátis da Pluto TV, só fora da
 * Play) e o NOSSO add-on de vídeo legal, Public Domain Movies (server/api/src/publicdomain.js, Internet Archive).
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

    /**
     * [id] = id do manifesto (chave do texto traduzido `ad_sug_<chave>`); [kind] = "catalog", "subtitles" ou "stream";
     * [group] = em qual seção aparece (um de [GROUPS]; pedido do Paulo 18/11: "separar por tipo, como o Stremio").
     */
    data class Entry(val id: String, val url: String, val kind: String, val group: String = groupOfKind(kind))

    /** Seções, nesta ordem, na TV e no painel (o painel usa como filtro). Nomes técnicos em inglês; texto em `ad_grupo_*`. */
    val GROUPS = listOf("movies_series", "anime", "tv", "subtitles", "music", "other")

    private fun groupOfKind(kind: String) = if (kind == "subtitles") "subtitles" else "other"

    /**
     * Seção de um add-on pelo manifesto (vale para os instalados, que não vêm da lista): anime vence filme/série;
     * só legenda = legendas. [types] = `types` do manifesto; [resources] = nomes dos recursos.
     */
    fun groupOf(types: List<String>, resources: List<String>): String {
        val t = types.map { it.lowercase() }.toSet()
        return when {
            resources.isNotEmpty() && resources.all { it == "subtitles" } -> "subtitles"
            "anime" in t -> "anime"
            "movie" in t || "series" in t -> "movies_series"
            "tv" in t || "channel" in t || "channels" in t -> "tv"
            "music" in t || "radio" in t || "podcast" in t -> "music"
            else -> "other"
        }
    }

    /** A lista em seções, na ordem de [GROUPS], sem seção vazia. */
    fun grouped(list: List<Entry>): List<Pair<String, List<Entry>>> =
        GROUPS.mapNotNull { g -> list.filter { it.group == g }.takeIf { it.isNotEmpty() }?.let { g to it } }

    val BUILT_IN = listOf(
        Entry("pw.ers.netflix-catalog", "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json", "catalog", "movies_series"),
        Entry("tmdb-addon", "https://94c8cb9f702d-tmdb-addon.baby-beamup.club/manifest.json", "catalog", "movies_series"),
        Entry("com.stremio.mubi_app", "https://mubi2stremio.adiba.ro/manifest.json", "catalog", "movies_series"),
        Entry("tv.sofaly.publicdomain", "https://api.sofaly.tv/v1/addons/public-domain/manifest.json", "stream", "movies_series"),
        Entry("community.anime.kitsu", "https://anime-kitsu.strem.fun/manifest.json", "catalog", "anime"),
        Entry("animes-season-addon", "https://victorgveloso.github.io/animes-season-addon/manifest.json", "catalog", "anime"),
        Entry("org.stremio.opensubtitlesv3", "https://opensubtitles-v3.strem.io/manifest.json", "subtitles", "subtitles"),
        Entry("community.live-channels", "https://jantoom.github.io/live-channels/manifest.json", "stream", "tv"),
    )

    /** Texto curto traduzido para os add-ons que conhecemos (chave do recurso `ad_sug_<isto>`); null = usar o do manifesto. */
    fun textKey(id: String): String? = when (id) {
        "pw.ers.netflix-catalog" -> "streaming"
        "tmdb-addon" -> "tmdb"
        "com.stremio.mubi_app" -> "mubi"
        "community.anime.kitsu" -> "kitsu"
        "animes-season-addon" -> "animeseason"
        "org.stremio.opensubtitlesv3" -> "opensubtitles"
        "tv.sofaly.publicdomain" -> "publicdomain"
        "community.live-channels" -> "livechannels"
        else -> null
    }

    /** Nome que aparece antes de instalar (o manifesto só é lido na instalação). */
    fun name(id: String): String? = when (id) {
        "pw.ers.netflix-catalog" -> "Streaming Catalogs"
        "tmdb-addon" -> "The Movie Database"
        "com.stremio.mubi_app" -> "MUBI"
        "community.anime.kitsu" -> "Anime Kitsu"
        "animes-season-addon" -> "Anime Seasons"
        "org.stremio.opensubtitlesv3" -> "OpenSubtitles"
        "tv.sofaly.publicdomain" -> "Public Domain Movies"
        "community.live-channels" -> "Live Channels"
        else -> null
    }

    /**
     * Resposta do servidor: `{"addons":[{"id","url","kind","group"?}]}`. Só https terminando em manifest.json, sem
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
            val group = o.optString("group").trim().takeIf { it in GROUPS } ?: groupOfKind(kind)
            out += Entry(id, url, kind, group)
            if (out.size >= MAX) break
        }
        out.takeIf { it.isNotEmpty() }
    }.getOrNull()

    /**
     * O que mostrar: a lista menos o que já está instalado (pelo id do manifesto ou pelo mesmo endereço). Na versão da
     * Play ([play]) somem os de [Entry.kind] "stream": lá o recurso `stream` não existe (PlayPolicy.Power.ADDON_STREAMS)
     * e o Paulo decidiu em 19/11/2026 que os canais (Pluto TV via Live Channels) ficam só fora da Play.
     */
    fun toShow(list: List<Entry>, installedIds: Set<String>, installedBases: Set<String>, play: Boolean = false): List<Entry> =
        list.filter { e ->
            e.id !in installedIds && AddonsProtocolo.base(e.url) !in installedBases &&
                (e.kind != "stream" || PlayPolicy.allowed(PlayPolicy.Power.ADDON_STREAMS, play))
        }
}
