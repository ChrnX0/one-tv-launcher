package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** O login OAuth do MCP (McpAutorizacao). Roda no computador: gradlew testReleaseUnitTest. */
class McpAutorizacaoTest {
    private var agora = 1_000_000L
    private var salvo: String? = null
    private fun nova(estado: String? = null) = McpAutorizacao({ agora }, estado, { salvo = it })

    private val volta = "https://claude.ai/api/mcp/auth_callback"
    private val verifier = "a".repeat(50)
    private val desafio = McpAutorizacao.desafioS256(verifier)

    private fun registrar(a: McpAutorizacao, redirect: String = volta): String =
        a.registrar(JSONObject().put("redirect_uris", JSONArray(listOf(redirect))).put("client_name", "Claude")).getString("client_id")

    private fun esperaErro(codigo: String, bloco: () -> Unit) {
        try { bloco(); fail("devia dar $codigo") } catch (e: McpAutorizacao.Erro) { assertEquals(codigo, e.codigo) }
    }

    private fun login(a: McpAutorizacao, cliente: String): McpAutorizacao.Par {
        a.conferirPedido(cliente, volta, "code", desafio, "S256")
        val codigo = a.criarCodigo(cliente, volta, desafio)
        return a.trocarCodigo(codigo, cliente, volta, verifier)
    }

    @Test fun soAceitaVoltaParaOClaudeOuLocal() {
        assertTrue(McpAutorizacao.redirectPermitido("https://claude.ai/api/mcp/auth_callback"))
        assertTrue(McpAutorizacao.redirectPermitido("https://claude.com/api/mcp/auth_callback"))
        assertTrue(McpAutorizacao.redirectPermitido("https://chatgpt.com/connector_platform_oauth_redirect"))
        assertTrue(McpAutorizacao.redirectPermitido("http://localhost:33418/callback"))
        assertFalse(McpAutorizacao.redirectPermitido("https://chatgpt.com.mal.com/cb"))
        assertTrue(McpAutorizacao.redirectPermitido("https://www.perplexity.ai/rest/connections/oauth_callback"))
        assertTrue(McpAutorizacao.redirectPermitido("https://grok.com/connectors-oauth-exchange-code/"))
        assertTrue(McpAutorizacao.redirectPermitido("https://antigravity.google/oauth-callback"))
        assertFalse(McpAutorizacao.redirectPermitido("https://grok.com.mal.com/cb"))
        assertTrue(McpAutorizacao.redirectPermitido("http://127.0.0.1:5000/cb"))
        assertFalse(McpAutorizacao.redirectPermitido("http://claude.ai/cb"))            // sem TLS
        assertFalse(McpAutorizacao.redirectPermitido("https://claude.ai.mal.com/cb"))
        assertFalse(McpAutorizacao.redirectPermitido("https://mal.com/cb"))
        assertFalse(McpAutorizacao.redirectPermitido("https://user@claude.ai/cb"))
        assertFalse(McpAutorizacao.redirectPermitido("https://claude.ai/cb#x"))
        assertFalse(McpAutorizacao.redirectPermitido("javascript:alert(1)"))
        assertFalse(McpAutorizacao.redirectPermitido(null))
    }

    @Test fun registroComVoltaEstranhaFalha() {
        esperaErro("invalid_redirect_uri") { registrar(nova(), "https://mal.com/cb") }
    }

    @Test fun fluxoCompletoDaToken() {
        val a = nova()
        val par = login(a, registrar(a))
        assertTrue(a.tokenValido(par.acesso))
        assertFalse(a.tokenValido(par.renovar))   // refresh não serve como acesso
        assertFalse(a.tokenValido("outro"))
        assertEquals(1, a.quantosLogins())
    }

