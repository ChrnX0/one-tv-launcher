package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.item
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A lista "Conferir na TV" (ConferirTvActivity) junta três arrays por posição: ids, títulos e perguntas. Quem reescreve
 * a lista antes de cada Release já colou um item uma posição antes (15/11/2026: o título em inglês foi parar em
 * roteiro_ids e a pergunta em roteiro_titulos). Este teste pega isso antes da Release.
 */
class RoteiroArraysTest {
    private fun arrays(pasta: String): Map<String, List<String>> {
        val f = listOf(File("src/main/res/$pasta/strings_versoes.xml"), File("app/src/main/res/$pasta/strings_versoes.xml")).first { it.exists() }
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(f)
        val lista = doc.getElementsByTagName("string-array")
        return (0 until lista.length).associate { i ->
            val a = lista.item(i) as org.w3c.dom.Element
            val itens = a.getElementsByTagName("item")
            a.getAttribute("name") to (0 until itens.length).map { itens.item(it).textContent }
        }
    }

    @Test fun idsTitulosEPerguntasAlinhados() {
        val en = arrays("values"); val pt = arrays("values-pt")
        val ids = en.getValue("roteiro_ids")
        for ((nome, a) in listOf("en" to en, "pt" to pt)) {
            assertEquals("$nome roteiro_titulos", ids.size, a.getValue("roteiro_titulos").size)
            assertEquals("$nome roteiro_perguntas", ids.size, a.getValue("roteiro_perguntas").size)
        }
        assertEquals("ids repetidos", ids.size, ids.toSet().size)
        ids.forEach { assertTrue("id que parece texto: $it", Regex("^[a-z0-9_]+$").matches(it)) }
        assertEquals("novidades en/pt", en.getValue("novidades").size, pt.getValue("novidades").size)
    }
}
