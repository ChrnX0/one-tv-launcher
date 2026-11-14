package br.paulo.tvlauncher

/**
 * Regras da coluna esquerda do player (TV, Filmes, Séries), aprovadas pelo Paulo em 14/11/2026. Só lógica pura, testada
 * em ColunaRegrasTest; quem desenha é a [ColunaCategorias].
 *
 *  - categorias em ordem alfabética do nome exibido (sem acento, sem o prefixo "Filmes |" que a lista põe), "Outros" no fim;
 *  - "Esportes" fixo na TV: junta futebol, lutas, UFC, combate e afins, com quem está ao vivo agora primeiro;
 *  - "N novos" por categoria desde a última visita.
 */
object ColunaRegras {

    /** Ordena (grupo, contagem) pelo nome exibido, sem acento e sem caixa. Grupo sem nome ("Outros") vai por último. */
    fun ordenarAZ(grupos: List<Pair<String, Int>>): List<Pair<String, Int>> {
        val (semNome, comNome) = grupos.partition { it.first.isBlank() }
        return comNome.map { chaveAZ(it.first) to it }.sortedBy { it.first }.map { it.second } + semNome
    }

    /** Chave da ordem alfabética de um grupo: o nome que aparece na tela, sem acento e minúsculo. */
    fun chaveAZ(grupo: String): String {
        val n = PlayerFiltro.normalizar(PlayerLista.nomeExibido(grupo))
        // Grupo só com símbolo ou em outro alfabeto: vai depois das letras, na ordem do próprio texto.
        return n.ifEmpty { "￿" + grupo.lowercase() }
    }

    // ---------------------------------------------------------------- Esportes (TV)

    // Palavras inteiras (normalizadas, sem acento). "sport" pega sportv, espn não tem "esporte" no nome.
    private val reEsporteGrupo = Regex(
        """\b(esporte|esportes|esportivo|esportivos|sport|sports|sportv|espn|premiere|combate|ufc|luta|lutas|mma|boxe|wwe|""" +
            """futebol|football|soccer|nba|nfl|nhl|mlb|f1|formula 1|motor|tenis|tennis|golfe|golf|ppv|campeonato|""" +
            """brasileirao|libertadores|champions|copa|jogos|jogos de hoje|dazn|eleven|band sports|bandsports|tnt sports|""" +
            """xsports|fight|fighting|volei|basquete)\b""")
    private val reEsporteCanal = Regex(
        """\b(sportv|espn|premiere|combate|ufc|band ?sports|tnt sports|fox sports|dazn|eleven|xsports|canal do futebol|""" +
            """sport|sports|esporte|esportes|nba|nfl|f1)\b|\bsportv\d|\bespn\d|\bpremiere\d""")

    /** O canal é de esporte? Pelo grupo da lista ou, se o grupo não disser, pelo nome do canal. */
    fun ehEsporte(grupo: String, nomeCanal: String): Boolean {
        val g = PlayerFiltro.normalizar(PlayerLista.nomeExibido(grupo))
        if (g.isNotEmpty() && reEsporteGrupo.containsMatchIn(g)) return true
        return reEsporteCanal.containsMatchIn(PlayerFiltro.normalizar(nomeCanal))
    }

    private val reAoVivo = Regex("""\b(ao vivo|en vivo|live)\b|\s(x|vs\.?|versus)\s""")
    private val reNaoAoVivo = Regex("""\b(reprise|melhores momentos|compacto|vt|replay|reapresentacao|gols da rodada)\b""")

    /**
     * O programa de agora parece um evento ao vivo? O guia (XMLTV) não tem um campo confiável para isso; o título é o
     * que diz: "Flamengo x Palmeiras", "UFC 300 ao vivo", "Live: ...". Reprise e melhores momentos não contam.
     */
    fun pareceAoVivo(titulo: String, categoria: String = ""): Boolean {
        val t = PlayerFiltro.normalizar("$titulo $categoria")
        if (t.isEmpty() || reNaoAoVivo.containsMatchIn(t)) return false
        return reAoVivo.containsMatchIn(" $t ")
    }

    /** Ao vivo primeiro (na ordem em que vieram), depois o resto, também na ordem de antes. */
    fun <T> aoVivoPrimeiro(itens: List<T>, aoVivo: (T) -> Boolean): List<T> {
        val (sim, nao) = itens.partition(aoVivo)
        return sim + nao
    }

    // ---------------------------------------------------------------- "N novos" por categoria

    /**
     * Quantos títulos de cada grupo entraram depois de [marco] (segundos; o "adicionado" do Xtream). Marco 0 = primeira
     * visita: nada é "novo" (senão todas as categorias acenderiam de uma vez).
     */
    fun <T> novosPorGrupo(itens: List<T>, marco: Long, grupo: (T) -> String, quando: (T) -> Long): Map<String, Int> {
        if (marco <= 0) return emptyMap()
        val m = HashMap<String, Int>()
        for (i in itens) if (quando(i) > marco) { val g = grupo(i).trim(); m[g] = (m[g] ?: 0) + 1 }
        return m
    }

    /** O marco da próxima visita: o título mais novo do catálogo agora (nunca volta para trás). */
    fun <T> novoMarco(itens: List<T>, anterior: Long, quando: (T) -> Long): Long =
        maxOf(anterior, itens.maxOfOrNull(quando) ?: 0L)

    /** A última categoria aberta ainda existe? Senão, a de entrada padrão. Ações (Buscar, Filtro) nunca são "última". */
    fun categoriaInicial(salva: String?, existentes: Collection<String>, padrao: String): String =
        salva?.takeIf { it.isNotEmpty() && it in existentes } ?: padrao

    // ---------------------------------------------------------------- pular para uma letra (acréscimo 3)

    /** '#' (números e símbolos) e A a Z, na ordem em que aparecem na grade em ordem de nome. */
    val LETRAS: List<Char> = listOf('#') + ('A'..'Z')

    /** A letra de um título na ordem por nome ([Vod.chaveNome]: sem prefixo de país, sem acento). */
    fun letraDe(chaveNome: String): Char {
        val c = chaveNome.firstOrNull()?.uppercaseChar() ?: return '#'
        return if (c in 'A'..'Z') c else '#'
    }

    /**
     * Onde começa a [letra] numa grade em ordem de nome (as chaves já ordenadas): o 1º título dela ou, se não houver,
     * o 1º depois dela. '#' = o começo. -1 = grade vazia.
     */
    fun indiceDaLetra(chavesOrdenadas: List<String>, letra: Char): Int = indiceDaLetra(chavesOrdenadas.size, { chavesOrdenadas[it] }, letra)

    /**
     * O mesmo, por busca binária: só calcula a chave de ~15 títulos em vez de 20 mil (calcular todas levava segundos na
     * TV). Vale porque a grade em ordem de nome está ordenada por essa mesma chave ([Vod.ordenarPorNome]).
     */
    fun indiceDaLetra(tamanho: Int, chaveEm: (Int) -> String, letra: Char): Int {
        if (tamanho == 0) return -1
        if (letra == '#') return 0
        val alvo = letra.lowercaseChar().toString()
        var lo = 0; var hi = tamanho
        while (lo < hi) { val m = (lo + hi) ushr 1; if (chaveEm(m) < alvo) lo = m + 1 else hi = m }
        return minOf(lo, tamanho - 1)
    }
}
