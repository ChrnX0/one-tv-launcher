package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.MidiaRecente
import br.paulo.tvlauncher.home.MidiaRecente.Registro
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MidiaRecenteTest {
    private fun r(p: String, q: Long, t: String = "T $p") = Registro(p, t, "", 0, 0, q)

    @Test fun juntarPoeNoTopoTiraORepetidoECorta() {
        var l = emptyList<Registro>()
        for (i in 1..8) l = MidiaRecente.juntar(l, r("app$i", i.toLong()))
        assertEquals(MidiaRecente.MAX, l.size)
        assertEquals("app8", l.first().pacote)
        l = MidiaRecente.juntar(l, r("app5", 99, "Outro"))
        assertEquals(listOf("app5", "app8", "app7", "app6", "app4", "app3"), l.map { it.pacote })
        assertEquals("Outro", l.first().titulo)
    }

    @Test fun tituloUtilNaoAceitaVazioNemONomeDoApp() {
        assertTrue(MidiaRecente.tituloUtil("Stranger Things", "Netflix"))
        assertFalse(MidiaRecente.tituloUtil("  ", "Netflix"))
        assertFalse(MidiaRecente.tituloUtil(null, "Netflix"))
        assertFalse(MidiaRecente.tituloUtil(" netflix ", "Netflix"))
    }

    @Test fun validosTiraOVelhoEOrdena() {
        val agora = 100L * 24 * 3600 * 1000
        val l = listOf(r("a", agora - 1000), r("b", agora - MidiaRecente.VALIDADE_MS - 1), r("c", agora - 10))
        assertEquals(listOf("c", "a"), MidiaRecente.validos(l, agora).map { it.pacote })
        assertTrue(MidiaRecente.validos(listOf(r("futuro", agora + 5000)), agora).isEmpty())   // relógio voltou: não confia
    }

    @Test fun progressoEPosicao() {
        assertNull(MidiaRecente.progresso(Registro("a", "t", "", 1000, 0, 1)))
        assertEquals(0.25f, MidiaRecente.progresso(Registro("a", "t", "", 250, 1000, 1))!!, 0.001f)
        assertEquals(1f, MidiaRecente.progresso(Registro("a", "t", "", 5000, 1000, 1))!!, 0.001f)
        assertEquals(1000L, MidiaRecente.posicaoAgora(1000, 50, 1f, tocando = false, agoraElapsed = 9999))
        assertEquals(3000L, MidiaRecente.posicaoAgora(1000, 1000, 1f, tocando = true, agoraElapsed = 3000))
        assertEquals(5000L, MidiaRecente.posicaoAgora(1000, 1000, 2f, tocando = true, agoraElapsed = 3000))
    }

    @Test fun jsonIdaEVolta() {
        val l = listOf(Registro("a.b", "Título", "Artista", 10, 20, 30))
        assertEquals(l, MidiaRecente.deJson(MidiaRecente.paraJson(l)))
        assertTrue(MidiaRecente.deJson("lixo").isEmpty())
    }
}
