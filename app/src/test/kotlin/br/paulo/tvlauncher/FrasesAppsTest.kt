package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class FrasesAppsTest {
    private val fichas = mapOf(
        "pl.solidexplorer2" to FrasesApps.Ficha("Solid Explorer: gerenciador de arquivos.", "File manager"),
        "com.sugarapple.workout" to FrasesApps.Ficha("Home Workout: treinos em casa.", null),
        "com.google.android.youtube.tvmusic" to FrasesApps.Ficha("YouTube Music.", null),
        "com.netflix.ninja" to FrasesApps.Ficha("Netflix. O botão Netflix do controle abre ele.", "Movies and shows"),
    )

    @Test fun manualVenceTudo() {
        assertEquals("Filmes e séries", FrasesApps.escolher("com.netflix.ninja", "Netflix", "Filmes e séries", fichas, true, "Vídeo"))
        assertEquals("Movies and shows", FrasesApps.escolher("com.netflix.ninja", "Netflix", "Movies and shows", fichas, false, "Video"))
    }

    @Test fun fichaEmPortugues() {
        assertEquals("Gerenciador de arquivos", FrasesApps.escolher("pl.solidexplorer2", "Solid Explorer", null, fichas, true, "Produtividade"))
        assertEquals("Treinos em casa", FrasesApps.escolher("com.sugarapple.workout", "Home Workout", null, fichas, true, null))
        // Primeira frase é só o nome: pula para a seguinte.
        assertEquals("O botão Netflix do controle abre ele", FrasesApps.escolher("com.netflix.ninja", "Netflix", null, fichas, true, null))
    }

    @Test fun inglesUsaEnOuCategoriaNuncaPortugues() {
        assertEquals("File manager", FrasesApps.escolher("pl.solidexplorer2", "Solid Explorer", null, fichas, false, "Productivity"))
        assertEquals("Productivity", FrasesApps.escolher("com.sugarapple.workout", "Home Workout", null, fichas, false, "Productivity"))
        assertNull(FrasesApps.escolher("com.sugarapple.workout", "Home Workout", null, fichas, false, null))
    }

    @Test fun fichaQueEhSoONomeCaiNaCategoria() {
        assertEquals("Áudio", FrasesApps.escolher("com.google.android.youtube.tvmusic", "YouTube Music", null, fichas, true, "Áudio"))
    }

    @Test fun semFichaOuTabelaAindaNaoLida() {
        assertEquals("Jogos", FrasesApps.escolher("x.y", "X", null, fichas, true, "Jogos"))
        assertEquals("Jogos", FrasesApps.escolher("pl.solidexplorer2", "Solid Explorer", null, null, true, "Jogos"))
        assertNull(FrasesApps.escolher("x.y", "X", null, null, true, " "))
    }

    @Test fun fraseLongaDemaisNaoEntra() {
        assertNull(FrasesApps.curta("a".repeat(FrasesApps.MAXIMO + 1) + ".", "App"))
    }

    @Test fun jsonQuebradoViraMapaVazio() {
        assertTrue(FrasesApps.ler("{ isto não é json").isEmpty())
    }

    @Test fun tabelaDeVerdade() {
        val arq = listOf("src/main/assets/fichas-apps.json", "app/src/main/assets/fichas-apps.json").map { File(it) }.first { it.exists() }
        val t = FrasesApps.ler(arq.readText())
        assertTrue("tabela com ${t.size} apps", t.size > 80)
        assertEquals("Gerenciador de arquivos", FrasesApps.escolher("pl.solidexplorer2", "Solid Explorer", null, t, true, null))
        assertEquals("File manager", FrasesApps.escolher("pl.solidexplorer2", "Solid Explorer", null, t, false, null))
        // Toda frase em inglês da tabela tem de servir (curta e não só o nome).
        for ((p, f) in t) f.en?.let { assertTrue(p, FrasesApps.curta(it, "") != null) }
    }
}