    @Test fun pedidoSemPkceOuVoltaDiferenteFalha() {
        val a = nova(); val c = registrar(a)
        esperaErro("invalid_request") { a.conferirPedido(c, volta, "code", desafio, "plain") }
        esperaErro("invalid_request") { a.conferirPedido(c, "https://claude.ai/outra", "code", desafio, "S256") }
        esperaErro("unsupported_response_type") { a.conferirPedido(c, volta, "token", desafio, "S256") }
        esperaErro("invalid_client") { a.conferirPedido("c_nao_existe", volta, "code", desafio, "S256") }
    }

    @Test fun codigoValeUmaVezSoEVence() {
        val a = nova(); val c = registrar(a)
        val codigo = a.criarCodigo(c, volta, desafio)
        a.trocarCodigo(codigo, c, volta, verifier)
        esperaErro("invalid_grant") { a.trocarCodigo(codigo, c, volta, verifier) }
        val codigo2 = a.criarCodigo(c, volta, desafio)
        agora += McpAutorizacao.CODIGO_MS + 1
        esperaErro("invalid_grant") { a.trocarCodigo(codigo2, c, volta, verifier) }
    }

    @Test fun verifierErradoFalhaEQueimaOCodigo() {
        val a = nova(); val c = registrar(a)
        val codigo = a.criarCodigo(c, volta, desafio)
        esperaErro("invalid_grant") { a.trocarCodigo(codigo, c, volta, "b".repeat(50)) }
        esperaErro("invalid_grant") { a.trocarCodigo(codigo, c, volta, verifier) }
    }

    @Test fun acessoVence() {
        val a = nova(); val par = login(a, registrar(a))
        agora += McpAutorizacao.ACESSO_MS + 1
        assertFalse(a.tokenValido(par.acesso))
    }

    @Test fun renovarGiraEReusoDerrubaAFamilia() {
        val a = nova(); val c = registrar(a)
        val p1 = login(a, c)
        val p2 = a.renovar(p1.renovar, c)
        assertNotEquals(p1.acesso, p2.acesso)
        assertFalse(a.tokenValido(p1.acesso))
        assertTrue(a.tokenValido(p2.acesso))
        agora += McpAutorizacao.TOLERANCIA_MS + 1                    // fora da tolerância de resposta perdida (mcp-8)
        esperaErro("invalid_grant") { a.renovar(p1.renovar, c) }   // reuso do antigo
        esperaErro("invalid_grant") { a.renovar(p2.renovar, "c_outro") }
    }

    /** Revisão 09/11/2026: o reuso de um refresh velho apaga a família inteira (antes só dava "inválido"). */
    @Test fun reusoApagaOsTokensNovosDaFamilia() {
        val a = nova(); val c = registrar(a)
        val p1 = login(a, c)
        val p2 = a.renovar(p1.renovar, c)          // o ladrão (ou o Claude) renovou primeiro
        agora += McpAutorizacao.TOLERANCIA_MS + 1  // passou a tolerância de resposta perdida (mcp-8)
        esperaErro("invalid_grant") { a.renovar(p1.renovar, c) }   // o outro aparece com o velho
        assertFalse(a.tokenValido(p2.acesso))
        esperaErro("invalid_grant") { a.renovar(p2.renovar, c) }
        assertEquals(0, a.quantosLogins())
    }

    @Test fun reusoEPegoDepoisDeReiniciar() {
        val a = nova(); val c = registrar(a)
        val p1 = login(a, c)
        val p2 = a.renovar(p1.renovar, c)
        agora += McpAutorizacao.TOLERANCIA_MS + 1
        val b = nova(salvo)                          // o launcher reiniciou
        esperaErro("invalid_grant") { b.renovar(p1.renovar, c) }
        assertFalse(b.tokenValido(p2.acesso))
    }

