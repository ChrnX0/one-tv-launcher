package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class VisualCodigoTest {
    private val v = VisualCodigo.Visual("GALERIA", 110, "com.exemplo.icones", true, false, 20, -20)

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
}
