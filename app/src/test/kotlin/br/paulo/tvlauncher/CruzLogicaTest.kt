package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.CruzLogica
import br.paulo.tvlauncher.home.CruzLogica.Categoria
import br.paulo.tvlauncher.home.CruzLogica.Resultado
import br.paulo.tvlauncher.home.CruzLogica.Tecla
import br.paulo.tvlauncher.home.IconeCruz
import br.paulo.tvlauncher.player.item
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

    @Test fun continuarDoMaisRecenteMantendoEmpateECortando() {
        val l = listOf(5L to "a", 0L to "b", 9L to "c", 5L to "d", 0L to "e")
        assertEquals(listOf("c", "a", "d", "b", "e"), CruzLogica.recentes(l, 10))
        assertEquals(listOf("c", "a"), CruzLogica.recentes(l, 2))
        assertEquals(emptyList<String>(), CruzLogica.recentes(l, -1))
    }

    @Test fun ordenarAppsSemCorteNemTodos() {
        val ordem = (1..12).map { "p$it" }
        val l = CruzLogica.ordenarApps(ordem, mapOf("p12" to 5L), mapOf("p3" to 2.0), listOf("p7"))
        assertEquals(12, l.size)
        assertEquals(listOf("p7", "p3", "p12", "p1"), l.take(4))
        assertTrue(CruzLogica.ID_TODOS_APPS !in l)
    }

    @Test fun linhasTvUmaInformacaoPorLinha() {
        assertEquals("Canal 5 · X\nJornal · faltam 10 min\nDepois, 21:30: Novela",
            CruzLogica.linhasTv("Canal 5 · X", "Jornal · faltam 10 min", "Depois, 21:30: Novela"))
        // Sem guia: só o canal (as linhas vazias somem, sem quebra sobrando).
        assertEquals("Canal 5 · X", CruzLogica.linhasTv("Canal 5 · X", "", null))
        assertEquals("Canal 5 · X\nDepois, 21:30: Novela", CruzLogica.linhasTv("Canal 5 · X", " ", "Depois, 21:30: Novela"))
    }

    /** Dono, 18/11/2026: ao sair de um app, a HOME volta no item que o abriu (não na TV). */
    @Test fun voltaNoUltimoAppAberto() {
        val e = CruzLogica.inicial(amostra)
        assertEquals(1, e.cat); assertEquals(0, e.sel[1])   // abertura: Assistir › TV
        // Abriu o app2 em Aplicativos: a volta vai para lá, na posição dele.
        val volta = CruzLogica.focar(amostra, e, "app2", Categoria.APLICATIVOS)!!
        assertEquals(4, volta.cat); assertEquals(2, volta.sel[4])
        assertEquals(e.sel[1], volta.sel[1])   // as outras colunas ficam onde estavam
        // O app subiu para o topo da coluna depois de aberto (ordem por uso): o foco segue o app, não a posição.
        val reordenado = CruzLogica.Mapa(amostra.categorias, amostra.itens.toMutableList().also { it[4] = listOf("app2", "app0", "app1", CruzLogica.ID_TODOS_APPS) })
        assertEquals(0, CruzLogica.reajustar(amostra, volta, reordenado).sel[4])
        // Item que não está na cruz: null (quem chama mantém o foco onde estava).
        assertNull(CruzLogica.focar(amostra, e, "sumiu"))
        // HOME refeita pelo Android com um app aberto há pouco: começa nele.
        val refeita = CruzLogica.inicial(amostra, emptyMap(), "podcasts")
        assertEquals(2, refeita.cat); assertEquals(1, refeita.sel[2])
        // Mesmo id em duas categorias: a de onde foi aberto.
        val dois = CruzLogica.Mapa(listOf(Categoria.CONTINUAR, Categoria.ASSISTIR), listOf(listOf("x", "app:yt"), listOf("tv", "app:yt")))
        assertEquals(1, CruzLogica.focar(dois, CruzLogica.inicial(dois), "app:yt", Categoria.ASSISTIR)!!.cat)
        assertEquals(0, CruzLogica.focar(dois, CruzLogica.inicial(dois), "app:yt", Categoria.CONTINUAR)!!.cat)
    }

    @Test fun animacaoDosIconesTocaEDescansa() {
        assertEquals(-1f, IconeCruz.faseEm(-5, 1500, 2500))          // antes de começar (atraso)
        assertEquals(0f, IconeCruz.faseEm(0, 1500, 2500))
        assertEquals(0.5f, IconeCruz.faseEm(750, 1500, 2500))
        assertEquals(-1f, IconeCruz.faseEm(2000, 1500, 2500))        // descanso
        assertEquals(0f, IconeCruz.faseEm(4000, 1500, 2500))         // passada seguinte
        assertEquals(5L, IconeCruz.proximoEm(-5, 1500, 2500))         // acorda quando o atraso acaba
        assertEquals(2000L, IconeCruz.proximoEm(2000, 1500, 2500))    // parado: só no fim do descanso
        assertEquals(0f, IconeCruz.sino(0f)); assertEquals(0f, IconeCruz.sino(1f)); assertEquals(1f, IconeCruz.sino(0.5f), 1e-6f)
        // Cada ícone tem a sua cor (nada de todos iguais).
        assertEquals(IconeCruz.Tipo.entries.size, IconeCruz.Tipo.entries.map { IconeCruz.corLinha(it) }.size)
        assertTrue(listOf(IconeCruz.Tipo.CONTINUAR, IconeCruz.Tipo.ASSISTIR, IconeCruz.Tipo.OUVIR, IconeCruz.Tipo.JOGAR, IconeCruz.Tipo.APLICATIVOS)
            .map { IconeCruz.corLinha(it) }.toSet().size == 5)
    }

    /** Toda a HOME: uma informação por linha, sem vazias nem repetidas, no máximo 4. */
    @Test fun nomeSemMarcaDeVersao() {
        assertEquals("Sem Deixar Vestigios", CruzLogica.semMarca("Sem Deixar Vestigios LEG"))
        assertEquals("Duna", CruzLogica.semMarca("Duna [DUB]"))
        assertEquals("Noturno", CruzLogica.semMarca("Noturno - DUAL 4K"))
        assertEquals("Avatar", CruzLogica.semMarca("Avatar (L)"))
        assertEquals("Oppenheimer", CruzLogica.semMarca("Oppenheimer | Dual Áudio"))
        // nome que só parece marca fica como veio
        assertEquals("Plano D", CruzLogica.semMarca("Plano D"))
        assertEquals("LEG", CruzLogica.semMarca("LEG"))
        assertEquals("Legado", CruzLogica.semMarca("Legado"))
        assertEquals("O Último Farol", CruzLogica.semMarca("  O Último Farol  "))
    }

    @Test fun linhasDeInformacao() {
        assertEquals("Duna\n2021 · 2 h 35 min · ★ 8,0\nFicção científica",
            CruzLogica.linhas("Duna", CruzLogica.juntar("2021", "2 h 35 min", "★ 8,0"), null, " ", "Ficção científica", "Duna"))
        assertEquals("a\nb\nc\nd", CruzLogica.linhas("a", "b", "c", "d", "e"))
        assertNull(CruzLogica.juntar(null, "", "  "))
        assertEquals("T1 E2 · faltam 10 min", CruzLogica.juntar("T1 E2", null, "faltam 10 min"))
    }

    @Test fun umaLinhaEmbaixoDoNomeEDetalhesNoCanto() {
        val serie = "Lei e Ordem: Unidade de Vítimas Especiais\nT1 E1 · faltam 43 min\n1999 · ★ 8,0\nCrime, Drama, Mistério"
        val p = CruzLogica.partes(serie)
        assertEquals("Lei e Ordem: Unidade de Vítimas Especiais", p.principal)
        assertEquals(listOf("T1 E1 · faltam 43 min", "1999 · ★ 8,0", "Crime, Drama, Mistério"), p.detalhes)
        // principal cortada com "…": vai inteira para o canto de baixo
        assertEquals("Lei e Ordem: Unidade de Vítimas Especiais", CruzLogica.detalhes(p, principalCortada = true).first())
        assertEquals(p.detalhes, CruzLogica.detalhes(p, principalCortada = false))
    }

    @Test fun oQueABarraJaMostraNaoRepete() {
        val tv = "Canal 2 · Globo SP\nSP1 · faltam 58 min\nDepois, 12:45: Globo Esporte"
        val p = CruzLogica.partes(tv, tirar = listOf(" SP1 · faltam 58 min "))
        assertEquals("Canal 2 · Globo SP", p.principal)
        assertEquals(listOf("Depois, 12:45: Globo Esporte"), p.detalhes)
        assertEquals(CruzLogica.Partes("", emptyList()), CruzLogica.partes(null))
        assertEquals(CruzLogica.Partes("só", emptyList()), CruzLogica.partes("\n só \n\n", tirar = listOf("")))
    }
}
