package br.paulo.tvlauncher

import android.content.Context
import android.view.SurfaceView
import android.widget.FrameLayout

/**
 * Proporção da imagem e velocidade (11/11/2026, ideias vistas no 1 Play). Vale para a TV ao vivo e para filmes/séries.
 *
 * Antes o vídeo ocupava a tela inteira sempre: um canal 4:3 ficava esticado. Agora o padrão é "Ajustar" (barras
 * pretas quando o formato não bate), e o usuário escolhe "Esticar" ou "Zoom" (corta as bordas e enche a tela).
 * A conta do tamanho é pura (tamanho()), testada em PlayerImagemTest; aplicar() só põe o tamanho na SurfaceView.
 */
object PlayerImagem {
    enum class Modo { AJUSTAR, ESTICAR, ZOOM }

    /** Velocidades oferecidas em filmes e séries (ao vivo não tem velocidade: não há o que adiantar). */
    val VELOCIDADES = floatArrayOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    fun rotuloVelocidade(v: Float): String = if (v == 1f) "Normal" else (if (v % 1f == 0f) "${v.toInt()}x" else "${v}x".replace('.', ','))

    /**
     * Largura e altura da área do vídeo, centrada na tela. `proporcaoPixel` é o pixelWidthHeightRatio do Media3 (vídeo
     * anamórfico: o pixel não é quadrado). Vídeo sem tamanho ainda (0) = tela inteira, até ele chegar.
     */
    fun tamanho(modo: Modo, telaL: Int, telaA: Int, videoL: Int, videoA: Int, proporcaoPixel: Float = 1f): Pair<Int, Int> {
        if (modo == Modo.ESTICAR || videoL <= 0 || videoA <= 0 || telaL <= 0 || telaA <= 0) return telaL to telaA
        val pv = videoL * (if (proporcaoPixel > 0f) proporcaoPixel else 1f) / videoA
        val pt = telaL.toFloat() / telaA
        val pelaLargura = when (modo) {
            Modo.AJUSTAR -> pv >= pt   // vídeo mais "largo" que a tela: encosta nas laterais
            else -> pv < pt            // zoom: o contrário (enche a tela e o que sobra fica fora dela)
        }
        return if (pelaLargura) telaL to Math.round(telaL / pv) else Math.round(telaA * pv) to telaA
    }

    // ---- preferência (uma para o player todo; o player roda no processo ":player", prefs simples bastam)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("player", Context.MODE_PRIVATE)

    fun modo(ctx: Context): Modo = runCatching { Modo.valueOf(prefs(ctx).getString("proporcao", null) ?: "AJUSTAR") }.getOrDefault(Modo.AJUSTAR)
    fun definirModo(ctx: Context, m: Modo) = prefs(ctx).edit().putString("proporcao", m.name).apply()

    fun rotulo(ctx: Context, m: Modo): String = ctx.getString(when (m) {
        Modo.AJUSTAR -> R.string.imagem_ajustar
        Modo.ESTICAR -> R.string.imagem_esticar
        Modo.ZOOM -> R.string.imagem_zoom
    })

    /**
     * Pergunta a proporção (Ajustar, Esticar, Zoom), grava e devolve a escolhida. Uma lista só para a TV ao vivo e para
     * filmes/séries (antes eram duas cópias), no cartão do sistema de design (Estilo.lista).
     */
    fun escolher(ctx: Context, atual: Modo, aoEscolher: (Modo) -> Unit) {
        val modos = Modo.values()
        Estilo.lista(ctx, ctx.getString(R.string.imagem_titulo), modos.map { rotulo(ctx, it) }, listOf(modos.indexOf(atual))) { i ->
            definirModo(ctx, modos[i])
            aoEscolher(modos[i])
        }
    }

    /** Põe o tamanho na SurfaceView (que está num FrameLayout em tela cheia, centrada). */
    fun aplicar(video: SurfaceView, modo: Modo, videoL: Int, videoA: Int, proporcaoPixel: Float) {
        val pai = video.parent as? android.view.View ?: return
        // Antes do 1º layout o pai mede 0x0 e o vídeo ficaria 0x0 (invisível) até o próximo tamanho: espera o layout.
        if (pai.width <= 0 || pai.height <= 0) { pai.post { if (pai.width > 0) aplicar(video, modo, videoL, videoA, proporcaoPixel) }; return }
        val (l, a) = tamanho(modo, pai.width, pai.height, videoL, videoA, proporcaoPixel)
        val lp = video.layoutParams as? FrameLayout.LayoutParams ?: return
        if (lp.width == l && lp.height == a) return
        lp.width = l; lp.height = a
        video.layoutParams = lp
    }
}
