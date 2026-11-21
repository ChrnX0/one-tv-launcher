package br.paulo.tvlauncher.player

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.settings.Fontes

/**
 * Zero tela preta (estudo A dos players, 20/11/2026): enquanto o filme ou episódio abre, a tela mostra a cena dele
 * (quando a lista manda uma), o "T1 E2" em laranja e o título grande, com uma linha correndo embaixo. Antes era preto
 * com um "Carregando" no meio, a pior parte da troca de episódio. Some com um esmaecer curto no primeiro quadro.
 *
 * Referências: a abertura de episódio do Apple TV e do Netflix (cena escurecida + título no canto de baixo) e o token
 * de saída do transitions.dev (sumir ~250 ms, desacelerando). Letras grandes e laranja só no detalhe, como a cruz.
 */
object CortinaVideo {
    private const val SUMIR_MS = 260L

    /** [linha] = o que vai em cima do título (ex.: "T1 E2"); [titulo] = nome do episódio ou do filme. */
    fun montar(ctx: Context, imagem: String?, linha: String?, titulo: String): View {
        val d = ctx.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val raiz = FrameLayout(ctx).apply {
            setBackgroundColor(Estilo.FUNDO)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        if (!imagem.isNullOrEmpty() && M3u.urlAceita(imagem)) {
            val img = ImageView(ctx).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0.6f }
            // 960 px bastam atrás de um escuro (~1 MB na memória da TV de 2 GB), como o fundo da ficha.
            PlayerLogos.carregar(ctx, imagem, img, lado = 960)
            raiz.addView(img, FrameLayout.LayoutParams(-1, -1))
            raiz.addView(View(ctx).apply {
                background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                    intArrayOf(Estilo.FUNDO, Estilo.alfa(Estilo.FUNDO, 0xA0), Estilo.alfa(Estilo.FUNDO, 0x20)))
            }, FrameLayout.LayoutParams(-1, -1))
        }
        val bloco = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        if (!linha.isNullOrBlank()) bloco.addView(TextView(ctx).apply {
            text = linha.uppercase(); textSize = 20f; letterSpacing = 0.12f
            typeface = Fontes.nunito(ctx, 800); setTextColor(Estilo.ACENTO)
        })
        if (titulo.isNotBlank()) bloco.addView(TextView(ctx).apply {
            text = titulo; textSize = 40f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END
            typeface = Fontes.nunito(ctx, 800); setTextColor(Estilo.TEXTO)
        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        // A linha que corre: diz "está abrindo" sem texto, no lugar do "Carregando".
        val trilho = FrameLayout(ctx).apply {
            background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Estilo.alfa(Estilo.TEXTO, 0x30)) }
            clipChildren = true
        }
        val corre = View(ctx).apply { background = GradientDrawable().apply { cornerRadius = dp(2).toFloat(); setColor(Estilo.ACENTO) } }
        trilho.addView(corre, FrameLayout.LayoutParams(dp(72), -1))
        bloco.addView(trilho, LinearLayout.LayoutParams(dp(280), dp(4)).apply { topMargin = dp(20) })
        raiz.addView(bloco, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM or Gravity.START).apply {
            // Acima da barra do player (que aparece junto ao abrir e ocupa ~170 dp embaixo): conferido na Sala 20/11.
            setMargins(dp(Estilo.MARGEM_LATERAL), 0, dp(Estilo.MARGEM_LATERAL), dp(210))
        })
        ObjectAnimator.ofFloat(corre, View.TRANSLATION_X, -dp(72).toFloat(), dp(280).toFloat()).apply {
            duration = 1_100; repeatCount = ValueAnimator.INFINITE; interpolator = LinearInterpolator()
            raiz.tag = this
            start()
        }
        return raiz
    }

    /** Esmaece e tira a cortina da tela (o primeiro quadro chegou, ou não vai chegar: música, erro). */
    fun tirar(v: View) {
        (v.tag as? ValueAnimator)?.cancel()
        v.animate().alpha(0f).setDuration(SUMIR_MS).setInterpolator(DecelerateInterpolator())
            .withEndAction { (v.parent as? android.view.ViewGroup)?.removeView(v) }.start()
    }
}
