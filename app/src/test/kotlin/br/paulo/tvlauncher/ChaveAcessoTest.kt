package br.paulo.tvlauncher

import br.paulo.tvlauncher.mcp.ChaveAcesso
import br.paulo.tvlauncher.mcp.DesafiosChave
import br.paulo.tvlauncher.panel.entrar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Ideia 99: entrar com a digital. Os dados abaixo são de verdade: gravados do Chromium com o autenticador virtual do
 * DevTools (WebAuthn.addVirtualAuthenticator, 14/11/2026) num cadastro e duas entradas em http://localhost:8098. Chave
 * descartável, de teste; nada de aparelho de ninguém. Assim o teste confere o formato que o navegador manda mesmo.
 */
class ChaveAcessoTest {
    private val rp = "localhost"
    private val origem = "http://localhost:8098"
    private val cad = mapOf(
        "id" to "zh0zOIfcx09XiEBUQzP0vPxLRDXjTDtbKnema0KkNz4",
        "chavePublica" to "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEZF4ifxVnNX-CUg7YSiJTk01EzJUHS77kvjEJdWE4aqBMJpwIzBN0H2t0T6tgsbKZfHMIZrX6qOLhz2LAQjWI8g",
        "authData" to "SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2NFAAAAAQECAwQFBgcIAQIDBAUGBwgAIM4dMziH3MdPV4hAVEMz9Lz8S0Q140w7Wyp3pmtCpDc-pQECAyYgASFYIGReIn8VZzV_glIO2EoiU5NNRMyVB0u-5L4xCXVhOGqgIlggTCacCMwTdB9rdE-rYLGymXxzCGa1-qji4c9iwEI1iPI",
        "clientData" to "eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIiwiY2hhbGxlbmdlIjoiSXQxX2lseEpaMEN0b3lKU0d3TGpWWVhNMFl6V3Q2WERueUNEV2dHTWJ3dyIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3Q6ODA5OCIsImNyb3NzT3JpZ2luIjpmYWxzZSwib3RoZXJfa2V5c19jYW5fYmVfYWRkZWRfaGVyZSI6ImRvIG5vdCBjb21wYXJlIGNsaWVudERhdGFKU09OIGFnYWluc3QgYSB0ZW1wbGF0ZS4gU2VlIGh0dHBzOi8vZ29vLmdsL3lhYlBleCJ9",
        "desafio" to "It1_ilxJZ0CtoyJSGwLjVYXM0YzWt6XDnyCDWgGMbww",
    )
    private val g1 = mapOf(
        "id" to "zh0zOIfcx09XiEBUQzP0vPxLRDXjTDtbKnema0KkNz4",
        "authData" to "SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2MFAAAAAg",
        "clientData" to "eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiSEUzcjg1d1FkQndCS0s2bFFOVjNqclVNWVVDbnB1VFpKaTduaDRLTHIwcyIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3Q6ODA5OCIsImNyb3NzT3JpZ2luIjpmYWxzZX0",
        "assinatura" to "MEYCIQDiwEdBS1CGyB_ZYbZ2tI26uhjmzIDSozF5rgks15JS6wIhAJsSjtQtcN9vd4ZEu26UZQIBK6oLMBM-8ab0_bkOMdto",
        "desafio" to "HE3r85wQdBwBKK6lQNV3jrUMYUCnpuTZJi7nh4KLr0s",
    )
    private val g2 = mapOf(
        "id" to "zh0zOIfcx09XiEBUQzP0vPxLRDXjTDtbKnema0KkNz4",
        "authData" to "SZYN5YgOjGh0NBcPZHZgW4_krrmihjLHmVzzuoMdl2MFAAAAAw",
        "clientData" to "eyJ0eXBlIjoid2ViYXV0aG4uZ2V0IiwiY2hhbGxlbmdlIjoiRjhzdjhReWxuVHo1Skp5NUYyZjg2Qm5Ec21NRVJLajZrY21TUFlOdUV0ZyIsIm9yaWdpbiI6Imh0dHA6Ly9sb2NhbGhvc3Q6ODA5OCIsImNyb3NzT3JpZ2luIjpmYWxzZSwib3RoZXJfa2V5c19jYW5fYmVfYWRkZWRfaGVyZSI6ImRvIG5vdCBjb21wYXJlIGNsaWVudERhdGFKU09OIGFnYWluc3QgYSB0ZW1wbGF0ZS4gU2VlIGh0dHBzOi8vZ29vLmdsL3lhYlBleCJ9",
        "assinatura" to "MEUCIE8dFtNmI3rsjVeYWLn73LbPsG_YKWFS6w3-cVIjU7y3AiEAsM3_uF2NLRs8--xEYJS3HLujyhwoS4UJhAGUcrgqaa8",
        "desafio" to "F8sv8QylnTz5JJy5F2f86BnDsmMERKj6kcmSPYNuEtg",
    )

