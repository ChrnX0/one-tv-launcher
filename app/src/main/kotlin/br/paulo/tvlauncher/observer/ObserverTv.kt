package br.paulo.tvlauncher.observer

import android.app.ActivityManager
import android.content.Context
import android.os.SystemClock
import android.util.Log
import br.paulo.tvlauncher.ComunidadeApps
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.Rotinas
import br.paulo.tvlauncher.family.Diario
import br.paulo.tvlauncher.player.OnDemand
import br.paulo.tvlauncher.player.OnDemandTv
import br.paulo.tvlauncher.privilege.PrivilegioDO
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
    fun start(ctx: Context) {
        if (app != null) return
        val a = ctx.applicationContext
        app = a
        Fundo.rodar("observer-boot") { runCatching { checkBoot(a) }.onFailure { Log.w(TAG, "boot: $it") } }
    }

    /**
     * Uma vez por processo: se o contador de boots do Android mudou desde a última vez, a TV ligou de novo. Vai ao
     * registro como "boot", com o motivo e quanto tempo ficou sem sinal ([ObserverCore.bootWhy]).
     */
    private fun checkBoot(ctx: Context) {
        val count = android.provider.Settings.Global.getInt(ctx.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
        if (count < 0) return
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (p.getInt("boot_count", -1) == count) return
        val agora = System.currentTimeMillis()
        val bootAt = agora - SystemClock.elapsedRealtime()
        // Motivo guardado pelo Android ("reboot,deviceowner", "kernel_panic", ...). O SELinux pode negar a leitura: fica vazio.
        val motivo = runCatching {
            Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, "sys.boot.reason") as? String
        }.getOrNull()
        val why = ObserverCore.bootWhy(motivo, p.getLong("alive_at", 0L), bootAt)
        synchronized(this) {
            val log = ObserverCore.appendLog(p.getString("log", null), ObserverCore.logEntry(bootAt, "boot", null, null, why, emptyMap()), agora)
            p.edit().putString("log", log).putInt("boot_count", count).apply()
        }
        runCatching { Diario.anotar(ctx, "tv", "Observer: boot", why) }
        Log.i(TAG, why)
    }

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
        // A última batida antes de cair: o "boot" seguinte diz quanto tempo a TV ficou fora ([checkBoot]).
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong("alive_at", agora).apply()
        runCatching { OnDemandTv.tick(ctx) }   // sob demanda: congela de novo o que ficou aberto (cinto do onWindow)
        if (trialsPending) Fundo.rodar("observer-trials") { runCatching { checkTrials(ctx) }.onFailure { Log.w(TAG, "provas: $it") } }
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
        val pico = pico(p)
        val use = habit(ctx).hour(hd)?.use ?: 0.0
        for (f in change.appeared) {
            // Pico em sombra (fase 2): a nota de "vale avisar?" vai ao registro; quem decide ainda é a regra (severidade ≥ WARNING).
            val vezes = ObserverCore.readLog(log, agora).let { a ->
                (0 until a.length()).count { i -> a.getJSONObject(i).let { it.optString("event") == "appeared" && it.optString("kind") == f.kind.name && agora - it.optLong("at") < 86_400_000L } }
            }
            val x = ObserverCore.tellingFeatures(f, snap, base, hw, hd, use, 0.0, vezes)
            val nota = pico.score(ObserverPico.Task.WORTH_TELLING, x)
            log = ObserverCore.appendLog(log, ObserverCore.logEntry(agora, "appeared", f.kind, f.severity, f.why, snap)
                .put("pico_tell", Math.round(nota * 100) / 100.0), agora)
            if (f.severity >= ObserverCore.Severity.WARNING) notes = ObserverCore.enqueue(notes, ObserverCore.noteFor(f, agora, ObserverCore.argsFor(f.kind, snap)), agora)
            runCatching { Diario.anotar(ctx, "tv", "Observer: ${f.kind.name}", f.why) }
            active.add(f.kind)
        }
        for (k in change.cleared) {
            log = ObserverCore.appendLog(log, ObserverCore.logEntry(agora, "cleared", k, null, "voltou ao normal", snap), agora)
            runCatching { Diario.anotar(ctx, "tv", "Observer: ${k.name}", "voltou ao normal") }
            active.remove(k)
        }
        if (change.cleared.isNotEmpty()) notes = ObserverCore.dropCleared(notes, change.cleared, agora)   // o recado não fica mentindo
        p.edit().putString("log", log).putString("notes", notes).putString("active", active.joinToString(",") { it.name }).apply()
        Log.i(TAG, "apareceu=${change.appeared.map { it.kind }} sumiu=${change.cleared}")
        // A Sofy (rosto e voz) olha a fila: só o que a regra dela manda mostrar agora aparece (SofyTv/SofyPolicy).
        if (change.appeared.isNotEmpty()) Fundo.rodar { runCatching { SofyTv.present(ctx) } }   // fora do lock do Observador
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

    // ---------------------------------------------------------------- pico (fase 2c, sombra)

    @Volatile private var pico: ObserverPico? = null

    private fun pico(p: android.content.SharedPreferences): ObserverPico = pico ?: ObserverPico.fromJson(
        p.getString("pico", null)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }).also { pico = it }

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
                .putString("nights", ObserverMorning.appendNight(p.getString("nights", null), ObserverMorning.nightOf(agora, snap)))
            base?.let { e.putString("baseline", it.toJson().toString()) }
            habit?.let { e.putString("habit", it.toJson().toString()) }
            e.apply()
        }
        Log.i(TAG, "sonda da madrugada: ${snap.mapValues { it.value.value }}")
    }

    /** O relatório da manhã (fase 2d): a noite de hoje contra a mediana das anteriores, e os achados das últimas 24 h. */
    fun morning(ctx: Context): ObserverMorning.Report {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val agora = System.currentTimeMillis()
        return ObserverMorning.report(ObserverMorning.readNights(p.getString("nights", null)),
            ObserverCore.readLog(p.getString("log", null), agora), agora)
    }

    /** Inventário dos pacotes contra as fichas (vigia de pacotes, parte 1). Lê o PackageManager: fora da thread da tela. */
    fun packages(ctx: Context): ObserverPackages.Inventory {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        // MATCH_UNINSTALLED_PACKAGES: app oculto pelo Device Owner (congelado por nós, inclusive sob demanda) some da lista
        // comum. Visto na Sala 20/11 15:45Z: 133 pacotes viraram 131 depois de congelar dois. Desinstalado de verdade
        // (sem FLAG_INSTALLED) fica fora; oculto conta como congelado.
        val apps = pm.getInstalledApplications(android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS or
            android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)
            .filter { it.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED != 0 }
        val lista = apps.map { a ->
            ObserverPackages.Pkg(a.packageName, (a.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0,
                !a.enabled || PrivilegioDO.estaOculto(ctx, a.packageName))
        }
        val fichas = runCatching { org.json.JSONObject(ComunidadeApps.fichasJuntas(ctx)) }.getOrDefault(org.json.JSONObject())
        // Versão da Play: sem QUERY_ALL_PACKAGES o Android filtra a lista; o inventário diz que está incompleto.
        val completo = ctx.checkSelfPermission(android.Manifest.permission.QUERY_ALL_PACKAGES) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return ObserverPackages.inventory(lista, fichas, completo)
    }

    // ---------------------------------------------------------------- prova de congelamento (fase 3)

    @Volatile private var trialsPending = true   // até a 1ª leitura das prefs: pode haver prova de antes do reinício

    /** Estado de um pacote: null = não existe nesta TV; true = instalado, visível e ligado; false = oculto ou congelado. */
    private fun pkgOk(ctx: Context, pkg: String): Boolean? {
        @Suppress("DEPRECATION")
        val ai = try { ctx.packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES or android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS) }
            catch (_: Exception) { return null }
        if (ai.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED == 0) return null
        return ai.enabled && !PrivilegioDO.estaOculto(ctx, pkg)
    }

    /** As conferências de agora. A que não se aplica nesta TV (sem Cast, por exemplo) fica fora do mapa. */
    fun checks(ctx: Context): Map<ObserverTrial.Check, Boolean> {
        val pm = ctx.packageManager
        fun abre(i: android.content.Intent) = runCatching { pm.resolveActivity(i, 0) != null }.getOrDefault(false)
        val m = LinkedHashMap<ObserverTrial.Check, Boolean>()
        m[ObserverTrial.Check.VOICE] = abre(android.content.Intent(android.content.Intent.ACTION_ASSIST)) ||
            abre(android.content.Intent("android.speech.action.WEB_SEARCH"))
        pkgOk(ctx, "com.android.vending")?.let { v ->
            m[ObserverTrial.Check.PLAY_STORE] = v && (pm.getLeanbackLaunchIntentForPackage("com.android.vending") ?: pm.getLaunchIntentForPackage("com.android.vending")) != null
        }
        m[ObserverTrial.Check.SETTINGS] = abre(android.content.Intent(android.provider.Settings.ACTION_SETTINGS))
        pkgOk(ctx, "com.google.android.apps.mediashell")?.let { cast ->
            // O Cast precisa do setupwraith (credenciais de fábrica, 06/11/2026); onde ele não existe, não conta.
            m[ObserverTrial.Check.CAST] = cast && pkgOk(ctx, "com.google.android.tungsten.setupwraith") != false
        }
        val net = blackBox.snapshot()[ObserverCore.Signal.INTERNET]
        if (net != null && System.currentTimeMillis() - net.at <= ObserverCore.STALE_MS) m[ObserverTrial.Check.INTERNET] = net.value == 1L
        return m
    }

    /** O painel congelou [pkg]: guarda as conferências de antes. Chamar logo DEPOIS de congelar não serve: chamar antes. */
    fun beforeFreeze(ctx: Context): Map<ObserverTrial.Check, Boolean> = checks(ctx.applicationContext)

    @Synchronized fun startTrial(ctx: Context, pkg: String, before: Map<ObserverTrial.Check, Boolean>) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val agora = System.currentTimeMillis()
        val lista = ObserverTrial.add(ObserverTrial.fromJson(p.getString("trials", null)), ObserverTrial.start(pkg, agora, before))
        val log = ObserverCore.appendLog(p.getString("log", null), ObserverCore.logEntry(agora, "trial_start", null, null, pkg, blackBox.snapshot()), agora)
        p.edit().putString("trials", ObserverTrial.toJson(lista)).putString("log", log).apply()
        trialsPending = true
    }

    /** Na batida da rede (5 min): decide as provas vencidas. Desfaz o congelamento que quebrou algo que funcionava. */
    @Synchronized fun checkTrials(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lista = ObserverTrial.fromJson(p.getString("trials", null))
        if (lista.isEmpty()) { trialsPending = false; return }
        val agora = System.currentTimeMillis()
        val agoraChecks by lazy { checks(ctx) }
        var log = p.getString("log", null)
        val restantes = ArrayList<ObserverTrial.Trial>()
        var avisarSofy = false
        for (t in lista) {
            if (agora - t.at > ObserverTrial.EXPIRE_MS) continue
            // Alguém já descongelou à mão: a prova acabou.
            if (pkgOk(ctx, t.pkg) != false) continue
            when (val v = ObserverTrial.decide(t, agora, agoraChecks)) {
                ObserverTrial.Verdict.Wait -> restantes += t
                ObserverTrial.Verdict.Keep -> {
                    log = ObserverCore.appendLog(log, ObserverCore.logEntry(agora, "trial_kept", null, null, t.pkg, blackBox.snapshot()), agora)
                    runCatching { Diario.anotar(ctx, "tv", "Observer: ${t.pkg}", "congelado e conferido") }
                }
                is ObserverTrial.Verdict.Undo -> {
                    PrivilegioDO.ocultar(ctx, t.pkg, false)
                    runCatching { OnDemandTv.forget(ctx, t.pkg) }   // quebrou algo: não volta a congelar sozinho depois de usado
                    val voltou = pkgOk(ctx, t.pkg) == true
                    // O Android recusou (shell fora do ar, DPM disse não): a prova fica para tentar na próxima batida (Codex, #335).
                    if (!voltou) { restantes += t; Log.w(TAG, "prova: ${t.pkg} quebrou ${v.broke}; descongelar falhou, tenta de novo"); continue }
                    log = ObserverCore.appendLog(log, ObserverCore.logEntry(agora, "trial_undone", null, null, t.pkg, blackBox.snapshot())
                        .put("broke", org.json.JSONArray(v.broke.map { it.name })).put("restored", voltou), agora)
                    runCatching { Diario.anotar(ctx, "tv", "Observer: ${t.pkg}", "congelamento desfeito: parou ${v.broke.joinToString { it.name }}") }
                    // Recado para a Sofy: ela agiu sozinha e desfez (ACTED_FELT). Nome e o que parou já no idioma da TV.
                    runCatching {
                        val nome = runCatching { ctx.packageManager.getApplicationInfo(t.pkg, 0).loadLabel(ctx.packageManager).toString() }.getOrDefault(t.pkg)
                        val parou = v.broke.joinToString(", ") { checkLabel(ctx, it) }
                        p.edit().putString("notes", ObserverCore.enqueue(p.getString("notes", null), ObserverCore.freezeUndoneNote(agora, nome, parou), agora)).apply()
                        avisarSofy = true
                    }
                    Log.w(TAG, "prova: ${t.pkg} quebrou ${v.broke}; descongelado=$voltou")
                }
            }
        }
        p.edit().putString("trials", ObserverTrial.toJson(restantes)).putString("log", log).apply()
        trialsPending = restantes.isNotEmpty()
        if (avisarSofy) Fundo.rodar { runCatching { SofyTv.present(ctx) } }   // fora do lock do Observador
    }

    /** O nome da conferência no idioma da TV, para o recado da Sofy. */
    private fun checkLabel(ctx: Context, c: ObserverTrial.Check): String = ctx.getString(when (c) {
        ObserverTrial.Check.VOICE -> R.string.observer_check_voice
        ObserverTrial.Check.PLAY_STORE -> R.string.observer_check_play_store
        ObserverTrial.Check.SETTINGS -> R.string.observer_check_settings
        ObserverTrial.Check.CAST -> R.string.observer_check_cast
        ObserverTrial.Check.INTERNET -> R.string.observer_check_internet
    })

    // ---------------------------------------------------------------- recados (para o rosto do Observador, fase do mascote)

    /** Os recados que valem agora, do mais novo ao mais velho. Quem mostra escolhe a frase pelo [ObserverCore.Note.kind]. */
    fun notes(ctx: Context): List<ObserverCore.Note> {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        // Rede de segurança do dropCleared: recado de um estado que não está mais ativo não aparece (cobre a fila gravada
        // por uma versão anterior, que não tirava o recado quando o estado acabava).
        val ativos = p.getString("active", "").orEmpty().split(',').toSet()
        return ObserverCore.readNotes(p.getString("notes", null), System.currentTimeMillis())
            .filter { it.step != ObserverCore.Step.OBSERVED || it.kind.name in ativos }
    }

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
            .put("phase", 3).put("acts", true).put("running", app != null)
            .put("quiet_start_hour", runCatching { quietStart(ctx) }.getOrDefault(ObserverHabit.DEFAULT_HOUR))
            .put("signals", sinais).put("now", agoraJson)
            .put("notes", ObserverCore.toJson(notes(ctx)))
            .put("morning", runCatching { morning(ctx).toJson() }.getOrNull() ?: org.json.JSONObject.NULL)
            .put("packages", runCatching { packages(ctx).toJson() }.getOrNull() ?: org.json.JSONObject.NULL)
            .put("trials", runCatching { org.json.JSONArray(ObserverTrial.toJson(ObserverTrial.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("trials", null)))) }.getOrNull() ?: org.json.JSONArray())
            .put("checks", runCatching { org.json.JSONObject().apply { checks(ctx).forEach { (c, v) -> put(c.name, v) } } }.getOrNull() ?: org.json.JSONObject.NULL)
            .put("on_demand", runCatching { org.json.JSONArray(OnDemand.toJson(OnDemandTv.entries(ctx))) }.getOrNull() ?: org.json.JSONArray())
            .put("log_total", log.length()).put("log", logNovo)
    }

    fun report(ctx: Context): String = buildString {
        val agora = System.currentTimeMillis()
        appendLine("Observer (fase 3: observa, aprende, registra e desfaz congelamento que quebrou algo). ativa=${app != null}")
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
        val m = morning(ctx)
        appendLine("manhã: " + if (m.night == null) "sem sonda nas últimas 20 h" else
            "noite de ${java.text.SimpleDateFormat("dd/MM HH:mm", java.util.Locale.ROOT).format(m.night.at)} contra ${m.compared} anteriores; " +
            m.items.joinToString("; ") { "${it.signal.name} ${it.tonight} (normal ${it.usual}) ${it.change}" } +
            "; achados 24 h: ${m.events24h.ifEmpty { null } ?: "nenhum"}")
        runCatching { packages(ctx) }.getOrNull()?.let { inv ->
            appendLine("pacotes${if (inv.complete) "" else " (só os visíveis: versão sem QUERY_ALL_PACKAGES)"}: ${inv.total} (${inv.system} de sistema, ${inv.frozen.size} congelados); por risco ${inv.byRisk}; " +
                "sem ficha ${inv.unknown.size}" + inv.unknown.take(20).joinToString("") { "\n  ? ${it.name}${if (it.system) " (sistema)" else ""}" })
        }
        val provas = ObserverTrial.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("trials", null))
        appendLine("provas: " + if (provas.isEmpty()) "nenhuma" else provas.joinToString("; ") { "${it.pkg} há ${(agora - it.at) / 60_000} min" })
        appendLine("conferências: " + runCatching { checks(ctx).entries.joinToString(" ") { "${it.key.name}=${it.value}" } }.getOrDefault("?"))
        appendLine(runCatching { OnDemandTv.report(ctx) }.getOrDefault("sob demanda: ?"))
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
