package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject

/**
 * Saúde do acesso remoto (10/11/2026, melhoria "saúde do shell e do túnel visível").
 *
 * Duas coisas param em silêncio e a gente só descobria quando precisava delas:
 *  - o SHELL PRÓPRIO (uid 2000, AdbConnectionManager): o pareamento se perde (reset, "Revogar autorizações", troca de
 *    chave) e o adb da TV para de aceitar o launcher. Sem ele: painel sem teclas, MCP sem shell, sem autocura;
 *  - o TÚNEL do Tailscale (TunelTailscale), com o "Claude pela internet" ligado: o login venceu ou o programa não sobe.
 *
 * O que este objeto faz:
 *  1. A cada [TICK_MS] olha o túnel (barato: só lê variáveis) e, a cada [SONDA_SHELL_MS], testa o shell com um `echo`
 *     (se nenhum outro comando provou o shell nesse meio tempo, ver [registrarShell]).
 *  2. Fora há mais de [LIMITE_SHELL_MS] / [LIMITE_TUNEL_MS]: abre o aviso (SaudeAvisoActivity) com o atalho para
 *     refazer. Só com a tela acesa e a NOSSA HOME na frente (nunca por cima de filme, nem do nosso player), uma vez por
 *     queda e no máximo uma vez a cada [INTERVALO_AVISO_MS] por item, mesmo se o launcher reiniciar.
 *  3. [json]: o estado pronto para o painel e o MCP.
 *
 * A regra (quando está fora, quando avisar) fica em [SaudeRegras], sem Android, com teste JVM (SaudeAcessoTest).
 */
object SaudeAcesso {
    private const val TAG = "TvLauncher.Saude"
    private const val PREFS = "saude_acesso"
    private const val TICK_MS = 2 * 60_000L
    private const val SONDA_SHELL_MS = 10 * 60_000L
    const val LIMITE_SHELL_MS = 30 * 60_000L          // ~3 sondas seguidas sem resposta (uma a cada 10 min): não é só o boot demorando
    const val LIMITE_TUNEL_MS = 10 * 60_000L          // o túnel religa sozinho em até 5 min; passou disso, é queda
    const val INTERVALO_AVISO_MS = 6 * 60 * 60_000L   // no máximo 1 aviso a cada 6 h por item (sem spam)

    private val principal = Handler(Looper.getMainLooper())
    private var vigiando = false
    @Volatile private var sondando = false

