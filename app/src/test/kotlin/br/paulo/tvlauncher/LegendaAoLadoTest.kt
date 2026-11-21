package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.LegendaAoLado
import br.paulo.tvlauncher.player.LegendaAoLado.Achada
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Legenda ao lado do vídeo, pelo nome do arquivo (como Kodi e VLC). */
class LegendaAoLadoTest {
    private val pasta = listOf("A Origem (2010).mkv", "A Origem (2010).srt", "A Origem (2010).pt-BR.ass", "A Origem (2010).eng.srt",
        "A Origem (2010).forced.srt", "A Origem (2010).nfo", "Outro Filme.srt", "A Origem (2010) extras.srt", "poster.jpg")

    @Test fun achaAsDoMesmoNomeComESemIdioma() {
        assertEquals(listOf(
            Achada("A Origem (2010).eng.srt", "application/x-subrip", "en"),
            Achada("A Origem (2010).forced.srt", "application/x-subrip", null),
            Achada("A Origem (2010).pt-BR.ass", "text/x-ssa", "pt-BR"),
            Achada("A Origem (2010).srt", "application/x-subrip", null),
        ), LegendaAoLado.achar("A Origem (2010).mkv", pasta))
    }

    @Test fun maiusculasNaoImportam() {
        assertEquals(listOf("EP01.SRT"), LegendaAoLado.achar("ep01.mp4", listOf("EP01.SRT", "ep02.srt")).map { it.nome })
    }

    @Test fun nadaQuandoNaoHaLegenda() {
        assertEquals(emptyList<Achada>(), LegendaAoLado.achar("x.mkv", listOf("x.mkv", "x.txt")))
        assertEquals(emptyList<Achada>(), LegendaAoLado.achar(".mkv", listOf(".srt")))
    }

    @Test fun limiteDeLegendas() {
        val muitas = (1..20).map { "v.l$it.srt" }
        assertEquals(LegendaAoLado.MAX, LegendaAoLado.achar("v.mkv", muitas).size)
    }

    @Test fun idiomas() {
        assertEquals("pt-BR", LegendaAoLado.idioma("pt_br"))
        assertEquals("pt", LegendaAoLado.idioma("por"))
        assertEquals("pt-BR", LegendaAoLado.idioma("pob"))
        assertEquals("es", LegendaAoLado.idioma("es.forced"))
        assertNull(LegendaAoLado.idioma("forced"))
        assertNull(LegendaAoLado.idioma("sdh"))
    }
}
