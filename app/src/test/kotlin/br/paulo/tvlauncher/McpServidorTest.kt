package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Partes puras do McpServidor e do McpExtras (auditoria 10/11/2026). */
class McpServidorTest {
    /** mcp-1: um pedido longo (shell de 140 s) não pode segurar os outros na fila. */
    @Test fun pedidoLongoNaoTravaOsOutros() {
        val pool = McpServidor.novoPool()
        val trava = CountDownLatch(1)
        val iniciou = CountDownLatch(1)
        try {
            pool.execute { trava.await() }
            pool.execute { iniciou.countDown() }
            assertTrue("o 2º pedido ficou na fila atrás do 1º", iniciou.await(2, TimeUnit.SECONDS))
            assertTrue(pool.allowsCoreThreadTimeOut())   // parado, não segura thread
        } finally { trava.countDown(); pool.shutdownNow() }
    }

    /** mcp-2: 1 byte a cada 5 s (abaixo do soTimeout) não pode segurar a conexão além do prazo total. */
    @Test fun corpoLentoEstouraOPrazoTotal() {
        var agora = 0L
        val cab = "POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 65536\r\n\r\n".toByteArray()
        var i = 0
        val lenta = object : InputStream() {
            override fun read(): Int {
                if (i < cab.size) return cab[i++].toInt()
                agora += 5_000           // cada byte do corpo demora 5 s
                return '['.code
            }
            // Como um socket de verdade: devolve só o que "chegou" (1 byte), não espera encher o pedaço.
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                b[off] = read().toByte(); return 1
            }
        }
        val entrada = EntradaComPrazo(lenta, McpServidor.PRAZO_PEDIDO_MS) { agora }
        try {
            PainelHttp.ler(entrada, 64 * 1024)
            fail("devia dar 408")
        } catch (e: ErroHttp) {
            assertEquals(408, e.codigo)
            assertTrue(agora <= McpServidor.PRAZO_PEDIDO_MS + 10_000)
        }
    }

    @Test fun pedidoRapidoPassa() {
        val txt = "POST /mcp HTTP/1.1\r\nHost: x\r\nContent-Length: 2\r\n\r\n{}"
        val req = PainelHttp.ler(EntradaComPrazo(txt.byteInputStream(), McpServidor.PRAZO_PEDIDO_MS), 1024)
        assertEquals("{}", req.corpoTexto())
    }

    /** mcp-7: casa_acionar passa pelas Confirmações das ações; as de leitura não. */
    @Test fun casaAcionarPedeConfirmacao() {
        assertEquals(Confirmacoes.Acao.MCP_ACOES, McpExtras.confirmacao("casa_acionar"))
        assertNull(McpExtras.confirmacao("casa_listar"))
        assertNull(McpExtras.confirmacao("ver_tela"))
    }
}
