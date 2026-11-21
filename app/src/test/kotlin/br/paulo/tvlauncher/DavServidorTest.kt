package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.DavLogica
import br.paulo.tvlauncher.panel.ErroHttp
import br.paulo.tvlauncher.panel.PainelArquivos
import br.paulo.tvlauncher.panel.PainelDav
import br.paulo.tvlauncher.panel.PainelHttp
import br.paulo.tvlauncher.panel.PanelShortcut
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.Base64
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * O servidor da unidade de rede (PainelDav) de verdade, sem Android: numa pasta temporária no lugar da memória interna
 * e em http no 127.0.0.1 (o TLS é o mesmo do painel, já testado; aqui o que importa é o WebDAV). Os pedidos são
 * escritos à mão, como Windows, Finder e davfs2 mandam.
 *
 * Para testar com clientes reais (rclone, cadaver, davfs2): ONETV_DAV_SERVIR=<porta> ONETV_DAV_RAIZ=<pasta> roda só
 * [servirParaClientesReais], que fica no ar até ser morto. Usuário qualquer, senha 123456.
 */
class DavServidorTest {
    private lateinit var raiz: File
    private lateinit var servidor: ServerSocket
    private val diario = ArrayList<String>()
    private val porta get() = servidor.localPort
    private val auth = "Basic " + Base64.getEncoder().encodeToString("tv:123456".toByteArray())

    private val amb = object : PainelDav.Ambiente {
        override fun acesso() = true
        override fun registrar(ip: String, acao: String) { synchronized(diario) { diario.add(acao) } }
        override fun avisar(f: File) {}
        override fun atalho(): File? = atalhoTeste
    }
    /** O atalho do painel (PanelShortcut) que o "Android" entrega; null = sem atalho. */
    private var atalhoTeste: File? = null

    private fun conferir(c: String?): PainelDav.Recusa? =
        if (DavLogica.senhaBasic(c) == "123456") null else PainelDav.Recusa(401, "PIN errado")

    private fun subir(raizDir: File, portaFixa: Int = 0) {
        raiz = raizDir
        PainelArquivos.raizTeste = raiz
        servidor = ServerSocket(portaFixa, 16, InetAddress.getLoopbackAddress())
        Thread {
            while (!servidor.isClosed) {
                val c = try { servidor.accept() } catch (e: Exception) { break }
                Thread { atender(c) }.start()
            }
        }.apply { isDaemon = true }.start()
    }

    /** O mesmo caminho do PainelWeb.atender para o WebDAV. */
    private fun atender(c: Socket) = c.use {
        try {
            c.soTimeout = 15_000
            val entrada = c.getInputStream().buffered(8192)
            val saida = c.getOutputStream().buffered(16 * 1024)
            val req = PainelHttp.lerCabecalhos(entrada, chunkedEm = { DavLogica.ehDav(it) })
            if (PainelDav.meu(req)) PainelDav.atender(amb, req, entrada, saida, "127.0.0.1", true, ::conferir)
            else PainelHttp.escrever(saida, 404, "text/plain", "não".toByteArray())
            saida.flush()
        } catch (e: ErroHttp) {
            try { PainelHttp.escrever(c.getOutputStream(), e.codigo, "text/plain", ByteArray(0)) } catch (_: Exception) {}
        } catch (e: Exception) { /* conexão caiu */ }
    }

    @Before fun antes() {
        subir(Files.createTempDirectory("dav").toFile())
        File(raiz, "Download").mkdir()
        File(raiz, "Android/data").mkdirs()
        File(raiz, "Download/ola.txt").writeText("olá mundo")
    }

    @After fun depois() {
        servidor.close()
        PainelArquivos.raizTeste = null
        raiz.deleteRecursively()
    }

    class Resp(val codigo: Int, val cab: Map<String, String>, val corpo: ByteArray) { val texto get() = String(corpo, Charsets.UTF_8) }

    /** Um pedido cru. [corpoCru] já vem pronto (ex.: chunked); senão [corpo] vai com Content-Length. */
    private fun pedir(metodo: String, caminho: String, cab: Map<String, String> = emptyMap(), corpo: ByteArray? = null,
                      comSenha: Boolean = true, corpoCru: ByteArray? = null): Resp {
        Socket(InetAddress.getLoopbackAddress(), porta).use { s ->
            s.soTimeout = 10_000
            val sb = StringBuilder("$metodo $caminho HTTP/1.1\r\nHost: tv:8444\r\n")
            if (comSenha) sb.append("Authorization: $auth\r\n")
            for ((k, v) in cab) sb.append("$k: $v\r\n")
            if (corpo != null) sb.append("Content-Length: ${corpo.size}\r\n")
            sb.append("\r\n")
            val out = s.getOutputStream()
            out.write(sb.toString().toByteArray(Charsets.UTF_8))
            corpo?.let { out.write(it) }
            corpoCru?.let { out.write(it) }
            out.flush()
            return ler(s.getInputStream(), metodo == "HEAD")
        }
    }

