package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.PlayPolicy
import org.json.JSONArray
import org.json.JSONObject

/**
 * Addons no protocolo do Stremio (16/11/2026, decisão do Paulo: "compatível com Stremio, só HTTP"). Lógica pura, sem
 * Android (AddonsProtocoloTest). Protocolo: https://github.com/Stremio/stremio-addon-sdk/blob/master/docs/protocol.md
 *
 * Um addon é um endereço HTTP com `/manifest.json` e rotas `/{recurso}/{tipo}/{id}[/{extras}].json`. Recursos que lemos:
 * `catalog` (listas), `meta` (ficha), `stream` (de onde tocar) e `subtitles`. Os eventos `player`/`library` (rastreadores)
 * NÃO são mandados: ninguém recebe o que se assiste aqui por um addon.
 *
 * Regras da Sofaly, nesta ordem (ver docs/addons.md):
 *  1. Só vídeo por HTTP(S). Fonte de torrent (`infoHash`, magnet), Usenet, arquivo compactado, YouTube e link externo são
 *     ignorados: a Sofaly não tem P2P (veto do Paulo, 11/11/2026) e não abre outros apps a partir de um addon.
 *  2. Addon que se declara P2P (`behaviorHints.p2p`) ou adulto (`behaviorHints.adult`) é recusado na instalação.
 *  3. O app não traz loja nem lista de addons: só se instala colando o endereço (painel ou QR), com o PIN.
 *  4. Ids `tt…` (IMDb) são só ids: a ficha deles vem do próprio addon ou do TMDB (/find), nunca do Cinemeta/IMDb.
 */
object AddonsProtocolo {
    const val MAX_ADDONS = 20
    const val MAX_ITENS_CATALOGO = 200
    const val MAX_FONTES = 60

    /** Os recursos que a Sofaly usa. Na versão da Play não entra `stream` (PlayPolicy.Power.ADDON_STREAMS). */
    fun recursosUteis(play: Boolean): List<String> =
        listOf("catalog", "meta", "stream", "subtitles").filter { it != "stream" || PlayPolicy.allowed(PlayPolicy.Power.ADDON_STREAMS, play) }

    data class CatalogoDecl(val tipo: String, val id: String, val nome: String, val extras: List<String>, val obrigatorios: List<String>,
                            val generos: List<String>) {
        val temBusca get() = "search" in extras
        /**
         * Catálogo que só funciona com um extra obrigatório que não sabemos preencher (ex.: só busca) não vira linha.
         * Gênero obrigatório COM opções vira linha com a primeira opção, como o Stremio faz (ex.: Live Channels, um
         * catálogo por país com gênero "All" obrigatório).
         */
        val listavel get() = obrigatorios.none { it != "skip" && !(it == "genre" && generos.isNotEmpty()) }

        /** Gênero a mandar quando a tela não escolheu nenhum: a primeira opção, só se o gênero for obrigatório. */
        fun generoPadrao(): String? = generos.firstOrNull()?.takeIf { "genre" in obrigatorios }
    }

    data class Recurso(val nome: String, val tipos: List<String>?, val prefixos: List<String>?)

    data class Manifesto(
        val id: String, val versao: String, val nome: String, val descricao: String, val logo: String,
        val tipos: List<String>, val prefixos: List<String>?, val recursos: List<Recurso>, val catalogos: List<CatalogoDecl>,
        val p2p: Boolean, val adulto: Boolean, val configuracaoObrigatoria: Boolean,
    ) {
        fun tem(recurso: String) = recursos.any { it.nome == recurso }

        /** Este addon atende [recurso] para este tipo e id (tipos e prefixos do recurso, ou os gerais do manifesto)? */
        fun atende(recurso: String, tipo: String, id: String): Boolean {
            val r = recursos.firstOrNull { it.nome == recurso } ?: return false
            val tipos = r.tipos ?: this.tipos
            if (tipos.isNotEmpty() && tipo !in tipos) return false
            val pref = r.prefixos ?: prefixos ?: return true
            return pref.any { id.startsWith(it) }
        }
    }

    sealed class Leitura {
        data class Ok(val m: Manifesto) : Leitura()
        data class Recusado(val motivo: Motivo) : Leitura()
    }
    enum class Motivo { INVALIDO, P2P, ADULTO, PRECISA_CONFIGURAR, SEM_RECURSO_UTIL, ENDERECO }

    // ---------------------------------------------------------------- endereço

