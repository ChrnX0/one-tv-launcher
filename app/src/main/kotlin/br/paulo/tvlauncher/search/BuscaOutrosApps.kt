package br.paulo.tvlauncher.search

import br.paulo.tvlauncher.BuscaActivity

/**
 * "Busca que continua nos outros apps" (ideia 14, 14/11/2026). No fim do resultado da busca única ([BuscaActivity]),
 * uma linha por app de vídeo instalado: "Procurar “termo” no YouTube", na Netflix, no Prime Video.
 *
 * Caminho padrão do Android: Intent.ACTION_SEARCH com o termo em SearchManager.QUERY e o pacote fixo. Se o app não
 * atender essa busca (resolveActivity null), a tela abre o app (fallback). Os nomes das constantes ficam aqui como texto
 * para o teste JVM não depender do android.jar; a tela confere com as do Android.
 *
 * Puro: a tabela de pacotes e quais linhas aparecem. Textos visíveis ficam nas strings (chave [Destino.chave]).
 */
object BuscaOutrosApps {
    /** Intent.ACTION_SEARCH. */
    const val ACAO = "android.intent.action.SEARCH"
    /** SearchManager.QUERY. */
    const val EXTRA_TERMO = "query"
    /** Termo mais comprido que isto é cortado (ninguém digita isso no controle; evita mandar texto colado enorme). */
    const val MAX_TERMO = 100

    /**
     * A busca do Google TV (16/11/2026, no lugar do "Onde assistir" da JustWatch): mostra em quais serviços o título está,
     * sem dado de terceiros no nosso app. Quem atende é o app de busca do Google TV (katniss), pela busca global do
     * Android (SearchManager.INTENT_ACTION_GLOBAL_SEARCH; conferido na Sala: é o único que atende essa ação).
     */
    const val ACAO_GLOBAL = "android.search.action.GLOBAL_SEARCH"
    const val PACOTE_GOOGLE_TV = "com.google.android.katniss"

    /** Um app que recebe a busca. [chave] escolhe a frase traduzida ("no YouTube", "na Netflix"...). */
    data class Destino(val chave: String, val pacote: String, val acao: String = ACAO)

    /** Na ordem em que aparecem. Pacotes das versões de TV (o YouTube de TV não é com.google.android.youtube). */
    val DESTINOS = listOf(
        Destino("googletv", PACOTE_GOOGLE_TV, ACAO_GLOBAL),
        Destino("youtube", "com.google.android.youtube.tv"),
        Destino("netflix", "com.netflix.ninja"),
        Destino("prime", "com.amazon.amazonvideo.livingroom"),
    )

    /** O que a tela precisa para montar a Intent de um destino. */
    data class Pedido(val destino: Destino, val termo: String, val acao: String = ACAO, val extra: String = EXTRA_TERMO)

    /** O termo como vai para o outro app: sem espaços sobrando; null se curto demais (menos de 2 letras). */
    fun limpar(termo: String): String? =
        termo.trim().replace(Regex("\\s+"), " ").take(MAX_TERMO).trim().takeIf { it.length >= 2 }

    /** As linhas a acrescentar: uma por destino instalado ([instalados] = pacotes), na ordem de [DESTINOS]. */
    fun montar(termo: String, instalados: Set<String>): List<Pedido> {
        val t = limpar(termo) ?: return emptyList()
        return DESTINOS.filter { it.pacote in instalados }.map { Pedido(it, t, it.acao) }
    }

    /**
     * "Onde assistir" da ficha: a busca do Google TV com o nome do título (e o ano, que separa refilmagens). null sem o
     * Google TV instalado (a ficha não mostra o botão).
     */
    fun ondeAssistir(nome: String, ano: Int?, instalados: Set<String>): Pedido? {
        if (PACOTE_GOOGLE_TV !in instalados) return null
        val t = limpar(listOfNotNull(nome, ano?.toString()).joinToString(" ")) ?: return null
        return Pedido(DESTINOS.first { it.chave == "googletv" }, t, ACAO_GLOBAL)
    }
}
