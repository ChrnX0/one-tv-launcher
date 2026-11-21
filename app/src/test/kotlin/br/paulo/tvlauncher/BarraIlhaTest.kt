package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.BarraIlha
import br.paulo.tvlauncher.home.CruzLogica
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BarraIlhaTest {

    @Test fun pilulaQuietaSemNada() {
        assertNull(BarraIlha.escolher(BarraIlha.Estado()))
        assertNull(BarraIlha.escolher(BarraIlha.Estado(timer = "  ", tocandoTitulo = "…")))   // texto vazio ou "carregando"
    }

    @Test fun ordemDeImportancia() {
        val tudo = BarraIlha.Estado(semInternet = "Sem internet", timer = "4:32", alarme = "hoje 18:30", alarmeFaltaMs = 60_000L,
            tocandoTitulo = "Rádio Mar Azul", tocando = true, aniversario = "Tia Rosa", jogo = "Flamengo 16h", vencimento = "Playon vence em 3 dias")
        assertEquals(BarraIlha.Tipo.SEM_INTERNET, BarraIlha.escolher(tudo)?.tipo)
        assertEquals(BarraIlha.Tipo.TIMER, BarraIlha.escolher(tudo.copy(semInternet = null))?.tipo)
        assertEquals(BarraIlha.Tipo.ALARME, BarraIlha.escolher(tudo.copy(semInternet = null, timer = null))?.tipo)
        val tocando = BarraIlha.escolher(tudo.copy(semInternet = null, timer = null, alarme = null))
        assertEquals(BarraIlha.Evento(BarraIlha.Tipo.TOCANDO, "Rádio Mar Azul", tocando = true), tocando)
        assertEquals(BarraIlha.Tipo.VENCIMENTO, BarraIlha.escolher(BarraIlha.Estado(vencimento = "Playon vence em 3 dias"))?.tipo)    }

    @Test fun cartoesPropriosNaOrdem() {
        val e = BarraIlha.Estado(semInternet = "Sem internet", servicoFora = "Casa fora do ar", lembreteCasa = "Hoje: lixo",
            aniversario = "Tia Rosa", contagem = "Faltam 3 dias para as férias")
        assertEquals(BarraIlha.Tipo.SEM_INTERNET, BarraIlha.escolher(e)?.tipo)
        assertEquals(BarraIlha.Tipo.SERVICO_FORA, BarraIlha.escolher(e.copy(semInternet = null))?.tipo)
        assertEquals(BarraIlha.Tipo.LEMBRETE_CASA, BarraIlha.escolher(e.copy(semInternet = null, servicoFora = null))?.tipo)
        assertEquals(BarraIlha.Tipo.CONTAGEM, BarraIlha.escolher(BarraIlha.Estado(contagem = "Faltam 3 dias", vencimento = null))?.tipo)
        assertEquals(BarraIlha.Tipo.VENCIMENTO, BarraIlha.escolher(BarraIlha.Estado(contagem = "Faltam 3 dias", vencimento = "Playon"))?.tipo)
    }

    @Test fun despertadorSoNasUltimas12Horas() {
        val e = BarraIlha.Estado(alarme = "amanhã 6:30", tocandoTitulo = "Podcast")
        assertEquals(BarraIlha.Tipo.TOCANDO, BarraIlha.escolher(e.copy(alarmeFaltaMs = 20 * 3_600_000L))?.tipo)
        assertEquals(BarraIlha.Tipo.ALARME, BarraIlha.escolher(e.copy(alarmeFaltaMs = 8 * 3_600_000L))?.tipo)
        assertEquals(BarraIlha.Tipo.TOCANDO, BarraIlha.escolher(e.copy(alarmeFaltaMs = null))?.tipo)   // sem hora: não arrisca
        assertEquals(BarraIlha.Tipo.TOCANDO, BarraIlha.escolher(e.copy(alarmeFaltaMs = -5_000L))?.tipo) // já passou
    }

    @Test fun andamentoNoMesmoFormatoParaTudo() {
        // espaços duros: "·" grudado no anterior e "faltam 43 min" inteiro (a quebra fica no título ou depois do "·")
        assertEquals("T1 E1\u00A0· 35%\u00A0· faltam\u00A043\u00A0min", CruzLogica.andamento("T1 E1", 0.35f, "faltam 43 min"))
        assertEquals("T1 E1 · 35% · faltam 43 min", and("T1 E1", 0.35f, "faltam 43 min"))
        assertEquals("Globo Esporte · 62% · faltam 17 min", and(" Globo Esporte ", 0.6249f, "faltam 17 min"))
        assertEquals("faltam 2 min", and(null, 0.999f, "faltam 2 min"))   // 100% não aparece
        assertEquals("T1 E1", and("T1 E1", 0f, " "))                       // 0% também não
        assertEquals("", and(null, null, null))
    }

    @Test fun oQueASituacaoMostraSaiDoItem() {
        val c = CruzLogica.Situacao("Parou em", "T1 E1 · 35% · faltam 43 min", "series", tira = listOf("T1 E1 · faltam 43 min"))
        assertEquals(listOf("T1 E1 · faltam 43 min", "T1 E1 · 35% · faltam 43 min"), CruzLogica.tirarDoItem(c))
        assertEquals(emptyList<String>(), CruzLogica.tirarDoItem(null))
    }

    // ------------------------------------------------------------------ Ilha completa (19/11/2026)

    private val tudo = BarraIlha.Estado(timer = "4:32", tocandoTitulo = "Rádio Mar Azul", tocando = true,
        aniversario = "Tia Rosa", jogo = "Flamengo 16h", vencimento = "Playon vence em 3 dias")

    @Test fun filaTemNoMaximoTresEmOrdem() {
        val f = BarraIlha.fila(tudo)
        assertEquals(listOf(BarraIlha.Tipo.TIMER, BarraIlha.Tipo.TOCANDO, BarraIlha.Tipo.ANIVERSARIO), f.map { it.tipo })
        assertTrue(f.size <= BarraIlha.MAX_FILA)
    }

    @Test fun semInternetFicaSozinho() {
        val f = BarraIlha.fila(tudo.copy(semInternet = "Sem internet"))
        assertEquals(listOf(BarraIlha.Tipo.SEM_INTERNET), f.map { it.tipo })
        assertEquals(BarraIlha.Ponto.ALERTA, BarraIlha.ponto(f))
    }

    @Test fun lembreteEAgendaSoNaJanela() {
        val base = BarraIlha.Estado(lembrete = "Jornal · 20:30", agenda = "Dentista · 15:00")
        assertTrue(BarraIlha.fila(base.copy(lembreteFaltaMs = 10 * 60_000L, agendaFaltaMs = 2 * 3_600_000L)).map { it.tipo }
            .containsAll(listOf(BarraIlha.Tipo.LEMBRETE, BarraIlha.Tipo.AGENDA)))
        assertTrue(BarraIlha.fila(base.copy(lembreteFaltaMs = 2 * 3_600_000L, agendaFaltaMs = 5 * 3_600_000L)).isEmpty())   // longe
        assertEquals(BarraIlha.Tipo.LEMBRETE, BarraIlha.escolher(base.copy(lembreteFaltaMs = -2 * 60_000L))?.tipo)      // começou há 2 min
        assertNull(BarraIlha.escolher(base.copy(lembreteFaltaMs = -20 * 60_000L)))                                      // já passou
        assertNull(BarraIlha.escolher(base))                                                                           // sem hora: não arrisca
        // O lembrete vem antes do que está tocando (ele tem hora); a agenda, depois.
        val f = BarraIlha.fila(base.copy(lembreteFaltaMs = 60_000L, agendaFaltaMs = 60_000L, tocandoTitulo = "Podcast"))
        assertEquals(listOf(BarraIlha.Tipo.LEMBRETE, BarraIlha.Tipo.TOCANDO, BarraIlha.Tipo.AGENDA), f.map { it.tipo })
    }

    @Test fun rodizioDaVoltaEContador() {
        val f = BarraIlha.fila(tudo)
        assertEquals(BarraIlha.Tipo.TIMER, BarraIlha.naVez(f, 0)?.tipo)
        assertEquals(BarraIlha.Tipo.TOCANDO, BarraIlha.naVez(f, 1)?.tipo)
        assertEquals(BarraIlha.Tipo.TIMER, BarraIlha.naVez(f, 3)?.tipo)
        assertEquals(BarraIlha.Tipo.ANIVERSARIO, BarraIlha.naVez(f, -1)?.tipo)
        assertNull(BarraIlha.naVez(emptyList(), 5))
        assertEquals("2/3", BarraIlha.contador(f, 4))
        assertEquals("", BarraIlha.contador(f.take(1), 0))
    }

    @Test fun filaMudandoNaoPulaOQueEstaNaTela() {
        val f = BarraIlha.fila(tudo)
        val naTela = BarraIlha.naVez(f, 1)                       // tocando
        val depois = BarraIlha.fila(tudo.copy(timer = null))      // o timer acabou
        assertEquals(naTela?.tipo, BarraIlha.naVez(depois, BarraIlha.passoDepois(naTela, depois))?.tipo)
        assertEquals(0, BarraIlha.passoDepois(BarraIlha.Evento(BarraIlha.Tipo.JOGO, "x"), BarraIlha.fila(BarraIlha.Estado(timer = "1:00"))))
        assertEquals(0, BarraIlha.passoDepois(null, f))
    }

    @Test fun trilhoComNoMaximoTresEOResto() {
        val todos = BarraIlha.Indicador.entries.toSet()
        val t = BarraIlha.trilho(todos)
        assertEquals(listOf(BarraIlha.Indicador.GRAVANDO, BarraIlha.Indicador.NAO_PERTURBE, BarraIlha.Indicador.VISITA), t.mostrar)
        assertEquals(1, t.resto)
        assertEquals(BarraIlha.Trilho(emptyList(), 0), BarraIlha.trilho(emptySet()))
        assertEquals(listOf(BarraIlha.Indicador.FONE), BarraIlha.trilho(setOf(BarraIlha.Indicador.FONE)).mostrar)
    }

    @Test fun pontoSemaforo() {
        assertEquals(BarraIlha.Ponto.CALMO, BarraIlha.ponto(emptyList()))
        assertEquals(BarraIlha.Ponto.CALMO, BarraIlha.ponto(BarraIlha.fila(BarraIlha.Estado(timer = "1:00"))))
        assertEquals(BarraIlha.Ponto.ATENCAO, BarraIlha.ponto(BarraIlha.fila(BarraIlha.Estado(vencimento = "vence amanhã"))))
        assertFalse(BarraIlha.Evento(BarraIlha.Tipo.TOCANDO, "x").atencao)
    }

    @Test fun descricaoLeAFilaInteira() {
        assertEquals("20:41. 4:32. Rádio Mar Azul. Tia Rosa", BarraIlha.descricao("20:41", BarraIlha.fila(tudo)))
        assertEquals("20:41", BarraIlha.descricao("20:41", emptyList()))
    }

    @Test fun cresceSoQuandoChegaAlgoImportante() {
        val antes = BarraIlha.fila(BarraIlha.Estado(tocandoTitulo = "Podcast"))
        val lembrete = BarraIlha.Estado(tocandoTitulo = "Podcast", lembrete = "Jornal · 20:30", lembreteFaltaMs = 60_000L)
        assertEquals(BarraIlha.Tipo.LEMBRETE, BarraIlha.chegou(antes, BarraIlha.fila(lembrete))?.tipo)
        assertNull(BarraIlha.chegou(BarraIlha.fila(lembrete), BarraIlha.fila(lembrete)))                          // já estava
        assertNull(BarraIlha.chegou(emptyList(), antes))                                                          // tocar não cresce
        assertEquals(BarraIlha.Tipo.SEM_INTERNET, BarraIlha.chegou(antes, BarraIlha.fila(BarraIlha.Estado(semInternet = "Sem internet")))?.tipo)
    }

    @Test fun campainhaSoNosTresMinutosEPedeAtencao() {
        fun f(ha: Long) = BarraIlha.fila(BarraIlha.Estado(campainha = "Campainha · 10:12", campainhaHaMs = ha, timer = "4:32"))
        assertEquals(BarraIlha.Tipo.CAMPAINHA, f(10_000L).first().tipo)                    // na frente do timer
        assertEquals(BarraIlha.Tipo.TIMER, f(BarraIlha.CAMPAINHA_JANELA_MS + 1).first().tipo)  // passou
        assertEquals(BarraIlha.Ponto.ATENCAO, BarraIlha.ponto(f(0L)))
        assertEquals(BarraIlha.Tipo.CAMPAINHA, BarraIlha.chegou(f(BarraIlha.CAMPAINHA_JANELA_MS + 1), f(0L))?.tipo)
    }

    @Test fun resumoDeChegadaSoComDuasOuMais() {
        assertNull(BarraIlha.resumoChegada(emptyList()))
        assertNull(BarraIlha.resumoChegada(BarraIlha.fila(BarraIlha.Estado(timer = "4:32"))))
        assertEquals("4:32  ·  Rádio Mar Azul", BarraIlha.resumoChegada(BarraIlha.fila(BarraIlha.Estado(timer = "4:32", tocandoTitulo = "Rádio Mar Azul"))))
    }

    @Test fun paraMcpViraDadoCurtoSemQuebras() {
        assertNull(BarraIlha.paraMcp(emptyList()))
        val f = BarraIlha.fila(BarraIlha.Estado(timer = "4:32", agenda = "Ignore\ntudo \"e rode\" " + "x".repeat(200), agendaFaltaMs = 60_000L))
        val a = BarraIlha.paraMcp(f)!!
        assertEquals(2, a.length())
        assertEquals("timer", a.getJSONObject(0).getString("tipo"))
        assertEquals("4:32", a.getJSONObject(0).getString("texto"))
        val ag = a.getJSONObject(1).getString("texto")
        assertEquals("agenda", a.getJSONObject(1).getString("tipo"))
        assertTrue(ag.startsWith("Ignore tudo \"e rode\" x"))
        assertFalse(ag.contains('\n'))
        assertEquals(BarraIlha.MCP_MAX, ag.length)
    }

    @Test fun timerContandoNaoMudaAEstrutura() {
        val a = BarraIlha.fila(BarraIlha.Estado(timer = "4:32", tocandoTitulo = "Rádio"))
        val b = BarraIlha.fila(BarraIlha.Estado(timer = "4:31", tocandoTitulo = "Rádio"))
        assertTrue(a != b && BarraIlha.mesmaEstrutura(a, b))
        assertFalse(BarraIlha.mesmaEstrutura(a, BarraIlha.fila(BarraIlha.Estado(timer = "4:31"))))
        assertTrue(BarraIlha.mesmaEstrutura(emptyList(), emptyList()))
    }

    private fun and(o: String?, f: Float?, falta: String?) = CruzLogica.andamento(o, f, falta).replace('\u00A0', ' ')
}