    private fun ler(i: InputStream, head: Boolean): Resp {
        fun linha(): String { val b = ByteArrayOutputStream(); while (true) { val c = i.read(); if (c < 0 || c == '\n'.code) break; b.write(c) }; return b.toString("ISO-8859-1").trimEnd('\r') }
        var status = linha()
        var cab = HashMap<String, String>()
        while (true) {
            cab = HashMap()
            while (true) { val l = linha(); if (l.isEmpty()) break; cab[l.substringBefore(':').lowercase()] = l.substringAfter(':').trim() }
            if (status.split(' ')[1] != "100") break
            status = linha()   // depois do "100 Continue" vem a resposta de verdade
        }
        val n = if (head) 0 else cab["content-length"]?.toInt() ?: 0
        val corpo = ByteArray(n); var lidos = 0
        while (lidos < n) { val r = i.read(corpo, lidos, n - lidos); if (r < 0) break; lidos += r }
        return Resp(status.split(' ')[1].toInt(), cab, corpo)
    }

    @Test fun optionsSemSenhaDizQueFalaWebDav() {
        val r = pedir("OPTIONS", "/dav/", comSenha = false)
        assertEquals(200, r.codigo)
        assertTrue(r.cab["dav"]!!.contains("2"))
        assertTrue(r.cab["allow"]!!.contains("PROPFIND"))
        assertEquals(200, pedir("OPTIONS", "/", comSenha = false).codigo)   // o Windows sonda a raiz
    }

    @Test fun semSenhaPede401ComBasic() {
        val r = pedir("PROPFIND", "/dav/", mapOf("Depth" to "1"), comSenha = false)
        assertEquals(401, r.codigo)
        assertTrue(r.cab["www-authenticate"]!!.startsWith("Basic"))
        assertEquals(401, pedir("GET", "/dav/Download/ola.txt", mapOf("Authorization" to "Basic eDp4"), comSenha = false).codigo)
    }

    /** O atalho "Abrir o painel da TV.url": aparece só na raiz, baixa com o IP de agora, não se apaga. */
    @Test fun atalhoDoPainelNaRaiz() {
        val fora = Files.createTempDirectory("atalho").toFile()
        try {
            val nome = PanelShortcut.fileName("Abrir o painel da TV")!!
            atalhoTeste = File(fora, nome).apply { writeBytes(PanelShortcut.content("192.168.1.153", 8080)) }
            val enc = DavLogica.codificar(nome)
            val lista = pedir("PROPFIND", "/dav/", mapOf("Depth" to "1"))
            assertEquals(207, lista.codigo)
            assertTrue(lista.texto, lista.texto.contains("<D:href>/dav/$enc</D:href>"))
            assertTrue(!pedir("PROPFIND", "/dav/Download/", mapOf("Depth" to "1")).texto.contains(enc))   // só na raiz
            assertEquals(207, pedir("PROPFIND", "/dav/$enc", mapOf("Depth" to "0")).codigo)
            val g = pedir("GET", "/dav/$enc")
            assertEquals(200, g.codigo)
            assertTrue(g.texto.contains("URL=http://192.168.1.153:8080/"))
            assertEquals(404, pedir("DELETE", "/dav/$enc").codigo)   // não está no disco: nada a apagar
            assertTrue(atalhoTeste!!.exists())
            assertEquals(404, pedir("GET", "/dav/Download/$enc").codigo)
            // um arquivo de verdade com o mesmo nome vale mais que o atalho
            File(raiz, nome).writeText("meu")
            assertEquals("meu", pedir("GET", "/dav/$enc").texto)
            assertEquals(1, Regex(Regex.escape("<D:href>/dav/$enc</D:href>")).findAll(pedir("PROPFIND", "/dav/", mapOf("Depth" to "1")).texto).count())
        } finally { atalhoTeste = null; fora.deleteRecursively() }
    }

