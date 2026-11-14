package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DestinoPainelTest {
    private val codigo = "a".repeat(48)

    @Test fun semDestinoEhOQrDeSempre() = assertEquals("#qr=$codigo", DestinoPainel.fragmento(codigo, null))

    @Test fun destinoComCartaoEAbaSo() {
        assertEquals("#qr=$codigo&ir=extras.cartaoCasa", DestinoPainel.fragmento(codigo, DestinoPainel.CASA))
        assertEquals("#qr=$codigo&ir=fotos", DestinoPainel.fragmento(codigo, DestinoPainel.FOTOS))
    }

    @Test fun porNome() {
        assertEquals(DestinoPainel.MURAL, DestinoPainel.porNome("MURAL"))
        assertNull(DestinoPainel.porNome("x"))
        assertNull(DestinoPainel.porNome(null))
    }

    /** Toda aba e todo cartão de destino existem no index.html (renomear um cartão quebra o QR sem ninguém ver). */
    @Test fun abasECartoesExistemNoPainel() {
        val html = File("src/main/assets/painel/index.html").readText()
        for (d in DestinoPainel.values()) {
            val secao = "aba" + d.aba.replaceFirstChar { it.uppercase() }
            assertTrue(d.name + " aba", html.contains("id=\"$secao\""))
            if (d.cartao.isNotEmpty()) assertTrue(d.name + " cartão", html.contains("id=\"${d.cartao}\""))
        }
    }

    /** O app.js aceita o mesmo formato que a TV gera. */
    @Test fun appJsLeOIr() {
        val js = File("src/main/assets/painel/app.js").readText()
        assertTrue(js.contains("&ir="))
    }
}