    /**
     * O endereço base do addon a partir do que a pessoa colou: aceita `https://x/manifest.json`, `https://x/` e o
     * `stremio://x/manifest.json` que os sites dos addons usam no botão "Install" (vira https). Só http(s). Null = inválido.
     */
    fun base(colado: String): String? {
        var u = colado.trim()
        if (u.startsWith("stremio://", ignoreCase = true)) u = "https://" + u.substring("stremio://".length)
        if (!u.startsWith("https://", ignoreCase = true) && !u.startsWith("http://", ignoreCase = true)) return null
        u = u.substringBefore('#')
        if (u.endsWith("/manifest.json")) u = u.removeSuffix("/manifest.json")
        // O servidor sai antes de tirar a "/" do fim: em "https:///", tirar antes deixava "https:" e o teste passava.
        val host = u.substringAfter("://").substringBefore('/').substringBefore('?')
        u = u.trimEnd('/')
        if (host.isEmpty() || ' ' in u) return null
        return u
    }

    fun urlManifesto(base: String) = "$base/manifest.json"

    /** Codifica um pedaço do caminho como o Stremio (encodeURIComponent). */
    fun cod(s: String): String = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")
        .replace("%21", "!").replace("%27", "'").replace("%28", "(").replace("%29", ")").replace("%7E", "~").replace("%2A", "*")

    /** `/{recurso}/{tipo}/{id}[/{extras}].json`; extras na ordem dada, "chave=valor&...". */
    fun url(base: String, recurso: String, tipo: String, id: String, extras: List<Pair<String, String>> = emptyList()): String {
        val e = if (extras.isEmpty()) "" else "/" + extras.joinToString("&") { (k, v) -> "${cod(k)}=${cod(v)}" }
        return "$base/$recurso/${cod(tipo)}/${cod(id)}$e.json"
    }

    // ---------------------------------------------------------------- manifesto

    private fun lista(a: JSONArray?): List<String> = if (a == null) emptyList() else (0 until a.length()).mapNotNull { a.optString(it).takeIf { s -> s.isNotBlank() } }

    fun lerManifesto(texto: String): Leitura {
        val o = runCatching { JSONObject(texto) }.getOrNull() ?: return Leitura.Recusado(Motivo.INVALIDO)
        val id = o.optString("id"); val nome = o.optString("name")
        if (id.isBlank() || nome.isBlank()) return Leitura.Recusado(Motivo.INVALIDO)
        val bh = o.optJSONObject("behaviorHints")
        if (bh?.optBoolean("p2p") == true) return Leitura.Recusado(Motivo.P2P)
        if (bh?.optBoolean("adult") == true) return Leitura.Recusado(Motivo.ADULTO)
        if (bh?.optBoolean("configurationRequired") == true) return Leitura.Recusado(Motivo.PRECISA_CONFIGURAR)
        val tipos = lista(o.optJSONArray("types"))
        val prefixos = o.optJSONArray("idPrefixes")?.let { lista(it) }
        val rs = o.optJSONArray("resources") ?: JSONArray()
        val recursos = (0 until rs.length()).mapNotNull { i ->
            when (val r = rs.opt(i)) {
                is String -> Recurso(r, null, null)
                is JSONObject -> r.optString("name").takeIf { it.isNotBlank() }?.let {
                    Recurso(it, r.optJSONArray("types")?.let(::lista), r.optJSONArray("idPrefixes")?.let(::lista))
                }
                else -> null
            }
        }
        val cs = o.optJSONArray("catalogs") ?: JSONArray()
        val catalogos = (0 until cs.length()).mapNotNull { i ->
            val c = cs.optJSONObject(i) ?: return@mapNotNull null
            val t = c.optString("type"); val cid = c.optString("id")
            if (t.isBlank() || cid.isBlank()) return@mapNotNull null
            val ex = c.optJSONArray("extra")
            val nomesEx = ArrayList<String>(); val obrig = ArrayList<String>(); var generos = emptyList<String>()
            if (ex != null) for (j in 0 until ex.length()) {
                val e = ex.optJSONObject(j) ?: continue
                val n = e.optString("name").takeIf { it.isNotBlank() } ?: continue
                nomesEx += n
                if (e.optBoolean("isRequired")) obrig += n
                if (n == "genre") generos = lista(e.optJSONArray("options"))
            }
            // Formato antigo: extraSupported / extraRequired.
            nomesEx += lista(c.optJSONArray("extraSupported")).filter { it !in nomesEx }
            obrig += lista(c.optJSONArray("extraRequired")).filter { it !in obrig }
            CatalogoDecl(t, cid, c.optString("name").ifBlank { cid }, nomesEx, obrig, generos)
        }
        val m = Manifesto(id, o.optString("version"), nome, o.optString("description"), o.optString("logo"), tipos, prefixos,
            recursos, catalogos, p2p = false, adulto = false, configuracaoObrigatoria = false)
        // Só eventos (player/library) ou nada que a Sofaly use: não instala.
        // Na versão da Play (PlayPolicy.Power.ADDON_STREAMS) um add-on só de `stream` também não tem o que fazer.
        if (recursosUteis(BuildConfig.PLAY).none { m.tem(it) }) return Leitura.Recusado(Motivo.SEM_RECURSO_UTIL)
        return Leitura.Ok(m)
    }

