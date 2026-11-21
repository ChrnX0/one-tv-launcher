package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.Canal
import br.paulo.tvlauncher.player.VariantesCanal
import br.paulo.tvlauncher.player.VariantesCanal.Nivel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Mesmo canal em várias qualidades vira um só; regiões, "+1" e numerais NÃO juntam. Nomes inventados no formato real. */
class VariantesCanalTest {
    private var n = 0
    private fun c(nome: String, grupo: String = "Abertos") = Canal("id${++n}", nome, n, grupo, url = "http://e.invalid/$n")

    @Test fun juntaSoQuandoADiferencaEOMarcadorDeQualidade() {
        val lista = listOf(c("BR: Globo SP FHD"), c("BR: Globo SP HD"), c("BR: Globo SP 4K", "4K"), c("BR: Globo SP SD"),
            c("BR: Globo RJ HD"), c("BR: Globo RJ FHD"), c("Warner HD"), c("Warner +1 HD"), c("HBO HD"), c("HBO 2 HD"),
            c("PT: Globo HD"))
        val g = VariantesCanal.agrupar(lista)
        assertEquals(listOf("BR: Globo SP", "BR: Globo RJ", "Warner HD", "Warner +1 HD", "HBO HD", "HBO 2 HD", "PT: Globo HD"),
            g.canais.map { it.nome })
        val sp = g.variantes.getValue(g.canais[0].id)
        assertEquals(listOf("BR: Globo SP 4K", "BR: Globo SP FHD", "BR: Globo SP HD", "BR: Globo SP SD"), sp.map { it.nome })
        assertEquals(2, g.variantes.size)   // só SP e RJ têm variantes
    }

    @Test fun representanteMoraNoGrupoDeVerdadeNaoNoGrupo4K() {
        val lista = listOf(c("ESPN 4K", "Canais | 4K"), c("ESPN FHD", "Esportes"), c("ESPN SD", "Esportes"))
        val g = VariantesCanal.agrupar(lista)
        assertEquals(1, g.canais.size)
        assertEquals("Esportes", g.canais[0].grupo)
        assertEquals("ESPN", g.canais[0].nome)
        assertEquals(g.canais[0].id, g.representante(lista[0].id))   // o id do 4K leva ao canal que o representa
    }

    @Test fun marcadoresDeCodificacaoEAlternativoTambemJuntam() {
        val g = VariantesCanal.agrupar(listOf(c("Cultura [FHD]"), c("Cultura (H265)"), c("Cultura HD ALT"), c("Cultura")))
        assertEquals(1, g.canais.size)
        assertEquals("Cultura", g.canais[0].nome)
        assertEquals(4, g.familia(g.canais[0].id).size)
    }

    @Test fun naoTiraHdDeDentroDePalavra() {
        assertEquals("canal hdmi", VariantesCanal.chave("Canal HDMI"))
        assertEquals("tv 2", VariantesCanal.chave("TV 2 FHD"))
        assertEquals("sao paulo +1", VariantesCanal.chave("São Paulo +1 HD"))
        assertTrue(VariantesCanal.grupoDeQualidade("Canais | UHD 4K"))
        assertFalse(VariantesCanal.grupoDeQualidade("Esportes HD"))
    }

    @Test fun nomeSoDeMarcadorNaoJunta() {
        val g = VariantesCanal.agrupar(listOf(c("4K"), c("HD")))
        assertEquals(2, g.canais.size)
        assertTrue(g.variantes.isEmpty())
    }

    @Test fun niveisPeloNome() {
        assertEquals(Nivel.UHD, VariantesCanal.nivel("Globo 4K"))
        assertEquals(Nivel.FHD, VariantesCanal.nivel("Globo FULL HD"))
        assertEquals(Nivel.SEM_MARCA, VariantesCanal.nivel("Globo"))
        assertTrue(Nivel.SEM_MARCA < Nivel.HD && Nivel.SD < Nivel.SEM_MARCA)
    }

    @Test fun idsAntigosMigramParaORepresentante() {
        val lista = listOf(c("Band HD"), c("Band SD"), c("Cultura"))
        val g = VariantesCanal.agrupar(lista)
        assertEquals(listOf(lista[0].id, lista[2].id), VariantesCanal.migrarIds(listOf(lista[1].id, lista[0].id, lista[2].id), g))
    }

    @Test fun listaSemRepetidosVoltaIgual() {
        val lista = listOf(c("A HD"), c("B HD"))
        val g = VariantesCanal.agrupar(lista)
        assertTrue(g.canais === lista)
    }

    // Rodada 13 da depuração: nome gigante (lixo da lista) não trava as regex; mesmo nível mantém a ordem da lista.
    @Test fun nomeGiganteNaoTravaEMesmoNivelMantemAOrdem() {
        val lixo = "X" + " |-|".repeat(50_000)
        val t0 = System.nanoTime()
        VariantesCanal.nomeLimpo(lixo); VariantesCanal.chave(lixo)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 2_000)
        val lista = listOf(c("Band HD"), c("Band SD"), c("Band HD ALT"), c("Band FHD"))
        val g = VariantesCanal.agrupar(lista)
        assertEquals(listOf("Band FHD", "Band HD", "Band HD ALT", "Band SD"), g.familia(g.canais[0].id).map { it.nome })
    }

    @Test fun paisDoGrupoNaoSeparaAVarianteDoGrupo4K() {
        val g = VariantesCanal.agrupar(listOf(c("Globo HD", "BR | Abertos"), c("Globo 4K", "4K")))
        assertEquals(1, g.canais.size)
        assertEquals("BR | Abertos", g.canais[0].grupo)
    }
}