    @Test fun listaARaizSemAPastaAndroid() {
        val r = pedir("PROPFIND", "/dav/", mapOf("Depth" to "1", "Content-Type" to "application/xml"),
            "<?xml version=\"1.0\"?><D:propfind xmlns:D=\"DAV:\"><D:allprop/></D:propfind>".toByteArray())
        assertEquals(207, r.codigo)
        assertTrue(r.texto.contains("<D:href>/dav/Download/</D:href>"))
        assertFalse(r.texto.contains("Android"))
        val zero = pedir("PROPFIND", "/dav/Download/ola.txt", mapOf("Depth" to "0"))
        assertEquals(207, zero.codigo)
        assertTrue(zero.texto.contains("<D:getcontentlength>${"olá mundo".toByteArray().size}</D:getcontentlength>"))
        assertEquals(404, pedir("PROPFIND", "/dav/nada", mapOf("Depth" to "0")).codigo)
        assertEquals(207, pedir("PROPFIND", "/", mapOf("Depth" to "1")).codigo)
    }

    @Test fun enviaBaixaSubstituiEApaga() {
        val dados = ByteArray(300_000) { (it % 251).toByte() }
        assertEquals(201, pedir("PUT", "/dav/Download/F%C3%A9rias%20a%C3%A7%C3%A3o.bin", corpo = dados).codigo)
        assertArrayEquals(dados, File(raiz, "Download/Férias ação.bin").readBytes())
        val get = pedir("GET", "/dav/Download/F%C3%A9rias%20a%C3%A7%C3%A3o.bin")
        assertEquals(200, get.codigo); assertArrayEquals(dados, get.corpo)
        assertTrue(get.cab["content-disposition"]!!.startsWith("attachment"))
        assertEquals(204, pedir("PUT", "/dav/Download/F%C3%A9rias%20a%C3%A7%C3%A3o.bin", corpo = byteArrayOf(1, 2)).codigo)
        assertEquals(2L, File(raiz, "Download/Férias ação.bin").length())
        val head = pedir("HEAD", "/dav/Download/ola.txt")
        assertEquals(200, head.codigo); assertEquals("olá mundo".toByteArray().size.toString(), head.cab["content-length"])
        assertEquals(204, pedir("DELETE", "/dav/Download/F%C3%A9rias%20a%C3%A7%C3%A3o.bin").codigo)
        assertEquals(404, pedir("GET", "/dav/Download/F%C3%A9rias%20a%C3%A7%C3%A3o.bin").codigo)
        assertTrue(diario.any { it.startsWith("unidade de rede: enviar Férias ação.bin") })
        assertFalse("sobrou arquivo temporário", File(raiz, "Download").list()!!.any { it.endsWith(".parcial") })
    }

    @Test fun envioComExpectContinue() {
        val r = pedir("PUT", "/dav/Download/e.txt", mapOf("Expect" to "100-continue"), "abc".toByteArray())
        assertEquals(201, r.codigo); assertEquals("abc", File(raiz, "Download/e.txt").readText())
    }

    @Test fun envioChunkedDoFinder() {
        val corpo = "5\r\nHello\r\n6\r\n Mundo\r\n0\r\n\r\n".toByteArray()
        val r = pedir("PUT", "/dav/Download/finder.txt", mapOf("Transfer-Encoding" to "chunked", "X-Expected-Entity-Length" to "11"), corpoCru = corpo)
        assertEquals(201, r.codigo); assertEquals("Hello Mundo", File(raiz, "Download/finder.txt").readText())
    }

    @Test fun chunkedForaDoDavContinuaRecusado() {
        val r = pedir("POST", "/api/arquivos/enviar", mapOf("Transfer-Encoding" to "chunked"), corpoCru = "0\r\n\r\n".toByteArray())
        assertEquals(411, r.codigo)
    }

    @Test fun envioSemPastaOuGrandeDemais() {
        assertEquals(409, pedir("PUT", "/dav/naoexiste/a.txt", corpo = "x".toByteArray()).codigo)
        val r = pedir("PUT", "/dav/Download/enorme.bin", mapOf("Transfer-Encoding" to "chunked",
            "X-Expected-Entity-Length" to Long.MAX_VALUE.toString()), corpoCru = "0\r\n\r\n".toByteArray())
        assertEquals(507, r.codigo)
        assertEquals(405, pedir("PUT", "/dav/Download", corpo = "x".toByteArray()).codigo)
    }

