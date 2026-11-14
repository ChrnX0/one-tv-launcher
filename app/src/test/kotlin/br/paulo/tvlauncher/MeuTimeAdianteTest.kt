package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "Meu time" com alcance de dias (ideia 16): o que entra na lista à parte, o teto e a leitura de volta. Teste puro. */
class MeuTimeAdianteTest {
    private val h = 3_600_000L
    private val agora = 1_800_000_000_000L
    private val dia = 24 * h

    @Test fun casaPalavraInteiraSemAcento() {
        assertTrue(MeuTimeAdiante.casa("Palmeiras x Santos", "palmeiras"))
        assertTrue(MeuTimeAdiante.casa("GP de São Paulo de Fórmula 1", "formula 1"))
        assertFalse(MeuTimeAdiante.casa("Palmeirense na TV", "palmeiras"))
        assertFalse(MeuTimeAdiante.casa("Fórmula 10 anos", "formula 1"))
        assertFalse(MeuTimeAdiante.casa("Flamengo", "fl"))
    }

    @Test fun coletorGuardaSoOQueCasaDentroDosSeteDias() {
        val c = MeuTimeAdiante.Coletor(listOf("Flamengo"), agora)
        assertTrue(c.oferecer("sportv", agora + 3 * dia, agora + 3 * dia + 2 * h, "Flamengo x Vasco"))
        assertFalse(c.oferecer("sportv", agora + 3 * dia, agora + 3 * dia + 2 * h, "Flamengo x Vasco"))  // repetido
        assertFalse(c.oferecer("globo", agora + 2 * dia, agora + 2 * dia + h, "Novela"))               // não casa
        assertFalse(c.oferecer("sportv", agora + 8 * dia, agora + 8 * dia + h, "Flamengo x Bahia"))    // passa de 7 dias
        assertFalse(c.oferecer("sportv", agora - h, agora + h, "Flamengo ao vivo"))                     // já começou
        assertFalse(c.oferecer("", agora + dia, agora + dia + h, "Flamengo"))                           // sem canal
        assertTrue(c.oferecer("premiere", agora + 6 * dia, agora + 6 * dia + 2 * h, "Fluminense x Flamengo"))
        assertEquals(listOf("Flamengo x Vasco", "Fluminense x Flamengo"), c.partidas().map { it.titulo })
        // Sem palavras não guarda nada (nem lê, na prática: o PlayerCarga nem cria o coletor).
        assertFalse(MeuTimeAdiante.Coletor(emptyList(), agora).oferecer("a", agora + dia, agora + dia + h, "Flamengo"))
    }

    @Test fun coletorFicaComOsMaisCedoNoTeto() {
        val c = MeuTimeAdiante.Coletor(listOf("Flamengo"), agora, max = 3)
        // De trás para a frente: os mais tarde chegam primeiro e devem sair.
        for (i in 20 downTo 1) c.oferecer("c$i", agora + i * h, agora + i * h + h, "Flamengo jogo $i")
        assertEquals(listOf("c1", "c2", "c3"), c.partidas().map { it.guiaId })
    }

    @Test fun validasTiraAcabadasForaDoAlcanceEPalavraApagada() {
        val l = listOf(
            MeuTimeAdiante.Partida("a", agora - 3 * h, agora - h, "Flamengo x Vasco"),        // acabou
            MeuTimeAdiante.Partida("b", agora + 2 * dia, agora + 2 * dia + h, "Flamengo x Bahia"),
            MeuTimeAdiante.Partida("c", agora + 3 * dia, agora + 3 * dia + h, "Palmeiras x Santos"), // palavra apagada
            MeuTimeAdiante.Partida("d", agora + 9 * dia, agora + 9 * dia + h, "Flamengo x Grêmio"),  // longe demais
        )
        assertEquals(listOf("b"), MeuTimeAdiante.validas(l, listOf("Flamengo"), agora).map { it.guiaId })
        assertTrue(MeuTimeAdiante.validas(l, emptyList(), agora).isEmpty())
    }

    @Test fun jsonIdaEVolta() {
        val l = listOf(MeuTimeAdiante.Partida("sportv", agora + dia, agora + dia + 2 * h, "Flamengo x Vasco"))
        assertEquals(l, MeuTimeAdiante.deJson(MeuTimeAdiante.paraJson(l)))
        assertTrue(MeuTimeAdiante.deJson("lixo").isEmpty())
        assertTrue(MeuTimeAdiante.deJson(null).isEmpty())
        assertTrue(MeuTimeAdiante.deJson("""[{"c":"","i":1,"f":2,"t":"x"},{"c":"a","i":5,"f":3,"t":"x"}]""").isEmpty())
    }
}
