package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.DestinoPainel
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DestinoPainelTest {
    private val codigo = "a".repeat(48)

    @Test fun semDestinoEhOQrDeSempre() = assertEquals("#qr=$codigo", DestinoPainel.fragmento(codigo, null))

    @Test fun destinoComPaginaECartao() {
        // Painel v3 (16/11/2026): a página segue as abas das Configurações da TV; o cartão leva direto ao lugar certo.
        assertEquals("#qr=$codigo&ir=casa.cartaoCasa", DestinoPainel.fragmento(codigo, DestinoPainel.CASA))
        assertEquals("#qr=$codigo&ir=avancado.cartaoRoteadorUniFi", DestinoPainel.fragmento(codigo, DestinoPainel.ROTEADOR))
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
