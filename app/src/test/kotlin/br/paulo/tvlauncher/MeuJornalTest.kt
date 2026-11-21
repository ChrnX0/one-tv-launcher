package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.MeuJornal
import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.Podcasts
import org.junit.Assert.assertEquals
import org.junit.Test

/** Meu jornal: o mais novo de cada podcast de notícias, das últimas 36 h, nunca começado. */
class MeuJornalTest {
    private val agora = 1_000L * 3_600_000L
    private fun ep(guid: String, horasAtras: Int, explicito: Boolean = false) =
        PodcastFeed.Episodio(guid, guid, "", agora - horasAtras * 3_600_000L, 600, "https://x/$guid.mp3", "audio/mpeg", 0, "", explicito, 0, 0, emptyList(), "")
    private fun feed(cat: String, vararg e: PodcastFeed.Episodio) = PodcastFeed.Feed("F", "", "", "", "", "", false, e.toList(), categorias = listOf(cat))

    @Test fun montar() {
        val feeds = mapOf(
            "a" to feed("News", ep("a1", 2), ep("a2", 1)),          // o mais novo: a2
            "b" to feed("Daily News", ep("b1", 30)),
            "c" to feed("Comedy", ep("c1", 1)),                    // não é notícia
            "d" to feed("Politics", ep("d1", 50)),                 // velho demais
            "e" to feed("News", ep("e1", 3, explicito = true)),
            "f" to feed("News", ep("f1", 4)),                      // já começado
        )
        val pos = mapOf(Podcasts.chave("f", "f1") to Podcasts.Posicao("f", "f1", 5000, 600_000, false, agora, "f1", "F"))
        assertEquals(listOf("a2", "e1", "b1"), MeuJornal.montar(feeds, pos, agora, protegido = false).map { it.second.guid })
        assertEquals(listOf("a2", "b1"), MeuJornal.montar(feeds, pos, agora, protegido = true).map { it.second.guid })
    }
}
