package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

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

    private fun tipoDoTexto(t: String): Tipo? = when (t) { "movie", "movies" -> Tipo.FILME; "show", "shows", "anime" -> Tipo.SERIE; else -> null }

    // ================================================================ Trakt

    /**
     * Itens de uma lista do Trakt: watchlist (GET /sync/watchlist/{movies|shows}) ou lista (GET .../items/{movie,show}).
     * Cada um é {type:"movie", movie:{...}} ou {type:"show", show:{...}}; temporada, episódio e pessoa ficam de fora.
     */
    fun traktLerItens(json: String): List<Item> {
        val a = arranjo(json) ?: return emptyList()
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
    fun traktLerVistos(json: String, tipo: Tipo): List<Visto> {
        val a = arranjo(json) ?: return emptyList()
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
    fun simklLerTodos(json: String): List<EntradaSimkl> = runCatching {
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
        r
    }.getOrDefault(emptyList())

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
            for ((serie, eps) in episodios.groupBy { it.first.ids }) {
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
