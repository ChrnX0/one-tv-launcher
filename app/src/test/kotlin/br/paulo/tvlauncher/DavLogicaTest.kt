package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.DavLogica
import java.io.IOException
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A parte pura da unidade de rede (DavLogica.kt): caminhos, senha, XML e o corpo chunked do Finder. */
class DavLogicaTest {
    @Test fun soOCaminhoDavEhDav() {
        assertTrue(DavLogica.ehDav("/dav")); assertTrue(DavLogica.ehDav("/dav/")); assertTrue(DavLogica.ehDav("/dav/a"))
        assertFalse(DavLogica.ehDav("/davi")); assertFalse(DavLogica.ehDav("/api/arquivos")); assertFalse(DavLogica.ehDav("/"))
    }

    @Test fun caminhoRelativoDecodificado() {
        assertEquals("", DavLogica.relativo("/dav"))
        assertEquals("", DavLogica.relativo("/dav/"))
        assertEquals("Download/Férias 2026.mp4", DavLogica.relativo("/dav/Download/F%C3%A9rias%202026.mp4"))
        assertEquals("Download/pasta", DavLogica.relativo("/dav/Download/pasta/"))
        // UTF-8 cru (sem %xx), como a linha do pedido chega lida em ISO-8859-1: o mesmo nome.
        val cru = String("/dav/Férias".toByteArray(Charsets.UTF_8), Charsets.ISO_8859_1)
        assertEquals("Férias", DavLogica.relativo(cru))
    }

    @Test fun caminhoQueSaiDaRaizOuEstranhoEhRecusado() {
        for (c in listOf("/dav/../etc", "/dav/%2e%2e/x", "/dav/a/%2E%2E/%2E%2E", "/dav/Android/data", "/dav/android",
            "/dav/a%5Cb", "/dav/a%00b", "/dav/a%zz", "/dav/a%C3", "/dav/a//b", "/dav/%2F%2Fetc%2Fpasswd/.."))
            assertNull(c, DavLogica.relativo(c))
    }

    @Test fun destinoDoMoveTemDeSerOMesmoServidor() {
        assertEquals("Download/novo nome.txt", DavLogica.destino("https://tv-sala:8444/dav/Download/novo%20nome.txt", "tv-sala:8444"))
        assertEquals("x", DavLogica.destino("/dav/x", "tv:8444"))
        assertEquals("x", DavLogica.destino("HTTPS://TV:8444/dav/x", "tv:8444"))
        assertNull(DavLogica.destino("https://outro:8444/dav/x", "tv:8444"))
        assertNull(DavLogica.destino("https://tv:9999/dav/x", "tv:8444"))
        assertNull(DavLogica.destino("https://tv:8444/api/x", "tv:8444"))
        assertNull(DavLogica.destino("https://tv:8444/dav/../x", "tv:8444"))
        assertNull(DavLogica.destino("ftp://tv/dav/x", "tv"))
        assertNull(DavLogica.destino(null, "tv"))
    }

    @Test fun cabecalhosSimples() {
        assertEquals(0, DavLogica.profundidade("0")); assertEquals(1, DavLogica.profundidade("1"))
        assertEquals(1, DavLogica.profundidade("infinity")); assertEquals(1, DavLogica.profundidade(null))
        assertTrue(DavLogica.sobrescrever(null)); assertTrue(DavLogica.sobrescrever("T")); assertFalse(DavLogica.sobrescrever("F"))
    }

    private fun basic(u: String, s: String) = "Basic " + Base64.getEncoder().encodeToString("$u:$s".toByteArray())

    @Test fun senhaDoBasicEhOPinEOUsuarioNaoImporta() {
        assertEquals("123456", DavLogica.senhaBasic(basic("tv", "123456")))
        assertEquals("123456", DavLogica.senhaBasic(basic("", "123456")))
        assertEquals("a:b", DavLogica.senhaBasic(basic("u", "a:b")))   // só o primeiro ":" separa
        assertNull(DavLogica.senhaBasic("Bearer xyz"))
        assertNull(DavLogica.senhaBasic("Basic !!!"))
        assertNull(DavLogica.senhaBasic("Basic " + Base64.getEncoder().encodeToString("semdoispontos".toByteArray())))
        assertNull(DavLogica.senhaBasic(null))
    }

    @Test fun cacheDaSenhaVenceELimpa() {
        var agora = 0L
        val c = DavLogica.CacheSenha({ agora }, validadeMs = 1000, maximo = 2)
        c.lembrarCerto("a"); assertTrue(c.certo("a")); assertFalse(c.certo("b")); assertFalse(c.errado("a"))
        agora = 1001; assertFalse(c.certo("a"))
        c.lembrarErrado("x"); assertTrue(c.errado("x"))
        c.lembrarCerto("1"); c.lembrarCerto("2"); c.lembrarCerto("3")   // máximo 2: o mais velho sai
        assertFalse(c.certo("1")); assertTrue(c.certo("3"))
        c.limpar(); assertFalse(c.certo("3")); assertFalse(c.errado("x"))
    }

    @Test fun hrefCodificaCadaPedaco() {
        assertEquals("/dav/", DavLogica.href("", true))
        assertEquals("/dav/Download/", DavLogica.href("Download", true))
        assertEquals("/dav/Download/F%C3%A9rias%20%26%20%3Cx%3E.mp4", DavLogica.href("Download/Férias & <x>.mp4", false))
    }

