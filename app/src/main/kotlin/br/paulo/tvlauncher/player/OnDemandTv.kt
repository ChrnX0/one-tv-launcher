package br.paulo.tvlauncher.player

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.util.Log
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.OuvinteNotificacoes
import br.paulo.tvlauncher.PainelApps
import br.paulo.tvlauncher.home.IconPackFreezer
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.observer.ObserverTv
import br.paulo.tvlauncher.privilege.JanelaNaFrente
import br.paulo.tvlauncher.privilege.PrivilegioDO

/**
 * O lado Android do congelar sob demanda (regra em [OnDemand]).
 *
 *  - [mark]: guarda nome e ícone (a HOME desenha da cópia enquanto ele está congelado), congela pelo [PrivilegioDO] e
 *    abre a prova do Observador (10 min: se algo que funcionava parar, ele descongela sozinho e o tira daqui).
 *  - [open]: o OK na tela inicial descongela, espera a tela dele existir de novo (até 3 s) e abre.
 *  - [onWindow] (serviço de acessibilidade, a cada tela) e [tick] (batida da rede, 5 min): passados 2 min longe da
 *    frente e sem tocar mídia, congela de novo.
 *  - [unmark]: descongela e esquece.
 * Prefs "on_demand" (fora do backup de propósito: o congelamento é desta TV).
 * adb: EXTRAS --es o ondemand | ondemand_mark --es pacote <p> | ondemand_unmark --es pacote <p>.
 */
object OnDemandTv {
    private const val TAG = "TvLauncher.SobDemanda"
    private const val PREFS = "on_demand"
    /** "Pacote" falso da pasta de ícones do IconPackFreezer: filesDir/icon-cache/_on_demand/<pacote>.png. */
    private const val ICONS = "_on_demand"
    private const val OPEN_WAIT_MS = 3_000L

    private val main by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var cache: List<OnDemand.Entry>? = null

    @Synchronized fun entries(ctx: Context): List<OnDemand.Entry> =
        cache ?: OnDemand.fromJson(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("entries", null)).also { cache = it }

    @Synchronized private fun save(ctx: Context, l: List<OnDemand.Entry>) {
        cache = l
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("entries", OnDemand.toJson(l)).apply()
    }

    fun marked(ctx: Context, pkg: String) = entries(ctx).any { it.pkg == pkg }

    /** O ícone guardado (o original, sem arredondar: quem desenha arredonda como os outros). */
    fun icon(ctx: Context, pkg: String): Drawable? = IconPackFreezer.copia(ctx, ICONS, pkg)

