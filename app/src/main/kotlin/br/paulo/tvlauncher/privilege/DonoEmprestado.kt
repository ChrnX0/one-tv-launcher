package br.paulo.tvlauncher.privilege

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import br.paulo.tvlauncher.AdminReceiver
import br.paulo.tvlauncher.BuildConfig
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.PlayPolicy
import br.paulo.tvlauncher.settings.Planos
import org.json.JSONObject

/**
 * O launcher EMPRESTA o Device Owner a outros apps: servidor do Dhizuku embutido (15/11/2026, "poder máximo").
 * Apps feitos para o Dhizuku procuram o dono atual da TV e chamam `<pacote>.dhizuku_server.provider` (ProvedorDhizuku);
 * daí em diante as chamadas deles ao DevicePolicyManager saem com o uid do launcher, filtradas (RegrasDono, FiltroDpm).
 *
 * Desligado de fábrica; parte do Extras pago (Planos.Recurso.PODER_EMPRESTADO); sem Device Owner não existe.
 * Cada app é autorizado na TV com o PIN (PedidoDhizukuActivity). "Poder total" (processo/serviço no uid do launcher,
 * delegar escopos) é por app, com PIN e aviso (decisão do Paulo, 15/11/2026), e o painel revoga.
 * Prefs "emprestimo_dono" (fora do backup completo: autorização é desta TV, uid muda entre aparelhos).
 */
object DonoEmprestado {
    private const val PREFS = "emprestimo_dono"

    data class App(val pacote: String, val uid: Int, val total: Boolean)

    @Volatile private var cache: Map<Int, App>? = null

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun anotar(ctx: Context, t: String) = ServidorShizuku.anotar(ctx, "[dono] $t")

    fun ligado(ctx: Context) = prefs(ctx).getBoolean("ligado", false)

    fun definirLigado(ctx: Context, sim: Boolean) {
        prefs(ctx).edit().putBoolean("ligado", sim).apply()
        anotar(ctx, if (sim) "ligado" else "desligado")
        if (!sim) parado(ctx)   // tira na hora os escopos que os apps de poder total delegaram a si
    }

    fun somosDono(ctx: Context) = PrivilegioDO.somosDonos(ctx)

    /**
     * Também limpa os escopos delegados quando o empréstimo para (desligado ou plano perdido): um app de poder total
     * pode ter dado a si "permission-grant", "package-access"... e esses chamam o DPM direto, sem o servidor nem o
     * FiltroDpm. Sem dono não precisa: os escopos somem junto com o dono. Uma vez por parada (marca "escopos_limpos").
     */
    fun parado(ctx: Context): RegrasDono.Parado {
        val p = RegrasDono.parado(ligado(ctx),
            // Versão da Play enxuta (PlayPolicy): emprestar o Device Owner não existe; fica parado como "sem plano".
            Planos.permite(ctx, Planos.Recurso.PODER_EMPRESTADO) && PlayPolicy.allowed(PlayPolicy.Power.LEND_POWERS, BuildConfig.PLAY),
            somosDono(ctx))
        val limpos = prefs(ctx).getBoolean("escopos_limpos", true)
        if (p == RegrasDono.Parado.NAO) {
            if (limpos) prefs(ctx).edit().putBoolean("escopos_limpos", false).apply()
        } else if (!limpos && p != RegrasDono.Parado.SEM_DONO) {
            apps(ctx).forEach { tirarEscopos(ctx, it.pacote); encerrar(ctx, it.uid) }
            prefs(ctx).edit().putBoolean("escopos_limpos", true).apply()
            anotar(ctx, "parado (${p.name.lowercase()}): escopos delegados tirados")
        }
        return p
    }

    fun apps(ctx: Context): List<App> = mapa(ctx).values.sortedBy { it.pacote }

    // @Synchronized (mapa e gravar): sem trava, uma thread de binder podia regravar o cache antigo logo depois de um
    // revogar, e o app revogado continuava autorizado até o processo morrer.
    @Synchronized private fun mapa(ctx: Context): Map<Int, App> {
        cache?.let { return it }
        val r = HashMap<Int, App>()
        try {
            val o = JSONObject(prefs(ctx).getString("apps", "{}") ?: "{}")
            for (k in o.keys()) {
                val a = o.getJSONObject(k)
                val app = App(k, a.getInt("uid"), a.optBoolean("total", false))
                r[app.uid] = app
            }
        } catch (_: Exception) {}
        cache = r
        return r
    }

    @Synchronized private fun gravar(ctx: Context, apps: Collection<App>) {
        val o = JSONObject()
        apps.forEach { o.put(it.pacote, JSONObject().put("uid", it.uid).put("total", it.total)) }
        prefs(ctx).edit().putString("apps", o.toString()).commit()
        cache = null
    }

    /**
     * O app autorizado com este uid (null se nenhum). Confere que o pacote gravado ainda é desse uid: reinstalar muda
     * o uid, e um uid reaproveitado por outro app não herda a autorização.
     */
    fun porUid(ctx: Context, uid: Int): App? {
        val app = mapa(ctx)[uid] ?: return null
        val pacotes = try { ctx.packageManager.getPackagesForUid(uid) } catch (_: Exception) { null }
        return if (pacotes != null && app.pacote in pacotes) app else null
    }

