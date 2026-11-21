package br.paulo.tvlauncher.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.view.View
import androidx.media3.common.text.Cue

/**
 * Legenda que chega como IMAGEM (14/11/2026): PGS (Blu-ray), VobSub (DVD), DVB e ASS/SSA desenhada pelo libass.
 *
 * Antes a tela do filme só desenhava o texto das falas (cue.text); as legendas em imagem eram jogadas fora, e o filme
 * ficava sem legenda. Esta view fica por cima do vídeo e desenha cada imagem no lugar que o arquivo manda, dentro da
 * área do vídeo (a SurfaceView já está do tamanho certo pelo PlayerImagem). A conta do lugar é pura ([lugar]) e tem
 * teste (LegendaImagemTest); a view só desenha.
 */
object LegendaImagem {
    // Os mesmos valores das constantes do Media3 (Cue.DIMEN_UNSET, Cue.ANCHOR_TYPE_*), copiados para a conta pura
    // rodar no teste do computador sem o Media3.
    const val SEM_VALOR = -Float.MAX_VALUE
    const val ANCORA_INICIO = 0
    const val ANCORA_MEIO = 1
    const val ANCORA_FIM = 2

    /** Retângulo em pixels (esquerda, topo, largura, altura). */
    data class Lugar(val x: Int, val y: Int, val l: Int, val a: Int)

    /**
     * Onde desenhar uma imagem de legenda numa área de `areaL` x `areaA` pixels que começa em (`areaX`, `areaY`).
     * Mesma conta do SubtitleView do Media3: posição/linha/tamanho são frações da área; a âncora diz se o ponto é o
     * começo, o meio ou o fim da imagem. Sem altura no cue, a altura segue a proporção da própria imagem.
     * Campos sem valor (arquivo mal feito) caem num padrão seguro: centrada, embaixo, na largura da imagem.
     */
    fun lugar(areaX: Int, areaY: Int, areaL: Int, areaA: Int, imgL: Int, imgA: Int,
              posicao: Float, ancoraPosicao: Int, linha: Float, ancoraLinha: Int, tamanho: Float, alturaImagem: Float): Lugar? {
        if (areaL <= 0 || areaA <= 0 || imgL <= 0 || imgA <= 0) return null
        val pos = if (posicao == SEM_VALOR) 0.5f else posicao
        val ancP = if (posicao == SEM_VALOR) ANCORA_MEIO else ancoraPosicao
        val lin = if (linha == SEM_VALOR) 0.95f else linha
        val ancL = if (linha == SEM_VALOR) ANCORA_FIM else ancoraLinha
        val largura = if (tamanho == SEM_VALOR || tamanho <= 0f) Math.round(imgL.toFloat() * areaL / maxOf(imgL, areaL))
                      else Math.round(areaL * tamanho)
        if (largura <= 0) return null
        val altura = if (alturaImagem != SEM_VALOR && alturaImagem > 0f) Math.round(areaA * alturaImagem)
                     else Math.round(largura * (imgA.toFloat() / imgL))
        val ax = areaX + pos * areaL
        val ay = areaY + lin * areaA
        val x = Math.round(when (ancP) { ANCORA_FIM -> ax - largura; ANCORA_MEIO -> ax - largura / 2f; else -> ax })
        val y = Math.round(when (ancL) { ANCORA_FIM -> ay - altura; ANCORA_MEIO -> ay - altura / 2f; else -> ay })
        return Lugar(x, y, largura, altura)
    }

    /** A view que desenha. [area] devolve o retângulo do vídeo na tela (a SurfaceView). */
    class Vista(ctx: Context, private val area: () -> Rect) : View(ctx) {
        private var cues: List<Cue> = emptyList()
        private val pincel = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val destino = Rect()

        init { setWillNotDraw(false) }

        /** Só as que têm imagem; as de texto continuam no TextView da tela (com o estilo acessível). */
        fun mostrar(todas: List<Cue>) {
            val novas = todas.filter { it.bitmap != null }
            if (novas.isEmpty() && cues.isEmpty()) return
            cues = novas
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (cues.isEmpty()) return
            val r = area()
            for (c in cues) {
                val b = c.bitmap ?: continue
                val p = lugar(r.left, r.top, r.width(), r.height(), b.width, b.height,
                    c.position, c.positionAnchor, c.line, c.lineAnchor, c.size, c.bitmapHeight) ?: continue
                destino.set(p.x, p.y, p.x + p.l, p.y + p.a)
                canvas.drawBitmap(b, null, destino, pincel)
            }
        }
    }
}
