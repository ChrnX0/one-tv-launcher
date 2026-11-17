package tv.sofaly.exemplo.contagem

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.DatePicker
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate

/** Tela simples, feita para o controle remoto: nome, data e "Salvar". Letra grande, como pede a Sofaly. */
class EscolherDataActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val atual = Preferencias.data(this) ?: LocalDate.now().plusDays(30)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; setPadding(96, 48, 96, 48)
        }
        raiz.addView(TextView(this).apply { text = getString(R.string.nome); textSize = 32f })
        val nome = EditText(this).apply { setText(Preferencias.titulo(this@EscolherDataActivity)); textSize = 24f; setSingleLine() }
        raiz.addView(nome, LinearLayout.LayoutParams(800, -2))
        val data = DatePicker(this).apply {
            calendarViewShown = false; spinnersShown = true
            updateDate(atual.year, atual.monthValue - 1, atual.dayOfMonth)
        }
        raiz.addView(data)
        val salvar = Button(this).apply {
            text = getString(R.string.salvar); textSize = 24f
            setOnClickListener {
                Preferencias.salvar(this@EscolherDataActivity, LocalDate.of(data.year, data.month + 1, data.dayOfMonth), nome.text.toString())
                finish()   // a tela inicial relê o cartão quando a pessoa volta
            }
        }
        raiz.addView(salvar)
        setContentView(raiz)
        salvar.requestFocus()
    }
}
