package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpnpTest {
    @Test fun localDaRespostaSsdp() {
        val r = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=1800\r\nLocation: http://192.168.1.20:8200/rootDesc.xml\r\nST: x\r\n\r\n"
        assertEquals("http://192.168.1.20:8200/rootDesc.xml", Upnp.localDaResposta(r))
        assertNull(Upnp.localDaResposta("HTTP/1.1 200 OK\r\nLOCATION: file:///etc/passwd\r\n"))
    }

    @Test fun fichaAchaContentDirectoryRelativo() {
        val xml = """<?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <friendlyName>NAS da casa</friendlyName>
              <serviceList>
                <service><serviceType>urn:schemas-upnp-org:service:ConnectionManager:1</serviceType><controlURL>/cm</controlURL></service>
                <service><serviceType>urn:schemas-upnp-org:service:ContentDirectory:1</serviceType><controlURL>/ctl/ContentDir</controlURL></service>
              </serviceList></device></root>""".toByteArray()
        val s = Upnp.lerFicha(xml, "http://192.168.1.20:8200/rootDesc.xml")!!
        assertEquals("NAS da casa", s.nome)
        assertEquals("http://192.168.1.20:8200/ctl/ContentDir", s.pastas)
    }

    @Test fun fichaSemPastasDaNull() {
        val xml = """<root><device><friendlyName>TV</friendlyName><serviceList>
            <service><serviceType>urn:schemas-upnp-org:service:AVTransport:1</serviceType><controlURL>/av</controlURL></service>
            </serviceList></device></root>""".toByteArray()
        assertNull(Upnp.lerFicha(xml, "http://10.0.0.2/d.xml"))
    }

    @Test fun respostaNavegarEDidl() {
        val didl = """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">""" +
            """<item id="v1" parentID="2"><dc:title>Férias &amp; praia</dc:title><upnp:class>object.item.videoItem.movie</upnp:class><res protocolInfo="http-get:*:video/mp4:*">http://192.168.1.20:8200/MediaItems/1.mp4</res></item>""" +
            """<container id="2$1" parentID="2" childCount="3"><dc:title>Filmes</dc:title><upnp:class>object.container.storageFolder</upnp:class></container>""" +
            """<item id="x"><dc:title>sem endereço</dc:title><upnp:class>object.item.videoItem</upnp:class></item>""" +
            "</DIDL-Lite>"
        val esc = didl.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val soap = """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body>
            <u:BrowseResponse xmlns:u="urn:schemas-upnp-org:service:ContentDirectory:1"><Result>$esc</Result>
            <NumberReturned>3</NumberReturned><TotalMatches>3</TotalMatches></u:BrowseResponse></s:Body></s:Envelope>""".toByteArray()
        val (texto, total) = Upnp.lerRespostaNavegar(soap)
        assertEquals(3, total)
        val e = Upnp.lerDidl(texto)
        assertEquals(2, e.size)
        assertTrue(e[0].pasta); assertEquals("Filmes", e[0].titulo); assertEquals("2$1", e[0].id)
        assertEquals("Férias & praia", e[1].titulo)
        assertEquals(Upnp.Tipo.VIDEO, e[1].tipo)
        assertEquals("http://192.168.1.20:8200/MediaItems/1.mp4", e[1].url)
    }

    @Test(expected = IllegalArgumentException::class)
    fun recusaDoctype() {
        Upnp.lerFicha("""<?xml version="1.0"?><!DOCTYPE r [<!ENTITY x SYSTEM "file:///etc/passwd">]><root>&x;</root>""".toByteArray(), "http://a/b")
    }

    @Test fun corpoEscapaId() {
        assertTrue(Upnp.corpoNavegar("a<b&c", 0, 10).contains("<ObjectID>a&lt;b&amp;c</ObjectID>"))
    }
}
