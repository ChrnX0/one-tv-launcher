package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject

/**
 * Sofy, o observador do launcher (fase 1, "o maestro", 20/11/2026). Estudo e decisões do Paulo:
 * https://claude.ai/artifact/Khqu2ek1rtuWut1zYqb1op (nome "Sofy" e poder total de fábrica decididos em 20/11).
 *
 * Porquê: o launcher já tinha ~30 vigias (rede, memória, quadros, quedas, shell...), cada uma com o próprio relógio e o
 * próprio juiz, sem saber umas das outras. Para quê: uma visão só da TV, cada decisão com o porquê registrado, e uma
 * fila de recados que o rosto da Sofy (o mascote) vai mostrar. Como: os sensores que já existem PUBLICAM aqui; nenhum
 * relógio novo (o juiz roda quando chega um sinal), nada de Android neste arquivo (SofyCoreTest na JVM).
 *
 * Nesta fase ela só observa e registra (nenhuma ação). A política de degraus já existe e é testada, para a fase 3.
 * A parte do Android é o SofyTv.
 */
object SofyCore {

    // ---------------------------------------------------------------- sinais e caixa-preta

    /** O que os sensores publicam. [unit] só para o texto do adb e do relatório. */
    enum class Signal(val unit: String) {
        MEMORY_AVAILABLE_MB("MB"),   // ActivityManager.MemoryInfo.availMem
        MEMORY_PRESSURE("nível"),    // onTrimMemory (constantes do ComponentCallbacks2)
        INTERNET("0/1"),             // HistoricoRede: a conexão até 1.1.1.1 abriu
        LATENCY_MS("ms"),            // HistoricoRede: tempo dessa conexão
        WIFI_DBM("dBm"),             // HistoricoRede: sinal do Wi-Fi (-127 = sem Wi-Fi)
        SLOW_FRAMES_PCT("%"),        // DesempenhoTv: quadros lentos da HOME numa rodada
        DISK_FREE_MB("MB"),          // espaço livre da partição de dados
    }

    data class Reading(val at: Long, val value: Long)

    /**
     * Caixa-preta: os últimos [WINDOW_MS] de cada sinal, no máximo [MAX_PER_SIGNAL] leituras cada. Quando algo der
     * errado, é o trecho que explica o que veio antes. Fica só na memória (some se o processo cair; a fase 2 congela o
     * trecho em disco no momento do incidente).
     */
    class BlackBox {
        private val data = HashMap<Signal, ArrayDeque<Reading>>()

        @Synchronized fun put(s: Signal, value: Long, at: Long) {
            val q = data.getOrPut(s) { ArrayDeque() }
            q.addLast(Reading(at, value))
            while (q.size > MAX_PER_SIGNAL || (q.isNotEmpty() && at - q.first().at > WINDOW_MS)) q.removeFirst()
        }

        @Synchronized fun latest(s: Signal): Reading? = data[s]?.lastOrNull()

        @Synchronized fun series(s: Signal): List<Reading> = data[s]?.toList() ?: emptyList()

        /** Fotografia de agora: o último valor de cada sinal que já chegou. */
        @Synchronized fun snapshot(): Map<Signal, Reading> = data.mapNotNull { (k, v) -> v.lastOrNull()?.let { k to it } }.toMap()
    }

    const val WINDOW_MS = 30 * 60_000L
    const val MAX_PER_SIGNAL = 120

    // ---------------------------------------------------------------- juiz (regras escritas; a estatística vem na fase 2)

    enum class Severity { INFO, WARNING, CRITICAL }

    /** O que o juiz vê. [why] é a frase técnica do porquê (vai para o registro e o adb; o rosto usa o [kind]). */
    data class Finding(val kind: Kind, val severity: Severity, val why: String)

    enum class Kind { NO_INTERNET, SLOW_INTERNET, WEAK_WIFI, LOW_MEMORY, MEMORY_PRESSURE, SLOW_HOME, LOW_DISK }

