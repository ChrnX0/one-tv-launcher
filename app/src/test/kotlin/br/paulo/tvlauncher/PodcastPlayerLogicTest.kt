package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.PodcastPlayerLogic
import br.paulo.tvlauncher.player.Episodio
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** As contas do player de podcast em tela cheia ([PodcastPlayerLogic]). */
class PodcastPlayerLogicTest {
    private fun ep(guid: String, video: Boolean = false) = PodcastFeed.Episodio(guid, "t$guid", "", 0, 60,
        if (video) "https://x/$guid.mp4" else "https://x/$guid.mp3", if (video) "video/mp4" else "audio/mpeg", 0, "", false, 0, 0, emptyList(), "")

    // O feed vem do mais novo para o mais antigo.
    private val feed = listOf(ep("e5"), ep("e4"), ep("e3"), ep("e2"), ep("e1"))

    @Test fun aSeguirVemOMaisNovoPertoDepoisOsAntigos() {
        val l = PodcastPlayerLogic.aSeguir(feed, "e3", { false })
        assertEquals(listOf("e4", "e5", "e2", "e1"), l.map { it.guid })
    }

    @Test fun aSeguirPulaOuvidosVideosEOAtual() {
        val f = listOf(ep("e5"), ep("e4", video = true), ep("e3"), ep("e2"), ep("e1"))
        val l = PodcastPlayerLogic.aSeguir(f, "e3", { it.guid == "e5" })
        assertEquals(listOf("e2", "e1"), l.map { it.guid })
    }

    @Test fun aSeguirSemOAtualNaListaDaOsNaoOuvidosDoMaisNovo() {
        val l = PodcastPlayerLogic.aSeguir(feed, "sumiu", { it.guid == "e4" }, max = 2)
        assertEquals(listOf("e5", "e3"), l.map { it.guid })
    }

    @Test fun aSeguirRespeitaOMaximo() {
        assertEquals(2, PodcastPlayerLogic.aSeguir(feed, "e1", { false }, max = 2).size)
        assertEquals(emptyList<PodcastFeed.Episodio>(), PodcastPlayerLogic.aSeguir(emptyList(), "e1", { false }))
    }

    @Test fun terminaContaAVelocidade() {
        assertEquals(1_000L + 600_000L, PodcastPlayerLogic.terminaEm(1_000L, 0L, 600_000L, 1f))
        assertEquals(1_000L + 300_000L, PodcastPlayerLogic.terminaEm(1_000L, 0L, 600_000L, 2f))
        assertNull(PodcastPlayerLogic.terminaEm(1_000L, 0L, 0L, 1f))
        assertNull(PodcastPlayerLogic.terminaEm(1_000L, 600_000L, 600_000L, 1f))
    }

    @Test fun tempos() {
        assertEquals("0:00", PodcastPlayerLogic.tempo(-5))
        assertEquals("4:05", PodcastPlayerLogic.tempo(245_000))
        assertEquals("1:02:03", PodcastPlayerLogic.tempo(3_723_000))
        assertEquals("−33:30", PodcastPlayerLogic.falta(0, 2_010_000, 1f))
        assertEquals("−16:45", PodcastPlayerLogic.falta(0, 2_010_000, 2f))
        assertEquals("", PodcastPlayerLogic.falta(0, 0, 1f))
    }

    @Test fun rotuloDaVelocidade() {
        val pt = Locale.forLanguageTag("pt-BR")
        assertEquals("1x", PodcastPlayerLogic.rotuloVelocidade(1f, pt))
        assertEquals("1,2x", PodcastPlayerLogic.rotuloVelocidade(1.2f, pt))
        assertEquals("1,75x", PodcastPlayerLogic.rotuloVelocidade(1.75f, pt))
        assertEquals("0.8x", PodcastPlayerLogic.rotuloVelocidade(0.8f, Locale.US))
        assertEquals("2x", PodcastPlayerLogic.rotuloVelocidade(2f, Locale.US))
    }

    @Test fun andarNaBarraNaoPassaDasPontas() {
        assertEquals(0L, PodcastPlayerLogic.andar(5_000, -10_000, 60_000))
        assertEquals(59_000L, PodcastPlayerLogic.andar(55_000, 10_000, 60_000))
        assertEquals(30_000L, PodcastPlayerLogic.andar(20_000, 10_000, 60_000))
        assertEquals(30_000L, PodcastPlayerLogic.andar(20_000, 10_000, 0))   // sem duração: só não volta do zero
    }

    @Test fun minutosDoTimer() {
        assertEquals(0, PodcastPlayerLogic.minutosParaDormir(0, 1_000))
        assertEquals(0, PodcastPlayerLogic.minutosParaDormir(1_000, 1_000))
        assertEquals(1, PodcastPlayerLogic.minutosParaDormir(1_001, 1_000))
        assertEquals(15, PodcastPlayerLogic.minutosParaDormir(15 * 60_000L + 1_000, 1_000))
        assertEquals(listOf(0, 15, 30, 45, 60, 90), PodcastPlayerLogic.DORMIR_MIN)
    }
}
