package br.paulo.tvlauncher.listen

/**
 * "Meu jornal" (proposta C do Ouvir, 21/11/2026): o noticiário do dia montado com os podcasts de notícias que a casa segue.
 *
 * Por quê: o Spotify e o Apple têm a fila "notícias do dia"; quem segue 3 ou 4 programas de notícia abria um por um.
 * Para quê: um OK e a TV toca, em sequência, o episódio mais novo de cada podcast de notícias, como um rádio-jornal.
 * Como: das assinaturas com assunto Notícias ([PodcastAssuntos]), o episódio mais novo de cada uma, das últimas
 * [HORAS] horas e nunca começado; os mais novos primeiro. Lógica pura (MeuJornalTest); quem toca a fila é o OuvirServico.
 */
object MeuJornal {
    const val HORAS = 36
    const val MAX = 8

    fun montar(feeds: Map<String, PodcastFeed.Feed>, posicoes: Map<String, Podcasts.Posicao>, agoraMs: Long,
               protegido: Boolean): List<Pair<String, PodcastFeed.Episodio>> {
        val desde = agoraMs - HORAS * 3_600_000L
        return feeds.mapNotNull { (url, f) ->
            if (PodcastAssuntos.Assunto.NOTICIAS !in PodcastAssuntos.de(f.categorias)) return@mapNotNull null
            f.episodios.filter {
                it.dataMs in desde..(agoraMs + 3_600_000L) && !it.video && it.url.isNotEmpty() &&
                    posicoes[Podcasts.chave(url, it.guid)] == null && Podcasts.visivel(it, protegido)
            }.maxByOrNull { it.dataMs }?.let { url to it }
        }.sortedByDescending { it.second.dataMs }.take(MAX)
    }
}
