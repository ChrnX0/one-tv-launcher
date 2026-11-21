package br.paulo.tvlauncher.observer

import org.json.JSONArray
import org.json.JSONObject

/**
 * O Observador do launcher (nome técnico Observer; nome público em decisão) (fase 1, "o maestro", 20/11/2026). Estudo e decisões do Paulo:
 * https://claude.ai/artifact/Khqu2ek1rtuWut1zYqb1op (poder total de fábrica decidido em 20/11).
 *
 * Porquê: o launcher já tinha ~30 vigias (rede, memória, quadros, quedas, shell...), cada uma com o próprio relógio e o
 * próprio juiz, sem saber umas das outras. Para quê: uma visão só da TV, cada decisão com o porquê registrado, e uma
 * fila de recados que o rosto do Observador (o mascote) vai mostrar. Como: os sensores que já existem PUBLICAM aqui; nenhum
 * relógio novo (o juiz roda quando chega um sinal), nada de Android neste arquivo (ObserverCoreTest na JVM).
 *
 * Nesta fase ela só observa e registra (nenhuma ação). A política de degraus já existe e é testada, para a fase 3.
 * A parte do Android é o ObserverTv.
 */
object ObserverCore {

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

    // ---------------------------------------------------------------- juiz (regras escritas + a linha de base da casa, fase 2)

    enum class Severity { INFO, WARNING, CRITICAL }

    /** O que o juiz vê. [why] é a frase técnica do porquê (vai para o registro e o adb; o rosto usa o [kind]). */
    data class Finding(val kind: Kind, val severity: Severity, val why: String)

    enum class Kind { NO_INTERNET, SLOW_INTERNET, WEAK_WIFI, LOW_MEMORY, MEMORY_PRESSURE, SLOW_HOME, LOW_DISK,
        // Fase 2: dentro dos limites fixos, mas fora do normal DESTA casa nesta hora (ObserverBaseline). Só INFO: registro e diário.
        UNUSUAL_LATENCY, UNUSUAL_MEMORY, UNUSUAL_WIFI, UNUSUAL_FRAMES,
        // Fase 3: não é achado do juiz; é o recado de quando a prova desfez um congelamento que quebrou algo ([freezeUndoneNote]).
        FREEZE_UNDONE }

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

    /** Qual achado de limite fixo cobre o mesmo sinal: com ele presente, o "fora do normal" desse sinal seria repetição. */
    private val UNUSUAL = linkedMapOf(
        Signal.LATENCY_MS to (Kind.UNUSUAL_LATENCY to Kind.SLOW_INTERNET),
        Signal.MEMORY_AVAILABLE_MB to (Kind.UNUSUAL_MEMORY to Kind.LOW_MEMORY),
        Signal.WIFI_DBM to (Kind.UNUSUAL_WIFI to Kind.WEAK_WIFI),
        Signal.SLOW_FRAMES_PCT to (Kind.UNUSUAL_FRAMES to Kind.SLOW_HOME),
    )

    /**
     * Fase 2: o juiz completo = limites fixos ([judge]) + o que está fora do normal desta casa nesta hora
     * ([ObserverBaseline.badness] ≥ [ObserverBaseline.UNUSUAL_Z]). Sem internet, latência e Wi-Fi não são julgados contra a base.
     */
    fun judge(snapshot: Map<Signal, Reading>, now: Long, base: ObserverBaseline?, hourOfWeek: Int, hourOfDay: Int): List<Finding> {
        val fixed = judge(snapshot, now)
        if (base == null) return fixed
        val kinds = fixed.map { it.kind }.toSet()
        val offline = Kind.NO_INTERNET in kinds
        val extra = buildList {
            for ((s, pair) in UNUSUAL) {
                val (kind, covering) = pair
                if (covering in kinds) continue
                if (offline && (s == Signal.LATENCY_MS || s == Signal.WIFI_DBM)) continue
                val r = snapshot[s]?.takeIf { now - it.at <= STALE_MS } ?: continue
                if (s == Signal.WIFI_DBM && r.value !in -126L..-1L) continue
                val z = base.badness(s, r.value, hourOfWeek, hourOfDay) ?: continue
                if (z < ObserverBaseline.UNUSUAL_Z) continue
                val c = base.expected(s, hourOfWeek, hourOfDay) ?: continue
                add(Finding(kind, Severity.INFO, "${r.value} ${s.unit}, o normal desta hora é ~${Math.round(c.mean)} ${s.unit} (${"%.1f".format(java.util.Locale.ROOT, z)} desvios)"))
            }
        }
        return (fixed + extra).sortedByDescending { it.severity.ordinal }
    }

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
    /** [kind] null = linha que não é achado (a sonda da madrugada, evento "night"). */
    fun logEntry(at: Long, event: String, kind: Kind?, severity: Severity?, why: String, snapshot: Map<Signal, Reading>): JSONObject {
        val signals = JSONObject()
        for ((s, r) in snapshot.entries.sortedBy { it.key.ordinal }) signals.put(s.name, r.value)
        return JSONObject().put("at", at).put("event", event).put("kind", kind?.name ?: JSONObject.NULL)
            .put("severity", severity?.name ?: JSONObject.NULL).put("why", why).put("signals", signals)
    }

