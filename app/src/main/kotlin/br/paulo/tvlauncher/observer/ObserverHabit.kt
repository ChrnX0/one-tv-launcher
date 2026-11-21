package br.paulo.tvlauncher.observer

import kotlin.math.max
import org.json.JSONArray
import org.json.JSONObject

/**
 * Observador, fase 2 ("sonda noturna no horário aprendido", 20/11/2026). Paulo: "entender o hábito do usuário para
 * saber quando rodar".
 *
 * Porquê: a manutenção da madrugada rodava às 4h fixas. Numa casa onde alguém vê TV até as 4h30 ela adiava toda noite;
 * numa casa que acorda às 5h ela corria o risco de pegar gente ligando a TV. Como: a cada amostra de rede (5 min) o
 * Observador marca se a TV estava EM USO naquela hora do dia (tela acesa e controle tocado há menos de 1 h). Cada uma
 * das 24 horas guarda a fração de uso (média que anda devagar). A janela da madrugada é a de [WINDOW] horas (o alarme e
 * a nova tentativa 1 h depois, ver ManutencaoRegras.decidir), começando entre
 * [EARLIEST] e [LATEST], com menos uso; empate fica com a mais perto das 4h. Sem dados suficientes = 4h (o de sempre).
 * Pura (sem Android): ObserverHabitTest.
 */
class ObserverHabit {

    data class Hour(val use: Double, val n: Int)

    private val hours = arrayOfNulls<Hour>(24)

    @Synchronized fun learn(hourOfDay: Int, inUse: Boolean) {
        val h = hours[hourOfDay]
        val x = if (inUse) 1.0 else 0.0
        hours[hourOfDay] = if (h == null) Hour(x, 1) else {
            val a = max(ALPHA, 1.0 / (h.n + 1))
            Hour(h.use + a * (x - h.use), minOf(h.n + 1, 100_000))
        }
    }

    @Synchronized fun hour(h: Int): Hour? = hours[h]

    /** Hora em que começa a janela da manutenção. [DEFAULT_HOUR] enquanto alguma hora candidata tem menos de [MATURE] leituras. */
    @Synchronized fun quietStart(): Int {
        val candidates = EARLIEST..LATEST
        if ((EARLIEST..LATEST + WINDOW - 1).any { (hours[it]?.n ?: 0) < MATURE }) return DEFAULT_HOUR
        return candidates.minWith(compareBy<Int>({ s -> (s until s + WINDOW).sumOf { hours[it]!!.use } }, { Math.abs(it - DEFAULT_HOUR) }))
    }

    @Synchronized fun toJson(): JSONObject = JSONObject().put("v", 1).put("h", JSONArray().apply {
        hours.forEach { put(if (it == null) JSONObject.NULL else JSONArray().put(Math.round(it.use * 1000) / 1000.0).put(it.n)) }
    })

    companion object {
        /** Peso de cada leitura nova depois do começo (~2 semanas de memória com 12 leituras por hora). */
        const val ALPHA = 1.0 / 168
        /** Leituras por hora antes de confiar (36 = 3 noites de amostras de 5 min). */
        const val MATURE = 36
        const val DEFAULT_HOUR = 4
        /** O alarme fica entre 1h e 4h; a janela dura 2 h (alarme + uma nova tentativa), a última termina às 6h. */
        const val EARLIEST = 1
        const val LATEST = 4
        const val WINDOW = 2

        fun fromJson(o: JSONObject?): ObserverHabit {
            val r = ObserverHabit()
            val a = o?.takeIf { it.optInt("v") == 1 }?.optJSONArray("h") ?: return r
            if (a.length() != 24) return r
            for (i in 0 until 24) a.optJSONArray(i)?.let { r.hours[i] = Hour(it.getDouble(0), it.getInt(1)) }
            return r
        }
    }
}
