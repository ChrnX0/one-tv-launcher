package br.paulo.tvlauncher.observer

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong
import org.json.JSONObject

/**
 * Observador, fase 2 ("memória"): a linha de base DESTA casa, por hora da semana (20/11/2026).
 *
 * Porquê: um limite fixo (latência > 400 ms) não vê o que é estranho PARA ESTA TV. Se a latência daqui é sempre 30 ms
 * às 21h de terça, 250 ms nesse horário já é um sinal, mesmo abaixo do limite; se sempre é 300 ms, não é.
 * Como: para cada sinal guardado, 168 "gavetas" (dia da semana × hora) e 24 de reserva (só a hora, enche em 1 dia
 * enquanto as da semana enchem em 1 semana). Cada gaveta guarda uma média e um desvio típico que andam devagar
 * (média móvel exponencial do valor e do desvio absoluto: um pouco de memória, sem guardar a série).
 * Robusta: um valor muito fora entra cortado em [CLIP] desvios, então uma noite de queda não estraga a semana.
 * Pura (sem Android): ObserverBaselineTest. Quem calcula a hora local e grava é o ObserverTv.
 */
class ObserverBaseline {

    /** Uma gaveta: média, desvio absoluto médio e quantas leituras já entraram (para 0 a [MATURE]). */
    data class Cell(val mean: Double, val dev: Double, val n: Int)

    private val week = HashMap<ObserverCore.Signal, Array<Cell?>>()
    private val day = HashMap<ObserverCore.Signal, Array<Cell?>>()

    /** Junta uma leitura. [hourOfWeek] 0..167 (0 = domingo 0h, como o Calendar), [hourOfDay] 0..23. */
    @Synchronized fun learn(s: ObserverCore.Signal, value: Long, hourOfWeek: Int, hourOfDay: Int) {
        if (s !in LEARNED) return
        week.getOrPut(s) { arrayOfNulls(168) }.let { it[hourOfWeek] = step(it[hourOfWeek], value.toDouble(), floorOf(s)) }
        day.getOrPut(s) { arrayOfNulls(24) }.let { it[hourOfDay] = step(it[hourOfDay], value.toDouble(), floorOf(s)) }
    }

    /** A gaveta que vale para julgar agora: a da semana, se madura; senão a do dia, se madura; senão nenhuma. */
    @Synchronized fun expected(s: ObserverCore.Signal, hourOfWeek: Int, hourOfDay: Int): Cell? =
        week[s]?.get(hourOfWeek)?.takeIf { it.n >= MATURE } ?: day[s]?.get(hourOfDay)?.takeIf { it.n >= MATURE }

    /**
     * Quão fora do normal está [value], em desvios robustos, só no lado RUIM do sinal (latência alta, memória baixa,
     * Wi-Fi fraco, quadros lentos). Null = não dá para dizer (gaveta imatura ou sinal que não aprende).
     */
    fun badness(s: ObserverCore.Signal, value: Long, hourOfWeek: Int, hourOfDay: Int): Double? {
        val c = expected(s, hourOfWeek, hourOfDay) ?: return null
        val spread = max(c.dev * MAD_TO_SIGMA, floorOf(s))
        val z = (value - c.mean) / spread
        return if (badIsHigh(s)) z else -z
    }

    @Synchronized fun toJson(): JSONObject = JSONObject().apply {
        put("v", 1)
        for (s in LEARNED) {
            val w = week[s]; val d = day[s]
            if (w == null && d == null) continue
            put(s.name, JSONObject().put("w", cells(w)).put("d", cells(d)))
        }
    }

    private fun cells(a: Array<Cell?>?) = org.json.JSONArray().apply {
        a?.forEach { c -> put(if (c == null) JSONObject.NULL else org.json.JSONArray().put(round1(c.mean)).put(round1(c.dev)).put(c.n)) }
    }

    companion object {
        /** Sinais que têm linha de base (pressão de memória e internet são eventos, não medidas contínuas; disco só desce). */
        val LEARNED = setOf(ObserverCore.Signal.LATENCY_MS, ObserverCore.Signal.MEMORY_AVAILABLE_MB,
            ObserverCore.Signal.WIFI_DBM, ObserverCore.Signal.SLOW_FRAMES_PCT)

        /** Peso de cada leitura nova (1/32: ~32 leituras de memória; com 12 por hora, umas 3 semanas por gaveta da semana). */
        const val ALPHA = 1.0 / 32
        /** Leituras antes de a gaveta valer (12 = uma semana de amostras de 5 min naquela hora; 1 dia para a do dia). */
        const val MATURE = 12
        /** Valor que chega além disso (em desvios) entra cortado: um apagão não vira o novo normal. */
        const val CLIP = 3.0
        /** Desvio absoluto médio → desvio padrão de uma normal (≈1,25); arredondado para cima, conservador. */
        const val MAD_TO_SIGMA = 1.25
        /** A partir de quantos desvios no lado ruim o juiz anota "fora do normal desta casa". */
        const val UNUSUAL_Z = 4.0

        /** Menor desvio que vale (sinal quase constante não pode virar "anormal" com 1 ms a mais). */
        fun floorOf(s: ObserverCore.Signal): Double = when (s) {
            ObserverCore.Signal.LATENCY_MS -> 20.0
            ObserverCore.Signal.MEMORY_AVAILABLE_MB -> 40.0
            ObserverCore.Signal.WIFI_DBM -> 3.0
            ObserverCore.Signal.SLOW_FRAMES_PCT -> 3.0
            else -> 1.0
        }

        fun badIsHigh(s: ObserverCore.Signal): Boolean =
            s == ObserverCore.Signal.LATENCY_MS || s == ObserverCore.Signal.SLOW_FRAMES_PCT

        /** Um passo da gaveta. A primeira leitura vira a média; o desvio começa no piso e aprende. */
        fun step(c: Cell?, x: Double, floor: Double): Cell {
            if (c == null) return Cell(x, floor, 1)
            val spread = max(c.dev * MAD_TO_SIGMA, floor)
            val clipped = x.coerceIn(c.mean - CLIP * spread, c.mean + CLIP * spread)
            // Nas primeiras leituras o peso é maior (média simples até 1/ALPHA), para não levar semanas a sair do primeiro valor.
            val a = max(ALPHA, 1.0 / (c.n + 1))
            val mean = c.mean + a * (clipped - c.mean)
            val dev = c.dev + a * (abs(clipped - c.mean) - c.dev)
            return Cell(mean, dev, minOf(c.n + 1, 10_000))
        }

        private fun round1(d: Double) = (d * 10).roundToLong() / 10.0

        fun fromJson(o: JSONObject?): ObserverBaseline {
            val b = ObserverBaseline()
            if (o == null || o.optInt("v") != 1) return b
            for (s in LEARNED) {
                val so = o.optJSONObject(s.name) ?: continue
                read(so.optJSONArray("w"), 168)?.let { b.week[s] = it }
                read(so.optJSONArray("d"), 24)?.let { b.day[s] = it }
            }
            return b
        }

        private fun read(a: org.json.JSONArray?, size: Int): Array<Cell?>? {
            if (a == null || a.length() != size) return null
            return Array(size) { i -> a.optJSONArray(i)?.let { c -> Cell(c.getDouble(0), c.getDouble(1), c.getInt(2)) } }
        }
    }
}