    /** Abaixo disto o Android começa a fechar apps em segundo plano num aparelho de 2 GB (inferido; a fase 2 troca pela linha de base da casa). */
    const val LOW_MEMORY_MB = 150L
    /** Latência até 1.1.1.1 acima disto: a internet "funciona" mas tudo demora. */
    const val SLOW_LATENCY_MS = 400L
    /** Sinal abaixo disto: o Wi-Fi cai e volta. */
    const val WEAK_WIFI_DBM = -80L
    /** Rodada da HOME com mais disto de quadros lentos = a tela está pesada. */
    const val SLOW_FRAMES_PCT = 20L
    /** Mesmo limite do catálogo de falhas (Falhas.DISCO_MINIMO_MB): abaixo disso o Android falha ao gravar. */
    const val LOW_DISK_MB = 300L
    /** onTrimMemory a partir de RUNNING_LOW (10) com o app na frente = o sistema está apertado agora. */
    const val PRESSURE_LEVEL = 10L
    /** Leitura mais velha que isto não vale mais para julgar (TV desligada, sensor parado). */
    const val STALE_MS = 15 * 60_000L

    fun judge(snapshot: Map<Signal, Reading>, now: Long): List<Finding> = buildList {
        fun v(s: Signal): Long? = snapshot[s]?.takeIf { now - it.at <= STALE_MS }?.value
        v(Signal.INTERNET)?.let { if (it == 0L) add(Finding(Kind.NO_INTERNET, Severity.CRITICAL, "a conexão até 1.1.1.1 não abriu")) }
        if (v(Signal.INTERNET) != 0L) v(Signal.LATENCY_MS)?.let {
            if (it > SLOW_LATENCY_MS) add(Finding(Kind.SLOW_INTERNET, Severity.WARNING, "latência de $it ms (limite $SLOW_LATENCY_MS)"))
        }
        v(Signal.WIFI_DBM)?.let {
            if (it in -126L..WEAK_WIFI_DBM) add(Finding(Kind.WEAK_WIFI, Severity.WARNING, "sinal do Wi-Fi em $it dBm (limite $WEAK_WIFI_DBM)"))
        }
        v(Signal.MEMORY_AVAILABLE_MB)?.let {
            if (it < LOW_MEMORY_MB) add(Finding(Kind.LOW_MEMORY, Severity.WARNING, "só $it MB de memória livre (limite $LOW_MEMORY_MB)"))
        }
        v(Signal.MEMORY_PRESSURE)?.let {
            if (it >= PRESSURE_LEVEL && it < 20) add(Finding(Kind.MEMORY_PRESSURE, Severity.WARNING, "o Android pediu memória com o launcher na frente (nível $it)"))
        }
        v(Signal.SLOW_FRAMES_PCT)?.let {
            if (it > SLOW_FRAMES_PCT) add(Finding(Kind.SLOW_HOME, Severity.INFO, "$it% dos quadros da HOME saíram lentos"))
        }
        v(Signal.DISK_FREE_MB)?.let {
            if (it < LOW_DISK_MB) add(Finding(Kind.LOW_DISK, Severity.CRITICAL, "só $it MB livres no disco (limite $LOW_DISK_MB)"))
        }
    }.sortedByDescending { it.severity.ordinal }

    /** O que mudou entre dois julgamentos: só isso vai ao registro, ao diário e aos recados (sem repetir a cada sinal). */
    data class Change(val appeared: List<Finding>, val cleared: List<Kind>)

    fun diff(before: Collection<Kind>, now: List<Finding>): Change =
        Change(now.filter { it.kind !in before }, before.filter { k -> now.none { it.kind == k } })

    // ---------------------------------------------------------------- política de degraus (usada a partir da fase 3)

    /** Degraus de autonomia. Paulo, 20/11/2026: "poder total" de fábrica = [FULL]. */
    enum class Level { OBSERVE, SUGGEST, ACT, FULL }

    /** O que a TV está fazendo agora, para os freios. */
    data class Context(
        val now: Long,
        val screenOn: Boolean,
        val soundPlaying: Boolean,
        val lastKeyAt: Long,
        val lastSameActionAt: Long,
    )

    /** Mesmos freios do ReparoWifi: quem está usando não é cortado, e nada entra em ciclo. */
    const val IDLE_WITH_SCREEN_MS = 30 * 60_000L
    const val MIN_INTERVAL_INTERRUPTING_MS = 6 * 3_600_000L
    const val MIN_INTERVAL_REVERSIBLE_MS = 30 * 60_000L

