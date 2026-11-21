package br.paulo.tvlauncher.player

import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ficha e notas de fora (fatia 2 dos serviços de filmes e séries, 14/11/2026): TMDB (sinopse, elenco, próximo episódio,
 * coleção) e Trakt (nota para todos). Só lógica pura (sem Android), testada em
 * InfoExternaTest; quem fala com a rede e guarda no disco é o [InfoExternaCache].
 *
 * O id de cada título: 1º o `tmdb`/`tmdb_id` que o Xtream manda (lista ou ficha), 2º a busca no TMDB pelo nome limpo +
 * ano. A pessoa corrige com "Não é este" (o id recusado não volta).
 */
object InfoExterna {
    const val TMDB_API = "https://api.themoviedb.org/3"

    /** Quanto tempo a ficha e as notas de fora valem no disco antes de pedir de novo. */
    const val VALIDADE_MS = 30L * 24 * 3_600_000
    /** Série com próximo episódio marcado: a data pode mudar; pede de novo depois de 3 dias. */
    const val VALIDADE_SERIE_NO_AR_MS = 3L * 24 * 3_600_000
    /** Formato da ficha guardada: 2 = com trailer (15/11/2026). Ficha de formato antigo é pedida de novo. */
    const val VERSAO_DETALHE = 3   // 3: diretor do TMDB (16/11/2026)

    // ================================================================ id do Xtream

