package br.paulo.tvlauncher

/**
 * Ideia 24 (14/11/2026): "Pular abertura" marcado uma vez por série. O servidor não diz onde a abertura está, então a
 * pessoa marca no ▼ do player o início e o fim (na posição em que o vídeo está), e a marcação vale para a série toda
 * (prefs "player", chave "abertura:<chave da série>"). Nos episódios seguintes, quando o vídeo entra no trecho, aparece
 * por ~8 s a pílula "Pular abertura · OK"; OK nesse momento pula para o fim do trecho.
 *
 * Só lógica pura (sem Android), testada em AberturaSerieTest.
 */
object AberturaSerie {
    const val DURACAO_MIN_MS = 10_000L        // menos que isso não é abertura (marcação errada)
    const val DURACAO_MAX_MS = 5 * 60_000L    // abertura de 5 min já é exagero
    const val INICIO_MAX_MS = 15 * 60_000L    // a abertura começa nos primeiros 15 min
    const val AVISO_MS = 8_000L               // quanto tempo a pílula fica na tela
    private const val FOLGA_FIM_MS = 1_000L   // a 1 s do fim, pular já não vale a pena

    /** Trecho da abertura em ms. [fim] = -1: só o início foi marcado (ainda falta o fim). */
    data class Marca(val ini: Long, val fim: Long = -1L) {
        val completa get() = fim >= 0
    }

    fun chave(chaveSerie: String) = "abertura:$chaveSerie"

    /** Como fica nas prefs: "ini,fim". */
    fun paraTexto(m: Marca): String = "${m.ini},${m.fim}"

    fun deTexto(t: String?): Marca? {
        val p = t?.split(',') ?: return null
        if (p.size != 2) return null
        val ini = p[0].trim().toLongOrNull()?.takeIf { it >= 0 } ?: return null
        val fim = p[1].trim().toLongOrNull() ?: return null
        return Marca(ini, if (fim < 0) -1L else fim)
    }

    /** Marcação que serve para pular: fim depois do início, de 10 s a 5 min, começando nos primeiros 15 min. */
    fun valida(m: Marca?): Boolean {
        m ?: return false
        if (!m.completa || m.ini < 0 || m.fim <= m.ini) return false
        val dur = m.fim - m.ini
        return dur in DURACAO_MIN_MS..DURACAO_MAX_MS && m.ini < INICIO_MAX_MS
    }

    /** Marca o início aqui. O fim que já existia fica, se continuar valendo com o início novo; senão falta marcá-lo. */
    fun marcarInicio(atual: Marca?, pos: Long): Marca {
        val ini = pos.coerceAtLeast(0)
        val fim = atual?.fim ?: -1L
        val nova = Marca(ini, fim)
        return if (valida(nova)) nova else Marca(ini)
    }

    /** Marca o fim aqui. Sem início marcado, null (a pessoa marca o início primeiro). Quem chama confere com [valida]. */
    fun marcarFim(atual: Marca?, pos: Long): Marca? = atual?.copy(fim = pos.coerceAtLeast(0))

    /** A pílula "Pular abertura" vale para esta posição? Dentro de [ini, fim), e não colado no fim. */
    fun mostrarPulo(m: Marca?, pos: Long): Boolean =
        valida(m) && pos >= m!!.ini && pos < m.fim - FOLGA_FIM_MS

    /** Para onde o OK leva. Null: a marcação não vale (não pula). */
    fun alvo(m: Marca?): Long? = if (valida(m)) m!!.fim else null
}
