package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.InfoExterna.Guardado
import org.json.JSONObject
import java.io.File

/**
 * Disco e rede da ficha e das notas de fora ([InfoExterna]). Tudo aqui bloqueia (rede): chamar fora da thread da tela.
 *
 * files/player/externo.json: por título do catálogo, o id do TMDB, a ficha resumida e as notas do Trakt e do Simkl, com a
 * data. Vale 30 dias (série no ar: 3). Nunca pede a cada foco: a ficha pede ao abrir; a nota do Trakt vem de madrugada
 * ([noturnoTrakt], [noturnoSimkl]).
 */
object InfoExternaCache {
    private const val TAG = "TvLauncher.InfoExterna"
    private const val ARQUIVO = "externo.json"
    private const val MAX_TITULOS = 6000
    private const val BUSCA_DE_NOVO_MS = 7L * 24 * 3_600_000

    private val trava = Any()
    @Volatile private var memoria: LinkedHashMap<String, Guardado>? = null
    private val colecoes = HashMap<Int, List<InfoExterna.Candidato>>()

    /*
     * Dois processos usam este arquivo (15/11/2026): o da HOME (manutenção da noite, nota do Trakt, busca) e o do player
     * (Filmes e Séries). Antes cada um lia uma vez e gravava o mapa inteiro por cima do outro: notas e ids do
     * TMDB sumiam e a cota do TMDB era gasta de novo. Agora: [sujos] guarda o que mudou AQUI desde a última gravação;
     * gravar trava o arquivo entre processos, relê o disco se outro processo gravou, põe os sujos por cima e grava.
     * E a leitura confere (no máximo a cada 2 s) se o arquivo mudou, para não usar um mapa velho por horas.
     */
    private val sujos = HashSet<String>()
    private var visto = ""            // identidade do arquivo na última leitura/gravação (inode + tamanho + data)
    private var conferidoEm = 0L      // System.nanoTime da última conferência

    private fun chaves() = ServicosContas.chavesApp()
    fun temTmdb(): Boolean = chaves().tmdb.isNotBlank()
    private fun agente() = ServicosFilmes.agente(BuildConfig.VERSION_CODE)

    // ================================================================ disco

    private fun arquivo(ctx: Context) = File(PlayerArmazem.pasta(ctx), ARQUIVO)

    /** Muda quando outro processo grava: cada gravação é um arquivo novo (renomeado), então o inode muda. */
    private fun identidade(arq: File): String = try {
        val st = android.system.Os.stat(arq.path)
        "${st.st_ino}:${st.st_size}:${st.st_mtime}"
    } catch (_: Exception) { "${arq.length()}:${arq.lastModified()}" }

    private fun ler(arq: File): LinkedHashMap<String, Guardado> = LinkedHashMap<String, Guardado>().also { m ->
        runCatching {
            val o = JSONObject(arq.readText())
            for (k in o.keys()) o.optJSONObject(k)?.let { m[k] = InfoExterna.guardadoDeJson(it) }
        }
    }

    /** Relê do disco se outro processo gravou, mantendo por cima o que mudou aqui ([sujos]). Chamar com [trava]. */
    private fun recarregarSeMudou(arq: File, forcar: Boolean) {
        val agora = System.nanoTime()
        val m = memoria
        if (m != null && !forcar && agora - conferidoEm < 2_000_000_000L) return
        conferidoEm = agora
        val id = identidade(arq)
        if (m != null && id == visto) return
        val novo = ler(arq)
        if (m != null) for (k in sujos) m[k]?.let { novo.remove(k); novo[k] = it }
        visto = id
        memoria = novo
    }

    private fun mapa(ctx: Context): LinkedHashMap<String, Guardado> = synchronized(trava) {
        recarregarSeMudou(arquivo(ctx), forcar = false)
        memoria!!
    }

    /** Põe um título no mapa e marca para a próxima gravação. Chamar com [trava]. */
    private fun por(m: LinkedHashMap<String, Guardado>, chave: String, g: Guardado) { m.remove(chave); m[chave] = g; sujos.add(chave) }

