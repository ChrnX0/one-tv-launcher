package br.paulo.tvlauncher

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A parte do Android do Observador (regras e formatos em ObserverCore). Fase 1: observa e registra, não age.
 *
 * Quem publica (nenhum relógio novo; o Observador anda na batida de quem já existe):
 *  - HistoricoRede, a cada amostra (5 min, 30 s durante queda): internet, latência, Wi-Fi, e aproveita a mesma batida
 *    para ler memória livre e disco ([fromNetworkSample]);
 *  - LauncherApp.onTrimMemory: o nível de aperto de memória;
 *  - DesempenhoTv: a fração de quadros lentos da HOME a cada rodada.
 *
 * O juiz roda numa thread do Fundo depois de cada publicação (no máximo uma por vez, [MIN_JUDGE_GAP_MS] entre elas).
 * Só o que MUDA (apareceu/sumiu) vai ao registro "observer", ao Diário da TV e à fila de recados.
 * Só no processo principal ([start]); no ":player" e nos outros, publicar não faz nada.
 * Prefs "observer": fora do backup (BackupCompleto.FORA_DE_PROPOSITO), porque o estado de uma TV não diz nada sobre outra.
 * adb: `EXTRAS --es o observer`; MCP: ferramenta `observer` ([reportJson]).
 */
object ObserverTv {
    private const val TAG = "TvLauncher.Observer"
    private const val PREFS = "observer"
    private const val MIN_JUDGE_GAP_MS = 10_000L

    val blackBox = ObserverCore.BlackBox()
    @Volatile private var app: Context? = null
    @Volatile private var lastJudge = 0L
    private val judging = AtomicBoolean(false)
    private val active = LinkedHashSet<ObserverCore.Kind>()

    /** LauncherApp, só no processo principal. Idempotente. */
    fun start(ctx: Context) { if (app == null) app = ctx.applicationContext }

    /** Um sensor manda um valor. Barato (vale chamar da thread de quadros): só guarda e, se der, agenda o juiz. */
    fun publish(signal: ObserverCore.Signal, value: Long) {
        val a = app ?: return
        blackBox.put(signal, value, System.currentTimeMillis())
        val agora = SystemClock.elapsedRealtime()
        if (agora - lastJudge < MIN_JUDGE_GAP_MS || !judging.compareAndSet(false, true)) return
        lastJudge = agora
        Fundo.rodar("observer-judge") {
            try { judgeNow(a) } catch (e: Throwable) { Log.w(TAG, "juiz: $e") } finally { judging.set(false) }
        }
    }

    /** Chamado pelo HistoricoRede depois de cada amostra (na thread dele). */
    fun fromNetworkSample(ctx: Context, internet: Boolean, latMs: Int, dbm: Int) {
        if (app == null) return
        val agora = System.currentTimeMillis()
        blackBox.put(ObserverCore.Signal.INTERNET, if (internet) 1 else 0, agora)
        if (internet && latMs >= 0) blackBox.put(ObserverCore.Signal.LATENCY_MS, latMs.toLong(), agora)
        blackBox.put(ObserverCore.Signal.WIFI_DBM, dbm.toLong(), agora)
        runCatching {
            val mi = ActivityManager.MemoryInfo()
            ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            blackBox.put(ObserverCore.Signal.MEMORY_AVAILABLE_MB, mi.availMem / 1_048_576, agora)
        }
        runCatching { blackBox.put(ObserverCore.Signal.DISK_FREE_MB, ctx.filesDir.usableSpace / 1_048_576, agora) }
        runCatching { learnHabit(ctx, agora) }
        // O último put passa pelo publish para acordar o juiz uma vez só por amostra.
        publish(ObserverCore.Signal.INTERNET, if (internet) 1 else 0)
    }

    @Volatile private var activeLoaded = false

    @Synchronized private fun judgeNow(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // O que estava aberto antes de o processo reiniciar continua aberto: sem isso, cada reinício "reapareceria" tudo.
        if (!activeLoaded) {
            activeLoaded = true
            p.getString("active", "").orEmpty().split(',').mapNotNull { n -> ObserverCore.Kind.entries.firstOrNull { it.name == n } }.forEach { active.add(it) }
        }
        val agora = System.currentTimeMillis()
        val snap = blackBox.snapshot()
        val (hw, hd) = hours(agora)
        val base = baseline(p)
        // Julga ANTES de aprender: a leitura de agora não pode puxar a base na própria direção e se esconder (Codex, #329).
        val findings = ObserverCore.judge(snap, agora, base, hw, hd)
        learn(p, base, snap, hw, hd, agora)
        val change = ObserverCore.diff(active.toList(), findings)
        if (change.appeared.isEmpty() && change.cleared.isEmpty()) return
        var log = p.getString("log", null)
        var notes = p.getString("notes", null)
        for (f in change.appeared) {
            log = ObserverCore.appendLog(log, ObserverCore.logEntry(agora, "appeared", f.kind, f.severity, f.why, snap), agora)
            if (f.severity >= ObserverCore.Severity.WARNING) notes = ObserverCore.enqueue(notes, ObserverCore.noteFor(f, agora, ObserverCore.argsFor(f.kind, snap)), agora)
            runCatching { Diario.anotar(ctx, "tv", "Observer: ${f.kind.name}", f.why) }
            active.add(f.kind)
        }
        for (k in change.cleared) {
            log = ObserverCore.appendLog(log, ObserverCore.logEntry(agora, "cleared", k, null, "voltou ao normal", snap), agora)
            runCatching { Diario.anotar(ctx, "tv", "Observer: ${k.name}", "voltou ao normal") }
            active.remove(k)
        }
        p.edit().putString("log", log).putString("notes", notes).putString("active", active.joinToString(",") { it.name }).apply()
        Log.i(TAG, "apareceu=${change.appeared.map { it.kind }} sumiu=${change.cleared}")
    }