    /**
     * A TV ligou de novo (20/11/2026): sem isto o registro não mostrava nem o sistema caindo nem a TV presa no boot (Sala,
     * 17:10Z: o system_server caiu no meio de um `pm move-package` e ninguém soube pelo Observador). [reason] = o motivo
     * que o Android guardou (vazio quando não dá para ler); [aliveAt] = a última batida da rede antes de cair (0 = nunca).
     */
    fun bootWhy(reason: String?, aliveAt: Long, bootAt: Long): String {
        val r = reason?.trim()?.takeIf { it.isNotEmpty() } ?: "desconhecido"
        val fora = if (aliveAt in 1 until bootAt) "; sem sinal desde ${(bootAt - aliveAt) / 60_000} min antes de ligar" else ""
        return "ligou de novo (motivo: $r)$fora"
    }

    /** O registro como está guardado, sem o que passou de [LOG_DAYS] (o corte do [appendLog] só roda quando entra linha nova). */
    fun readLog(stored: String?, now: Long): JSONArray {
        val a = try { JSONArray(stored ?: "[]") } catch (_: Exception) { JSONArray() }
        return JSONArray().apply {
            for (i in 0 until a.length()) a.optJSONObject(i)?.takeIf { now - it.optLong("at") <= LOG_DAYS * 86_400_000L }?.let { put(it) }
        }
    }

    /** Põe [entry] na frente; corta acima de [LOG_MAX] linhas ou de [LOG_DAYS] dias. Texto estragado = começa vazio. */
    fun appendLog(stored: String?, entry: JSONObject, now: Long): String = prepend(stored, entry, LOG_MAX) { now - it.optLong("at") <= LOG_DAYS * 86_400_000L }

    // ---------------------------------------------------------------- recados (a saída que o rosto do Observador vai usar)

    /**
     * Um recado para a pessoa (contrato combinado com a thread do rosto, 20/11/2026). Guarda chaves e números, nunca a
     * frase pronta: quem mostra escolhe o texto no idioma da TV (14 línguas), a expressão do mascote, SE e ONDE aparece
     * (não perturbe, teto por hora/dia, resumo da manhã). Recado de pessoa, alarme, campainha e alerta oficial NÃO
     * passam por aqui.
     *  - [step]: o que o Observador fez (muda o tom da fala);
     *  - [state]: em andamento / feito / falhou (muda a expressão: trabalhando → feliz ou preocupada);
     *  - [titleKey]/[textKey] + [args]: nomes de string ("observer_<kind>_title"/"_text"); o porquê vai nos [args];
     *  - [undoKey]: como desfazer (nome de string), ou null = nada a desfazer;
     *  - [typeKey]: o tipo da fala, para a regra "ignorada 2 vezes seguidas = cala esse tipo 7 dias".
     * As strings em si entram junto com o rosto; até lá ninguém mostra a fila (só o adb e o MCP leem).
     */
    data class Note(
        val id: String, val at: Long, val kind: Kind, val severity: Severity,
        val step: Step, val state: State,
        val titleKey: String, val textKey: String, val args: List<String>,
        val undoKey: String?, val typeKey: String,
        val expiresAt: Long, val seen: Boolean = false,
    )

    enum class Step { OBSERVED, SUGGESTS, ACTED_REVERSIBLE, ACTED_FELT, QUESTION, FAILED }
    enum class State { IN_PROGRESS, DONE, FAILED }

    const val NOTES_MAX = 20
    const val NOTE_TTL_MS = 6 * 3_600_000L

    /**
     * Fase 3: a prova desfez sozinha um congelamento porque algo parou (combinado com a thread da Sofy, 20/11/2026).
     * ACTED_FELT: a pessoa pode ter sentido (voz, loja, Cast pararam), então a Sofy pode aparecer por cima, feliz ("consertei").
     * [args] = [nome do app, o que parou já em texto no idioma da TV].
     */
    fun freezeUndoneNote(at: Long, appLabel: String, brokeText: String): Note {
        val base = "observer_freeze_undone"
        return Note("FREEZE_UNDONE-$at", at, Kind.FREEZE_UNDONE, Severity.WARNING, Step.ACTED_FELT, State.DONE,
            base + "_title", base + "_text", listOf(appLabel, brokeText), null, "freeze_undone", at + NOTE_TTL_MS)
    }

    /** Fase 1: o Observador só observa, então todo recado é OBSERVED + DONE, sem desfazer. */
    fun noteFor(f: Finding, at: Long, args: List<String> = emptyList()): Note {
        val base = "observer_" + f.kind.name.lowercase()
        return Note("${f.kind.name}-$at", at, f.kind, f.severity, Step.OBSERVED, State.DONE,
            base + "_title", base + "_text", args, null, f.kind.name.lowercase(), at + NOTE_TTL_MS)
    }