    @Test fun faixaDeVideo() {
        val r = pedir("GET", "/dav/Download/ola.txt", mapOf("Range" to "bytes=0-2"))
        assertEquals(206, r.codigo); assertEquals("ol", String(r.corpo, Charsets.UTF_8).take(2))
        assertEquals("bytes 0-2/${"olá mundo".toByteArray().size}", r.cab["content-range"])
    }

    @Test fun pastasMoverECopiar() {
        assertEquals(201, pedir("MKCOL", "/dav/Download/Nova%20pasta").codigo)
        assertEquals(405, pedir("MKCOL", "/dav/Download/Nova%20pasta").codigo)
        assertEquals(409, pedir("MKCOL", "/dav/x/y").codigo)
        // Renomear (o Windows usa Destination com a URL inteira e Overwrite: F).
        val mv = pedir("MOVE", "/dav/Download/ola.txt", mapOf("Destination" to "https://tv:8444/dav/Download/Nova%20pasta/oi.txt", "Overwrite" to "F"))
        assertEquals(201, mv.codigo)
        assertTrue(File(raiz, "Download/Nova pasta/oi.txt").exists()); assertFalse(File(raiz, "Download/ola.txt").exists())
        val cp = pedir("COPY", "/dav/Download/Nova%20pasta/", mapOf("Destination" to "/dav/Download/Copia/"))
        assertEquals(201, cp.codigo)
        assertEquals("olá mundo", File(raiz, "Download/Copia/oi.txt").readText())
        File(raiz, "Download/b.txt").writeText("b")
        assertEquals(412, pedir("MOVE", "/dav/Download/b.txt", mapOf("Destination" to "/dav/Download/Copia/oi.txt", "Overwrite" to "F")).codigo)
        assertEquals(204, pedir("MOVE", "/dav/Download/b.txt", mapOf("Destination" to "/dav/Download/Copia/oi.txt", "Overwrite" to "T")).codigo)
        assertEquals("b", File(raiz, "Download/Copia/oi.txt").readText())
        assertEquals(403, pedir("MOVE", "/dav/Download/Copia/", mapOf("Destination" to "/dav/Download/Copia/dentro/")).codigo)
        assertEquals(400, pedir("MOVE", "/dav/Download/Copia/oi.txt", mapOf("Destination" to "https://outro:8444/dav/x")).codigo)
    }

    @Test fun destinoQueContemAOrigemNaoApagaNada() {
        File(raiz, "Download/Fotos/2024").mkdirs(); File(raiz, "Download/Fotos/2024/a.jpg").writeText("foto")
        val r = pedir("MOVE", "/dav/Download/Fotos/2024/a.jpg", mapOf("Destination" to "/dav/Download/Fotos", "Overwrite" to "T"))
        assertEquals(403, r.codigo)
        assertEquals("foto", File(raiz, "Download/Fotos/2024/a.jpg").readText())   // antes: Fotos inteira apagada
        assertEquals(403, pedir("COPY", "/dav/Download/Fotos/2024/a.jpg", mapOf("Destination" to "/dav/Download/Fotos", "Overwrite" to "T")).codigo)
        assertTrue(File(raiz, "Download/Fotos/2024/a.jpg").exists())
    }

    @Test fun copiarParaSiMesmoNaoZeraOArquivo() {
        val r = pedir("COPY", "/dav/Download/ola.txt", mapOf("Destination" to "/dav/Download/ola.txt", "Overwrite" to "T"))
        assertEquals(403, r.codigo)
        assertEquals("olá mundo", File(raiz, "Download/ola.txt").readText())
    }

    @Test fun moverPastaDoSistemaNaoApagaODestinoAntes() {
        File(raiz, "Outra").mkdirs(); File(raiz, "Outra/x.txt").writeText("x")
        val r = pedir("MOVE", "/dav/Download", mapOf("Destination" to "/dav/Outra", "Overwrite" to "T"))
        assertEquals(403, r.codigo)
        assertTrue(File(raiz, "Outra/x.txt").exists() && File(raiz, "Download/ola.txt").exists())
    }

    @Test fun lockComXmlGrandeEhRecusado() {
        val corpo = "<a>".repeat(4000).toByteArray()
        assertEquals(413, pedir("LOCK", "/dav/Download/ola.txt", corpo = corpo).codigo)
        assertEquals(413, pedir("PROPPATCH", "/dav/Download/ola.txt", corpo = corpo).codigo)
    }