    private fun gravar(ctx: Context) = synchronized(trava) {
        val arq = arquivo(ctx)
        runCatching {
            // Trava entre processos (fcntl) durante reler + gravar: o outro processo espera a vez.
            java.io.RandomAccessFile(File(arq.path + ".trava"), "rw").use { raf ->
                raf.channel.lock().use {
                    recarregarSeMudou(arq, forcar = true)
                    val m = memoria!!
                    while (m.size > MAX_TITULOS) m.remove(m.keys.first())   // os mais antigos saem
                    val o = JSONObject(); for ((k, g) in m) o.put(k, InfoExterna.guardadoParaJson(g))
                    val tmp = File.createTempFile("$ARQUIVO.", ".tmp", arq.parentFile)
                    try { tmp.writeText(o.toString()); check(tmp.renameTo(arq)) } finally { tmp.delete() }
                    sujos.clear()
                    visto = identidade(arq)
                }
            }
        }.onFailure { Log.w(TAG, "não gravei: ${it.javaClass.simpleName}") }
    }

    /** Elevação 6.3: o id do IMDb (só o número, como identificador) da série/filme de id TMDB [tmdb], se já guardado. */
    fun imdbPorTmdb(ctx: Context, tmdb: Int): String? = synchronized(trava) {
        mapa(ctx).values.firstOrNull { it.tmdb == tmdb && it.imdb.isNotBlank() }?.imdb
    }

    /** O que já está guardado (sem rede). Rápido: pode chamar da tela depois da primeira leitura. */
    fun guardado(ctx: Context, chave: String): Guardado? = synchronized(trava) { mapa(ctx)[chave] }

    /** Nota de 0 a 10 de fora (Trakt), para o Filtro e a ordem. Só o que está guardado. */
    fun notas(ctx: Context): Map<String, Float> = synchronized(trava) {
        mapa(ctx).mapNotNull { (k, g) -> InfoExterna.notasDe(g).de0a10?.let { k to it } }.toMap()
    }

    private fun mudar(ctx: Context, chave: String, f: (Guardado) -> Guardado): Guardado = synchronized(trava) {
        val m = mapa(ctx)
        val novo = f(m[chave] ?: Guardado())
        por(m, chave, novo)   // tira e põe de volta: vai para o fim (mais recente)
        gravar(ctx)
        novo
    }

    // ================================================================ ficha (ao abrir)

    /**
     * Garante id e ficha do TMDB de um título, se vencidos. [tmdbConhecido] = o que o Xtream mandou.
     * Devolve o que ficou guardado (pode ser vazio: sem chave, sem rede ou sem casar o nome).
     */
    fun resolver(ctx: Context, chave: String, nome: String, ano: Int?, serie: Boolean, tmdbConhecido: Int?): Guardado {
        val app = ctx.applicationContext
        val agora = System.currentTimeMillis()
        var g = guardado(app, chave) ?: Guardado()
        val k = chaves()
        val idioma = InfoExterna.idiomaTmdb()
        // 1. O id: o do Xtream vale mais (a não ser que a pessoa tenha recusado); depois o já achado; depois a busca.
        val doXtream = tmdbConhecido?.takeIf { it !in g.recusados }
        if (doXtream != null && doXtream != g.tmdb) g = mudar(app, chave) { it.copy(tmdb = doXtream, detalhe = null, detalheEm = 0, traktNota = null, traktEm = 0, simkl = null, simklNota = null, simklEm = 0) }
        if (g.tmdb == null && k.tmdb.isNotBlank() && agora - g.buscaEm > BUSCA_DE_NOVO_MS) {
            val limpo = LegendaExterna.nomeDeBusca(Vod.nomeLimpo(nome)).ifBlank { Vod.nomeLimpo(nome) }
            val achado = runCatching {
                val r = ServicosContas.pedir(InfoExterna.urlBusca(k.tmdb, limpo, ano, serie, idioma), InfoExterna.tmdbCabecalhos(k.tmdb, agente()))
                if (r.codigo in 200..299) InfoExterna.escolher(InfoExterna.lerBusca(r.corpo, serie), Vod.nomeLimpo(nome), ano, g.recusados)
                    ?: if (ano != null) {   // sem casar com o ano: tenta sem ele (ano errado na lista é comum)
                        val r2 = ServicosContas.pedir(InfoExterna.urlBusca(k.tmdb, limpo, null, serie, idioma), InfoExterna.tmdbCabecalhos(k.tmdb, agente()))
                        if (r2.codigo in 200..299) InfoExterna.escolher(InfoExterna.lerBusca(r2.corpo, serie), Vod.nomeLimpo(nome), null, g.recusados) else null
                    } else null
                else null
            }.onFailure { Log.w(TAG, "busca: ${it.javaClass.simpleName}") }.getOrNull()
            g = mudar(app, chave) { it.copy(tmdb = achado?.id, buscaEm = if (achado == null) agora else 0) }
        }
        val id = g.tmdb ?: return g
        // 2. Ficha do TMDB.
        if (k.tmdb.isNotBlank() && InfoExterna.vencido(g.detalheEm, agora, g.detalhe?.noAr == true)) runCatching {
            val r = ServicosContas.pedir(InfoExterna.urlDetalhe(k.tmdb, id, serie, idioma), InfoExterna.tmdbCabecalhos(k.tmdb, agente()))
            InfoExterna.lerDetalhe(r.corpo, serie)?.takeIf { r.codigo in 200..299 }?.let { d ->
                g = mudar(app, chave) { it.copy(detalhe = d, detalheEm = agora, imdb = d.imdb.ifBlank { it.imdb }) }
            }
        }.onFailure { Log.w(TAG, "ficha: ${it.javaClass.simpleName}") }
        return guardado(app, chave) ?: g
    }

