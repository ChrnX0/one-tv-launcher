package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coluna nova do player e "concluído" por minutos (14/11/2026). Dados inventados. */
class ColunaRegrasTest {
    private val min = 60_000L

    @Test fun categoriasDeAaZSemAcentoNemPrefixo() {
        val g = listOf("Filmes | Terror" to 3, "" to 9, "Ação" to 5, "4K" to 2, "filmes: animação" to 4, "Drama" to 1)
        assertEquals(listOf("4K", "Ação", "filmes: animação", "Drama", "Filmes | Terror", ""), ColunaRegras.ordenarAZ(g).map { it.first })
        // Vod.grupos e Series.grupos já saem em ordem alfabética.
        val fs = listOf(Filme("1", "a", "x", grupo = "Zumbis"), Filme("2", "a", "y", grupo = "Ação"), Filme("3", "a", "z", grupo = "Zumbis"))
        assertEquals(listOf("Ação" to 1, "Zumbis" to 2), Vod.grupos(fs))
    }

    @Test fun esportes() {
        assertTrue(ColunaRegras.ehEsporte("Canais | Esportes", "Qualquer"))
        assertTrue(ColunaRegras.ehEsporte("Jogos de Hoje", "Canal 1"))
        assertTrue(ColunaRegras.ehEsporte("UFC / Combate", "Canal 2"))
        assertTrue(ColunaRegras.ehEsporte("Abertos", "SporTV 2 FHD"))
        assertTrue(ColunaRegras.ehEsporte("Abertos", "ESPN 4"))
        assertFalse(ColunaRegras.ehEsporte("Abertos", "Globo SP"))
        assertFalse(ColunaRegras.ehEsporte("Filmes", "Telecine Action"))
        assertFalse(ColunaRegras.ehEsporte("Notícias", "Transporte Agora"))   // "sport" só como palavra inteira
    }

    @Test fun aoVivoPrimeiroPeloTitulo() {
        assertTrue(ColunaRegras.pareceAoVivo("Brasileirão: Flamengo x Palmeiras"))
        assertTrue(ColunaRegras.pareceAoVivo("UFC 300 ao vivo"))
        assertTrue(ColunaRegras.pareceAoVivo("NBA: Lakers vs. Celtics"))
        assertFalse(ColunaRegras.pareceAoVivo("Flamengo x Palmeiras (reprise)"))
        assertFalse(ColunaRegras.pareceAoVivo("Melhores momentos: Flamengo x Palmeiras"))
        assertFalse(ColunaRegras.pareceAoVivo("Programa de debate"))
        assertEquals(listOf(2, 4, 1, 3), ColunaRegras.aoVivoPrimeiro(listOf(1, 2, 3, 4)) { it % 2 == 0 })
    }

    @Test fun novosDesdeAUltimaVisita() {
        val fs = listOf(Filme("1", "a", "x", grupo = "Ação", adicionado = 100), Filme("2", "a", "y", grupo = "Ação ", adicionado = 300),
            Filme("3", "a", "z", grupo = "Drama", adicionado = 250), Filme("4", "a", "w", grupo = "Drama", adicionado = 50))
        assertEquals(emptyMap<String, Int>(), ColunaRegras.novosPorGrupo(fs, 0, { it.grupo }, { it.adicionado }))   // 1ª visita
        assertEquals(mapOf("Ação" to 1, "Drama" to 1), ColunaRegras.novosPorGrupo(fs, 200, { it.grupo }, { it.adicionado }))
        assertEquals(300L, ColunaRegras.novoMarco(fs, 200) { it.adicionado })
        assertEquals(500L, ColunaRegras.novoMarco(fs, 500) { it.adicionado })   // nunca volta
    }

    @Test fun ultimaCategoria() {
        assertEquals("g:Ação", ColunaRegras.categoriaInicial("g:Ação", setOf("g:Ação", "*todos"), "*continuar"))
        assertEquals("*continuar", ColunaRegras.categoriaInicial("g:Sumiu", setOf("g:Ação"), "*continuar"))
        assertEquals("*todos", ColunaRegras.categoriaInicial(null, setOf("g:Ação"), "*todos"))
    }

    @Test fun pularParaLetra() {
        assertEquals('#', ColunaRegras.letraDe("007 contra"))
        assertEquals('#', ColunaRegras.letraDe(""))
        assertEquals('A', ColunaRegras.letraDe("avatar"))
        val g = listOf("2012", "alien", "avatar", "duna", "matrix", "zumbilandia")
        assertEquals(0, ColunaRegras.indiceDaLetra(g, '#'))
        assertEquals(1, ColunaRegras.indiceDaLetra(g, 'A'))
        assertEquals(3, ColunaRegras.indiceDaLetra(g, 'B'))   // sem B: o 1º depois dela
        assertEquals(4, ColunaRegras.indiceDaLetra(g, 'M'))
        assertEquals(5, ColunaRegras.indiceDaLetra(g, 'Z'))
        assertEquals(-1, ColunaRegras.indiceDaLetra(emptyList(), 'A'))
        // Títulos sem letra latina (chave "\uFFFF…") ficam no fim; letra sem título cai no 1º depois dela.
        val h = listOf("1917", "baby", "casa", "zorro", "\uFFFF가")
        assertEquals(1, ColunaRegras.indiceDaLetra(h, 'A'))
        assertEquals(3, ColunaRegras.indiceDaLetra(h, 'Y'))
    }

