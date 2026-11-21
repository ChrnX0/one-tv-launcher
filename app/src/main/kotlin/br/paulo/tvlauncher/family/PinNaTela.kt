package br.paulo.tvlauncher.family

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.widget.Toast
import br.paulo.tvlauncher.CaixaTv
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.panel.PinSeguro
import br.paulo.tvlauncher.panel.conferirPin

/**
 * Pede o PIN do painel numa tela qualquer e roda [aoAcertar] se acertar (15/11/2026, pedidos de poder emprestado:
 * PedidoShizukuActivity e PedidoDhizukuActivity). A espera crescente é a mesma das Configurações (TentativasPin, prefs
 * "config_pin"): errar aqui conta lá. A conferência (PBKDF2, lenta) roda fora da thread da tela.
 * Sem PIN definido não dá para liberar poder: avisa onde definir e não roda nada.
 */
object PinNaTela {
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(Prefs.CONFIG_PIN, Context.MODE_PRIVATE)
    private fun ler(ctx: Context) = prefs(ctx).let { TentativasPin.Estado(it.getInt("erros", 0), it.getLong("ate", 0), it.getLong("ultimo", 0)) }
    private fun gravar(ctx: Context, e: TentativasPin.Estado) =
        prefs(ctx).edit().putInt("erros", e.erros).putLong("ate", e.bloqueadoAte).putLong("ultimo", e.ultimoErro).apply()

    /** [aoCancelar]: o diálogo fechou sem acertar (Cancelar, BACK, PIN errado ou sem PIN definido). */
    fun pedir(tela: Activity, titulo: String, aoCancelar: (() -> Unit)? = null, aoAcertar: () -> Unit) {
        var acertou = false
        fun aviso(t: String) = Toast.makeText(tela, t, Toast.LENGTH_LONG).show()
        if (!PainelArmazem.temPin(tela)) { aviso(tela.getString(R.string.emp_sem_pin)); aoCancelar?.invoke(); return }
        val espera = TentativasPin.espera(ler(tela), System.currentTimeMillis())
        if (espera > 0) { aviso(tela.getString(R.string.player_pin_espere, TentativasPin.minutos(espera))); aoCancelar?.invoke(); return }
        val dp = { v: Int -> TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), tela.resources.displayMetrics).toInt() }
        val campo = android.widget.EditText(tela).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(PinSeguro.MAX_DIGITOS))
            setSingleLine()
            // O título é o que vai ser liberado (ex.: o nome do add-on); a dica diz o que digitar (visto na Sala em 19/11/2026).
            hint = tela.getString(R.string.player_pin_titulo)
        }
        fun conferir(pin: String) = Fundo.rodar("pin-na-tela") {
            val certo = PainelArmazem.conferirPin(tela, pin)
            tela.runOnUiThread {
                if (tela.isDestroyed || tela.isFinishing) return@runOnUiThread
                if (certo) { acertou = true; gravar(tela, TentativasPin.acertou()); aoAcertar() }
                else {
                    val e = TentativasPin.errou(ler(tela), System.currentTimeMillis())
                    gravar(tela, e)
                    val falta = TentativasPin.espera(e, System.currentTimeMillis())
                    aviso(if (falta > 0) tela.getString(R.string.player_pin_espere, TentativasPin.minutos(falta)) else tela.getString(R.string.player_pin_errado))
                    aoCancelar?.invoke()
                }
            }
        }
        val d = CaixaTv(tela)
            .setTitle(titulo)
            .setView(android.widget.FrameLayout(tela).apply { setPaddingRelative(dp(20), dp(8), dp(20), 0); addView(campo) })
            .setPositiveButton("OK") { _, _ -> conferir(campo.text.toString()) }
            .setNegativeButton(tela.getString(R.string.cl_cancelar)) { _, _ -> aoCancelar?.invoke() }
            .create()
        d.setOnCancelListener { if (!acertou) aoCancelar?.invoke() }   // BACK
        campo.setOnEditorActionListener { _, acao, _ ->
            if (acao == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || acao == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                d.dismiss(); conferir(campo.text.toString()); true
            } else false
        }
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }
}
