package br.paulo.tvlauncher

import br.paulo.tvlauncher.observer.ObserverCore
import br.paulo.tvlauncher.observer.SofyMotion
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** As frases da Sofy existem em todas as línguas do app, com os mesmos números (%1$s) que o inglês. */
class SofyStringsTest {
    private val res = listOf(File("src/main/res"), File("app/src/main/res")).first { it.isDirectory }
    private val linha = Regex("""<string name="([a-z_]+)">(.*)</string>""")
    private fun ler(f: File) = f.readLines().mapNotNull { linha.find(it) }.associate { it.groupValues[1] to it.groupValues[2] }
    private fun numeros(s: String) = Regex("%\\d\\\$s").findAll(s).map { it.value }.toSet()

    @Test fun everyNoteKindHasTitleAndTextInEveryLanguage() {
        val en = ler(File(res, "values/strings_sofy.xml"))
        val esperado = ObserverCore.Kind.entries.filter { !it.name.startsWith("UNUSUAL_") }
            .flatMap { k -> listOf("observer_${k.name.lowercase()}_title", "observer_${k.name.lowercase()}_text") }.toSet()
        assertEquals(esperado, en.keys)
        val linguas = res.listFiles()!!.filter { it.name.startsWith("values-") && File(it, "strings.xml").exists() }
        assertTrue(linguas.size >= 14)
        for (d in linguas) {
            val f = File(d, "strings_sofy.xml")
            assertTrue("falta ${f.path}", f.exists())
            val t = ler(f)
            assertEquals(d.name, en.keys, t.keys)
            en.forEach { (k, v) -> assertEquals("${d.name} $k", numeros(v), numeros(t.getValue(k))) }
        }
    }

    @Test fun talkingMouthIsAscii() {
        assertEquals("o", SofyMotion.talkingMouth(true))
        assertEquals("-", SofyMotion.talkingMouth(false))
    }
}
