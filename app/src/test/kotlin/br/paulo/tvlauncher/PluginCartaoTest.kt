package br.paulo.tvlauncher

import br.paulo.tvlauncher.screensaver.PluginCartao
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plugins (degrau 3): o cartão que um plugin manda é limpo, curto e não abre outro app. */
class PluginCartaoTest {
    private val pkg = "com.exemplo.placar"

    @Test fun limpaECorta() {
        val c = PluginCartao.limpar(pkg, mapOf("titulo" to "  Placar\n do\tjogo ", "valor" to "2 x 1 (2º tempo, 40 min)", "detalhe" to null, "cor" to "#1E8E3E"))!!
        assertEquals("Placar do jogo", c.titulo)
        assertEquals(12, c.valor.length)
        assertEquals("", c.detalhe)
        assertEquals(0xFF1E8E3E.toInt(), c.cor)
        assertNull(c.acao)
        assertNull(PluginCartao.limpar(pkg, mapOf("titulo" to " ", "valor" to "")))
    }

    @Test fun corSemTransparencia() {
        assertEquals(PluginCartao.COR_PADRAO, PluginCartao.cor("#801E8E3E"))
        assertEquals(PluginCartao.COR_PADRAO, PluginCartao.cor("red"))
    }

    @Test fun acaoSoDoProprioPlugin() {
        assertTrue(PluginCartao.acaoValida(pkg, "intent:#Intent;action=android.intent.action.VIEW;package=$pkg;end"))
        assertTrue(PluginCartao.acaoValida(pkg, "intent:#Intent;action=android.intent.action.MAIN;end"))
        assertFalse(PluginCartao.acaoValida(pkg, "intent:#Intent;package=br.paulo.tvlauncher;end"))
        assertFalse(PluginCartao.acaoValida(pkg, "intent:#Intent;component=com.android.settings/.Settings;end"))
        assertFalse(PluginCartao.acaoValida(pkg, "intent:#Intent;SEL;package=$pkg;end"))
        assertFalse(PluginCartao.acaoValida(pkg, "https://exemplo.com"))
    }

    @Test fun permissaoPorCertificado() {
        val ok = mapOf(pkg to "ab12")
        assertTrue(PluginCartao.permitido(ok, pkg, "ab12"))
        assertFalse(PluginCartao.permitido(ok, pkg, "ff00"))   // outro certificado = outro app
        assertFalse(PluginCartao.permitido(ok, pkg, null))
        assertFalse(PluginCartao.permitido(emptyMap(), pkg, "ab12"))
    }
}
