package br.paulo.tvlauncher

/**
 * Ideia 48 (14/11/2026): o que dizer quando algo ganha o foco. Objeto puro, testado na JVM; quem junta os textos da
 * tela e fala é o [VozFoco].
 */
object FalaFoco {
    /** Frase longa demais cansa (e a próxima seta já corta): até [MAX] caracteres, cortando numa palavra. */
    const val MAX = 140

    /**
     * Junta os pedaços de texto do que está em foco (nome, detalhe, valor): tira espaço sobrando, vazios, repetidos e
     * símbolos de navegação (setas, "›") que o motor de voz leria por extenso.
     */
    fun frase(partes: List<CharSequence?>): String {
        val limpas = partes.mapNotNull { p ->
            p?.toString()?.replace(SIMBOLOS, " ")?.replace(Regex("\\s+"), " ")?.trim()?.takeIf { it.isNotEmpty() }
        }.distinct()
        val tudo = limpas.joinToString(". ")
        if (tudo.length <= MAX) return tudo
        val corte = tudo.lastIndexOf(' ', MAX).takeIf { it > MAX / 2 } ?: MAX
        return tudo.substring(0, corte).trimEnd('.', ',', ' ')
    }

    private val SIMBOLOS = Regex("[›»◀▶▲▼←→↑↓•·✓✗]")
}
