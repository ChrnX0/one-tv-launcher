package br.paulo.tvlauncher.player

import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.family.Classificacao
import java.io.File
import org.json.JSONObject

/**
 * Ideia 91: a classificação indicativa de cada filme e série (pelo id do TMDB), pedida aos poucos e guardada em
 * files/player/idades.json ({"f": {"<id>": [idade, quando]}, "s": {...}}). Só é pedida com o modo infantil ligado e uma
 * idade máxima escolhida; cada abertura de Filmes ou Séries pergunta no máximo [POR_VEZ] títulos novos.
 * Não vai no backup: refaz sozinho.
 */
object IdadesTmdb {
    private const val TAG = "TvLauncher.Idades"
    private const val ARQUIVO = "idades.json"
    const val POR_VEZ = 300
    private const val VALE_MS = 90L * 24 * 3_600_000      // classificação conhecida: pergunta de novo depois de 90 dias
    private const val SEM_VALE_MS = 20L * 24 * 3_600_000  // sem classificação: tenta de novo em 20 dias
    private val trava = Any()

    /** O que Filmes e Séries precisam para filtrar: nulo quando o filtro por idade não está valendo. */
    data class Filtro(val max: Int, val infantis: Set<String>, val duras: Set<String>, val idades: Map<Int, Int>)

    private fun arquivo(ctx: Context) = File(PlayerArmazem.pasta(ctx), ARQUIVO)

    private fun ler(ctx: Context): JSONObject = runCatching { JSONObject(arquivo(ctx).readText()) }.getOrDefault(JSONObject())

    /** id TMDB -> idade (Classificacao.SEM quando o TMDB não tem). */
    fun idades(ctx: Context, serie: Boolean): Map<Int, Int> = synchronized(trava) {
        val o = ler(ctx).optJSONObject(if (serie) "s" else "f") ?: return emptyMap()
        val r = HashMap<Int, Int>(o.length() * 2)
        for (k in o.keys()) { val id = k.toIntOrNull() ?: continue; o.optJSONArray(k)?.let { r[id] = it.optInt(0, Classificacao.SEM) } }
        r
    }

    /**
     * O filtro para esta tela, ou nulo (modo infantil desligado, ou sem idade máxima). [grupos] = as categorias do
     * catálogo (para as "duras": escondidas, adultas, controle parental).
     */
    fun filtro(ctx: Context, grupos: Collection<String>, serie: Boolean): Filtro? {
        val r = PlayerArmazem.parental(ctx)
        if (!r.infantil || r.idadeMax == Classificacao.DESLIGADA) return null
        val duras = VodFiltro.ocultas(grupos, PlayerArmazem.gruposEscondidos(ctx), PlayerArmazem.gruposLiberados(ctx), r.copy(infantil = false))
        return Filtro(r.idadeMax, r.infantis, duras, idades(ctx, serie))
    }

    // ---------------------------------------------------------------- catálogo inteiro de madrugada (16/11/2026)
    // Só pela abertura de Filmes (300 por vez), 21 mil filmes levavam ~70 aberturas: o "Até 12 anos" da Sala mostrava
    // 1 filme. Agora a manutenção da madrugada classifica tudo aos poucos. O TMDB limita a VELOCIDADE (~40 pedidos/s,
    // sem teto diário): um pedido por vez, com [INTERVALO_MS] entre eles, fica bem abaixo disso.
    private const val INTERVALO_MS = 50L          // no máximo 20 pedidos/s (na prática menos: cada pedido espera a resposta)
    private const val LOTE = 500                  // grava o arquivo a cada lote: uma noite interrompida não perde o feito
    const val PRAZO_MS = 40L * 60_000             // por noite; o que faltar continua na seguinte
    private val rodando = java.util.concurrent.atomic.AtomicBoolean(false)   // a madrugada e o EXTRAS "idades" juntos: só um laço

