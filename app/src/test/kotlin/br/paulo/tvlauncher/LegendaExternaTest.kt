package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.LegendaExterna
import br.paulo.tvlauncher.player.LegendaExterna.Busca
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegendaExternaTest {
    @Test fun buscaDeFilmeEmOrdemAlfabetica() {
        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?languages=pt-br%2Cpt-pt&query=duna+parte+2&type=movie&year=2024",
            LegendaExterna.urlBusca(Busca("Duna Parte 2", 2024)))
    }

    @Test fun buscaDeEpisodio() {
        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?episode_number=3&languages=pt-br%2Cpt-pt&query=frieren&season_number=1&type=episode",
            LegendaExterna.urlBusca(Busca("Frieren", 2023, 1, 3)))
    }

    @Test fun buscaNoIdiomaDaTv() {   // ideia 25
        assertEquals("pt-br,pt-pt", LegendaExterna.idiomasBusca("pt"))
        assertEquals("en", LegendaExterna.idiomasBusca("en"))
        assertEquals("es", LegendaExterna.idiomasBusca("es"))
        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?languages=en&query=duna+parte+2&type=movie&year=2024",
            LegendaExterna.urlBusca(Busca("Duna Parte 2", 2024), "en"))
        // Sem idioma: o mesmo endereço de antes (português).
        assertEquals(LegendaExterna.urlBusca(Busca("Frieren", 2023, 1, 3)), LegendaExterna.urlBusca(Busca("Frieren", 2023, 1, 3), "pt"))
    }

    @Test fun ordenaNoIdiomaDaTv() {
        val r = """{"data":[
            {"attributes":{"language":"pt-br","download_count":900,"files":[{"file_id":1}]}},
            {"attributes":{"language":"en","download_count":5,"files":[{"file_id":2}]}}
        ]}"""
        assertEquals(listOf(2L, 1L), LegendaExterna.lerBusca(r, "en").map { it.arquivo })
        assertEquals(listOf(1L, 2L), LegendaExterna.lerBusca(r, "pt").map { it.arquivo })
    }

    @Test fun nomeDeBuscaSemMarca() {
        assertEquals("duna parte 2", LegendaExterna.nomeDeBusca("Duna: Parte 2 (2024) [L]"))
    }

    private val resposta = """{"total_count":3,"data":[
        {"attributes":{"language":"pt-pt","download_count":900,"hearing_impaired":false,"release":"Dune.Part.Two.2024.1080p","files":[{"file_id":11,"file_name":"a.srt"}]}},
        {"attributes":{"language":"pt-br","download_count":50,"hearing_impaired":true,"release":"SDH","files":[{"file_id":22}]}},
        {"attributes":{"language":"pt-br","download_count":10,"hearing_impaired":false,"release":null,"files":[{"file_id":33,"file_name":"Dune2.srt"}]}},
        {"attributes":{"language":"pt-br","files":[]}},
        {"nada":1}
    ]}"""

    @Test fun ordenaBrasilSemSurdosMaisBaixadas() {
        val l = LegendaExterna.lerBusca(resposta)
        assertEquals(listOf(33L, 22L, 11L), l.map { it.arquivo })
        assertEquals("Dune2.srt", l[0].nome)
        assertTrue(LegendaExterna.lerBusca("lixo").isEmpty())
    }

    @Test fun downloadSoHttps() {
        assertEquals("https://dl.opensubtitles.org/x.srt", LegendaExterna.lerDownload("""{"link":"https://dl.opensubtitles.org/x.srt","remaining":4}"""))
        assertNull(LegendaExterna.lerDownload("""{"link":"http://x/y.srt"}"""))
        assertNull(LegendaExterna.lerDownload("""{"message":"limite"}"""))
        assertEquals("limite", LegendaExterna.lerErro("""{"message":"limite"}"""))
    }

    private val srt = "1\n00:00:01,500 --> 00:00:03,000\nOlá\n\n2\n01:59:59,900 --> 02:00:00,100\nFim\n"

    @Test fun deslocaTempos() {
        val d = LegendaExterna.deslocar(srt, 1000)
        assertTrue(d.contains("00:00:02,500 --> 00:00:04,000"))
        assertTrue(d.contains("02:00:00,900 --> 02:00:01,100"))
        assertTrue(d.contains("Olá"))
        // Adiantar além do zero não fica negativo.
        assertTrue(LegendaExterna.deslocar(srt, -2000).contains("00:00:00,000 --> 00:00:01,000"))
        assertEquals(srt, LegendaExterna.deslocar(srt, 0))
    }

    @Test fun reconheceSrt() {
        assertTrue(LegendaExterna.pareceSrt(srt))
        assertFalse(LegendaExterna.pareceSrt("<html>erro</html>"))
    }

    @Test fun textoUtf8OuLatin1() {
        assertEquals("Olá", LegendaExterna.decodificar("﻿Olá".toByteArray(Charsets.UTF_8)))
        assertEquals("Olá", LegendaExterna.decodificar("Olá".toByteArray(Charsets.ISO_8859_1)))
    }

    // 18/11/2026: "%20" levava a API a responder 301 (conferido com curl) e a busca falhava sempre.
    @Test fun espacoViraMaisNaoPorcento20() {
        val u = LegendaExterna.urlBusca(Busca("Sem Deixar Vestigios", 2024))
        assertFalse(u.contains("%20"))
        assertTrue(u.contains("query=sem+deixar+vestigios"))
    }

    @Test fun buscaPeloTmdbDoFilmeEDaSerie() {
        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?languages=pt-br%2Cpt-pt&tmdb_id=1129598&type=movie",
            LegendaExterna.urlBuscaTmdb(Busca("Sem Deixar Vestigios", 2024, tmdb = 1129598)))
        assertEquals("https://api.opensubtitles.com/api/v1/subtitles?episode_number=3&languages=pt-br%2Cpt-pt&parent_tmdb_id=209867&season_number=1&type=episode",
            LegendaExterna.urlBuscaTmdb(Busca("Frieren", null, 1, 3, tmdb = 209867)))
        assertNull(LegendaExterna.urlBuscaTmdb(Busca("Frieren")))
    }

    @Test fun buscasDaMaisCerteiraParaAMaisLarga() {
        val l = LegendaExterna.buscas(Busca("Duna Parte 2", 2024, tmdb = 693134))
        assertEquals(3, l.size)
        assertTrue(l[0].contains("tmdb_id=693134"))
        assertTrue(l[1].contains("year=2024"))
        assertFalse(l[2].contains("year="))
        // Episódio: sem a busca "sem ano" (o ano não entra na do episódio).
        assertEquals(2, LegendaExterna.buscas(Busca("Frieren", 2023, 1, 3, tmdb = 1)).size)
        assertEquals(1, LegendaExterna.buscas(Busca("Frieren", null, 1, 3)).size)
    }

    @Test fun soSegueRedirecionamentoDoProprioOpenSubtitles() {
        assertEquals("https://api.opensubtitles.com/api/v1/x", LegendaExterna.redirecionamentoSeguro("/api/v1/x"))
        assertEquals("https://vip-api.opensubtitles.com/api/v1/x", LegendaExterna.redirecionamentoSeguro("https://vip-api.opensubtitles.com/api/v1/x"))
        assertNull(LegendaExterna.redirecionamentoSeguro("http://api.opensubtitles.com/api/v1/x"))
        assertNull(LegendaExterna.redirecionamentoSeguro("https://opensubtitles.com.mal.example/x"))
        assertNull(LegendaExterna.redirecionamentoSeguro(null))
    }

    @Test fun nomeLegivelNaTv() {
        assertEquals("Dune 2021 1080p WEBRip DD5.1 x264-SHITBOX", LegendaExterna.nomeLegivel("Dune.2021.1080p.WEBRip.DD5.1.x264-SHITBOX"))
        assertEquals("Dune 2021 1080p HMAX WEB-DL DDP5.1 Atmos x264-EVO", LegendaExterna.nomeLegivel("Dune 2021 1080p HMAX WEB-DL DDP5 1 Atmos x264-EVO"))
        assertEquals("Show S01E03 720p", LegendaExterna.nomeLegivel("Show_S01E03__720p"))
    }

    @Test fun nomeDoIdioma() {
        assertEquals("Português (Brasil)", LegendaExterna.nomeIdioma("pt-BR", java.util.Locale.forLanguageTag("pt-BR")))
        assertEquals("Inglês", LegendaExterna.nomeIdioma("en", java.util.Locale.forLanguageTag("pt-BR")))
        assertEquals("XX", LegendaExterna.nomeIdioma("xx", java.util.Locale.forLanguageTag("pt-BR")))
    }
}
