package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Podcasts ("Ouvir" parte 2, 17/11/2026): as assinaturas, o que já foi ouvido e a cópia de cada feed na TV.
 *
 * Arquivos (files/player, no backup completo os dois primeiros):
 *  - podcasts.json: as assinaturas (endereço do feed, nome, capa, ETag/Last-Modified da última leitura);
 *  - podcast_posicoes.json: onde parou em cada episódio, se já ouviu, quando (no máximo [MAX_POSICOES]);
 *  - podcasts/<hash>.json: o feed já lido (episódios), refeito a cada atualização. Fica fora do backup: volta da rede.
 *
 * Rede: só o servidor do autor do feed. Pedido condicional (If-None-Match / If-Modified-Since) para não pesar nele; 304
 * = nada novo. Corta a leitura em [PodcastFeed.MAX_BYTES]. Endereço da própria TV não é aceito (como na rádio colada).
 * Nada de baixar episódio (o aparelho tem pouco espaço): toca direto do servidor dele.
 *
 * Lógica pura (JSON, ouvido, continuar, novos): PodcastsTest.
 */
object Podcasts {
    private const val TAG = "TvLauncher.Podcast"
    const val MAX_ASSINATURAS = 200
    const val MAX_POSICOES = 500
    /** Passou disto do episódio = ouvido (créditos e propaganda do fim não contam). */
    const val FRACAO_OUVIDO = 0.95
    /** Menos que isto de escuta não entra em "Continuar" (só abriu e saiu). */
    const val MIN_CONTINUAR_MS = 30_000L
    /** "Episódios novos" = dos últimos tantos dias, ainda não começados. */
    const val DIAS_NOVOS = 14

    data class Assinatura(val url: String, val titulo: String, val autor: String = "", val capa: String = "",
                          val explicito: Boolean = false, val etag: String = "", val modificado: String = "",
                          val verificadoEm: Long = 0L, val desde: Long = 0L)

    data class Posicao(val feed: String, val guid: String, val ms: Long, val durMs: Long, val ouvido: Boolean, val em: Long,
                       val titulo: String = "", val podcast: String = "")

    // ---------------------------------------------------------------- puro: assinaturas

    fun assinaturasParaJson(l: List<Assinatura>): String = JSONObject().put("assinaturas", JSONArray().apply {
        l.forEach { a -> put(JSONObject().put("url", a.url).put("titulo", a.titulo).put("autor", a.autor).put("capa", a.capa)
            .put("explicito", a.explicito).put("etag", a.etag).put("modificado", a.modificado)
            .put("verificado_em", a.verificadoEm).put("desde", a.desde)) }
    }).toString()

