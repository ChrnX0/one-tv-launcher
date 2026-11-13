package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.FrameMetrics
import android.view.Window
import org.json.JSONObject

/**
 * A parte do Android da guarda de desempenho ([Desempenho]). A HOME liga o contador no onResume e desliga no onPause;
 * a cada pausa o que juntou vai para as prefs "desempenho" (versão atual e a anterior).
 *
 * O FrameMetrics entrega os números numa thread própria ([fio]), nunca na da tela: medir não pode deixar a HOME lenta.
 */
object DesempenhoTv {
    private const val PREFS = "desempenho"
    private val fio by lazy { HandlerThread("quadros").apply { start() } }
    private val trava = Any()
    private var parcial = Desempenho.Medida()
    private var ouvinte: Window.OnFrameMetricsAvailableListener? = null
    /** Instante (uptime) do onCreate de uma HOME nova; o 1º quadro depois dele dá o tempo de abertura. */
    @Volatile private var abriuEm = 0L

    fun criou() { abriuEm = SystemClock.uptimeMillis() }

    fun ligar(a: Activity) {
        if (ouvinte != null) return
        val o = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            val ms = m.getMetric(FrameMetrics.TOTAL_DURATION) / 1_000_000.0
            val abriu = abriuEm
            synchronized(trava) {
                parcial.quadro(ms)
                if (abriu != 0L) { abriuEm = 0L; parcial.abertura((SystemClock.uptimeMillis() - abriu).toInt()) }
            }
        }
        try { a.window.addOnFrameMetricsAvailableListener(o, Handler(fio.looper)); ouvinte = o } catch (_: Exception) {}
    }

    fun desligar(a: Activity) {
        val o = ouvinte ?: return
        ouvinte = null
        try { a.window.removeOnFrameMetricsAvailableListener(o) } catch (_: Exception) {}
        val juntou = synchronized(trava) { parcial.also { parcial = Desempenho.Medida() } }
        if (juntou.total == 0 && juntou.aberturas.isEmpty()) return
        val app = a.applicationContext
        Fundo.rodar("desempenho") { runCatching { guardar(app, juntou) } }
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun guardar(ctx: Context, juntou: Desempenho.Medida) {
        val p = prefs(ctx)
        val versao = BuildConfig.VERSION_CODE.toLong()
        val atual = Desempenho.deJson(p.getString("atual", null)?.let { runCatching { JSONObject(it) }.getOrNull() })
        val versaoGuardada = p.getLong("versao_atual", 0L)
        val e = p.edit()
        val medida = if (versaoGuardada == versao) atual else {
            // Versão nova: a atual vira a "anterior" (se tinha quadros de verdade) e a contagem recomeça.
            if (versaoGuardada != 0L && atual.total >= Desempenho.MIN_QUADROS) {
                e.putString("anterior", Desempenho.paraJson(atual).toString()).putLong("versao_anterior", versaoGuardada)
            }
            e.putLong("versao_atual", versao).putBoolean("alertou", false)
            Desempenho.Medida()
        }
        medida.somar(juntou)
        e.putString("atual", Desempenho.paraJson(medida).toString()).apply()
        conferir(ctx)
    }

    /** Compara e, se piorou, avisa uma vez por versão (painel > Saúde e celular). */
    private fun conferir(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("alertou", false)) return
        val c = comparacao(ctx) ?: return
        if (!c.piorou) return
        p.edit().putBoolean("alertou", true).apply()
        Avisos.enviar(ctx, Avisos.Tipo.DESEMPENHO, "Launcher mais lento",
            "Versão ${p.getLong("versao_atual", 0)}: quadro lento ${c.p90Agora} ms, era ${c.p90Antes} ms (+${c.porcento}%).")
    }

    private fun medidas(ctx: Context): Pair<Desempenho.Medida, Desempenho.Medida> {
        val p = prefs(ctx)
        fun ler(k: String) = Desempenho.deJson(p.getString(k, null)?.let { runCatching { JSONObject(it) }.getOrNull() })
        return ler("anterior") to ler("atual")
    }

    fun comparacao(ctx: Context): Desempenho.Comparacao? = medidas(ctx).let { (a, b) -> Desempenho.comparar(a, b) }

    /** Para o painel (Extras) e o MCP. */
    fun json(ctx: Context): JSONObject {
        val p = prefs(ctx)
        val (ant, atu) = medidas(ctx)
        val c = Desempenho.comparar(ant, atu)
        return JSONObject()
            .put("atual", Desempenho.resumo(p.getLong("versao_atual", 0), atu))
            .put("anterior", if (ant.total > 0) Desempenho.resumo(p.getLong("versao_anterior", 0), ant) else JSONObject.NULL)
            .put("piorou", c?.piorou ?: false)
            .put("situacao", when {
                c == null -> "medindo (precisa de ${Desempenho.MIN_QUADROS} quadros em cada versão)"
                c.piorou -> "mais lenta: quadro lento ${c.p90Agora} ms, era ${c.p90Antes} ms"
                else -> "igual ou mais rápida: quadro lento ${c.p90Agora} ms, era ${c.p90Antes} ms"
            })
    }
}
