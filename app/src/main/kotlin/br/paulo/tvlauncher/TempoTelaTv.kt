package br.paulo.tvlauncher

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import java.util.Calendar

/**
 * Tempo de tela por dia (ideia 33; regras em [TempoTela], Familia.kt). Com a chave [Extras.Chave.TEMPO_TELA] ligada, um
 * relógio de 1 min (na thread principal do processo do serviço de acessibilidade) soma o tempo com a tela acesa e um app
 * que conta na frente ([JanelaNaFrente]). Faltando 5 min, avisa; acabou, o app da frente volta para a HOME com aviso, e
 * cada app que abrir também (menos os liberados da hora de dormir). Quem chama: o serviço de acessibilidade, a cada janela.
 */
object TempoTelaTv {
    private const val PASSO_MS = 60_000L
    private val principal by lazy { Handler(Looper.getMainLooper()) }
    @Volatile private var armado = false
    @Volatile private var ultimoTique = 0L
    @Volatile private var ultimoAviso = 0L

    private fun hoje(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 10000 + (it.get(Calendar.MONTH) + 1) * 100 + it.get(Calendar.DAY_OF_MONTH) }

    fun cota(ctx: Context) = Extras.numero(ctx, TempoTela.CHAVE_COTA, TempoTela.PADRAO_COTA).coerceIn(TempoTela.MIN_COTA, TempoTela.MAX_COTA)
    fun definirCota(ctx: Context, min: Int) = Extras.definirNumero(ctx, TempoTela.CHAVE_COTA, min.coerceIn(TempoTela.MIN_COTA, TempoTela.MAX_COTA))
    private fun estado(ctx: Context) = TempoTela.ler(Extras.texto(ctx, TempoTela.CHAVE_ESTADO))
    private fun gravar(ctx: Context, e: TempoTela.Estado) = Extras.definirTexto(ctx, TempoTela.CHAVE_ESTADO, TempoTela.escrever(e))

    fun restanteMs(ctx: Context): Long = TempoTela.restanteMs(estado(ctx), hoje(), cota(ctx))

    /** Mais [TempoTela.EXTRA_MIN] minutos só hoje (a tela pede o PIN antes). */
    fun darMais(ctx: Context) = gravar(ctx, TempoTela.darMais(estado(ctx), hoje()))

    private fun valendo(ctx: Context) = Extras.ligado(ctx, Extras.Chave.TEMPO_TELA)

    /** Uma janela de [pacote] apareceu: arma o relógio e, se a cota de hoje acabou, fecha o app. */
    fun janela(servico: AccessibilityService, pacote: CharSequence?) {
        if (!valendo(servico)) return
        armar(servico)
        fecharSeAcabou(servico, pacote?.toString())
    }

    private fun fecharSeAcabou(servico: AccessibilityService, pacote: String?) {
        if (!TempoTela.conta(pacote, HoraDormirTv.permitidos(servico))) return
        if (!TempoTela.acabou(estado(servico), hoje(), cota(servico))) return
        servico.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        val agora = SystemClock.elapsedRealtime()
        if (agora - ultimoAviso > 10_000) {
            ultimoAviso = agora
            AvisoTv.mostrar(servico, servico.getString(R.string.tela_titulo), servico.getString(R.string.tela_acabou), 8,
                som = false, guardarSeApagada = false)
        }
    }

    private fun armar(ctx: Context) {
        if (armado) return
        armado = true
        ultimoTique = SystemClock.elapsedRealtime()
        val app = ctx.applicationContext
        principal.postDelayed(object : Runnable {
            override fun run() {
                if (!valendo(app)) { armado = false; return }
                tique(app)
                principal.postDelayed(this, PASSO_MS)
            }
        }, PASSO_MS)
    }

    private fun tique(app: Context) {
        val agora = SystemClock.elapsedRealtime()
        val passo = agora - ultimoTique
        ultimoTique = agora
        val acesa = app.getSystemService(PowerManager::class.java)?.isInteractive ?: false
        val pacote = JanelaNaFrente.pacote
        if (!acesa || !TempoTela.conta(pacote, HoraDormirTv.permitidos(app))) return
        val antes = estado(app)
        val restAntes = TempoTela.restanteMs(antes, hoje(), cota(app))
        val depois = TempoTela.somar(antes, hoje(), passo)
        gravar(app, depois)
        val restDepois = TempoTela.restanteMs(depois, hoje(), cota(app))
        if (TempoTela.cruzouAviso(restAntes, restDepois))
            AvisoTv.mostrar(app, app.getString(R.string.tela_titulo), app.getString(R.string.tela_5min), 8, som = false, guardarSeApagada = false)
        if (restDepois == 0L) AutorizaShizukuService.instancia?.let { fecharSeAcabou(it, pacote) }
    }
}