    /**
     * O id do TMDB de um título sem ir à rede, com a MESMA prioridade de [resolver]: o da lista (Xtream) se a pessoa
     * não o recusou ("Não é este"), senão o já achado. Antes, `doXtream ?: guardado.tmdb` voltava ao id recusado e a
     * nota da noite (Trakt/Simkl) e a idade do modo infantil vinham do filme errado.
     */
    fun tmdbSemRede(ctx: Context, chave: String, doXtream: Int?): Int? {
        val g = guardado(ctx.applicationContext, chave)
        return doXtream?.takeIf { g == null || it !in g.recusados } ?: g?.tmdb
    }

    /** "Não é este filme": o id atual vai para os recusados e a próxima abertura busca de novo. */
    fun recusar(ctx: Context, chave: String) {
        mudar(ctx.applicationContext, chave) { g -> Guardado(recusados = g.recusados + listOfNotNull(g.tmdb)) }
    }

    /** As partes da coleção (saga). Guardadas só na memória: é uma consulta por saga, ao abrir a ficha. */
    fun colecao(colecaoId: Int): List<InfoExterna.Candidato> {
        synchronized(colecoes) { colecoes[colecaoId]?.let { return it } }
        val k = chaves(); if (k.tmdb.isBlank()) return emptyList()
        val partes = runCatching {
            val r = ServicosContas.pedir(InfoExterna.urlColecao(k.tmdb, colecaoId, InfoExterna.idiomaTmdb()), InfoExterna.tmdbCabecalhos(k.tmdb, agente()))
            if (r.codigo in 200..299) InfoExterna.lerColecao(r.corpo) else emptyList()
        }.getOrDefault(emptyList())
        synchronized(colecoes) { colecoes[colecaoId] = partes }
        return partes
    }

    // ================================================================ ideias 83 e 84 (memória só: uma consulta por ficha)

    private val pedidos = object : LinkedHashMap<String, Any>(64, 0.75f, true) {
        override fun removeEldestEntry(e: MutableMap.MutableEntry<String, Any>) = size > 120
    }

    /** Pergunta ao TMDB (memória por [chave]); null sem chave do app, sem rede ou com resposta que não serve. */
    private fun <T : Any> tmdb(chave: String, url: (String) -> String, ler: (String) -> T?): T? {
        synchronized(pedidos) { @Suppress("UNCHECKED_CAST") (pedidos[chave] as? T)?.let { return it } }
        val k = chaves(); if (k.tmdb.isBlank()) return null
        val v = runCatching {
            val r = ServicosContas.pedir(url(k.tmdb), InfoExterna.tmdbCabecalhos(k.tmdb, agente()))
            if (r.codigo in 200..299) ler(r.corpo) else null
        }.onFailure { Log.w(TAG, "$chave: ${it.javaClass.simpleName}") }.getOrNull() ?: return null
        synchronized(pedidos) { pedidos[chave] = v }
        return v
    }

