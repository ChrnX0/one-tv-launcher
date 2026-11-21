package br.paulo.tvlauncher

import br.paulo.tvlauncher.family.Classificacao
import br.paulo.tvlauncher.family.ControleParental
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassificacaoTest {
    @Test fun idadesDoBrasil() {
        assertEquals(0, Classificacao.idadeBr("L"))
        assertEquals(0, Classificacao.idadeBr("AL"))
        assertEquals(12, Classificacao.idadeBr("A12"))
        assertEquals(16, Classificacao.idadeBr(" 16 "))
        assertNull(Classificacao.idadeBr("13"))
        assertNull(Classificacao.idadeBr("PG"))
    }

    @Test fun filmePrefereBrasilDepoisEuaParaCima() {
        val br = """{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"PG"}]},
            {"iso_3166_1":"BR","release_dates":[{"certification":""},{"certification":"12"}]}]}"""
        assertEquals(12, Classificacao.lerFilme(br))
        assertEquals(14, Classificacao.lerFilme("""{"results":[{"iso_3166_1":"US","release_dates":[{"certification":"PG-13"}]}]}"""))
        assertEquals(Classificacao.SEM, Classificacao.lerFilme("""{"results":[{"iso_3166_1":"FR","release_dates":[{"certification":"U"}]}]}"""))
        assertNull(Classificacao.lerFilme("lixo"))
    }

    @Test fun serieUsaContentRatings() {
        assertEquals(10, Classificacao.lerSerie("""{"results":[{"iso_3166_1":"BR","rating":"10"},{"iso_3166_1":"US","rating":"TV-MA"}]}"""))
        assertEquals(18, Classificacao.lerSerie("""{"results":[{"iso_3166_1":"US","rating":"TV-MA"}]}"""))
    }

    @Test fun regraDoModoInfantil() {
        val inf = setOf("Infantil"); val duras = setOf("Escondida")
        assertTrue(Classificacao.visivel("Ação", 10, 12, inf, duras))          // classificado, de qualquer categoria
        assertFalse(Classificacao.visivel("Infantil", 14, 12, inf, duras))     // passa da idade: some até da infantil
        assertTrue(Classificacao.visivel("Infantil", null, 12, inf, duras))    // sem classificação: só na infantil
        assertFalse(Classificacao.visivel("Ação", Classificacao.SEM, 12, inf, duras))
        assertFalse(Classificacao.visivel("Escondida", 0, 12, inf, duras))     // categoria dura nunca aparece
        assertFalse(Classificacao.visivel("Filmes Adultos XXX", 0, 16, inf, emptySet()))
    }

    @Test fun parentalGuardaAIdade() {
        val r = ControleParental.deJson(ControleParental.paraJson(ControleParental.Regras(infantil = true, idadeMax = 12)))
        assertEquals(12, r.idadeMax)
        assertEquals(Classificacao.DESLIGADA, ControleParental.deJson("""{"idade":13}""").idadeMax)
        assertEquals(Classificacao.DESLIGADA, ControleParental.deJson("{}").idadeMax)
    }
}