    // ---------------------------------------------------------------- catálogo e ficha

    data class Item(val id: String, val tipo: String, val nome: String, val poster: String, val fundo: String,
                    val ano: String, val sinopse: String, val nota: String, val generos: List<String>)

    data class Video(val id: String, val titulo: String, val temporada: Int, val episodio: Int, val lancado: String,
                     val sinopse: String, val miniatura: String)

    data class Ficha(val item: Item, val videos: List<Video>, val duracao: String)

    private fun item(o: JSONObject, tipoPadrao: String): Item? {
        val id = o.optString("id").takeIf { it.isNotBlank() } ?: return null
        val nome = o.optString("name").takeIf { it.isNotBlank() } ?: return null
        return Item(id, o.optString("type").ifBlank { tipoPadrao }, nome, httpOuVazio(o.optString("poster")),
            httpOuVazio(o.optString("background")), o.optString("releaseInfo").ifBlank { o.opt("year")?.toString().orEmpty() },
            o.optString("description"), o.optString("imdbRating").takeIf { it != "null" }.orEmpty(), lista(o.optJSONArray("genres")))
    }

    private fun httpOuVazio(u: String?): String = u?.takeIf { it.startsWith("https://") || it.startsWith("http://") }.orEmpty()

    fun lerCatalogo(texto: String, tipo: String): List<Item> {
        val a = runCatching { JSONObject(texto).optJSONArray("metas") }.getOrNull() ?: return emptyList()
        return (0 until minOf(a.length(), MAX_ITENS_CATALOGO)).mapNotNull { a.optJSONObject(it)?.let { o -> item(o, tipo) } }
            .distinctBy { it.id }
    }

    fun lerFicha(texto: String, tipo: String): Ficha? {
        val o = runCatching { JSONObject(texto).optJSONObject("meta") }.getOrNull() ?: return null
        val it = item(o, tipo) ?: return null
        val vs = o.optJSONArray("videos")
        val videos = if (vs == null) emptyList() else (0 until vs.length()).mapNotNull { i ->
            val v = vs.optJSONObject(i) ?: return@mapNotNull null
            val vid = v.optString("id").takeIf { s -> s.isNotBlank() } ?: return@mapNotNull null
            Video(vid, v.optString("title").ifBlank { v.optString("name") }, v.optInt("season", v.optInt("seasonNumber", 0)),
                v.optInt("episode", v.optInt("number", 0)), v.optString("released"), v.optString("overview"),
                httpOuVazio(v.optString("thumbnail")))
        }.sortedWith(compareBy({ if (it.temporada == 0) Int.MAX_VALUE else it.temporada }, { it.episodio }))
        return Ficha(it, videos, o.optString("runtime"))
    }

    /** O episódio depois de [id] na ordem da ficha (temporadas em ordem, especiais no fim). Null = era o último. */
    fun proximo(videos: List<Video>, id: String): Video? {
        val i = videos.indexOfFirst { it.id == id }
        if (i < 0) return null
        val prox = videos.getOrNull(i + 1) ?: return null
        // Do fim da última temporada não pula para os especiais (temporada 0): especial não é "o próximo".
        return prox.takeUnless { it.temporada == 0 && videos[i].temporada != 0 }
    }

    /** Id do vídeo de um episódio no padrão do Stremio para ids do IMDb: `tt123:1:2` (série, temporada, episódio). */
    fun idEpisodio(serie: String, temporada: Int, episodio: Int) = "$serie:$temporada:$episodio"

    // ---------------------------------------------------------------- fontes e legendas

    data class Fonte(val url: String, val nome: String, val descricao: String, val cabecalhos: Map<String, String>,
                     val grupoMaratona: String, val arquivo: String)

