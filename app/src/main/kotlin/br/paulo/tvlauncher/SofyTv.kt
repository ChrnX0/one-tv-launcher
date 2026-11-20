package br.paulo.tvlauncher

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A parte do Android da Sofy (regras e formatos em SofyCore). Fase 1: observa e registra, não age.
 *
 * Quem publica (nenhum relógio novo; a Sofy anda na batida de quem já existe):
 *  - HistoricoRede, a cada amostra (5 min, 30 s durante queda): internet, latência, Wi-Fi, e aproveita a mesma batida
 *    para ler memória livre e disco ([fromNetworkSample]);
 *  - LauncherApp.onTrimMemory: o nível de aperto de memória;
 *  - DesempenhoTv: a fração de quadros lentos da HOME a cada rodada.
 *
 * O juiz roda numa thread do Fundo depois de cada publicação (no máximo uma por vez, [MIN_JUDGE_GAP_MS] entre elas).
 * Só o que MUDA (apareceu/sumiu) vai ao registro "sofy", ao Diário da TV e à fila de recados.
 * Só no processo principal ([start]); no ":player" e nos outros, publicar não faz nada.
 * Prefs "sofy": fora do backup (BackupConfig.ARQUIVOS), porque o estado de uma TV não diz nada sobre outra.
 * adb: `EXTRAS --es o sofy`.
 */
object SofyTv {
    private const val TAG = "TvLauncher.Sofy"
    private const val PREFS = "sofy"
    private const val MIN_JUDGE_GAP_MS = 10_000L

    val blackBox = SofyCore.BlackBox()
    @Volatile private var app: Context? = null
    @Volatile private var lastJudge = 0L
    private val judging = AtomicBoolean(false)
    private val active = LinkedHashSet<SofyCore.Kind>()

    /** LauncherApp, só no processo principal. Idempotente. */
    fun start(ctx: Context) { if (app == null) app = ctx.applicationContext }

    /** Um sensor manda um valor. Barato (vale chamar da thread de quadros): só guarda e, se der, agenda o juiz. */
    fun publish(signal: SofyCore.Signal, value: Long) {
        val a = app ?: return
        blackBox.put(signal, value, System.currentTimeMillis())
        val agora = SystemClock.elapsedRealtime()
        if (agora - lastJudge < MIN_JUDGE_GAP_MS || !judging.compareAndSet(false, true)) return
        lastJudge = agora
        Fundo.rodar("sofy-juiz") {
            try { judgeNow(a) } catch (e: Throwable) { Log.w(TAG, "juiz: $e") } finally { judging.set(false) }
        }
    }

    /** Chamado pelo HistoricoRede depois de cada amostra (na thread dele). */
    fun fromNetworkSample(ctx: Context, internet: Boolean, latMs: Int, dbm: Int) {
        if (app == null) return
        val agora = System.currentTimeMillis()
        blackBox.put(SofyCore.Signal.INTERNET, if (internet) 1 else 0, agora)
        if (internet && latMs >= 0) blackBox.put(SofyCore.Signal.LATENCY_MS, latMs.toLong(), agora)
        blackBox.put(SofyCore.Signal.WIFI_DBM, dbm.toLong(), agora)
        runCatching {
            val mi = ActivityManager.MemoryInfo()
            ctx.getSystemService(ActivityManager::class.java).getMemoryInfo(mi)
            blackBox.put(SofyCore.Signal.MEMORY_AVAILABLE_MB, mi.availMem / 1_048_576, agora)
        }
        runCatching { blackBox.put(SofyCore.Signal.DISK_FREE_MB, ctx.filesDir.usableSpace / 1_048_576, agora) }
        // O último put passa pelo publish para acordar o juiz uma vez só por amostra.
        publish(SofyCore.Signal.INTERNET, if (internet) 1 else 0)
    }

    @Volatile private var activeLoaded = false

