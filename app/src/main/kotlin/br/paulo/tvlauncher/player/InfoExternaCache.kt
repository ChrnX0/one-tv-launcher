package br.paulo.tvlauncher.player

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.LauncherApp
import br.paulo.tvlauncher.player.InfoExterna.Guardado
import br.paulo.tvlauncher.weather.DataSources
import java.io.File
import org.json.JSONObject

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
    /** TMDB (pago): só com contrato, a chave no build e o plano Pro ([DataSources.tmdb], 19/11/2026). */
    fun temTmdb(): Boolean = runCatching { DataSources.tmdb(LauncherApp.instancia) }.getOrDefault(false)
    /** Ficha de fora de algum jeito: TMDB (Pro) ou Trakt (Básico, grátis). */
    fun temFicha(): Boolean = temTmdb() || chaves().traktId.isNotBlank()
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
     * Garante id e ficha de um título, se vencidos. [tmdbConhecido] = o que o Xtream mandou. A ficha vem do TMDB no
     * plano Pro com contrato ([temTmdb]) e do Trakt no resto ([resolverTrakt]); o id guardado é sempre o do TMDB.
     * Devolve o que ficou guardado (pode ser vazio: sem chave, sem rede ou sem casar o nome).
     */
    fun resolver(ctx: Context, chave: String, nome: String, ano: Int?, serie: Boolean, tmdbConhecido: Int?): Guardado {
        val app = ctx.applicationContext
        val agora = System.currentTimeMillis()
        var g = guardado(app, chave) ?: Guardado()
        val k = chaves()
        if (!temTmdb()) return resolverTrakt(app, chave, nome, ano, serie, tmdbConhecido)
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

    /** Pergunta ao Trakt com a chave do app; null sem chave, sem rede ou fora de 2xx (204 = corpo vazio). */
    private fun trakt(url: String): String? {
        val id = chaves().traktId.takeIf { it.isNotBlank() } ?: return null
        val r = runCatching { ServicosContas.pedir(url, ServicosFilmes.traktCabecalhos(id, BuildConfig.VERSION_CODE)) }
            .onFailure { Log.w(TAG, "Trakt: ${it.javaClass.simpleName}") }.getOrNull() ?: return null
        // Sem isto uma recusa do Trakt (403, 429...) deixava a ficha vazia sem rastro (19/11/2026, conferência na Sala).
        if (r.codigo !in 200..299) Log.w(TAG, "Trakt ${url.substringAfter("trakt.tv").substringBefore('?')}: HTTP ${r.codigo}")
        return r.takeIf { it.codigo in 200..299 }?.corpo
    }

    /**
     * Plano Básico (19/11/2026): id e ficha pelo Trakt. A busca casa o nome como a do TMDB ([InfoExterna.escolher]);
     * o Trakt devolve o título em inglês, então, sem casar, confere a tradução no idioma da TV dos até 3 candidatos de
     * ano certo (nome dublado da lista: "O Poderoso Chefão"). Ficha: item + tradução + pessoas (+ próximo episódio e
     * temporadas na série), até 5 perguntas por título a cada 30 dias.
     */
    private fun resolverTrakt(app: Context, chave: String, nome: String, ano: Int?, serie: Boolean, tmdbConhecido: Int?): Guardado {
        if (chaves().traktId.isBlank()) { Log.w(TAG, "ficha Trakt: build sem TRAKT_ID"); return guardado(app, chave) ?: Guardado() }
        val agora = System.currentTimeMillis()
        var g = guardado(app, chave) ?: Guardado()
        val (lingua, pais) = TraktDetails.idioma()
        val doXtream = tmdbConhecido?.takeIf { it !in g.recusados }
        if (doXtream != null && doXtream != g.tmdb) g = mudar(app, chave) { it.copy(tmdb = doXtream, detalhe = null, detalheEm = 0, traktNota = null, traktEm = 0, simkl = null, simklNota = null, simklEm = 0) }
        if (g.tmdb == null && g.buscaEm > 0) esquecerBuscasAntigas(app)?.let { g = it[chave] ?: g }
        if (g.tmdb == null && agora - g.buscaEm > BUSCA_DE_NOVO_MS) {
            val recusados = g.recusados
            fun procurar(b: TraktDetails.Busca, anoBusca: Int?): Int? {
                val titulo = b.alvo
                val limpo = LegendaExterna.nomeDeBusca(b.consulta).ifBlank { b.consulta }
                val achados = trakt(TraktDetails.urlBusca(limpo, anoBusca, serie))?.let { TraktDetails.lerBusca(it, serie) }.orEmpty()
                    .filter { it.tmdb !in recusados }
                Log.i(TAG, "busca Trakt '$limpo' ano=${anoBusca ?: "-"}: ${achados.size} (${achados.take(3).joinToString { "${it.titulo} ${it.ano ?: ""}".trim() }})")
                if (b.direto) InfoExterna.escolher(TraktDetails.candidatos(achados), titulo, anoBusca, recusados)?.id?.let { return it }
                if (lingua == "en") return null
                return achados.filter { anoBusca == null || it.ano == null || kotlin.math.abs(it.ano - anoBusca) <= 1 }.take(4).firstOrNull { a ->
                    val t = trakt(TraktDetails.urlTraducao(a.trakt, serie, lingua))?.let { TraktDetails.lerTraducao(it, pais) }
                    if (t != null && !TraktDetails.mesmoTitulo(t.first, titulo)) Log.i(TAG, "busca Trakt: tradução '${t.first}' não casa")
                    t != null && TraktDetails.mesmoTitulo(t.first, titulo)
                }?.tmdb
            }
            // Ano errado na lista do IPTV (relançamento, ano da cópia) é comum: sem nada com o ano, tenta sem ele, como o TMDB.
            // Nome "dublado - original" da lista ("Lei e Ordem: Unidade de Vítimas Especiais - Law & Order", Sala 19/11/2026)
            // não casa com nada: tenta também só a parte dublada (o Trakt busca nas traduções).
            val achado = TraktDetails.nomesDeBusca(Vod.nomeLimpo(nome)).firstNotNullOfOrNull { b ->
                procurar(b, ano) ?: if (ano != null) procurar(b, null) else null
            }
            g = mudar(app, chave) { it.copy(tmdb = achado, buscaEm = if (achado == null) agora else 0) }
        }
        val id = g.tmdb ?: run { Log.i(TAG, "ficha Trakt: $chave sem id do TMDB (busca de novo em ${((g.buscaEm + BUSCA_DE_NOVO_MS - agora) / 3_600_000).coerceAtLeast(0)} h)"); return g }
        // Ficha que veio do TMDB (só ele traz pôster) numa TV que agora usa o Trakt: troca já, sem esperar vencer, para
        // nada do TMDB aparecer sem a licença.
        val doTmdb = g.detalhe?.poster?.isNotEmpty() == true
        if (doTmdb || InfoExterna.vencido(g.detalheEm, agora, g.detalhe?.noAr == true)) runCatching {
            val item = trakt(TraktDetails.urlPorTmdb(id, serie))?.let { TraktDetails.lerPorTmdb(it, serie) }
                ?: run { Log.w(TAG, "ficha Trakt: tmdb $id sem item"); return@runCatching }
            val t = TraktDetails.traktDe(item) ?: run { Log.w(TAG, "ficha Trakt: tmdb $id sem id do Trakt"); return@runCatching }
            synchronized(traktIds) { traktIds[InfoExterna.chaveTmdb(id, serie)] = t }
            val traducao = if (lingua == "en") null else trakt(TraktDetails.urlTraducao(t, serie, lingua))?.let { TraktDetails.lerTraducao(it, pais) }
            val pessoas = trakt(TraktDetails.urlPessoas(t, serie))?.let { TraktDetails.lerPessoas(it) } ?: (emptyList<String>() to "")
            val proximo = if (serie) trakt(TraktDetails.urlProximo(t))?.let { TraktDetails.lerProximo(it) } else null
            val temporadas = if (serie) trakt(TraktDetails.urlTemporadas(t))?.let { TraktDetails.lerTemporadas(it) } ?: 0 else 0
            val d = TraktDetails.detalhe(id, item, serie, traducao, pessoas, proximo, temporadas)
            g = mudar(app, chave) { it.copy(detalhe = d, detalheEm = agora, imdb = d.imdb.ifBlank { it.imdb }) }
            Log.i(TAG, "ficha Trakt: tmdb $id ok (sinopse ${d.sinopse.isNotBlank()}, elenco ${d.elenco.size}, próximo ${d.proximo != null})")
        }.onFailure { Log.w(TAG, "ficha Trakt: ${it.javaClass.simpleName}") }
        else Log.i(TAG, "ficha Trakt: tmdb $id guardada de ${(agora - g.detalheEm) / 3_600_000} h atrás")
        return guardado(app, chave) ?: g
    }

    /**
     * Resumo das últimas [n] fichas guardadas, só leitura (`EXTRAS --es o fichas`): sem isto não dava para saber, na
     * Sala, se a ficha vazia era do Trakt, do TMDB antigo ou de nenhum dos dois (19/11/2026).
     */
    fun resumoAdb(ctx: Context, n: Int): String = synchronized(trava) {
        val agora = System.currentTimeMillis()
        val m = mapa(ctx.applicationContext)
        val linhas = m.entries.toList().takeLast(n.coerceIn(1, 100)).reversed().map { (k, g) ->
            val d = g.detalhe
            "$k tmdb=${g.tmdb ?: "-"} " + if (d == null) "sem ficha (busca ${if (g.buscaEm > 0) "${(agora - g.buscaEm) / 3_600_000} h atrás" else "-"})"
            else "ficha ${(agora - g.detalheEm) / 3_600_000} h atrás: sinopse=${d.sinopse.length} elenco=${d.elenco.size} " +
                "próximo=${d.proximo != null} noAr=${d.noAr} temporadas=${d.temporadas} pôster=${d.poster.isNotEmpty()}"
        }
        "títulos=${m.size} trakt=${chaves().traktId.isNotBlank()} tmdb=${temTmdb()}\n" + linhas.joinToString("\n")
    }

    /**
     * Uma vez por TV: buscas que falharam antes do Trakt virar a fonte do Básico (19/11/2026) esperavam 7 dias para
     * tentar de novo, então a série que o TMDB não achou ficava sem ficha mesmo com o Trakt achando. Zera a espera delas.
     * Devolve o mapa quando zerou algo (null quando já foi feito).
     */
    private fun esquecerBuscasAntigas(app: Context): Map<String, Guardado>? {
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getBoolean(PREF_BUSCA_TRAKT, false)) return null
        return synchronized(trava) {
            val m = mapa(app)
            var n = 0
            for ((k, g) in m.entries.toList()) if (g.tmdb == null && g.buscaEm > 0) { m[k] = g.copy(buscaEm = 0); sujos.add(k); n++ }
            if (n > 0) gravar(app)
            prefs.edit().putBoolean(PREF_BUSCA_TRAKT, true).apply()
            Log.i(TAG, "ficha Trakt: $n buscas antigas liberadas")
            m
        }
    }
    private const val PREFS = "fichas"
    private const val PREF_BUSCA_TRAKT = "busca_trakt_v3"   // v3: busca também pelo nome original (19/11/2026)

    /** Id do Trakt de cada título já visto ("F123"/"S45" -> trakt), só na memória. */
    private val traktIds = HashMap<String, Int>()

    private fun traktDaSerie(tmdb: Int): Int? {
        val k = InfoExterna.chaveTmdb(tmdb, true)
        synchronized(traktIds) { traktIds[k]?.let { return it } }
        val t = trakt(TraktDetails.urlPorTmdb(tmdb, true))?.let { TraktDetails.traktDe(TraktDetails.lerPorTmdb(it, true)) } ?: return null
        synchronized(traktIds) { traktIds[k] = t }
        return t
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
        val k = chaves(); if (!temTmdb()) return emptyList()
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
        val k = chaves(); if (!temTmdb()) return null
        val v = runCatching {
            val r = ServicosContas.pedir(url(k.tmdb), InfoExterna.tmdbCabecalhos(k.tmdb, agente()))
            if (r.codigo in 200..299) ler(r.corpo) else null
        }.onFailure { Log.w(TAG, "$chave: ${it.javaClass.simpleName}") }.getOrNull() ?: return null
        synchronized(pedidos) { pedidos[chave] = v }
        return v
    }

    /** Ideia 83: (título, resumo) de um episódio no idioma da TV. Bloqueia (rede). */
    fun episodio(serie: Int, temporada: Int, numero: Int): Pair<String, String>? =
        if (!temTmdb()) episodioTrakt(serie, temporada, numero) else tmdb("ep:$serie:$temporada:$numero", { ElencoTmdb.urlEpisodio(it, serie, temporada, numero, InfoExterna.idiomaTmdb()) }, ElencoTmdb::lerEpisodio)

    /** Ideia 83 no plano Básico: o episódio pelo Trakt (com a tradução no idioma da TV quando houver). */
    private fun episodioTrakt(serie: Int, temporada: Int, numero: Int): Pair<String, String>? {
        val chave = "ep-trakt:$serie:$temporada:$numero"
        synchronized(pedidos) { @Suppress("UNCHECKED_CAST") (pedidos[chave] as? Pair<String, String>)?.let { return it } }
        val t = traktDaSerie(serie) ?: return null
        val (lingua, pais) = TraktDetails.idioma()
        val corpo = trakt(TraktDetails.urlEpisodio(t, temporada, numero)) ?: return null
        val traducao = if (lingua == "en") null else trakt(TraktDetails.urlTraducaoEpisodio(t, temporada, numero, lingua))?.let { TraktDetails.lerTraducao(it, pais) }
        val v = TraktDetails.lerEpisodio(corpo, traducao) ?: return null
        synchronized(pedidos) { pedidos[chave] = v }
        return v
    }

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
     * Nota da comunidade do Simkl, SÓ para quem conectou a própria conta Simkl (17/11/2026), salvo com a licença e o
     * plano Pro ([DataSources.simklSemLogin], 19/11/2026). A Simkl respondeu que sincronizar a conta da pessoa não pede
     * licença comercial, mas mostrar a nota sem login pede; então o pedido vai com o token da pessoa e, sem conta (e sem a
     * licença), nada é pedido e as notas guardadas são apagadas ([esquecerSimkl]). Como a do Trakt: de madrugada, até
     * [MAX_SIMKL_POR_NOITE] títulos, cada nota vale 30 dias. Uma pergunta por título (a ficha, em cache no Cloudflare). O
     * id do Simkl vem do que já sabemos (catálogo e biblioteca da pessoa); só título nunca visto passa pelo /redirect,
     * uma vez por id TMDB (pedido do Simkl, 18/11/2026). Para no primeiro erro ou
     * limite (429). Devolve quantos ganharam nota, ou -1 sem chave/lista. Bloqueia (rede).
     */
    fun noturnoSimkl(ctx: Context): Int {
        val app = ctx.applicationContext
        val cliente = chaves().simklId.takeIf { it.isNotBlank() } ?: return -1
        // Sem a conta Simkl da pessoa e sem a licença: nenhum pedido, e some a nota que tiver ficado (de antes de
        // 17/11/2026 ou de quem saiu). Com a licença (Pro), sem conta pede só com a chave do app.
        val token = ServicosContas.conta(app, ServicosFilmes.Servico.SIMKL)?.acesso?.takeIf { it.isNotBlank() }
        if (token == null && !DataSources.simklSemLogin(app)) { esquecerSimkl(app); return -1 }
        val agora = System.currentTimeMillis()
        val fila = filaDaNoite(app, MAX_SIMKL_POR_NOITE) { agora - (it?.simklEm ?: 0) in 0 until InfoExterna.SIMKL_VALIDADE_MS } ?: return -1
        val versao = BuildConfig.VERSION_CODE
        val cab = ServicosFilmes.simklCabecalhos(cliente, versao, token)
        // Ids já sabidos (catálogo + biblioteca da pessoa): o /redirect é busca ao vivo sem cache no Simkl e os ids não
        // mudam, então só pergunta por título nunca visto, e uma vez por id TMDB (não uma por versão). 18/11/2026.
        val conhecidos = InfoExterna.simklConhecidos(simklDoCatalogo(app), runCatching { ServicosSync.idsSimklDaBiblioteca(app) }.getOrDefault(emptyMap()))
        val semSimkl = HashSet<String>()
        // Depois os arquivos prontos (sem limite, sem token): quem estiver neles já sai com id e nota, sem pergunta.
        val prontos = if (fila.isEmpty()) emptyMap() else arquivosSimkl(cliente, versao)
        var n = 0; var redirects = 0; var dosArquivos = 0
        for ((ch, tmdb, serie) in fila) {
            val k = InfoExterna.chaveTmdb(tmdb, serie)
            val doArquivo = prontos[serie to tmdb]
            var simkl = guardado(app, ch)?.simkl ?: conhecidos[k] ?: doArquivo?.id
            if (doArquivo != null && doArquivo.id == simkl) {
                dosArquivos++
                if (doArquivo.nota != null) n++
                synchronized(trava) {
                    val m = mapa(app); val g = m[ch] ?: Guardado()
                    por(m, ch, g.copy(tmdb = g.tmdb ?: tmdb, simkl = simkl, simklNota = doArquivo.nota, simklEm = agora))
                }
                continue
            }
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
        Log.i(TAG, "Simkl: $n notas de ${fila.size} títulos, $redirects /redirect, $dosArquivos dos arquivos prontos")
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

    /**
     * Baixa e junta os [InfoExterna.ARQUIVOS_SIMKL], um de cada vez (o texto de um sai da memória antes do próximo).
     * Arquivo que falhar fica de fora; os títulos dele caem no caminho de antes (/redirect + ficha). Bloqueia (rede).
     */
    private fun arquivosSimkl(cliente: String, versao: Int): Map<Pair<Boolean, Int>, InfoExterna.SimklDoArquivo> {
        val r = HashMap<Pair<Boolean, Int>, InfoExterna.SimklDoArquivo>()
        val cab = mapOf("User-Agent" to ServicosFilmes.agente(versao), "Accept" to "application/json")
        for ((caminho, serie) in InfoExterna.ARQUIVOS_SIMKL) {
            val resp = runCatching { ServicosContas.pedir(ServicosFilmes.simklUrlArquivo(caminho, cliente, versao), cab, maxBytes = MAX_ARQUIVO_SIMKL) }.getOrNull()
            if (resp == null || resp.codigo !in 200..299) { Log.w(TAG, "Simkl arquivo $caminho: ${resp?.codigo}"); continue }
            // lerAte corta calado no limite: arquivo maior vira JSON quebrado e cai inteiro. Avisa para o limite ser revisto.
            if (resp.corpo.length >= MAX_ARQUIVO_SIMKL / 2 && resp.corpo.toByteArray().size >= MAX_ARQUIVO_SIMKL) {
                Log.w(TAG, "Simkl arquivo $caminho passou de $MAX_ARQUIVO_SIMKL bytes: ignorado"); continue
            }
            for ((k, v) in InfoExterna.lerArquivoSimkl(resp.corpo, serie)) r.putIfAbsent(k, v)
        }
        return r
    }

    private const val MAX_SIMKL_POR_NOITE = 400
    private const val MAX_ARQUIVO_SIMKL = 6_000_000

    /** Apaga a nota do Simkl de todos os títulos (fica o id, que é só identificador). Sem rede. */
    fun esquecerSimkl(ctx: Context) {
        val app = ctx.applicationContext
        synchronized(trava) {
            val m = mapa(app)
            val com = m.filterValues { it.simklNota != null || it.simklEm > 0 }.keys.toList()
            if (com.isEmpty()) return
            // No lugar (sem [por]: não muda a ordem de quem sai primeiro), mas marcado, para a releitura não desfazer.
            for (ch in com) m[ch]?.let { m[ch] = it.copy(simklNota = null, simklEm = 0); sujos.add(ch) }
            gravar(app)
            Log.i(TAG, "Simkl: ${com.size} notas apagadas (sem conta)")
        }
    }

    /** Os títulos da grade que têm id TMDB (da lista ou já achado) para o lote: chave -> id. */
    fun idsConhecidos(ctx: Context, itens: List<Pair<String, Int?>>): List<Pair<String, Int>> = synchronized(trava) {
        val m = mapa(ctx)
        itens.mapNotNull { (k, t) -> (t ?: m[k]?.tmdb)?.let { k to it } }
    }
}