    /** Ideia 83: (título, resumo) de um episódio no idioma da TV. Bloqueia (rede). */
    fun episodio(serie: Int, temporada: Int, numero: Int): Pair<String, String>? =
        tmdb("ep:$serie:$temporada:$numero", { ElencoTmdb.urlEpisodio(it, serie, temporada, numero, InfoExterna.idiomaTmdb()) }, ElencoTmdb::lerEpisodio)

    /** Ideia 84: o elenco com foto. Bloqueia (rede). */
    fun elenco(id: Int, serie: Boolean): List<ElencoTmdb.Pessoa> {
        val idioma = InfoExterna.idiomaTmdb()
        val l = tmdb("elenco:$serie:$id", { ElencoTmdb.urlElenco(it, id, serie, idioma) }) { ElencoTmdb.lerElenco(it).ifEmpty { null } }.orEmpty()
        // Nome em outra escrita ("張震" numa TV em português): troca pelo nome em letras latinas da pessoa, se houver.
        return l.map { p ->
            if (!ElencoTmdb.precisaLatino(p.nome, idioma)) p
            else tmdb("nomelat:${p.id}", { ElencoTmdb.urlPessoa(it, p.id) }, ElencoTmdb::lerNomeLatino)?.let { p.copy(nome = it) } ?: p
        }
    }

    /** Ideia 84: em que filmes e séries a pessoa atuou. Bloqueia (rede). */
    fun creditos(pessoa: Int): ElencoTmdb.Creditos =
        tmdb("pessoa:$pessoa", { ElencoTmdb.urlCreditos(it, pessoa, InfoExterna.idiomaTmdb()) }, ElencoTmdb::lerCreditosCompletos)
            ?: ElencoTmdb.Creditos(emptySet(), emptySet())

    // ================================================================ ideia 82: onde assistir

    private fun paisTv() = OndeAssistir.pais(java.util.Locale.getDefault().country)

    /** Os serviços de assinatura/grátis onde o título está, no país da TV (vazio: nenhum ou sem chave). Bloqueia (rede). */
    fun provedores(id: Int, serie: Boolean): List<String> = if (!OndeAssistir.JUSTWATCH_LIBERADO) emptyList() else paisTv().let { pais ->
        tmdb("prov:$serie:$id:$pais", { OndeAssistir.urlProvedores(it, id, serie) }) { OndeAssistir.lerProvedores(it, pais) }.orEmpty()
    }

    /** Filmes e séries do TMDB com o termo (para achar o que não está na lista). Bloqueia (rede). */
    fun buscarFora(termo: String): List<OndeAssistir.Achado> =
        tmdb("multi:${termo.lowercase()}", { OndeAssistir.urlBusca(it, termo, InfoExterna.idiomaTmdb()) }, OndeAssistir::lerBusca).orEmpty()

    // ================================================================ Nota do Trakt (manutenção da noite)

