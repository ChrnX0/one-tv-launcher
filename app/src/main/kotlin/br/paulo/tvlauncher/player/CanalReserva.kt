package br.paulo.tvlauncher.player

/**
 * Canal reserva automático (elevação 5.2, onda 1, 17/11/2026): as REGRAS (CanalReservaTest). Quem usa é o
 * PlayerActivity, depois das 3 tentativas do mesmo endereço.
 *
 * Listas de IPTV costumam trazer o mesmo canal mais de uma vez ("Globo HD", "Globo FHD", "Globo SD", "Globo [Alt]") e,
 * na casa com duas contas, o mesmo canal em cada uma. Quando o canal cai 3 vezes seguidas, o player toca a próxima
 * variante que ainda não falhou e avisa em uma linha. Nunca troca para canal bloqueado (o filtro vem de fora) nem
 * volta para um endereço que já falhou nesta sequência.
 */
object CanalReserva {
    /** Palavras de "cópia" que não mudam o canal (além das de qualidade do JuntoRegras.normalizarCanal). */
    private val COPIA = Regex("\\b(alt|alternativo|backup|bkp|reserva|op\\d|opcao \\d|server \\d|s\\d|h264|60fps|50fps)\\b")

    /** "BR: Globo HD [Alt]" e "Globo FHD" viram a mesma chave. */
    fun base(nome: String): String {
        var n = JuntoRegras.normalizarCanal(nome).replace('-', ' ')
        n = n.replace(COPIA, " ").replace(Regex("\\s+"), " ").trim()
        // "br globo" (país na frente, comum nas listas): o país sozinho no começo sai.
        val p = n.split(' ')
        if (p.size > 1 && p[0].length == 2) n = p.drop(1).joinToString(" ")
        return n
    }

    /** Ordem de preferência da reserva: mesma qualidade primeiro, depois a mais leve (SD carrega mais fácil na rede ruim). */
    private fun qualidade(nome: String): Int {
        val n = nome.lowercase()
        return when {
            Regex("\\b(4k|uhd)\\b").containsMatchIn(n) -> 3
            Regex("\\bfhd|1080p\\b").containsMatchIn(n) -> 2
            Regex("\\bhd|720p\\b").containsMatchIn(n) -> 1
            else -> 0
        }
    }

    /**
     * A próxima variante de [atual] em [canais], fora as de [tentados] (ids e endereços que já falharam) e as que
     * [permitido] recusa. null = não há reserva (aí sim a mensagem de "sem sinal").
     */
    fun proxima(canais: List<Canal>, atual: Canal, tentados: Set<String>, permitido: (Canal) -> Boolean = { true }): Canal? {
        val chave = base(atual.nome)
        if (chave.isEmpty()) return null
        val q = qualidade(atual.nome)
        return canais.asSequence()
            .filter { it.id != atual.id && it.url != atual.url && it.id !in tentados && it.url !in tentados }
            .filter { base(it.nome) == chave && permitido(it) }
            .sortedWith(compareBy<Canal>({ if (qualidade(it.nome) == q) 0 else 1 }, { qualidade(it.nome) }, { it.numero }))
            .firstOrNull()
    }
}
