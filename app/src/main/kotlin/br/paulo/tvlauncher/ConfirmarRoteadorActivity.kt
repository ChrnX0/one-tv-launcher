package br.paulo.tvlauncher

import android.app.Activity
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
 * Confirmação NA TV do "Reconectar a TV" pedido pelo painel (a única ação que o launcher faz no roteador UniFi).
 * Mesmo jeito da ConfirmarIaActivity: foco começa em "Cancelar", ◀ ▶ trocam, OK escolhe, Voltar cancela, e sem
 * resposta em [UnifiCliente.ESPERA_CONFIRMAR_MS] a tela fecha e o pedido é cancelado. Quem está com o controle na mão
 * decide; o painel sozinho não derruba o Wi-Fi da TV no meio de um filme.
 */
class ConfirmarRoteadorActivity : Activity() {
    private var respondido = false
    private val ui = Handler(Looper.getMainLooper())

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        if (UnifiCliente.estadoReconectar != UnifiCliente.EstadoReconectar.ESPERANDO_TV) { respondido = true; finish(); return }
        fun botao(t: String, aoClicar: () -> Unit) = Estilo.botao(this, t).apply { setOnClickListener { aoClicar() } }
        val cancelar = botao(getString(R.string.comum_cancelar)) { responder(false) }
        val reconectar = botao(getString(R.string.rt_reconectar)) { responder(true) }
        // Diálogo único do sistema de design (Estilo.dialogo): o mesmo das outras perguntas na TV.
        setContentView(Estilo.dialogo(this, getString(R.string.rt_pedido_painel), getString(R.string.rt_reconectar_wifi),
            getString(R.string.rt_reconectar_det), listOf(cancelar, reconectar)))
        cancelar.requestFocus()   // o padrão é o seguro
        ui.postDelayed({ responder(false) }, UnifiCliente.ESPERA_CONFIRMAR_MS)
    }

    private fun responder(sim: Boolean) {
        if (respondido) return
        respondido = true
        finish()
        UnifiCliente.responderReconectar(applicationContext, sim)
    }

    @Deprecated("API antiga, mas é a que existe em Activity (sem AndroidX)")
    override fun onBackPressed() = responder(false)

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (!respondido && isFinishing) { respondido = true; UnifiCliente.responderReconectar(applicationContext, false) }
    }
}
