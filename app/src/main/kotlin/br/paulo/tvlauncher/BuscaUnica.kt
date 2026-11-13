package br.paulo.tvlauncher

/**
 * Busca única da HOME e "Hoje na TV" (12/11/2026, ideias 2 e 8). Só a regra, pura e testada (BuscaUnicaTest); a tela é a
 * [BuscaActivity].
 *
 * Uma caixa só procura em tudo o que a TV já tem guardado: canais, programas do guia (próximas 24 h), filmes, séries e
 * apps. Nada é baixado na hora: sem cache de um catálogo, aquele grupo só fica vazio.
 *
 * "Hoje na TV" é o estado vazio da busca (a HOME não ganhou linha nova, para não mexer no desenho dela): programas de
 * agora até [JANELA_HOJE_MS] à frente que batem com o "Meu time" (as palavras dos lembretes), passam num canal favorito
 * ou num canal que a pessoa assistiu há pouco.
 */
object BuscaUnica {
    /** Extra das telas de Filmes e Séries: abrem já com este termo buscado. */
    const val EXTRA_TERMO = "termo_busca"
    const val MAX_POR_GRUPO = 8
    const val HORIZONTE_PROGRAMAS_MS = 24 * 3_600_000L
    const val JANELA_HOJE_MS = 3 * 3_600_000L
    const val MAX_HOJE = 10

    fun palavras(termo: String): List<String> = PlayerFiltro.normalizar(termo).split(' ').filter { it.isNotEmpty() }

    /**
     * Quão bem [nome] bate com as [palavras] (todas precisam aparecer). Menor = melhor; null = não bate.
     * 0 nome igual ao termo, 1 começa com o termo, 2 alguma palavra do nome começa com a 1ª do termo, 3 só contém.
     */
    fun pontos(nome: String, palavras: List<String>): Int? =
        if (palavras.isEmpty()) null else pontosNorm(PlayerFiltro.normalizar(nome), palavras)

    /** [pontos] com o nome já normalizado ([PlayerFiltro.normalizar]): a busca normaliza cada nome uma vez só. */
    fun pontosNorm(n: String, palavras: List<String>): Int? {
        if (palavras.isEmpty()) return null
        if (palavras.any { it !in n }) return null
        val t = palavras.joinToString(" ")
        return when {
            n == t -> 0
            n.startsWith(t) -> 1
            n.split(' ').any { it.startsWith(palavras[0]) } -> 2
            else -> 3
        }
    }

    /** Os que batem, do melhor para o pior (empate: nome mais curto primeiro), até [max]. */
    fun <T> filtrar(itens: Iterable<T>, termo: String, max: Int = MAX_POR_GRUPO, nome: (T) -> String): List<T> {
        val p = palavras(termo)
        if (p.isEmpty()) return emptyList()
        return itens.mapNotNull { i -> pontos(nome(i), p)?.let { Triple(i, it, nome(i).length) } }
            .sortedWith(compareBy({ it.second }, { it.third })).take(max).map { it.first }
    }

    /** Um item com o nome guardado já normalizado (feito uma vez ao abrir a busca, não a cada tecla). */
    class Indexado<T>(val item: T, val nome: String, val norm: String)

    fun <T> indexar(itens: Iterable<T>, nome: (T) -> String): List<Indexado<T>> =
        itens.map { val n = nome(it); Indexado(it, n, PlayerFiltro.normalizar(n)) }

    /** [filtrar] e [contar] de uma vez sobre o índice: (os melhores até [max], quantos batem ao todo). */
    fun <T> filtrarIndice(itens: List<Indexado<T>>, termo: String, max: Int = MAX_POR_GRUPO): Pair<List<T>, Int> {
        val p = palavras(termo)
        if (p.isEmpty()) return emptyList<T>() to 0
        val achados = itens.mapNotNull { i -> pontosNorm(i.norm, p)?.let { Triple(i.item, it, i.nome.length) } }
        return achados.sortedWith(compareBy({ it.second }, { it.third })).take(max).map { it.first } to achados.size
    }

    /**
     * O mesmo programa (título e horário iguais) passando em vários canais, como o "Globo Esporte" nas afiliadas
     * (achado na Sala, 12/11/2026: 8 linhas iguais): fica uma linha só, no canal de menor número, com quantos outros
     * canais passam. Ordem: por horário, depois pelo número do canal.
     */
    fun agrupar(itens: List<Lembretes.Candidato>): List<Pair<Lembretes.Candidato, Int>> {
        val grupos = LinkedHashMap<String, MutableList<Lembretes.Candidato>>()
        for (c in itens.sortedWith(compareBy({ it.programa.inicio }, { it.numero })))
            grupos.getOrPut("${PlayerFiltro.normalizar(c.programa.titulo)}|${c.programa.inicio}") { ArrayList() } += c
        return grupos.values.map { it.first() to it.size - 1 }
    }

    /** Quantos batem ao todo (para o "ver todos os N"). */
    fun <T> contar(itens: Iterable<T>, termo: String, nome: (T) -> String): Int {
        val p = palavras(termo)
        return if (p.isEmpty()) 0 else itens.count { pontos(nome(it), p) != null }
    }

    data class Sugestao(val canalId: String, val canalNome: String, val numero: Int, val programa: Programa,
                        val motivo: String, val pontos: Int)

    /**
     * Regra pura do "Hoje na TV". [itens] = programas do guia já ligados ao canal (o mesmo Candidato dos lembretes).
     * Pontos: Meu time 100, canal favorito 40, canal recente 30 (menos a posição na lista), passando agora +10.
     * Programa sem nenhum motivo fica de fora. O mesmo título no mesmo horário em dois canais aparece uma vez só.
     */
    fun hojeNaTv(itens: Sequence<Lembretes.Candidato>, agora: Long, meuTime: List<String>, favoritos: Set<String>,
                 recentes: List<String>, max: Int = MAX_HOJE): List<Sugestao> {
        val melhores = HashMap<String, Sugestao>()
        for (c in itens) {
            val p = c.programa
            if (p.fim <= agora || p.inicio > agora + JANELA_HOJE_MS || p.titulo.isBlank()) continue
            var pts = 0
            var motivo = ""
            fun soma(v: Int, m: String) { pts += v; if (motivo.isEmpty()) motivo = m }
            if (meuTime.any { Lembretes.casa(p.titulo, it) }) soma(100, "Meu time")
            if (c.canalId in favoritos) soma(40, "Canal favorito")
            val r = recentes.indexOf(c.canalId)
            if (r >= 0) soma(30 - r.coerceAtMost(20), "Você assistiu")
            if (pts == 0) continue
            if (p.inicio <= agora) pts += 10
            val s = Sugestao(c.canalId, c.canalNome, c.numero, p, motivo, pts)
            val chave = "${Lembretes.normalizar(p.titulo)}|${p.inicio}"
            val antes = melhores[chave]
            if (antes == null || s.pontos > antes.pontos || (s.pontos == antes.pontos && s.numero < antes.numero)) melhores[chave] = s
        }
        return melhores.values.sortedWith(compareByDescending<Sugestao> { it.pontos }.thenBy { it.programa.inicio }.thenBy { it.numero })
            .take(max)
    }
}