    /** [needed] = o degrau que a ação exige (ex.: soltar cache = ACT; reiniciar = FULL). Devolve null = pode, ou o motivo de não poder. */
    fun blockedBy(needed: Level, max: Level, c: Context): String? {
        if (needed > max) return "degrau ${needed.ordinal} acima do máximo ${max.ordinal}"
        if (needed <= Level.SUGGEST) return null
        val interrupts = needed == Level.FULL
        val interval = if (interrupts) MIN_INTERVAL_INTERRUPTING_MS else MIN_INTERVAL_REVERSIBLE_MS
        if (c.lastSameActionAt > 0 && c.now - c.lastSameActionAt < interval) return "a mesma ação rodou há ${(c.now - c.lastSameActionAt) / 60_000} min"
        if (interrupts) {
            if (c.soundPlaying) return "tem som tocando"
            if (c.screenOn && c.now - c.lastKeyAt < IDLE_WITH_SCREEN_MS) return "alguém mexeu no controle há ${(c.now - c.lastKeyAt) / 60_000} min"
        }
        return null
    }

    // ---------------------------------------------------------------- registro das decisões (o porquê de tudo)

    const val LOG_MAX = 300
    const val LOG_DAYS = 7

    /** Uma linha: quando, o que (apareceu/sumiu), o tipo, a gravidade, o porquê e os sinais daquele momento. */
    fun logEntry(at: Long, event: String, kind: Kind, severity: Severity?, why: String, snapshot: Map<Signal, Reading>): JSONObject {
        val signals = JSONObject()
        for ((s, r) in snapshot.entries.sortedBy { it.key.ordinal }) signals.put(s.name, r.value)
        return JSONObject().put("at", at).put("event", event).put("kind", kind.name)
            .put("severity", severity?.name ?: JSONObject.NULL).put("why", why).put("signals", signals)
    }

    /** Põe [entry] na frente; corta acima de [LOG_MAX] linhas ou de [LOG_DAYS] dias. Texto estragado = começa vazio. */
    fun appendLog(stored: String?, entry: JSONObject, now: Long): String = prepend(stored, entry, LOG_MAX) { now - it.optLong("at") <= LOG_DAYS * 86_400_000L }

    // ---------------------------------------------------------------- recados (a saída que o rosto da Sofy vai usar)

    /**
     * Um recado para a pessoa. Guarda o TIPO e os números, nunca o texto: quem mostra (a fase do rosto) escolhe a
     * frase no idioma da TV e a expressão do mascote. [expiresAt]: recado vencido não aparece mais.
     */
    data class Note(val id: String, val at: Long, val kind: Kind, val severity: Severity, val expiresAt: Long, val seen: Boolean = false)

    const val NOTES_MAX = 20
    const val NOTE_TTL_MS = 6 * 3_600_000L

    fun noteFor(f: Finding, at: Long): Note = Note("${f.kind.name}-$at", at, f.kind, f.severity, at + NOTE_TTL_MS)

    /** Recado de um tipo que já está na fila e não foi visto não entra de novo (sem repetir a mesma coisa). */
    fun enqueue(stored: String?, n: Note, now: Long): String {
        val atuais = readNotes(stored, now)
        if (atuais.any { it.kind == n.kind && !it.seen }) return toJson(atuais).toString()
        return toJson((listOf(n) + atuais).take(NOTES_MAX)).toString()
    }

    /** Os que valem agora, do mais novo ao mais velho. */
    fun readNotes(stored: String?, now: Long): List<Note> {
        val arr = try { JSONArray(stored ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
            val sev = runCatching { Severity.valueOf(o.getString("severity")) }.getOrNull() ?: return@mapNotNull null
            Note(o.optString("id"), o.optLong("at"), kind, sev, o.optLong("expiresAt"), o.optBoolean("seen"))
        }.filter { it.expiresAt > now }
    }

    fun markSeen(stored: String?, id: String, now: Long): String =
        toJson(readNotes(stored, now).map { if (it.id == id) it.copy(seen = true) else it }).toString()

    private fun toJson(notes: List<Note>) = JSONArray().apply {
        for (n in notes) put(JSONObject().put("id", n.id).put("at", n.at).put("kind", n.kind.name)
            .put("severity", n.severity.name).put("expiresAt", n.expiresAt).put("seen", n.seen))
    }

    private fun prepend(stored: String?, entry: JSONObject, max: Int, keep: (JSONObject) -> Boolean): String {
        val old = try { JSONArray(stored ?: "[]") } catch (_: Exception) { JSONArray() }
        val out = JSONArray().put(entry)
        for (i in 0 until old.length()) {
            if (out.length() >= max) break
            val o = old.optJSONObject(i) ?: continue
            if (keep(o)) out.put(o)
        }
        return out.toString()
    }
}