    /** O número que a frase do recado mostra (ex.: "só 90 MB livres"): o valor do sinal que gerou o achado. */
    /** O sinal que cada achado lê (null = não tem um número só: sem internet). */
    fun signalOf(kind: Kind): Signal? = when (kind) {
        Kind.NO_INTERNET -> null
        Kind.SLOW_INTERNET, Kind.UNUSUAL_LATENCY -> Signal.LATENCY_MS
        Kind.WEAK_WIFI, Kind.UNUSUAL_WIFI -> Signal.WIFI_DBM
        Kind.LOW_MEMORY, Kind.UNUSUAL_MEMORY -> Signal.MEMORY_AVAILABLE_MB
        Kind.MEMORY_PRESSURE -> Signal.MEMORY_PRESSURE
        Kind.SLOW_HOME, Kind.UNUSUAL_FRAMES -> Signal.SLOW_FRAMES_PCT
        Kind.LOW_DISK -> Signal.DISK_FREE_MB
        Kind.FREEZE_UNDONE -> null
    }

    fun argsFor(kind: Kind, snapshot: Map<Signal, Reading>): List<String> {
        val s = signalOf(kind) ?: return emptyList()
        return listOfNotNull(snapshot[s]?.value?.toString())
    }

    /**
     * Medidas da pergunta [ObserverPico.Task.WORTH_TELLING] para um achado que acabou de aparecer (fase 2: só sombra).
     * [habitUse] = fração de uso da TV nesta hora; [toldLast24h] = recados do mesmo tipo nas últimas 24 h.
     */
    fun tellingFeatures(f: Finding, snapshot: Map<Signal, Reading>, base: ObserverBaseline?, hourOfWeek: Int, hourOfDay: Int,
                        habitUse: Double, openHours: Double, toldLast24h: Int): DoubleArray {
        val z = signalOf(f.kind)?.let { s -> snapshot[s]?.let { r -> base?.badness(s, r.value, hourOfWeek, hourOfDay) } } ?: 0.0
        return doubleArrayOf(f.severity.ordinal.toDouble(), z.coerceIn(0.0, 8.0), habitUse.coerceIn(0.0, 1.0),
            openHours.coerceIn(0.0, 6.0), toldLast24h.coerceIn(0, 5).toDouble())
    }

    /** Recado de um tipo que já está na fila e não foi visto não entra de novo (sem repetir a mesma coisa). */
    fun enqueue(stored: String?, n: Note, now: Long): String {
        val atuais = readNotes(stored, now)
        if (atuais.any { it.typeKey == n.typeKey && !it.seen }) return toJson(atuais).toString()
        return toJson((listOf(n) + atuais).take(NOTES_MAX)).toString()
    }

    /** Os que valem agora, do mais novo ao mais velho. Linha estragada ou de versão desconhecida é pulada. */
    fun readNotes(stored: String?, now: Long): List<Note> {
        val arr = try { JSONArray(stored ?: "[]") } catch (_: Exception) { JSONArray() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                val args = o.optJSONArray("args")
                Note(o.getString("id"), o.getLong("at"), Kind.valueOf(o.getString("kind")), Severity.valueOf(o.getString("severity")),
                    Step.valueOf(o.getString("step")), State.valueOf(o.getString("state")),
                    o.getString("titleKey"), o.getString("textKey"),
                    if (args == null) emptyList() else (0 until args.length()).map { args.getString(it) },
                    if (o.isNull("undoKey")) null else o.optString("undoKey"), o.getString("typeKey"),
                    o.getLong("expiresAt"), o.optBoolean("seen"))
            }.getOrNull()
        }.filter { it.expiresAt > now }
    }

    /**
     * Recado de um ESTADO que já acabou sai da fila (21/11/2026, teste da Sala): "Sem internet" ficava 6 h na faixa da
     * Início do painel mesmo com a internet de volta, e o recado passava a mentir. O registro ("cleared") guarda o histórico.
     * Só os recados OBSERVED (descrevem o agora); o que a Sofy FEZ (ex.: [freezeUndoneNote]) fica até expirar.
     */
    fun dropCleared(stored: String?, cleared: Collection<Kind>, now: Long): String =
        toJson(readNotes(stored, now).filterNot { it.kind in cleared && it.step == Step.OBSERVED }).toString()

    fun markSeen(stored: String?, id: String, now: Long): String =
        toJson(readNotes(stored, now).map { if (it.id == id) it.copy(seen = true) else it }).toString()

    fun toJson(notes: List<Note>) = JSONArray().apply {
        for (n in notes) put(JSONObject().put("id", n.id).put("at", n.at).put("kind", n.kind.name)
            .put("severity", n.severity.name).put("step", n.step.name).put("state", n.state.name)
            .put("titleKey", n.titleKey).put("textKey", n.textKey).put("args", JSONArray(n.args))
            .put("undoKey", n.undoKey ?: JSONObject.NULL).put("typeKey", n.typeKey)
            .put("expiresAt", n.expiresAt).put("seen", n.seen))
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