    @Test fun listagemEscondeLinkSimbolico() {
        val fora = Files.createTempDirectory("fora").toFile()
        Files.createSymbolicLink(File(raiz, "Download/atalho").toPath(), fora.toPath())
        val r = pedir("PROPFIND", "/dav/Download/", mapOf("Depth" to "1"))
        assertEquals(207, r.codigo)
        assertFalse("atalho" in r.texto)
        fora.deleteRecursively()
    }

    @Test fun pastasDoSistemaNaoSeApagam() {
        assertEquals(403, pedir("DELETE", "/dav/Download").codigo)
        assertEquals(403, pedir("DELETE", "/dav/").codigo)
        assertTrue(File(raiz, "Download/ola.txt").exists())
    }

    @Test fun naoSaiDaRaiz() {
        assertEquals(400, pedir("GET", "/dav/../etc/passwd").codigo)
        assertEquals(400, pedir("GET", "/dav/%2e%2e/%2e%2e/etc/passwd").codigo)
        assertEquals(400, pedir("PROPFIND", "/dav/Android/data", mapOf("Depth" to "1")).codigo)
        val fora = Files.createTempDirectory("fora").toFile()
        File(fora, "segredo.txt").writeText("não")
        Files.createSymbolicLink(File(raiz, "Download/atalho").toPath(), fora.toPath())
        assertEquals(400, pedir("GET", "/dav/Download/atalho/segredo.txt").codigo)
        assertTrue(pedir("PUT", "/dav/Download/atalho/novo.txt", corpo = "x".toByteArray()).codigo >= 400)
        assertFalse(File(fora, "novo.txt").exists())
        fora.deleteRecursively()
    }

    @Test fun travaEPropriedadesDoWindows() {
        val lock = pedir("LOCK", "/dav/Download/novo.docx", mapOf("Timeout" to "Second-3600"),
            "<?xml version=\"1.0\"?><D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype><D:owner><D:href>Paulo</D:href></D:owner></D:lockinfo>".toByteArray())
        assertEquals(201, lock.codigo)
        assertTrue(lock.cab["lock-token"]!!.startsWith("<opaquelocktoken:"))
        assertTrue(File(raiz, "Download/novo.docx").exists())
        val token = lock.cab["lock-token"]!!.trim('<', '>')
        val renova = pedir("LOCK", "/dav/Download/novo.docx", mapOf("If" to "(<$token>)"))
        assertEquals(200, renova.codigo); assertEquals("<$token>", renova.cab["lock-token"])
        assertEquals(204, pedir("UNLOCK", "/dav/Download/novo.docx", mapOf("Lock-Token" to "<$token>")).codigo)
        val pp = pedir("PROPPATCH", "/dav/Download/ola.txt", corpo = ("<?xml version=\"1.0\"?><D:propertyupdate xmlns:D=\"DAV:\" " +
            "xmlns:Z=\"urn:schemas-microsoft-com:\"><D:set><D:prop><Z:Win32LastModifiedTime>Thu, 01 Jan 2026 12:00:00 GMT" +
            "</Z:Win32LastModifiedTime></D:prop></D:set></D:propertyupdate>").toByteArray())
        assertEquals(207, pp.codigo)
        assertEquals(DavLogica.lerDataHttp("Thu, 01 Jan 2026 12:00:00 GMT"), File(raiz, "Download/ola.txt").lastModified())
    }

    @Test fun origemDeFoReRecusada() {
        assertEquals(403, pedir("PROPFIND", "/dav/", mapOf("Origin" to "https://site-mau.com", "Depth" to "0")).codigo)
    }

    @Test fun pastaNoNavegadorViraPaginaSemScript() {
        File(raiz, "Download/<script>.txt").writeText("x")
        val r = pedir("GET", "/dav/Download/")
        assertEquals(200, r.codigo)
        assertTrue(r.texto.contains("&lt;script&gt;.txt")); assertFalse(r.texto.contains("<script>"))
        assertTrue(r.cab["content-security-policy"]!!.startsWith("sandbox"))
    }

    /** Fica no ar para clientes reais. Ver o comentário da classe. */
    @Test fun servirParaClientesReais() {
        val p = System.getenv("ONETV_DAV_SERVIR")?.toIntOrNull()
        Assume.assumeTrue(p != null)
        servidor.close()
        subir(File(System.getenv("ONETV_DAV_RAIZ") ?: error("ONETV_DAV_RAIZ")).apply { mkdirs() }, p!!)
        println("WebDAV de teste em http://127.0.0.1:$p/dav/ (pasta $raiz)")
        Thread.sleep(Long.MAX_VALUE)
    }
}
