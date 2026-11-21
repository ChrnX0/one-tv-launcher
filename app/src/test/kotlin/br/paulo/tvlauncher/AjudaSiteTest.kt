package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.AjudaQr
import br.paulo.tvlauncher.settings.Manual
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ajuda por QR (degrau 2, 16/11/2026): o site (site/ajuda/<idioma>.json) tem o mesmo manual do app. Falhou? Rode
 * `python3 scripts/gerar-ajuda-site.py` e faça commit do site/ajuda.
 */
class AjudaSiteTest {
    private fun titulos(xml: File): Map<String, String> =
        Regex("""<string name="aj_(\w+?)_titulo">(.*?)</string>""").findAll(xml.readText())
            .associate { it.groupValues[1] to it.groupValues[2].replace("\\'", "'").replace("\\\"", "\"").replace("&amp;", "&") }

    @Test fun siteTemTodosOsAssuntosComOMesmoTitulo() {
        val pares = mapOf("values" to "en", "values-pt" to "pt", "values-es" to "es", "values-ja" to "ja", "values-ar" to "ar")
        for ((pasta, l) in pares) {
            val json = File("../site/ajuda/$l.json")
            assertTrue("falta $json: rode scripts/gerar-ajuda-site.py", json.exists())
            val topicos = JSONObject(json.readText()).getJSONObject("topicos")
            val app = titulos(File("src/main/res/$pasta/strings_ajuda.xml"))
            for (id in Manual.TOPICOS) {
                assertTrue("$l: falta $id no site", topicos.has(id))
                assertEquals("$l/$id: o site ficou para trás (rode scripts/gerar-ajuda-site.py)", app[id], topicos.getJSONObject(id).getString("titulo"))
            }
        }
    }

    @Test fun linkDoQr() {
        assertEquals("https://chrnx0.github.io/sofaly/help.html?l=pt&t=familia", AjudaQr.link("pt", "familia"))
        assertEquals("https://chrnx0.github.io/sofaly/help.html?l=pt-PT&t=tv", AjudaQr.link("pt-PT", "tv"))
        assertEquals("https://chrnx0.github.io/sofaly/help.html?l=id&t=tv", AjudaQr.link("in", "tv"))
        assertEquals("inicio", AjudaQr.topicoDaAba(0))
        assertEquals(null, AjudaQr.topicoDaAba(7))   // a própria aba Ajuda
    }
}
