package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Ajuda e manual (14/11/2026): cada assunto de [Manual.TOPICOS] tem título, resumo e texto em inglês (values/) e em
 * português (values-pt/), sem sobra de um lado só; e o texto cita caminhos de menu que existem.
 */
class ManualTest {
    // Os testes de unidade rodam com a pasta do módulo (app/) como diretório atual.
    private fun arquivo(rel: String): File = listOf("src/main/$rel", "app/src/main/$rel").map(::File).first { it.isFile }

    private fun strings(pasta: String): Map<String, String> =
        Regex("""<string name="(aj_[^"]+)">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(arquivo("res/$pasta/strings_ajuda.xml").readText())
            .associate { it.groupValues[1] to it.groupValues[2] }

    @Test fun cadaAssuntoTemAsTresPartesNosDoisIdiomas() {
        for (pasta in listOf("values", "values-pt")) {
            val s = strings(pasta)
            for (id in Manual.TOPICOS) for (p in Manual.Parte.values()) {
                val nome = Manual.recurso(id, p)
                assertTrue("$pasta: falta $nome", s[nome]?.isNotBlank() == true)
            }
        }
    }

    @Test fun osDoisIdiomasTemAsMesmasChaves() = assertEquals(strings("values").keys, strings("values-pt").keys)

    @Test fun nenhumAssuntoSobrandoForaDaLista() {
        val ids = strings("values-pt").keys.mapNotNull { Regex("""aj_(\w+)_titulo""").matchEntire(it)?.groupValues?.get(1) }
        assertEquals(Manual.TOPICOS.toSet(), ids.toSet())
        assertEquals("assunto repetido", Manual.TOPICOS.size, Manual.TOPICOS.toSet().size)
    }

    /** Aspas retas somem no aapt e apóstrofo sem barra quebra o build: no manual, só aspas curvas e \'. */
    @Test fun semAspasRetasNemApostrofoSolto() {
        for (pasta in listOf("values", "values-pt")) for ((nome, v) in strings(pasta)) {
            assertFalse("$pasta/$nome tem aspas retas", v.contains('"'))
            assertFalse("$pasta/$nome tem apóstrofo sem barra", Regex("""(?<!\\)'""").containsMatchIn(v))
        }
    }

    /** As abas citadas ("Configurações › X") existem no menu das Configurações, no mesmo idioma. */
    @Test fun abasCitadasExistem() {
        fun abas(pasta: String): Set<String> {
            val nomes = listOf("cfg2_aba_inicio", "cl_aba_conta", "cfg2_aba_familia", "cl_aba_energia", "cfg2_aba_casa",
                "cl_aba_remoto", "cl_aba_sistema", "aj_aba", "cfg2_aba_avancado")
            val dir = arquivo("res/$pasta/strings.xml").absoluteFile.parentFile!!
            val todas = dir.listFiles { a -> a.name.endsWith(".xml") }!!.joinToString("\n") { it.readText() }
            return nomes.map { n -> Regex("""<string name="$n">([^<]+)</string>""").find(todas)!!.groupValues[1] }.toSet()
        }
        for ((pasta, raiz) in listOf("values" to "Settings", "values-pt" to "Configurações")) {
            val validas = abas(pasta)
            // Depois de "Configurações › " vem o nome de uma aba (e então " ›", pontuação ou o resto da frase).
            for ((nome, v) in strings(pasta)) for (m in Regex("""$raiz › """).findAll(v)) {
                val resto = v.substring(m.range.last + 1)
                assertTrue("$pasta/$nome cita uma aba que não existe: “${resto.take(30)}”",
                    validas.any { aba -> resto.startsWith(aba) && (resto.length == aba.length || !resto[aba.length].isLetter()) })
            }
        }
    }

    @Test fun vizinhoDaAVolta() {
        val n = Manual.TOPICOS.size
        assertEquals(1, Manual.vizinho(0, 1))
        assertEquals(n - 1, Manual.vizinho(0, -1))
        assertEquals(0, Manual.vizinho(n - 1, 1))
        assertEquals("aj_tv_texto", Manual.recurso("tv", Manual.Parte.TEXTO))
    }
}
