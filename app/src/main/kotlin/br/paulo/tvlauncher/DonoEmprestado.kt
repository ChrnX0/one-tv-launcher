package br.paulo.tvlauncher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
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
    }

    fun somosDono(ctx: Context) = PrivilegioDO.somosDonos(ctx)

    fun parado(ctx: Context): RegrasDono.Parado = RegrasDono.parado(
        ligado(ctx), Planos.permite(ctx, Planos.Recurso.PODER_EMPRESTADO), somosDono(ctx))

    fun apps(ctx: Context): List<App> = mapa(ctx).values.sortedBy { it.pacote }

    private fun mapa(ctx: Context): Map<Int, App> {
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

    private fun gravar(ctx: Context, apps: Collection<App>) {
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

    fun autorizar(ctx: Context, pacote: String, uid: Int, total: Boolean) {
        val apps = mapa(ctx).values.filter { it.pacote != pacote } + App(pacote, uid, total)
        gravar(ctx, apps)
        anotar(ctx, "autorizado $pacote uid=$uid total=$total")
    }

    /** Liga/desliga o poder total de um app já autorizado. Desligar tira também os escopos que ele delegou a si. */
    fun definirTotal(ctx: Context, pacote: String, total: Boolean): Boolean {
        val atual = mapa(ctx).values.firstOrNull { it.pacote == pacote } ?: return false
        gravar(ctx, mapa(ctx).values.filter { it.pacote != pacote } + atual.copy(total = total))
        if (!total) tirarEscopos(ctx, pacote)
        anotar(ctx, "poder total de $pacote: $total")
        return true
    }

    fun revogar(ctx: Context, pacote: String) {
        gravar(ctx, mapa(ctx).values.filter { it.pacote != pacote })
        tirarEscopos(ctx, pacote)
        anotar(ctx, "revogado $pacote")
    }

    /** Escopos delegados ficam no sistema mesmo depois de revogar: zera (só o dono pode). */
    private fun tirarEscopos(ctx: Context, pacote: String) {
        if (Build.VERSION.SDK_INT < 26 || !somosDono(ctx)) return
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.setDelegatedScopes(ComponentName(ctx, AdminReceiver::class.java), pacote, emptyList())
        } catch (e: Exception) { anotar(ctx, "não tirei os escopos de $pacote: $e") }
    }

    fun estado(ctx: Context): String {
        val p = parado(ctx)
        return (if (p == RegrasDono.Parado.NAO) "ligado" else "parado (${p.name.lowercase()})") +
            " apps=${apps(ctx).size} mapaDpm=${if (FiltroDpm.mapaOk()) "ok" else "falhou"}"
    }

    fun negado(ctx: Context, uid: Int, oque: String) = anotar(ctx, "NEGADO uid=$uid: $oque")
}
