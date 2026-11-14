package br.paulo.tvlauncher

/**
 * Ajuda e manual (14/11/2026, pedido do dono): os assuntos da aba "Ajuda" das Configurações, na ordem de quem está
 * aprendendo (primeiro o controle e a tela inicial, por último o que é de manutenção). Objeto puro, testado na JVM
 * (ManualTest): cada assunto precisa de aj_<id>_titulo, aj_<id>_resumo e aj_<id>_texto em values/ e values-pt/.
 *
 * O texto mora nos recursos (res/values(-pt)/strings_ajuda.xml), não aqui, para seguir o idioma da TV como o resto do app.
 * Regra do conteúdo: só o que existe na main. Mudou um caminho de menu ou uma tecla, mude o texto junto.
 */
object Manual {
    val TOPICOS = listOf(
        "controle", "inicio", "painel", "voz", "clima", "protetor", "relogio", "tv", "filmes",
        "familia", "casa", "lg", "celular", "claude", "backup", "sistema",
    )

    /** As três partes de cada assunto, como sufixo do nome do recurso. */
    enum class Parte(val sufixo: String) { TITULO("titulo"), RESUMO("resumo"), TEXTO("texto") }

    /** Nome do recurso de [id] e [parte]: "aj_<id>_<parte>". */
    fun recurso(id: String, parte: Parte): String = "aj_${id}_${parte.sufixo}"

    /** O assunto vizinho de [i] ([delta] -1 = anterior, +1 = próximo), dando a volta nas pontas. */
    fun vizinho(i: Int, delta: Int): Int {
        val n = TOPICOS.size
        return (((i + delta) % n) + n) % n
    }
}