    /** Pode ser sob demanda (para o menu da HOME): ver [OnDemand.eligible]. */
    fun eligible(ctx: Context, pkg: String): Boolean {
        val pm = ctx.packageManager
        val tela = runCatching { (pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)) != null }.getOrDefault(false)
        return OnDemand.eligible(tela || marked(ctx, pkg), PainelApps.protegido(ctx, pkg), pkg == ctx.packageName,
            PainelApps.eSistema(ctx, pkg), PainelApps.risco(ctx, pkg))
    }

    /** Congela e passa a abrir sob demanda. BLOQUEIA (sem Device Owner o shell leva até ~20 s): fora da thread da tela. Null = ok. */
    fun mark(ctx: Context, pkg: String): String? {
        val app = ctx.applicationContext
        if (marked(app, pkg)) return null
        if (!eligible(app, pkg)) return "não pode: sem tela, protegido, ou app de sistema sem ficha \"seguro\""
        val pm = app.packageManager
        val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return "não instalado"
        val nome = runCatching { ai.loadLabel(pm).toString() }.getOrDefault(pkg)
        val launch = pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg)
        val icone = runCatching { launch?.let { pm.getActivityIcon(it) } ?: ai.loadIcon(pm) }.getOrNull()
        if (icone != null) IconPackFreezer.guardar(app, ICONS, pkg, icone)
        val antes = runCatching { ObserverTv.beforeFreeze(app) }.getOrNull()
        if (!PrivilegioDO.ocultar(app, pkg, true) || !PrivilegioDO.estaOculto(app, pkg)) return "o Android não deixou congelar"
        save(app, OnDemand.put(entries(app), OnDemand.Entry(pkg, nome)))
        if (antes != null) runCatching { ObserverTv.startTrial(app, pkg, antes) }
        Log.i(TAG, "sob demanda: $pkg")
        return null
    }

    /** Descongela e deixa de ser sob demanda. BLOQUEIA como [mark]. Null = ok. */
    fun unmark(ctx: Context, pkg: String): String? {
        val app = ctx.applicationContext
        if (PrivilegioDO.estaOculto(app, pkg) && !PrivilegioDO.ocultar(app, pkg, false)) return "o Android não deixou descongelar"
        save(app, entries(app).filter { it.pkg != pkg })
        runCatching { java.io.File(app.filesDir, "icon-cache/$ICONS/$pkg.png").delete() }
        return null
    }

    /** O Observador desfez o congelamento porque algo parou: deixa de ser sob demanda, sem mexer no estado dele. */
    fun forget(ctx: Context, pkg: String) {
        if (marked(ctx, pkg)) save(ctx.applicationContext, entries(ctx).filter { it.pkg != pkg })
    }

    /**
     * Abre um app sob demanda (thread da tela). Congelado: descongela fora da tela, espera a tela dele aparecer e abre.
     * [falhou] roda na thread da tela se não deu (a cruz avisa).
     */
    fun open(ctx: Context, pkg: String, falhou: () -> Unit) {
        val app = ctx.applicationContext
        Fundo.rodar("sob-demanda-abrir") {
            val pm = app.packageManager
            fun intent() = runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) ?: pm.getLaunchIntentForPackage(pkg) }.getOrNull()
            if (PrivilegioDO.estaOculto(app, pkg)) PrivilegioDO.ocultar(app, pkg, false)
            var i = intent()
            val limite = System.currentTimeMillis() + OPEN_WAIT_MS
            while (i == null && System.currentTimeMillis() < limite) { Thread.sleep(100); i = intent() }
            if (i == null) { Log.w(TAG, "$pkg não abriu depois de descongelar"); main.post(falhou); return@rodar }
            save(app, OnDemand.update(entries(app), pkg) { it.copy(thawedAt = System.currentTimeMillis(), leftAt = 0L) })
            val abrir = i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            main.post { runCatching { app.startActivity(abrir) }.onFailure { Log.w(TAG, "abrir $pkg: $it"); falhou() } }
        }
    }

    /** Serviço de acessibilidade, a cada tela que abre (thread da tela): só faz conta se há algum descongelado. */
    fun onWindow(ctx: Context, foreground: CharSequence?) {
        val l = cache ?: return
        if (l.none { it.thawed }) return
        val agora = System.currentTimeMillis()
        val novo = OnDemand.onWindow(l, foreground?.toString(), agora)
        if (novo != l) {
            save(ctx.applicationContext, novo)
            val app = ctx.applicationContext
            main.postDelayed({ tick(app) }, OnDemand.GRACE_MS + 5_000L)
        }
    }

    /** Congela de novo o que já passou do prazo. Chamado pelo [onWindow] (2 min depois) e pela batida da rede. */
    fun tick(ctx: Context) {
        val app = ctx.applicationContext
        if (entries(app).none { it.thawed }) return
        Fundo.rodar("sob-demanda-congelar") {
            val vencidos = OnDemand.due(entries(app), JanelaNaFrente.pacote, playing(app), System.currentTimeMillis())
            for (p in vencidos) {
                val ok = PrivilegioDO.ocultar(app, p, true) && PrivilegioDO.estaOculto(app, p)
                Log.i(TAG, "congelar de novo $p => $ok")
                if (ok) save(app, OnDemand.update(entries(app), p) { it.copy(thawedAt = 0L, leftAt = 0L) })
            }
        }
    }

    /**
     * Apps com mídia tocando agora (música em segundo plano não congela). Sem o ouvinte de notificações não dá para saber
     * QUEM toca (Codex, #338): se algum som toca, vale como "todos tocando" e ninguém congela nesta batida.
     */
    private fun playing(ctx: Context): Set<String> = try {
        ctx.getSystemService(MediaSessionManager::class.java)
            ?.getActiveSessions(ComponentName(ctx, OuvinteNotificacoes::class.java))
            ?.filter { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?.map { it.packageName }?.toSet() ?: emptySet()
    } catch (_: Exception) {
        val tocando = runCatching { ctx.getSystemService(android.media.AudioManager::class.java)?.isMusicActive == true }.getOrDefault(true)
        if (tocando) entries(ctx).map { it.pkg }.toSet() else emptySet()
    }

    /** Ainda instalado (congelado conta). Desinstalado sai da lista (Codex, #338: sobrava um card morto na HOME). */
    fun installed(ctx: Context, pkg: String): Boolean {
        @Suppress("DEPRECATION")
        val ai = runCatching { ctx.packageManager.getApplicationInfo(pkg, android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES or android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS) }.getOrNull()
        val ok = ai != null && ai.flags and android.content.pm.ApplicationInfo.FLAG_INSTALLED != 0
        if (!ok) forget(ctx, pkg)
        return ok
    }

    /** adb (EXTRAS ondemand). */
    fun report(ctx: Context): String {
        val l = entries(ctx)
        if (l.isEmpty()) return "sob demanda: nenhum"
        return "sob demanda (${l.size}):" + l.joinToString("") {
            "\n  ${it.pkg} \"${it.label}\" " + (if (it.thawed) "descongelado" + (if (it.leftAt > 0) ", fora da frente há ${(System.currentTimeMillis() - it.leftAt) / 1000} s" else ", na frente")
                else "congelado") + " oculto=${PrivilegioDO.estaOculto(ctx, it.pkg)}"
        }
    }
}
