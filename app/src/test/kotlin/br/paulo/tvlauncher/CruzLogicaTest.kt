package br.paulo.tvlauncher

import br.paulo.tvlauncher.CruzLogica.Categoria
import br.paulo.tvlauncher.CruzLogica.Resultado
import br.paulo.tvlauncher.CruzLogica.Tecla
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CruzLogicaTest {
    /** O mesmo mapa da amostra navegável (home-grupos/amostra-a-b.html). */
    private val amostra = CruzLogica.Mapa(
        listOf(Categoria.CONTINUAR, Categoria.ASSISTIR, Categoria.OUVIR, Categoria.JOGAR, Categoria.APLICATIVOS),
        listOf(
            listOf("cont1", "cont2", "cont3"),
            listOf("tv", "series", "filmes", "rede", "addons"),
            listOf("radio", "podcasts"),
            listOf("jogos", "sofa"),
            listOf("app0", "app1", "app2", CruzLogica.ID_TODOS_APPS),
        ),
    )

    private fun teclas(alvo: String, m: CruzLogica.Mapa = amostra) = CruzLogica.menorCaminho(m, alvo)?.size

    @Test fun tabelaDaAmostra() {
        // Aceite da especificação: tem que bater com a tabela medida pela amostra.
        assertEquals(1, teclas("tv"))
        assertEquals(2, teclas("series"))
        assertEquals(2, teclas("radio"))
        assertEquals(3, teclas("podcasts"))
        assertEquals(3, teclas("jogos"))
        assertEquals(4, teclas("sofa"))
        assertEquals(2, teclas("cont1"))
    }

    @Test fun caminhosSaoOsEsperados() {
        assertEquals(listOf(Tecla.OK), CruzLogica.menorCaminho(amostra, "tv"))
        assertEquals(listOf(Tecla.ESQUERDA, Tecla.OK), CruzLogica.menorCaminho(amostra, "cont1"))
        assertEquals(listOf(Tecla.DIREITA, Tecla.DIREITA, Tecla.BAIXO, Tecla.OK), CruzLogica.menorCaminho(amostra, "sofa"))
    }

    @Test fun abreEmAssistirTvMesmoComOutroLembrado() {
        val e = CruzLogica.inicial(amostra, mapOf("assistir" to "filmes", "ouvir" to "podcasts"))
        assertEquals("tv", e.item(amostra))
        // as outras categorias começam no lembrado
        val ouvir = CruzLogica.passo(amostra, CruzLogica.passo(amostra, e, Tecla.DIREITA).estado, Tecla.OK) as Resultado.Abrir
        assertEquals("podcasts", ouvir.item)
    }

    @Test fun cimaNoPrimeiroSobeParaOTopoEBaixoNoUltimoNaoDaAVolta() {
        val e = CruzLogica.inicial(amostra)
        assertTrue(CruzLogica.passo(amostra, e, Tecla.CIMA) is Resultado.SubirTopo)
        var s = e
        repeat(10) { s = CruzLogica.passo(amostra, s, Tecla.BAIXO).estado }
        assertEquals("addons", s.item(amostra))
    }

    @Test fun bordasDaLinhaEVoltarNaoFazemNada() {
        var s = CruzLogica.inicial(amostra)
        repeat(9) { s = CruzLogica.passo(amostra, s, Tecla.ESQUERDA).estado }
        assertEquals(Categoria.CONTINUAR, s.categoria(amostra))
        repeat(9) { s = CruzLogica.passo(amostra, s, Tecla.DIREITA).estado }
        assertEquals(Categoria.APLICATIVOS, s.categoria(amostra))
        assertEquals(s, CruzLogica.passo(amostra, s, Tecla.VOLTAR).estado)
    }

    @Test fun homeVaiParaAssistirEDuasVezesParaTv() {
        var s = CruzLogica.inicial(amostra)
        s = CruzLogica.passo(amostra, s, Tecla.BAIXO).estado            // Séries
        s = CruzLogica.passo(amostra, s, Tecla.DIREITA).estado          // Ouvir
        s = CruzLogica.passo(amostra, s, Tecla.HOME).estado
        assertEquals(Categoria.ASSISTIR, s.categoria(amostra))
        assertEquals("series", s.item(amostra))                         // a 1ª HOME mantém o item
        s = CruzLogica.passo(amostra, s, Tecla.HOME).estado
        assertEquals("tv", s.item(amostra))
    }

    @Test fun reajustarMantemOItemQuandoAListaMuda() {
        var s = CruzLogica.inicial(amostra)
        s = CruzLogica.passo(amostra, s, Tecla.BAIXO).estado
        s = CruzLogica.passo(amostra, s, Tecla.BAIXO).estado            // Filmes
        // a TV sumiu (conta de canais apagada) e Ouvir também
        val novo = CruzLogica.Mapa(
            listOf(Categoria.CONTINUAR, Categoria.ASSISTIR, Categoria.JOGAR, Categoria.APLICATIVOS),
            listOf(listOf("cont1"), listOf("series", "filmes", "rede"), listOf("jogos"), listOf(CruzLogica.ID_TODOS_APPS)),
        )
        val r = CruzLogica.reajustar(amostra, s, novo)
        assertEquals("filmes", r.item(novo))
        // a categoria em foco sumiu: fica a vizinha (mesma posição)
        val emOuvir = CruzLogica.passo(amostra, CruzLogica.inicial(amostra), Tecla.DIREITA).estado
        assertEquals(Categoria.JOGAR, CruzLogica.reajustar(amostra, emOuvir, novo).categoria(novo))
    }

    @Test fun semAssistirAbreNaPrimeira() {
        val m = CruzLogica.Mapa(listOf(Categoria.OUVIR, Categoria.APLICATIVOS), listOf(listOf("radio"), listOf(CruzLogica.ID_TODOS_APPS)))
        assertEquals(Categoria.OUVIR, CruzLogica.inicial(m).categoria(m))
        assertNull(CruzLogica.menorCaminho(m, "tv"))
    }

    @Test fun appsDaColunaSaoOsOitoMaisUsadosETodosNoFim() {
        val linha = (1..12).map { "p$it" }
        val uso = mapOf("p9" to 900L, "p3" to 300L, "p12" to 1200L)
        val col = CruzLogica.appsDaColuna(linha, uso)
        assertEquals(listOf("p12", "p9", "p3", "p1", "p2", "p4", "p5", "p6", CruzLogica.ID_TODOS_APPS), col)
        assertEquals(listOf(CruzLogica.ID_TODOS_APPS), CruzLogica.appsDaColuna(emptyList(), emptyMap()))
    }
}
