package br.paulo.tvlauncher.home

/**
 * Barra de cima "Ilha" (Paulo, 18/11/2026: a mistura A+B+D do estudo da barra). Navegando na cruz ela recolhe, e sobram:
 * no centro, a pílula com o ponto laranja e a hora, que se estica com o que está acontecendo no SISTEMA ([fila]).
 * O estado de cada item da cruz (no ar, parou em…) fica no canto de baixo à direita, com os detalhes dele ([CruzLogica.Situacao];
 * Paulo, 18/11/2026 14:59 local: "a barra inteligente fica para as coisas do sistema e o canto inferior direito para as
 * infos dos nossos apps").
 * Com ▲ a barra de sempre volta (com o véu atrás) e a pílula dá lugar a ela. Lógica pura, testada na JVM.
 *
 * Ilha completa (19/11/2026, Paulo: "completíssima, tudo o que Apple, Samsung e Fire TV fazem, melhor e mais bonito"):
 *  - [fila]: até [MAX_FILA] eventos que se revezam a cada [RODIZIO_MS] (como o Now Bar da Samsung), em vez de só o 1º;
 *    "sem internet" fica sozinho (é o que importa quando acontece);
 *  - [trilho]: ícones pequenos do que está VALENDO (como os ícones de estado da Apple TV), no máximo [MAX_TRILHO];
 *  - [ponto]: o ponto da pílula vira semáforo (calmo, atenção, alerta), para quem não lê o texto.
 */
object BarraIlha {

    /** A fila que a pílula mostra agora, para o MCP (estado_tv), já como [paraMcp]; null = pílula só com a hora. */
    @Volatile var agora: org.json.JSONArray? = null

    /** Em ordem de importância: o primeiro presente abre a fila. */
    enum class Tipo { SEM_INTERNET, CAMPAINHA, SERVICO_FORA, TIMER, ALARME, LEMBRETE, TOCANDO, LEMBRETE_CASA, AGENDA, ANIVERSARIO, JOGO, VENCIMENTO, CONTAGEM }

    data class Evento(val tipo: Tipo, val texto: String, val tocando: Boolean = false) {
        /** Pede atenção (texto em âmbar e ponto âmbar/vermelho). */
        val atencao: Boolean get() = tipo == Tipo.SEM_INTERNET || tipo == Tipo.CAMPAINHA || tipo == Tipo.SERVICO_FORA || tipo == Tipo.VENCIMENTO
    }

    /** O que as pílulas da barra mostram agora (null = escondida). */
    data class Estado(
        val semInternet: String? = null,
        /** Campainha da Casa (Home Assistant): "Campainha · 10:12"; entra nos [CAMPAINHA_JANELA_MS] depois de tocar. */
        val campainha: String? = null,
        val campainhaHaMs: Long? = null,
        val timer: String? = null,
        val alarme: String? = null,
        /** Quanto falta para o despertador (ms); só entra na pílula dentro de [ALARME_JANELA_MS]. */
        val alarmeFaltaMs: Long? = null,
        /** Programa com lembrete marcado no guia ("Jornal · 20:30"); entra dentro de [LEMBRETE_JANELA_MS]. */
        val lembrete: String? = null,
        val lembreteFaltaMs: Long? = null,
        val tocandoTitulo: String? = null,
        val tocando: Boolean = false,
        /** Próximo compromisso da agenda de hoje ("Dentista · 15:00"); entra dentro de [AGENDA_JANELA_MS]. */
        val agenda: String? = null,
        val agendaFaltaMs: Long? = null,
        val aniversario: String? = null,
        val jogo: String? = null,
        val vencimento: String? = null,
        // Cartões próprios (HomeCards, 18/11/2026)
        val servicoFora: String? = null,
        val lembreteCasa: String? = null,
        val contagem: String? = null,
    )

