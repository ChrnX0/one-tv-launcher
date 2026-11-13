package br.paulo.tvlauncher

import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PaginaCertificadoTest {
    private val modelo: String = listOf("src/main/assets/painel/certificado.html", "app/src/main/assets/painel/certificado.html")
        .map(::File).first { it.isFile }.readText()

    private fun pronto(dias: Long = 800, ipNoCert: Boolean = true) = JSONObject()
        .put("ligado", true).put("pronto", true).put("porta", 8443)
        .put("endereco", "https://tv:8443/")
        .put("enderecoIp", if (ipNoCert) "https://192.168.1.50:8443/" else JSONObject.NULL)
        .put("ipNoCertificado", ipNoCert)
        .put("impressao", "AB:CD").put("validoAte", 1_864_000_000_000L).put("diasRestantes", dias)

    private fun semSobras(html: String) {
        assertFalse("sobrou {{", html.contains("{{"))
        assertFalse("sobrou marcador", html.contains("<!--se:") || html.contains("<!--fim:"))
    }

    @Test fun prontoMostraPassosEEndereco() {
        val h = PaginaCertificado.preencher(modelo, pronto(), "tv")
        semSobras(h)
        assertTrue(h.contains("href=\"https://tv:8443/\""))
        assertTrue(h.contains("https://192.168.1.50:8443/"))
        assertTrue(h.contains("/certificado.crt"))
        assertTrue(h.contains("AB:CD"))
        assertFalse(h.contains("Ainda não está pronto"))
        assertFalse(h.contains("dias para vencer"))
    }

    @Test fun naoProntoSoAvisa() {
        val h = PaginaCertificado.preencher(modelo, JSONObject().put("ligado", false).put("pronto", false).put("porta", 8443), "tv")
        semSobras(h)
        assertTrue(h.contains("Ainda não está pronto"))
        assertFalse(h.contains("/certificado.crt"))
    }

    @Test fun vencendoAvisaESemIpNaoMostraOIp() {
        val h = PaginaCertificado.preencher(modelo, pronto(dias = 20, ipNoCert = false), "tv")
        semSobras(h)
        assertTrue(h.contains("Faltam 20 dias"))
        assertFalse(h.contains("192.168.1.50"))
    }

    @Test fun valoresEscapados() {
        val h = PaginaCertificado.preencher("{{NOME}}|{{IMPRESSAO}}", pronto().put("impressao", "<b>\"x\"&'"), "<script>")
        assertTrue(h, h == "&lt;script&gt;|&lt;b&gt;&quot;x&quot;&amp;&#39;")
    }
}
