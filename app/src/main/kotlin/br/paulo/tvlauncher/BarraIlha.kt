package br.paulo.tvlauncher

/**
 * Barra de cima "Ilha" (Paulo, 18/11/2026: a mistura A+B+D do estudo da barra). Navegando na cruz ela recolhe, e sobram:
 * - no centro, a pílula com o ponto laranja e a hora, que se estica com o que está acontecendo ([escolher]);
 * - no canto esquerdo, uma linha sobre a categoria em foco ([Canto]): o que está no ar, o que há para ouvir, a Central.
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
    /** Canto esquerdo: até 2 linhas do item, juntas com " · ". */
    const val CANTO_LINHAS = 2

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

    /** Uma linha do canto: [rotulo] colorido + [texto], vindos do item [origem] da categoria. */
    data class Canto(val rotulo: String, val texto: String, val origem: String)

    /** As primeiras linhas úteis de uma "linha de baixo" de item (separadas por \n), numa linha só. */
    fun linhaDoCanto(sub: String?, max: Int = CANTO_LINHAS, ignorar: String? = null): String =
        sub.orEmpty().split('\n').map { it.trim() }.filter { it.isNotEmpty() && it != ignorar?.trim() }.take(max).joinToString(" · ")

    /** Canto de Assistir: o programa no ar primeiro (é o que importa; visto na Sala, o canal sozinho enchia a linha). */
    fun cantoTv(canal: String, agora: String?): String = listOfNotNull(agora?.trim()?.takeIf { it.isNotEmpty() }, canal.trim()).joinToString(" · ")

    /** Largura do canto: do começo dele até a pílula, menos um respiro (nunca menos que [CANTO_MIN_DP]). */
    fun larguraCanto(inicioCanto: Int, inicioPilula: Int, respiro: Int, minimo: Int): Int = maxOf(minimo, inicioPilula - inicioCanto - respiro)
    const val CANTO_MIN_DP = 120

    /**
     * Sem repetir (Paulo: "o que está na pílula não aparece no canto"): o canto some quando o item de onde ele vem está em
     * foco (a linha de baixo dele já diz o mesmo, maior) ou quando a pílula já mostra o mesmo texto.
     */
    fun cantoVisivel(c: Canto?, itemFocado: String?, pilula: String?): Boolean {
        if (c == null || c.texto.isBlank() || c.origem == itemFocado) return false
        val p = pilula?.trim().orEmpty()
        return p.isEmpty() || !c.texto.contains(p, ignoreCase = true)
    }

    /** Ouvir: com o nosso som tocando (já na pílula), o canto passa para o outro item (rádio <-> podcasts). */
    fun origemOuvir(tocandoTipo: String?, temRadio: Boolean, temPodcast: Boolean): String? = when (tocandoTipo) {
        "radio" -> "podcasts".takeIf { temPodcast }
        "podcast" -> "radio".takeIf { temRadio }
        else -> if (temRadio) "radio" else "podcasts".takeIf { temPodcast }
    }
}
