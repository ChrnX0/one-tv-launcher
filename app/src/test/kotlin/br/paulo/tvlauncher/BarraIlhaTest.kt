package br.paulo.tvlauncher

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
        assertEquals(BarraIlha.Tipo.VENCIMENTO, BarraIlha.escolher(BarraIlha.Estado(vencimento = "Playon vence em 3 dias"))?.tipo)
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

    private fun and(o: String?, f: Float?, falta: String?) = CruzLogica.andamento(o, f, falta).replace('\u00A0', ' ')
}
