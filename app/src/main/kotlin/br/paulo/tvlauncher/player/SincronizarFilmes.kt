package br.paulo.tvlauncher.player

import java.time.Instant
import org.json.JSONArray
import org.json.JSONObject

/**
 * Fatia 3 dos serviços de filmes (14/11/2026): Trakt e Simkl sincronizados com Interesses, Assistidos e Continuar, o
 * "contar o que eu assisto" (scrobble) e as listas de fora como categorias em LISTAS. Só a lógica pura (testada na JVM):
 * ler as respostas, montar os corpos, as regras de conflito e o cruzamento lista x catálogo. A rede e o disco ficam em
 * ServicosSync.
 *
 * Formatos lidos das documentações: Trakt (trakt.docs.apiary.io: sync/last_activities, sync/watchlist, sync/watched,
 * sync/playback, scrobble, users/lists, users/likes), Simkl (api.simkl.org: sync/activities, sync/all-items,
 * sync/add-to-list, sync/history, scrobble) e MDBList (api.mdblist.com/schema: lists/user, lists/liked, lists/top,
 * lists/{id}/items). Listas do Simkl ficam de fora: na API elas exigem conta PRO/VIP.
 *
 * Regras (brief da fatia 3):
 *  - nada vai para fora sem o dono ligar; Modo visita e perfil infantil nunca mandam nada ([podeEnviar]);
 *  - visto é união: nunca desmarca sozinho ([vistosParaTrazer], [vistosParaEnviar]);
 *  - Interesses é uma mescla de três vias com a foto da última sincronização ([mesclarInteresses]): o que alguém tirou
 *    não volta;
 *  - "visto" só vai quando a NOSSA régua de concluído marcar ([decidirFim]); fora disso, pausa (o Trakt e o Simkl
 *    marcam visto no stop acima de 80%).
 */
object SincronizarFilmes {
    enum class Tipo { FILME, SERIE }

    data class Ids(val tmdb: Int? = null, val imdb: String? = null, val trakt: Int? = null, val simkl: Int? = null) {
        val vazio: Boolean get() = tmdb == null && imdb == null && trakt == null && simkl == null
    }

    data class Item(val tipo: Tipo, val ids: Ids, val titulo: String = "", val ano: Int? = null)

    data class Ep(val temporada: Int, val numero: Int)

    /** Um progresso parado em outro aparelho (0 a 100). [ep] só nas séries. [id] = o da pausa no serviço. */
    data class Pausa(val item: Item, val ep: Ep?, val progresso: Float, val quandoMs: Long, val id: Long = 0)

    /** Um título visto lá fora. Nas séries, [eps] = os episódios vistos. */
    data class Visto(val item: Item, val quandoMs: Long, val eps: Set<Ep> = emptySet())

    /** Uma lista de fora (Trakt ou MDBList) que pode virar categoria. [dono] = slug do dono no Trakt (lista curtida). */
    data class ListaFora(val servico: String, val id: String, val nome: String, val total: Int, val dono: String = "") {
        /** Chave estável da categoria na coluna e no disco. */
        val chave: String get() = "$servico:$id"
    }

    // ================================================================ leitura comum

    private fun inteiro(o: JSONObject, k: String): Int? = when (val v = o.opt(k)) {
        is Number -> v.toInt().takeIf { it > 0 }
        is String -> v.trim().toIntOrNull()?.takeIf { it > 0 }
        else -> null
    }

    fun lerIds(o: JSONObject?): Ids {
        if (o == null) return Ids()
        val imdb = o.optString("imdb").trim().takeIf { InfoExterna.idImdbValido(it) }
        return Ids(inteiro(o, "tmdb"), imdb, inteiro(o, "trakt"), inteiro(o, "simkl") ?: inteiro(o, "simkl_id"))
    }

    fun lerItem(o: JSONObject?, tipo: Tipo): Item? {
        if (o == null) return null
        val ids = lerIds(o.optJSONObject("ids"))
        if (ids.vazio) return null
        return Item(tipo, ids, o.optString("title").takeIf { it != "null" }.orEmpty(), inteiro(o, "year"))
    }

    /** Data ISO do Trakt/Simkl ("2014-11-20T07:01:32.000Z") em ms; null se não vier. */
    fun lerData(s: String?): Long? = s?.takeIf { it.isNotBlank() && it != "null" }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

    private fun arranjo(json: String): JSONArray? = runCatching { JSONArray(json) }.getOrNull()

    // ================================================================ Trakt

