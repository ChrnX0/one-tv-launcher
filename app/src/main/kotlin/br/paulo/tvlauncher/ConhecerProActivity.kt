package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout

/**
 * "Conhecer o Pro" (degrau 1, venda sem atrito, 15/11/2026): os três planos lado a lado e um QR que leva o celular à
 * página dos planos, onde a compra vai acontecer (sem digitar nada na TV). Enquanto [Planos.VENDA_ATIVA] = false, a
 * tela só aparece pelo adb (EXTRAS --es o planos), para conferir o visual; nada é cobrado nem bloqueado.
 */
class ConhecerProActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.pl_titulo)))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.pl_texto)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP }
        val atual = Planos.atual(this)
        for ((plano, nome, itens) in listOf(
            Triple(Planos.Plano.BASICO, R.string.pl_basico, R.string.pl_basico_itens),
            Triple(Planos.Plano.PRO, R.string.pl_pro, R.string.pl_pro_itens),
            Triple(Planos.Plano.ULTRA, R.string.pl_ultra, R.string.pl_ultra_itens),
        )) {
            linha.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(14), dp(16), dp(14))
                background = Estilo.fundo(context, if (plano == atual) 0x33FFFFFF else 0x1AFFFFFF, Estilo.RAIO, Estilo.VIDRO_BORDA)
                addView(Estilo.texto(context, Estilo.Papel.TITULO, getString(nome)).apply { textSize = 22f })
                addView(Estilo.texto(context, Estilo.Papel.CORPO, getString(itens)).apply { setLineSpacing(0f, 1.3f) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
        }
        linha.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            isFocusable = true   // a tela precisa de um foco para o VOLTAR e o controle andarem
            addView(ImageView(context).apply { setImageBitmap(runCatching { QrCodigo.bitmap(Planos.PAGINA, dp(180)) }.getOrNull()) },
                LinearLayout.LayoutParams(dp(180), dp(180)))
            addView(Estilo.texto(context, Estilo.Papel.LEGENDA, getString(R.string.pl_qr)).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(dp(200), -2).apply { topMargin = dp(8) })
        }, LinearLayout.LayoutParams(-2, -2))
        raiz.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(20) })
        setContentView(raiz)
        linha.getChildAt(3)?.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