    fun assinaturasDeJson(t: String?): List<Assinatura> = runCatching {
        val a = JSONObject(t ?: return emptyList()).optJSONArray("assinaturas") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            if (!PodcastFeed.urlValida(url)) null
            else Assinatura(url, o.optString("titulo").take(200).ifBlank { url }, o.optString("autor").take(200),
                o.optString("capa").takeIf { PodcastFeed.urlValida(it) }.orEmpty(), o.optBoolean("explicito"),
                o.optString("etag").take(300), o.optString("modificado").take(100), o.optLong("verificado_em"), o.optLong("desde"))
        }.distinctBy { it.url }.take(MAX_ASSINATURAS)
    }.getOrDefault(emptyList())

    /** Assina (ou atualiza nome/capa se já assinado). A nova entra no fim. */
    fun assinar(l: List<Assinatura>, a: Assinatura): List<Assinatura> {
        val i = l.indexOfFirst { it.url == a.url }
        return if (i >= 0) l.toMutableList().apply { this[i] = a.copy(desde = l[i].desde) } else (l + a).take(MAX_ASSINATURAS)
    }

    // ---------------------------------------------------------------- puro: posições

    fun chave(feed: String, guid: String) = "$feed\u0001$guid"

    fun posicoesParaJson(m: Map<String, Posicao>): String = JSONArray().apply {
        m.values.forEach { p -> put(JSONObject().put("feed", p.feed).put("guid", p.guid).put("ms", p.ms).put("dur", p.durMs)
            .put("ouvido", p.ouvido).put("em", p.em).put("titulo", p.titulo).put("podcast", p.podcast)) }
    }.toString()

    fun posicoesDeJson(t: String?): Map<String, Posicao> = runCatching {
        val a = JSONArray(t ?: return emptyMap())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val feed = o.optString("feed"); val guid = o.optString("guid")
            if (feed.isEmpty() || guid.isEmpty()) null
            else Posicao(feed, guid.take(300), o.optLong("ms").coerceAtLeast(0), o.optLong("dur").coerceAtLeast(0), o.optBoolean("ouvido"),
                o.optLong("em"), o.optString("titulo").take(200), o.optString("podcast").take(200))
        }.associateBy { chave(it.feed, it.guid) }
    }.getOrDefault(emptyMap())

    fun ehOuvido(ms: Long, durMs: Long): Boolean = durMs > 0 && ms >= (durMs * FRACAO_OUVIDO).toLong()

    /** Grava a posição [p] em [m]; passa de 95% = ouvido (e não volta a "não ouvido" sozinho). Corta as mais velhas. */
    fun guardar(m: Map<String, Posicao>, p: Posicao): Map<String, Posicao> {
        val k = chave(p.feed, p.guid)
        val ouvido = p.ouvido || ehOuvido(p.ms, p.durMs) || (m[k]?.ouvido == true && p.ms == 0L)
        val nova = LinkedHashMap(m).apply { this[k] = p.copy(ouvido = ouvido) }
        return if (nova.size <= MAX_POSICOES) nova else nova.values.sortedByDescending { it.em }.take(MAX_POSICOES).associateBy { chave(it.feed, it.guid) }
    }

    /** Onde retomar: a posição salva menos 5 s (para lembrar a frase), ou 0 se já ouvido/quase no começo. */
    fun retomarEm(p: Posicao?): Long = when {
        p == null || p.ouvido || p.ms < 10_000 -> 0L
        else -> p.ms - 5_000
    }

    /** "Continuar": começados e não terminados, de podcasts ainda assinados, do mais recente para o mais antigo. */
    fun continuar(m: Map<String, Posicao>, assinados: Set<String>, max: Int = 20): List<Posicao> =
        m.values.filter { !it.ouvido && it.ms >= MIN_CONTINUAR_MS && it.feed in assinados }.sortedByDescending { it.em }.take(max)

    /** "Episódios novos": dos últimos [DIAS_NOVOS] dias, nunca começados, do mais novo para o mais velho. */
    fun novos(feeds: Map<String, PodcastFeed.Feed>, m: Map<String, Posicao>, agora: Long, max: Int = 30): List<Pair<String, PodcastFeed.Episodio>> {
        val limite = agora - DIAS_NOVOS * 86_400_000L
        return feeds.flatMap { (url, f) -> f.episodios.filter { it.dataMs in limite..(agora + 86_400_000L) && m[chave(url, it.guid)] == null }.map { url to it } }
            .sortedByDescending { it.second.dataMs }.take(max)
    }

    /** Esconde o explícito com o modo infantil ou o controle dos pais ligado. */
    fun visivel(ep: PodcastFeed.Episodio, protegido: Boolean) = !(protegido && ep.explicito)

    // ---------------------------------------------------------------- puro: cópia do feed

    fun feedParaJson(f: PodcastFeed.Feed): String = JSONObject().put("titulo", f.titulo).put("autor", f.autor).put("descricao", f.descricao)
        .put("capa", f.capa).put("link", f.link).put("idioma", f.idioma).put("explicito", f.explicito)
        .put("episodios", JSONArray().apply {
            f.episodios.forEach { e -> put(JSONObject().put("guid", e.guid).put("titulo", e.titulo).put("resumo", e.resumo)
                .put("data", e.dataMs).put("dur", e.duracaoS).put("url", e.url).put("tipo", e.tipo).put("tamanho", e.tamanho)
                .put("capa", e.capa).put("explicito", e.explicito).put("temporada", e.temporada).put("numero", e.numero)
                .put("capitulos", e.capitulos).put("transcricoes", JSONArray().apply {
                    e.transcricoes.forEach { t -> put(JSONObject().put("url", t.url).put("tipo", t.tipo).put("idioma", t.idioma)) }
                })) }
        }).toString()

    fun feedDeJson(t: String?): PodcastFeed.Feed? = runCatching {
        val o = JSONObject(t ?: return null)
        val a = o.optJSONArray("episodios") ?: JSONArray()
        val eps = (0 until a.length()).mapNotNull { i ->
            val e = a.optJSONObject(i) ?: return@mapNotNull null
            val url = e.optString("url")
            if (!PodcastFeed.urlValida(url)) return@mapNotNull null
            val tr = e.optJSONArray("transcricoes") ?: JSONArray()
            PodcastFeed.Episodio(e.optString("guid").ifEmpty { url }, e.optString("titulo"), e.optString("resumo"), e.optLong("data"),
                e.optInt("dur"), url, e.optString("tipo"), e.optLong("tamanho"), e.optString("capa"), e.optBoolean("explicito"),
                e.optInt("temporada"), e.optInt("numero"),
                (0 until tr.length()).mapNotNull { j -> tr.optJSONObject(j)?.let { PodcastFeed.Transcricao(it.optString("url"), it.optString("tipo"), it.optString("idioma")) } },
                e.optString("capitulos"))
        }
        PodcastFeed.Feed(o.optString("titulo"), o.optString("autor"), o.optString("descricao"), o.optString("capa"), o.optString("link"),
            o.optString("idioma"), o.optBoolean("explicito"), eps)
    }.getOrNull()

    // ---------------------------------------------------------------- Android: arquivos

    private fun arqAssinaturas(ctx: Context) = File(PlayerArmazem.pasta(ctx), "podcasts.json")
    private fun arqPosicoes(ctx: Context) = File(PlayerArmazem.pasta(ctx), "podcast_posicoes.json")
    private fun pastaFeeds(ctx: Context) = File(PlayerArmazem.pasta(ctx), "podcasts").apply { mkdirs() }
    private fun arqFeed(ctx: Context, url: String) = File(pastaFeeds(ctx), hash(url) + ".json")
    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    private val trava = Any()

    fun assinaturas(ctx: Context): List<Assinatura> = assinaturasDeJson(runCatching { arqAssinaturas(ctx).readText() }.getOrNull())

    /** Lê, muda e grava de uma vez (o painel e a tela da TV podem mexer ao mesmo tempo). */
    fun editarAssinaturas(ctx: Context, mudar: (List<Assinatura>) -> List<Assinatura>): List<Assinatura> = synchronized(trava) {
        val nova = mudar(assinaturas(ctx))
        PlayerCarga.gravarAtomico(arqAssinaturas(ctx), assinaturasParaJson(nova))
        nova
    }

    fun desassinar(ctx: Context, url: String) {
        editarAssinaturas(ctx) { l -> l.filterNot { it.url == url } }
        runCatching { arqFeed(ctx, url).delete() }
    }

    fun posicoes(ctx: Context): Map<String, Posicao> = posicoesDeJson(runCatching { arqPosicoes(ctx).readText() }.getOrNull())

    fun guardarPosicao(ctx: Context, p: Posicao) = synchronized(trava) {
        PlayerCarga.gravarAtomico(arqPosicoes(ctx), posicoesParaJson(guardar(posicoes(ctx), p)))
    }

    /** Muda as posições direto (marcar/desmarcar ouvido na tela). */
    fun editarPosicoes(ctx: Context, mudar: (Map<String, Posicao>) -> Map<String, Posicao>) = synchronized(trava) {
        PlayerCarga.gravarAtomico(arqPosicoes(ctx), posicoesParaJson(mudar(posicoes(ctx))))
    }

    fun feed(ctx: Context, url: String): PodcastFeed.Feed? = feedDeJson(runCatching { arqFeed(ctx, url).readText() }.getOrNull())

    /** As cópias de todos os assinados (para "Episódios novos"). Fora da thread da tela. */
    fun feeds(ctx: Context): Map<String, PodcastFeed.Feed> = assinaturas(ctx).mapNotNull { a -> feed(ctx, a.url)?.let { a.url to it } }.toMap()

    /** O modo infantil ou o controle dos pais estão ligados? (esconde episódio explícito) */
    fun protegido(ctx: Context): Boolean = runCatching { PlayerArmazem.parental(ctx).let { it.infantil || it.ligado } }.getOrDefault(false)

    // ---------------------------------------------------------------- Android: rede

    sealed class Leitura {
        data class Nova(val feed: PodcastFeed.Feed, val etag: String, val modificado: String) : Leitura()
        object SemMudanca : Leitura()
        data class Erro(val motivo: String) : Leitura()
    }

    /** Baixa e lê o feed [url]. Com [etag]/[modificado], o servidor pode responder "nada novo" (304). Fora da thread da tela. */
    fun baixar(url: String, etag: String = "", modificado: String = ""): Leitura {
        if (!PodcastFeed.urlValida(url)) return Leitura.Erro("endereço inválido")
        var atual = url
        repeat(5) {   // segue até 5 redirecionamentos (o HttpURLConnection não segue de http para https sozinho)
            val u = runCatching { URL(atual) }.getOrElse { return Leitura.Erro("endereço inválido") }
            val ips = runCatching { java.net.InetAddress.getAllByName(u.host).toList() }.getOrElse { return Leitura.Erro("endereço não existe") }
            if (ips.any { Radios.destinoProibido(it) }) return Leitura.Erro("endereço da própria TV não é aceito")
            val c = u.openConnection() as HttpURLConnection
            try {
                c.instanceFollowRedirects = false
                c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", "Sofaly/1 (TV podcast player)")
                c.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml;q=0.9, */*;q=0.5")
                if (etag.isNotEmpty() && atual == url) c.setRequestProperty("If-None-Match", etag)
                if (modificado.isNotEmpty() && atual == url) c.setRequestProperty("If-Modified-Since", modificado)
                val cod = c.responseCode
                when {
                    cod == 304 -> return Leitura.SemMudanca
                    cod in 300..399 -> {
                        val dest = c.getHeaderField("Location") ?: return Leitura.Erro("redirecionamento sem destino")
                        atual = URL(u, dest).toString()
                        if (!PodcastFeed.urlValida(atual)) return Leitura.Erro("redirecionamento inválido")
                        return@repeat
                    }
                    cod != 200 -> return Leitura.Erro("o servidor respondeu $cod")
                }
                // Feed maior que o limite: lê só o começo (o mais novo vem primeiro); PodcastFeed.ler aceita o corte.
                val f = c.inputStream.use { e ->
                    val p = android.util.Xml.newPullParser()
                    p.setInput(Limitado(e, PodcastFeed.MAX_BYTES), null)
                    runCatching { PodcastFeed.ler(p) }.onFailure { Log.w(TAG, "feed: $it") }.getOrNull()
                } ?: return Leitura.Erro("não é um feed de podcast")
                return Leitura.Nova(f, c.getHeaderField("ETag").orEmpty().take(300), c.getHeaderField("Last-Modified").orEmpty().take(100))
            } catch (e: Exception) {
                return Leitura.Erro(e.javaClass.simpleName)
            } finally { c.disconnect() }
        }
        return Leitura.Erro("redirecionamentos demais")
    }

    /** Entrada que acaba em [max] bytes (feed gigante ou sem fim não enche a memória: o leitor vê o fim e para). */
    private class Limitado(private val dentro: java.io.InputStream, private var resta: Long) : java.io.FilterInputStream(dentro) {
        override fun read(): Int = if (resta <= 0) -1 else super.read().also { if (it >= 0) resta-- }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (resta <= 0) return -1
            val n = super.read(b, off, minOf(len.toLong(), resta).toInt())
            if (n > 0) resta -= n
            return n
        }
    }

    /**
     * Assina [url]: baixa, confere que é podcast, grava a cópia e a assinatura. Devolve o nome do podcast ou o erro.
     * Fora da thread da tela.
     */
    fun adicionar(ctx: Context, url: String): Result<String> = runCatching {
        val u = url.trim().let { if (it.startsWith("feed://", true)) "https://" + it.substring(7) else it }
        when (val r = baixar(u)) {
            is Leitura.Nova -> {
                PlayerCarga.gravarAtomico(arqFeed(ctx, u), feedParaJson(r.feed))
                val agora = System.currentTimeMillis()
                editarAssinaturas(ctx) { l -> assinar(l, Assinatura(u, r.feed.titulo.ifBlank { u }, r.feed.autor, r.feed.capa,
                    r.feed.explicito, r.etag, r.modificado, agora, agora)) }
                r.feed.titulo.ifBlank { u }
            }
            is Leitura.Erro -> error(r.motivo)
            Leitura.SemMudanca -> error("sem resposta")
        }
    }

    /**
     * Relê os feeds assinados (manutenção noturna e ao abrir a tela, no máximo a cada [intervaloMs]). Devolve quantos
     * mudaram. Fora da thread da tela.
     */
    fun atualizar(ctx: Context, intervaloMs: Long = 6 * 3600_000L): Int {
        var mudaram = 0
        val agora = System.currentTimeMillis()
        for (a in assinaturas(ctx)) {
            if (agora - a.verificadoEm < intervaloMs && arqFeed(ctx, a.url).exists()) continue
            val r = baixar(a.url, if (arqFeed(ctx, a.url).exists()) a.etag else "", if (arqFeed(ctx, a.url).exists()) a.modificado else "")
            when (r) {
                is Leitura.Nova -> {
                    PlayerCarga.gravarAtomico(arqFeed(ctx, a.url), feedParaJson(r.feed)); mudaram++
                    editarAssinaturas(ctx) { l -> l.map { if (it.url == a.url) it.copy(titulo = r.feed.titulo.ifBlank { it.titulo },
                        autor = r.feed.autor, capa = r.feed.capa.ifEmpty { it.capa }, explicito = r.feed.explicito,
                        etag = r.etag, modificado = r.modificado, verificadoEm = agora) else it } }
                }
                Leitura.SemMudanca -> editarAssinaturas(ctx) { l -> l.map { if (it.url == a.url) it.copy(verificadoEm = agora) else it } }
                is Leitura.Erro -> Log.w(TAG, "${a.titulo}: ${r.motivo}")
            }
        }
        return mudaram
    }

    /** Importa uma lista OPML (texto). Devolve (assinados, falharam). Fora da thread da tela: baixa cada feed. */
    fun importarOpml(ctx: Context, texto: String): Pair<Int, Int> {
        val p = android.util.Xml.newPullParser().apply { setInput(texto.reader()) }
        val itens = runCatching { PodcastFeed.lerOpml(p) }.getOrDefault(emptyList())
        var ok = 0; var falhou = 0
        val ja = assinaturas(ctx).map { it.url }.toSet()
        for (i in itens) {
            if (i.url in ja) { ok++; continue }
            if (adicionar(ctx, i.url).isSuccess) ok++ else falhou++
        }
        return ok to falhou
    }

    fun exportarOpml(ctx: Context): String = PodcastFeed.paraOpml(assinaturas(ctx).map { PodcastFeed.ItemOpml(it.url, it.titulo) })
}
