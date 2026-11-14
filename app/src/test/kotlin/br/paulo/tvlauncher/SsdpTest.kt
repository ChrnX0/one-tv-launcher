package br.paulo.tvlauncher

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
        val x = Ssdp.descricao(Ssdp.nomeVisivel("Sala & <Cozinha>"), uuid, "http://192.0.2.10:8080/unidade")
        assertTrue("<friendlyName>Sala &amp; &lt;Cozinha&gt; · arquivos</friendlyName>" in x)
        assertTrue("<presentationURL>http://192.0.2.10:8080/unidade</presentationURL>" in x)
        assertTrue("<UDN>uuid:$uuid</UDN>" in x)
        assertTrue("<deviceType>${Ssdp.TIPO}</deviceType>" in x)
    }
}
