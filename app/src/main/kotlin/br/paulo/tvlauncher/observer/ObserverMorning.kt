package br.paulo.tvlauncher.observer

import br.paulo.tvlauncher.panel.eventos
import org.json.JSONArray
import org.json.JSONObject

/**
 * Observador, fase 2d ("relatório da manhã", 20/11/2026).
 *
 * Porquê: a sonda da madrugada tira uma fotografia dos sinais com a casa parada, sempre na mesma situação (sem ninguém
 * vendo, depois da manutenção). Comparar noite com noite mostra o que piora devagar e que nenhum limite fixo pega:
 * o espaço livre que encolhe um pouco por dia, a memória livre que cai depois de uma atualização, o Wi-Fi que perdeu
 * força porque alguém mudou o roteador de lugar.
 *
 * Como: as noites ficam numa lista própria ([appendNight], até [MAX_NIGHTS]), fora do registro (que corta em 300 linhas
 * e poderia perder a noite anterior num dia barulhento). [report] compara a noite mais nova com a MEDIANA das anteriores
 * (até [COMPARE_NIGHTS]); a mediana ignora uma noite estranha. Mudou = passou do mínimo absoluto E do relativo do sinal
 * ([THRESHOLDS]), para o lado bom ou ruim. Junta os achados das últimas 24 h do registro, por tipo.
 * Quem mostra (o rosto do Observador, o painel, o MCP) recebe tudo tipado: nada de frase pronta aqui.
 * Pura (sem Android): ObserverMorningTest.
 */
object ObserverMorning {

    data class Night(val at: Long, val signals: Map<ObserverCore.Signal, Long>)

    enum class Change { BETTER, SAME, WORSE }

    data class Item(val signal: ObserverCore.Signal, val tonight: Long, val usual: Long, val change: Change)

    data class Report(
        val night: Night?,
        /** Quantas noites anteriores entraram na mediana (0 = primeira noite, nada a comparar). */
        val compared: Int,
        val items: List<Item>,
        /** Achados que apareceram nas últimas 24 h, por tipo. */
        val events24h: Map<String, Int>,
    ) {
        val worse get() = items.count { it.change == Change.WORSE }
        val better get() = items.count { it.change == Change.BETTER }

        fun toJson(): JSONObject = JSONObject()
            .put("night_at", night?.at ?: JSONObject.NULL)
            .put("compared_nights", compared)
            .put("worse", worse).put("better", better)
            .put("items", JSONArray().apply {
                items.forEach { put(JSONObject().put("signal", it.signal.name).put("unit", it.signal.unit)
                    .put("tonight", it.tonight).put("usual", it.usual).put("change", it.change.name)) }
            })
            .put("events_24h", JSONObject().apply { events24h.forEach { (k, n) -> put(k, n) } })
    }

    /** Mínimo absoluto e relativo para contar como mudança, e se mais é melhor. */
    data class Threshold(val abs: Long, val rel: Double, val higherIsBetter: Boolean)

    val THRESHOLDS = linkedMapOf(
        ObserverCore.Signal.MEMORY_AVAILABLE_MB to Threshold(60, 0.15, true),
        ObserverCore.Signal.DISK_FREE_MB to Threshold(200, 0.10, true),
        ObserverCore.Signal.LATENCY_MS to Threshold(15, 0.30, false),
        ObserverCore.Signal.WIFI_DBM to Threshold(4, 0.0, true),
    )

    const val MAX_NIGHTS = 14
    const val COMPARE_NIGHTS = 7
    /** A noite mais nova só vale para "esta manhã" se tiver menos de 20 h (senão a sonda não rodou ontem). */
    const val FRESH_MS = 20 * 3_600_000L

    fun nightOf(at: Long, snapshot: Map<ObserverCore.Signal, ObserverCore.Reading>) =
        Night(at, snapshot.mapValues { it.value.value })

    /** Põe [n] na frente; até [MAX_NIGHTS]. Texto estragado = começa vazio. */
    fun appendNight(stored: String?, n: Night): String {
        val antigas = readNights(stored)
        return JSONArray().apply { (listOf(n) + antigas).take(MAX_NIGHTS).forEach { put(toJson(it)) } }.toString()
    }

    fun readNights(stored: String?): List<Night> {
        val a = try { JSONArray(stored ?: "[]") } catch (_: Exception) { return emptyList() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val s = o.optJSONObject("signals") ?: JSONObject()
            Night(o.optLong("at"), ObserverCore.Signal.entries.filter { s.has(it.name) }.associateWith { s.getLong(it.name) })
        }.sortedByDescending { it.at }
    }

    private fun toJson(n: Night) = JSONObject().put("at", n.at)
        .put("signals", JSONObject().apply { n.signals.forEach { (k, v) -> put(k.name, v) } })

    /** O relatório desta manhã. [log] = registro do ObserverCore (mais novo primeiro). */
    fun report(nights: List<Night>, log: JSONArray, now: Long): Report {
        val eventos = linkedMapOf<String, Int>()
        for (i in 0 until log.length()) {
            val o = log.optJSONObject(i) ?: continue
            if (now - o.optLong("at") > 86_400_000L || o.optString("event") != "appeared") continue
            val k = o.optString("kind").takeIf { it.isNotEmpty() && it != "null" } ?: continue
            eventos[k] = (eventos[k] ?: 0) + 1
        }
        val ordenadas = nights.sortedByDescending { it.at }
        val esta = ordenadas.firstOrNull()?.takeIf { now - it.at in 0..FRESH_MS }
            ?: return Report(null, 0, emptyList(), eventos)
        val antes = ordenadas.drop(1).take(COMPARE_NIGHTS)
        val itens = THRESHOLDS.mapNotNull { (s, t) ->
            val hoje = esta.signals[s]?.takeIf { valid(s, it) } ?: return@mapNotNull null
            val valores = antes.mapNotNull { n -> n.signals[s]?.takeIf { valid(s, it) } }
            if (valores.isEmpty()) return@mapNotNull null
            val usual = median(valores)
            val d = hoje - usual
            val mudou = Math.abs(d) >= t.abs && Math.abs(d) >= t.rel * Math.abs(usual)
            val ch = when {
                !mudou -> Change.SAME
                (d > 0) == t.higherIsBetter -> Change.BETTER
                else -> Change.WORSE
            }
            Item(s, hoje, usual, ch)
        }
        return Report(esta, antes.size, itens, eventos)
    }

    /** Sem Wi-Fi (cabo, ou a medida falhou) o HistoricoRede grava 0 ou -127: não é um sinal, fica fora da comparação. */
    fun valid(s: ObserverCore.Signal, v: Long) = s != ObserverCore.Signal.WIFI_DBM || v in -126L..-1L

    /** Mediana; com número par, a média das duas do meio (arredondada para baixo). */
    fun median(v: List<Long>): Long {
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else Math.floorDiv(s[s.size / 2 - 1] + s[s.size / 2], 2L)
    }
}
