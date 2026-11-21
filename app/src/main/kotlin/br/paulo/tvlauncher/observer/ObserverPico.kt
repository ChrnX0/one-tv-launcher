package br.paulo.tvlauncher.observer

import kotlin.math.abs
import kotlin.math.exp
import org.json.JSONArray
import org.json.JSONObject

/**
 * Observador, fase 2 ("interface de decisão e pico", 20/11/2026). O modelo NOSSO, local, sem depender de ninguém
 * (Paulo: "a gente não consegue nós mesmos criar um modelo nosso local para não depender da TypeSafe? se sim, faz").
 *
 * Interface (a mesma que um modelo de fora vai poder ocupar depois, fase 4): três respostas tipadas, nunca texto livre.
 *  - [score]: probabilidade de 0 a 1 de "sim" para uma pergunta ([Task]) dadas as medidas ([features]);
 *  - [choose]: a melhor de várias opções, com a confiança;
 *  - "noul" (não sei): [choose] devolve [Answer.NotSure] quando a confiança ou a diferença para a segunda opção é pequena,
 *    (toda pergunta começa com pesos de regra, então nunca está "vazia"). Não saber é uma resposta válida, e
 *    quem pergunta cai na regra escrita.
 *
 * O "pico": uma regressão logística por pergunta, de poucos números (um peso por medida), que começa com pesos de regra
 * ([priors], o que a regra escrita já faria) e aprende com cada resultado ([learn], descida de gradiente com passo que
 * diminui). Cabe em qualquer TV (32 bits, 2 GB), roda em microssegundos, explica-se: [explain] diz o peso de cada medida.
 * Fase 2: só sombra (o juiz anota a nota do pico no registro e nada muda). Fase 3: as ações aprendem com o resultado.
 * Pura (sem Android): ObserverPicoTest.
 */
class ObserverPico {

    /** Perguntas que o Observador faz. Cada uma tem as suas medidas, na ordem de [FEATURES]. */
    enum class Task { WORTH_TELLING, ACT_NOW }

    sealed class Answer {
        data class Sure(val option: String, val confidence: Double) : Answer()
        data class NotSure(val reason: String) : Answer()
    }

    private class Model(val w: DoubleArray, var n: Int)

    private val models = HashMap<Task, Model>()

    private fun model(t: Task) = models.getOrPut(t) { Model(priors(t).copyOf(), 0) }

    /** Probabilidade de "sim". [x] na ordem de [FEATURES] (sem o viés; ele é o primeiro peso). */
    @Synchronized fun score(t: Task, x: DoubleArray): Double {
        val m = model(t)
        require(x.size == m.w.size - 1) { "esperava ${m.w.size - 1} medidas para $t" }
        var z = m.w[0]
        for (i in x.indices) z += m.w[i + 1] * x[i]
        return sigmoid(z)
    }

    /** A melhor opção, ou "não sei". Cada opção traz as próprias medidas (ex.: agir agora / esperar / perguntar). */
    @Synchronized fun choose(t: Task, options: Map<String, DoubleArray>): Answer {
        if (options.isEmpty()) return Answer.NotSure("nenhuma opção")
        val ranked = options.mapValues { score(t, it.value) }.entries.sortedByDescending { it.value }
        val best = ranked[0]
        val margin = best.value - (ranked.getOrNull(1)?.value ?: 0.0)
        return when {
            best.value < MIN_CONFIDENCE -> Answer.NotSure("confiança ${pct(best.value)} abaixo de ${pct(MIN_CONFIDENCE)}")
            margin < MIN_MARGIN -> Answer.NotSure("${best.key} e ${ranked[1].key} quase empatadas")
            else -> Answer.Sure(best.key, best.value)
        }
    }

    /** O resultado de uma decisão: [yes] = a resposta certa era "sim". Um passo de gradiente, com regularização leve para a regra. */
    @Synchronized fun learn(t: Task, x: DoubleArray, yes: Boolean) {
        val m = model(t)
        val p = score(t, x)
        val err = (if (yes) 1.0 else 0.0) - p
        val rate = RATE / (1 + m.n / 50.0)
        val prior = priors(t)
        m.w[0] += rate * (err - L2 * (m.w[0] - prior[0]))
        for (i in x.indices) m.w[i + 1] += rate * (err * x[i] - L2 * (m.w[i + 1] - prior[i + 1]))
        m.n = minOf(m.n + 1, 1_000_000)
    }

    /** Quanto cada medida empurra para o "sim" agora (peso × valor), para o porquê no registro. */
    @Synchronized fun explain(t: Task, x: DoubleArray): List<Pair<String, Double>> {
        val m = model(t)
        return FEATURES.getValue(t).mapIndexed { i, nome -> nome to m.w[i + 1] * x[i] }.sortedByDescending { abs(it.second) }
    }

    @Synchronized fun samples(t: Task): Int = models[t]?.n ?: 0

    @Synchronized fun toJson(): JSONObject = JSONObject().put("v", 1).apply {
        for ((t, m) in models) put(t.name, JSONObject().put("n", m.n).put("w", JSONArray().apply { m.w.forEach { put(Math.round(it * 1e4) / 1e4) } }))
    }

    companion object {
        /** Nomes das medidas de cada pergunta (o viés não entra). Mudar a lista = pesos guardados viram os de regra de novo. */
        val FEATURES = mapOf(
            // Vale avisar a pessoa? gravidade (0 a 2), desvios do normal da casa (0 a 8), a TV costuma estar em uso nesta
            // hora (0 a 1), há quantas horas o problema está aberto (0 a 6), quantos avisos do mesmo tipo nas últimas 24 h (0 a 5).
            Task.WORTH_TELLING to listOf("severity", "unusual_z", "habit_use", "open_hours", "told_24h"),
            // Agir sozinho agora? gravidade, desvios, uso nesta hora, tela acesa (0/1), minutos desde a última tecla / 60 (0 a 6).
            Task.ACT_NOW to listOf("severity", "unusual_z", "habit_use", "screen_on", "idle_hours"),
        )

        /** Pesos de partida = a regra escrita: avisar o que é grave e persistente, sem repetir; agir com a casa parada. */
        fun priors(t: Task): DoubleArray = when (t) {
            Task.WORTH_TELLING -> doubleArrayOf(-1.0, 2.2, 0.25, 0.8, 0.4, -1.2)   // WARNING novo sem mais nada = 77% (a regra avisa); INFO = 27%
            Task.ACT_NOW -> doubleArrayOf(-1.5, 1.2, 0.2, -2.0, -2.5, 0.6)
        }

        const val MIN_CONFIDENCE = 0.7
        const val MIN_MARGIN = 0.15
        const val RATE = 0.2
        const val L2 = 0.01

        fun sigmoid(z: Double) = 1.0 / (1.0 + exp(-z.coerceIn(-30.0, 30.0)))
        private fun pct(p: Double) = "${Math.round(p * 100)}%"

        fun fromJson(o: JSONObject?): ObserverPico {
            val r = ObserverPico()
            if (o == null || o.optInt("v") != 1) return r
            for (t in Task.entries) {
                val m = o.optJSONObject(t.name) ?: continue
                val w = m.optJSONArray("w") ?: continue
                if (w.length() != priors(t).size) continue   // medidas mudaram: volta à regra
                r.models[t] = Model(DoubleArray(w.length()) { w.getDouble(it) }, m.optInt("n"))
            }
            return r
        }
    }
}
