package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Test

/** Busca única mais rápida e sem repetidos (12/11/2026). */
class BuscaRapidaTest {
    private fun cand(titulo: String, inicio: Long, numero: Int) =
        Lembretes.Candidato("c$numero", "Canal $numero", numero, Programa("g$numero", inicio, inicio + 3_600_000L, titulo))

    @Test fun programaRepetidoNasAfiliadasViraUmaLinha() {
        val r = BuscaUnica.agrupar(listOf(
            cand("Globo Esporte", 1000, 273), cand("Globo Esporte", 1000, 4), cand("Globo Esporte", 1000, 304),
            cand("Globo Esporte", 5000, 4),      // mesmo título em outro horário: outra linha
            cand("Jornal Hoje", 2000, 4),
        ))
        assertEquals(listOf(4 to 2, 4 to 0, 4 to 0), r.map { it.first.numero to it.second })
        assertEquals(listOf(1000L, 2000L, 5000L), r.map { it.first.programa.inicio })
    }

    @Test fun acentoEMaiusculaNaoSeparamOGrupo() {
        val r = BuscaUnica.agrupar(listOf(cand("Notícias", 1000, 9), cand("NOTICIAS", 1000, 3)))
        assertEquals(listOf(3 to 1), r.map { it.first.numero to it.second })
    }

    @Test fun indiceDaOMesmoResultadoQueFiltrarEConta() {
        val nomes = listOf("Globo SP HD", "GloboNews", "Rede Globo", "Band", "Glória", "globo")
        val idx = BuscaUnica.indexar(nomes) { it }
        val (lista, total) = BuscaUnica.filtrarIndice(idx, "globo")
        assertEquals(BuscaUnica.filtrar(nomes, "globo") { it }, lista)
        assertEquals(BuscaUnica.contar(nomes, "globo") { it }, total)
        assertEquals(0, BuscaUnica.filtrarIndice(idx, "  ").second)
    }
}