    // Estado em memória (relógio desde o boot: não pula com acerto de hora). Ver SaudeRegras.Item.
    @Volatile private var shell = SaudeRegras.Item()
    @Volatile private var tunel = SaudeRegras.Item()
    @Volatile private var ultimoOkShell = 0L          // elapsedRealtime do último comando que respondeu
    @Volatile private var ultimaSonda = 0L            // elapsedRealtime da última sonda (respondeu ou não)
    /**
     * Quando a tela acendeu pela última vez (elapsedRealtime); Long.MAX_VALUE = apagada. Revisão 10/11/2026: o relógio
     * desde o boot conta o sono, então uma queda vista às 23h virava "fora há 8 h" ao ligar a TV de manhã, antes de
     * qualquer observação nova. Agora o tempo fora só conta a partir de quando a tela acendeu ([SaudeRegras.precisaAvisar]).
     */
    @Volatile private var acordouEm = SystemClock.elapsedRealtime()
    /** Classe da última janela que apareceu (o pacote vem de JanelaNaFrente.pacote). */
    @Volatile private var classeNaFrente: String? = null

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Liga o vigia. Uma vez por processo; chamado pelo serviço de acessibilidade (que segura o processo vivo). */
    fun vigiar(ctx: Context) {
        if (vigiando) return
        vigiando = true
        val app = ctx.applicationContext
        // SCREEN_ON/OFF só chegam a receiver registrado em código. Vive enquanto o processo vive.
        try {
            app.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    acordouEm = if (i.action == Intent.ACTION_SCREEN_ON) SystemClock.elapsedRealtime() else Long.MAX_VALUE
                }
            }, IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) })
        } catch (e: Exception) { Log.w(TAG, "sem receiver da tela: $e") }
        val tick = object : Runnable {
            override fun run() {
                try { conferir(app) } catch (e: Exception) { Log.w(TAG, "conferir falhou: $e") }
                principal.postDelayed(this, TICK_MS)
            }
        }
        principal.postDelayed(tick, TICK_MS)
    }

    /**
     * Qualquer comando no shell próprio que voltou (ou não) passa por aqui (GuardiaoShizuku). Assim a sonda só roda
     * quando ninguém usou o shell nos últimos 10 min.
     */
    @Synchronized fun registrarShell(ctx: Context, ok: Boolean) {
        val agora = SystemClock.elapsedRealtime()
        // Falha com a tela apagada (ex.: a manutenção das 4h, Wi-Fi em economia) não conta: a TV ligar de manhã não pode
        // herdar uma "queda" que ninguém confirmou acordado.
        if (!ok && !telaAcesa(ctx)) return
        if (ok) {
            ultimoOkShell = agora
            // "Já funcionou alguma vez": só avisamos de pareamento PERDIDO, não de um que nunca foi feito.
            val p = prefs(ctx)
            if (!p.getBoolean("shell_ja_funcionou", false)) p.edit().putBoolean("shell_ja_funcionou", true).apply()
        }
        shell = SaudeRegras.observar(shell, if (ok || shellEsperado(ctx)) ok else null, agora)
    }

    /** O serviço de acessibilidade avisa cada janela nova: a HOME apareceu é a hora boa para um aviso pendente. */
    fun janela(ctx: Context, pacote: CharSequence?, classe: CharSequence?) {
        val p = pacote?.toString() ?: return
        if (p == "android" || p == "com.android.systemui") return   // mesmo filtro do JanelaNaFrente
        classeNaFrente = classe?.toString()
        if (!naHome(ctx)) return
        // Um instante depois: a HOME acabou de abrir, deixa ela desenhar antes de pôr algo por cima.
        val app = ctx.applicationContext
        principal.postDelayed({ talvezAvisar(app); ManutencaoNoturna.talvezMostrarResumo(app) }, 1500)
    }

    /** A nossa HOME (MainActivity) está na frente? Só nela um aviso não atrapalha ninguém. */
    fun naHome(ctx: Context) = SaudeRegras.lugarSeguro(JanelaNaFrente.pacote, classeNaFrente, ctx.packageName)

    /** O shell próprio já respondeu alguma vez neste aparelho? Sem isso é pareamento nunca feito, não perdido. */
    fun shellEsperado(ctx: Context) = prefs(ctx).getBoolean("shell_ja_funcionou", false)

    private fun telaAcesa(ctx: Context) = ctx.getSystemService(PowerManager::class.java)?.isInteractive == true

    /** O túnel deveria estar no ar? MCP ligado, binário presente e login feito alguma vez (sem isso é configuração, não queda). */
    private fun tunelEsperado(ctx: Context) =
        McpServidor.ligado(ctx) && TunelTailscale.binario(ctx).exists() && TunelTailscale.configurado(ctx)

    private fun tunelNoAr() = TunelTailscale.rodando() && TunelTailscale.urlLogin == null && TunelTailscale.urlAtivar == null

    private fun conferir(app: Context) {
        val agora = SystemClock.elapsedRealtime()
        val okTunel = if (tunelEsperado(app)) tunelNoAr() else null
        // Túnel fora com a tela apagada não conta (mesmo motivo do registrarShell).
        if (okTunel != false || telaAcesa(app)) synchronized(this) { tunel = SaudeRegras.observar(tunel, okTunel, agora) }
        if (!shellEsperado(app)) synchronized(this) { shell = SaudeRegras.Item() }
        // Revisão 10/11/2026: conta também a última SONDA, senão com o shell fora ela rodava a cada tick (2 min) e
        // ocupava a fila única do adb ~30% do tempo (comandos do painel e do MCP descartados pela FilaComPrazo).
        else if (agora - maxOf(ultimoOkShell, ultimaSonda) >= SONDA_SHELL_MS && !sondando) {
            sondando = true
            ultimaSonda = agora
            // O comando espera até 35 s (conexão + resposta): fora da thread da tela.
            Fundo.rodar("Saude-sonda") {
                try {
                    val r = GuardiaoShizuku.comando(app, "echo ok", 15_000)
                    registrarShell(app, r?.contains("ok") == true)
                } finally { sondando = false }
                principal.post { talvezAvisar(app) }
            }
        }
        talvezAvisar(app)
    }

    /** Abre o aviso se algum item passou do limite e a hora é boa. Roda na thread da tela. */
    @Synchronized fun talvezAvisar(ctx: Context) {
        val app = ctx.applicationContext
        val agora = SystemClock.elapsedRealtime()
        val parede = System.currentTimeMillis()
        if (!telaAcesa(app) || !naHome(app)) return
        // A tela está acesa mas o SCREEN_ON ainda não chegou (corrida com a janela da HOME): conta como "acabou de acordar".
        if (acordouEm == Long.MAX_VALUE) acordouEm = agora
        val p = prefs(app)
        val avisarShell = SaudeRegras.precisaAvisar(shell, agora, LIMITE_SHELL_MS, p.getLong("aviso_shell", 0), parede, INTERVALO_AVISO_MS, acordouEm)
        val avisarTunel = SaudeRegras.precisaAvisar(tunel, agora, LIMITE_TUNEL_MS, p.getLong("aviso_tunel", 0), parede, INTERVALO_AVISO_MS, acordouEm)
        if (!avisarShell && !avisarTunel) return
        val ctxTela: Context = AutorizaShizukuService.instancia ?: app   // o serviço pode abrir tela vindo de segundo plano
        try {
            ctxTela.startActivity(Intent(ctxTela, SaudeAvisoActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .putExtra(SaudeAvisoActivity.EXTRA_SHELL, avisarShell)
                .putExtra(SaudeAvisoActivity.EXTRA_TUNEL, avisarTunel)
                .putExtra(SaudeAvisoActivity.EXTRA_MINUTOS, ((agora - (if (avisarShell) shell else tunel).foraDesde) / 60_000).toInt()))
        } catch (e: Exception) { Log.w(TAG, "não abri o aviso: $e"); return }
        // Marca já: uma vez por queda, e a hora vai para as prefs para valer também depois de um reinício do launcher.
        val ed = p.edit()
        if (avisarShell) { shell = shell.copy(avisado = true); ed.putLong("aviso_shell", parede) }
        if (avisarTunel) { tunel = tunel.copy(avisado = true); ed.putLong("aviso_tunel", parede) }
        ed.apply()
        Log.i(TAG, "aviso aberto: shell=$avisarShell túnel=$avisarTunel")
    }

    /**
     * Estado para o painel (aba Saúde, "acesso" em /api/saude) e o MCP (estado_tv, "saude"):
     * {"shell":{"estado":"ok|fora|desligado|desconhecido","foraHaMin":N,"okHaMin":N},
     *  "tunel":{"estado":...,"foraHaMin":N,"nome":"...","erro":"..."},
     *  "acao":"o que fazer (texto curto) ou null", "manutencao":{relatório da última noite}}
     */
    fun json(ctx: Context): JSONObject {
        val app = ctx.applicationContext
        val agora = SystemClock.elapsedRealtime()
        val s = SaudeRegras.jsonItem(shell, agora).apply {
            if (ultimoOkShell > 0) put("okHaMin", (agora - ultimoOkShell) / 60_000)
        }
        val t = SaudeRegras.jsonItem(tunel, agora).apply {
            TunelTailscale.nome(app)?.let { put("nome", it) }
            TunelTailscale.ultimoErro(app)?.let { put("erro", it) }
            if (TunelTailscale.urlLogin != null || TunelTailscale.urlAtivar != null) put("faltaLogin", true)
        }
        val acao = when {
            shell.ok == false -> app.getString(R.string.saude_acao_shell)
            tunel.ok == false -> app.getString(R.string.saude_acao_tunel)
            else -> null
        }
        return JSONObject().put("shell", s).put("tunel", t).put("acao", acao ?: JSONObject.NULL)
            .put("manutencao", ManutencaoNoturna.json(app))
    }
}

