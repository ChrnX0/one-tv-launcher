package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import br.paulo.tvlauncher.mcp.PedidoIa
import br.paulo.tvlauncher.mcp.PedidoPendente

/**
 * A confirmação NA TV de um pedido de ações do catálogo (ComandosIa; hoje pelo adb, depois pelo MCP). Nada roda sem
 * alguém apertar OK aqui, no controle de verdade.
 *
 * O foco começa em "Cancelar" (o seguro), ◀ ▶ trocam, OK escolhe. Voltar = cancelar. Sem resposta em 3 min, a tela
 * fecha e o pedido vai para as notificações (PedidoPendente). Aberta pela notificação ([PedidoPendente.EXTRA_TARDIO]),
 * pergunta "Aceitar este pedido?" com Não/Sim; o Sim roda pelo PedidoIa.aceitarDepois.
 */
class ConfirmarIaActivity : Activity() {
    companion object {
        const val EXTRA_ID = "pedido_ia"

        /**
         * De onde veio o pedido, em palavras da família ("Pedido de Claude", "Pedido de painel"). Antes o título dizia
         * "Pedido pelo painel (mcp)" para qualquer origem (teste na Sala, 15/11/2026).
         */
        fun nomeOrigem(ctx: android.content.Context, origem: String): String = when (origem) {
            "mcp", "claude" -> ctx.getString(R.string.ci_origem_claude)
            "painel" -> ctx.getString(R.string.ci_origem_painel)
            "link", "cena" -> ctx.getString(R.string.ci_origem_link)
            "celular", "app" -> ctx.getString(R.string.ci_origem_celular)
            "rotina" -> ctx.getString(R.string.ci_origem_rotina)
            else -> origem
        }
    }

    private var id: String? = null
    private var respondido = false
    /** Pedido vindo da notificação: (frase, origem, JSON das ações). */
    private var tardio: Triple<String, String, String>? = null
    private val ui = Handler(Looper.getMainLooper())

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        // A pergunta espera até 3 min: sem isto o protetor de tela pode voltar por cima dela antes de alguém responder.
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        montar(intent)
    }

    // Um pedido novo enquanto esta tela está aberta: troca o conteúdo (o anterior já foi cancelado em PedidoIa).
    override fun onNewIntent(novo: Intent) {
        super.onNewIntent(novo)
        respondido = false
        ui.removeCallbacksAndMessages(null)
        montar(novo)
    }

    private fun montar(i: Intent) {
        if (i.getBooleanExtra(PedidoPendente.EXTRA_TARDIO, false)) { montarTardio(i); return }
        tardio = null
        id = i.getStringExtra(EXTRA_ID)
        val p = PedidoIa.estado(id)
        val acoes = p?.plano?.acoes.orEmpty()
        if (p == null || p.situacao != PedidoIa.Situacao.ESPERANDO || acoes.isEmpty()) { respondido = true; finish(); return }

        fun botao(t: String, aoClicar: () -> Unit) = Estilo.botao(this, t).apply { setOnClickListener { aoClicar() } }
        val cancelar = botao(getString(R.string.comum_cancelar)) { responder(false) }
        val executar = botao(getString(R.string.ci_executar)) { responder(true) }
        // Diálogo único do sistema de design (Estilo.dialogo).
        setContentView(Estilo.dialogo(this, getString(R.string.ci_pedido_de, nomeOrigem(this, p.origem)), "“${p.frase}”",
            getString(R.string.ci_ia_quer) + "\n" + acoes.joinToString("\n") { "• ${it.descricao}" }, listOf(cancelar, executar)))
        cancelar.requestFocus()   // o padrão é o seguro
        // Fecha junto com o prazo do PedidoIa (que marca EXPIRADO): um OK atrasado não executa nada velho.
        ui.postDelayed({ if (!respondido) { respondido = true; finish() } }, PedidoIa.ESPERA_CONFIRMAR_MS)
    }

    private fun montarTardio(i: Intent) {
        id = null
        val frase = i.getStringExtra(PedidoPendente.EXTRA_FRASE).orEmpty()
        val origem = i.getStringExtra(PedidoPendente.EXTRA_ORIGEM).orEmpty()
        val acoes = i.getStringExtra(PedidoPendente.EXTRA_ACOES).orEmpty()
        val descricoes = i.getStringArrayExtra(PedidoPendente.EXTRA_DESCRICOES).orEmpty()
        PedidoPendente.apagar(this, i.getIntExtra(PedidoPendente.EXTRA_NOTIF, 0))
        if (acoes.isEmpty() || descricoes.isEmpty()) { respondido = true; finish(); return }
        tardio = Triple(frase, origem, acoes)
        fun botao(t: String, aoClicar: () -> Unit) = Estilo.botao(this, t).apply { setOnClickListener { aoClicar() } }
        val nao = botao(getString(R.string.pp_nao)) { responder(false) }
        val sim = botao(getString(R.string.pp_sim)) { responder(true) }
        setContentView(Estilo.dialogo(this, getString(R.string.ci_pedido_de, nomeOrigem(this, origem)), getString(R.string.pp_aceitar),
            "“$frase”\n" + descricoes.joinToString("\n") { "• $it" }, listOf(nao, sim)))
        nao.requestFocus()   // o padrão é o seguro
    }

    private fun responder(sim: Boolean) {
        if (respondido) return
        respondido = true
        tardio?.let { (frase, origem, acoes) ->
            finish()
            if (sim) { val app = applicationContext; Handler(Looper.getMainLooper()).post { PedidoIa.aceitarDepois(app, frase, origem, acoes) } }
            return
        }
        val meu = id
        // Fecha ANTES de executar: assim o app aberto pela ação fica por cima, e não esta tela.
        finish()
        // Handler NOVO, não o `ui`: o onDestroy limpa o `ui`, e a resposta não pode sumir junto.
        val app = applicationContext
        if (meu != null) Handler(Looper.getMainLooper()).post { PedidoIa.responder(app, meu, sim) }
    }

    @Deprecated("API antiga, mas é a que existe em Activity (sem AndroidX)")
    override fun onBackPressed() = responder(false)

    override fun onDestroy() {
        super.onDestroy()
        if (!respondido && isFinishing) id?.let { PedidoIa.responder(applicationContext, it, false) }
        ui.removeCallbacksAndMessages(null)
    }
}
