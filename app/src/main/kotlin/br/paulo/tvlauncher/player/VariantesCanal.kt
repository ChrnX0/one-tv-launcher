package br.paulo.tvlauncher.player

import java.text.Normalizer

/**
 * Canais repetidos por qualidade (pedido do Paulo, 18/11/2026). As listas de IPTV trazem o mesmo canal várias vezes, uma
 * por qualidade ("Globo SP 4K", "Globo SP FHD", "Globo SP HD", "Globo SP SD"). Aqui eles viram UM canal na lista, e o
 * player escolhe a variante sozinho ([QualidadeAuto]). Só lógica pura (sem Android), testada em VariantesCanalTest.
 *
 * A regra de ouro: só junta quando a ÚNICA diferença entre os nomes é o marcador de qualidade (ou de codificação, ou
 * "ALT"). "Globo SP" x "Globo RJ", "HBO" x "HBO 2", "Warner" x "Warner +1", "BR: Globo" x "PT: Globo" continuam
 * separados, porque a diferença ali muda a programação ou a região.
 */
object VariantesCanal {

    /** Nível de qualidade para ordenar. SEM_MARCA = o nome não diz (fica entre SD e HD: costuma ser a versão "normal"). */
    enum class Nivel(val rotulo: String) { SD("SD"), SEM_MARCA(""), HD("HD"), FHD("Full HD"), UHD("4K") }

    /**
     * Resultado do agrupamento.
     * @param canais um canal por família, na ordem da lista (é o que a lista, o ▲▼ e o número usam)
     * @param variantes id do representante -> variantes da família, da MELHOR para a pior (só famílias com 2 ou mais)
     * @param apelido id de qualquer variante -> id do representante (favoritos e recentes antigos, pedidos de fora)
     */
    data class Grupos(
        val canais: List<Canal>,
        val variantes: Map<String, List<Canal>>,
        val apelido: Map<String, String>,
    ) {
        fun familia(id: String): List<Canal> = variantes[apelido[id] ?: id].orEmpty()
        fun representante(id: String): String = apelido[id] ?: id
    }

    // Marcadores que só dizem COMO o canal chega, não O QUE passa. Palavras soltas: "HD" não casa dentro de "HDMI".
    private val marcadores = Regex(
        """(?<![\p{L}\p{N}])(4K|UHD|2160[PI]?|FHD|FULL[ -]?HD|1080[PI]?|HD|HDTV|720[PI]?|SD|480[PI]?|576[PI]?|HQ|LQ|""" +
            """HEVC|H\.?26[45]|X26[45]|HDR(?:10)?|50 ?FPS|60 ?FPS|ALT(?:ERNATIV[OA])?|BACKUP)(?![\p{L}\p{N}])""",
        RegexOption.IGNORE_CASE)
    // Sobra dos marcadores: "[]", "()", "| |", traços e barras soltos no fim.
    private val sobra = Regex("""[\[(]\s*[|/\-]*\s*[\])]|\s*[|/\-–:]+\s*$|^\s*[|/\-–]+\s*""")
    private val espacos = Regex("""\s+""")
    private val pontuacao = Regex("""[^\p{L}\p{N}+]+""")
    private val acentos = Regex("""\p{Mn}+""")
    private val palavrasDeGrupo = Regex("""\b(canais|canal|channels?|tv|ao vivo|live)\b""")
    // Nome de canal maior que isso é lixo da lista: corta antes das regex (o "sobra" pode andar muito em texto longo).
    private const val NOME_MAX = 200

    /** Nível pelo nome (a mesma leitura do filtro de qualidade, mais o "sem marca"). */
    fun nivel(nome: String): Nivel = when (PlayerFiltro.qualidade(nome)) {
        PlayerFiltro.Qualidade.UHD -> Nivel.UHD
        PlayerFiltro.Qualidade.FHD -> Nivel.FHD
        PlayerFiltro.Qualidade.HD -> Nivel.HD
        PlayerFiltro.Qualidade.SD -> Nivel.SD
        null -> Nivel.SEM_MARCA
    }

    /** O nome para a TELA, sem os marcadores: "BR: Globo SP FHD" -> "BR: Globo SP"; "ESPN [4K]" -> "ESPN". */
    fun nomeLimpo(nome: String): String {
        var s = marcadores.replace(nome.take(NOME_MAX), " ")
        repeat(2) { s = sobra.replace(s, " ") }
        return espacos.replace(s, " ").trim().ifEmpty { nome.trim() }
    }

