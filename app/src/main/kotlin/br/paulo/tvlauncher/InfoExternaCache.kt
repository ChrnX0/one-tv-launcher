package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.InfoExterna.Guardado
import org.json.JSONObject
import java.io.File

/**
 * Disco e rede da ficha e das notas de fora ([InfoExterna]). Tudo aqui bloqueia (rede): chamar fora da thread da tela.
 *
 * files/player/externo.json: por título do catálogo, o id do TMDB, a ficha resumida e as notas, cada uma com a data.
 * Vale 30 dias (série no ar: 3). Nunca pede a cada foco: a ficha pede ao abrir; as notas da grade vêm em lote, no máximo
 * [LOTES_POR_DIA] pedidos por dia (a cota grátis do MDBList, 1.000 por dia, é por conta e dividida entre as TVs).
 */
object InfoExternaCache {
    private const val TAG = "TvLauncher.InfoExterna"
    private const val ARQUIVO = "externo.json"
    private const val MAX_TITULOS = 6000
    private const val LOTES_POR_DIA = 20
    private const val BUSCA_DE_NOVO_MS = 7L * 24 * 3_600_000

    private val trava = Any()
    @Volatile private var memoria: LinkedHashMap<String, Guardado>? = null
    private val colecoes = HashMap<Int, List<InfoExterna.Candidato>>()

    private fun chaves() = ServicosContas.chavesApp()
    fun temTmdb(): Boolean = chaves().tmdb.isNotBlank()
    fun temNotas(ctx: Context): Boolean = ServicosContas.chaveMdblist(ctx) != null
    private fun agente() = ServicosFilmes.agente(BuildConfig.VERSION_CODE)

    // ================================================================ disco

    private fun mapa(ctx: Context): LinkedHashMap<String, Guardado> = memoria ?: synchronized(trava) {
        memoria ?: LinkedHashMap<String, Guardado>().also { m ->
            runCatching {
                val o = JSONObject(File(PlayerArmazem.pasta(ctx), ARQUIVO).readText())
                for (k in o.keys()) o.optJSONObject(k)?.let { m[k] = InfoExterna.guardadoDeJson(it) }
            }
            memoria = m
        }
    }

    private fun gravar(ctx: Context) = synchronized(trava) {
        val m = mapa(ctx)
        while (m.size > MAX_TITULOS) m.remove(m.keys.first())   // os mais antigos saem
        runCatching {
            val o = JSONObject(); for ((k, g) in m) o.put(k, InfoExterna.guardadoParaJson(g))
            val arq = File(PlayerArmazem.pasta(ctx), ARQUIVO)
            val tmp = File(arq.path + ".tmp"); tmp.writeText(o.toString()); check(tmp.renameTo(arq))
        }.onFailure { Log.w(TAG, "não gravei: ${it.javaClass.simpleName}") }
    }

    /** O que já está guardado (sem rede). Rápido: pode chamar da tela depois da primeira leitura. */
    fun guardado(ctx: Context, chave: String): Guardado? = synchronized(trava) { mapa(ctx)[chave] }

    /** Nota de 0 a 10 de fora (IMDb...), para o Filtro e a ordem. Só o que está guardado. */
    fun notas(ctx: Context): Map<String, Float> = synchronized(trava) {
        mapa(ctx).mapNotNull { (k, g) -> InfoExterna.notasDe(g).de0a10?.let { k to it } }.toMap()
    }

    private fun mudar(ctx: Context, chave: String, f: (Guardado) -> Guardado): Guardado = synchronized(trava) {
        val m = mapa(ctx)
        val novo = f(m.remove(chave) ?: Guardado())   // tira e põe de volta: vai para o fim (mais recente)
        m[chave] = novo
        gravar(ctx)
        novo
    }

    // ================================================================ ficha (ao abrir)

