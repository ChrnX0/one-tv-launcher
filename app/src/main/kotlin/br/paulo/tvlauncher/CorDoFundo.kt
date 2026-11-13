package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap

/**
 * Cor de destaque que segue o fundo (10/11/2026, ideia K; li no Projectivy: "Adaptive Colors: the interface adapts its
 * colors to match your wallpaper"). No tema Galeria, a cada pintura do dia, tiramos uma cor marcante da imagem e a
 * borda de foco dos cards usa essa cor em vez do branco. Só com a chave [Extras.Chave.COR_DO_FUNDO] ligada.
 *
 * Como escolhe (puro, em [escolher], testado em CorDoFundoTest): reduz a imagem a 24×24, joga fora os pixels quase
 * cinza (pouca saturação) e os muito escuros, agrupa por matiz em 12 fatias e pega a fatia mais cheia, com peso para
 * a saturação. Depois CLAREIA a cor até ter luminância mínima, para a borda aparecer sobre o fundo escuro da HOME.
 * Sem biblioteca nova (a Palette do AndroidX faria o mesmo com ~100 KB a mais).
 */
object CorDoFundo {
    private const val CHAVE = "cor_do_fundo"
    const val LUMINANCIA_MINIMA = 0.55

    /** Chamado pelo tema Galeria quando a pintura do dia carrega (fora da thread da tela). */
    fun guardar(ctx: Context, bmp: Bitmap) {
        val pequeno = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val px = IntArray(24 * 24); pequeno.getPixels(px, 0, 24, 0, 0, 24, 24)
        if (pequeno !== bmp) pequeno.recycle()
        val cor = escolher(px) ?: return
        Extras.definirNumero(ctx, CHAVE, cor)
    }

    /** Cor da borda de foco: a do fundo (se ligada e conhecida) ou [padrao]. */
    fun foco(ctx: Context, padrao: Int): Int =
        if (!Extras.ligado(ctx, Extras.Chave.COR_DO_FUNDO) || Tema.atual(ctx) != Tema.GALERIA) padrao
        else Extras.numero(ctx, CHAVE, 0).takeIf { it != 0 } ?: padrao

    // ---------------------------------------------------------------- puro

    /** Cor ARGB opaca escolhida dos pixels, ou null se a imagem é toda cinza/escura. */
    fun escolher(pixels: IntArray): Int? {
        val peso = DoubleArray(12); val somaR = DoubleArray(12); val somaG = DoubleArray(12); val somaB = DoubleArray(12)
        for (c in pixels) {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val mx = maxOf(r, g, b); val mn = minOf(r, g, b)
            if (mx < 40) continue                       // escuro demais
            val sat = (mx - mn).toDouble() / mx
            if (sat < 0.25) continue                    // quase cinza
            val fatia = (matiz(r, g, b) / 30.0).toInt().coerceIn(0, 11)
            peso[fatia] += sat; somaR[fatia] += r * sat; somaG[fatia] += g * sat; somaB[fatia] += b * sat
        }
        val melhor = peso.indices.maxByOrNull { peso[it] } ?: return null
        if (peso[melhor] <= 0.0) return null
        val r = (somaR[melhor] / peso[melhor]).toInt(); val g = (somaG[melhor] / peso[melhor]).toInt(); val b = (somaB[melhor] / peso[melhor]).toInt()
        return clarear(r, g, b)
    }

    /** Matiz em graus (0..360). */
    fun matiz(r: Int, g: Int, b: Int): Double {
        val mx = maxOf(r, g, b); val mn = minOf(r, g, b); val d = (mx - mn).toDouble()
        if (d == 0.0) return 0.0
        val h = when (mx) {
            r -> ((g - b) / d) % 6
            g -> (b - r) / d + 2
            else -> (r - g) / d + 4
        } * 60
        return if (h < 0) h + 360 else h
    }

    /** Luminância relativa (0..1), fórmula simplificada do sRGB. */
    fun luminancia(r: Int, g: Int, b: Int): Double = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0

    /** Mistura com branco até a luminância passar de [LUMINANCIA_MINIMA]. Devolve ARGB opaco. */
    fun clarear(r0: Int, g0: Int, b0: Int): Int {
        var r = r0; var g = g0; var b = b0
        var passo = 0
        while (luminancia(r, g, b) < LUMINANCIA_MINIMA && passo < 20) {
            r += (255 - r) / 5 + 1; g += (255 - g) / 5 + 1; b += (255 - b) / 5 + 1
            r = r.coerceAtMost(255); g = g.coerceAtMost(255); b = b.coerceAtMost(255); passo++
        }
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