    @Test fun multistatusEscapaNomesEDizOEspaco() {
        val x = DavLogica.multistatus(listOf(DavLogica.Item("", true, 0, 0), DavLogica.Item("a<b>&.txt", false, 5, 0)), 100, 50)
        assertTrue(x.contains("<D:quota-available-bytes>100</D:quota-available-bytes>"))
        assertTrue(x.contains("<D:displayname>a&lt;b&gt;&amp;.txt</D:displayname>"))
        assertTrue(x.contains("<D:getcontentlength>5</D:getcontentlength>"))
        assertTrue(x.contains("<D:collection/>"))
        assertFalse(x.contains("a<b>"))
        assertEquals(1, Regex("quota-available").findAll(x).count() / 2)   // só no primeiro item
    }

    @Test fun raizDoServidor() {
        val zero = DavLogica.multistatusRaiz(0, 0)
        assertTrue(zero.contains("<D:href>/</D:href>")); assertFalse(zero.contains("<D:href>/dav/</D:href>"))
        val um = DavLogica.multistatusRaiz(1, 0)
        assertTrue(um.contains("<D:href>/</D:href>")); assertTrue(um.contains("<D:href>/dav/</D:href>"))
    }

    @Test fun datas() {
        assertEquals("Thu, 01 Jan 1970 00:00:00 GMT", DavLogica.dataHttp(0))
        assertEquals("1970-01-01T00:00:00Z", DavLogica.dataIso(0))
        assertEquals(1_000L * 86_400, DavLogica.lerDataHttp("Fri, 02 Jan 1970 00:00:00 GMT"))
        assertNull(DavLogica.lerDataHttp("ontem"))
        assertNull(DavLogica.lerDataHttp(""))
    }

    @Test fun proppatchDoWindowsDevolveADataPedida() {
        val xml = """<?xml version="1.0" encoding="utf-8" ?><D:propertyupdate xmlns:D="DAV:" xmlns:Z="urn:schemas-microsoft-com:">
            <D:set><D:prop><Z:Win32CreationTime>Sat, 14 Nov 2026 10:00:00 GMT</Z:Win32CreationTime>
            <Z:Win32LastModifiedTime>Sat, 14 Nov 2026 11:00:00 GMT</Z:Win32LastModifiedTime>
            <Z:Win32FileAttributes>00000020</Z:Win32FileAttributes></D:prop></D:set></D:propertyupdate>"""
        val p = DavLogica.propsDoProppatch(xml)
        assertEquals(listOf("Win32CreationTime", "Win32LastModifiedTime", "Win32FileAttributes"), p.map { it.nome })
        assertTrue(p.all { it.ns == "urn:schemas-microsoft-com:" })
        assertEquals(DavLogica.lerDataHttp("Sat, 14 Nov 2026 11:00:00 GMT"), DavLogica.dataPedida(p))
        val resp = DavLogica.proppatchXml("a.txt", false, p)
        assertTrue(resp.contains("<P1:Win32LastModifiedTime xmlns:P1=\"urn:schemas-microsoft-com:\"/>"))
        assertTrue(resp.contains("200 OK"))
    }

    @Test fun donoDoLock() {
        assertEquals("Paulo", DavLogica.donoDoLock("<D:lockinfo xmlns:D='DAV:'><D:owner><D:href>Paulo</D:href></D:owner></D:lockinfo>"))
        assertNull(DavLogica.donoDoLock("<lockinfo/>"))
        val x = DavLogica.lockXml("opaquelocktoken:1", "a b.txt", false, "<x>", 60)
        assertTrue(x.contains("<D:owner>&lt;x&gt;</D:owner>")); assertTrue(x.contains("/dav/a%20b.txt"))
    }

    @Test fun envioPelaMetadeNaoApareceNaLista() {
        assertTrue(DavLogica.escondido(".filme.mp4.12345.parcial"))
        assertFalse(DavLogica.escondido("filme.parcial")); assertFalse(DavLogica.escondido(".DS_Store"))
    }

    private fun chunked(s: String) = DavLogica.LeitorChunked(s.toByteArray(Charsets.ISO_8859_1).inputStream()).readBytes().toString(Charsets.ISO_8859_1)

    @Test fun corpoChunked() {
        assertEquals("Wikipedia", chunked("4\r\nWiki\r\n5;ext=1\r\npedia\r\n0\r\n\r\n"))
        assertEquals("", chunked("0\r\n\r\n"))
        assertEquals("ab", chunked("2\r\nab\r\n0\r\nX-Trailer: 1\r\n\r\nresto"))
        assertEquals("a".repeat(300), chunked("12C\r\n" + "a".repeat(300) + "\r\n0\r\n\r\n"))
    }

    @Test fun corpoChunkedQuebradoFalha() {
        for (s in listOf("zz\r\nab\r\n0\r\n\r\n", "5\r\nab", "2\r\nabXY0\r\n\r\n", "", "-1\r\n", "fffffffffffff\r\n"))
            try { chunked(s); throw AssertionError("aceitou: $s") } catch (e: IOException) { /* esperado */ }
    }
}
