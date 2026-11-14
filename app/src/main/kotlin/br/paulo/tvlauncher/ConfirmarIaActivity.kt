package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A confirmação NA TV de um pedido de ações do catálogo (ComandosIa; hoje pelo adb, depois pelo MCP). Nada roda sem
 * alguém apertar OK aqui, no controle de verdade.
 *
 * O foco começa em "Cancelar" (o seguro), ◀ ▶ trocam, OK escolhe. Voltar = cancelar. Sem resposta em 60 s, a tela
 * fecha e o pedido é cancelado.
 */
class ConfirmarIaActivity : Activity() {
    companion object { const val EXTRA_ID = "pedido_ia" }

    private var id: String? = null
    private var respondido = false
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
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
        id = i.getStringExtra(EXTRA_ID)
        val p = PedidoIa.estado(id)
        val acoes = p?.plano?.acoes.orEmpty()
        if (p == null || p.situacao != PedidoIa.Situacao.ESPERANDO || acoes.isEmpty()) { respondido = true; finish(); return }

        fun botao(t: String, aoClicar: () -> Unit) = Estilo.botao(this, t).apply { setOnClickListener { aoClicar() } }
        val cancelar = botao(getString(R.string.comum_cancelar)) { responder(false) }
        val executar = botao(getString(R.string.ci_executar)) { responder(true) }
        // Diálogo único do sistema de design (Estilo.dialogo).
        setContentView(Estilo.dialogo(this, getString(R.string.ci_pedido_de, p.origem), "“${p.frase}”",
            getString(R.string.ci_ia_quer) + "\n" + acoes.joinToString("\n") { "• ${it.descricao}" }, listOf(cancelar, executar)))
        cancelar.requestFocus()   // o padrão é o seguro
        // Fecha junto com o prazo do PedidoIa (que marca EXPIRADO): um OK atrasado não executa nada velho.
        ui.postDelayed({ if (!respondido) { respondido = true; finish() } }, PedidoIa.ESPERA_CONFIRMAR_MS)
    }

    private fun responder(sim: Boolean) {
        if (respondido) return
        respondido = true
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
