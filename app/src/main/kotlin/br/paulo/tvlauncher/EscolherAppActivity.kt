package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * A lista que aparece ao segurar 3 s um botão de app do controle (ver BotoesControle).
 * OK num app: salva para aquele botão e já abre o app. Voltar: sai sem mudar nada.
 */
class EscolherAppActivity : Activity() {
    companion object { const val EXTRA_TECLA = "tecla" }

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    override fun onCreate(estado: Bundle?) {
        super.onCreate(estado)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val botao = BotoesControle.botao(intent.getIntExtra(EXTRA_TECLA, -1)) ?: return finish()
        val atual = BotoesControle.appDo(this, botao)

        // Apps que aparecem no menu de apps da TV (os mesmos que o launcher mostra), em ordem alfabética.
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .distinctBy { it.activityInfo.packageName }
            .filter { it.activityInfo.packageName != packageName }
            .sortedBy { it.loadLabel(pm).toString().lowercase() }

        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(dp(8), 0, dp(8), dp(8)) }
        var focoInicial: View? = null
        for (app in apps) {
            val pacote = app.activityInfo.packageName
            // Linha padrão do sistema de design: superfície clara; com foco vira pílula branca e o texto escurece.
            val nome = Estilo.texto(this, Estilo.Papel.CORPO, app.loadLabel(pm).toString() + if (pacote == atual) getString(R.string.esc_atual) else "")
            val linha = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(10), dp(16), dp(10))
                isFocusable = true
                addView(ImageView(this@EscolherAppActivity).apply { setImageDrawable(app.loadIcon(pm)) },
                    LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginEnd = dp(16) })
                addView(nome)
                Estilo.focoLinha(this, nome to Estilo.Papel.CORPO)
                setOnClickListener {
                    BotoesControle.salvar(this@EscolherAppActivity, botao, pacote)
                    BotoesControle.abrirApp(this@EscolherAppActivity, pacote)
                    finish()
                }
            }
            if (pacote == atual) focoInicial = linha
            lista.addView(linha, Estilo.lpLinha(this))
        }

        val painel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(20))
            // Janela por cima do que estiver na tela: o mesmo vidro escuro das pílulas e avisos (sistema de design).
            background = Estilo.fundo(context, 0xF20B0F17.toInt(), Estilo.RAIO, Estilo.VIDRO_BORDA)
            addView(Estilo.texto(this@EscolherAppActivity, Estilo.Papel.TITULO, getString(R.string.esc_botao, botao.nome)),
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(12) })
            addView(ScrollView(this@EscolherAppActivity).apply { clipChildren = false; isVerticalScrollBarEnabled = false; addView(lista) })
        }

        setContentView(LinearLayout(this).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(0xCC000000.toInt())
            addView(painel, LinearLayout.LayoutParams(dp(520), ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(40); bottomMargin = dp(40)
            })
        })
        (focoInicial ?: lista.getChildAt(0))?.requestFocus()
    }
}
