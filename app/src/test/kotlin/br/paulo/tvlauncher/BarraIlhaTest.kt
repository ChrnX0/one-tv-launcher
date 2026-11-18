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

    @Test fun cantoVaiAtePilulaENaoSomeDeTodo() {
        assertEquals(332, BarraIlha.larguraCanto(48, 400, 20, 120))      // pílula quieta: espaço até ela
        assertEquals(120, BarraIlha.larguraCanto(48, 150, 20, 120))      // pílula enorme: sobra o mínimo
    }

    @Test fun cantoSegueASelecaoESemRepetirAPilula() {
        val c = BarraIlha.Canto("No ar", "Jornal da Noite · faltam 25 min", "tv")
        assertTrue(BarraIlha.cantoVisivel(c, null))
        assertFalse(BarraIlha.cantoVisivel(c, "jornal da noite"))      // a pílula já diz
        assertTrue(BarraIlha.cantoVisivel(c, "4:32"))
        assertFalse(BarraIlha.cantoVisivel(null, null))
        assertFalse(BarraIlha.cantoVisivel(c.copy(texto = " "), null))
        val buscar = BarraIlha.Canto("Buscar", "▲ abre a barra com a busca", "")
        assertEquals(c, BarraIlha.cantoDoFoco(c, buscar))
        assertEquals(buscar, BarraIlha.cantoDoFoco(null, buscar))          // item sem nada: o da categoria (Aplicativos)
        assertNull(BarraIlha.cantoDoFoco(c.copy(texto = ""), null))          // Séries, Filmes…: vazio
    }
}
