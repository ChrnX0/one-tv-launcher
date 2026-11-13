package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test

/** Ideias grandes de 10/11/2026: casa (CasaHA), erros sem dado pessoal (ErrosFiltrados), texto da tela, MCP com imagem. */
class IdeiasGrandesTest {
    @Test fun enderecoEToken() {
        assertEquals("http://192.168.1.10:8123", CasaHA.enderecoValido(" http://192.168.1.10:8123/ "))
        assertEquals("https://casa.local", CasaHA.enderecoValido("https://casa.local"))
        assertNull(CasaHA.enderecoValido("http://casa:8123/api"))
        assertNull(CasaHA.enderecoValido("ftp://casa"))
        assertNull(CasaHA.enderecoValido("http://casa:99999"))
        assertTrue(CasaHA.tokenValido("eyJhbGciOiJIUzI1NiJ9.eyJpc3MiOiJ4eHh4eHh4eHgifQ.abcDEF_-123456"))
        assertFalse(CasaHA.tokenValido("senha123"))
    }

    @Test fun soDominiosControlaveis() {
        val estados = JSONArray()
            .put(JSONObject().put("entity_id", "light.sala").put("state", "on").put("attributes", JSONObject().put("friendly_name", "Luz da sala")))
            .put(JSONObject().put("entity_id", "lock.porta").put("state", "locked"))
            .put(JSONObject().put("entity_id", "scene.cinema").put("state", "x"))
            .put(JSONObject().put("entity_id", "Light.Ruim").put("state", "on"))
        val l = CasaHA.lerEstados(estados.toString())
        assertEquals(listOf("scene.cinema", "light.sala"), l.map { it.id }.sortedDescending())
        assertEquals("Luz da sala", l.first { it.id == "light.sala" }.nome)
        assertEquals(0, CasaHA.lerEstados("lixo").size)
    }

    @Test fun catalogoFechadoDeServicos() {
        assertEquals("light" to "toggle", CasaHA.servico("light.sala", "alternar"))
        assertEquals("cover" to "open_cover", CasaHA.servico("cover.cortina", "ligar"))
        assertEquals("scene" to "turn_on", CasaHA.servico("scene.cinema", "ligar"))
        assertNull("cena não desliga", CasaHA.servico("scene.cinema", "desligar"))
        assertNull("fechadura fora", CasaHA.servico("lock.porta", "ligar"))
        assertNull("injeção no id", CasaHA.servico("light.sala/../x", "ligar"))
        assertNull(CasaHA.servico("light.sala", "apagar_tudo"))
        assertEquals("sensor.tv_sala_de_estar", CasaHA.idDaTv("Sala de Estar"))
        assertEquals("sensor.tv_sala", CasaHA.idDaTv("!!!"))
    }

    @Test fun erroSemDadoPessoal() {
        val texto = """
            Quando: 10/11/2026 03:12:45
            Versão do launcher: 1.2.3
            Aparelho: Sala (Chromecast)
            Thread: main
            Erro: IOException: falhou ao ligar em 192.168.1.50 rede MinhaCasa

            java.lang.RuntimeException: java.io.IOException: falhou em 192.168.1.50
            	at br.paulo.tvlauncher.UnifiCliente.ler(UnifiCliente.kt:120)
            	at java.lang.Thread.run(Thread.java:1012)
            Caused by: java.io.IOException: falhou em /sdcard/Download/foto-familia.jpg
            	at br.paulo.tvlauncher.Rede.x(Unknown Source)
        """.trimIndent()
        val f = ErrosFiltrados.filtrar(texto)
        assertEquals("10/11/2026", f.dia)
        assertEquals("1.2.3", f.versao)
        assertEquals("IOException", f.tipo)
        assertEquals(listOf("at br.paulo.tvlauncher.UnifiCliente.ler(UnifiCliente.kt:120)", "at java.lang.Thread.run(Thread.java:1012)",
            "Caused by: IOException", "at br.paulo.tvlauncher.Rede.x(Unknown Source)"), f.pilha)
        val tudo = f.json().toString()
        for (pessoal in listOf("192.168", "MinhaCasa", "foto-familia", "Sala (", "03:12")) assertFalse(pessoal, pessoal in tudo)
    }

    @Test fun textoDaTela() {
        assertEquals(listOf("Stranger Things", "T4 E1"), TextoTela.juntar(listOf("  Stranger   Things ", "Stranger Things", "", "T4 E1")))
        assertEquals(listOf("abc"), TextoTela.juntar(listOf("abc", "defg"), max = 5))
    }

    @Test fun mcpDevolveImagemQuandoAFerramentaQuiser() {
        val f = object : McpProtocolo.Ferramentas, McpProtocolo.ComConteudo {
            override fun lista() = JSONArray().put(JSONObject().put("name", "ver"))
            override fun chamar(nome: String, argumentos: JSONObject) = "texto"
            override fun conteudo(nome: String, argumentos: JSONObject) =
                JSONArray().put(JSONObject().put("type", "image").put("mimeType", "image/jpeg").put("data", "AAAA"))
        }
        val r = McpProtocolo.tratar("""{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"ver"}}""", f)!!
        val c = r.getJSONObject("result").getJSONArray("content").getJSONObject(0)
        assertEquals("image", c.getString("type"))
    }
}
