package br.paulo.tvlauncher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.privilege.Confirmacoes
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
     * As regras de agora. Sem regras gravadas (primeira leitura, ou prefs perdidas/estragadas), começa PROTEGIDA.
     * Antes, "o pacote já foi atualizado" dava "Liberar tudo" (para a Sala, de 2026); mas toda TV se atualiza sozinha,
     * então uma instalação da Play que se atualizasse antes da 1ª leitura, ou prefs estragadas, liberavam shell e
     * instalação sem perguntar. As TVs antigas já têm as regras gravadas desde então: não mudam.
     */
    @Synchronized fun regras(ctx: Context): Confirmacoes.Regras {
        Confirmacoes.deJson(prefs(ctx).getString(CHAVE, null))?.let { return it }
        val nova = true
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

    private class Pergunta(val titulo: String, val oQue: String, val quem: String) {
        val id = proximoId.incrementAndGet()
        val pronto = CountDownLatch(1)
        @Volatile var sim = false
    }
    private val proximoId = java.util.concurrent.atomic.AtomicLong()
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

    private fun perguntar(app: Context, a: Confirmacoes.Acao, oQue: String, quem: String): Boolean =
        perguntarSempre(app, app.getString(tituloRes(a)), oQue, quem)

    /**
     * Pergunta na TV sem passar pelas regras (nem "Liberar tudo" responde por ela): para o que SEMPRE precisa de
     * alguém na frente da TV, como um computador novo pedindo os arquivos (MtpNaRede). Bloqueia até 60 s.
     */
    fun perguntarSempre(ctx: Context, titulo: String, oQue: String, quem: String): Boolean {
        val app = ctx.applicationContext
        val p = Pergunta(titulo, if (oQue.length <= 300) oQue else oQue.take(290) + " … [cortado]", quem.take(80))
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
    internal fun atual(): Triple<String, String, String>? = aberta?.let { Triple(it.titulo, it.oQue, it.quem) }

    /** O número da pergunta aberta: a tela responde só a ELA (uma tela velha não responde pela pergunta seguinte). */
    internal fun atualId(): Long? = aberta?.id

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
    internal fun responder(id: Long, sim: Boolean) {
        val p = aberta ?: return
        if (p.id != id) return
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
    private var pergunta = 0L
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val (titulo, oQue, quem) = ConfirmacoesTv.atual() ?: run { respondido = true; finish(); return }
        pergunta = ConfirmacoesTv.atualId() ?: 0L
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
        ConfirmacoesTv.responder(pergunta, sim)
    }

    @Deprecated("API antiga, mas é a que existe em Activity (sem AndroidX)")
    override fun onBackPressed() = responder(false)

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (!respondido) { respondido = true; ConfirmacoesTv.responder(pergunta, false) }
    }
}
