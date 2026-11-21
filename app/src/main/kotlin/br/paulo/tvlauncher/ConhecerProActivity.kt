package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.ImageView
import android.widget.LinearLayout
import br.paulo.tvlauncher.panel.QrCodigo
import br.paulo.tvlauncher.settings.CobrancaPlay
import br.paulo.tvlauncher.settings.Planos

/**
 * "Conhecer o Pro" (degrau 1, venda sem atrito, 15/11/2026): os três planos lado a lado e um QR que leva o celular à
 * página dos planos (APK do site, sem cobrança). No build da Play ([Planos.VENDA_ATIVA]) os cartões Pro e Ultra abrem
 * a compra da própria Play (CobrancaPlay.kt) e o QR some.
 */
class ConhecerProActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
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
            val cartao = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPaddingRelative(dp(16), dp(14), dp(16), dp(14))
                background = Estilo.fundo(context, if (plano == atual) 0x33FFFFFF else 0x1AFFFFFF, Estilo.RAIO, Estilo.VIDRO_BORDA)
                addView(Estilo.texto(context, Estilo.Papel.TITULO, getString(nome)).apply { textSize = 22f })
                addView(Estilo.texto(context, Estilo.Papel.CORPO, getString(itens)).apply { setLineSpacing(0f, 1.3f) },
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }
            // Build da Play: Pro e Ultra viram botões que abrem a compra da própria Play, com o preço que ela manda.
            val produto = when (plano) { Planos.Plano.PRO -> CobrancaPlay.PRODUTO_PRO; Planos.Plano.ULTRA -> CobrancaPlay.PRODUTO_ULTRA; else -> null }
            if (Planos.VENDA_ATIVA && produto != null) {
                val botao = Estilo.texto(this, Estilo.Papel.CORPO,
                    if (plano == atual) getString(R.string.pl_seu_plano)
                    else CobrancaPlay.preco(produto)?.let { getString(R.string.pl_assinar_preco, it) } ?: getString(R.string.pl_assinar))
                cartao.addView(botao, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
                cartao.isFocusable = true
                cartao.setOnFocusChangeListener { v, f -> v.scaleX = if (f) 1.04f else 1f; v.scaleY = v.scaleX }
                cartao.setOnClickListener {
                    if (plano == atual) return@setOnClickListener
                    if (!CobrancaPlay.comprar(this, produto) { recreate() })
                        android.widget.Toast.makeText(this, R.string.pl_loja_indisponivel, android.widget.Toast.LENGTH_LONG).show()
                }
            } else if (plano == atual && Planos.VENDA_ATIVA) {
                cartao.addView(Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.pl_seu_plano)),
                    LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
            }
            linha.addView(cartao, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(16) })
        }
        // Os preços chegam da Play um pouco depois de abrir: redesenha uma vez quando chegarem.
        if (Planos.VENDA_ATIVA && CobrancaPlay.preco(CobrancaPlay.PRODUTO_PRO) == null && savedInstanceState == null)
            CobrancaPlay.atualizar(this) { runOnUiThread { if (!isFinishing && CobrancaPlay.preco(CobrancaPlay.PRODUTO_PRO) != null) recreate() } }
        // QR para a página dos planos: só no APK do site. Na Play a compra é dentro do app (a loja proíbe mandar pagar fora).
        if (!Planos.VENDA_ATIVA) linha.addView(LinearLayout(this).apply {
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
        (linha.getChildAt(3) ?: linha.getChildAt(1))?.requestFocus()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