    data class Legenda(val url: String, val idioma: String, val id: String)

    /**
     * Fontes tocáveis: só `url` http(s). Tudo o mais (infoHash, nzbUrl, ytId, externalUrl, arquivos) fica de fora. Os
     * cabeçalhos de `behaviorHints.proxyHeaders.request` seguem (alguns servidores pedem User-Agent).
     */
    fun lerFontes(texto: String): List<Fonte> {
        val a = runCatching { JSONObject(texto).optJSONArray("streams") }.getOrNull() ?: return emptyList()
        val saida = ArrayList<Fonte>()
        for (i in 0 until a.length()) {
            val s = a.optJSONObject(i) ?: continue
            if (s.has("infoHash") || s.has("nzbUrl")) continue
            val u = s.optString("url")
            if (!(u.startsWith("https://") || u.startsWith("http://"))) continue
            val bh = s.optJSONObject("behaviorHints")
            val req = bh?.optJSONObject("proxyHeaders")?.optJSONObject("request")
            val cab = HashMap<String, String>()
            req?.keys()?.forEach { k -> req.optString(k).takeIf { it.isNotEmpty() && cabecalhoPermitido(k) }?.let { cab[k] = it } }
            saida += Fonte(u, s.optString("name").ifBlank { s.optString("title").lineSequence().firstOrNull().orEmpty() },
                s.optString("description").ifBlank { s.optString("title") }, cab, bh?.optString("bingeGroup").orEmpty(),
                bh?.optString("filename").orEmpty())
            if (saida.size >= MAX_FONTES) break
        }
        return saida.distinctBy { it.url }
    }

    /** Cabeçalhos que um addon pode pedir: nunca os que mexem com sessão ou destino (Cookie, Host, Authorization...). */
    fun cabecalhoPermitido(nome: String): Boolean = nome.lowercase() in setOf("user-agent", "referer", "origin", "accept-language")

    fun lerLegendas(texto: String): List<Legenda> {
        val a = runCatching { JSONObject(texto).optJSONArray("subtitles") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val s = a.optJSONObject(i) ?: return@mapNotNull null
            val u = s.optString("url").takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: return@mapNotNull null
            Legenda(u, s.optString("lang"), s.optString("id"))
        }.distinctBy { it.url }
    }

    /**
     * Para o próximo episódio: a fonte do mesmo "grupo de maratona" (mesmo addon e qualidade) da que tocou, como o Stremio
     * faz com `bingeGroup`. Sem grupo igual: null (a pessoa escolhe de novo).
     */
    fun mesmaMaratona(fontes: List<Fonte>, grupo: String): Fonte? =
        if (grupo.isEmpty()) null else fontes.firstOrNull { it.grupoMaratona == grupo }

    // ---------------------------------------------------------------- guardar

    data class Instalado(val base: String, val m: Manifesto)

    fun paraJson(lista: List<Instalado>): String = JSONArray().apply {
        lista.forEach { put(JSONObject().put("base", it.base).put("manifesto", manifestoJson(it.m))) }
    }.toString()

    fun deJson(texto: String?): List<Instalado> {
        val a = runCatching { JSONArray(texto ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val b = base(o.optString("base")) ?: return@mapNotNull null
            (lerManifesto(o.optJSONObject("manifesto")?.toString() ?: return@mapNotNull null) as? Leitura.Ok)?.let { Instalado(b, it.m) }
        }.distinctBy { it.base }.take(MAX_ADDONS)
    }

    /** O manifesto de volta em JSON (o mesmo formato do protocolo, para [lerManifesto] reler). */
    fun manifestoJson(m: Manifesto): JSONObject = JSONObject().apply {
        put("id", m.id); put("version", m.versao); put("name", m.nome); put("description", m.descricao); put("logo", m.logo)
        put("types", JSONArray(m.tipos)); m.prefixos?.let { put("idPrefixes", JSONArray(it)) }
        put("resources", JSONArray().apply {
            m.recursos.forEach { r ->
                put(JSONObject().put("name", r.nome).apply {
                    r.tipos?.let { put("types", JSONArray(it)) }; r.prefixos?.let { put("idPrefixes", JSONArray(it)) }
                })
            }
        })
        put("catalogs", JSONArray().apply {
            m.catalogos.forEach { c ->
                put(JSONObject().put("type", c.tipo).put("id", c.id).put("name", c.nome).put("extra", JSONArray().apply {
                    c.extras.forEach { e ->
                        put(JSONObject().put("name", e).put("isRequired", e in c.obrigatorios).apply {
                            if (e == "genre" && c.generos.isNotEmpty()) put("options", JSONArray(c.generos))
                        })
                    }
                }))
            }
        })
    }

    /** Instala (ou atualiza, se a base já existe) respeitando o limite. Null = cheio. */
    fun instalar(lista: List<Instalado>, novo: Instalado): List<Instalado>? {
        val sem = lista.filter { it.base != novo.base && it.m.id != novo.m.id }
        if (sem.size >= MAX_ADDONS) return null
        return sem + novo
    }
}

/**
 * Ficha de ids `tt…` sem Cinemeta (que usa dados do IMDb, proibidos aqui por licença): o TMDB acha o título pelo id
 * (/find, external_source=imdb_id) e dá temporadas e episódios. Do IMDb só passa o número do id. Puro (AddonsProtocoloTest).
 */
object AddonsTmdb {
    private const val IMAGEM = "https://image.tmdb.org/t/p/"
    private fun imagem(caminho: String?, tam: String): String =
        caminho?.takeIf { it.matches(Regex("/[A-Za-z0-9_-]+\\.(jpg|png)")) }?.let { "$IMAGEM$tam$it" }.orEmpty()