    /** O despertador vai para a pílula nas últimas 12 h (antes disso, só na barra aberta). */
    const val ALARME_JANELA_MS = 12 * 3_600_000L
    /** Lembrete do guia: da última meia hora até 5 min depois de começar (quem chega atrasado ainda vê). */
    const val LEMBRETE_JANELA_MS = 30 * 60_000L
    const val LEMBRETE_ATRASO_MS = 5 * 60_000L
    /** Compromisso da agenda: nas 3 h antes (e enquanto acontece, até 15 min depois do início). */
    const val AGENDA_JANELA_MS = 3 * 3_600_000L
    const val AGENDA_ATRASO_MS = 15 * 60_000L
    /** A campainha fica na pílula por 3 min (quem estava na cozinha ainda vê que tocou). */
    const val CAMPAINHA_JANELA_MS = 3 * 60_000L
    /** Ao acordar a TV, a barra fica aberta por este tempo e então recolhe. */
    const val ACORDAR_ABERTA_MS = 4_000L
    /** Quantos eventos revezam na pílula, e de quanto em quanto tempo (calmo o bastante para ler em voz baixa). */
    const val MAX_FILA = 3
    const val RODIZIO_MS = 6_000L
    /** Ícones de estado ao lado da hora: mais que isso vira "+N" (a tela tem que continuar calma). */
    const val MAX_TRILHO = 3

    private fun ok(s: String?) = s?.trim()?.takeIf { it.isNotEmpty() && it != "…" }
    private fun dentro(falta: Long?, janela: Long, atraso: Long = 0L) = falta != null && falta in -atraso..janela

    /** Tudo o que pode ir para a pílula agora, do mais importante para o menos; no máximo [MAX_FILA]. */
    fun fila(e: Estado): List<Evento> {
        ok(e.semInternet)?.let { return listOf(Evento(Tipo.SEM_INTERNET, it)) }
        val l = ArrayList<Evento>(MAX_FILA)
        fun add(t: Tipo, s: String?, tocando: Boolean = false) { if (l.size < MAX_FILA) ok(s)?.let { l += Evento(t, it, tocando) } }
        if (dentro(e.campainhaHaMs?.let { -it }, 0L, CAMPAINHA_JANELA_MS)) add(Tipo.CAMPAINHA, e.campainha)
        add(Tipo.SERVICO_FORA, e.servicoFora)
        add(Tipo.TIMER, e.timer)
        if (dentro(e.alarmeFaltaMs, ALARME_JANELA_MS)) add(Tipo.ALARME, e.alarme)
        if (dentro(e.lembreteFaltaMs, LEMBRETE_JANELA_MS, LEMBRETE_ATRASO_MS)) add(Tipo.LEMBRETE, e.lembrete)
        add(Tipo.TOCANDO, e.tocandoTitulo, e.tocando)
        add(Tipo.LEMBRETE_CASA, e.lembreteCasa)
        if (dentro(e.agendaFaltaMs, AGENDA_JANELA_MS, AGENDA_ATRASO_MS)) add(Tipo.AGENDA, e.agenda)
        add(Tipo.ANIVERSARIO, e.aniversario)
        add(Tipo.JOGO, e.jogo)
        add(Tipo.VENCIMENTO, e.vencimento)
        add(Tipo.CONTAGEM, e.contagem)
        return l
    }

    /** O mais importante (o que a pílula mostra sem rodízio). */
    fun escolher(e: Estado): Evento? = fila(e).firstOrNull()

    /** O evento da vez no rodízio ([passo] cresce a cada [RODIZIO_MS]). */
    fun naVez(fila: List<Evento>, passo: Int): Evento? = if (fila.isEmpty()) null else fila[Math.floorMod(passo, fila.size)]

    /**
     * Quando a fila muda (um evento chega ou sai), em que passo continuar: o mesmo evento que estava na tela, se ele ainda
     * está na fila (a pílula não pula sozinha); senão, o começo (o mais importante).
     */
    fun passoDepois(antes: Evento?, fila: List<Evento>): Int =
        antes?.let { a -> fila.indexOfFirst { it.tipo == a.tipo }.takeIf { it >= 0 } } ?: 0

    // ------------------------------------------------------------------ trilho de estado

    /** Em ordem de importância. GRAVANDO primeiro: é o que custa caro esquecer ligado. */
    enum class Indicador { GRAVANDO, NAO_PERTURBE, VISITA, FONE }