    /**
     * A chave da família: o nome sem marcadores, sem acento, sem pontuação e em minúsculas. O que muda a programação
     * ("SP", "RJ", "+1", "2", "Leste") fica na chave, e por isso não junta.
     */
    fun chave(nome: String): String {
        val semMarca = marcadores.replace(nome.take(NOME_MAX), " ")
        val semAcento = acentos.replace(Normalizer.normalize(semMarca, Normalizer.Form.NFD), "")
        return espacos.replace(pontuacao.replace(semAcento.lowercase(), " "), " ").trim()
    }

    // A chave de cada nome, lembrada: o agrupar roda a cada filtro (na thread da tela) e 20 mil nomes com regex e
    // Normalizer pesam na TV de 2 GB. Os nomes quase nunca mudam entre uma chamada e outra.
    private val chaves = HashMap<String, String>()

    @Synchronized private fun chaveGuardada(nome: String): String {
        chaves[nome]?.let { return it }
        if (chaves.size > 50_000) chaves.clear()
        return chave(nome).also { chaves[nome] = it }
    }

    /**
     * Grupo que só existe para separar por qualidade ("4K", "Canais | FHD", "UHD 4K"): o canal não "mora" ali. O
     * representante prefere morar no grupo de verdade ("Globo", "Abertos"), para não sumir da categoria dele.
     */
    fun grupoDeQualidade(grupo: String): Boolean {
        val g = PlayerLista.nomeExibido(grupo)
        if (!marcadores.containsMatchIn(g)) return false
        return chave(g).replace(palavrasDeGrupo, "").isBlank()
    }

    /**
     * Junta as variantes. Uma família só se forma com nomes que, sem os marcadores, ficam iguais E com pelo menos um
     * marcador de diferença (dois "Globo SP HD" iguais também juntam: o segundo vira reserva). Nome que fica vazio sem
     * os marcadores (um canal chamado "4K") não junta com nada.
     */
    fun agrupar(canais: List<Canal>): Grupos {
        if (canais.size < 2) return Grupos(canais, emptyMap(), emptyMap())
        val porChave = LinkedHashMap<String, MutableList<Canal>>()
        val ordem = ArrayList<Any>(canais.size)   // Canal solto ou a chave da família, na ordem da 1ª aparição
        for (c in canais) {
            // O país do NOME já fica na chave ("BR: ESPN" -> "br espn"); o do grupo não entra de propósito: a variante
            // "Globo FHD" que mora no grupo "4K" tem de juntar com a "Globo HD" do grupo "BR | Abertos".
            val k = chaveGuardada(c.nome)
            if (k.isEmpty()) { ordem += c; continue }
            val l = porChave[k]
            if (l == null) { porChave[k] = mutableListOf(c); ordem += k } else l += c
        }
        if (porChave.values.none { it.size > 1 }) return Grupos(canais, emptyMap(), emptyMap())
        val saida = ArrayList<Canal>(porChave.size)
        val variantes = HashMap<String, List<Canal>>()
        val apelido = HashMap<String, String>()
        val ehGrupoDeQualidade = HashMap<String, Boolean>()   // poucos grupos, muitos canais: lê cada grupo uma vez
        for (o in ordem) {
            if (o is Canal) { saida += o; continue }
            val familia = porChave.getValue(o as String)
            if (familia.size == 1) { saida += familia[0]; continue }
            // Mora no grupo de verdade (o 1º que não é "4K"/"FHD"); o número e o id são dessa variante.
            val casa = familia.firstOrNull { !ehGrupoDeQualidade.getOrPut(it.grupo) { grupoDeQualidade(it.grupo) } } ?: familia[0]
            val rep = casa.copy(nome = nomeLimpo(casa.nome),
                logo = casa.logo.ifEmpty { familia.firstOrNull { it.logo.isNotEmpty() }?.logo.orEmpty() },
                guiaId = casa.guiaId.ifEmpty { familia.firstOrNull { it.guiaId.isNotEmpty() }?.guiaId.orEmpty() },
                arquivoDias = familia.maxOf { it.arquivoDias })
            saida += rep
            // Melhor primeiro; no mesmo nível, a ordem da lista (a 1ª costuma ser a principal, as outras são reserva).
            // O nível é lido uma vez por canal (no comparador seria a cada comparação).
            val niveis = IntArray(familia.size) { nivel(familia[it].nome).ordinal }
            variantes[rep.id] = familia.indices.sortedWith(compareByDescending<Int> { niveis[it] }.thenBy { it }).map { familia[it] }
            for (v in familia) if (v.id != rep.id) apelido[v.id] = rep.id
        }
        return Grupos(saida, variantes, apelido)
    }

    /** Ids antigos (de uma variante que sumiu da lista ao juntar) -> id do representante, sem repetir. */
    fun migrarIds(ids: Collection<String>, g: Grupos): List<String> =
        if (g.apelido.isEmpty()) ids.toList() else ids.mapTo(LinkedHashSet()) { g.representante(it) }.toList()
}
