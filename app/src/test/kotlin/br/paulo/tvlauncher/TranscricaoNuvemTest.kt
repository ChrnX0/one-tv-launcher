package br.paulo.tvlauncher

import br.paulo.tvlauncher.listen.PodcastFeed
import br.paulo.tvlauncher.listen.Transcricao
import br.paulo.tvlauncher.listen.TranscricaoNuvem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Texto gerado na nuvem: pedaços, tempos, respostas, cota e propaganda. */
class TranscricaoNuvemTest {
    private fun ep(tipo: String, url: String) = PodcastFeed.Episodio("g", "t", "", 0, 0, url, tipo, 0, "", false, 0, 0, emptyList(), "")

    @Test fun podeGerar() {
        assertTrue(TranscricaoNuvem.podeGerar(ep("audio/mpeg", "https://x/a")))
        assertTrue(TranscricaoNuvem.podeGerar(ep("", "https://x/a.MP3?x=1")))
        assertFalse(TranscricaoNuvem.podeGerar(ep("audio/x-m4a", "https://x/a.m4a")))
        assertFalse(TranscricaoNuvem.podeGerar(ep("video/mp4", "https://x/a.mp4")))
    }

    @Test fun ordemETempo() {
        val p = TranscricaoNuvem.PEDACO
        assertEquals(listOf(2, 3, 0, 1), TranscricaoNuvem.ordem(p * 4 - 10, p * 2 + 5))
        assertEquals(listOf(0), TranscricaoNuvem.ordem(100, 0))
        assertTrue(TranscricaoNuvem.ordem(0, 0).isEmpty())
        assertEquals(30_000L, TranscricaoNuvem.msDe(500, 1000, 60_000))
        assertEquals(500L, TranscricaoNuvem.byteDe(30_000, 1000, 60_000))
    }

    @Test fun lerResposta() {
        val l = TranscricaoNuvem.ler("""{"segments":[{"start":1.5,"end":3,"text":" Oi "},{"start":4,"end":5,"text":""}]}""", 60_000)
        assertEquals(listOf(Transcricao.Trecho(61_500, 63_000, "Oi")), l)
        assertTrue(TranscricaoNuvem.ler("lixo", 0).isEmpty())
    }

    @Test fun unirEResumo() {
        val a = listOf(Transcricao.Trecho(5_000, 6_000, "b"))
        val b = listOf(Transcricao.Trecho(1_000, 2_000, "a"), Transcricao.Trecho(5_000, 6_000, "b"))
        assertEquals(listOf("a", "b"), Transcricao.unir(a, b).map { it.texto })
        assertEquals("1: a\n5: b\n", TranscricaoNuvem.textoParaResumo(Transcricao.unir(a, b)))
        assertEquals("1: a\n", TranscricaoNuvem.textoParaResumo(b, max = 7))
        val r = TranscricaoNuvem.lerResumo("""{"summary":"Fala de X.","ads":[[10,40]]}""")!!
        assertEquals("Fala de X.", r.first); assertEquals(listOf(10_000L..40_000L), r.second)
        assertNull(TranscricaoNuvem.lerResumo("x"))
    }

    @Test fun cotaEPropaganda() {
        assertEquals(TranscricaoNuvem.MAX_MIN_DIA.toFloat(), TranscricaoNuvem.restante("2026-11-20", 100f, "2026-11-21"))
        assertEquals(TranscricaoNuvem.MAX_MIN_DIA - 30f, TranscricaoNuvem.restante("2026-11-21", 30f, "2026-11-21"))
        val ads = listOf(10_000L..40_000L)
        assertEquals(ads[0], TranscricaoNuvem.propagandaEm(ads, 15_000, emptySet()))
        assertNull(TranscricaoNuvem.propagandaEm(ads, 15_000, setOf(10_000L)))   // já pulada: ◀ volta e não pula de novo
        assertNull(TranscricaoNuvem.propagandaEm(ads, 50_000, emptySet()))
    }
}