    /**
     * Nota do Trakt para todos, sem login (16/11/2026, no lugar do arquivo do IMDb, que é só para uso não comercial).
     * De madrugada, para os títulos da lista com id do TMDB (da lista ou já achado) e nota vencida, uma pergunta por
     * título ao Trakt, no máximo [MAX_TRAKT_POR_NOITE], primeiro os que a pessoa marcou e depois os mais novos. Ritmo de
     * ~1,4 por segundo: o limite do Trakt é 500 GET a cada 5 min por chave e IP (developer.trakt.tv/docs/rate-limiting).
     * Devolve quantos ganharam nota, ou -1 sem chave/lista. Bloqueia (rede).
     */
    fun noturnoTrakt(ctx: Context): Int {
        val app = ctx.applicationContext
        val id = chaves().traktId.takeIf { it.isNotBlank() } ?: return -1
        val agora = System.currentTimeMillis()
        val fila = filaDaNoite(app, MAX_TRAKT_POR_NOITE) { agora - (it?.traktEm ?: 0) in 0 until InfoExterna.TRAKT_VALIDADE_MS } ?: return -1
        val cab = ServicosFilmes.traktCabecalhos(id, BuildConfig.VERSION_CODE)
        var n = 0
        for ((ch, tmdb, serie) in fila) {
            val r = runCatching { ServicosContas.pedir(InfoExterna.urlTraktPorTmdb(tmdb, serie), cab) }.getOrNull() ?: break
            if (r.codigo == 429) { Log.i(TAG, "Trakt: limite"); break }
            if (r.codigo !in 200..299) { Log.w(TAG, "Trakt: ${r.codigo}"); break }
            val nota = InfoExterna.lerNotaTrakt(r.corpo, serie)
            if (nota != null) n++
            synchronized(trava) {
                val m = mapa(app); val g = m[ch] ?: Guardado()
                // Sem nota (não achou ou poucos votos): guarda a data, para não perguntar de novo por 30 dias.
                por(m, ch, g.copy(tmdb = g.tmdb ?: tmdb, traktNota = nota, traktEm = agora))
            }
            Thread.sleep(700)
        }
        synchronized(trava) { gravar(app) }
        Log.i(TAG, "Trakt: $n notas de ${fila.size} títulos")
        return n
    }

    private const val MAX_TRAKT_POR_NOITE = 600

    /**
     * Os títulos da lista com id do TMDB (da lista ou já achado) que ainda não estão [emDia]: primeiro os que a pessoa
     * marcou, depois os mais novos; no máximo [max]. null sem lista. (chave, tmdb, série)
     */
    private fun filaDaNoite(app: Context, max: Int, emDia: (Guardado?) -> Boolean): List<Triple<String, Int, Boolean>>? {
        val filmes = runCatching { PlayerCarga.filmes(app, soCache = true) }.getOrDefault(emptyList())
        val series = runCatching { PlayerCarga.series(app, soCache = true) }.getOrDefault(emptyList())
        if (filmes.isEmpty() && series.isEmpty()) return null
        val marcados = PlayerArmazem.interesses(app, false) + PlayerArmazem.interesses(app, true) + PlayerArmazem.posicoes(app).keys
        return (filmes.map { Triple(it.chave, it.tmdb, false) to it.adicionado } + series.map { Triple(it.chave, it.tmdb, true) to it.atualizada })
            .sortedWith(compareBy({ it.first.first !in marcados }, { -it.second }))
            .map { it.first }
            .mapNotNull { (ch, t, s) ->
                val g = guardado(app, ch); val tmdb = t?.takeIf { g == null || it !in g.recusados } ?: g?.tmdb ?: return@mapNotNull null
                if (emDia(g)) null else Triple(ch, tmdb, s)
            }
            .distinctBy { it.first }
            .take(max)
    }

    // ================================================================ Nota do Simkl (manutenção da noite)