    @Synchronized fun autorizar(ctx: Context, pacote: String, uid: Int, total: Boolean) {
        val apps = mapa(ctx).values.filter { it.pacote != pacote } + App(pacote, uid, total)
        gravar(ctx, apps)
        anotar(ctx, "autorizado $pacote uid=$uid total=$total")
    }

    /** Liga/desliga o poder total de um app já autorizado. Desligar tira também os escopos que ele delegou a si. */
    @Synchronized fun definirTotal(ctx: Context, pacote: String, total: Boolean): Boolean {
        val atual = mapa(ctx).values.firstOrNull { it.pacote == pacote } ?: return false
        gravar(ctx, mapa(ctx).values.filter { it.pacote != pacote } + atual.copy(total = total))
        if (!total) { tirarEscopos(ctx, pacote); encerrar(ctx, atual.uid) }
        anotar(ctx, "poder total de $pacote: $total")
        return true
    }

    @Synchronized fun revogar(ctx: Context, pacote: String) {
        val uid = mapa(ctx).values.firstOrNull { it.pacote == pacote }?.uid
        gravar(ctx, mapa(ctx).values.filter { it.pacote != pacote })
        tirarEscopos(ctx, pacote)
        if (uid != null) encerrar(ctx, uid)
        anotar(ctx, "revogado $pacote")
    }

    /**
     * Revogar não fecha só a porta (17/11/2026, decisão do Paulo "estuda e aplica o melhor"): os processos que o app
     * abriu com o nosso uid (remote_process, com filhos) e os UserServices dele são encerrados na hora. Os que ainda
     * estão no meio de uma chamada terminam nela; nenhuma chamada nova passa (porUid já não acha o app).
     */
    private fun encerrar(ctx: Context, uid: Int) {
        Fundo.rodar("dono-encerrar") {
            try { com.rosan.dhizuku.server_api.DhizukuService.encerrarDoUid(uid); anotar(ctx, "encerrado o que uid=$uid rodava") }
            catch (e: Throwable) { anotar(ctx, "não encerrei uid=$uid: $e") }
            // Os UserServices de TODOS os apps dividem o processo :dhizuku_usuario (uid do launcher): o "onDestroy" só
            // fecha o processo quando não sobra serviço de outro app, e um serviço subindo no meio da revogação escapava.
            // Mata o processo inteiro; os apps ainda autorizados religam o serviço quando precisarem.
            try {
                val am = ctx.getSystemService(android.app.ActivityManager::class.java)
                am.runningAppProcesses?.filter { it.processName == ctx.packageName + ":dhizuku_usuario" }?.forEach {
                    android.os.Process.killProcess(it.pid); anotar(ctx, "processo dos serviços encerrado (pid ${it.pid})")
                }
            } catch (e: Throwable) { anotar(ctx, "não encerrei o processo dos serviços: $e") }
        }
    }

    /**
     * Escopos delegados ficam no sistema mesmo depois de revogar: zera (só o dono pode). Não só os do [pacote]: um app
     * de poder total podia delegar a OUTRO pacote dele (setDelegatedScopes pelo transact) e esse continuava chamando o
     * DPM direto depois da revogação. O launcher não delega nada; fica só o que um app de poder total AINDA autorizado
     * deu a si mesmo.
     */
    private fun tirarEscopos(ctx: Context, pacote: String) {
        if (Build.VERSION.SDK_INT < 26 || !somosDono(ctx)) return
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, AdminReceiver::class.java)
        try { dpm.setDelegatedScopes(admin, pacote, emptyList()) } catch (e: Exception) { anotar(ctx, "não tirei os escopos de $pacote: $e") }
        val mantidos = mapa(ctx).values.filter { it.total }.map { it.pacote }.toSet()
        val delegados = ESCOPOS.flatMap { e -> runCatching { dpm.getDelegatePackages(admin, e) }.getOrNull().orEmpty() }.toSet()
        for (p in delegados - mantidos - ctx.packageName) {
            try { dpm.setDelegatedScopes(admin, p, emptyList()); anotar(ctx, "escopos de $p tirados") }
            catch (e: Exception) { anotar(ctx, "não tirei os escopos de $p: $e") }
        }
    }

    private val ESCOPOS = listOf("delegation-cert-install", "delegation-app-restrictions", "delegation-block-uninstall",
        "delegation-enable-system-app", "delegation-keep-uninstalled-packages", "delegation-package-access",
        "delegation-permission-grant", "delegation-install-existing-package", "delegation-cert-selection",
        "delegation-network-logging", "delegation-security-logging")

    fun estado(ctx: Context): String {
        val p = parado(ctx)
        return (if (p == RegrasDono.Parado.NAO) "ligado" else "parado (${p.name.lowercase()})") +
            " apps=${apps(ctx).size} mapaDpm=${if (FiltroDpm.mapaOk()) "ok" else "falhou"}"
    }

    fun negado(ctx: Context, uid: Int, oque: String) = anotar(ctx, "NEGADO uid=$uid: $oque")
}