    fun urlFind(chave: String, imdb: String, idioma: String): String? {
        if (!InfoExterna.idImdbValido(imdb)) return null
        return InfoExterna.tmdbUrl("/find/$imdb", chave, linkedMapOf("external_source" to "imdb_id", "language" to idioma))
    }

    data class Achado(val tmdb: Int, val serie: Boolean, val item: AddonsProtocolo.Item)

    /** O título que o /find achou; [tipo] = "series" prefere tv_results, senão movie_results. */
    fun lerFind(json: String, imdb: String, tipo: String): Achado? = runCatching {
        val o = JSONObject(json)
        val serie = tipo == "series"
        val ordem = if (serie) listOf("tv_results", "movie_results") else listOf("movie_results", "tv_results")
        for (k in ordem) {
            val r = o.optJSONArray(k)?.optJSONObject(0) ?: continue
            val ehSerie = k == "tv_results"
            val nome = r.optString(if (ehSerie) "name" else "title")
            if (nome.isBlank()) continue
            val data = r.optString(if (ehSerie) "first_air_date" else "release_date")
            return@runCatching Achado(r.optInt("id"), ehSerie, AddonsProtocolo.Item(imdb, if (ehSerie) "series" else "movie", nome,
                imagem(r.optString("poster_path"), "w342"), imagem(r.optString("backdrop_path"), "w780"), data.take(4),
                r.optString("overview"), "", emptyList()))
        }
        null
    }.getOrNull()

    fun urlSerie(chave: String, id: Int, idioma: String) = InfoExterna.tmdbUrl("/tv/$id", chave, linkedMapOf("language" to idioma))
    fun urlTemporada(chave: String, id: Int, temporada: Int, idioma: String) =
        InfoExterna.tmdbUrl("/tv/$id/season/$temporada", chave, linkedMapOf("language" to idioma))

    /** Números das temporadas (a 0, de especiais, vai para o fim). */
    fun lerTemporadas(json: String): List<Int> = runCatching {
        val a = JSONObject(json).optJSONArray("seasons") ?: return@runCatching emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optInt("season_number", -1)?.takeIf { n -> n >= 0 } }
            .distinct().sortedBy { if (it == 0) Int.MAX_VALUE else it }
    }.getOrDefault(emptyList())

    /** Episódios de uma temporada, com o id de vídeo do Stremio (`tt…:T:E`), que é o que os addons de fonte esperam. */
    fun lerEpisodios(json: String, imdb: String, temporada: Int): List<AddonsProtocolo.Video> = runCatching {
        val a = JSONObject(json).optJSONArray("episodes") ?: return@runCatching emptyList()
        (0 until a.length()).mapNotNull { i ->
            val e = a.optJSONObject(i) ?: return@mapNotNull null
            val n = e.optInt("episode_number", -1).takeIf { it > 0 } ?: return@mapNotNull null
            AddonsProtocolo.Video(AddonsProtocolo.idEpisodio(imdb, temporada, n), e.optString("name"), temporada, n,
                e.optString("air_date"), e.optString("overview"), imagem(e.optString("still_path"), "w300"))
        }
    }.getOrDefault(emptyList())
}
