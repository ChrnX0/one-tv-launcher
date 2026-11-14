package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * A parte Android das Confirmações (a lógica pura está em Confirmacoes.kt).
 *
 * As regras ficam na preferência "confirmacoes", FORA do backup de propósito (BackupConfig.ARQUIVOS): restaurar um
 * backup não pode liberar nada sem o PIN.
 */
object ConfirmacoesTv {
    private const val TAG = "TvLauncher.Confirma"
    private const val PREFS = "confirmacoes"
    private const val CHAVE = "regras"
    const val ESPERA_MS = 60_000L

    private fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * As regras de agora. Na primeira leitura grava as iniciais: instalação nova (o pacote nunca foi atualizado) começa
     * protegida; TV que já tinha o launcher (a Sala, atualizada por cima) começa com "Liberar tudo".
     */
    @Synchronized fun regras(ctx: Context): Confirmacoes.Regras {
        Confirmacoes.deJson(prefs(ctx).getString(CHAVE, null))?.let { return it }
        val nova = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            pi.firstInstallTime == pi.lastUpdateTime
        } catch (e: Exception) { true }   // na dúvida, o lado protegido
        val r = Confirmacoes.iniciais(nova)
        prefs(ctx).edit().putString(CHAVE, Confirmacoes.paraJson(r)).apply()
        Log.i(TAG, "regras iniciais: liberarTudo=${r.liberarTudo} (instalação nova=$nova)")
        return r
    }

    /** Grava. Quem chama confere o PIN antes, quando Confirmacoes.pedePin disser que precisa. */
    @Synchronized fun definir(ctx: Context, r: Confirmacoes.Regras) {
        prefs(ctx).edit().putString(CHAVE, Confirmacoes.paraJson(r)).apply()
    }

    fun modo(ctx: Context, a: Confirmacoes.Acao) = Confirmacoes.modo(regras(ctx), a)

    // ---------------------------------------------------------------- perguntar na TV

    private class Pergunta(val acao: Confirmacoes.Acao, val oQue: String, val quem: String) {
        val pronto = CountDownLatch(1)
        @Volatile var sim = false
    }
    @Volatile private var aberta: Pergunta? = null

    /**
     * A ação [a] pode seguir? Permitir = sim na hora; Bloqueado = não; Perguntar = mostra a pergunta na TV e espera até
     * 60 s (sem resposta = não). BLOQUEIA a thread: nunca chamar da thread da tela. [oQue] e [quem] aparecem na TV.
     * O resultado vai para o registro do painel.
     */
    fun permitir(ctx: Context, a: Confirmacoes.Acao, oQue: String, quem: String): Boolean {
        check(Looper.myLooper() != Looper.getMainLooper()) { "ConfirmacoesTv.permitir na thread da tela" }
        val app = ctx.applicationContext
        val sim = when (modo(app, a)) {
            Confirmacoes.Modo.PERMITIR -> return true
            Confirmacoes.Modo.BLOQUEAR -> false.also { registrar(app, quem, oQue, "bloqueado nas Confirmações") }
            Confirmacoes.Modo.PERGUNTAR -> perguntar(app, a, oQue, quem)
        }
        return sim
    }

    private fun perguntar(app: Context, a: Confirmacoes.Acao, oQue: String, quem: String): Boolean {
        val p = Pergunta(a, if (oQue.length <= 300) oQue else oQue.take(290) + " … [cortado]", quem.take(80))
        synchronized(this) {
            if (aberta != null) { registrar(app, quem, oQue, "negado: outra pergunta na tela"); return false }
            aberta = p
        }
        try {
            app.startActivity(Intent(app, PerguntaTvActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            p.pronto.await(ESPERA_MS + 2_000, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            Log.w(TAG, "pergunta falhou: ${e.javaClass.simpleName}")
        } finally {
            synchronized(this) { if (aberta === p) aberta = null }
        }
        registrar(app, quem, oQue, if (p.sim) "permitido na TV" else "negado na TV (ou sem resposta)")
        return p.sim
    }

    private fun registrar(app: Context, quem: String, oQue: String, resultado: String) {
        try { PainelArmazem.registrar(app, quem, "confirmação: $oQue", resultado) } catch (_: Exception) {}
    }

    /** Para a PerguntaTvActivity. O título sai no idioma da TV (os textos de Confirmacoes.kt ficam para a IA). */
    internal fun atual(): Triple<String, String, String>? = aberta?.let { Triple(txt(tituloRes(it.acao)), it.oQue, it.quem) }

    /** Nome, explicação e modo de cada linha das Confirmações, nos recursos de texto (inglês padrão, pt traduzido). */
    fun tituloRes(a: Confirmacoes.Acao): Int = when (a) {
        Confirmacoes.Acao.MCP_CLIENTE -> R.string.cl_acao_mcp_cliente
        Confirmacoes.Acao.MCP_ACOES -> R.string.cl_acao_mcp_acoes
        Confirmacoes.Acao.MCP_SHELL -> R.string.cl_acao_mcp_shell
        Confirmacoes.Acao.INSTALAR -> R.string.cl_acao_instalar
        Confirmacoes.Acao.REMOVER -> R.string.cl_acao_remover
        Confirmacoes.Acao.PERMISSOES -> R.string.cl_acao_permissoes
        Confirmacoes.Acao.ABRIR_FORA -> R.string.cl_acao_abrir_fora
        Confirmacoes.Acao.ATUALIZACAO -> R.string.cl_acao_atualizacao
    }

    fun explicacaoRes(a: Confirmacoes.Acao): Int = when (a) {
        Confirmacoes.Acao.MCP_CLIENTE -> R.string.cl_acao_mcp_cliente_det
        Confirmacoes.Acao.MCP_ACOES -> R.string.cl_acao_mcp_acoes_det
        Confirmacoes.Acao.MCP_SHELL -> R.string.cl_acao_mcp_shell_det
        Confirmacoes.Acao.INSTALAR -> R.string.cl_acao_instalar_det
        Confirmacoes.Acao.REMOVER -> R.string.cl_acao_remover_det
        Confirmacoes.Acao.PERMISSOES -> R.string.cl_acao_permissoes_det
        Confirmacoes.Acao.ABRIR_FORA -> R.string.cl_acao_abrir_fora_det
        Confirmacoes.Acao.ATUALIZACAO -> R.string.cl_acao_atualizacao_det
    }

    fun modoRes(m: Confirmacoes.Modo): Int = when (m) {
        Confirmacoes.Modo.PERGUNTAR -> R.string.cl_modo_perguntar
        Confirmacoes.Modo.PERMITIR -> R.string.cl_modo_permitir
        Confirmacoes.Modo.BLOQUEAR -> R.string.cl_modo_bloquear
    }
    internal fun responder(sim: Boolean) {
        val p = aberta ?: return
        p.sim = sim
        p.pronto.countDown()
    }
}

/**
 * A pergunta na TV (Confirmações). Mesmo jeito da ConfirmarIaActivity: foco começa em
 * "Negar", ◀ ▶ trocam, OK escolhe, VOLTAR nega, 60 s sem resposta nega.
 */
class PerguntaTvActivity : Activity() {
    private var respondido = false
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val (titulo, oQue, quem) = ConfirmacoesTv.atual() ?: run { respondido = true; finish(); return }
        fun botao(t: String, aoClicar: () -> Unit) = Estilo.botao(this, t).apply { setOnClickListener { aoClicar() } }
        val negar = botao(getString(R.string.cf_negar)) { responder(false) }
        val permitir = botao(getString(R.string.cf_permitir)) { responder(true) }
        // Diálogo único do sistema de design (Estilo.dialogo).
        setContentView(Estilo.dialogo(this, titulo, oQue,
            getString(R.string.cf_pedido_por, quem), listOf(negar, permitir)))
        negar.requestFocus()   // o padrão é o seguro
        ui.postDelayed({ responder(false) }, ConfirmacoesTv.ESPERA_MS)
    }

    private fun responder(sim: Boolean) {
        if (respondido) return
        respondido = true
        finish()   // fecha antes: o que a ação abrir fica por cima
        ConfirmacoesTv.responder(sim)
    }

    @Deprecated("API antiga, mas é a que existe em Activity (sem AndroidX)")
    override fun onBackPressed() = responder(false)

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (!respondido) { respondido = true; ConfirmacoesTv.responder(false) }
    }
}
