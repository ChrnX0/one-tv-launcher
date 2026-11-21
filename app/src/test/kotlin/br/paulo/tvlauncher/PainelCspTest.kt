package br.paulo.tvlauncher

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * A CSP do painel (PainelHttp: img-src 'self' blob:) não aceita imagem data:. Um ícone em data: no CSS some em
 * silêncio no navegador de verdade (o cadeado dos apps protegidos sumiu assim; visto na Sala em 21/11/2026).
 */
class PainelCspTest {
    @Test fun semImagemDataNoCssNemNoJs() {
        val pasta = listOf(File("src/main/assets/painel"), File("app/src/main/assets/painel")).first { it.isDirectory }
        val ruins = pasta.listFiles().orEmpty().filter { it.name.endsWith(".css") || it.name.endsWith(".js") }
            .filter { Regex("""url\(\s*["']?data:image""").containsMatchIn(it.readText()) }.map { it.name }
        assertTrue("imagem data: bloqueada pela CSP em: $ruins", ruins.isEmpty())
    }
}