    /**
     * Nota da comunidade do Simkl para todos, sem login (16/11/2026). Como a do Trakt: de madrugada, até
     * [MAX_SIMKL_POR_NOITE] títulos, cada nota vale 30 dias. Uma pergunta por título (a ficha, em cache no Cloudflare). O
     * id do Simkl vem do que já sabemos (catálogo e biblioteca da pessoa); só título nunca visto passa pelo /redirect,
     * uma vez por id TMDB (pedido do Simkl, 18/11/2026). Para no primeiro erro ou
     * limite (429). Devolve quantos ganharam nota, ou -1 sem chave/lista. Bloqueia (rede).
     */
    fun noturnoSimkl(ctx: Context): Int {
        val app = ctx.applicationContext
        val cliente = chaves().simklId.takeIf { it.isNotBlank() } ?: return -1
        val agora = System.currentTimeMillis()
        val fila = filaDaNoite(app, MAX_SIMKL_POR_NOITE) { agora - (it?.simklEm ?: 0) in 0 until InfoExterna.SIMKL_VALIDADE_MS } ?: return -1
        val versao = BuildConfig.VERSION_CODE
        val cab = ServicosFilmes.simklCabecalhos(cliente, versao)
        // Ids já sabidos (catálogo + biblioteca da pessoa): o /redirect é busca ao vivo sem cache no Simkl e os ids não
        // mudam, então só pergunta por título nunca visto, e uma vez por id TMDB (não uma por versão). 18/11/2026.
        val conhecidos = InfoExterna.simklConhecidos(simklDoCatalogo(app), runCatching { ServicosSync.idsSimklDaBiblioteca(app) }.getOrDefault(emptyMap()))
        val semSimkl = HashSet<String>()
        var n = 0; var redirects = 0
        for ((ch, tmdb, serie) in fila) {
            val k = InfoExterna.chaveTmdb(tmdb, serie)
            var simkl = guardado(app, ch)?.simkl ?: conhecidos[k]
            if (simkl == null && k !in semSimkl) {
                val r = runCatching { ServicosContas.pedir(InfoExterna.urlSimklRedirect(tmdb, serie, cliente, versao), cab) }.getOrNull() ?: break
                redirects++
                if (r.codigo == 429) { Log.i(TAG, "Simkl: limite"); break }
                simkl = if (r.codigo in 300..399) InfoExterna.idSimklDoLocal(r.local, serie) else null
                if (simkl == null && r.codigo !in 300..499) { Log.w(TAG, "Simkl redirect: ${r.codigo}"); break }
                if (simkl != null) conhecidos[k] = simkl else semSimkl += k
                Thread.sleep(300)
            }
            var nota: Float? = null
            if (simkl != null) {
                // Ficha falhou: guarda ao menos o id que o /redirect achou (sem data: a nota é pedida de novo amanhã,
                // mas sem repetir o /redirect).
                fun guardarSoId() = synchronized(trava) { val m = mapa(app); val g = m[ch] ?: Guardado(); if (g.simkl == null) por(m, ch, g.copy(simkl = simkl)) }
                val r = runCatching { ServicosContas.pedir(InfoExterna.urlSimklFicha(simkl, serie, cliente, versao), cab) }.getOrNull() ?: run { guardarSoId(); null } ?: break
                if (r.codigo == 429) { Log.i(TAG, "Simkl: limite"); guardarSoId(); break }
                if (r.codigo !in 200..299 && r.codigo != 404) { Log.w(TAG, "Simkl: ${r.codigo}"); guardarSoId(); break }
                nota = InfoExterna.lerNotaSimkl(r.corpo)
            }
            if (nota != null) n++
            synchronized(trava) {
                val m = mapa(app); val g = m[ch] ?: Guardado()
                // Sem nota (o Simkl não tem ou poucos votos): guarda a data, para não perguntar de novo por 30 dias.
                por(m, ch, g.copy(tmdb = g.tmdb ?: tmdb, simkl = simkl, simklNota = nota, simklEm = agora))
            }
            Thread.sleep(500)
        }
        synchronized(trava) { gravar(app) }
        Log.i(TAG, "Simkl: $n notas de ${fila.size} títulos, $redirects /redirect")
        return n
    }

    /** (tmdb, série, id do Simkl guardado) de cada título do catálogo com id TMDB. Sem rede. */
    private fun simklDoCatalogo(app: Context): List<Triple<Int, Boolean, Int?>> {
        val filmes = runCatching { PlayerCarga.filmes(app, soCache = true) }.getOrDefault(emptyList())
        val series = runCatching { PlayerCarga.series(app, soCache = true) }.getOrDefault(emptyList())
        return synchronized(trava) {
            val m = mapa(app)
            filmes.mapNotNull { f -> val g = m[f.chave]; val t = f.tmdb ?: g?.tmdb ?: return@mapNotNull null; Triple(t, false, g?.simkl) } +
                series.mapNotNull { x -> val g = m[x.chave]; val t = x.tmdb ?: g?.tmdb ?: return@mapNotNull null; Triple(t, true, g?.simkl) }
        }
    }

    private const val MAX_SIMKL_POR_NOITE = 400

    /** Os títulos da grade que têm id TMDB (da lista ou já achado) para o lote: chave -> id. */
    fun idsConhecidos(ctx: Context, itens: List<Pair<String, Int?>>): List<Pair<String, Int>> = synchronized(trava) {
        val m = mapa(ctx)
        itens.mapNotNull { (k, t) -> (t ?: m[k]?.tmdb)?.let { k to it } }
    }
}