    /** O id do TMDB que o painel Xtream manda ("tmdb" ou "tmdb_id", número ou texto). Nulo se não veio ou não é número. */
    fun tmdbDoXtream(o: JSONObject): Int? {
        for (k in listOf("tmdb", "tmdb_id")) {
            val v = o.opt(k) ?: continue
            val n = when (v) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull()
                else -> null
            }
            if (n != null && n in 1..99_999_999) return n.toInt()
        }
        return null
    }

    // ================================================================ TMDB

    /** A chave do TMDB pode ser a "Chave da API" (v3, vai no endereço) ou o token de leitura (v4, longo, vai no cabeçalho). */
    fun tmdbEhToken(chave: String): Boolean = chave.length > 40

    private fun cod(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    /** Idioma do TMDB a partir do da TV: "pt-BR", "en-US", "es-ES"... */
    fun idiomaTmdb(l: Locale = Locale.getDefault()): String {
        val lang = l.language.ifBlank { "en" }
        val pais = l.country.ifBlank { if (lang == "pt") "BR" else if (lang == "en") "US" else lang.uppercase() }
        return "$lang-$pais"
    }

    fun tmdbUrl(caminho: String, chave: String, parametros: Map<String, String> = emptyMap()): String {
        val p = LinkedHashMap(parametros)
        if (!tmdbEhToken(chave)) p["api_key"] = chave
        val q = p.entries.joinToString("&") { "${it.key}=${cod(it.value)}" }
        return "$TMDB_API$caminho" + if (q.isEmpty()) "" else "?$q"
    }

    fun tmdbCabecalhos(chave: String, agente: String): Map<String, String> = buildMap {
        put("Accept", "application/json"); put("User-Agent", agente)
        if (tmdbEhToken(chave)) put("Authorization", "Bearer $chave")
    }

    fun urlBusca(chave: String, nome: String, ano: Int?, serie: Boolean, idioma: String): String {
        val p = linkedMapOf("query" to nome, "language" to idioma, "include_adult" to "false")
        if (ano != null) p[if (serie) "first_air_date_year" else "year"] = ano.toString()
        return tmdbUrl(if (serie) "/search/tv" else "/search/movie", chave, p)
    }

    fun urlDetalhe(chave: String, id: Int, serie: Boolean, idioma: String): String =
        tmdbUrl(if (serie) "/tv/$id" else "/movie/$id", chave,
            linkedMapOf("language" to idioma, "append_to_response" to if (serie) "external_ids,aggregate_credits,videos" else "external_ids,credits,videos",
                // Trailer: no idioma da TV, senão o inglês, senão o sem idioma (o TMDB filtra pelo "language" sem isto).
                "include_video_language" to listOf(idioma.substringBefore('-'), "en", "null").distinct().joinToString(",")))

    fun urlColecao(chave: String, id: Int, idioma: String): String = tmdbUrl("/collection/$id", chave, linkedMapOf("language" to idioma))

    /** Um resultado da busca do TMDB. */
    data class Candidato(val id: Int, val titulo: String, val original: String, val ano: Int?, val popularidade: Double)

    fun lerBusca(json: String, serie: Boolean): List<Candidato> {
        val a = runCatching { JSONObject(json).optJSONArray("results") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            val data = o.optString(if (serie) "first_air_date" else "release_date")
            Candidato(id, o.optString(if (serie) "name" else "title"), o.optString(if (serie) "original_name" else "original_title"),
                data.take(4).toIntOrNull()?.takeIf { it in 1880..2100 }, o.optDouble("popularity", 0.0))
        }
    }

    /**
     * Escolhe o candidato certo para "[nome] ([ano])". Precisa casar o nome (o traduzido ou o original, pela mesma chave
     * do par dublado x legendado, [Idioma.chaveTitulo]); com ano, aceita 1 de diferença (lançamento em outro país).
     * Mais de um casando: o de ano exato, depois o mais popular. Nenhum: null (melhor sem ficha que a ficha errada).
     */
    fun escolher(candidatos: List<Candidato>, nome: String, ano: Int?, recusados: Set<Int> = emptySet()): Candidato? {
        val alvo = Idioma.chaveTitulo(nome)
        if (alvo.isEmpty()) return null
        val casam = candidatos.filter { c ->
            c.id !in recusados &&
                (Idioma.chaveTitulo(c.titulo) == alvo || Idioma.chaveTitulo(c.original) == alvo) &&
                (ano == null || c.ano == null || kotlin.math.abs(c.ano - ano) <= 1)
        }
        return casam.sortedWith(compareBy<Candidato>({ if (ano != null && it.ano == ano) 0 else 1 }, { -it.popularidade })).firstOrNull()
    }

    private const val POSTER = "https://image.tmdb.org/t/p/w342"

    /** Pôster só de um caminho do TMDB conferido ("/abc_D-1.jpg"); qualquer outra coisa vira vazio (nada de endereço de fora). */
    fun urlPoster(caminho: String?): String =
        caminho?.takeIf { it.matches(Regex("/[A-Za-z0-9_-]+\\.(jpg|png)")) }?.let { POSTER + it }.orEmpty()

    /** Próximo episódio de uma série no ar (TMDB next_episode_to_air). */
    data class ProximoEp(val data: String, val temporada: Int, val numero: Int)

    /** Coleção (saga) do filme: id e nome ("Duna: Coleção"). */
    data class Colecao(val id: Int, val nome: String)

    data class Detalhe(
        val tmdb: Int,
        val imdb: String = "",
        val sinopse: String = "",
        val elenco: List<String> = emptyList(),
        val generos: List<String> = emptyList(),
        val colecao: Colecao? = null,
        val proximo: ProximoEp? = null,
        val noAr: Boolean = false,
        /** Trailer no YouTube (só a chave do vídeo, ex. "n9xhJrPXop4"); vazio quando o TMDB não tem. */
        val trailer: String = "",
        /** Direção (filme): crew "Director" do TMDB. Vence a do provedor, que errou na Sala (Getúlio: "Gigi Soares"). */
        val diretor: String = "",
        /** Pôster (elevação 8.2): endereço do image.tmdb.org, só montado a partir de um caminho conferido. */
        val poster: String = "",
        /** Quantas temporadas a série tem (0 = filme ou não informado). */
        val temporadas: Int = 0,
    ) {
        val linkTrailer: String get() = if (trailer.isEmpty()) "" else "https://www.youtube.com/watch?v=$trailer"
    }

    /**
     * O trailer de "videos" do TMDB: só YouTube; Trailer antes de Teaser; no idioma da TV antes do inglês; oficial antes
     * do de fã. Só aceita chave de vídeo do YouTube (letras, números, - e _), nunca um endereço vindo de fora.
     */
    fun escolherTrailer(videos: JSONArray?, idiomaTv: String): String {
        if (videos == null) return ""
        val lingua = idiomaTv.substringBefore('-').lowercase()
        data class V(val chave: String, val tipo: Int, val lingua: Int, val oficial: Boolean)
        val lista = (0 until videos.length()).mapNotNull { i ->
            val o = videos.optJSONObject(i) ?: return@mapNotNull null
            val chave = o.optString("key")
            if (!o.optString("site").equals("YouTube", true) || !chave.matches(Regex("[A-Za-z0-9_-]{6,20}"))) return@mapNotNull null
            val tipo = when (o.optString("type")) { "Trailer" -> 0; "Teaser" -> 1; else -> return@mapNotNull null }
            val l = when (o.optString("iso_639_1").lowercase()) { lingua -> 0; "en" -> 1; else -> 2 }
            V(chave, tipo, l, o.optBoolean("official"))
        }
        return lista.sortedWith(compareBy<V>({ it.tipo }, { it.lingua }, { if (it.oficial) 0 else 1 })).firstOrNull()?.chave.orEmpty()
    }

    fun lerDetalhe(json: String, serie: Boolean): Detalhe? = runCatching {
        // (o id do IMDb vem em external_ids; quem guarda é o InfoExternaCache, em Guardado.imdb)
        val o = JSONObject(json)
        val id = o.optInt("id").takeIf { it > 0 } ?: return null
        val imdb = (o.optJSONObject("external_ids")?.optString("imdb_id") ?: "").ifBlank { o.optString("imdb_id") }
            .takeIf { it.matches(Regex("tt\\d{5,10}")) }.orEmpty()
        val creditos = o.optJSONObject(if (serie) "aggregate_credits" else "credits")?.optJSONArray("cast")
        val diretor = if (serie) "" else o.optJSONObject("credits")?.optJSONArray("crew")?.let { c ->
            (0 until c.length()).mapNotNull { c.optJSONObject(it) }.filter { it.optString("job") == "Director" }
                .mapNotNull { it.optString("name").takeIf { n -> n.isNotBlank() } }.distinct().take(2).joinToString(", ")
        }.orEmpty()
        val elenco = creditos?.let { c -> (0 until minOf(c.length(), 6)).mapNotNull { c.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } } }.orEmpty()
        val generos = o.optJSONArray("genres")?.let { g -> (0 until g.length()).mapNotNull { g.optJSONObject(it)?.optString("name")?.takeIf { n -> n.isNotBlank() } } }.orEmpty()
        val col = o.optJSONObject("belongs_to_collection")?.let { c -> c.optInt("id").takeIf { it > 0 }?.let { Colecao(it, c.optString("name")) } }
        val prox = o.optJSONObject("next_episode_to_air")?.let { p ->
            val d = p.optString("air_date")
            if (d.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) ProximoEp(d, p.optInt("season_number"), p.optInt("episode_number")) else null
        }
        Detalhe(id, imdb, o.optString("overview").trim().take(1500), elenco, generos.take(3), col, prox,
            noAr = serie && (o.optBoolean("in_production") || prox != null),
            trailer = escolherTrailer(o.optJSONObject("videos")?.optJSONArray("results"), idiomaTmdb()), diretor = diretor,
            poster = urlPoster(o.optString("poster_path")), temporadas = if (serie) o.optInt("number_of_seasons").coerceAtLeast(0) else 0)
    }.getOrNull()

    /** As partes da coleção: (id TMDB, título, ano), do mais antigo ao mais novo. */
    fun lerColecao(json: String): List<Candidato> {
        val a = runCatching { JSONObject(json).optJSONArray("parts") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optInt("id").takeIf { it > 0 } ?: return@mapNotNull null
            Candidato(id, o.optString("title"), o.optString("original_title"), o.optString("release_date").take(4).toIntOrNull(), 0.0)
        }.sortedBy { it.ano ?: Int.MAX_VALUE }
    }

    /**
     * Quantas partes da coleção a lista de IPTV tem: pelo id do TMDB (quando a lista manda) ou pelo nome + ano.
     * Devolve (tem, total). "Coleção Duna: 2 de 3 na sua lista".
     */
    fun contarNaLista(partes: List<Candidato>, idsNaLista: Set<Int>, nomesNaLista: Set<String>): Pair<Int, Int> {
        val tem = partes.count { p -> p.id in idsNaLista || chaveNomeAno(p.titulo, p.ano) in nomesNaLista ||
            chaveNomeAno(p.original, p.ano) in nomesNaLista }
        return tem to partes.size
    }

    /**
     * A chave nome+ano que [contarNaLista] e o cruzamento com listas de fora usam. Sem as marcas de qualidade no fim
     * ("Oppenheimer (2023) 4K" do IPTV = "Oppenheimer" 2023 do Simkl; achado no teste de 18/11/2026).
     */
    fun chaveNomeAno(nome: String, ano: Int?): String =
        Idioma.chaveTitulo(nome).let { k -> if (QUALIDADES.any { k.endsWith(it) }) k.replace(reQualidade, "") else k } + "|" + (ano ?: 0)

    // A expressão de baixo só casa se a chave termina numa destas (20/11/2026: pré-checagem, ver o Idioma).
    private val QUALIDADES = listOf("4k", "hd", "hdr", "hdr10", "dv", "sdr", "1080p", "720p", "2160p", "hevc", "h265", "x265")

    private val reQualidade = Regex("""(\s+(4k|uhd|fhd|hd|hdr|hdr10|dv|sdr|1080p|720p|2160p|hevc|h265|x265))+$""")

    // ================================================================ notas

    /**
     * As notas que mostramos: Trakt e Simkl (0-10), que permitem uso comercial (Simkl: grátis até US$ 150/mês de receita;
     * acima disso, licença). A do Simkl entrou em 16/11/2026 para o Trakt não ficar sozinho. Fora desde 16/11/2026, porque o app vai ser
     * vendido e nenhum deles libera sem contrato: IMDb (data.imdb.com, Conditions of Use), Rotten Tomatoes (API só para
     * parceiros, Fandango/Fabric), Metacritic (sem API pública; Fabric/Fandom) e Letterboxd (API por pedido). O MDBList
     * repassava os quatro; ele continua só para as listas da pessoa.
     */
    data class Notas(val trakt: Float? = null, val simkl: Float? = null) {
        val vazia: Boolean get() = trakt == null && simkl == null
        /** A nota da estrela (0 a 10): a média das que houver. */
        val media: Float? get() = listOfNotNull(trakt, simkl).takeIf { it.isNotEmpty() }?.average()?.toFloat()
        /** Uma nota de 0 a 10 para ordenar e filtrar: a mesma da estrela. */
        val de0a10: Float? get() = media
    }

    /** "Trakt 78%  ·  Simkl 8,1": cada uma como no site dela (o Trakt em %, o Simkl de 0 a 10). */
    fun textoNotas(n: Notas, l: Locale = Locale.getDefault()): String = listOfNotNull(
        n.trakt?.let { "Trakt " + Math.round(it * 10) + "%" },
        n.simkl?.let { "Simkl " + String.format(l, "%.1f", it) },
    ).joinToString("  ·  ")

    // ================================================================ cache

    /** Pede de novo? Sem data = nunca pediu. Série no ar vence mais cedo (a data do próximo episódio muda). */
    fun vencido(quando: Long, agora: Long, noAr: Boolean = false): Boolean =
        quando <= 0 || agora - quando > (if (noAr) VALIDADE_SERIE_NO_AR_MS else VALIDADE_MS) || agora < quando

    /** O que fica guardado por título (chave do catálogo: "fonte:f123" / "fonte:s45"). */
    data class Guardado(
        val tmdb: Int? = null,
        val imdb: String = "",          // id do IMDb (tt...), só como identificador (casar com Trakt/Simkl); nenhuma informação do IMDb
        val traktNota: Float? = null,   // nota do Trakt (0-10), vale para todos, sem login; pedida de madrugada
        val traktEm: Long = 0,          // quando a nota do Trakt foi pedida (0 = nunca)
        val simkl: Int? = null,         // id do Simkl (do /redirect), para não perguntar de novo
        val simklNota: Float? = null,   // nota da comunidade do Simkl (0-10), só com a conta Simkl da pessoa; de madrugada
        val simklEm: Long = 0,          // quando a nota do Simkl foi pedida (0 = nunca)
        val recusados: Set<Int> = emptySet(),
        val detalhe: Detalhe? = null,
        val detalheEm: Long = 0,
        val buscaEm: Long = 0,   // última busca sem resultado (não buscar de novo a cada abertura)
    )

    /** As notas para mostrar: Trakt (todos, sem login) e Simkl (só existe com a conta conectada; InfoExternaCache). */
    fun notasDe(g: Guardado): Notas = Notas(g.traktNota, g.simklNota)

    fun guardadoParaJson(g: Guardado): JSONObject = JSONObject().apply {
        g.tmdb?.let { put("t", it) }
        if (g.imdb.isNotBlank()) put("im", g.imdb)
        g.traktNota?.let { put("tv", it.toDouble()) }
        if (g.traktEm > 0) put("te", g.traktEm)
        g.simkl?.let { put("sk", it) }
        g.simklNota?.let { put("sv", it.toDouble()) }
        if (g.simklEm > 0) put("se", g.simklEm)
        if (g.recusados.isNotEmpty()) put("x", JSONArray(g.recusados.toList()))
        if (g.buscaEm > 0) put("b", g.buscaEm)
        g.detalhe?.let { d ->
            put("d", JSONObject().put("i", d.imdb).put("s", d.sinopse).put("e", JSONArray(d.elenco)).put("g", JSONArray(d.generos))
                .put("ar", d.noAr).put("v", VERSAO_DETALHE).apply {
                    if (d.trailer.isNotEmpty()) put("tr", d.trailer)
                    if (d.diretor.isNotEmpty()) put("dr", d.diretor)
                    if (d.poster.isNotEmpty()) put("po", d.poster)
                    if (d.temporadas > 0) put("nt", d.temporadas)
                    d.colecao?.let { put("c", it.id).put("cn", it.nome) }
                    d.proximo?.let { put("pd", it.data).put("pt", it.temporada).put("pe", it.numero) }
                })
            put("de", g.detalheEm)
        }
    }

    fun guardadoDeJson(o: JSONObject): Guardado {
        fun lista(a: JSONArray?) = a?.let { x -> (0 until x.length()).map { x.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val tmdb = o.optInt("t").takeIf { it > 0 }
        val det = o.optJSONObject("d")?.let { d ->
            if (tmdb == null) return@let null
            Detalhe(tmdb, d.optString("i"), d.optString("s"), lista(d.optJSONArray("e")), lista(d.optJSONArray("g")),
                d.optInt("c").takeIf { it > 0 }?.let { Colecao(it, d.optString("cn")) },
                d.optString("pd").takeIf { it.isNotBlank() }?.let { ProximoEp(it, d.optInt("pt"), d.optInt("pe")) }, d.optBoolean("ar"),
                d.optString("tr"), d.optString("dr"), urlPoster(d.optString("po").removePrefix(POSTER)), d.optInt("nt"))
        }
        // Ficha guardada antes do trailer (sem "v"): vale até a próxima abertura, que pede de novo ao TMDB.
        val detEm = if (o.optJSONObject("d")?.optInt("v") == VERSAO_DETALHE) o.optLong("de") else 0L
        // "iv" (arquivo do IMDb) e "n"/"ne" (notas do MDBList: IMDb, RT, Metacritic, Letterboxd) de versões antigas:
        // não são lidos e somem na próxima gravação.
        val rec = o.optJSONArray("x")?.let { x -> (0 until x.length()).map { x.optInt(it) }.filter { it > 0 }.toSet() }.orEmpty()
        return Guardado(tmdb, o.optString("im"), o.optDouble("tv", Double.NaN).takeIf { !it.isNaN() }?.toFloat(), o.optLong("te"),
            o.optInt("sk").takeIf { it > 0 }, o.optDouble("sv", Double.NaN).takeIf { !it.isNaN() }?.toFloat(), o.optLong("se"),
            rec, det, detEm, o.optLong("b"))
    }

    // ================================================================ Trakt: nota para todos

    /**
     * Nota do Trakt no lugar da do IMDb (16/11/2026). O Trakt permite uso comercial, em app grátis ou pago, sem contrato
     * (developer.trakt.tv/docs/api-use-policy), pedindo a frase "uses Trakt but is not endorsed" (licencas.txt). Só a chave
     * do app (trakt-api-key), sem login da pessoa. GET /search/tmdb/{id}?type=movie|show&extended=full devolve
     * [{"type":"movie","movie":{"title",...,"rating":8.4,"votes":1234,"ids":{...}}}].
     */
    fun urlTraktPorTmdb(id: Int, serie: Boolean): String =
        "${ServicosFilmes.TRAKT_API}/search/tmdb/$id?type=${if (serie) "show" else "movie"}&extended=full"

    /** Nota válida por 30 dias (como a ficha); série no ar não muda tanto que valha pedir antes. */
    const val TRAKT_VALIDADE_MS = 30L * 24 * 3_600_000
    /** Com menos votos que isto a nota não diz muito (título quase sem público): não mostramos. */
    const val TRAKT_MIN_VOTOS = 20

    /** A nota (0-10) do primeiro resultado do tipo pedido; null se não achou, poucos votos ou resposta estranha. */
    fun lerNotaTrakt(json: String, serie: Boolean): Float? = runCatching {
        val a = JSONArray(json)
        val tipo = if (serie) "show" else "movie"
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i)?.takeIf { it.optString("type") == tipo }?.optJSONObject(tipo) ?: continue
            val nota = o.optDouble("rating", Double.NaN).takeIf { !it.isNaN() && it > 0 && it <= 10 } ?: return@runCatching null
            return@runCatching if (o.optInt("votes", 0) >= TRAKT_MIN_VOTOS) nota.toFloat() else null
        }
        null
    }.getOrNull()

    // ================================================================ Simkl: nota da comunidade para todos

    /*
     * 16/11/2026. Sem login, só o client_id do app (api.simkl.org/api-reference/ratings): o TMDB vira id do Simkl pelo
     * GET /redirect?to=simkl&type=movie|tv&tmdb=N (301 com Location https://simkl.com/movies/{id}/{slug}), e a nota vem
     * em GET /movies/{id} ou /tv/{id} ("ratings":{"simkl":{"rating":8.6,"votes":15265},"imdb":{...}}; a ficha fica em
     * cache no Cloudflare). O bloco "imdb" que vem junto é IGNORADO (dado do IMDb, uso comercial proibido).
     */
    /** Chave de um título pelo id TMDB e tipo ("F27205", "S1396"): o mesmo id é filme ou série conforme o tipo. */
    fun chaveTmdb(tmdb: Int, serie: Boolean): String = (if (serie) "S" else "F") + tmdb

    /**
     * Os ids do Simkl já sabidos, por [chaveTmdb]: os do catálogo que já foram achados (todas as versões de um título,
     * 4K, LEG, dividem o id) e, por cima, os da biblioteca da pessoa no Simkl. Os ids não mudam (Simkl): com isso o
     * /redirect só é chamado para título nunca visto.
     */
    fun simklConhecidos(catalogo: List<Triple<Int, Boolean, Int?>>, daBiblioteca: Map<String, Int>): HashMap<String, Int> {
        val r = HashMap<String, Int>()
        for ((tmdb, serie, simkl) in catalogo) if (simkl != null) r.putIfAbsent(chaveTmdb(tmdb, serie), simkl)
        r.putAll(daBiblioteca)
        return r
    }

    fun urlSimklRedirect(tmdb: Int, serie: Boolean, clienteId: String, versao: Int): String =
        ServicosFilmes.simklUrl("/redirect", clienteId, versao, "to=simkl&type=${if (serie) "tv" else "movie"}&tmdb=$tmdb")

    fun urlSimklFicha(simkl: Int, serie: Boolean, clienteId: String, versao: Int): String =
        ServicosFilmes.simklUrl(if (serie) "/tv/$simkl" else "/movies/$simkl", clienteId, versao)

    /** O id do Simkl no Location do /redirect; null se não veio (título que o Simkl não tem). Anime também vale. */
    fun idSimklDoLocal(local: String?, serie: Boolean): Int? {
        val m = Regex("simkl\\.com/(movies|tv|anime)/(\\d+)").find(local ?: return null) ?: return null
        if (!serie && m.groupValues[1] != "movies") return null   // pediu filme, veio série
        if (serie && m.groupValues[1] == "movies") return null
        return m.groupValues[2].toIntOrNull()?.takeIf { it > 0 }
    }

    const val SIMKL_VALIDADE_MS = TRAKT_VALIDADE_MS
    const val SIMKL_MIN_VOTOS = TRAKT_MIN_VOTOS

    /** A nota da comunidade (0-10); null sem nota, com poucos votos ou resposta estranha. Só o bloco "simkl". */
    fun lerNotaSimkl(json: String): Float? = runCatching { notaSimklDe(JSONObject(json)) }.getOrNull()

    /** O bloco ratings.simkl de um título (ficha ou arquivo pronto); null sem nota ou com poucos votos. */
    private fun notaSimklDe(o: JSONObject): Float? {
        val s = o.optJSONObject("ratings")?.optJSONObject("simkl") ?: return null
        val nota = s.optDouble("rating", Double.NaN).takeIf { !it.isNaN() && it > 0 && it <= 10 } ?: return null
        return if (s.optInt("votes", 0) >= SIMKL_MIN_VOTOS) nota.toFloat() else null
    }

    /** Um título achado num arquivo pronto do Simkl: o id dele e a nota (null = sem nota ou poucos votos). */
    data class SimklDoArquivo(val id: Int, val nota: Float?)

    /**
     * Os títulos de um arquivo pronto do Simkl, por (série?, id TMDB). Aceita os dois formatos: a lista direta dos
     * arquivos de trending (discover/trending/<tipo>/month_500.json) e o {"calendar":[..],"metadata":{id:{..}}} do
     * calendário v2. [serie] é o tipo do arquivo; anime com "anime_type":"movie" vira filme. Sem TMDB, fica de fora.
     */
    fun lerArquivoSimkl(json: String, serie: Boolean): Map<Pair<Boolean, Int>, SimklDoArquivo> = runCatching {
        val itens: List<JSONObject> = when (val raiz = org.json.JSONTokener(json).nextValue()) {
            is JSONArray -> (0 until raiz.length()).mapNotNull { raiz.optJSONObject(it) }
            is JSONObject -> raiz.optJSONObject("metadata")?.let { m -> m.keys().asSequence().mapNotNull { m.optJSONObject(it) }.toList() }.orEmpty()
            else -> emptyList()
        }
        val r = HashMap<Pair<Boolean, Int>, SimklDoArquivo>()
        for (i in itens) {
            val ids = i.optJSONObject("ids") ?: continue
            val tmdb = ids.optString("tmdb").trim().toIntOrNull()?.takeIf { it > 0 } ?: continue
            val id = (ids.optInt("simkl_id", 0).takeIf { it > 0 } ?: ids.optInt("simkl", 0)).takeIf { it > 0 } ?: continue
            val ehSerie = serie && i.optString("anime_type") != "movie"
            r.putIfAbsent(ehSerie to tmdb, SimklDoArquivo(id, notaSimklDe(i)))
        }
        r as Map<Pair<Boolean, Int>, SimklDoArquivo>
    }.getOrDefault(emptyMap())

    /** Os arquivos prontos lidos de madrugada: caminho e se é de séries. ~7,5 MB no total (medido em 17/11/2026). */
    val ARQUIVOS_SIMKL = listOf(
        "discover/trending/movies/month_500.json" to false,
        "discover/trending/tv/month_500.json" to true,
        "discover/trending/anime/month_500.json" to true,
        "calendar/v2/movie_release.json" to false,
        "calendar/v2/tv.json" to true,
        "calendar/v2/anime.json" to true,
    )

    /** Id do IMDb (tt...) como IDENTIFICADOR, para casar títulos com Trakt e Simkl. Não é informação do IMDb. */
    fun idImdbValido(id: String): Boolean = id.matches(Regex("tt\\d{5,10}"))

    // ================================================================ próximo episódio

    /**
     * "Novo episódio sexta, 21/11" / "New episode Friday, 11/21". [hoje] e a data em "aaaa-mm-dd". Passado = null.
     * Hoje e amanhã ganham a palavra (o modelo [hojeTxt]/[amanhaTxt] vem dos recursos, no idioma da TV).
     */
    fun textoProximo(p: ProximoEp, hoje: java.time.LocalDate, modelo: String, hojeTxt: String, amanhaTxt: String, l: Locale = Locale.getDefault()): String? {
        val d = runCatching { java.time.LocalDate.parse(p.data) }.getOrNull() ?: return null
        if (d.isBefore(hoje)) return null
        val quando = when (d) {
            hoje -> hojeTxt
            hoje.plusDays(1) -> amanhaTxt
            else -> {
                val dia = d.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, l)
                val data = java.time.format.DateTimeFormatter.ofPattern(if (l.language == "en") "M/d" else "dd/MM", l).format(d)
                "$dia, $data"
            }
        }
        return String.format(l, modelo, quando, p.temporada, p.numero)
    }

    /**
     * Versão curta para a etiqueta do pôster: "hoje", "amanhã" ou "sex., 20/11" ("Fri, 11/20"). Passado ou data inválida = null.
     */
    fun quandoCurto(p: ProximoEp, hoje: java.time.LocalDate, hojeTxt: String, amanhaTxt: String, l: Locale = Locale.getDefault()): String? {
        val d = runCatching { java.time.LocalDate.parse(p.data) }.getOrNull() ?: return null
        return when {
            d.isBefore(hoje) -> null
            d == hoje -> hojeTxt
            d == hoje.plusDays(1) -> amanhaTxt
            else -> d.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, l) + ", " +
                java.time.format.DateTimeFormatter.ofPattern(if (l.language == "en") "M/d" else "dd/MM", l).format(d)
        }
    }
}
