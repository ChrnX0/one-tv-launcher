package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

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

    /** Pergunta ao TMDB a classificação dos [ids] ainda não sabidos (ou vencidos), no máximo [POR_VEZ]. Bloqueia (rede). Devolve quantos mudaram. */
    fun classificar(ctx: Context, serie: Boolean, ids: Collection<Int>): Int {
        val k = ServicosContas.chavesApp().tmdb.takeIf { it.isNotBlank() } ?: return 0
        val agora = System.currentTimeMillis()
        val tipo = if (serie) "s" else "f"
        val conhecidos = synchronized(trava) { ler(ctx).optJSONObject(tipo) ?: JSONObject() }
        val faltam = ids.asSequence().distinct().filter { id ->
            val a = conhecidos.optJSONArray(id.toString()) ?: return@filter true
            agora - a.optLong(1) > if (a.optInt(0, Classificacao.SEM) >= 0) VALE_MS else SEM_VALE_MS
        }.take(POR_VEZ).toList()
        if (faltam.isEmpty()) return 0
        val achados = HashMap<Int, Int>()
        val agente = ServicosFilmes.agente(BuildConfig.VERSION_CODE)
        for (id in faltam) {
            val r = runCatching { ServicosContas.pedir(if (serie) Classificacao.urlSerie(k, id) else Classificacao.urlFilme(k, id), InfoExterna.tmdbCabecalhos(k, agente)) }
                .onFailure { Log.w(TAG, "classificar: ${it.javaClass.simpleName}") }.getOrNull() ?: break   // sem rede: para e tenta na próxima
            if (r.codigo == 429) break
            if (r.codigo == 404) { achados[id] = Classificacao.SEM; continue }
            if (r.codigo !in 200..299) continue
            (if (serie) Classificacao.lerSerie(r.corpo) else Classificacao.lerFilme(r.corpo))?.let { achados[id] = it }
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
