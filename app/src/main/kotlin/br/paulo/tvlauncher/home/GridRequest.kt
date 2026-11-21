package br.paulo.tvlauncher.home

import br.paulo.tvlauncher.CalculoTela

/**
 * Junta os pedidos de conta da grade de Filmes e Séries que a [CalculoTela] descarta (18/11/2026).
 *
 * Visto na Sala (v463338): Filmes abriu com "Todos 0", grade vazia e sem foco. A 1ª conta (grade NOVA, categoria de
 * entrada, foco na grade) foi passada para trás em milissegundos pela conta das listas de fora (Trakt/MDBList), que só
 * pedia para refazer a coluna (grade IGUAL). A [CalculoTela] entrega só o pedido mais novo, então a grade nunca veio.
 *
 * Regra: enquanto um pedido não chega à tela, o seguinte herda o que ele exigia. A grade mais forte vence
 * (nova > manter o lugar > igual), "inicial" continua valendo e as ações de depois rodam as duas, na ordem.
 */
object GridRequest {
    /** [strength]: 2 = grade nova, 1 = refazer mantendo o lugar, 0 = grade igual (só a coluna). */
    class Need(val strength: Int, val initial: Boolean, val after: (() -> Unit)?)

    fun merge(pending: Need?, next: Need): Need =
        if (pending == null) next
        else Need(maxOf(pending.strength, next.strength), pending.initial || next.initial, chain(pending.after, next.after))

    private fun chain(a: (() -> Unit)?, b: (() -> Unit)?): (() -> Unit)? = when {
        a == null -> b
        b == null -> a
        else -> { { a(); b() } }
    }
}