    /** mcp-8: a resposta do /token se perdeu e o cliente reenviou o mesmo refresh logo depois: não derruba o login. */
    @Test fun reenvioDentroDaToleranciaNaoDerrubaOLogin() {
        val a = nova(); val c = registrar(a)
        val p1 = login(a, c)
        val p2 = a.renovar(p1.renovar, c)          // resposta perdida: o cliente nunca viu p2
        agora += 5_000
        val p3 = a.renovar(p1.renovar, c)          // reenvio do mesmo refresh
        assertTrue(a.tokenValido(p3.acesso))
        assertFalse(a.tokenValido(p2.acesso))      // o par perdido morre
        assertEquals(1, a.quantosLogins())
        val p4 = a.renovar(p3.renovar, c)          // a vida segue normal
        assertTrue(a.tokenValido(p4.acesso))
    }

    /** mcp-8: o par perdido que aparece depois é cópia: derruba a família. */
    @Test fun parPerdidoQueApareceDepoisDerrubaAFamilia() {
        val a = nova(); val c = registrar(a)
        val p1 = login(a, c)
        val p2 = a.renovar(p1.renovar, c)
        val p3 = a.renovar(p1.renovar, c)          // tolerância: p2 morre
        esperaErro("invalid_grant") { a.renovar(p2.renovar, c) }
        assertFalse(a.tokenValido(p3.acesso))
        assertEquals(0, a.quantosLogins())
    }

    /** mcp-8: a tolerância não vale se o par novo já foi usado, nem para outro cliente, nem depois de reiniciar sem ela. */
    @Test fun toleranciaSoParaRespostaPerdidaDoMesmoCliente() {
        val a = nova(); val c = registrar(a); val outro = registrar(a)
        val p1 = login(a, c)
        val p2 = a.renovar(p1.renovar, c)
        esperaErro("invalid_grant") { a.renovar(p1.renovar, outro) }   // outro cliente: derruba
        assertFalse(a.tokenValido(p2.acesso))

        val q1 = login(a, c)
        val q2 = a.renovar(q1.renovar, c)
        a.renovar(q2.renovar, c)                                        // o par novo JÁ foi usado: não se perdeu
        esperaErro("invalid_grant") { a.renovar(q1.renovar, c) }
        assertEquals(0, a.quantosLogins())

        val r1 = login(a, c)
        a.renovar(r1.renovar, c)
        val b = nova(salvo)                                             // reiniciou: a tolerância continua valendo
        assertTrue(b.tokenValido(b.renovar(r1.renovar, c).acesso))
    }

    /** Revisão 09/11/2026: registros em massa pela internet não expulsam o cliente que tem login. */
    @Test fun registroEmMassaNaoExpulsaQuemTemLogin() {
        val a = nova(); val c = registrar(a)
        val par = login(a, c)
        repeat(McpAutorizacao.MAX_CLIENTES * 3) { registrar(a) }
        assertEquals("Claude", a.nomeDoCliente(c))
        assertTrue(a.tokenValido(par.acesso))
        a.conferirPedido(c, volta, "code", desafio, "S256")   // o próximo login do Claude ainda funciona
    }

    @Test fun voltaComAcentoOuEspacoERecusada() {
        assertFalse(McpAutorizacao.redirectPermitido("https://claude.ai/volta/ção"))
        assertFalse(McpAutorizacao.redirectPermitido("https://claude.ai/a b"))
        assertTrue(McpAutorizacao.redirectPermitido("https://claude.ai/api/mcp/auth_callback"))
    }

    @Test fun guardaSoResumoEVoltaDoEstadoSalvo() {
        val a = nova(); val c = registrar(a)
        val par = login(a, c)
        assertFalse(salvo!!.contains(par.acesso))
        assertFalse(salvo!!.contains(par.renovar))
        val b = nova(salvo)
        assertTrue(b.tokenValido(par.acesso))
        assertEquals("Claude", b.nomeDoCliente(c))
    }

    @Test fun revogarTudoApaga() {
        val a = nova(); val par = login(a, registrar(a))
        a.revogarTudo()
        assertFalse(a.tokenValido(par.acesso))
        assertEquals(0, a.quantosLogins())
    }

    @Test fun desafioS256DaRfc7636() {
        // Exemplo do apêndice B da RFC 7636.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM",
            McpAutorizacao.desafioS256("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }
}
