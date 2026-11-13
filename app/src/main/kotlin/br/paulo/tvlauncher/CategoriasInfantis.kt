package br.paulo.tvlauncher

/**
 * Modo infantil (10/11/2026): a lista de categorias que a tela "Categorias infantis" mostra para marcar. Junta as categorias
 * dos TRÊS catálogos (TV ao vivo, Filmes e Séries) numa lista só, porque a marca vale pelo nome nas três telas
 * (ControleParental.grupoBloqueado). Categoria adulta não entra: nunca pode ser marcada como infantil.
 *
 * Só lógica pura (sem Android), testada em ControleParentalTest.
 */
object CategoriasInfantis {
    /** Uma categoria e quantos itens ela tem em cada catálogo. */
    data class Categoria(val nome: String, val tv: Int, val filmes: Int, val series: Int)

    /**
     * @param tv, filmes, series os nomes de categoria de cada item (Canal.grupo, Filme.grupo, Serie.grupo), com repetição
     * @return as categorias em ordem alfabética, sem as vazias ("Outros") e sem as adultas
     */
    fun juntar(tv: List<String>, filmes: List<String>, series: List<String>): List<Categoria> {
        val conta = HashMap<String, IntArray>()
        fun somar(lista: List<String>, i: Int) {
            for (bruto in lista) {
                val g = bruto.trim()
                if (g.isEmpty() || PlayerFiltro.ehAdulto(g)) continue
                conta.getOrPut(g) { IntArray(3) }[i]++
            }
        }
        somar(tv, 0); somar(filmes, 1); somar(series, 2)
        return conta.entries.sortedBy { it.key.lowercase() }.map { Categoria(it.key, it.value[0], it.value[1], it.value[2]) }
    }

    /** O texto da linha: "Desenhos  (TV 12 · Filmes 40)", só com os catálogos onde ela existe. */
    fun rotulo(c: Categoria): String {
        val partes = ArrayList<String>(3)
        if (c.tv > 0) partes += "TV ${c.tv}"
        if (c.filmes > 0) partes += "Filmes ${c.filmes}"
        if (c.series > 0) partes += "Séries ${c.series}"
        return "${PlayerLista.nomeExibido(c.nome)}  (${partes.joinToString(" · ")})"
    }
}