    data class Trilho(val mostrar: List<Indicador>, val resto: Int)

    fun trilho(ativos: Set<Indicador>): Trilho {
        val l = Indicador.entries.filter { it in ativos }
        return Trilho(l.take(MAX_TRILHO), (l.size - MAX_TRILHO).coerceAtLeast(0))
    }

    // ------------------------------------------------------------------ ponto-semáforo

    enum class Ponto { CALMO, ATENCAO, ALERTA }

    fun ponto(fila: List<Evento>): Ponto = when {
        fila.any { it.tipo == Tipo.SEM_INTERNET } -> Ponto.ALERTA
        fila.any { it.atencao } -> Ponto.ATENCAO
        else -> Ponto.CALMO
    }

    // ------------------------------------------------------------------ Ilha que cresce

    /** Tipos que, ao CHEGAR, fazem a Ilha crescer por [CRESCER_MS] com a dica da ação (como a Dynamic Island). */
    private val CRESCEM = setOf(Tipo.SEM_INTERNET, Tipo.CAMPAINHA, Tipo.LEMBRETE, Tipo.AGENDA, Tipo.ALARME)
    const val CRESCER_MS = 5_000L

    /**
     * O evento que acabou de chegar e merece crescer (null = nada novo de importante). Só o que não estava na fila antes:
     * o rodízio e a fila reordenando não fazem a Ilha crescer de novo.
     */
    fun chegou(antes: List<Evento>, depois: List<Evento>): Evento? =
        depois.firstOrNull { d -> d.tipo in CRESCEM && antes.none { it.tipo == d.tipo } }

    /**
     * Resumo de chegada (como o "enquanto você esteve fora" dos concorrentes): ao acordar a TV, se há 2 ou mais coisas na
     * fila, a Ilha cresce com todas numa linha só, para quem chega não esperar o rodízio. Com 0 ou 1, nada (a pílula basta).
     */
    fun resumoChegada(fila: List<Evento>): String? = if (fila.size < 2) null else fila.joinToString("  ·  ") { it.texto }

    /** Cada texto que sai para o MCP: no máximo este tamanho (títulos vêm de fora: guia, agenda, Casa). */
    const val MCP_MAX = 80

    /**
     * A fila para o MCP (estado_tv.barra), como DADO estruturado: [{"tipo": "agenda", "texto": "…"}], com o texto sem
     * quebras/controles e cortado em [MCP_MAX], para um título de agenda ou do guia não virar um bloco de "instruções" na
     * saída da ferramenta (alertas do code scanning, #277). Visita já tira a agenda da fila; o resto é o que a tela mostra.
     */
    fun paraMcp(fila: List<Evento>): org.json.JSONArray? {
        if (fila.isEmpty()) return null
        val a = org.json.JSONArray()
        for (e in fila) {
            val t = e.texto.replace(Regex("[\\p{Cntrl}\\u2028\\u2029]+"), " ").replace(Regex("\\s+"), " ").trim()
            a.put(org.json.JSONObject().put("tipo", e.tipo.name.lowercase()).put("texto", if (t.length > MCP_MAX) t.take(MCP_MAX - 1) + "…" else t))
        }
        return a
    }

    /**
     * Mesmos eventos, na mesma ordem (só o texto pode ter mudado, como o timer contando). Nesse caso a tela só troca o texto:
     * o rodízio segue e o leitor de tela não anuncia a fila de novo a cada segundo (revisão do #277).
     */
    fun mesmaEstrutura(a: List<Evento>, b: List<Evento>): Boolean = a.map { it.tipo } == b.map { it.tipo }

    /** "2/3" quando há mais de um na fila; vazio com um só. */
    fun contador(fila: List<Evento>, passo: Int): String =
        if (fila.size < 2) "" else "${Math.floorMod(passo, fila.size) + 1}/${fila.size}"

    /** Texto para o leitor de tela e o MCP: a hora e a fila inteira (quem ouve não espera o rodízio). */
    fun descricao(hora: String, fila: List<Evento>): String = (listOf(hora) + fila.map { it.texto }).joinToString(". ")
}
