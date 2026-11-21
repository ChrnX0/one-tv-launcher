package br.paulo.tvlauncher

import br.paulo.tvlauncher.settings.VisualCodigo
import java.io.File
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualCodigoTest {
    private val v = VisualCodigo.Visual("GALERIA", 110, "com.exemplo.icones", true, 20, -20)

    @Test fun idaEVolta() {
        val c = VisualCodigo.codificar(v)
        assertTrue(c.startsWith(VisualCodigo.PREFIXO))
        assertTrue("cabe num QR pequeno: ${c.length}", c.length < 200)
        assertEquals(v, VisualCodigo.decodificar(c))
        assertEquals(v, VisualCodigo.decodificar("https://x.github.io/one-tv-launcher/visual.html#$c"))
    }

    @Test fun recusaLixo() {
        assertNull(VisualCodigo.decodificar("qualquer coisa"))
        assertNull(VisualCodigo.decodificar(VisualCodigo.PREFIXO + "!!!"))
        assertNull(VisualCodigo.decodificar(VisualCodigo.PREFIXO + "A".repeat(2000)))
    }

    @Test fun valoresForaDaFaixaNaoAplicam() {
        val json = """{"t":"ROXO","x":300,"i":"../../x","c":"sim","b":500,"k":-20,"z":1}"""
        val c = VisualCodigo.PREFIXO + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        assertEquals(VisualCodigo.Visual(contraste = -20), VisualCodigo.decodificar(c))
    }

    /** Código feito antes de 21/11/2026 com cartões, transparência, grade e pilhas (HOME antiga): vale, sem esses campos. */
    @Test fun codigoDaHomeAntigaIgnoraOQueSaiu() {
        val json = """{"t":"ESCURO","x":100,"c":false,"p":true,"s":115,"v":true,"g":true}"""
        val c = VisualCodigo.PREFIXO + Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())
        assertEquals(VisualCodigo.Visual("ESCURO", 100, corDoFundo = false), VisualCodigo.decodificar(c))
    }

    @Test fun prefixoAntigoContinuaValendo() {
        val novo = VisualCodigo.codificar(v)
        assertTrue(novo.startsWith("sofaly-visual:1:"))
        val antigo = VisualCodigo.PREFIXO_ANTIGO + novo.removePrefix(VisualCodigo.PREFIXO)
        assertEquals(v, VisualCodigo.decodificar(antigo))
    }

    @Test fun prontosValidosECiclo() {
        assertEquals(5, VisualCodigo.PRONTOS.size)
        assertEquals(VisualCodigo.PRONTOS.size, VisualCodigo.PRONTOS.map { it.id }.toSet().size)
        for (p in VisualCodigo.PRONTOS) {
            assertEquals(p.visual, VisualCodigo.decodificar(VisualCodigo.codificar(p.visual)))   // nada fora da faixa
            assertEquals(p, VisualCodigo.prontoAtual(p.visual))
        }
        val ultimo = VisualCodigo.PRONTOS.last().visual
        assertEquals(VisualCodigo.PRONTOS.first(), VisualCodigo.proximoPronto(ultimo))
        assertEquals(VisualCodigo.PRONTOS[1], VisualCodigo.proximoPronto(VisualCodigo.PRONTOS[0].visual))
        // visual ajustado à mão (pacote de ícones e brilho não contam): começa pelo primeiro
        assertEquals(VisualCodigo.PRONTOS.first(), VisualCodigo.proximoPronto(VisualCodigo.Visual("ESCURO", 110, brilho = 10)))
    }

    /** A galeria do site (scripts/gerar-visual.py) repete os PRONTOS: mesmos visuais, mesma ordem. */
    @Test fun siteBateComOApp() {
        val html = File("../site/visual.html").readText()
        val codigos = Regex("""data-codigo="([^"]+)"""").findAll(html).map { it.groupValues[1] }.distinct().toList()
        assertEquals(VisualCodigo.PRONTOS.map { it.visual }, codigos.map { VisualCodigo.decodificar(it) })
    }
}
