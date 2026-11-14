package br.paulo.tvlauncher

/**
 * Tecla falsa (14/11/2026, ideia 63): plano de eventos para entregar uma tecla ao MESMO funil das teclas do controle
 * (AutorizaShizukuService.tecla), sem o controle na mão. Puro, com teste JVM.
 *
 * Por que existe: o `input keyevent` do adb NÃO passa pelo filtro de teclas da acessibilidade (ver CLAUDE.md), então
 * HOME segurado, botões Netflix/YouTube, hora de dormir etc. só se testavam com o controle. Com isto, testa-se a
 * lógica; o hardware (o controle mandar o código certo) continua precisando do controle de verdade.
 *
 * Imita o que o Android faz com uma tecla segurada: DOWN (repeat 0); depois de [REPETIR_APOS_MS], um DOWN repetido a
 * cada [REPETIR_CADA_MS] (repeat 1, 2...); por fim o UP na hora de soltar.
 */
object TeclaFalsa {
    const val REPETIR_APOS_MS = 500L   // ViewConfiguration.getKeyRepeatTimeout padrão
    const val REPETIR_CADA_MS = 50L    // ViewConfiguration.getKeyRepeatDelay padrão
    const val MAX_MS = 10_000L         // ninguém segura uma tecla mais que isso; protege a fila principal
    const val PADRAO_MS = 100L         // aperto curto

    /** Um evento do plano: [desce] = DOWN (false = UP), [repeticao] = repeatCount, [emMs] = atraso desde o início. */
    data class Evento(val desce: Boolean, val repeticao: Int, val emMs: Long)

    /** Duração pedida, presa entre 0 e [MAX_MS]; null ou inválida = [PADRAO_MS]. */
    fun duracao(pedida: Long?): Long = (pedida ?: PADRAO_MS).coerceIn(0L, MAX_MS)

    fun plano(segurarMs: Long): List<Evento> {
        val d = duracao(segurarMs)
        val r = ArrayList<Evento>()
        r += Evento(true, 0, 0L)
        var t = REPETIR_APOS_MS
        var n = 1
        while (t < d) { r += Evento(true, n++, t); t += REPETIR_CADA_MS }
        r += Evento(false, 0, d)
        return r
    }
}