    /**
     * Garante id, ficha do TMDB e notas do MDBList de um título, se vencidos. [tmdbConhecido] = o que o Xtream mandou.
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
        if (doXtream != null && doXtream != g.tmdb) g = mudar(app, chave) { it.copy(tmdb = doXtream, detalhe = null, detalheEm = 0, notas = null, notasEm = 0) }
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
        // 3. Notas (um lote de 1).
        if (InfoExterna.vencido(g.notasEm, agora)) notasEmLote(app, listOf(Triple(chave, id, serie)), contarCota = false)
        return guardado(app, chave) ?: g
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

    // ================================================================ notas em lote (grade)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("info_externa", Context.MODE_PRIVATE)

    /** Cabe mais um lote hoje? (conta por dia, em UTC como a cota do MDBList) */
    private fun cabeLote(ctx: Context): Boolean {
        val dia = System.currentTimeMillis() / 86_400_000
        val p = prefs(ctx)
        val n = if (p.getLong("dia", -1) == dia) p.getInt("lotes", 0) else 0
        if (n >= LOTES_POR_DIA) return false
        p.edit().putLong("dia", dia).putInt("lotes", n + 1).apply()
        return true
    }

    /**
     * Notas de vários títulos (chave, id TMDB, é série) com nota vencida: pedidos de até 100 ids. Devolve quantos
     * ganharam nota. [contarCota] = false só na ficha aberta (um título, a pessoa pediu).
     */
    fun notasEmLote(ctx: Context, itens: List<Triple<String, Int, Boolean>>, contarCota: Boolean = true): Int {
        val app = ctx.applicationContext
        val chave = ServicosContas.chaveMdblist(app) ?: return 0
        val agora = System.currentTimeMillis()
        val vencidos = itens.filter { (k, _, _) -> InfoExterna.vencido(guardado(app, k)?.notasEm ?: 0, agora) }
        var n = 0
        for ((serie, grupo) in vencidos.groupBy { it.third }) for (lote in grupo.chunked(InfoExterna.LOTE_MDBLIST)) {
            if (contarCota && !cabeLote(app)) return n
            val r = runCatching {
                ServicosContas.pedir(InfoExterna.urlLoteMdblist(chave, serie), mapOf("User-Agent" to agente(), "Content-Type" to "application/json"),
                    InfoExterna.corpoLoteMdblist(lote.map { it.second }))
            }.getOrNull() ?: return n
            if (r.codigo == 429) { Log.i(TAG, "MDBList: limite"); return n }
            if (r.codigo !in 200..299) { Log.w(TAG, "MDBList: ${r.codigo}"); return n }
            val notas = InfoExterna.lerLote(r.corpo)
            synchronized(trava) {
                val m = mapa(app)
                for ((k, id, _) in lote) {
                    val g = m.remove(k) ?: Guardado(tmdb = id)
                    // Sem nota no MDBList: guarda vazio com a data, para não perguntar de novo por 30 dias.
                    m[k] = g.copy(tmdb = g.tmdb ?: id, notas = notas[id] ?: InfoExterna.Notas(), notasEm = agora)
                    if (notas[id]?.vazia == false) n++
                }
                gravar(app)
            }
        }
        return n
    }

    // ================================================================ IMDb semanal (manutenção da noite)

