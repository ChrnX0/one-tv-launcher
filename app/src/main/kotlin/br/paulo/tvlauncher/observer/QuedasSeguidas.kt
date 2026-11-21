package br.paulo.tvlauncher.observer

/**
 * Modo de segurança da HOME (degrau 2, 16/11/2026): as REGRAS, sem Android, para o teste JVM (QuedasSeguidasTest).
 * Quem grava o arquivo e abre a HOME mínima é o ModoSeguro.kt.
 *
 * O que já existia (VigiaRegras): depois de atualizar, a tela que cai 2 vezes deixa de abrir. Mas a HOME nunca é
 * bloqueada ali (sem ela a TV fica sem tela inicial). Se é a HOME que cai em laço, a pessoa fica olhando para "o app
 * parou" sem saída. Esta regra cobre esse buraco:
 *
 *  - uma queda só conta se a HOME ainda não ficou estável neste processo ([ESTAVEL_MS] aberta sem cair);
 *  - [QUEDAS] quedas assim dentro de [JANELA_MS] = modo de segurança LIGADO para esta versão: no lugar da HOME abre
 *    uma tela mínima (lista de apps, procurar versão corrigida, Configurações do Android, tentar de novo);
 *  - a HOME ficou estável = zera a conta; versão nova instalada = o modo sai sozinho (a correção chegou);
 *  - "Tentar a tela normal" zera tudo. Se cair de novo, precisa de mais 3 quedas para voltar ao modo.
 */
object QuedasSeguidas {
    const val QUEDAS = 3
    const val JANELA_MS = 15 * 60_000L
    const val ESTAVEL_MS = 30_000L
    private const val MAX_GUARDADAS = 10

    data class Estado(val quedas: List<Long> = emptyList(), val seguroVersao: Long = 0L)

    /** O modo de segurança vale para a versão [versao]? (Outra versão instalada = não vale mais.) */
    fun ativo(e: Estado, versao: Long) = e.seguroVersao != 0L && e.seguroVersao == versao

    /**
     * O processo caiu. [homeEstavel] = a HOME já tinha ficado [ESTAVEL_MS] aberta neste processo: essa queda não é laço
     * de abertura (é um defeito de outra tela, que o VigiaRegras já cuida), então não conta e zera a sequência.
     */
    fun aoCair(e: Estado, versao: Long, agora: Long, homeEstavel: Boolean): Estado {
        if (homeEstavel) return e.copy(quedas = emptyList())
        val recentes = (e.quedas.filter { agora - it in 0..JANELA_MS } + agora).takeLast(MAX_GUARDADAS)
        return if (recentes.size >= QUEDAS) Estado(quedas = emptyList(), seguroVersao = versao)
        else e.copy(quedas = recentes)
    }

    /** A HOME ficou estável: as quedas de antes não eram laço. (O modo, se ligado, só sai pela pessoa ou versão nova.) */
    fun aoEstabilizar(e: Estado): Estado = if (e.quedas.isEmpty()) e else e.copy(quedas = emptyList())

    /** "Tentar a tela inicial normal": tudo do zero. */
    fun sair(): Estado = Estado()

    // ------------------------------------------------------------------ arquivo (uma linha: "seguro=<v>;q=<t1>,<t2>")

    fun texto(e: Estado): String = "seguro=${e.seguroVersao};q=${e.quedas.joinToString(",")}"

    fun ler(texto: String?): Estado {
        if (texto.isNullOrBlank()) return Estado()
        var seguro = 0L
        var quedas = emptyList<Long>()
        for (parte in texto.trim().split(';')) {
            val (k, v) = parte.split('=', limit = 2).let { if (it.size == 2) it[0] to it[1] else return@let null } ?: continue
            when (k) {
                "seguro" -> seguro = v.toLongOrNull() ?: 0L
                "q" -> quedas = v.split(',').mapNotNull { it.trim().toLongOrNull() }.takeLast(MAX_GUARDADAS)
            }
        }
        return Estado(quedas, seguro)
    }
}