    @Test fun concluidoPorMinutos() {
        // Filme de 2 h: faltando 9 min = concluído; 12 min = ainda continua.
        assertTrue(Vod.finalizado(Posicao(111 * min, 120 * min, 0)))
        assertFalse(Vod.finalizado(Posicao(108 * min, 120 * min, 0)))
        assertTrue(Vod.deveRetomar(Posicao(108 * min, 120 * min, 0)))
        // Episódio de 24 min: faltando 2:30 = concluído; faltando 4 min, ainda não.
        assertTrue(Vod.finalizado(Posicao(21 * min + 30_000, 24 * min, 0), episodio = true))
        assertFalse(Vod.finalizado(Posicao(20 * min, 24 * min, 0), episodio = true))
        // Curta de 15 min: com 5 min vistos faltam 10, mas não passou de 70%: não concluído.
        assertFalse(Vod.finalizado(Posicao(5 * min + 1, 15 * min, 0)))
        assertTrue(Vod.finalizado(Posicao(11 * min, 15 * min, 0)))
        // Sem duração: nunca concluído pela régua.
        assertFalse(Vod.finalizado(Posicao(50 * min, 0, 0)))
    }

    @Test fun marcarAMaoEAssistidosSoConcluidos() {
        val f1 = Filme("1", "a", "Visto"); val f2 = Filme("2", "a", "Começado")
        var p = mapOf(f2.chave to Posicao(10 * min, 100 * min, 5))
        assertEquals(emptyList<Filme>(), Vod.assistidos(listOf(f1, f2), p))   // começado não é "assistido"
        p = Vod.marcarAssistido(p, f1.chave, 9)
        assertTrue(Vod.finalizado(p[f1.chave]))
        assertFalse(Vod.deveRetomar(p[f1.chave]))
        assertEquals(listOf(f1), Vod.assistidos(listOf(f1, f2), p))
        p = Vod.marcarAssistido(p, f2.chave, 10)   // mantém a duração conhecida
        assertEquals(Posicao(100 * min, 100 * min, 10), p[f2.chave])
        p = Vod.esquecer(p, f2.chave)
        assertNull(p[f2.chave])
    }

    private fun ficha(vararg porTemporada: Int) = FichaSerie(temporadas = porTemporada.withIndex().associate { (i, n) ->
        (i + 1) to (1..n).map { Episodio("${i + 1}-$it", i + 1, it, "E$it", duracaoMin = 20) }
    })

    private fun vistos(f: FichaSerie, quantos: Int): Map<String, Posicao> =
        Series.emOrdem(f).take(quantos).associate { it.chave("a") to Posicao(20 * min, 20 * min, 1) }

    @Test fun etiquetasDaSerie() {
        val s = Serie("9", "a", "Série", atualizada = 100)
        val f = ficha(3)
        val p1 = Series.progressoDaFicha(s, f, vistos(f, 2), null)!!
        assertNull(Series.etiqueta(s, p1))
        val conc = Series.progressoDaFicha(s, f, vistos(f, 3), p1)!!
        assertEquals(EtiquetaSerie.Concluida, Series.etiqueta(s, conc))
        assertEquals(3, conc.concluidaCom)
        // O servidor atualizou a série: sabe que tem coisa nova, sem saber quanto.
        val s2 = s.copy(atualizada = 200)
        assertEquals(EtiquetaSerie.Novos(0), Series.etiqueta(s2, conc))
        // Ficha relida: 2 episódios novos na mesma temporada.
        val f2 = ficha(5)
        val p2 = Series.progressoDaFicha(s2, f2, vistos(f, 3), conc)!!
        assertEquals(EtiquetaSerie.Novos(2), Series.etiqueta(s2, p2))
        // Ou uma temporada nova.
        val f3 = ficha(3, 4)
        assertEquals(EtiquetaSerie.NovaTemporada, Series.etiqueta(s2, Series.progressoDaFicha(s2, f3, vistos(f, 3), conc)))
        // Gravado e lido de volta.
        assertEquals(mapOf("k" to p2), Series.progressoDeJson(Series.progressoParaJson(mapOf("k" to p2))))
        // Assistidos = só as concluídas.
        assertEquals(listOf(s2), Series.assistidos(listOf(s2), emptyMap(), emptyMap(), mapOf(s2.chave to p2)))
        assertEquals(emptyList<Serie>(), Series.assistidos(listOf(s), emptyMap(), emptyMap(), mapOf(s.chave to p1)))
    }

    @Test fun comecarOProximoConcluiOAnterior() {
        val f = ficha(3)
        val (e1, e2, e3) = Series.emOrdem(f)
        val p = mapOf(e1.chave("a") to Posicao(15 * min, 20 * min, 7))
        val depois = Series.concluirAnterior(f, "a", e2, p, 99)
        assertTrue(Vod.finalizado(depois[e1.chave("a")], episodio = true))
        // Anterior nunca aberto: continua não visto.
        assertEquals(p, Series.concluirAnterior(f, "a", e3, p, 99))
    }
}
