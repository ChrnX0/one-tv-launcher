package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.SubtitleSync
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleSyncTest {
    /** Um "filme" de 10 min com falas de 1 a 4 s e silêncios de 0,5 a 6 s (semente fixa: o teste é sempre o mesmo). */
    private fun falasReais(semente: Int = 7): List<SubtitleSync.Fala> {
        val r = Random(semente)
        val l = mutableListOf<SubtitleSync.Fala>()
        var t = 5_000L
        while (t < 600_000L) {
            val dur = 1_000L + r.nextLong(3_000)
            l += SubtitleSync.Fala(t, t + dur)
            t += dur + 500 + r.nextLong(5_500)
        }
        return l
    }

    /** O que o detector de fala "ouviu", com 10% das janelas trocadas (música, efeito, erro do detector). */
    private fun ouvido(falas: List<SubtitleSync.Fala>, duracao: Long = 600_000L, ruido: Double = 0.10): BooleanArray {
        val r = Random(3)
        val n = (duracao / SubtitleSync.PASSO_MS).toInt()
        return BooleanArray(n) { i ->
            val t = i * SubtitleSync.PASSO_MS
            val fala = falas.any { t >= it.inicioMs && t < it.fimMs }
            if (r.nextDouble() < ruido) !fala else fala
        }
    }

    /** A conta anda em janelas de 100 ms: acertar com até uma janela de diferença é acertar (ninguém nota 0,1 s). */
    private fun perto(esperado: Long, r: SubtitleSync.Resultado) =
        assertTrue("esperado ~$esperado, veio ${r.atrasoMs}", kotlin.math.abs(r.atrasoMs - esperado) <= SubtitleSync.PASSO_MS)

    private fun deslocar(l: List<SubtitleSync.Fala>, ms: Long) = l.map { SubtitleSync.Fala(it.inicioMs + ms, it.fimMs + ms) }

    @Test fun legendaAdiantadaVoltaAoLugar() {
        val reais = falasReais()
        // A legenda aparece 2,5 s antes da fala: tem de atrasar 2,5 s.
        val r = SubtitleSync.ajustar(ouvido(reais), 0, deslocar(reais, -2_500))!!
        perto(2_500L, r)
        assertTrue(SubtitleSync.aplicar(r))
    }

    @Test fun legendaAtrasadaVoltaAoLugar() {
        val reais = falasReais()
        val r = SubtitleSync.ajustar(ouvido(reais), 0, deslocar(reais, 7_300))!!
        perto(-7_300L, r)
    }

    @Test fun legendaCertaNaoMexe() {
        val reais = falasReais()
        val r = SubtitleSync.ajustar(ouvido(reais), 0, reais)
        perto(0L, r!!)
        assertFalse(SubtitleSync.aplicar(r))
    }

    @Test fun legendaDeOutroFilmeNaoInventaAjuste() {
        // Legenda que não tem nada a ver com o áudio (outra semente): sem destaque, não propõe nada.
        assertNull(SubtitleSync.ajustar(ouvido(falasReais(7)), 0, falasReais(99)))
    }

    @Test fun poucaFalaOuvidaEspera() {
        val reais = falasReais().filter { it.inicioMs < 40_000 }
        assertNull(SubtitleSync.ajustar(ouvido(reais, duracao = 40_000, ruido = 0.0), 0, reais))
    }

    @Test fun trechoNoMeioDoFilme() {
        // Começou a ouvir aos 5 min: as janelas contam a partir dali.
        val reais = falasReais()
        val ouvidoTodo = ouvido(reais)
        val desde = (300_000L / SubtitleSync.PASSO_MS).toInt()
        val r = SubtitleSync.ajustar(ouvidoTodo.copyOfRange(desde, ouvidoTodo.size), 300_000L, deslocar(reais, -1_200))!!
        perto(1_200L, r)
    }

    @Test fun leAsFalasDoSrt() {
        val srt = "1\n00:00:01,500 --> 00:00:03,000\nOi\n\n2\n01:02:03.4 --> 01:02:05,000\nTchau\n\n3\n00:00:09,000 --> 00:00:08,000\nquebrada\n"
        assertEquals(listOf(SubtitleSync.Fala(1_500, 3_000), SubtitleSync.Fala(3_723_400, 3_725_000)), SubtitleSync.falasDoSrt(srt))
    }

    @Test fun marcaFalaAcimaDoPiso() {
        // Ambiente a -50 dB, fala a -30 dB, janelas não ouvidas (NaN) nunca são fala.
        val e = FloatArray(100) { if (it % 4 == 0) -30f else -50f }.also { it[1] = Float.NaN }
        val f = SubtitleSync.marcarFala(e)
        assertTrue(f[0]); assertFalse(f[1]); assertFalse(f[2]); assertTrue(f[96])
        assertEquals(25, f.count { it })
    }

    @Test fun doSomAteOAjuste() {
        // O caminho todo: energia por janela -> fala -> ajuste, com a legenda 3 s adiantada.
        val reais = falasReais()
        val r = Random(5)
        val e = FloatArray(6_000) { i ->
            val t = i * SubtitleSync.PASSO_MS
            (if (reais.any { t >= it.inicioMs && t < it.fimMs }) -28f else -48f) + (r.nextFloat() - 0.5f) * 10f
        }
        perto(3_000L, SubtitleSync.ajustar(SubtitleSync.marcarFala(e), 0, deslocar(reais, -3_000))!!)
    }
}
