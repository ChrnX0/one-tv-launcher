package br.paulo.tvlauncher

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Bundle
import android.os.IBinder
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.family.PinNaTela
import br.paulo.tvlauncher.privilege.DonoEmprestado
import br.paulo.tvlauncher.privilege.RegrasDono
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.settings.Tema
import com.rosan.dhizuku.aidl.IDhizukuRequestPermissionListener
import com.rosan.dhizuku.shared.DhizukuVariables

/**
 * Pedido de um app para usar o Device Owner emprestado (Dhizuku, ver DonoEmprestado). Quem abre é o próprio app, com
 * `Dhizuku.requestPermission()`: manda o uid dele e um binder para a resposta. O uid vem do app e não dá para provar
 * quem abriu a tela, então o que se autoriza é o app MOSTRADO (nome e ícone daquele uid), igual ao Dhizuku oficial.
 *
 * Negar (foco inicial, o seguro), Permitir (só o DevicePolicyManager, filtrado) ou Poder total (aviso no título do
 * PIN: o app passa a rodar como o launcher e ler os dados dele). Os dois pedem o PIN do painel (PinNaTela).
 * A Dhizuku-API 2.7 desiste em 15 s: se o PIN demorar mais, a autorização fica gravada e vale na próxima vez que o app pedir.
 */
class PedidoDhizukuActivity : Activity() {
    private var respondido = false
    private var ouvinte: IDhizukuRequestPermissionListener? = null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        val extras = intent.extras
        val uid = extras?.getInt(DhizukuVariables.PARAM_CLIENT_UID, -1) ?: -1
        val b: IBinder? = extras?.getBinder(DhizukuVariables.PARAM_CLIENT_REQUEST_PERMISSION_BINDER)
        ouvinte = b?.let { IDhizukuRequestPermissionListener.Stub.asInterface(it) }
        val pacotes = if (uid >= 0) packageManager.getPackagesForUid(uid) else null
        val pacote = pacotes?.singleOrNull()
        // uid de sistema (shell 2000 etc.) nunca: autorizar "com.android.shell" daria o dono a todo processo do adb e do
        // shell emprestado (Shizuku). Só apps de verdade (uid >= FIRST_APPLICATION_UID).
        if (uid < android.os.Process.FIRST_APPLICATION_UID) return responder(false)
        if (pacote == null || pacote == packageName || DonoEmprestado.parado(this) != RegrasDono.Parado.NAO) return responder(false)
        if (DonoEmprestado.porUid(this, uid) != null) return responder(true)   // já autorizado: sem tela
        val ai = try { packageManager.getApplicationInfo(pacote, 0) } catch (_: Exception) { return responder(false) }

        val tema = Tema.atual(this)
        val nome = ai.loadLabel(packageManager).toString()
        fun botao(texto: String, aoClicar: () -> Unit) = TextView(this).apply {
            text = texto
            setTextColor(Color.WHITE)
            textSize = 20f
            typeface = Fontes.uiMedia(this@PedidoDhizukuActivity, tema)
            gravity = Gravity.CENTER
            setPaddingRelative(dp(24), dp(14), dp(24), dp(14))
            isFocusable = true
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused),
                    GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x55FFFFFF) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(28).toFloat(); setColor(0x1AFFFFFF) })
            }
            setOnClickListener { aoClicar() }
        }
        fun permitir(total: Boolean) = PinNaTela.pedir(this,
            getString(if (total) R.string.emd_pin_total else R.string.emp_pin_titulo)) {
            if (respondido) return@pedir
            DonoEmprestado.autorizar(this, pacote, uid, total)
            responder(true)
        }
        val negar = botao(getString(R.string.emp_negar)) { responder(false) }
        val lp = { LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(12) } }

        val painel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPaddingRelative(dp(32), dp(28), dp(32), dp(24))
            background = GradientDrawable().apply { cornerRadius = dp(22).toFloat(); setColor(0xF01C2024.toInt()) }
            addView(ImageView(this@PedidoDhizukuActivity).apply { setImageDrawable(ai.loadIcon(packageManager)) },
                LinearLayout.LayoutParams(dp(64), dp(64)).apply { bottomMargin = dp(14) })
            addView(TextView(this@PedidoDhizukuActivity).apply {
                text = getString(R.string.emd_pedido_titulo, nome)
                setTextColor(Color.WHITE); textSize = 24f; gravity = Gravity.CENTER
                typeface = Fontes.uiMedia(this@PedidoDhizukuActivity, tema)
            })
            addView(TextView(this@PedidoDhizukuActivity).apply {
                text = getString(R.string.emd_pedido_texto)
                setTextColor(0xCCFFFFFF.toInt()); textSize = 16f; gravity = Gravity.CENTER
                typeface = Fontes.ui(this@PedidoDhizukuActivity, tema)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(10); bottomMargin = dp(22)
            })
            addView(LinearLayout(this@PedidoDhizukuActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(negar, lp())
                addView(botao(getString(R.string.emp_permitir)) { permitir(false) }, lp())
                addView(botao(getString(R.string.emd_total)) { permitir(true) })
            })
        }
        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            addView(painel, LinearLayout.LayoutParams(dp(700), ViewGroup.LayoutParams.WRAP_CONTENT))
        })
        negar.requestFocus()
    }

    private fun responder(permitido: Boolean) {
        if (!respondido) {
            respondido = true
            try {
                ouvinte?.onRequestPermission(if (permitido) PackageManager.PERMISSION_GRANTED else PackageManager.PERMISSION_DENIED)
            } catch (_: Throwable) {}
        }
        if (!isFinishing) finish()
    }

    @Deprecated("API antiga, mas é a que existe em Activity (sem AndroidX)")
    override fun onBackPressed() = responder(false)

    // Mesma regra do PedidoShizukuActivity: o protetor de tela chama onStop sem ninguém escolher; só negar ao destruir.
    override fun onDestroy() {
        super.onDestroy()
        if (!respondido && isFinishing) responder(false)
    }
}
