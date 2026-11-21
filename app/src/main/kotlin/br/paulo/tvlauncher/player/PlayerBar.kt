package br.paulo.tvlauncher.player

import android.content.Context
import android.content.res.ColorStateList
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R

/**
 * A barra de baixo dos players (proposta C, B9, 20/11/2026): filmes, séries e TV ao vivo com a MESMA peça. Antes cada
 * player tinha a sua (raio, margens, tamanho do título, cor e espessura da barra diferentes; a de filmes usava um
 * vermelho-rosado fora da marca). Agora: vidro do sistema, título no papel TITULO, linha de detalhe embaixo dele, um
 * selo de estado à direita (AO VIVO, PAUSADO), a barra de progresso da cruz (laranja só no que já passou, regra do
 * estudo global) e a linha de teclas no tamanho DICA.
 *
 * Referência: a barra do Apple TV e da Netflix (título em cima, progresso fino, tempo restante e "termina às" na linha
 * de baixo). Cada player só põe o que é dele: a TV coloca número e logo do canal no começo da [Partes.linha].
 */
object PlayerBar {
    class Partes(
        val raiz: LinearLayout,
        val linha: LinearLayout,
        val titulo: TextView,
        val detalhe: TextView,
        val estado: TextView,
        val progresso: ProgressBar,
        val teclas: TextView,
    )

    fun montar(ctx: Context, teclas: CharSequence): Partes {
        fun dp(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
        val raiz = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundo(ctx, Estilo.alfa(Estilo.FUNDO, 0xD9), Estilo.RAIO, Estilo.VIDRO_BORDA)
            setPaddingRelative(dp(24), dp(16), dp(24), dp(16))
        }
        val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val textos = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val titulo = Estilo.texto(ctx, Estilo.Papel.TITULO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        val detalhe = TextView(ctx).apply {
            textSize = Estilo.Leitura.ITEM_DETALHE; setTextColor(Estilo.TEXTO_2); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        textos.addView(titulo); textos.addView(detalhe)
        linha.addView(textos, LinearLayout.LayoutParams(0, -2, 1f))
        val estado = Estilo.texto(ctx, Estilo.Papel.SECAO).apply { visibility = View.GONE }
        linha.addView(estado, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16) })
        raiz.addView(linha)
        val progresso = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(Estilo.ACENTO)
            progressBackgroundTintList = ColorStateList.valueOf(Estilo.VIDRO_BORDA)
            secondaryProgressTintList = ColorStateList.valueOf(0x66FFFFFF)   // o que já carregou (filmes e séries)
        }
        raiz.addView(progresso, LinearLayout.LayoutParams(-1, dp(BarraTempo.ESPESSURA_DP)).apply { topMargin = dp(12) })
        val linhaTeclas = TextView(ctx).apply { text = teclas; textSize = Estilo.Leitura.DICA; setTextColor(Estilo.TEXTO_3) }
        raiz.addView(linhaTeclas, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10) })
        return Partes(raiz, linha, titulo, detalhe, estado, progresso, linhaTeclas)
    }
}
