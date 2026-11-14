package br.paulo.tvlauncher

/**
 * Ideia 42 (14/11/2026): os blocos (tiles) do painel rápido escolhidos por você, na ordem que você quiser. Objeto puro,
 * testado na JVM; quem grava é [PainelBlocosTv] e quem escolhe é a [PainelBlocosActivity].
 *
 * Decisão anterior que isto toca: o painel rápido foi redesenhado no molde do Google (13/11, PR #56) com 8 blocos fixos
 * em pares, mais Casa e Cenas quando existem. O padrão continua exatamente esse; escolher é opcional.
 */
object PainelBlocos {
    /** Todos os blocos, na ordem do catálogo. "filtro" e "dns" são novos (ideia 42) e nascem escondidos. */
    val TODOS = listOf("wifi", "bluetooth", "timer", "dormir", "protetor", "audio", "despertador", "visita", "casa", "cenas", "filtro", "dns")
    /** O painel de antes da ideia 42. */
    val PADRAO = TODOS.take(10)

    /** Lê a lista gravada ("wifi,timer,..."): ignora nome desconhecido e repetido. Nada gravado = [PADRAO]. */
    fun ler(gravado: String?): List<String> {
        if (gravado == null) return PADRAO
        return gravado.split(',').map { it.trim() }.filter { it in TODOS }.distinct()
    }

    fun gravar(lista: List<String>): String = lista.filter { it in TODOS }.distinct().joinToString(",")

    /** Mostra ou esconde [id]. Ao voltar a mostrar, ele entra no fim da lista (a ordem é a sua). */
    fun alternar(lista: List<String>, id: String): List<String> =
        if (id in lista) lista - id else if (id in TODOS) lista + id else lista

    /** Anda [id] uma posição para trás ([delta] = -1) ou para a frente (+1) entre os mostrados. */
    fun mover(lista: List<String>, id: String, delta: Int): List<String> {
        val i = lista.indexOf(id)
        val j = i + delta
        if (i < 0 || j < 0 || j >= lista.size) return lista
        return lista.toMutableList().also { it[i] = it[j]; it[j] = id }
    }

    /**
     * A ordem da tela de escolha: primeiro os mostrados (na ordem deles), depois os escondidos (na do catálogo).
     */
    fun ordemDaEscolha(lista: List<String>): List<String> = lista + TODOS.filter { it !in lista }

    /** Os que entram no painel agora: escolhidos e disponíveis (Casa só com Home Assistant, Cenas só com cena). */
    fun visiveis(lista: List<String>, disponivel: (String) -> Boolean): List<String> = lista.filter(disponivel)

    /** Em pares, como a grade do Google; um sozinho no fim ocupa a linha inteira. */
    fun linhas(visiveis: List<String>): List<List<String>> = visiveis.chunked(2)
}
