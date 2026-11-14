package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.TimeZone

/** Próximo jogo do "Meu time" na cápsula de avisos da HOME e no protetor (ideia 12). */
class ProximoJogoTest {
    private val h = 3_600_000L
    private val agora = 1_763_000_000_000L
    private val palavras = listOf("Flamengo", "Fórmula 1")

    private fun jogo(titulo: String, emH: Double, palavra: String = "Flamengo", duracaoH: Double = 2.0) =
        ProximoJogo.Item(titulo, agora + (emH * h).toLong(), agora + ((emH + duracaoH) * h).toLong(), palavra)

    @Test fun escolheOMaisProximoDentroDe24h() {
        val a = jogo("Flamengo x Palmeiras", 5.0)
        val b = jogo("Fórmula 1: GP de São Paulo", 3.0, "Fórmula 1")
        val c = jogo("Vasco x Flamengo", 30.0)
        assertEquals(b, ProximoJogo.escolher(listOf(a, c, b), palavras, agora))
        assertEquals(a, ProximoJogo.escolher(listOf(a, c), palavras, agora))
        assertNull("depois de 24 h não entra", ProximoJogo.escolher(listOf(c), palavras, agora))
    }

    @Test fun jogoEmAndamentoContaJaAcabadoNao() {
        val emAndamento = jogo("Flamengo x Palmeiras", -1.0)
        val acabou = jogo("Flamengo x Santos", -3.0)
        assertEquals(emAndamento, ProximoJogo.escolher(listOf(acabou, emAndamento), palavras, agora))
        assertNull(ProximoJogo.escolher(listOf(acabou), palavras, agora))
    }

    @Test fun soComPalavraDoMeuTime() {
        val manual = jogo("Flamengo x Palmeiras", 2.0, palavra = "")
        assertNull("lembrete marcado à mão não entra", ProximoJogo.escolher(listOf(manual), palavras, agora))
        val auto = jogo("Flamengo x Palmeiras", 2.0)
        assertNull("sem palavras salvas, nada", ProximoJogo.escolher(listOf(auto), emptyList(), agora))
        assertNull("palavra apagada no painel some na hora", ProximoJogo.escolher(listOf(auto), listOf("Palmeiras"), agora))
        // A palavra salva com outro acento/caixa ainda vale.
        assertEquals(auto, ProximoJogo.escolher(listOf(auto), listOf("flamengo"), agora))
    }

    @Test fun textoCurtoNoFusoDaTv() {
        val utc = TimeZone.getTimeZone("UTC")
        val inicio = 1_700_000_000_000L - 1_700_000_000_000L % (24 * h) + 21 * h + 30 * 60_000L   // 21:30 UTC
        val i = ProximoJogo.Item("Flamengo x Palmeiras", inicio, inicio + 2 * h, "Flamengo")
        assertEquals("Flamengo × Palmeiras · 21:30", ProximoJogo.texto(i, utc))
        assertEquals("Flamengo × Palmeiras · 18:30", ProximoJogo.texto(i, TimeZone.getTimeZone("GMT-03:00")))
    }

    @Test fun tituloLimpo() {
        assertEquals("Flamengo × Palmeiras", ProximoJogo.titulo("Flamengo x Palmeiras"))
        assertEquals("Flamengo × Palmeiras", ProximoJogo.titulo("Brasileirão: Flamengo vs Palmeiras"))
        assertEquals("Flamengo × Palmeiras", ProximoJogo.titulo("Flamengo  X  Palmeiras"))
        // Sem "×" depois dos dois-pontos, fica o título inteiro.
        assertEquals("Fórmula 1: GP de São Paulo", ProximoJogo.titulo("Fórmula 1: GP de São Paulo"))
        val longo = ProximoJogo.titulo("Campeonato: Clube de Regatas do Flamengo x Sociedade Esportiva Palmeiras")
        assertEquals(ProximoJogo.MAX_TITULO, longo.length)
        assertEquals('…', longo.last())
    }
}
