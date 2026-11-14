package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Atalhos de voz (ideia 8): cada activity-alias que aponta para a AtalhoVozActivity precisa de destino na tabela
 * [AtalhosVoz.TABELA] (senão "Ok Google, abrir X" abre e fecha sem fazer nada), e cada entrada da tabela precisa do
 * alias no manifesto, com rótulo nos dois idiomas e as mesmas categorias dos outros (é o que o Gemini enxerga).
 */
class AtalhosVozTest {
    // Os testes de unidade rodam com a pasta do módulo (app/) como diretório atual.
    private fun arquivo(rel: String): File = listOf("src/main/$rel", "app/src/main/$rel").map(::File).first { it.isFile }

    private val android = "http://schemas.android.com/apk/res/android"

    private fun aliasesDeVoz(): List<Element> {
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val doc = f.newDocumentBuilder().parse(arquivo("AndroidManifest.xml"))
        val nos = doc.getElementsByTagName("activity-alias")
        return (0 until nos.length).map { nos.item(it) as Element }
            .filter { it.getAttributeNS(android, "targetActivity") == ".AtalhoVozActivity" }
    }

    private fun nome(e: Element) = e.getAttributeNS(android, "name").removePrefix(".")

    /** Todos os <string name="..."> de uma pasta values*. */
    private fun strings(pasta: String): Set<String> {
        val dir = arquivo("res/$pasta/strings.xml").absoluteFile.parentFile!!
        return dir.listFiles { a -> a.name.endsWith(".xml") }!!.flatMap { a ->
            Regex("""<string\s+name="([^"]+)"""").findAll(a.readText()).map { it.groupValues[1] }.toList()
        }.toSet()
    }

    @Test fun cadaAliasTemDestinoEViceVersa() {
        val nomes = aliasesDeVoz().map(::nome)
        assertEquals("alias repetido no manifesto", nomes.size, nomes.toSet().size)
        assertEquals(AtalhosVoz.TABELA.keys, nomes.toSet())
        nomes.forEach { assertTrue("sem destino: $it", AtalhosVoz.destino(it) != null) }
    }

    @Test fun telasDaIdeia8EstaoNaTabela() {
        for (a in listOf("VozFilmes", "VozSeries", "VozNaRede", "VozCasa", "VozBusca", "VozMural", "VozCenas"))
            assertTrue("falta $a", a in AtalhosVoz.TABELA)
        assertNull(AtalhosVoz.destino("VozQualquer"))
        assertNull(AtalhosVoz.destino(""))
    }

    @Test fun aliasNoMesmoPadraoComRotuloNosDoisIdiomas() {
        val en = strings("values")
        val pt = strings("values-pt")
        for (e in aliasesDeVoz()) {
            val n = nome(e)
            assertEquals("$n exported", "true", e.getAttributeNS(android, "exported"))
            val rotulo = e.getAttributeNS(android, "label")
            assertTrue("$n sem rótulo @string", rotulo.startsWith("@string/"))
            val id = rotulo.removePrefix("@string/")
            assertTrue("$n: $id falta em values/", id in en)
            assertTrue("$n: $id falta em values-pt/", id in pt)
            val c = e.getElementsByTagName("category")
            val cats = (0 until c.length).map { (c.item(it) as Element).getAttributeNS(android, "name") }
            assertTrue("$n: LAUNCHER", "android.intent.category.LAUNCHER" in cats)
            assertTrue("$n: LEANBACK_LAUNCHER", "android.intent.category.LEANBACK_LAUNCHER" in cats)
        }
    }
}
