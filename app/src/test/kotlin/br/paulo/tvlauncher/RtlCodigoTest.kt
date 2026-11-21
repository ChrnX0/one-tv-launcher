package br.paulo.tvlauncher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Elevação 16.8: o árabe só espelha se o código não fixar esquerda e direita. Varre o código: margem e recuo sempre
 * "start/end", gravidade START/END e o manifesto com supportsRtl.
 */
class RtlCodigoTest {
    private val pasta = File("src/main/kotlin/br/paulo/tvlauncher")

    @Test fun manifestoAceitaDireitaParaEsquerda() {
        assertTrue(File("src/main/AndroidManifest.xml").readText().contains("""android:supportsRtl="true""""))
    }

    @Test fun nadaFixaEsquerdaOuDireita() {
        assertTrue("rodar a partir da pasta app/", pasta.isDirectory)
        val proibidos = listOf(Regex("""\b(left|right)Margin\s*="""), Regex("""Gravity\.(LEFT|RIGHT)\b"""), Regex("""(?<![\w.])setPadding\(|\.setPadding\("""))
        val achados = mutableListOf<String>()
        CodeSource.all().forEach { f ->
            f.readLines().forEachIndexed { i, l ->
                val codigo = l.substringBefore("//")
                if (proibidos.any { it.containsMatchIn(codigo) }) achados += "${f.name}:${i + 1}: ${l.trim().take(100)}"
            }
        }
        assertTrue("use marginStart/marginEnd, Gravity.START/END e setPaddingRelative:\n" + achados.joinToString("\n"), achados.isEmpty())
    }
}