    /**
     * Nota do IMDb para todos, sem chave (pedido do dono, 14/11/2026): 1x por semana, de madrugada. 1. Para os títulos da
     * lista com id do TMDB e sem id do IMDb, pergunta ao TMDB (no máximo [MAX_IDS_POR_NOITE], primeiro os que a pessoa
     * marcou, depois os mais novos na lista). 2. Baixa o arquivo público do IMDb e guarda só as notas desses ids.
     * Devolve quantos títulos têm nota do IMDb depois. Bloqueia (rede, ~9 MB).
     */
    fun semanalImdb(ctx: Context, forcar: Boolean = false): Int {
        val app = ctx.applicationContext
        val p = prefs(app)
        val agora = System.currentTimeMillis()
        if (!forcar && agora - p.getLong("imdb_em", 0) < InfoExterna.IMDB_VALIDADE_MS) return -1
        val filmes = runCatching { PlayerCarga.filmes(app, soCache = true) }.getOrDefault(emptyList())
        val series = runCatching { PlayerCarga.series(app, soCache = true) }.getOrDefault(emptyList())
        if (filmes.isEmpty() && series.isEmpty()) return -1
        val marcados = PlayerArmazem.interesses(app, false) + PlayerArmazem.interesses(app, true) + PlayerArmazem.posicoes(app).keys
        // 1. ids do IMDb que faltam (pelo TMDB).
        val k = chaves()
        if (k.tmdb.isNotBlank()) {
            val faltam = (filmes.map { Triple(it.chave, it.tmdb, false) to it.adicionado } + series.map { Triple(it.chave, it.tmdb, true) to it.atualizada })
                .sortedWith(compareBy({ it.first.first !in marcados }, { -it.second }))
                .map { it.first }
                .mapNotNull { (ch, t, s) -> val g = guardado(app, ch); val id = t ?: g?.tmdb; if (id != null && g?.imdb.isNullOrBlank()) Triple(ch, id, s) else null }
                .take(MAX_IDS_POR_NOITE)
            for ((ch, id, s) in faltam) {
                val imdb = runCatching {
                    val r = ServicosContas.pedir(InfoExterna.urlIdsExternos(k.tmdb, id, s), InfoExterna.tmdbCabecalhos(k.tmdb, agente()))
                    if (r.codigo == 429) return@runCatching null
                    if (r.codigo in 200..299) InfoExterna.lerIdImdb(r.corpo) else null
                }.getOrNull()
                synchronized(trava) {
                    val m = mapa(app); val g = m.remove(ch) ?: Guardado()
                    m[ch] = g.copy(tmdb = g.tmdb ?: id, imdb = imdb ?: g.imdb.ifBlank { "-" })   // "-" = o TMDB não tem: não perguntar de novo
                }
                Thread.sleep(120)   // ~8 por segundo: bem abaixo do limite do TMDB
            }
            synchronized(trava) { gravar(app) }
        }
        // 2. o arquivo do IMDb.
        val porImdb = synchronized(trava) { mapa(app).entries.filter { InfoExterna.idImdbValido(it.value.imdb) }.groupBy({ it.value.imdb }, { it.key }) }
        if (porImdb.isEmpty()) { p.edit().putLong("imdb_em", agora).apply(); return 0 }
        val notas = runCatching {
            val c = java.net.URL(InfoExterna.IMDB_NOTAS).openConnection() as java.net.HttpURLConnection
            try {
                c.connectTimeout = 15_000; c.readTimeout = 60_000; c.instanceFollowRedirects = false
                c.setRequestProperty("User-Agent", agente())
                check(c.responseCode in 200..299) { "IMDb ${c.responseCode}" }
                java.util.zip.GZIPInputStream(c.inputStream).bufferedReader().useLines { InfoExterna.filtrarImdb(it, porImdb.keys) }
            } finally { c.disconnect() }
        }.onFailure { Log.w(TAG, "IMDb: ${it.javaClass.simpleName}") }.getOrNull() ?: return -1
        synchronized(trava) {
            val m = mapa(app)
            for ((imdb, chaves) in porImdb) for (ch in chaves) m[ch]?.let { m[ch] = it.copy(imdbNota = notas[imdb]) }
            gravar(app)
        }
        p.edit().putLong("imdb_em", agora).apply()
        Log.i(TAG, "IMDb: ${notas.size} notas de ${porImdb.size} títulos")
        return notas.size
    }

    private const val MAX_IDS_POR_NOITE = 1500

    /** Os títulos da grade que têm id TMDB (da lista ou já achado) para o lote: chave -> id. */
    fun idsConhecidos(ctx: Context, itens: List<Pair<String, Int?>>): List<Pair<String, Int>> = synchronized(trava) {
        val m = mapa(ctx)
        itens.mapNotNull { (k, t) -> (t ?: m[k]?.tmdb)?.let { k to it } }
    }
}