    /**
     * Classifica os filmes e as séries guardados (só o cache, nada é baixado da lista) que têm id do TMDB, até [prazoMs]
     * ou até [parar] dizer que a TV voltou a ser usada. Categorias adultas ficam de fora (nunca aparecem mesmo).
     * Bloqueia (rede). Devolve quantos títulos ganharam classificação.
     */
    fun catalogo(ctx: Context, prazoMs: Long = PRAZO_MS, parar: () -> Boolean = { false }): Int {
        if (!rodando.compareAndSet(false, true)) return 0
        try {
            val fim = System.currentTimeMillis() + prazoMs
            val filmes = runCatching { PlayerCarga.filmes(ctx, soCache = true) }.getOrDefault(emptyList())
                .filter { !PlayerFiltro.ehAdulto(it.grupo) }.mapNotNull { InfoExternaCache.tmdbSemRede(ctx, it.chave, it.tmdb) }
            val series = runCatching { PlayerCarga.series(ctx, soCache = true) }.getOrDefault(emptyList())
                .filter { !PlayerFiltro.ehAdulto(it.grupo) }.mapNotNull { InfoExternaCache.tmdbSemRede(ctx, it.chave, it.tmdb) }
            var total = 0
            for ((serie, ids) in listOf(false to filmes, true to series)) {
                while (System.currentTimeMillis() < fim && !parar()) {
                    val n = classificar(ctx, serie, ids, LOTE, INTERVALO_MS, fim, parar)
                    total += n
                    if (n == 0) break   // nada faltando, sem rede, ou o TMDB pediu calma (429): para por hoje
                }
            }
            Log.i(TAG, "madrugada: $total classificados (${filmes.size} filmes e ${series.size} séries com id do TMDB)")
            return total
        } finally { rodando.set(false) }
    }

    /** Quantos títulos já têm classificação guardada (filmes, séries), para o EXTRAS "idades". */
    fun contagem(ctx: Context): Pair<Int, Int> = idades(ctx, false).size to idades(ctx, true).size

    /** Pergunta ao TMDB a classificação dos [ids] ainda não sabidos (ou vencidos), no máximo [limite]. Bloqueia (rede). Devolve quantos mudaram. */
    fun classificar(ctx: Context, serie: Boolean, ids: Collection<Int>, limite: Int = POR_VEZ, intervaloMs: Long = 0L,
                    ate: Long = Long.MAX_VALUE, parar: () -> Boolean = { false }): Int {
        // TMDB só no Pro com contrato (DataSources, 19/11/2026); no Básico a classificação americana do Trakt.
        val usarTmdb = InfoExternaCache.temTmdb()
        val k = ServicosContas.chavesApp().let { if (usarTmdb) it.tmdb else it.traktId }.takeIf { it.isNotBlank() } ?: return 0
        val agora = System.currentTimeMillis()
        val tipo = if (serie) "s" else "f"
        val conhecidos = synchronized(trava) { ler(ctx).optJSONObject(tipo) ?: JSONObject() }
        val faltam = ids.asSequence().distinct().filter { id ->
            val a = conhecidos.optJSONArray(id.toString()) ?: return@filter true
            agora - a.optLong(1) > if (a.optInt(0, Classificacao.SEM) >= 0) VALE_MS else SEM_VALE_MS
        }.take(limite).toList()
        if (faltam.isEmpty()) return 0
        val achados = HashMap<Int, Int>()
        val agente = ServicosFilmes.agente(BuildConfig.VERSION_CODE)
        for (id in faltam) {
            if (System.currentTimeMillis() >= ate || parar()) break
            // Trakt: 500 GET a cada 5 min por chave e IP (developer.trakt.tv/docs/rate-limiting): no mínimo 650 ms entre pedidos.
            val espera = if (usarTmdb) intervaloMs else maxOf(intervaloMs, 650L)
            if (espera > 0) Thread.sleep(espera)
            val r = runCatching {
                if (usarTmdb) ServicosContas.pedir(if (serie) Classificacao.urlSerie(k, id) else Classificacao.urlFilme(k, id), InfoExterna.tmdbCabecalhos(k, agente))
                else ServicosContas.pedir(TraktDetails.urlPorTmdb(id, serie), ServicosFilmes.traktCabecalhos(k, BuildConfig.VERSION_CODE))
            }
                .onFailure { Log.w(TAG, "classificar: ${it.javaClass.simpleName}") }.getOrNull() ?: break   // sem rede: para e tenta na próxima
            if (r.codigo == 429) break
            if (r.codigo == 404) { achados[id] = Classificacao.SEM; continue }
            if (r.codigo !in 200..299) continue
            (if (!usarTmdb) TraktDetails.idade(r.corpo, serie) else if (serie) Classificacao.lerSerie(r.corpo) else Classificacao.lerFilme(r.corpo))
                ?.let { achados[id] = it }
        }
        if (achados.isEmpty()) return 0
        synchronized(trava) {
            val tudo = ler(ctx)
            val o = tudo.optJSONObject(tipo) ?: JSONObject().also { tudo.put(tipo, it) }
            for ((id, idade) in achados) o.put(id.toString(), org.json.JSONArray().put(idade).put(agora))
            runCatching {
                val tmp = File(arquivo(ctx).path + ".tmp")
                tmp.writeText(tudo.toString()); tmp.renameTo(arquivo(ctx))
            }
        }
        Log.i(TAG, "${achados.size} classificações (${if (serie) "séries" else "filmes"})")
        return achados.size
    }
}