/**
 * A regra da saúde, sem Android (teste JVM em SaudeAcessoTest). Tempos em ms do relógio desde o boot, exceto onde diz
 * "parede" (hora do relógio comum, que sobrevive a um reinício do launcher porque vai para as prefs).
 */
object SaudeRegras {
    /**
     * [ok]: true = no ar, false = fora, null = não se aplica (desligado/nunca configurado) ou ainda não sei.
     * [foraDesde]: quando caiu (vale com ok = false). [avisado]: já avisei desta queda.
     */
    data class Item(val ok: Boolean? = null, val foraDesde: Long = 0, val avisado: Boolean = false)

    /** Nova observação. [okAgora] null = não se aplica mais: volta ao estado neutro (e uma queda futura é outra). */
    fun observar(item: Item, okAgora: Boolean?, agora: Long): Item = when (okAgora) {
        null -> Item()
        true -> Item(ok = true)
        false -> if (item.ok == false) item else Item(ok = false, foraDesde = agora)   // continua a mesma queda
    }

    /**
     * Avisa se está fora há [limiteMs] ou mais, ainda não avisou desta queda e o último aviso foi há [intervaloMs] ou mais.
     * O tempo fora só conta desde [acordouEm] (a tela acendeu): o relógio desde o boot conta o sono, e uma queda de antes
     * de dormir não pode virar aviso no instante em que a TV liga, antes de qualquer observação nova.
     */
    fun precisaAvisar(item: Item, agora: Long, limiteMs: Long, ultimoAvisoParede: Long, agoraParede: Long, intervaloMs: Long,
                      acordouEm: Long = 0): Boolean =
        item.ok == false && !item.avisado && agora - maxOf(item.foraDesde, acordouEm) >= limiteMs &&
            (ultimoAvisoParede <= 0 || agoraParede - ultimoAvisoParede >= intervaloMs || agoraParede < ultimoAvisoParede)

    /**
     * Lugar seguro para um aviso: a NOSSA HOME na frente. Qualquer outro app pode ser um filme; e o nosso pacote também
     * tem o player de TV ao vivo, filmes e séries, por isso a classe precisa ser a MainActivity.
     */
    fun lugarSeguro(pacote: String?, classe: String?, nossoPacote: String): Boolean =
        pacote == nossoPacote && classe == "$nossoPacote.MainActivity"

    fun estado(item: Item): String = when (item.ok) { true -> "ok"; false -> "fora"; null -> "desligado" }

    fun jsonItem(item: Item, agora: Long): JSONObject = JSONObject().put("estado", estado(item)).apply {
        if (item.ok == false) put("foraHaMin", (agora - item.foraDesde) / 60_000).put("avisado", item.avisado)
    }
}
