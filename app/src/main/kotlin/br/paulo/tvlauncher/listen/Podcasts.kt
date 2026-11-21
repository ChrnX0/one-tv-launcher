package br.paulo.tvlauncher.listen

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.player.Episodio
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.PlayerCarga
import br.paulo.tvlauncher.player.Posicao
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

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

    fun assinaturasDeJson(t: String?): List<Assinatura> = assinaturasDeJsonOuNull(t) ?: emptyList()

    /** Null = texto que não abre (arquivo estragado); vazio = sem assinaturas. */
    fun assinaturasDeJsonOuNull(t: String?): List<Assinatura>? = runCatching {
        val a = JSONObject(t ?: return emptyList()).optJSONArray("assinaturas") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val url = o.optString("url").trim()
            if (!PodcastFeed.urlValida(url)) null
            else Assinatura(url, o.optString("titulo").take(200).ifBlank { url }, o.optString("autor").take(200),
                o.optString("capa").takeIf { PodcastFeed.urlValida(it) }.orEmpty(), o.optBoolean("explicito"),
                o.optString("etag").take(300), o.optString("modificado").take(100), o.optLong("verificado_em"), o.optLong("desde"))
        }.distinctBy { it.url }.take(MAX_ASSINATURAS)
    }.getOrNull()

    /** Assina (ou atualiza nome/capa se já assinado). A nova entra no fim; passou de [MAX_ASSINATURAS], não entra. */
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

    fun posicoesDeJson(t: String?): Map<String, Posicao> = posicoesDeJsonOuNull(t) ?: emptyMap()

    /** Null = texto que não abre (arquivo estragado). */
    fun posicoesDeJsonOuNull(t: String?): Map<String, Posicao>? = runCatching {
        val a = JSONArray(t ?: return emptyMap())
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val feed = o.optString("feed"); val guid = o.optString("guid")
            if (feed.isEmpty() || guid.isEmpty()) null
            else Posicao(feed, guid.take(300), o.optLong("ms").coerceAtLeast(0), o.optLong("dur").coerceAtLeast(0), o.optBoolean("ouvido"),
                o.optLong("em"), o.optString("titulo").take(200), o.optString("podcast").take(200))
        }.associateBy { chave(it.feed, it.guid) }
    }.getOrNull()

    fun ehOuvido(ms: Long, durMs: Long): Boolean = durMs > 0 && ms >= (durMs * FRACAO_OUVIDO).toLong()

    /**
     * Grava a posição [p] em [m]. Passou de 95% = ouvido. Já ouvido continua ouvido quando a posição é 0 (abriu e saiu
     * no começo); ouvir de novo (posição > 0) tira a marca, e o episódio volta para "Continuar". Posição tirada ANTES
     * da que já está guardada ([Posicao.em] menor) é ignorada: gravações em paralelo não voltam o relógio do episódio.
     * Corta as mais velhas.
     */
    fun guardar(m: Map<String, Posicao>, p: Posicao): Map<String, Posicao> {
        val k = chave(p.feed, p.guid)
        if ((m[k]?.em ?: Long.MIN_VALUE) > p.em) return m
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
        .put("categorias", JSONArray(f.categorias))
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
            o.optString("idioma"), o.optBoolean("explicito"), eps,
            o.optJSONArray("categorias")?.let { c -> (0 until c.length()).map { c.optString(it) }.filter { it.isNotEmpty() } }.orEmpty())
    }.getOrNull()

    /** Cópia gravada antes dos assuntos (21/11/2026): relê o feed inteiro uma vez (sem etag) para ganhar os assuntos. */
    private fun semAssuntos(ctx: Context, url: String) = runCatching { !arqFeed(ctx, url).readText().contains("\"categorias\"") }.getOrDefault(false)

    // ---------------------------------------------------------------- Android: arquivos

    private fun arqAssinaturas(ctx: Context) = File(PlayerArmazem.pasta(ctx), "podcasts.json")
    private fun arqPosicoes(ctx: Context) = File(PlayerArmazem.pasta(ctx), "podcast_posicoes.json")
    private fun pastaFeeds(ctx: Context) = File(PlayerArmazem.pasta(ctx), "podcasts").apply { mkdirs() }
    private fun arqFeed(ctx: Context, url: String) = File(pastaFeeds(ctx), hash(url) + ".json")
    private fun hash(s: String) = MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(20)
    private val trava = Any()

    /**
     * Uma edição por vez, entre threads E entre processos: o painel e a madrugada rodam no processo da HOME, a tela e o
     * serviço no ":player". A trava do Java vale só dentro do processo; o FileLock (podcasts.lock) vale entre eles.
     */
    private fun <T> comTrava(ctx: Context, bloco: () -> T): T = synchronized(trava) {
        val lock = runCatching { java.io.RandomAccessFile(File(PlayerArmazem.pasta(ctx), "podcasts.lock"), "rw") }.getOrNull()
        try {
            val f = runCatching { lock?.channel?.lock() }.getOrNull()
            try { bloco() } finally { runCatching { f?.release() } }
        } finally { runCatching { lock?.close() } }
    }

    /**
     * Fila única do processo para gravar posições e reler a tela: quem lê depois de pedir uma gravação vê a gravação
     * (o Fundo é um conjunto de threads em paralelo, sem ordem). Só trabalho curto de disco; rede nunca.
     */
    private val fila = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "podcast-fila").apply { isDaemon = true } }
    fun naFila(tarefa: () -> Unit) {
        fila.execute { try { tarefa() } catch (t: Throwable) { Log.w(TAG, "fila: $t") } }
    }

    /**
     * Lê [arq] com [ler]. Arquivo estragado (não abre) é guardado como .estragado e conta como vazio: a próxima gravação
     * não apaga o que havia nele sem deixar cópia.
     */
    private fun <T> lerArquivo(arq: File, vazio: T, estrito: Boolean = false, ler: (String?) -> T?): T {
        if (!arq.exists()) return vazio
        // Existe mas não deu para ler (E/S, memória): para mostrar conta como vazio; para EDITAR não, senão a gravação
        // seguinte apagava as assinaturas/posições de verdade (rodada 3 de depuração, 18/11/2026).
        val t = runCatching { arq.readText() }.getOrNull() ?: if (estrito) throw java.io.IOException("não li ${arq.name}") else return vazio
        ler(t)?.let { return it }
        Log.w(TAG, "${arq.name} estragado: guardado como .estragado")
        runCatching { arq.copyTo(File(arq.path + ".estragado"), overwrite = true) }
        return vazio
    }

    fun assinaturas(ctx: Context): List<Assinatura> = lerArquivo(arqAssinaturas(ctx), emptyList(), ler = ::assinaturasDeJsonOuNull)
    private fun assinaturasParaEditar(ctx: Context): List<Assinatura> = lerArquivo(arqAssinaturas(ctx), emptyList(), true, ::assinaturasDeJsonOuNull)

    /** Grava ou lança: quem edita precisa saber que não ficou salvo. */
    private fun gravar(arq: File, texto: String) { if (!PlayerCarga.gravarAtomico(arq, texto)) throw java.io.IOException("não gravei ${arq.name}") }

    /** Lê, muda e grava de uma vez (o painel e a tela da TV podem mexer ao mesmo tempo). */
    fun editarAssinaturas(ctx: Context, mudar: (List<Assinatura>) -> List<Assinatura>): List<Assinatura> = comTrava(ctx) {
        val nova = mudar(assinaturasParaEditar(ctx))
        gravar(arqAssinaturas(ctx), assinaturasParaJson(nova))
        nova
    }

    fun desassinar(ctx: Context, url: String) {
        comTrava(ctx) {
            gravar(arqAssinaturas(ctx), assinaturasParaJson(assinaturasParaEditar(ctx).filterNot { it.url == url }))
            runCatching { arqFeed(ctx, url).delete() }
        }
    }

    fun posicoes(ctx: Context): Map<String, Posicao> = lerArquivo(arqPosicoes(ctx), emptyMap(), ler = ::posicoesDeJsonOuNull)
    private fun posicoesParaEditar(ctx: Context): Map<String, Posicao> = lerArquivo(arqPosicoes(ctx), emptyMap(), true, ::posicoesDeJsonOuNull)

    fun guardarPosicao(ctx: Context, p: Posicao) = comTrava(ctx) {
        gravar(arqPosicoes(ctx), posicoesParaJson(guardar(posicoesParaEditar(ctx), p)))
    }

    /** Muda as posições direto (marcar/desmarcar ouvido na tela). */
    fun editarPosicoes(ctx: Context, mudar: (Map<String, Posicao>) -> Map<String, Posicao>) = comTrava(ctx) {
        gravar(arqPosicoes(ctx), posicoesParaJson(mudar(posicoesParaEditar(ctx))))
    }

    fun feed(ctx: Context, url: String): PodcastFeed.Feed? = feedDeJson(runCatching { arqFeed(ctx, url).readText() }.getOrNull())

    /** As cópias de todos os assinados (para "Episódios novos"). Fora da thread da tela. */
    fun feeds(ctx: Context): Map<String, PodcastFeed.Feed> = assinaturas(ctx).mapNotNull { a -> feed(ctx, a.url)?.let { a.url to it } }.toMap()

    /** O modo infantil ou o controle dos pais estão ligados? (esconde episódio explícito) */
    fun protegido(ctx: Context): Boolean = runCatching { PlayerArmazem.parental(ctx).let { it.infantil || it.ligado } }.getOrDefault(true)

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
                c.setRequestProperty("User-Agent", Http.USER_AGENT)
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
                // Prazo estourado ou DTD com entidades: erro (não vale como corte).
                var entrada: PodcastFeed.Entrada? = null
                val f = c.inputStream.use { e ->
                    val p = android.util.Xml.newPullParser()
                    val en = PodcastFeed.Entrada(e, PodcastFeed.MAX_BYTES).also { entrada = it }
                    p.setInput(en, null)
                    runCatching { PodcastFeed.ler(p) }.onFailure { Log.w(TAG, "feed: $it") }.getOrNull()
                }
                if (entrada?.estourou == true) return Leitura.Erro("o servidor demorou demais")
                if (entrada?.recusou == true) return Leitura.Erro("não é um feed de podcast")
                f ?: return Leitura.Erro("não é um feed de podcast")
                return Leitura.Nova(f, c.getHeaderField("ETag").orEmpty().take(300), c.getHeaderField("Last-Modified").orEmpty().take(100))
            } catch (e: Exception) {
                return Leitura.Erro(e.javaClass.simpleName)
            } finally { c.disconnect() }
        }
        return Leitura.Erro("redirecionamentos demais")
    }

    /** O endereço como fica guardado (feed:// vira https://). */
    fun normalizar(url: String) = url.trim().let { if (it.startsWith("feed://", true)) "https://" + it.substring(7) else it }

    /**
     * Assina [url]: baixa, confere que é podcast, grava a cópia e a assinatura. Devolve o nome do podcast ou o erro.
     * Fora da thread da tela.
     */
    fun adicionar(ctx: Context, url: String): Result<String> = runCatching {
        val u = normalizar(url)
        when (val r = baixar(u)) {
            is Leitura.Nova -> {
                val agora = System.currentTimeMillis()
                val ok = comTrava(ctx) {
                    val l = assinaturasParaEditar(ctx)
                    if (l.none { it.url == u } && l.size >= MAX_ASSINATURAS) return@comTrava false
                    gravar(arqFeed(ctx, u), feedParaJson(r.feed))
                    gravar(arqAssinaturas(ctx), assinaturasParaJson(assinar(l, Assinatura(u, r.feed.titulo.ifBlank { u },
                        r.feed.autor, r.feed.capa, r.feed.explicito, r.etag, r.modificado, agora, agora))))
                    true
                }
                if (!ok) error("limite de $MAX_ASSINATURAS podcasts")
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
            if (agora - a.verificadoEm < intervaloMs && arqFeed(ctx, a.url).exists() && !semAssuntos(ctx, a.url)) continue
            val condicional = arqFeed(ctx, a.url).exists() && !semAssuntos(ctx, a.url)
            val r = baixar(a.url, if (condicional) a.etag else "", if (condicional) a.modificado else "")
            when (r) {
                is Leitura.Nova -> comTrava(ctx) {
                    // Removido enquanto baixava (painel, outra tela): não recria a cópia órfã.
                    val l = assinaturasParaEditar(ctx)
                    if (l.none { it.url == a.url }) return@comTrava
                    gravar(arqFeed(ctx, a.url), feedParaJson(r.feed)); mudaram++
                    gravar(arqAssinaturas(ctx), assinaturasParaJson(l.map { if (it.url == a.url) it.copy(
                        titulo = r.feed.titulo.ifBlank { it.titulo }, autor = r.feed.autor, capa = r.feed.capa.ifEmpty { it.capa },
                        explicito = r.feed.explicito, etag = r.etag, modificado = r.modificado, verificadoEm = agora) else it }))
                }
                Leitura.SemMudanca -> editarAssinaturas(ctx) { l -> l.map { if (it.url == a.url) it.copy(verificadoEm = agora) else it } }
                is Leitura.Erro -> Log.w(TAG, "${a.titulo}: ${r.motivo}")
            }
        }
        return mudaram
    }

    /**
     * Importa uma lista OPML (texto). Devolve (seguidos da lista, que ficaram de fora). Rápido: assina com o nome que a
     * lista dá e baixa os episódios depois, em segundo plano (antes baixava 500 feeds um a um na thread do pedido do
     * painel). Feed que não responder fica seguido sem episódios até a próxima atualização; dá para remover.
     */
    fun importarOpml(ctx: Context, texto: String): Pair<Int, Int> {
        if (PodcastFeed.temEntidade(texto)) return 0 to 0
        val p = android.util.Xml.newPullParser().apply { setInput(texto.reader()) }
        val itens = PodcastFeed.lerOpml(p)   // XML quebrado no meio: fica o que veio antes (ver lerOpml)
        if (itens.isEmpty()) return 0 to 0
        val l = editarAssinaturas(ctx) { atual ->
            itens.fold(atual) { acc, i -> if (acc.any { it.url == i.url }) acc else assinar(acc, Assinatura(i.url, i.titulo.ifBlank { i.url }, desde = System.currentTimeMillis())) }
        }
        val seguidos = itens.count { i -> l.any { it.url == i.url } }
        val app = ctx.applicationContext
        Fundo.rodar("podcast") { runCatching { atualizar(app) } }
        return seguidos to (itens.size - seguidos)
    }

    fun exportarOpml(ctx: Context): String = PodcastFeed.paraOpml(assinaturas(ctx).map { PodcastFeed.ItemOpml(it.url, it.titulo) })
}
