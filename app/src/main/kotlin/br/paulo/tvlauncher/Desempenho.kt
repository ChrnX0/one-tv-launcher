package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Guarda de desempenho por versão (12/11/2026, ideia 4). O objetivo nº 1 do launcher é ser rápido; esta peça percebe
 * sozinha quando uma versão nova deixou a HOME mais lenta, sem shell e sem PC.
 *
 * Como mede: a HOME conta o tempo de cada quadro desenhado (FrameMetrics do Android, dentro do próprio app) num
 * histograma de 1 ms (0 a [MAX_MS]; o último balde junta tudo acima). Um histograma por versão, só as duas últimas.
 * Quando a versão atual e a anterior têm [MIN_QUADROS] quadros cada, compara o p90 (o quadro "lento típico"): piorou
 * mais de [LIMITE_PIORA] e de pelo menos [MIN_DIFERENCA_MS] = alerta (uma vez por versão).
 *
 * Só a conta pura, testada em DesempenhoTest. A parte do Android é DesempenhoTv.
 */
object Desempenho {
    const val MAX_MS = 250
    const val MIN_QUADROS = 1500
    const val LIMITE_PIORA = 0.15
    const val MIN_DIFERENCA_MS = 3

    /** Quadros por milissegundo (índice = ms, o último = MAX_MS ou mais) + aberturas da HOME (ms, as últimas 20). */
    class Medida(val baldes: IntArray = IntArray(MAX_MS + 1), val aberturas: MutableList<Int> = ArrayList()) {
        val total: Int get() = baldes.sum()
        fun quadro(ms: Double) { baldes[ms.toInt().coerceIn(0, MAX_MS)]++ }
        fun abertura(ms: Int) { aberturas += ms.coerceIn(0, 60_000); while (aberturas.size > 20) aberturas.removeAt(0) }
        fun somar(outra: Medida) { for (i in baldes.indices) baldes[i] += outra.baldes[i]; outra.aberturas.forEach { abertura(it) } }
    }

    /** Percentil [p] (0..100) do histograma, em ms. Null sem quadros. */
    fun percentil(m: Medida, p: Int): Int? {
        val total = m.total
        if (total == 0) return null
        val alvo = Math.ceil(total * p / 100.0).toLong().coerceAtLeast(1)
        var soma = 0L
        for (i in m.baldes.indices) { soma += m.baldes[i]; if (soma >= alvo) return i }
        return MAX_MS
    }

    fun mediana(l: List<Int>): Int? = if (l.isEmpty()) null else l.sorted()[l.size / 2]

    data class Comparacao(val p90Antes: Int, val p90Agora: Int, val piorou: Boolean) {
        val porcento: Int get() = if (p90Antes == 0) 0 else ((p90Agora - p90Antes) * 100.0 / p90Antes).toInt()
    }

    /** Compara a atual com a anterior. Null = ainda sem quadros suficientes em alguma das duas. */
    fun comparar(anterior: Medida, atual: Medida): Comparacao? {
        if (anterior.total < MIN_QUADROS || atual.total < MIN_QUADROS) return null
        val a = percentil(anterior, 90)!!; val b = percentil(atual, 90)!!
        val piorou = b - a >= MIN_DIFERENCA_MS && b > a * (1 + LIMITE_PIORA)
        return Comparacao(a, b, piorou)
    }

    fun paraJson(m: Medida): JSONObject {
        // Esparso: só os baldes com quadro ("ms": contagem). Um dia de HOME cabe em ~1 KB.
        val b = JSONObject()
        m.baldes.forEachIndexed { i, n -> if (n > 0) b.put(i.toString(), n) }
        return JSONObject().put("baldes", b).put("aberturas", JSONArray(m.aberturas))
    }

    fun deJson(o: JSONObject?): Medida {
        val m = Medida()
        if (o == null) return m
        o.optJSONObject("baldes")?.let { b ->
            for (k in b.keys()) { val i = k.toIntOrNull() ?: continue; if (i in 0..MAX_MS) m.baldes[i] = b.optInt(k).coerceAtLeast(0) }
        }
        o.optJSONArray("aberturas")?.let { a -> for (i in 0 until minOf(a.length(), 20)) m.aberturas += a.optInt(i) }
        return m
    }

    /** Resumo de uma versão para o painel. */
    fun resumo(versao: Long, m: Medida): JSONObject = JSONObject().put("versao", versao).put("quadros", m.total)
        .put("p50", percentil(m, 50) ?: JSONObject.NULL).put("p90", percentil(m, 90) ?: JSONObject.NULL)
        .put("abertura", mediana(m.aberturas) ?: JSONObject.NULL)
}