    @Synchronized private fun judgeNow(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // O que estava aberto antes de o processo reiniciar continua aberto: sem isso, cada reinício "reapareceria" tudo.
        if (!activeLoaded) {
            activeLoaded = true
            p.getString("active", "").orEmpty().split(',').mapNotNull { n -> SofyCore.Kind.entries.firstOrNull { it.name == n } }.forEach { active.add(it) }
        }
        val agora = System.currentTimeMillis()
        val snap = blackBox.snapshot()
        val findings = SofyCore.judge(snap, agora)
        val change = SofyCore.diff(active.toList(), findings)
        if (change.appeared.isEmpty() && change.cleared.isEmpty()) return
        var log = p.getString("log", null)
        var notes = p.getString("notes", null)
        for (f in change.appeared) {
            log = SofyCore.appendLog(log, SofyCore.logEntry(agora, "appeared", f.kind, f.severity, f.why, snap), agora)
            if (f.severity >= SofyCore.Severity.WARNING) notes = SofyCore.enqueue(notes, SofyCore.noteFor(f, agora), agora)
            runCatching { Diario.anotar(ctx, "tv", "Sofy: ${f.kind.name}", f.why) }
            active.add(f.kind)
        }
        for (k in change.cleared) {
            log = SofyCore.appendLog(log, SofyCore.logEntry(agora, "cleared", k, null, "voltou ao normal", snap), agora)
            runCatching { Diario.anotar(ctx, "tv", "Sofy: ${k.name}", "voltou ao normal") }
            active.remove(k)
        }
        p.edit().putString("log", log).putString("notes", notes).putString("active", active.joinToString(",") { it.name }).apply()
        Log.i(TAG, "apareceu=${change.appeared.map { it.kind }} sumiu=${change.cleared}")
    }

    // ---------------------------------------------------------------- recados (para o rosto da Sofy, fase do mascote)

    /** Os recados que valem agora, do mais novo ao mais velho. Quem mostra escolhe a frase pelo [SofyCore.Note.kind]. */
    fun notes(ctx: Context): List<SofyCore.Note> =
        SofyCore.readNotes(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("notes", null), System.currentTimeMillis())

    /** O rosto chama depois de mostrar um recado: ele não volta, e um novo do mesmo tipo pode entrar. */
    @Synchronized fun markSeen(ctx: Context, id: String) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        p.edit().putString("notes", SofyCore.markSeen(p.getString("notes", null), id, System.currentTimeMillis())).apply()
    }

    // ---------------------------------------------------------------- adb

    fun report(ctx: Context): String = buildString {
        val agora = System.currentTimeMillis()
        appendLine("Sofy (fase 1: observa e registra). ativa=${app != null}")
        appendLine("sinais (último valor, idade):")
        val snap = blackBox.snapshot()
        if (snap.isEmpty()) appendLine("  nenhum ainda (o primeiro chega com a amostra de rede, até 5 min)")
        for ((s, r) in snap.entries.sortedBy { it.key.ordinal })
            appendLine("  ${s.name} = ${r.value} ${s.unit} (${(agora - r.at) / 1000} s, ${blackBox.series(s).size} na caixa-preta)")
        val f = SofyCore.judge(snap, agora)
        appendLine("agora: " + if (f.isEmpty()) "tudo normal" else f.joinToString("; ") { "${it.kind} [${it.severity}] ${it.why}" })
        val n = notes(ctx)
        appendLine("recados na fila: ${n.size}" + n.joinToString("") { "\n  ${it.kind} ${it.severity}${if (it.seen) " (visto)" else ""}" })
        val log = try { org.json.JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("log", "[]")) } catch (_: Exception) { org.json.JSONArray() }
        appendLine("registro (${log.length()} linhas, as 10 mais novas):")
        for (i in 0 until minOf(10, log.length())) {
            val o = log.getJSONObject(i)
            appendLine("  ${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ROOT).format(o.getLong("at"))} " +
                "${o.getString("event")} ${o.getString("kind")}: ${o.getString("why")}")
        }
    }
}
