package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File

/** Reorganização das Configurações e o fim do card "Ocultos" na HOME (14/11/2026). Conferência no código-fonte. */
class ConfiguracoesOrganizacaoTest {
    private val pasta = File("src/main/kotlin/br/paulo/tvlauncher")

    /** O dono não quer um card "Ocultos (N)" na tela inicial: os ocultos voltam por Configurações > Tela inicial. */
    @Test fun homeNaoGanhaCardDeOcultos() =
        assertFalse(File(pasta, "MainActivity.kt").readText().contains("entradas.add(Entrada.Ocultos"))

    /** ABA_CONTA e ABA_AVANCADO apontam para as abas certas na lista (outras telas abrem por esses índices). */
    @Test fun indicesDasAbasBatemComALista() {
        val t = File(pasta, "ConfigLauncherActivity.kt").readText()
        val lista = t.substringAfter("private val abas: List<Aba> by lazy {").substringBefore("\n        )")
        val montadores = Regex("""this::(\w+)\)""").findAll(lista).map { it.groupValues[1] }.toList()
        assertEquals(8, montadores.size)
        assertEquals("abaConta", montadores[ConfigLauncherActivity.ABA_CONTA])
        assertEquals("abaAvancado", montadores[ConfigLauncherActivity.ABA_AVANCADO])
    }
}
