package br.paulo.tvlauncher.observer

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.util.Log
import br.paulo.tvlauncher.AntesDepois
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.PlacarActivity
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.privilege.Debloat
import br.paulo.tvlauncher.privilege.GuardiaoShizuku

/**
 * Antes e depois da limpeza (elevação 14.3, onda 0, 17/11/2026), a parte que mede na TV. As regras e a frase moram em
 * AntesDepois.kt (testadas).
 *
 * O que mede, sempre a pedido da pessoa (linha "Medir antes e depois" na limpeza de apps de fábrica):
 *  - memória livre do Android (ActivityManager.MemoryInfo.availMem), antes de abrir qualquer app;
 *  - tempo de abrir os 3 apps mais usados nos últimos 14 dias, "a frio": `am force-stop` e `am start -W` pelo shell
 *    próprio (o TotalTime que o Android mede, o mesmo do `adb shell am start -W`). Cada app aparece na tela por uns
 *    segundos; no fim as Configurações voltam para a frente. Sem o shell próprio, só a memória.
 * Nada disso roda sozinho de madrugada: abrir apps na tela de alguém só com a pessoa na frente e pedindo.
 */
object LimpezaMedida {
    private const val TAG = "TvLauncher.Limpeza"
    private const val ANTES = "limpeza_antes"
    private const val FRASE = "limpeza_frase"
    private const val QUATORZE_DIAS = 14L * 24 * 3600 * 1000

    fun antes(ctx: Context): AntesDepois.Medida? = AntesDepois.ler(MedidasTv.lerTexto(ctx, ANTES))
    fun ultimaFrase(ctx: Context): String? = MedidasTv.lerTexto(ctx, FRASE)?.takeIf { it.isNotBlank() }

    /** Os [n] apps com mais tempo na tela em 14 dias, que abrem pela HOME (sem nós e sem o sistema). */
    fun maisUsados(ctx: Context, n: Int = 3): List<String> = try {
        val um = ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val agora = System.currentTimeMillis()
        um.queryAndAggregateUsageStats(agora - QUATORZE_DIAS, agora).values
            .filter { it.totalTimeInForeground > 0 && it.packageName != ctx.packageName && it.packageName !in Debloat.NUNCA }
            .sortedByDescending { it.totalTimeInForeground }
            .map { it.packageName }
            .filter { componente(ctx, it) != null }
            .take(n)
    } catch (e: Exception) { Log.w(TAG, "uso: $e"); emptyList() }

    private fun componente(ctx: Context, p: String): String? {
        val pm = ctx.packageManager
        val i = pm.getLeanbackLaunchIntentForPackage(p) ?: pm.getLaunchIntentForPackage(p) ?: return null
        return i.component?.flattenToShortString()
    }

    fun memoriaLivreMb(ctx: Context): Long {
        val am = ctx.getSystemService(ActivityManager::class.java)
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }.availMem / 1_048_576
    }

    /** Mede agora (bloqueia por até ~40 s: chamar fora da thread da tela). */
    fun medirAgora(ctx: Context): AntesDepois.Medida {
        val app = ctx.applicationContext
        val livre = memoriaLivreMb(app)
        val tempos = LinkedHashMap<String, Long>()
        for (p in maisUsados(app)) {
            val comp = componente(app, p) ?: continue
            // force-stop: o tempo "a frio" é o que a limpeza muda (app quente abre da memória, igual antes e depois).
            val saida = GuardiaoShizuku.comando(app, "am force-stop $p; am start -W -n $comp", 15_000)
            AntesDepois.tempoDoAmStart(saida)?.let { tempos[p] = it }
            if (saida == null) break   // sem shell: não adianta tentar os outros
        }
        if (tempos.isNotEmpty()) {
            // Devolve a tela a quem pediu (as Configurações), por cima do último app aberto.
            GuardiaoShizuku.comando(app, "am start -f 0x20000 -n ${app.packageName}/${ConfigLauncherActivity::class.java.name}", 5_000)
        }
        return AntesDepois.Medida(livre, tempos, System.currentTimeMillis())
    }

    fun medirAntes(ctx: Context): AntesDepois.Medida =
        medirAgora(ctx).also { MedidasTv.gravarTexto(ctx, ANTES, AntesDepois.texto(it)); MedidasTv.gravarTexto(ctx, FRASE, "") }

    /** Mede o "depois", compara com o "antes" guardado e devolve a frase (null se não houver "antes"). */
    fun medirDepois(ctx: Context): String? {
        val a = antes(ctx) ?: return null
        val d = medirAgora(ctx)
        val dif = AntesDepois.comparar(a, d)
        val pm = ctx.packageManager
        val nomes = (a.abrirMs.keys + d.abrirMs.keys).associateWith { p ->
            runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        }
        val frase = AntesDepois.frase(dif, nomes,
            ctx.getString(R.string.elv_lp_mais_livre), ctx.getString(R.string.elv_lp_menos_livre),
            ctx.getString(R.string.elv_lp_mais_rapido), ctx.getString(R.string.elv_lp_mais_lento),
            ctx.getString(R.string.elv_lp_igual)) { Placar.tempoTexto(it, PlacarActivity.decimal()) }
        MedidasTv.gravarTexto(ctx, FRASE, frase)
        MedidasTv.anotarAgora(ctx, Placar.Chave.LIMPEZA, dif.memoriaMb)
        Log.i(TAG, "antes/depois: $frase")
        return frase
    }
}
