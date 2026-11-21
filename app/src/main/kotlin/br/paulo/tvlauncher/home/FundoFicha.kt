package br.paulo.tvlauncher.home

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.player.M3u
import br.paulo.tvlauncher.player.PlayerLogos

/**
 * A cena do filme ou da série ATRÁS da ficha (18/11/2026, "usar tudo o que a lista dá"): a imagem larga que a lista já
 * manda (backdrop_path do Xtream), que antes só ia para o card da HOME. Fica no lado direito e some num escuro forte
 * para a esquerda e para baixo: o texto da ficha continua sobre fundo quase liso (letra grande, idosos).
 * Sem imagem, a ficha fica como sempre.
 */
object FundoFicha {
    /** Põe a cena por baixo do que já está no [painel] (chamar depois de montar a ficha). */
    fun por(ctx: Context, painel: FrameLayout, url: String?) {
        if (url.isNullOrEmpty() || !M3u.urlAceita(url)) return
        val img = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.55f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // 960 px de largura bastam (fica atrás de um escuro) e custam ~1 MB na memória da TV de 2 GB.
        PlayerLogos.carregar(ctx, url, img, lado = 960)
        val escuro = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Estilo.FUNDO, Estilo.FUNDO, Estilo.alfa(Estilo.FUNDO, 0xB0), Estilo.alfa(Estilo.FUNDO, 0x70)))
        }
        val baixo = View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                intArrayOf(Estilo.FUNDO, Estilo.alfa(Estilo.FUNDO, 0x00)))
        }
        painel.addView(img, 0, FrameLayout.LayoutParams(-1, -1))
        painel.addView(escuro, 1, FrameLayout.LayoutParams(-1, -1))
        painel.addView(baixo, 2, FrameLayout.LayoutParams(-1, (painel.resources.displayMetrics.density * 220).toInt(), android.view.Gravity.BOTTOM))
    }
}
