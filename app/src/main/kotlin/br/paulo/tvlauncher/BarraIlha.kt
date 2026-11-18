package br.paulo.tvlauncher

/**
 * Barra de cima "Ilha" (Paulo, 18/11/2026: a mistura A+B+D do estudo da barra). Navegando na cruz ela recolhe, e sobram:
 * - no centro, a pílula com o ponto laranja e a hora, que se estica com o que está acontecendo ([escolher]);
 * - no canto esquerdo, o estado agora do item em foco ([Canto]): no ar, gravando, tocando, onde parou, conectado.
 * Com ▲ a barra de sempre volta (com o véu atrás) e a pílula dá lugar a ela. Lógica pura, testada na JVM.
 */
object BarraIlha {

    /** Em ordem de importância: o primeiro presente vai para a pílula. */
    enum class Tipo { SEM_INTERNET, TIMER, ALARME, TOCANDO, ANIVERSARIO, JOGO, VENCIMENTO }

    data class Evento(val tipo: Tipo, val texto: String, val tocando: Boolean = false)

    /** O que as pílulas da barra mostram agora (null = escondida). */
    data class Estado(
        val semInternet: String? = null,
        val timer: String? = null,
        val alarme: String? = null,
        /** Quanto falta para o despertador (ms); só entra na pílula dentro de [ALARME_JANELA_MS]. */
        val alarmeFaltaMs: Long? = null,
        val tocandoTitulo: String? = null,
        val tocando: Boolean = false,
        val aniversario: String? = null,
        val jogo: String? = null,
        val vencimento: String? = null,
    )

    /** O despertador vai para a pílula nas últimas 12 h (antes disso, só na barra aberta). */
    const val ALARME_JANELA_MS = 12 * 3_600_000L
    /** Ao acordar a TV, a barra fica aberta por este tempo e então recolhe. */
    const val ACORDAR_ABERTA_MS = 4_000L
    fun escolher(e: Estado): Evento? {
        fun ok(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && it != "…" }
        ok(e.semInternet)?.let { return Evento(Tipo.SEM_INTERNET, it) }
        ok(e.timer)?.let { return Evento(Tipo.TIMER, it) }
        ok(e.alarme)?.takeIf { e.alarmeFaltaMs != null && e.alarmeFaltaMs in 0..ALARME_JANELA_MS }?.let { return Evento(Tipo.ALARME, it) }
        ok(e.tocandoTitulo)?.let { return Evento(Tipo.TOCANDO, it, e.tocando) }
        ok(e.aniversario)?.let { return Evento(Tipo.ANIVERSARIO, it) }
        ok(e.jogo)?.let { return Evento(Tipo.JOGO, it) }
        ok(e.vencimento)?.let { return Evento(Tipo.VENCIMENTO, it) }
        return null
    }

    /**
     * O "estado agora" do item em foco (Paulo, 18/11/2026 14:15 local: "mais inteligente, coerente e lógico"): [rotulo]
     * colorido (No ar, Gravando, Tocando, Parou em, Conectado) + [texto] no formato de [andamento]. [tira] = as linhas
     * da linha de baixo do item que o canto substitui (não aparecem de novo embaixo nem nos detalhes).
     */
    data class Canto(val rotulo: String, val texto: String, val origem: String, val tira: List<String> = emptyList())

    /** "o quê · 35% · faltam 43 min": as partes vazias somem; a porcentagem só entre 1% e 99%. */
    fun andamento(oQue: String?, fracao: Float?, falta: String?): String = listOfNotNull(
        oQue?.trim()?.takeIf { it.isNotEmpty() },
        fracao?.takeIf { it >= 0.01f && it < 0.995f }?.let { "${kotlin.math.round(it * 100).toInt()}%" },
        falta?.trim()?.takeIf { it.isNotEmpty() },
    ).joinToString(" · ")

    /** O que a linha de baixo deixa de mostrar porque o canto mostra (o próprio texto do canto e o que ele substitui). */
    fun tirarDoItem(c: Canto?): List<String> = if (c == null) emptyList() else c.tira + c.texto

    /** Largura do canto: do começo dele até a pílula, menos um respiro (nunca menos que [CANTO_MIN_DP]). */
    fun larguraCanto(inicioCanto: Int, inicioPilula: Int, respiro: Int, minimo: Int): Int = maxOf(minimo, inicioPilula - inicioCanto - respiro)
    const val CANTO_MIN_DP = 120

    /**
     * O canto segue a seleção (Paulo, 18/11/2026 12:15 local: "a barra inteligente tem que acompanhar a seleção"): mostra
     * o que está acontecendo agora com o item em foco ([HomeCruz.Item.canto]); sem nada, fica vazio. Some também quando a
     * pílula já mostra o mesmo texto (Paulo: "o que está na pílula não aparece no canto").
     */
    fun cantoVisivel(c: Canto?, pilula: String?): Boolean {
        if (c == null || c.texto.isBlank()) return false
        val p = pilula?.trim().orEmpty()
        return p.isEmpty() || !c.texto.contains(p, ignoreCase = true)
    }
}
