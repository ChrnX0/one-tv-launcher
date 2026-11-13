package br.paulo.tvlauncher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader

/**
 * Fundo do foco dos cards de Assistir (TV, Séries, Filmes, Na rede), 11/11/2026. dono: "sabe esse fundinho que tem do
 * aplicativo quando ele está selecionado? por que não temos isso nos nossos cards?". Um app traz o banner dele (a arte
 * 16:9 da tela do Google TV); estes cards não são apps e só mostravam o degradê da cor. Agora montam a própria arte, no
 * mesmo formato do banner, e a [CapaFoco] a desenha como faz com um app (véu, máscara, esmaecer).
 *
 * Escolha do dono ("os dois"): a arte de verdade quando existe e, sem ela, o ícone do card em tamanho grande.
 * - Arte (Séries e Filmes): as imagens de cena (backdrop) do último título aberto, ou o pôster; preenche tudo (corte central).
 *   O logo do canal foi tentado e saiu no mesmo dia (dono: "por que apareceu aquilo?"). Foto do vídeo ao vivo não dá:
 *   na Sala o vídeo vai numa camada de hardware e a captura sai preta.
 * - Sem arte (nunca assistiu, sem internet, servidor sem imagem): o ícone de linha do card, grande.
 *
 * Roda em qualquer thread (só Canvas num Bitmap, sem View).
 */
object CapaAtalho {
    // Tamanho do banner de TV dobrado (320x180 x 1,6): a capa ocupa ~2/3 da largura da tela, e menor que isso borra.
    const val LARGURA = 512
    const val ALTURA = 288

    /** Monta a capa: degradê das [cores] do card e, por cima, a [arte] ou o ícone grande. */
    fun montar(cores: IntArray, icone: IconeApp.Tipo, arte: Bitmap?): Bitmap {
        val bmp = Bitmap.createBitmap(LARGURA, ALTURA, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val w = LARGURA.toFloat(); val h = ALTURA.toFloat()
        // Mesmo degradê do card (canto de cima à esquerda -> de baixo à direita).
        c.drawRect(0f, 0f, w, h, Paint().apply { shader = LinearGradient(0f, 0f, w, h, cores, null, Shader.TileMode.CLAMP) })
        val pinta = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        when {
            arte != null -> {
                // Corte central, como o banner de um app (CapaFoco faz o mesmo com o banner).
                val escala = maxOf(w / arte.width, h / arte.height)
                val vw = (w / escala).toInt(); val vh = (h / escala).toInt()
                val x0 = (arte.width - vw) / 2; val y0 = (arte.height - vh) / 2
                c.drawBitmap(arte, Rect(x0, y0, x0 + vw, y0 + vh), RectF(0f, 0f, w, h), pinta)
            }
            else -> {
                // O ícone de linha do card (o mesmo do canto do card), grande e quase branco: o "logo" de quem não tem logo.
                val lado = (h * 0.62f).toInt()
                IconeApp(icone, 0xE6FFFFFF.toInt()).apply {
                    setBounds(((w - lado) / 2).toInt(), ((h - lado) / 2).toInt(), ((w + lado) / 2).toInt(), ((h + lado) / 2).toInt())
                }.draw(c)
            }
        }
        return bmp
    }
}