    private fun cadastrar(origemUsada: String = origem, desafio: String = cad["desafio"]!!, authData: String = cad["authData"]!!) =
        ChaveAcesso.cadastrar(cad["id"]!!, cad["chavePublica"]!!, -7, authData, cad["clientData"]!!, desafio, rp, origemUsada, "Android", 1L)

    private fun entrar(chave: ChaveAcesso.Chave, g: Map<String, String>, assinatura: String = g["assinatura"]!!, origemUsada: String = origem) =
        ChaveAcesso.conferirEntrada(chave, g["authData"]!!, g["clientData"]!!, assinatura, g["desafio"]!!, rp, origemUsada)

    private fun recusa(bloco: () -> Unit) { try { bloco(); fail("devia recusar") } catch (e: ChaveAcesso.Recusa) {} }

    @Test fun cadastroEDuasEntradasDeVerdade() {
        val chave = cadastrar()
        assertEquals(cad["id"], chave.id)
        val n1 = entrar(chave, g1)
        val n2 = entrar(chave.copy(contador = n1), g2)
        assertTrue("o contador do autenticador virtual sobe", n2 > n1)
    }

    @Test fun cadastroRecusaOrigemDesafioEAuthDataTrocados() {
        recusa { cadastrar(origemUsada = "https://outro.ts.net:8443") }
        recusa { cadastrar(desafio = ChaveAcesso.novoDesafio()) }
        // Marca UV apagada no authData (byte 32): sem verificação do usuário não vale.
        val a = ChaveAcesso.deB64(cad["authData"]); a[32] = (a[32].toInt() and 0xFB).toByte()
        recusa { cadastrar(authData = ChaveAcesso.b64(a)) }
        recusa { ChaveAcesso.cadastrar(cad["id"]!!, cad["chavePublica"]!!, -257, cad["authData"]!!, cad["clientData"]!!, cad["desafio"]!!, rp, origem, "x", 1L) }
        // id que não é o de dentro do authData
        recusa { ChaveAcesso.cadastrar(ChaveAcesso.b64(ByteArray(32)), cad["chavePublica"]!!, -7, cad["authData"]!!, cad["clientData"]!!, cad["desafio"]!!, rp, origem, "x", 1L) }
    }

    @Test fun entradaRecusaAssinaturaOrigemEContadorRepetido() {
        val chave = cadastrar()
        val s = ChaveAcesso.deB64(g1["assinatura"]); s[s.size - 1] = (s[s.size - 1] + 1).toByte()
        recusa { entrar(chave, g1, assinatura = ChaveAcesso.b64(s)) }
        recusa { entrar(chave, g1, origemUsada = "https://localhost:8098") }
        val n1 = entrar(chave, g1)
        recusa { entrar(chave.copy(contador = n1), g1) }   // mesma resposta de novo: contador não subiu
        recusa { entrar(chave, g1.plus("desafio" to ChaveAcesso.novoDesafio())) }
        // RP ID de outro nome: o resumo dentro do authData não bate.
        recusa { ChaveAcesso.conferirEntrada(chave, g1["authData"]!!, g1["clientData"]!!, g1["assinatura"]!!, g1["desafio"]!!, "outro.ts.net", origem) }
    }

    @Test fun guardarEDesafios() {
        val chave = cadastrar()
        assertEquals(listOf(chave), ChaveAcesso.deJson(ChaveAcesso.paraJson(listOf(chave))))
        assertTrue(ChaveAcesso.deJson("lixo").isEmpty())
        var t = 0L
        val d = DesafiosChave({ t })
        val x = d.novo()
        assertTrue(d.usar(x)); assertFalse("uso único", d.usar(x))
        val y = d.novo(); t += ChaveAcesso.DESAFIO_MS + 1
        assertFalse("venceu", d.usar(y))
        assertEquals("onetv-sala.tail0.ts.net", ChaveAcesso.rpId("OneTV-Sala.tail0.ts.net:8443"))
        assertEquals("https://onetv-sala.tail0.ts.net:8443", ChaveAcesso.origem("OneTV-Sala.tail0.ts.net:8443"))
    }
}
