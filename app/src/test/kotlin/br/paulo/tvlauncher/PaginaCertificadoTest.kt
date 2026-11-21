package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.PaginaCertificado
import br.paulo.tvlauncher.panel.PainelHttp
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PaginaCertificadoTest {
    private val modelo: String = listOf("src/main/assets/painel/certificado.html", "app/src/main/assets/painel/certificado.html")
        .map(::File).first { it.isFile }.readText()

    private fun pronto(dias: Long = 800, ipNoCert: Boolean = true) = JSONObject()
        .put("ligado", true).put("pronto", true).put("porta", 8444)
        .put("endereco", "https://tv:8444/")
        .put("enderecoIp", if (ipNoCert) "https://192.168.1.50:8444/" else JSONObject.NULL)
        .put("ipNoCertificado", ipNoCert)
        .put("impressao", "AB:CD").put("validoAte", 1_864_000_000_000L).put("diasRestantes", dias)

    private fun semSobras(html: String) {
        assertFalse("sobrou {{", html.contains("{{"))
        assertFalse("sobrou marcador", html.contains("<!--se:") || html.contains("<!--fim:"))
    }

    @Test fun prontoMostraPassosEEndereco() {
        val h = PaginaCertificado.preencher(modelo, pronto(), "tv")
        semSobras(h)
        assertTrue(h.contains("href=\"https://tv:8444/\""))
        assertTrue(h.contains("https://192.168.1.50:8444/"))
        assertTrue(h.contains("/certificado.crt"))
        assertTrue(h.contains("AB:CD"))
        assertFalse(h.contains("Ainda não está pronto"))
        assertFalse(h.contains("dias para vencer"))
    }

    @Test fun naoProntoSoAvisa() {
        val h = PaginaCertificado.preencher(modelo, JSONObject().put("ligado", false).put("pronto", false).put("porta", 8444), "tv")
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

    // 16/11/2026: o sistema de quem abriu vem primeiro e aberto; os outros fechados.
    @Test fun sistemaPeloUserAgent() {
        val s = PaginaCertificado::sistema
        assertTrue(s("Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 Chrome/131 Mobile Safari/537.36") == PaginaCertificado.Sistema.ANDROID)
        assertTrue(s("Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X) AppleWebKit/605.1.15 Mobile/15E148 Safari/604.1") == PaginaCertificado.Sistema.IOS)
        assertTrue(s("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36 Edg/131") == PaginaCertificado.Sistema.WINDOWS)
        assertTrue(s("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 Version/18.1 Safari/605.1.15") == PaginaCertificado.Sistema.MAC)
        assertTrue(s("Mozilla/5.0 (X11; Linux x86_64; rv:132.0) Gecko/20100101 Firefox/132.0") == PaginaCertificado.Sistema.OUTRO)
        assertTrue(s(null) == PaginaCertificado.Sistema.OUTRO)
    }

    @Test fun sistemaDoAparelhoVemAberto() {
        val h = PaginaCertificado.preencher(modelo, pronto(), "tv", "Mozilla/5.0 (iPhone; CPU iPhone OS 18_1 like Mac OS X)")
        semSobras(h)
        assertTrue(h.contains("<details class=\"so primeiro\" open>\n      <summary>iPhone ou iPad</summary>"))
        assertTrue(h.contains("<details class=\"so \" >\n      <summary>Android"))
        assertTrue(h.contains("parece ser: <b>iPhone ou iPad</b>"))
        // Sem User-Agent conhecido: nada aberto, nada primeiro.
        val o = PaginaCertificado.preencher(modelo, pronto(), "tv", "curl/8")
        assertFalse(o.contains(" open>"))
        assertFalse(o.contains("primeiro\""))
        assertTrue(o.contains("cadeado.js"))
    }

    @Test fun cspDeixaTestarOCadeado() {
        assertTrue(PainelHttp.CABECALHOS_SEGURANCA["Content-Security-Policy"]!!.contains("connect-src 'self' https://*:8444;"))
    }
}
