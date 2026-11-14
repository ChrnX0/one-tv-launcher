package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** Ideia 23 (14/11/2026): filtro combinado, coleção pelo nome e sorteio em Filmes (FilmesFiltro, pura). */
class FilmesFiltroTest {
    private val acao90 = Filme("1", "c", "Duro de Matar 3 (1995)", grupo = "Filmes | Ação", nota = 7.5f, ano = 1995)
    private val terror = Filme("2", "c", "O Iluminado", grupo = "Terror Legendado", nota = 8.4f, ano = 1980)
    private val comedia = Filme("3", "c", "Curtindo a Vida Adoidado", grupo = "Comédias", nota = 6.1f, ano = 1986)
    private val semNada = Filme("4", "c", "Sem Dados", grupo = "Lançamentos")
    private val acaoNova = Filme("5", "c", "Missão Explosiva", grupo = "Ação e Aventura", nota = 5.0f, ano = 2025)
    private val todos = listOf(acao90, terror, comedia, semNada, acaoNova)
    private val visto = mapOf(terror.chave to Posicao(ms = 7_000_000, duracao = 7_200_000, quando = 1))

    @Test fun generoVemDoNomeDaCategoria() {
        assertEquals(setOf(FilmesFiltro.Genero.ACAO), FilmesFiltro.generosDoGrupo("Filmes | Ação"))
        assertEquals(setOf(FilmesFiltro.Genero.ACAO, FilmesFiltro.Genero.AVENTURA), FilmesFiltro.generosDoGrupo("Ação e Aventura"))
        assertEquals(setOf(FilmesFiltro.Genero.COMEDIA), FilmesFiltro.generosDoGrupo("COMEDIAS"))
        assertEquals(setOf(FilmesFiltro.Genero.FICCAO), FilmesFiltro.generosDoGrupo("Sci-Fi"))
        // Palavra inteira: "Transação" não é "ação"; categoria sem gênero não tem gênero.
        assertTrue(FilmesFiltro.generosDoGrupo("Transação").isEmpty())
        assertTrue(FilmesFiltro.generosDoGrupo("Lançamentos 2025").isEmpty())
        assertEquals(listOf(FilmesFiltro.Genero.ACAO, FilmesFiltro.Genero.AVENTURA, FilmesFiltro.Genero.COMEDIA, FilmesFiltro.Genero.TERROR),
            FilmesFiltro.generosPresentes(todos))
    }

    @Test fun semFiltroDevolveAMesmaLista() {
        val c = FilmesFiltro.Criterios()
        assertFalse(c.ativo)
        assertTrue(FilmesFiltro.filtrar(todos, c, visto) === todos)
    }

    @Test fun filtroCombinaGeneroEpocaNotaENaoVistos() {
        val acao = FilmesFiltro.Criterios(genero = FilmesFiltro.Genero.ACAO)
        assertEquals(listOf(acao90, acaoNova), FilmesFiltro.filtrar(todos, acao, emptyMap()))
        assertEquals(listOf(acao90), FilmesFiltro.filtrar(todos, acao.copy(decada = 1990), emptyMap()))
        assertEquals(listOf(acaoNova), FilmesFiltro.filtrar(todos, acao.copy(ano = 2025), emptyMap()))
        // Nota mínima: sem nota sai; 7 inclui o 7,5 e o 8,4.
        assertEquals(listOf(acao90, terror), FilmesFiltro.filtrar(todos, FilmesFiltro.Criterios(notaMin = 7), emptyMap()))
        // Não vistos: o terror terminado sai.
        assertEquals(listOf(acao90), FilmesFiltro.filtrar(todos, FilmesFiltro.Criterios(notaMin = 7, naoVistos = true), visto))
        assertEquals(listOf(terror, comedia), FilmesFiltro.filtrar(todos, FilmesFiltro.Criterios(decada = 1980), emptyMap()))
    }

    @Test fun epocasSoAsQueExistem() {
        val (anos, decadas) = FilmesFiltro.epocasPresentes(todos, anoAtual = 2026)
        assertEquals(listOf(2025), anos)
        assertEquals(listOf(2020, 1990, 1980), decadas)
    }

    @Test fun nomeBaseJuntaSequenciasEVersoes() {
        assertEquals("duna", FilmesFiltro.nomeBase("Duna (2021)"))
        assertEquals("duna", FilmesFiltro.nomeBase("Duna: Parte Dois (2024) [L]"))
        assertEquals("duna", FilmesFiltro.nomeBase("BR: Duna - Parte 2"))
        assertEquals("toy story", FilmesFiltro.nomeBase("Toy Story 3"))
        assertEquals("rocky", FilmesFiltro.nomeBase("Rocky II (1979)"))
        assertEquals("rocky", FilmesFiltro.nomeBase("Rocky [Dublado]"))
        assertEquals("harry potter", FilmesFiltro.nomeBase("Harry Potter Parte 2"))
        // Número de 3+ dígitos é o título; um título só com número fica como está.
        assertEquals("300", FilmesFiltro.nomeBase("300"))
        assertEquals("1917", FilmesFiltro.nomeBase("1917 (2019)"))
    }

    @Test fun colecaoEmOrdemDeAno() {
        val d2 = Filme("10", "c", "Duna: Parte Dois (2024)", ano = 2024)
        val d1 = Filme("11", "c", "Duna (2021)", ano = 2021)
        val d1l = Filme("12", "c", "Duna (2021) [L]", ano = 2021)
        val outro = Filme("13", "c", "Dunkirk (2017)", ano = 2017)
        assertEquals(listOf(d1, d1l, d2), FilmesFiltro.colecao(listOf(d2, outro, d1, d1l), d2))
        assertEquals(listOf(outro), FilmesFiltro.colecao(listOf(d2, outro, d1), outro))
    }

    @Test fun sorteioPrefereInteressesENaoVistos() {
        assertNull(FilmesFiltro.sortear(emptyList(), emptySet(), emptyMap()))
        // Interesses na grade: só eles concorrem (o terror visto sai, sobra a comédia).
        val ints = setOf(terror.chave, comedia.chave)
        repeat(20) { s -> assertEquals(comedia, FilmesFiltro.sortear(todos, ints, visto, Random(s))) }
        // Só o visto nos interesses: ele mesmo (melhor que nada).
        assertEquals(terror, FilmesFiltro.sortear(todos, setOf(terror.chave), visto, Random(1)))
        // Sem interesses: qualquer não visto; o Random decide e é repetível.
        val a = FilmesFiltro.sortear(todos, emptySet(), visto, Random(42))
        assertEquals(a, FilmesFiltro.sortear(todos, emptySet(), visto, Random(42)))
        assertTrue(a != terror && a in todos)
        val sorteados = (0 until 200).map { FilmesFiltro.sortear(todos, emptySet(), visto, Random(it)) }.toSet()
        assertEquals(setOf(acao90, comedia, semNada, acaoNova), sorteados)
    }
}
