package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.Ssdp
import br.paulo.tvlauncher.player.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Os textos SSDP/UPnP que põem a TV na tela "Rede" do Windows (Ssdp.kt). */
class SsdpTest {
    private val uuid = Ssdp.uuid("abc123")
    private val local = "http://192.0.2.10:8080/upnp/onetv.xml"

    @Test fun uuidEstavelPorTv() {
        assertEquals(uuid, Ssdp.uuid("abc123"))
        assertFalse(uuid == Ssdp.uuid("outra"))
    }

    @Test fun anunciosVivoESaida() {
        val vivo = Ssdp.notify(uuid, local, true)
        assertEquals(3, vivo.size)
        assertTrue(vivo.all { it.startsWith("NOTIFY * HTTP/1.1\r\n") && it.endsWith("\r\n\r\n") && "NTS: ssdp:alive" in it && "LOCATION: $local" in it })
        assertTrue(vivo.any { "NT: upnp:rootdevice\r\n" in it && "USN: uuid:$uuid::upnp:rootdevice" in it })
        val sai = Ssdp.notify(uuid, local, false)
        assertTrue(sai.all { "NTS: ssdp:byebye" in it && "LOCATION" !in it })
    }

    /** Byte a byte o que foi mandado à mão (nc) na Sala em 16/11/2026 e fez o Windows 11 soltar e reconectar. */
    @Test fun avisoDoAparelhoIgualAoMedidoNoWindows() {
        val u = "9b2a99ef-43cc-35a3-8470-cb83ff697715"
        val l = "http://192.0.2.10:8080/upnp/onetv-mtp.xml"
        assertEquals("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nNT: uuid:$u\r\nNTS: ssdp:byebye\r\nUSN: uuid:$u\r\n\r\n",
            Ssdp.avisoDoAparelho(u, l, false))
        assertEquals("NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=1800\r\nLOCATION: $l\r\n" +
            "SERVER: Android UPnP/1.0 OneTV/1.0\r\nNT: uuid:$u\r\nNTS: ssdp:alive\r\nUSN: uuid:$u\r\n\r\n",
            Ssdp.avisoDoAparelho(u, l, true))
    }

    private fun busca(st: String, man: String = "\"ssdp:discover\"") =
        "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: $man\r\nMX: 1\r\nST: $st\r\n\r\n"

    @Test fun respondeSoAoQueEhNosso() {
        assertEquals(3, Ssdp.respostas(busca("ssdp:all"), uuid, local).size)
        val raiz = Ssdp.respostas(busca("upnp:rootdevice"), uuid, local)
        assertEquals(1, raiz.size)
        assertTrue(raiz[0].startsWith("HTTP/1.1 200 OK\r\n") && "ST: upnp:rootdevice" in raiz[0] && "EXT:" in raiz[0])
        assertEquals(1, Ssdp.respostas(busca(Ssdp.TIPO), uuid, local).size)
        assertEquals(1, Ssdp.respostas(busca("uuid:$uuid"), uuid, local).size)
        assertTrue(Ssdp.respostas(busca("urn:dial-multiscreen-org:service:dial:1"), uuid, local).isEmpty())
        assertTrue(Ssdp.respostas(busca("ssdp:all", man = "\"outra\""), uuid, local).isEmpty())
        assertTrue(Ssdp.respostas("NOTIFY * HTTP/1.1\r\nNT: x\r\n\r\n", uuid, local).isEmpty())
        assertTrue(Ssdp.respostas(busca("ssdp:all") + "X".repeat(3000), uuid, local).isEmpty())
    }

    @Test fun descricaoEscapaONome() {
        val x = Ssdp.descricao(Ssdp.nomeVisivel("Sala & <Cozinha>"), uuid, "http://192.0.2.10:8080/")
        assertTrue("<friendlyName>Sofaly · Sala &amp; &lt;Cozinha&gt; · painel</friendlyName>" in x)
        assertTrue("<presentationURL>http://192.0.2.10:8080/</presentationURL>" in x)
        assertTrue("<UDN>uuid:$uuid</UDN>" in x)
        assertTrue("<deviceType>${Ssdp.TIPO}</deviceType>" in x)
    }

    @Test fun aparelhoMtpTemOTipoQueOWindowsProcura() {
        val u = Ssdp.uuidMtp("abc123")
        assertTrue(u != Ssdp.uuid("abc123"))
        val n = Ssdp.notify(u, "http://192.168.1.9:8080${Ssdp.CAMINHO_MTP}", true, Ssdp.TIPO_MTP)
        assertTrue(n.any { "NT: urn:microsoft-com:device:mtp:1\r\n" in it })
        val busca = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: ${Ssdp.TIPO_MTP}\r\n\r\n"
        assertEquals(1, Ssdp.respostas(busca, u, "http://x/", Ssdp.TIPO_MTP).size)
        assertEquals(0, Ssdp.respostas(busca, u, "http://x/").size)   // o aparelho Basic não responde por MTP
        val xml = Ssdp.descricaoMtp("Sala <1> & cia", u, "http://192.168.1.9:8080/", "abc123")
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(xml.byteInputStream())
        assertEquals("urn:microsoft-com:device:mtp:1", doc.getElementsByTagName("deviceType").item(0).textContent)
        assertEquals("Sala <1> & cia", doc.getElementsByTagName("friendlyName").item(0).textContent)
        assertEquals("urn:microsoft-com:device:mtp:1",
            doc.getElementsByTagNameNS("http://schemas.microsoft.com/windows/pnpx/2005/11", "X_compatibleId").item(0).textContent)
        javax.xml.parsers.DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(Ssdp.SERVICO_MTP.byteInputStream())
    }

    @Test fun nomeEmEsteComputadorLevaAMarcaNaFrente() {
        assertEquals("Sofaly · Sala de estar", Ssdp.nomeNaUnidade(" Sala de estar "))
        assertEquals("Sofaly da sala", Ssdp.nomeNaUnidade("Sofaly da sala"))   // sem marca repetida
        assertTrue(Ssdp.nomeNaUnidade("x".repeat(200)).length <= 60)
    }
}
