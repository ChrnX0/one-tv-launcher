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

    @Test fun frequenciaGanhaDoUltimoUsoEFixadosFicamNoTopo() {
        val linha = listOf("youtube", "globoplay", "netflix", "spotify")
        val uso = mapOf("globoplay" to 2000L, "youtube" to 1000L)
        val freq = mapOf("youtube" to 6.0, "globoplay" to 1.0)
        // o mais aberto vence o último aberto
        assertEquals(listOf("youtube", "globoplay", "netflix", "spotify", CruzLogica.ID_TODOS_APPS), CruzLogica.appsDaColuna(linha, uso, freq))
        // fixado vai ao topo na ordem em que foi fixado; fixado desinstalado some sem erro
        assertEquals(listOf("spotify", "netflix", "youtube", "globoplay", CruzLogica.ID_TODOS_APPS),
            CruzLogica.appsDaColuna(linha, uso, freq, listOf("spotify", "sumiu", "netflix", "spotify")))
    }

    @Test fun aberturaValeMetadeAcadaSeteDias() {
        val semana = CruzLogica.MEIA_VIDA_MS
        assertEquals(4.0, CruzLogica.frequenciaAgora(8.0, 0L, semana), 1e-9)
        assertEquals(2.0, CruzLogica.frequenciaAgora(8.0, 0L, 2 * semana), 1e-9)
        assertEquals(8.0, CruzLogica.frequenciaAgora(8.0, 100L, 50L), 1e-9)   // relógio que voltou: não cresce nem quebra
        assertEquals(3.0, CruzLogica.somarAbertura(4.0, 0L, semana), 1e-9)
        // 1 abertura por dia por 30 dias fica perto de 1/(1-2^(-1/7)) ~ 10,6, sem crescer sem fim
        var p = 0.0; var t = 0L
        repeat(30) { p = CruzLogica.somarAbertura(p, t, t + 86_400_000L); t += 86_400_000L }
        assertTrue(p in 8.0..10.6)
    }
}
