package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Segundo guia (ideia 15): casamento pelo nome normalizado e "só preenche quem ficou sem programação". Teste puro. */
class GuiaExtraTest {
    @Test fun normalizaAcentoCaixaSufixoEPontuacao() {
        assertEquals("sportv2", GuiaExtra.normalizarNome("SporTV 2 FHD"))
        assertEquals("sportv2", GuiaExtra.normalizarNome("sportv-2"))
        assertEquals("globosp", GuiaExtra.normalizarNome("Globo HD (SP)"))
        assertEquals("tvcultura", GuiaExtra.normalizarNome("TV Cultura SD"))
        assertEquals("discoveryhh", GuiaExtra.normalizarNome("Discovery H&H [4K]"))
        assertEquals("canalbrasil", GuiaExtra.normalizarNome("Canal Brasíl UHD"))
        assertEquals("espn2", GuiaExtra.normalizarNome("ESPN²"))
        // Só qualidade no nome: não some (um canal chamado "4K" ainda tem nome); sem letra nenhuma: vazio.
        assertEquals("4k", GuiaExtra.normalizarNome("4K"))
        assertEquals("", GuiaExtra.normalizarNome(" -- "))
        // "HD" no meio de uma palavra não é sufixo: "Shdtv" fica inteiro.
        assertEquals("shdtv", GuiaExtra.normalizarNome("SHDTV"))
    }

    @Test fun faltandoSoQuemTemIdENaoRecebeuPrograma() {
        val canais = listOf("globo.br" to "Globo", "" to "Sem id", "sportv2.br" to "SporTV 2", "sportv2.br" to "SporTV 2 HD", "cult" to "Cultura")
        val f = GuiaExtra.faltando(canais, comPrograma = setOf("globo.br"))
        assertEquals(listOf("sportv2.br", "cult"), f.keys.toList())
        assertEquals("SporTV 2", f["sportv2.br"])   // o primeiro nome vale
    }

    @Test fun casaPeloIdPeloNomeEPeloIdComCaraDeNome() {
        val m = GuiaExtra.Mescla(mapOf("sportv2.br" to "SporTV 2 FHD", "cult" to "TV Cultura", "x9" to "Canal Brasil"))
        assertFalse(m.vazia)
        m.canal("SPORTV2", listOf("Sportv-2 HD"))     // pelo display-name
        m.canal("cult", emptyList())                   // pelo mesmo id
        m.canal("CanalBrasil.br", emptyList())         // o id é o nome, com sufixo de país
        m.canal("globo", listOf("Globo"))              // Globo não está faltando: ignorado
        assertEquals(listOf("sportv2.br"), m.destinos("SPORTV2"))
        assertEquals(listOf("cult"), m.destinos("cult"))
        assertEquals(listOf("x9"), m.destinos("CanalBrasil.br"))
        assertTrue(m.destinos("globo").isEmpty())
        // <programme> de canal sem <channel> antes: só casa pelo id.
        assertEquals(listOf("x9"), m.destinos("x9"))
        assertTrue(m.destinos("desconhecido").isEmpty())
    }

    @Test fun mesmoNomeEmDoisCanaisPreencheOsDois() {
        val m = GuiaExtra.Mescla(mapOf("a" to "ESPN HD", "b" to "ESPN"))
        m.canal("espn.us", listOf("ESPN"))
        assertEquals(listOf("a", "b"), m.destinos("espn.us"))
    }

    @Test fun semNadaFaltandoAMesclaEVazia() {
        assertTrue(GuiaExtra.Mescla(emptyMap()).vazia)
        assertTrue(GuiaExtra.Mescla(GuiaExtra.faltando(listOf("a" to "A"), setOf("a"))).vazia)
    }
}
