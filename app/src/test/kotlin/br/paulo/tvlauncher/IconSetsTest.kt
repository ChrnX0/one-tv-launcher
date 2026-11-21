package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.IconSetArt
import br.paulo.tvlauncher.home.IconeCruz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Conjuntos de ícones com arte pronta (21/11/2026): cada tipo da cruz tem as duas camadas (parada e a que se mexe) em
 * cada conjunto, com um movimento conhecido e o ponto de giro dentro do quadro. Sem isso, um ícone cai no Objeto
 * (arquivo faltando) ou fica parado sem aviso. Também guarda a ordem do enum: o número gravado nas Extras não muda.
 */
class IconSetsTest {
    private fun pasta(): File = listOf("src/main/assets/icon-sets", "app/src/main/assets/icon-sets").map(::File).first { it.isDirectory }
    private val movimentos = setOf("spin", "swing", "wiggle", "nudge", "bob", "pulse", "twinkle")

    @Test fun cadaTipoTemAsDuasCamadasEUmMovimento() {
        val m = IconSetArt.lerMovimentos(File(pasta(), "motion.json").readText())
        val conjuntos = IconeCruz.Estilo.entries.mapNotNull { it.pasta }
        assertEquals(listOf("vintage", "jewel", "photoreal", "vintage2"), conjuntos)
        for (c in conjuntos) for (t in IconeCruz.Tipo.entries) {
            val nome = IconSetArt.arquivo(t)
            assertTrue("falta $c/$nome.webp", File(pasta(), "$c/$nome.webp").isFile)
            assertTrue("falta $c/$nome-a.webp", File(pasta(), "$c/$nome-a.webp").isFile)
            val mv = m["$c/$nome"] ?: error("sem movimento: $c/$nome")
            assertTrue("$c/$nome: movimento ${mv.m}", mv.m in movimentos)
            assertTrue("$c/$nome: giro fora do quadro", mv.px in 0f..1f && mv.py in 0f..1f)
        }
    }

    @Test fun aOrdemDosEstilosNaoMuda() {
        assertEquals(listOf("OBJETO", "SIMBOLO", "DUOTOM", "LINHA", "VINTAGE", "JOIA", "FOTO", "VINTAGE2"), IconeCruz.Estilo.entries.map { it.name })
        assertTrue(IconeCruz.Estilo.entries.filter { it.animado }.map { it.name } == listOf("LINHA", "VINTAGE", "JOIA", "FOTO", "VINTAGE2"))
    }
}
