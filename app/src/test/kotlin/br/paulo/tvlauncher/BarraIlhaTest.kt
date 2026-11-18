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

    @Test fun linhaDoCantoJuntaAsPrimeirasLinhas() {
        assertEquals("Canal 5 · Globo · Jornal · faltam 25 min", BarraIlha.linhaDoCanto("Canal 5 · Globo\nJornal · faltam 25 min\nDepois: 21:00 Novela"))
        assertEquals("só uma", BarraIlha.linhaDoCanto("\n  só uma  \n\n"))
        assertEquals("", BarraIlha.linhaDoCanto(null))
    }

    @Test fun cantoDaTvComOProgramaPrimeiro() {
        assertEquals("Mais Você · faltam 7 min · Canal 2 · Globo SP", BarraIlha.cantoTv("Canal 2 · Globo SP", "Mais Você · faltam 7 min"))
        assertEquals("Canal 2 · Globo SP", BarraIlha.cantoTv("Canal 2 · Globo SP", " "))
        assertEquals("Canal 2 · Globo SP", BarraIlha.cantoTv("Canal 2 · Globo SP", null))
    }

    @Test fun cantoVaiAtePilulaENaoSomeDeTodo() {
        assertEquals(332, BarraIlha.larguraCanto(48, 400, 20, 120))      // pílula quieta: espaço até ela
        assertEquals(120, BarraIlha.larguraCanto(48, 150, 20, 120))      // pílula enorme: sobra o mínimo
    }

    @Test fun cantoIgnoraAFraseDeApresentacao() {
        val generica = "Controles e a sua turma do Discord"
        assertEquals("", BarraIlha.linhaDoCanto(generica, ignorar = generica))
        assertEquals("2 controles · Por último: Moonlight", BarraIlha.linhaDoCanto("$generica\n2 controles\nPor último: Moonlight", ignorar = generica))
    }

    @Test fun cantoNaoRepete() {
        val c = BarraIlha.Canto("No ar", "Jornal da Noite · faltam 25 min", "tv")
        assertTrue(BarraIlha.cantoVisivel(c, "series", null))
        assertFalse(BarraIlha.cantoVisivel(c, "tv", null))                       // a linha de baixo do item já diz
        assertFalse(BarraIlha.cantoVisivel(c, "series", "jornal da noite"))      // a pílula já diz
        assertTrue(BarraIlha.cantoVisivel(c, "series", "4:32"))
        assertFalse(BarraIlha.cantoVisivel(null, "series", null))
        assertFalse(BarraIlha.cantoVisivel(c.copy(texto = " "), "series", null))
    }

    @Test fun ouvirTrocaDeItemQuandoONossoSomToca() {
        assertEquals("podcasts", BarraIlha.origemOuvir("radio", temRadio = true, temPodcast = true))
        assertNull(BarraIlha.origemOuvir("radio", temRadio = true, temPodcast = false))
        assertEquals("radio", BarraIlha.origemOuvir("podcast", temRadio = true, temPodcast = true))
        assertEquals("radio", BarraIlha.origemOuvir(null, temRadio = true, temPodcast = true))
        assertEquals("podcasts", BarraIlha.origemOuvir(null, temRadio = false, temPodcast = true))
        assertNull(BarraIlha.origemOuvir(null, temRadio = false, temPodcast = false))
    }
}
