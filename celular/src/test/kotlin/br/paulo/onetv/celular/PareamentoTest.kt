package br.paulo.onetv.celular

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PareamentoTest {
    private val cod = "0123456789abcdef0123456789abcdef0123456789abcdef"
    private val fp = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    @Test fun `le o QR completo da TV`() {
        val l = Pareamento.ler("http://192.168.1.50:8080/#qr=$cod&ir=controle&fp=$fp&ts=onetv-sala.tail1.ts.net")!!
        assertEquals("192.168.1.50", l.ip); assertEquals(cod, l.codigo); assertFalse(l.primeira)
        assertEquals(fp, l.fp); assertEquals("onetv-sala.tail1.ts.net", l.ts); assertEquals("controle", l.ir)
        assertEquals("#qr=$cod&ir=controle", Pareamento.fragmento(l))   // fp/ts não vão para o painel
    }

    @Test fun `QR da primeira configuracao`() {
        val l = Pareamento.ler("http://10.0.0.7:8080/#primeira=$cod")!!
        assertTrue(l.primeira); assertEquals("", l.fp); assertEquals("#primeira=$cod", Pareamento.fragmento(l))
    }

    @Test fun `recusa QR que nao e da TV`() {
        assertNull(Pareamento.ler("http://8.8.8.8:8080/#qr=$cod"))            // IP público
        assertNull(Pareamento.ler("http://010.1.2.3:8080/#qr=$cod"))          // octal: o WebView abriria 8.1.2.3
        assertNull(Pareamento.ler("http://10.01.2.3:8080/#qr=$cod"))
        assertNull(Pareamento.ler("https://192.168.1.50:8080/#qr=$cod"))      // não é o QR da TV
        assertNull(Pareamento.ler("http://192.168.1.50:80/#qr=$cod"))         // porta errada
        assertNull(Pareamento.ler("http://192.168.1.50:8080/#qr=123"))        // código curto
        assertNull(Pareamento.ler("http://evil.com@192.168.1.50:8080/#qr=$cod"))
        assertNull(Pareamento.ler(null))
    }

    @Test fun `campos estranhos sao ignorados, nao aceitos`() {
        val l = Pareamento.ler("http://192.168.1.50:8080/#qr=$cod&fp=xyz&ts=evil.com")!!
        assertEquals("", l.fp); assertEquals("", l.ts)
    }

    @Test fun `enderecos de casa e de fora`() {
        assertEquals("https://192.168.1.50:8444/", Pareamento.enderecoCasa(Pareamento.Tv("TV", "192.168.1.50", fp)))
        assertEquals("http://192.168.1.50:8080/", Pareamento.enderecoCasa(Pareamento.Tv("TV", "192.168.1.50")))
        assertNull(Pareamento.enderecoFora(Pareamento.Tv("TV", "192.168.1.50")))
        assertEquals("https://a.b.ts.net:8443/", Pareamento.enderecoFora(Pareamento.Tv("TV", "1.2.3.4", ts = "a.b.ts.net")))
    }

    @Test fun `so os enderecos que a TV serve ficam no app`() {
        val par = Pareamento.Tv("TV", "192.168.1.50", fp, ts = "a.b.ts.net")
        val achada = Pareamento.Tv("TV", "192.168.1.50")
        assertTrue(Pareamento.daTv(par, "https", "192.168.1.50", 8444))
        assertTrue(Pareamento.daTv(par, "http", "192.168.1.50", 8080))
        assertTrue(Pareamento.daTv(par, "https", "a.b.ts.net", 8443))
        assertFalse(Pareamento.daTv(par, "http", "192.168.1.50", 9000))      // outro programa no mesmo IP
        assertFalse(Pareamento.daTv(par, "http", "a.b.ts.net", 8443))
        assertFalse(Pareamento.daTv(achada, "https", "192.168.1.50", 8444))  // sem certificado fixado
        assertFalse(Pareamento.daTv(par, "https", "192.168.1.51", 8444))
    }

    @Test fun `confere so o certificado lido no QR`() {
        val tv = Pareamento.Tv("TV", "192.168.1.50", fp)
        assertTrue(Pareamento.confere(tv, "abc".toByteArray()))
        assertFalse(Pareamento.confere(tv, "abd".toByteArray()))
        assertFalse(Pareamento.confere(tv, null))
        assertFalse(Pareamento.confere(Pareamento.Tv("TV", "192.168.1.50"), "abc".toByteArray()))  // sem fp, nunca
    }

    @Test fun `lista guarda a mais recente no topo, sem repetir e no maximo 8`() {
        var l = emptyList<Pareamento.Tv>()
        for (i in 1..10) l = Pareamento.guardar(l, Pareamento.Tv("TV$i", "192.168.1.$i"))
        assertEquals(8, l.size); assertEquals("TV10", l[0].nome)
        l = Pareamento.guardar(l, Pareamento.Tv("Nova", "192.168.1.5"))
        assertEquals(1, l.count { it.ip == "192.168.1.5" }); assertEquals("Nova", l[0].nome)
        assertEquals(l, Pareamento.deJson(Pareamento.paraJson(l)))
        assertEquals(emptyList<Pareamento.Tv>(), Pareamento.deJson("lixo"))
    }

    @Test fun `varias casas - mesmo IP em casas diferentes sao duas TVs`() {
        val sala = Pareamento.Tv("Sala", "192.168.1.50")
        val praia = Pareamento.Tv("Praia", "192.168.1.50", casa = "Praia")
        var l = Pareamento.guardar(Pareamento.guardar(emptyList(), sala), praia)
        assertEquals(2, l.size)
        assertEquals(listOf("Praia", ""), Pareamento.casas(l))
        assertEquals(2, Pareamento.porCasa(l).size)
        assertTrue(Pareamento.chave(sala) != Pareamento.chave(praia))
        // A mesma TV (nome no Tailscale) lida de novo em outro IP é atualizada, não duplicada.
        val a = Pareamento.Tv("Sala", "192.168.1.50", ts = "onetv-sala.tail1.ts.net")
        l = Pareamento.guardar(Pareamento.guardar(emptyList(), a), a.copy(ip = "192.168.0.9"))
        assertEquals(1, l.size); assertEquals("192.168.0.9", l[0].ip)
        assertEquals(l, Pareamento.deJson(Pareamento.paraJson(l)))
        assertEquals("Casa da mãe", Pareamento.nomeCasa("  Casa   da mãe <>"))
        assertEquals("Praia", Pareamento.deJson(Pareamento.paraJson(listOf(praia)))[0].casa)
    }

    @Test fun `volume do celular vira tecla da TV`() {
        assertEquals("volume+", Pareamento.teclaDoVolume(true)); assertEquals("volume-", Pareamento.teclaDoVolume(false))
    }
}
