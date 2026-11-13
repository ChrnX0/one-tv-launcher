package br.paulo.tvlauncher

/**
 * Corta a moldura escura que algumas fotos de pintura do Met trazem (a borda da tela/do chassi fotografada junto).
 * Sem isso, no tema Galeria apareciam faixas pretas nas laterais da HOME e do protetor (visto em 10/11/2026).
 *
 * Função pura (só números, sem Android) para ter teste JVM: recebe os pixels ARGB e devolve o retângulo que fica.
 * Regra: vai tirando colunas/linhas da borda enquanto a média de brilho delas for quase preta, até no máximo
 * [MAX_FRACAO] de cada lado. O limite protege pintura escura de verdade (cena noturna): no pior caso perde 6% da borda.
 */
object BordaEscura {
    /** Brilho médio (0 a 255) abaixo do qual a coluna/linha conta como moldura. */
    const val LIMIAR = 30
    const val MAX_FRACAO = 0.06

    /** Devolve (esquerda, topo, direita, base), com direita/base exclusivas. Sem moldura = a imagem inteira. */
    fun limites(px: IntArray, largura: Int, altura: Int): IntArray {
        // Brilho aproximado (média ponderada do olho: verde pesa mais). Amostra 1 a cada 4 pixels: basta para a média.
        fun brilho(c: Int) = ((c shr 16 and 0xFF) * 3 + (c shr 8 and 0xFF) * 6 + (c and 0xFF)) / 10
        fun coluna(x: Int): Int { var s = 0; var n = 0; var y = 0; while (y < altura) { s += brilho(px[y * largura + x]); n++; y += 4 }; return if (n == 0) 255 else s / n }
        fun linha(y: Int): Int { var s = 0; var n = 0; var x = 0; while (x < largura) { s += brilho(px[y * largura + x]); n++; x += 4 }; return if (n == 0) 255 else s / n }
        val maxX = (largura * MAX_FRACAO).toInt()
        val maxY = (altura * MAX_FRACAO).toInt()
        var esq = 0; while (esq < maxX && coluna(esq) < LIMIAR) esq++
        var dir = largura; while (largura - dir < maxX && coluna(dir - 1) < LIMIAR) dir--
        var topo = 0; while (topo < maxY && linha(topo) < LIMIAR) topo++
        var base = altura; while (altura - base < maxY && linha(base - 1) < LIMIAR) base--
        return intArrayOf(esq, topo, dir, base)
    }

    /** Recorta [bmp] sem a moldura escura; devolve o próprio bmp se não houver nada a cortar. */
    fun aparar(bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val w = bmp.width; val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        val (e, t, d, b) = limites(px, w, h)
        if (e == 0 && t == 0 && d == w && b == h) return bmp
        return android.graphics.Bitmap.createBitmap(bmp, e, t, d - e, b - t).also { if (it !== bmp) bmp.recycle() }
    }
}
