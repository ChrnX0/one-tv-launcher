package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.Idioma
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Elevação 16.2: audiodescrição e legenda para surdos, escolhidas uma vez e respeitadas na seleção de faixa. */
class AcessibilidadeFaixasTest {
    private fun f(l: String?, rotulo: String? = null, padrao: Boolean = false, forcada: Boolean = false,
                  descreve: Boolean = false, surdos: Boolean = false) =
        Idioma.Faixa(l, padrao, forcada, rotulo, marcaDescreve = descreve, marcaSurdos = surdos)

    @Test fun reconheceAudiodescricaoPeloNome() {
        for (r in listOf("Português (AD)", "Audio Description", "Audiodescrição", "English - Descriptive", "DVS", "Hörfilm"))
            assertTrue(r, f("pt", r).descreve)
        for (r in listOf("Português", "Adventure Commentary", "Stereo", "AAC 5.1")) assertFalse(r, f("pt", r).descreve)
    }

    @Test fun reconheceSurdosPeloNome() {
        for (r in listOf("Português (SDH)", "English CC", "Hearing Impaired", "Legenda para surdos", "Español (sordos)"))
            assertTrue(r, f("pt", r).paraSurdos)
        assertFalse(f("pt", "Português").paraSurdos)
    }

    @Test fun semPreferenciaNuncaCaiNaAudiodescricao() {
        // O arquivo lista primeiro a faixa AD em português: antes, ela ganhava por ser a primeira do idioma.
        val faixas = listOf(f("en", padrao = true), f("pt", "Português (AD)"), f("pt", "Português"))
        assertEquals(2, Idioma.escolherAudio(faixas, Idioma.Pref.DUBLADO, "pt"))
    }

    @Test fun comPreferenciaPegaAAudiodescricaoDoMesmoIdioma() {
        val faixas = listOf(f("en", padrao = true), f("pt"), f("pt", descreve = true), f("en", "English AD"))
        assertEquals(2, Idioma.escolherAudio(faixas, Idioma.Pref.DUBLADO, "pt", audiodescricao = true))
        assertEquals(3, Idioma.escolherAudio(faixas, Idioma.Pref.ORIGINAL, "pt", audiodescricao = true))
    }

    @Test fun semAudiodescricaoNoArquivoFicaODeSempre() {
        val faixas = listOf(f("en", padrao = true), f("pt"))
        assertEquals(1, Idioma.escolherAudio(faixas, Idioma.Pref.DUBLADO, "pt", audiodescricao = true))
    }

    @Test fun surdosLigaLegendaMesmoDublado() {
        val textos = listOf(f("pt", forcada = true), f("pt", "Português"), f("pt", surdos = true))
        assertEquals(2, Idioma.escolherLegenda(textos, audioPortugues = true, idioma = "pt", surdos = true))
        // Sem a preferência, dublado continua só com a forçada.
        assertEquals(0, Idioma.escolherLegenda(textos, audioPortugues = true, idioma = "pt"))
    }

    @Test fun surdosSemFaixaParaSurdosUsaACompleta() {
        val textos = listOf(f("pt", forcada = true), f("pt"))
        assertEquals(1, Idioma.escolherLegenda(textos, audioPortugues = true, idioma = "pt", surdos = true))
        assertNull(Idioma.escolherLegenda(listOf(f("en")), audioPortugues = true, idioma = "pt", surdos = true))
    }

    @Test fun semPreferenciaAComumVemAntesDaParaSurdos() {
        // Antes: a primeira do idioma (a SDH, se viesse antes) ganhava.
        val textos = listOf(f("pt", "Português (SDH)"), f("pt", "Português"))
        assertEquals(1, Idioma.escolherLegenda(textos, audioPortugues = false, idioma = "pt"))
        assertEquals(0, Idioma.escolherLegenda(textos, audioPortugues = false, idioma = "pt", surdos = true))
    }

    @Test fun preferenciasViajamNoJson() {
        val p = Idioma.Prefs(audiodescricao = true, surdos = true)
        val volta = Idioma.deJson(Idioma.paraJson(p))
        assertTrue(volta.audiodescricao && volta.surdos)
        val velho = Idioma.deJson("""{"padrao":"ORIGINAL","titulos":{}}""")
        assertFalse(velho.audiodescricao || velho.surdos)
        assertEquals(Idioma.Pref.ORIGINAL, velho.padrao)
    }
}