    // ---------------------------------------------------------------- linha de base (fase 2)

    @Volatile private var base: ObserverBaseline? = null
    private val lastLearned = HashMap<ObserverCore.Signal, Long>()
    private var lastBaseSave = 0L
    /** Uma leitura por sinal a cada 4,5 min no máximo: durante uma queda a rede amostra a cada 30 s e pesaria demais. */
    private const val LEARN_GAP_MS = 270_000L
    private const val BASE_SAVE_GAP_MS = 30 * 60_000L

    /** Hora da semana (0 = domingo 0h) e hora do dia, no fuso da TV. */
    fun hours(at: Long): Pair<Int, Int> {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = at }
        val h = c.get(java.util.Calendar.HOUR_OF_DAY)
        return ((c.get(java.util.Calendar.DAY_OF_WEEK) - 1) * 24 + h) to h
    }

    private fun baseline(p: android.content.SharedPreferences): ObserverBaseline =
        base ?: ObserverBaseline.fromJson(p.getString("baseline", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() })
            .also { base = it }

    private fun learn(p: android.content.SharedPreferences, b: ObserverBaseline, snap: Map<ObserverCore.Signal, ObserverCore.Reading>, hw: Int, hd: Int, agora: Long) {
        var learned = false
        for (s in ObserverBaseline.LEARNED) {
            val r = snap[s] ?: continue
            if (agora - r.at > ObserverCore.STALE_MS || r.at - (lastLearned[s] ?: 0L) < LEARN_GAP_MS) continue
            if (s == ObserverCore.Signal.WIFI_DBM && r.value !in -126L..-1L) continue   // sem Wi-Fi (cabo): não é um sinal
            b.learn(s, r.value, hw, hd)
            lastLearned[s] = r.at
            learned = true
        }
        if (learned && agora - lastBaseSave > BASE_SAVE_GAP_MS) {
            lastBaseSave = agora
            p.edit().putString("baseline", b.toJson().toString()).apply()
        }
    }

    // ---------------------------------------------------------------- hábito e sonda noturna (fase 2b)

    @Volatile private var habit: ObserverHabit? = null
    @Volatile private var lastHabitAt = 0L
    @Volatile private var lastHabitSave = 0L

    private fun habit(ctx: Context): ObserverHabit = habit ?: ObserverHabit.fromJson(
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("habit", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() })
        .also { habit = it }

    /** Em uso = tela acesa e controle tocado há menos de 1 h (TV esquecida ligada na madrugada não conta como hábito). */
    @Synchronized private fun learnHabit(ctx: Context, agora: Long) {
        if (agora - lastHabitAt < LEARN_GAP_MS) return
        lastHabitAt = agora
        val inUse = ctx.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true && Rotinas.minutosSemTecla() < 60
        val h = habit(ctx)
        h.learn(hours(agora).second, inUse)
        if (agora - lastHabitSave > BASE_SAVE_GAP_MS) {
            lastHabitSave = agora
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("habit", h.toJson().toString()).apply()
        }
    }

    /** Hora em que a manutenção da madrugada (e a sonda) começa: a mais quieta desta casa, ou 4h sem dados. */
    fun quietStart(ctx: Context): Int = habit(ctx.applicationContext).quietStart()

    /**
     * A sonda da madrugada (ManutencaoNoturna, depois dos passos dela): uma fotografia de todos os sinais com a casa
     * parada, no registro como "night", e a linha de base e o hábito gravados. O relatório da manhã compara noite a noite.
     */
    fun nightProbe(ctx: Context) {
        val app = ctx.applicationContext
        val agora = System.currentTimeMillis()
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            val mi = android.app.ActivityManager.MemoryInfo()
            app.getSystemService(android.app.ActivityManager::class.java).getMemoryInfo(mi)
            blackBox.put(ObserverCore.Signal.MEMORY_AVAILABLE_MB, mi.availMem / 1_048_576, agora)
        }
        runCatching { blackBox.put(ObserverCore.Signal.DISK_FREE_MB, app.filesDir.usableSpace / 1_048_576, agora) }
        val snap = blackBox.snapshot().filterValues { agora - it.at <= ObserverCore.STALE_MS }
        synchronized(this) {
            val log = ObserverCore.appendLog(p.getString("log", null),
                ObserverCore.logEntry(agora, "night", null, null, "${quietStart(app)}h", snap), agora)
            val e = p.edit().putString("log", log)
            base?.let { e.putString("baseline", it.toJson().toString()) }
            habit?.let { e.putString("habit", it.toJson().toString()) }
            e.apply()
        }
        Log.i(TAG, "sonda da madrugada: ${snap.mapValues { it.value.value }}")
    }

    // ---------------------------------------------------------------- recados (para o rosto do Observador, fase do mascote)

    /** Os recados que valem agora, do mais novo ao mais velho. Quem mostra escolhe a frase pelo [ObserverCore.Note.kind]. */
    fun notes(ctx: Context): List<ObserverCore.Note> =
        ObserverCore.readNotes(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("notes", null), System.currentTimeMillis())

    /** O rosto chama depois de mostrar um recado: ele não volta, e um novo do mesmo tipo pode entrar. */
    @Synchronized fun markSeen(ctx: Context, id: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putString("notes", ObserverCore.markSeen(p.getString("notes", null), id, System.currentTimeMillis())).apply()
    }

    // ---------------------------------------------------------------- adb e MCP

    /**
     * O mesmo retrato do [report], em JSON, para a ferramenta MCP `observer`: sinais (valor, unidade, idade), o que o
     * juiz vê agora (limites fixos + linha de base), o normal desta hora de cada sinal ("normal", quando já aprendido), recados na fila e as [logMax] linhas mais novas do registro. Só leitura.
     */
    fun reportJson(ctx: Context, logMax: Int = 30): org.json.JSONObject {
        val agora = System.currentTimeMillis()
        val snap = blackBox.snapshot()
        val sinais = org.json.JSONObject()
        val (hw, hd) = hours(agora)
        val b = base ?: runCatching { baseline(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)) }.getOrNull()
        for ((s, r) in snap.entries.sortedBy { it.key.ordinal })
            sinais.put(s.name, org.json.JSONObject().put("value", r.value).put("unit", s.unit)
                .put("age_s", (agora - r.at) / 1000).put("in_black_box", blackBox.series(s).size)
                .apply { b?.expected(s, hw, hd)?.let { c -> put("normal", Math.round(c.mean)).put("normal_samples", c.n) } })
        val agoraJson = org.json.JSONArray()
        for (f in ObserverCore.judge(snap, agora, b, hw, hd))
            agoraJson.put(org.json.JSONObject().put("kind", f.kind.name).put("severity", f.severity.name).put("why", f.why))
        val log = ObserverCore.readLog(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", null), agora)
        val logNovo = org.json.JSONArray()
        for (i in 0 until minOf(logMax, log.length())) logNovo.put(log.get(i))
        return org.json.JSONObject()
            .put("phase", 2).put("acts", false).put("running", app != null)
            .put("quiet_start_hour", runCatching { quietStart(ctx) }.getOrDefault(ObserverHabit.DEFAULT_HOUR))
            .put("signals", sinais).put("now", agoraJson)
            .put("notes", ObserverCore.toJson(notes(ctx)))
            .put("log_total", log.length()).put("log", logNovo)
    }

    fun report(ctx: Context): String = buildString {
        val agora = System.currentTimeMillis()
        appendLine("Observer (fase 2: observa, aprende e registra). ativa=${app != null}")
        val hab = habit(ctx)
        appendLine("madrugada começa às ${hab.quietStart()}h; uso por hora (0-23): " +
            (0 until 24).joinToString(" ") { h -> hab.hour(h)?.let { "${Math.round(it.use * 100)}%" } ?: "-" })
        appendLine("sinais (último valor, idade):")
        val snap = blackBox.snapshot()
        if (snap.isEmpty()) appendLine("  nenhum ainda (o primeiro chega com a amostra de rede, até 5 min)")
        for ((s, r) in snap.entries.sortedBy { it.key.ordinal })
            appendLine("  ${s.name} = ${r.value} ${s.unit} (${(agora - r.at) / 1000} s, ${blackBox.series(s).size} na caixa-preta)")
        val (hw, hd) = hours(agora)
        val f = ObserverCore.judge(snap, agora, base, hw, hd)
        appendLine("agora: " + if (f.isEmpty()) "tudo normal" else f.joinToString("; ") { "${it.kind} [${it.severity}] ${it.why}" })
        val n = notes(ctx)
        appendLine("recados na fila: ${n.size}" + n.joinToString("") { "\n  ${it.kind} ${it.severity}${if (it.seen) " (visto)" else ""}" })
        val log = ObserverCore.readLog(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", null), agora)
        appendLine("registro (${log.length()} linhas, as 10 mais novas):")
        for (i in 0 until minOf(10, log.length())) {
            val o = log.getJSONObject(i)
            appendLine("  ${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ROOT).format(o.getLong("at"))} " +
                "${o.optString("event")} ${o.optString("kind")}: ${o.optString("why")}")
        }
    }
}
