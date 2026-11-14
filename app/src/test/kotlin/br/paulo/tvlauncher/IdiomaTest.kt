package br.paulo.tvlauncher

import br.paulo.tvlauncher.Idioma.Faixa
import br.paulo.tvlauncher.Idioma.Pref
import br.paulo.tvlauncher.Idioma.Versao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class IdiomaTest {
    // ---- faixas

    private val en = Faixa("en", padrao = true)
    private val pt = Faixa("pt-BR")
    private val ja = Faixa("ja")

    @Test fun dubladoEscolheOPortugues() {
        assertEquals(1, Idioma.escolherAudio(listOf(en, pt), Pref.DUBLADO))
    }

    @Test fun originalEscolheAPadraoQueNaoEPortugues() {
        assertEquals(0, Idioma.escolherAudio(listOf(en, pt), Pref.ORIGINAL))
        // Arquivo brasileiro: o dublado vem primeiro e marcado como padrão; o original é o outro.
        assertEquals(1, Idioma.escolherAudio(listOf(Faixa("por", padrao = true), ja), Pref.ORIGINAL))
    }

    @Test fun umaFaixaSoOuSemIdiomaNaoMexe() {
        assertNull(Idioma.escolherAudio(listOf(pt), Pref.ORIGINAL))
        assertNull(Idioma.escolherAudio(listOf(Faixa(null), Faixa("und")), Pref.ORIGINAL))
        assertNull(Idioma.escolherAudio(listOf(en, ja), Pref.DUBLADO))
    }

    @Test fun rotuloSemIdiomaContaComoPortugues() {
        assertEquals(true, Idioma.ePortugues(Faixa(null, rotulo = "Dublado")))
        assertEquals(true, Idioma.ePortugues(Faixa("und", rotulo = "Português (Brasil)")))
        assertEquals(false, Idioma.ePortugues(Faixa(null, rotulo = "English")))
        assertEquals(true, Idioma.ePortugues(Faixa("pob")))
    }

    @Test fun legendaSoComAudioEstrangeiro() {
        val legs = listOf(Faixa("en"), Faixa("pt", forcada = true), Faixa("pt"))
        assertEquals(2, Idioma.escolherLegenda(legs, audioPortugues = false))       // completa, não a forçada
        assertEquals(1, Idioma.escolherLegenda(legs, audioPortugues = true))        // só a forçada
        assertNull(Idioma.escolherLegenda(listOf(Faixa("pt")), audioPortugues = true))
        assertEquals(0, Idioma.escolherLegenda(listOf(Faixa("pt", forcada = true)), audioPortugues = false))
        assertNull(Idioma.escolherLegenda(listOf(Faixa("en")), audioPortugues = false))
    }

    @Test fun legendaBaixadaSempreLiga() {
        val legs = listOf(Faixa("pt"), Faixa("pt-BR", externa = true))
        assertEquals(1, Idioma.escolherLegenda(legs, audioPortugues = true))
        assertEquals(1, Idioma.escolherLegenda(legs, audioPortugues = false))
    }

    @Test fun escolhaManualViraPreferencia() {
        assertEquals(Pref.DUBLADO, Idioma.prefDaEscolha(pt))
        assertEquals(Pref.ORIGINAL, Idioma.prefDaEscolha(ja))
        assertNull(Idioma.prefDaEscolha(Faixa(null)))
    }

    // ---- ideia 25: idioma da TV

    @Test fun idiomaDaTvNormalizado() {
        assertEquals("pt", Idioma.idiomaDaTv("pt"))
        assertEquals("en", Idioma.idiomaDaTv("EN"))
        assertEquals("he", Idioma.idiomaDaTv("iw"))
        assertEquals("pt", Idioma.idiomaDaTv(""))
        assertEquals("pt", Idioma.idiomaDaTv(null))
    }

    @Test fun tvEmInglesDubladoEOIngles() {
        val faixas = listOf(Faixa("por", padrao = true), Faixa("eng"), ja)
        assertEquals(1, Idioma.escolherAudio(faixas, Pref.DUBLADO, "en"))
        assertEquals(0, Idioma.escolherAudio(faixas, Pref.ORIGINAL, "en"))      // português é "outra língua" aqui
        assertEquals(1, Idioma.escolherAudio(listOf(ja, Faixa("en-US")), Pref.DUBLADO, "en"))
        assertNull(Idioma.escolherAudio(listOf(pt, ja), Pref.DUBLADO, "en"))
        // Rótulo "Dublado" sem idioma só vale para português.
        assertEquals(false, Idioma.eDoIdioma(Faixa(null, rotulo = "Dublado"), "en"))
        assertEquals(false, Idioma.eDoIdioma(Faixa(null, rotulo = "English"), "en"))
    }

    @Test fun tvEmInglesLegendaEmIngles() {
        val legs = listOf(Faixa("pt"), Faixa("eng", forcada = true), Faixa("en"))
        assertEquals(2, Idioma.escolherLegenda(legs, audioPortugues = false, idioma = "en"))
        assertEquals(1, Idioma.escolherLegenda(legs, audioPortugues = true, idioma = "en"))
        assertNull(Idioma.escolherLegenda(listOf(Faixa("pt")), audioPortugues = false, idioma = "en"))
        assertEquals(Pref.DUBLADO, Idioma.prefDaEscolha(Faixa("eng"), "en"))
        assertEquals(Pref.ORIGINAL, Idioma.prefDaEscolha(pt, "en"))
    }

    @Test fun tvEmEspanhol() {
        val faixas = listOf(en, Faixa("spa"), pt)
        assertEquals(1, Idioma.escolherAudio(faixas, Pref.DUBLADO, "es"))
        assertEquals(0, Idioma.escolherAudio(faixas, Pref.ORIGINAL, "es"))
        assertEquals(1, Idioma.escolherLegenda(listOf(Faixa("pt"), Faixa("es-419")), audioPortugues = false, idioma = "es"))
        assertEquals(true, Idioma.eDoIdioma(Faixa("es-ES"), "es"))
        assertEquals(false, Idioma.eDoIdioma(Faixa("und", rotulo = "Español"), "es"))
    }

    @Test fun tvEmPortuguesIgualAntes() {
        // Os mesmos casos do começo, agora com o idioma explícito: nada muda para a TV em português.
        assertEquals(1, Idioma.escolherAudio(listOf(en, pt), Pref.DUBLADO, "pt"))
        assertEquals(true, Idioma.eDoIdioma(Faixa(null, rotulo = "Dublado"), "pt"))
        assertEquals(2, Idioma.escolherLegenda(listOf(Faixa("en"), Faixa("pt", forcada = true), Faixa("pt")), false, "pt"))
        assertEquals("pt-BR", Idioma.tagLegendaBaixada("pt"))
        assertEquals("en", Idioma.tagLegendaBaixada("en"))
    }

    // ---- preferência

    @Test fun preferenciaPorTituloVenceAGeral() {
        var p = Idioma.Prefs(padrao = Pref.ORIGINAL)
        p = Idioma.lembrar(p, "f:s1", Pref.DUBLADO)
        assertEquals(Pref.DUBLADO, p.para("f:s1"))
        assertEquals(Pref.ORIGINAL, p.para("f:s2"))
        val volta = Idioma.deJson(Idioma.paraJson(p))
        assertEquals(p, volta)
    }

    @Test fun jsonEstragadoDaOPadrao() {
        assertEquals(Idioma.Prefs(), Idioma.deJson("{x"))
        assertEquals(Idioma.Prefs(), Idioma.deJson(null))
        assertEquals(Pref.DUBLADO, Idioma.deJson("""{"padrao":"OUTRO","titulos":{"a":"NADA"}}""").padrao)
    }

    @Test fun lembrarTemLimite() {
        var p = Idioma.Prefs()
        repeat(Idioma.MAX_TITULOS + 10) { p = Idioma.lembrar(p, "t$it", Pref.ORIGINAL) }
        assertEquals(Idioma.MAX_TITULOS, p.titulos.size)
        assertEquals(null, p.titulos["t0"])
    }

    // ---- versão no catálogo

    @Test fun marcasNoNomeENaCategoria() {
        assertEquals(Versao.LEGENDADA, Idioma.versao("Duna: Parte 2 (2024) [L]", "Filmes"))
        assertEquals(Versao.LEGENDADA, Idioma.versao("Duna - Parte 2 (LEG)", ""))
        assertEquals(Versao.LEGENDADA, Idioma.versao("Duna Parte 2 - Legendado", ""))
        assertEquals(Versao.DUBLADA, Idioma.versao("Duna: Parte 2 (2024) [D]", ""))
        assertEquals(Versao.DUBLADA, Idioma.versao("Duna Parte 2 Dublado", ""))
        assertEquals(Versao.LEGENDADA, Idioma.versao("Duna: Parte 2", "FILMES | LEGENDADOS"))
        assertEquals(Versao.DUBLADA, Idioma.versao("Duna: Parte 2", "Séries Dubladas"))
        assertEquals(Versao.DESCONHECIDA, Idioma.versao("Duna: Parte 2", "Ficção"))
        // Palavra que só contém "leg"/"dub" não é marca.
        assertEquals(Versao.DESCONHECIDA, Idioma.versao("Legião Urbana", ""))
        assertEquals(Versao.DESCONHECIDA, Idioma.versao("Dublin", ""))
        assertEquals(Versao.DESCONHECIDA, Idioma.versao("O Colega", ""))
    }

    @Test fun chaveIgnoraMarcaAnoAcentoEPontuacao() {
        val a = Idioma.chaveTitulo("Duna: Parte 2 (2024) [L]")
        assertEquals(a, Idioma.chaveTitulo("Duna - Parte 2 (2024)"))
        assertEquals("a origem", Idioma.chaveTitulo("A Origem (LEG)"))
        assertEquals(Idioma.chaveTitulo("Coração Valente"), Idioma.chaveTitulo("Coracao Valente [D]"))
    }

    private data class F(val nome: String, val grupo: String, val ano: Int?)

    private fun par(alvo: F, lista: List<F>): F? =
        Idioma.outraVersao(alvo, Idioma.indice(lista, { it.nome }, { it.grupo }), { it.nome }, { it.grupo }, { it.ano })

    @Test fun achaOParNaOutraVersao() {
        val dub = F("Duna: Parte 2 (2024)", "Filmes Dublados", 2024)
        val leg = F("Duna - Parte 2 [L]", "Lançamentos", 2024)
        val outroAno = F("Duna (1984) [L]", "", 1984)
        val lista = listOf(outroAno, dub, leg)
        assertSame(leg, par(dub, lista))
        assertSame(dub, par(leg, lista))
    }

    @Test fun semMarcaOuAnoDiferenteNaoTemPar() {
        val semMarca = F("Duna", "Ficção", 2021)
        val leg = F("Duna [L]", "", 2021)
        assertNull(par(semMarca, listOf(semMarca, leg)))        // o alvo sem marca: não se sabe o que ele é
        assertNull(par(leg, listOf(semMarca, leg)))             // o par sem marca: idem
        val dub84 = F("Duna (1984) [D]", "", 1984)
        assertNull(par(leg, listOf(leg, dub84)))                // anos diferentes: outro filme
        val dubSemAno = F("Duna [D]", "", null)
        assertSame(dubSemAno, par(leg, listOf(leg, dubSemAno))) // um sem ano: aceita
    }

    @Test fun versaoDesejada() {
        assertEquals(Versao.LEGENDADA, Idioma.versaoDesejada(Pref.ORIGINAL))
        assertEquals(Versao.DUBLADA, Idioma.versaoDesejada(Pref.DUBLADO))
    }
}
