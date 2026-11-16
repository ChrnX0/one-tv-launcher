package tv.sofaly.extras

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A única tela do plugin: diz o estado em letra grande e o próximo passo. Configurar (quem recebe, consentimento da
 * pessoa) é sempre no launcher, em Cuidar de longe; aqui não se escolhe nada.
 */
class ExtrasActivity : Activity() {
    private lateinit var texto: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val d = resources.displayMetrics.density
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF101418.toInt())
            setPadding((64 * d).toInt(), (40 * d).toInt(), (64 * d).toInt(), (40 * d).toInt())
        }
        raiz.addView(TextView(this).apply { text = getString(R.string.nome); textSize = 34f; setTextColor(0xFFFFFFFF.toInt()) })
        texto = TextView(this).apply { textSize = 22f; setTextColor(0xFFDDE3E8.toInt()); setLineSpacing(0f, 1.3f); isFocusable = true }
        raiz.addView(texto, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (20 * d).toInt() })
        setContentView(raiz)
        Launcher.agendar(this)
    }

    override fun onResume() {
        super.onResume()
        texto.text = getString(R.string.carregando)
        Thread {
            val e = Launcher.estado(applicationContext)
            val msg = when {
                !e.instalado -> getString(R.string.sem_launcher)
                !e.autorizado -> getString(R.string.sem_ok)
                !e.naoLigouAtivo -> getString(R.string.sem_aviso)
                else -> getString(R.string.tudo_certo, e.nome)
            }
            runOnUiThread { if (!isFinishing) texto.text = msg }
        }.start()
    }
}
