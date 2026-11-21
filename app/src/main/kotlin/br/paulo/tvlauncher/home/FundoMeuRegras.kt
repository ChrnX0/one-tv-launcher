package br.paulo.tvlauncher.home

import br.paulo.tvlauncher.settings.Tema

/**
 * "Meu fundo" (degrau 1 contra o Projectivy, 15/11/2026): a pessoa põe a própria foto, GIF ou vídeo atrás da HOME, com
 * brilho e contraste. É um terceiro tema ([Tema.MEU]); os outros dois continuam iguais.
 *
 * Só as contas puras (testadas em FundoMeuRegrasTest). A tela é [FundoMeu]; a escolha do arquivo, as Configurações.
 */
object FundoMeuRegras {
    enum class Tipo { IMAGEM, ANIMADA, VIDEO }

    /** Pastas onde procurar (relativas à memória interna), na ordem em que aparecem na lista. */
    val PASTAS = listOf("Fundo", "Pictures", "DCIM", "Movies", "Download")
    const val MAX_ARQUIVOS = 60

    /** Tipo pelo nome do arquivo. Null = não serve de fundo. */
    fun tipo(nome: String): Tipo? = when (nome.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg", "png", "webp", "heic", "heif" -> Tipo.IMAGEM
        "gif" -> Tipo.ANIMADA
        "mp4", "webm", "mkv", "m4v", "mov" -> Tipo.VIDEO
        else -> null
    }

    /** Degraus do brilho e do contraste (em %), na volta do OK. 0 = como o arquivo é. */
    val DEGRAUS = listOf(0, 20, 40, -40, -20)

    fun proximoDegrau(atual: Int): Int = DEGRAUS[(DEGRAUS.indexOf(atual).coerceAtLeast(0) + 1) % DEGRAUS.size]   // estranho = vale 0

    /**
     * Matriz de cor 4x5 do Android (ColorMatrix) para [brilho] e [contraste] em % (-100..100).
     * Contraste c: escala (1 + c/100) em volta do cinza médio (128). Brilho b: soma b% de 255 depois.
     * Null quando os dois são 0: sem filtro, a GPU não gasta nada.
     */
    fun matriz(brilho: Int, contraste: Int): FloatArray? {
        if (brilho == 0 && contraste == 0) return null
        val k = 1f + contraste.coerceIn(-100, 100) / 100f
        val soma = 128f * (1f - k) + 255f * brilho.coerceIn(-100, 100) / 100f
        return floatArrayOf(
            k, 0f, 0f, 0f, soma,
            0f, k, 0f, 0f, soma,
            0f, 0f, k, 0f, soma,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** Fator de redução (potência de 2) para decodificar uma imagem [w]x[h] perto de [alvoW]x[alvoH] sem estourar memória. */
    fun amostragem(w: Int, h: Int, alvoW: Int, alvoH: Int): Int {
        var s = 1
        while (w / (s * 2) >= alvoW && h / (s * 2) >= alvoH) s *= 2
        return s
    }
}