    /**
     * Itens de uma lista do Trakt: watchlist (GET /sync/watchlist/{movies|shows}) ou lista (GET .../items/{movie,show}).
     * Cada um é {type:"movie", movie:{...}} ou {type:"show", show:{...}}; temporada, episódio e pessoa ficam de fora.
     */
    fun traktLerItens(json: String): List<Item>? {
        // Corpo ilegível (ou cortado no limite de 2 MB) = null, nunca "lista vazia": vazia apagaria os Interesses daqui.
        val a = arranjo(json) ?: return null
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            when (o.optString("type")) {
                "movie" -> lerItem(o.optJSONObject("movie"), Tipo.FILME)
                "show" -> lerItem(o.optJSONObject("show"), Tipo.SERIE)
                else -> o.optJSONObject("movie")?.let { lerItem(it, Tipo.FILME) } ?: o.optJSONObject("show")?.let { lerItem(it, Tipo.SERIE) }
            }
        }
    }

    /** GET /sync/watched/{movies|shows}: [{plays, last_watched_at, movie|show, seasons:[{number, episodes:[{number}]}]}]. */
    fun traktLerVistos(json: String, tipo: Tipo): List<Visto>? {
        val a = arranjo(json) ?: return null
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val item = lerItem(o.optJSONObject(if (tipo == Tipo.FILME) "movie" else "show"), tipo) ?: return@mapNotNull null
            val reset = lerData(o.optString("reset_at"))
            val eps = HashSet<Ep>()
            o.optJSONArray("seasons")?.let { ts ->
                for (j in 0 until ts.length()) {
                    val t = ts.optJSONObject(j) ?: continue
                    val n = t.optInt("number", -1).takeIf { it >= 0 } ?: continue
                    val es = t.optJSONArray("episodes") ?: continue
                    for (k in 0 until es.length()) {
                        val e = es.optJSONObject(k) ?: continue
                        // Reassistindo (reset_at): o que foi visto antes do recomeço não conta.
                        if (reset != null && (lerData(e.optString("last_watched_at")) ?: Long.MAX_VALUE) < reset) continue
                        e.optInt("number", -1).takeIf { it >= 0 }?.let { eps += Ep(n, it) }
                    }
                }
            }
            Visto(item, lerData(o.optString("last_watched_at")) ?: 0, eps)
        }
    }

    /** GET /sync/playback: [{progress, paused_at, id, type:"movie"|"episode", movie | episode+show}]. */
    fun traktLerPausas(json: String): List<Pausa> {
        val a = arranjo(json) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> lerPausa(a.optJSONObject(i)) }
    }

    /** Uma pausa no formato do Trakt (o Simkl usa o mesmo desenho em /sync/playback). */
    fun lerPausa(o: JSONObject?): Pausa? {
        if (o == null) return null
        val prog = o.optDouble("progress", -1.0).toFloat().takeIf { it in 0f..100f } ?: return null
        val quando = lerData(o.optString("paused_at")) ?: 0
        val id = o.optLong("id", 0)
        val ep = o.optJSONObject("episode")
        return if (ep != null || o.optString("type") == "episode") {
            val s = lerItem(o.optJSONObject("show"), Tipo.SERIE) ?: return null
            val t = ep?.optInt("season", -1) ?: -1; val n = ep?.optInt("number", -1) ?: -1
            if (t < 0 || n < 0) return null
            Pausa(s, Ep(t, n), prog, quando, id)
        } else {
            Pausa(lerItem(o.optJSONObject("movie"), Tipo.FILME) ?: return null, null, prog, quando, id)
        }
    }

    /**
     * GET /sync/last_activities: as datas que dizem o que mudou. Guardamos só as que pedem uma leitura: comparar com as
     * da última vez e buscar só o que mudou ([mudou]).
     */
    fun traktLerAtividades(json: String): Map<String, String> = runCatching {
        val o = JSONObject(json)
        val m = HashMap<String, String>()
        fun pega(grupo: String, campo: String) { o.optJSONObject(grupo)?.optString(campo)?.takeIf { it.isNotBlank() && it != "null" }?.let { m["$grupo.$campo"] = it } }
        pega("movies", "watched_at"); pega("movies", "paused_at"); pega("episodes", "watched_at"); pega("episodes", "paused_at")
        pega("watchlist", "updated_at"); pega("movies", "watchlisted_at"); pega("shows", "watchlisted_at"); pega("lists", "updated_at")
        o.optString("all").takeIf { it.isNotBlank() && it != "null" }?.let { m["all"] = it }
        m
    }.getOrDefault(emptyMap())

    /** Alguma das [campos] mudou desde [antes]? (Sem a foto de antes, tudo mudou.) */
    fun mudou(antes: Map<String, String>?, agora: Map<String, String>, vararg campos: String): Boolean =
        antes == null || campos.any { antes[it] != agora[it] }

    /** GET /users/me/lists: [{name, item_count, ids:{trakt, slug}}]. */
    fun traktLerListas(json: String): List<ListaFora> {
        val a = arranjo(json) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> lerListaTrakt(a.optJSONObject(i), "") }
    }

    /** GET /users/me/likes/lists: [{liked_at, type:"list", list:{..., user:{ids:{slug}}}}]. */
    fun traktLerCurtidas(json: String): List<ListaFora> {
        val a = arranjo(json) ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val l = a.optJSONObject(i)?.optJSONObject("list") ?: return@mapNotNull null
            val dono = l.optJSONObject("user")?.optJSONObject("ids")?.optString("slug").orEmpty()
            lerListaTrakt(l, dono)
        }
    }

    private fun lerListaTrakt(o: JSONObject?, dono: String): ListaFora? {
        if (o == null) return null
        val id = o.optJSONObject("ids")?.let { inteiro(it, "trakt") } ?: return null
        val nome = o.optString("name").trim().takeIf { it.isNotEmpty() } ?: return null
        return ListaFora("trakt", id.toString(), nome.take(80), o.optInt("item_count", 0), dono)
    }

    // ================================================================ Simkl

    /** Um item de /sync/all-items com o status em que está na lista do Simkl. */
    data class EntradaSimkl(val item: Item, val status: String, val quandoMs: Long)

    /**
     * GET /sync/all-items: {"movies":[{"movie":{...},"status":"plantowatch","last_watched_at":...}],
     * "shows":[{"show":{...},"status":"watching"}], "anime":[{"show":{...},...}]}. Anime conta como série.
     */
    fun simklLerTodos(json: String): List<EntradaSimkl>? = runCatching {
        val o = JSONObject(json)
        val r = ArrayList<EntradaSimkl>()
        for ((grupo, campo, tipo) in listOf(Triple("movies", "movie", Tipo.FILME), Triple("shows", "show", Tipo.SERIE), Triple("anime", "show", Tipo.SERIE))) {
            val a = o.optJSONArray(grupo) ?: continue
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val item = lerItem(e.optJSONObject(campo), tipo) ?: continue
                r += EntradaSimkl(item, e.optString("status"), lerData(e.optString("last_watched_at")) ?: lerData(e.optString("added_to_watchlist_at")) ?: 0)
            }
        }
        r as List<EntradaSimkl>
    }.getOrNull()   // ilegível ou cortado: null (a sincronização pula; lista vazia apagaria tudo)

    /** GET /sync/activities: o "all" (se não mudou, nada mudou: a doc manda parar aí). */
    fun simklLerAtividades(json: String): Map<String, String> = runCatching {
        val o = JSONObject(json)
        val m = HashMap<String, String>()
        o.optString("all").takeIf { it.isNotBlank() && it != "null" }?.let { m["all"] = it }
        for (g in listOf("movies", "tv_shows", "anime")) {
            val x = o.optJSONObject(g) ?: continue
            for (c in listOf("plantowatch", "completed", "watching", "playback", "removed_from_list")) {
                x.optString(c).takeIf { it.isNotBlank() && it != "null" }?.let { m["$g.$c"] = it }
            }
        }
        m
    }.getOrDefault(emptyMap())

    /**
     * GET /sync/all-items?extended=simkl_ids_only: só o ids.simkl de cada item da biblioteca (até 60 mil). Serve para
     * achar o que a pessoa tirou lá fora (o date_from só traz o que entrou ou mudou). null = resposta ilegível.
     */
    fun simklLerIds(json: String): Set<Int>? = runCatching {
        val o = JSONObject(json)
        val r = HashSet<Int>()
        for ((grupo, campo) in listOf("movies" to "movie", "shows" to "show", "anime" to "show")) {
            val a = o.optJSONArray(grupo) ?: continue
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val ids = (e.optJSONObject(campo) ?: e).optJSONObject("ids") ?: continue
                inteiro(ids, "simkl")?.let { r += it }
            }
        }
        r as Set<Int>
    }.getOrNull()

    /**
     * Chave de um item na cópia local da biblioteca do Simkl: "s<id simkl>"; um item que nós mesmos mandamos e ainda
     * não voltou de lá (sem id simkl) fica como "t<tmdb>:<tipo>" até o próximo date_from trazer o id de verdade.
     */
    fun simklChave(i: Item): String? = i.ids.simkl?.let { "s$it" } ?: i.ids.tmdb?.let { "t$it:${i.tipo.name}" }

    /** Junta o que veio do Simkl (fase 1 ou date_from) à cópia local: o novo substitui o velho do mesmo título. */
    fun simklMesclar(cache: Map<String, EntradaSimkl>, novos: List<EntradaSimkl>): Map<String, EntradaSimkl> {
        val r = LinkedHashMap(cache)
        for (e in novos) {
            val k = simklChave(e.item) ?: continue
            // O provisório ("t…") do mesmo título sai: agora temos o id do Simkl.
            e.item.ids.tmdb?.let { r.remove("t$it:${e.item.tipo.name}") }
            r[k] = e
        }
        return r
    }

    /** Tira da cópia local o que não está mais na lista de ids de lá. Provisórios ("t…") ficam: não dá para julgar. */
    fun simklTirarSumidos(cache: Map<String, EntradaSimkl>, idsLa: Set<Int>): Map<String, EntradaSimkl> =
        cache.filter { (k, _) -> !k.startsWith("s") || k.drop(1).toIntOrNull()?.let { it in idsLa } == true }

    /**
     * O que nós mesmos mandamos (pôr na lista, tirar, marcar visto), já na cópia local, para não pedir de volta.
     * [status] null = tirou da lista. Acha o título pelo id TMDB e tipo.
     */
    fun simklMarcar(cache: Map<String, EntradaSimkl>, item: Item, status: String?, quandoMs: Long): Map<String, EntradaSimkl> {
        val tmdb = item.ids.tmdb ?: return cache
        val r = LinkedHashMap(cache)
        val achados = r.filterValues { it.item.tipo == item.tipo && it.item.ids.tmdb == tmdb }.keys
        if (status == null) { achados.forEach { r.remove(it) }; return r }
        if (achados.isEmpty()) simklChave(item)?.let { r[it] = EntradaSimkl(item, status, quandoMs) }
        else achados.forEach { k -> r[k] = r.getValue(k).copy(status = status, quandoMs = quandoMs) }
        return r
    }

    fun simklBibliotecaParaJson(cache: Map<String, EntradaSimkl>): JSONArray = JSONArray().apply {
        for (e in cache.values) put(JSONObject().put("t", if (e.item.tipo == Tipo.FILME) "F" else "S")
            .put("ids", idsJson(e.item.ids)).put("n", e.item.titulo).put("a", e.item.ano ?: 0).put("s", e.status).put("q", e.quandoMs))
    }

    /**
     * Ids do Simkl que a biblioteca da pessoa já traz, por id TMDB (chave de [InfoExterna.chaveTmdb]). A nota noturna
     * usa isso em vez de perguntar ao /redirect, que é uma busca ao vivo sem cache (Simkl, Ennergizer, 18/11/2026).
     */
    fun simklIdsPorTmdb(biblioteca: Collection<EntradaSimkl>): Map<String, Int> {
        val r = HashMap<String, Int>()
        for (e in biblioteca) {
            val tmdb = e.item.ids.tmdb ?: continue; val simkl = e.item.ids.simkl ?: continue
            r.putIfAbsent(InfoExterna.chaveTmdb(tmdb, e.item.tipo == Tipo.SERIE), simkl)
        }
        return r
    }

    fun simklBibliotecaDeJson(a: JSONArray?): Map<String, EntradaSimkl> {
        val r = LinkedHashMap<String, EntradaSimkl>()
        if (a == null) return r
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val tipo = if (o.optString("t") == "F") Tipo.FILME else Tipo.SERIE
            val ids = lerIds(o.optJSONObject("ids")).takeIf { !it.vazio } ?: continue
            val item = Item(tipo, ids, o.optString("n"), o.optInt("a", 0).takeIf { it > 0 })
            simklChave(item)?.let { r[it] = EntradaSimkl(item, o.optString("s"), o.optLong("q", 0)) }
        }
        return r
    }

    /**
     * Quais chamadas fazer, pelo guia /sync do Simkl (api.simkl.org/guides/sync). [antes] = a foto de /sync/activities
     * salva da última vez; [agora] = a de agora.
     *  - sem cópia local (ou sem foto): fase 1, a biblioteca inteira, uma vez;
     *  - "all" igual: nada mudou lá, para aqui;
     *  - "all" mudou: só o que mudou desde a foto (date_from = o "all" salvo), e a lista de ids se algum
     *    removed_from_list andou (tirar não aparece no date_from).
     * As pausas (/sync/playback) só quando a pessoa usa "continuar" e o playback andou (ou nunca vieram).
     */
    data class PlanoSimkl(val completo: Boolean, val desde: String?, val conferirTirados: Boolean, val pausas: Boolean)

    fun simklPlano(antes: Map<String, String>?, agora: Map<String, String>, temCopia: Boolean, querPausas: Boolean, temPausas: Boolean): PlanoSimkl {
        val pausas = querPausas && (!temPausas || mudou(antes, agora, "movies.playback", "tv_shows.playback", "anime.playback"))
        val desde = antes?.get("all")
        if (!temCopia || desde == null || agora["all"] == null) return PlanoSimkl(true, null, false, pausas)
        if (desde == agora["all"]) return PlanoSimkl(false, null, false, pausas)
        return PlanoSimkl(false, desde, mudou(antes, agora, "movies.removed_from_list", "tv_shows.removed_from_list", "anime.removed_from_list"), pausas)
    }

    /**
     * A foto salva depois que NÓS tiramos algo da lista: só os removed_from_list andam (para não baixar a lista de ids
     * por causa da nossa própria remoção). O "all" fica o antigo: o próximo date_from traz o que mandamos, já com o id
     * do Simkl.
     */
    fun simklFotoAposTirar(salva: Map<String, String>, nova: Map<String, String>): Map<String, String> =
        salva + nova.filterKeys { it.endsWith(".removed_from_list") }

    /** GET /sync/playback: a doc do Simkl usa o mesmo desenho do Trakt (progress, paused_at, movie | show + episode). */
    fun simklLerPausas(json: String): List<Pausa> = traktLerPausas(json)

    // ================================================================ MDBList

    /** GET /lists/user ([{id, name, slug, items}]), /lists/top e /lists/search (o mesmo desenho) e /lists/liked ({lists:[...]}). */
    fun mdblistLerListas(json: String): List<ListaFora> {
        val a = arranjo(json) ?: runCatching { JSONObject(json).optJSONArray("lists") }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = inteiro(o, "id") ?: return@mapNotNull null
            val nome = o.optString("name").trim().takeIf { it.isNotEmpty() && it != "null" } ?: return@mapNotNull null
            ListaFora("mdblist", id.toString(), nome.take(80), o.optInt("items", 0), o.optString("user_name").takeIf { it != "null" }.orEmpty())
        }
    }

    /** GET /lists/{id}/items: {"movies":[{rank, title, release_year, ids:{imdb, tmdb}}], "shows":[...]} na ordem do rank. */
    fun mdblistLerItens(json: String): List<Item> = runCatching {
        val o = JSONObject(json)
        val r = ArrayList<Pair<Int, Item>>()
        for ((g, tipo) in listOf("movies" to Tipo.FILME, "shows" to Tipo.SERIE)) {
            val a = o.optJSONArray(g) ?: continue
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val ids = lerIds(e.optJSONObject("ids")).let { if (it.imdb == null) it.copy(imdb = e.optString("imdb_id").takeIf { s -> InfoExterna.idImdbValido(s) }) else it }
                if (ids.vazio) continue
                r += e.optInt("rank", Int.MAX_VALUE) to Item(tipo, ids, e.optString("title").takeIf { it != "null" }.orEmpty(), inteiro(e, "release_year"))
            }
        }
        r.sortedBy { it.first }.map { it.second }
    }.getOrDefault(emptyList())

    // ================================================================ corpos (o que vai para fora)

    private fun idsJson(ids: Ids): JSONObject = JSONObject().apply {
        ids.trakt?.let { put("trakt", it) }; ids.simkl?.let { put("simkl", it) }
        ids.tmdb?.let { put("tmdb", it) }; ids.imdb?.let { put("imdb", it) }
    }

    private fun objeto(i: Item): JSONObject = JSONObject().put("ids", idsJson(i.ids)).apply {
        if (i.titulo.isNotEmpty()) put("title", i.titulo); i.ano?.let { put("year", it) }
    }

    /**
     * {"movies":[{ids}], "shows":[{ids}]} para a watchlist do Trakt (POST /sync/watchlist e /remove). Com [simklPara],
     * cada item leva "to" (POST /sync/add-to-list do Simkl: "plantowatch").
     */
    fun corpoItens(itens: List<Item>, simklPara: String? = null): String {
        val o = JSONObject()
        val filmes = JSONArray(); val series = JSONArray()
        for (i in itens) {
            val x = objeto(i).apply { if (simklPara != null) put("to", simklPara) }
            if (i.tipo == Tipo.FILME) filmes.put(x) else series.put(x)
        }
        if (filmes.length() > 0) o.put("movies", filmes)
        if (series.length() > 0) o.put("shows", series)
        return o.toString()
    }

    /** POST /sync/history (Trakt e Simkl): filmes vistos com a data, e episódios de séries agrupados por temporada. */
    fun corpoHistorico(filmes: List<Pair<Item, Long>>, episodios: List<Triple<Item, Ep, Long>> = emptyList()): String {
        val o = JSONObject()
        if (filmes.isNotEmpty()) o.put("movies", JSONArray().apply {
            for ((i, q) in filmes) put(objeto(i).put("watched_at", Instant.ofEpochMilli(q).toString()))
        })
        if (episodios.isNotEmpty()) o.put("shows", JSONArray().apply {
            for ((_, eps) in episodios.groupBy { it.first.ids }) {
                val primeiro = eps.first().first
                put(objeto(primeiro).put("seasons", JSONArray().apply {
                    for ((t, daTemp) in eps.groupBy { it.second.temporada }.toSortedMap()) put(JSONObject().put("number", t).put("episodes", JSONArray().apply {
                        for ((_, e, q) in daTemp.sortedBy { it.second.numero }) put(JSONObject().put("number", e.numero).put("watched_at", Instant.ofEpochMilli(q).toString()))
                    }))
                }))
            }
        })
        return o.toString()
    }

    /** POST /scrobble/{start|pause|stop}: {"movie":{...},"progress":12.5} ou {"show":{...},"episode":{season,number},"progress":..}. */
    fun corpoScrobble(item: Item, ep: Ep?, progresso: Float): String {
        val o = JSONObject().put("progress", Math.round(progresso.coerceIn(0f, 100f) * 100) / 100.0)
        if (item.tipo == Tipo.FILME || ep == null) o.put("movie", objeto(item))
        else o.put("show", objeto(item)).put("episode", JSONObject().put("season", ep.temporada).put("number", ep.numero))
        return o.toString()
    }

    // ================================================================ regras

    /** O que fazer quando o vídeo para. */
    enum class AcaoFim { PAUSAR, PARAR, HISTORICO, NADA }

    /**
     * Pela NOSSA régua (Vod.finalizado, que olha os minutos que faltam): concluído com 80% ou mais = stop (o serviço
     * marca visto); concluído abaixo de 80% (créditos longos) = adiciona ao histórico direto; não concluído = pause, que
     * nunca marca visto (o stop marcaria acima de 80%). Menos de 1% não vale nada (o Trakt recusa com 422).
     */
    fun decidirFim(concluido: Boolean, progresso: Float): Pair<AcaoFim, Float> = when {
        concluido && progresso >= 80f -> AcaoFim.PARAR to progresso.coerceAtMost(100f)
        concluido -> AcaoFim.HISTORICO to 100f
        progresso < 1f -> AcaoFim.NADA to progresso
        else -> AcaoFim.PAUSAR to progresso.coerceAtMost(99f)
    }

    /** Progresso de 0 a 100 a partir da posição; sem duração, 0. */
    fun progresso(ms: Long, duracao: Long): Float = if (duracao <= 0) 0f else (ms * 100f / duracao).coerceIn(0f, 100f)

    /** Pode mandar para fora? Só com a opção ligada, e nunca em Modo visita nem no perfil infantil. */
    fun podeEnviar(ligado: Boolean, visita: Boolean, infantil: Boolean): Boolean = ligado && !visita && !infantil

    /** Resultado da mescla dos Interesses: o novo conjunto local e o que mandar para fora. */
    data class Mescla(val local: Set<Int>, val adicionarFora: Set<Int>, val tirarFora: Set<Int>, val base: Set<Int>)

    /**
     * Mescla de três vias, por id TMDB: [base] = como estava nos dois lados na última sincronização. Quem tirou de um
     * lado tira do outro (não ressuscita); quem pôs de um lado põe no outro. Sem base (primeira vez) é só união: nada
     * some. [enviar] = false (opção de mandar desligada ou visita/infantil): o local recebe, nada sai e a base não anda
     * para o que só existe aqui (assim, ao ligar depois, o que foi marcado na TV ainda sobe).
     * [remoto] chega só com o que o catálogo daqui tem: o resto da lista de fora nunca é mexido.
     */
    fun mesclarInteresses(local: Set<Int>, remoto: Set<Int>, base: Set<Int>?, enviar: Boolean, universo: Set<Int>): Mescla {
        // Só mexe no que o catálogo daqui tem (ou já está nos Interesses): um título que saiu da lista de IPTV não é
        // "tirado" lá fora, e um de fora que a lista não tem fica quieto.
        val l = local intersect universo; val r = remoto intersect universo
        val b = base?.intersect(universo); val fora = base.orEmpty() - universo
        val m = mesclar(l, r, b, enviar)
        return m.copy(local = m.local + (local - universo), base = m.base + fora)
    }

    private fun mesclar(local: Set<Int>, remoto: Set<Int>, base: Set<Int>?, enviar: Boolean): Mescla {
        if (base == null) {
            val uniao = local + remoto
            return if (enviar) Mescla(uniao, local - remoto, emptySet(), uniao) else Mescla(uniao, emptySet(), emptySet(), remoto)
        }
        val tiradoAqui = base - local; val tiradoLa = base - remoto
        val postoAqui = local - base; val postoLa = remoto - base
        val novoLocal = (local - tiradoLa) + postoLa
        if (!enviar) return Mescla(novoLocal, emptySet(), emptySet(), remoto)
        val adicionar = postoAqui - remoto
        val tirar = (tiradoAqui intersect remoto) - postoLa
        val novoRemoto = (remoto - tirar) + adicionar
        return Mescla(novoLocal, adicionar, tirar, novoLocal intersect novoRemoto)
    }

    /**
     * A base depois do envio, com o que o serviço CONFIRMOU (2xx). A [Mescla] supõe que tudo foi aceito; o que não foi
     * (lote acima de 100, 420 lista cheia, 429, 5xx, sem rede) não pode entrar na base como se estivesse lá:
     *  - pôr que falhou sai da base: na próxima vez ainda é "posto aqui" e sobe de novo (sem isso viraria "tirado lá"
     *    e sumiria da TV);
     *  - tirar que falhou fica na base: na próxima vez ainda é "tirado aqui" e tenta de novo (sem isso voltaria à TV).
     */
    fun baseAposEnvio(m: Mescla, adicionadosOk: Set<Int>, tiradosOk: Set<Int>): Set<Int> =
        m.base - (m.adicionarFora - adicionadosOk) + (m.tirarFora - tiradosOk)

    /** Vistos lá fora que aqui ainda não estão concluídos: marcar aqui. Visto é união, nunca desmarca. */
    fun vistosParaTrazer(remoto: Set<Int>, localConcluidos: Set<Int>): Set<Int> = remoto - localConcluidos

    /** Concluídos aqui que lá fora ainda não estão: mandar (só com "contar o que eu assisto" ligado). */
    fun vistosParaEnviar(localConcluidos: Set<Int>, remoto: Set<Int>): Set<Int> = localConcluidos - remoto

    /**
     * Uma pausa de fora vale aqui? Só se aqui não está concluído e a de fora é mais nova que a nossa (1 min de folga:
     * o relógio do celular e o da TV não batem). Sem a duração daqui não dá para converter % em minutos: o player
     * aplica ao abrir o vídeo ([posicaoDaPausa]).
     */
    fun pausaVale(p: Pausa, local: Posicao?, concluidoAqui: Boolean): Boolean =
        !concluidoAqui && p.progresso in 1f..95f && (local == null || p.quandoMs > local.quando + 60_000)

    /** A pausa de fora em milissegundos, para a duração deste vídeo. */
    fun posicaoDaPausa(progresso: Float, duracao: Long): Long = if (duracao <= 0) 0 else (duracao * (progresso / 100.0)).toLong()

    /** Sincronizar agora? No máximo a cada 20 min (a doc do Simkl pede 15 a 30), salvo quando o dono manda. */
    fun deveSincronizar(ultimaMs: Long, agoraMs: Long, forcar: Boolean): Boolean =
        forcar || ultimaMs <= 0 || agoraMs < ultimaMs || agoraMs - ultimaMs >= INTERVALO_MS

    const val INTERVALO_MS = 20 * 60_000L

    // ================================================================ cruzamento lista x catálogo

    /** Um título do catálogo, para cruzar com uma lista de fora. */
    data class Titulo(val chave: String, val tmdb: Int?, val nomeAno: String)

    /**
     * Os títulos do catálogo que estão na [lista], na ordem da lista (sem repetir). Casa pelo id TMDB; sem id, pelo nome +
     * ano (InfoExterna.chaveNomeAno). Devolve as chaves e quantos itens da lista casaram ("112 de 250").
     */
    fun cruzar(lista: List<Item>, catalogo: List<Titulo>, tipo: Tipo): Pair<List<String>, Int> {
        val porId = HashMap<Int, String>(); val porNome = HashMap<String, String>()
        for (t in catalogo) { t.tmdb?.let { porId.putIfAbsent(it, t.chave) }; porNome.putIfAbsent(t.nomeAno, t.chave) }
        val vistos = LinkedHashSet<String>()
        var casaram = 0
        for (i in lista) {
            if (i.tipo != tipo) continue
            val ch = i.ids.tmdb?.let { porId[it] } ?: i.titulo.takeIf { it.isNotBlank() }?.let { porNome[InfoExterna.chaveNomeAno(it, i.ano)] }
            if (ch != null && vistos.add(ch)) casaram++
        }
        return vistos.toList() to casaram
    }

    /**
     * Títulos do catálogo SEM id TMDB que casam por nome + ano com um item de fora que TEM id: chave -> id de fora.
     * Sem isso, um filme da lista do Simkl/Trakt cuja entrada no catálogo do IPTV veio sem `tmdb` (e cuja ficha nunca
     * foi aberta) nunca entrava nos Interesses (visto no teste de 18/11/2026: Oppenheimer em Plan to Watch, 3 versões
     * no catálogo, Interesses ficou em 0). Todas as versões com o mesmo nome + ano (4K, LEG) recebem o id; quem já tem
     * id não é tocado (pode ter sido escolhido à mão na ficha).
     */
    fun idsPorNome(catalogo: List<Titulo>, remoto: List<Item>): Map<String, Int> {
        val semId = HashMap<String, MutableList<String>>()
        for (t in catalogo) if (t.tmdb == null) semId.getOrPut(t.nomeAno) { ArrayList() } += t.chave
        if (semId.isEmpty()) return emptyMap()
        val comId = catalogo.mapNotNullTo(HashSet()) { it.tmdb }
        val r = LinkedHashMap<String, Int>()
        for (i in remoto) {
            val id = i.ids.tmdb ?: continue
            if (id in comId || i.titulo.isBlank()) continue
            for (ch in semId[InfoExterna.chaveNomeAno(i.titulo, i.ano)].orEmpty()) r.putIfAbsent(ch, id)
        }
        return r
    }

    // ================================================================ opções (o que o dono ligou)

    /** Por conta (Trakt ou Simkl). Tudo começa desligado. */
    data class Opcoes(val interesses: Boolean = false, val assistidos: Boolean = false, val continuar: Boolean = false, val contar: Boolean = false)

    fun opcoesParaJson(o: Opcoes): JSONObject = JSONObject().put("interesses", o.interesses).put("assistidos", o.assistidos)
        .put("continuar", o.continuar).put("contar", o.contar)

    fun opcoesDeJson(o: JSONObject?): Opcoes = if (o == null) Opcoes() else
        Opcoes(o.optBoolean("interesses"), o.optBoolean("assistidos"), o.optBoolean("continuar"), o.optBoolean("contar"))

    fun listasParaJson(l: List<ListaFora>): JSONArray = JSONArray().apply {
        for (x in l) put(JSONObject().put("s", x.servico).put("id", x.id).put("n", x.nome).put("t", x.total).put("d", x.dono))
    }

    fun listasDeJson(a: JSONArray?): List<ListaFora> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val s = o.optString("s").takeIf { it == "trakt" || it == "mdblist" } ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.matches(Regex("[0-9]{1,12}")) } ?: return@mapNotNull null
            ListaFora(s, id, o.optString("n").take(80), o.optInt("t"), o.optString("d").take(60))
        }.distinctBy { it.chave }.take(MAX_LISTAS)
    }

    /** Listas escolhidas no máximo (cada uma é uma categoria fixa na coluna). */
    const val MAX_LISTAS = 8

    fun itensParaJson(l: List<Item>): JSONArray = JSONArray().apply {
        for (i in l) put(JSONObject().put("f", i.tipo == Tipo.FILME).put("ids", idsJson(i.ids)).put("n", i.titulo).apply { i.ano?.let { put("a", it) } })
    }

    fun itensDeJson(a: JSONArray?): List<Item> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val ids = lerIds(o.optJSONObject("ids")).takeUnless { it.vazio } ?: return@mapNotNull null
            Item(if (o.optBoolean("f")) Tipo.FILME else Tipo.SERIE, ids, o.optString("n"), o.optInt("a", 0).takeIf { it > 0 })
        }
    }
}
