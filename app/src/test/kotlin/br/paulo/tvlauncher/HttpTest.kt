package br.paulo.tvlauncher

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class HttpTest {
    /** Servidor mínimo de teste: /ok devolve o User-Agent, /404, /grande (5000 bytes) e /pula (302 para /ok). */
    private val servidor = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    init {
        thread(isDaemon = true) {
            while (!servidor.isClosed) {
                val s = runCatching { servidor.accept() }.getOrNull() ?: break
                s.use { c ->
                    val linhas = c.getInputStream().bufferedReader().let { r -> generateSequence { r.readLine() }.takeWhile { it.isNotEmpty() }.toList() }
                    val caminho = linhas.first().split(" ")[1]
                    val agente = linhas.firstOrNull { it.startsWith("User-Agent:", true) }?.substringAfter(":")?.trim()
                    val (cab, corpo) = when (caminho) {
                        "/ok" -> "200 OK" to "olá $agente".toByteArray()
                        "/grande" -> "200 OK" to ByteArray(5000) { 'a'.code.toByte() }
                        "/pula" -> "302 Found\r\nLocation: /ok" to ByteArray(0)
                        else -> "404 Not Found" to ByteArray(0)
                    }
                    c.getOutputStream().apply {
                        write("HTTP/1.1 $cab\r\nContent-Length: ${corpo.size}\r\nConnection: close\r\n\r\n".toByteArray()); write(corpo); flush()
                    }
                }
            }
        }
    }
    private val base = "http://127.0.0.1:${servidor.localPort}"

    @After fun parar() = servidor.close()

    @Test fun textoComOAgenteDoApp() = assertEquals("olá ${Http.USER_AGENT}", Http.getText("$base/ok"))

    @Test fun outroCodigoEhNull() = assertNull(Http.getText("$base/404"))

    @Test fun tetoCortaOuRecusa() {
        assertEquals(1000, Http.getText("$base/grande", maxBytes = 1000, cut = true)!!.length)
        assertNull(Http.getText("$base/grande", maxBytes = 1000))
        assertEquals(5000, Http.getText("$base/grande", maxBytes = 5000)!!.length)   // exatamente no teto: passa
    }

    @Test fun semSeguirRedirecionamento() {
        assertNull(Http.getText("$base/pula", followRedirects = false))
        assertEquals("olá ${Http.USER_AGENT}", Http.getText("$base/pula"))
    }

    @Test fun erroDeRedeSoNoOrNull() = assertNull(Http.getTextOrNull("http://127.0.0.1:9/nada", connectMs = 500))

    @Test fun lerComTeto() {
        val dez = ByteArray(10) { it.toByte() }
        assertArrayEquals(dez, Http.readLimited(ByteArrayInputStream(dez), 10, cut = false))
        assertNull(Http.readLimited(ByteArrayInputStream(dez), 9, cut = false))
        assertArrayEquals(dez.copyOf(4), Http.readLimited(ByteArrayInputStream(dez), 4, cut = true))
        assertArrayEquals(ByteArray(0), Http.readLimited(ByteArrayInputStream(ByteArray(0)), 4, cut = false))
    }
}
