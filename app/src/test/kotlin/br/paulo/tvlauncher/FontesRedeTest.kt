package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/** Fontes de mídia novas de 10/11/2026: Plex, Emby, WebDAV, links e a busca NetBIOS. Tudo sem rede, só texto. */
class FontesRedeTest {
    private val plex = ContaMidia("p1", ContaMidia.PLEX, "Sala", "http://192.168.1.5:32400", token = "TOK en", usuarioId = "abc123")
    private val dav = ContaMidia("d1", ContaMidia.WEBDAV, "Nuvem", "https://nuvem.casa.com/remote.php/dav/files/ana", usuario = "ana", senha = "s3nha")

    // ------------------------------------------------------------ Plex

    @Test fun plexAchadoNaRede() {
        val r = "HTTP/1.0 200 OK\r\nContent-Type: plex/media-server\r\nResource-Identifier: abc123\r\nName: Plex da Sala\r\nPort: 32400\r\n\r\n"
        assertEquals(Plex.Achado("Plex da Sala", "http://192.168.1.5:32400", "abc123"), Plex.lerAchado(r, "192.168.1.5"))
        assertNull(Plex.lerAchado(r.replace("plex/media-server", "plex/media-player"), "192.168.1.5"))   // player, não servidor
        assertNull(Plex.lerAchado(r.replace("32400", "x"), "192.168.1.5"))
        assertNull(Plex.lerAchado(r.replace("abc123", "../x"), "192.168.1.5"))
    }

    @Test fun plexCodigoEToken() {
        val c = Plex.lerCodigo("""{"id":987,"code":"ab12","expiresIn":900,"authToken":null}""")
        assertEquals(987L, c.id); assertEquals("AB12", c.codigo); assertEquals(900, c.expiraEmS)
        assertNull(Plex.lerTokenDoCodigo("""{"id":987,"code":"AB12","authToken":null}"""))
        assertEquals("xyz", Plex.lerTokenDoCodigo("""{"id":987,"authToken":"xyz"}"""))
        assertTrue(runCatching { Plex.lerCodigo("""{"id":1,"code":"<b>"}""") }.isFailure)
    }

    @Test fun plexServidoresDaContaComEnderecoLocalPrimeiro() {
        val json = """[
          {"name":"Sala","provides":"server","clientIdentifier":"abc123","accessToken":"st","connections":[
            {"protocol":"https","address":"200.1.2.3","port":32400,"uri":"https://200-1-2-3.x.plex.direct:32400","local":false,"relay":false},
            {"protocol":"https","address":"192.168.1.5","port":32400,"uri":"https://192-168-1-5.x.plex.direct:32400","local":true,"relay":false}]},
          {"name":"Celular","provides":"player","clientIdentifier":"p","accessToken":"t","connections":[]},
          {"name":"Sem token","provides":"server","clientIdentifier":"q","connections":[]}]"""
        val s = Plex.lerServidores(json)
        assertEquals(1, s.size)
        assertEquals("st", s[0].token)
        assertEquals(listOf("https://192-168-1-5.x.plex.direct:32400", "http://192.168.1.5:32400", "https://200-1-2-3.x.plex.direct:32400"),
            s[0].enderecos.map { it.uri })
        assertTrue(Plex.lerServidores("lixo").isEmpty())
    }

    @Test fun plexListaPastasEArquivos() {
        val bib = Plex.lerBibliotecas("""{"MediaContainer":{"Directory":[{"key":"1","title":"Filmes","type":"movie"},{"key":"../2","title":"X"}]}}""")
        assertEquals(listOf("/library/sections/1/all"), bib.map { it.id })
        val itens = Plex.lerItens("""{"MediaContainer":{"Metadata":[
            {"ratingKey":"10","key":"/library/metadata/10","type":"movie","title":"Filme","year":2021,
             "Media":[{"Part":[{"key":"/library/parts/55/1699/file.mkv"}]}]},
            {"ratingKey":"20","key":"/library/metadata/20/children","type":"show","title":"Série"},
            {"ratingKey":"30","key":"/library/metadata/30","type":"episode","title":"Ep","grandparentTitle":"Série","parentIndex":2,"index":5,
             "Media":[{"Part":[{"key":"http://mal.com/x"}]}]},
            {"ratingKey":"40","key":"http://mal.com/y","type":"show","title":"Mal"}]}}""", plex)
        assertEquals(listOf("Série", "Filme (2021)"), itens.map { it.titulo })   // pasta primeiro; o resto foi recusado
        assertEquals("/library/metadata/20/children", itens[0].id)
        val u = itens[1].url!!
        assertEquals(Plex.Alvo("p1", "10", "/library/parts/55/1699/file.mkv"), Plex.lerUri(u))
        assertFalse(u.contains("TOK"))   // o token nunca vai no endereço que circula
        assertEquals("http://192.168.1.5:32400/library/parts/55/1699/file.mkv?X-Plex-Token=TOK%20en", Plex.urlTocar(plex, "/library/parts/55/1699/file.mkv"))
    }

    @Test fun plexEpisodioNoContinuar() {
        val o = org.json.JSONObject("""{"type":"episode","title":"Ep","grandparentTitle":"Série","parentIndex":2,"index":5}""")
        assertEquals("Série · T2E5 · Ep", Plex.nomeExibido(o, comSerie = true))
        assertEquals("T2E5 · Ep", Plex.nomeExibido(o))
    }

    @Test fun plexCaminhosRecusados() {
        assertFalse(Plex.caminhoValido("/library/../system"))
        assertFalse(Plex.caminhoValido("http://x/library/a"))
        assertFalse(Plex.parteValida("/library/metadata/1"))
        assertNull(Plex.lerUri("plex://p1/10?p=" + java.net.URLEncoder.encode("/library/parts/../../x", "UTF-8")))
        assertNull(Plex.lerUri("plex://p1/abc?p=%2Flibrary%2Fparts%2F1"))
        assertTrue(Plex.caminhoLinhaDoTempo("10", Plex.Estado.PAUSADO, 5000, 60000)
            .startsWith("/:/timeline?ratingKey=10&key=%2Flibrary%2Fmetadata%2F10&state=paused&time=5000&duration=60000"))
    }

    // ------------------------------------------------------------ Emby

    @Test fun embyAchadoEContaMarcada() {
        val a = Jellyfin.lerAchado("""{"Address":"http://192.168.1.7:8096","Id":"x","Name":"Emby da casa"}""", emby = true)
        assertEquals(Jellyfin.Achado("Emby da casa", "http://192.168.1.7:8096", true), a)
        val c = ContaMidia("e", ContaMidia.EMBY, "Emby", "http://x:8096")
        assertTrue(c.ehJellyOuEmby)
        assertEquals(1, MidiaContas.deJson(MidiaContas.paraJson(listOf(c, plex, dav))).count { it.tipo == ContaMidia.EMBY })
        assertEquals(3, MidiaContas.deJson(MidiaContas.paraJson(listOf(c, plex, dav))).size)
    }

    // ------------------------------------------------------------ WebDAV

    @Test fun webdavEndereco() {
        assertEquals("https://nuvem.casa.com/remote.php/dav/files/ana", MidiaContas.lerEnderecoWebDav("nuvem.casa.com/remote.php/dav/files/ana/"))
        assertEquals("http://nas:5005/video", MidiaContas.lerEnderecoWebDav("dav://nas:5005/video"))
        assertEquals("https://nas", MidiaContas.lerEnderecoWebDav("davs://nas"))
        assertNull(MidiaContas.lerEnderecoWebDav("https://eu:senha@nas/x"))   // senha no endereço: não
        assertNull(MidiaContas.lerEnderecoWebDav("na s"))
    }

    @Test fun webdavMultistatus() {
        val xml = """<?xml version="1.0"?>
            <d:multistatus xmlns:d="DAV:">
              <d:response><d:href>/remote.php/dav/files/ana/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/ana/Filmes%20HD/</d:href><d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/ana/ferias.mp4</d:href><d:propstat><d:prop><d:resourcetype/><d:getcontenttype>video/mp4</d:getcontenttype></d:prop></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/ana/.nextcloud</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/outro/segredo.mp4</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
              <d:response><d:href>/remote.php/dav/files/ana/../outro/x.mp4</d:href><d:propstat><d:prop><d:resourcetype/></d:prop></d:propstat></d:response>
            </d:multistatus>"""
        val e = WebDav.lerMultistatus(xml.toByteArray(), dav, "/remote.php/dav/files/ana/")
        assertEquals(listOf("Filmes HD", "ferias.mp4"), e.map { it.titulo })
        assertTrue(e[0].pasta)
        assertEquals(Upnp.Tipo.VIDEO, e[1].tipo)
        assertEquals("webdav://d1/remote.php/dav/files/ana/ferias.mp4", e[1].url)
        val alvo = WebDav.lerUri(e[1].url!!)!!
        assertEquals("https://nuvem.casa.com/remote.php/dav/files/ana/ferias.mp4", WebDav.urlTocar(dav, alvo.caminho))
        assertNull(WebDav.urlTocar(dav, "/remote.php/dav/files/outro/x.mp4"))
        assertEquals("Basic YW5hOnMzbmhh", WebDav.cabecalhoAuth(dav)["Authorization"])   // "ana:s3nha" (o usuário do teste virou "ana" ao tirar dados pessoais)
        assertTrue(runCatching { WebDav.lerMultistatus("<!DOCTYPE x [<!ENTITY a 'b'>]><d:multistatus xmlns:d=\"DAV:\"/>".toByteArray(), dav, "/") }.isFailure)
    }

    @Test fun respostaHttpChunked() {
        val r = "HTTP/1.1 207 Multi-Status\r\nTransfer-Encoding: chunked\r\nContent-Type: application/xml\r\n\r\n5\r\nolá \r\n3\r\nmun\r\n0\r\n\r\n"
        val lido = WebDav.lerResposta(ByteArrayInputStream(r.toByteArray(Charsets.UTF_8)))
        assertEquals(207, lido.codigo)
        assertEquals("olá mun", String(lido.corpo, Charsets.UTF_8))
        val r2 = "HTTP/1.1 401 Unauthorized\r\nContent-Length: 3\r\n\r\nabcdef"
        val l2 = WebDav.lerResposta(ByteArrayInputStream(r2.toByteArray()))
        assertEquals(401, l2.codigo); assertEquals("abc", String(l2.corpo))
    }

    // ------------------------------------------------------------ links

    @Test fun linksEListas() {
        assertEquals("https://x.com/v.mp4", MidiaContas.lerLink(" https://x.com/v.mp4 "))
        assertNull(MidiaContas.lerLink("rtmp://x/y"))
        assertNull(MidiaContas.lerLink("https://a b"))
        assertEquals("Meu filme", MidiaContas.nomeDoLink("http://nas/videos/Meu%20filme.mkv?x=1"))
        assertEquals("nas:8080", MidiaContas.nomeDoLink("http://nas:8080/"))
        assertTrue(FontesMidia.ehListaDeEntradas("#EXTM3U\n#EXTINF:-1,Um\nhttp://a/1.mp4\n#EXTINF:-1,Dois\nhttp://a/2.mp4\n"))
        assertFalse(FontesMidia.ehListaDeEntradas("#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXTINF:6.0,\nseg1.ts\n"))   // HLS = um vídeo
        assertFalse(FontesMidia.ehListaDeEntradas("ftypmp42"))
    }

    // ------------------------------------------------------------ NetBIOS

    @Test fun netbiosPedidoEResposta() {
        val p = Descoberta.pedidoNodeStatus(0x1234)
        assertEquals(50, p.size)
        assertEquals("CKAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA", String(p, 13, 32, Charsets.US_ASCII))
        assertEquals(0x21, p[47].toInt())
        assertEquals("DELL-5510", Descoberta.lerNodeStatus(resposta(listOf("DELL-5510" to 0x00, "WORKGROUP" to 0x00, "DELL-5510" to 0x20))))
        assertNull(Descoberta.lerNodeStatus(resposta(listOf("CELULAR" to 0x00))))   // sem serviço de arquivos
        assertNull(Descoberta.lerNodeStatus(ByteArray(20)))
    }

    /** Monta uma resposta "node status" como a do Windows. */
    private fun resposta(nomes: List<Pair<String, Int>>): ByteArray {
        val b = java.io.ByteArrayOutputStream()
        b.write(byteArrayOf(0x12, 0x34, 0x84.toByte(), 0, 0, 0, 0, 1, 0, 0, 0, 0))
        b.write(0x20); repeat(32) { b.write('A'.code) }; b.write(0)
        b.write(byteArrayOf(0, 0x21, 0, 1, 0, 0, 0, 0)); b.write(0); b.write(nomes.size * 18 + 1)
        b.write(nomes.size)
        for ((n, suf) in nomes) {
            b.write(n.padEnd(15).toByteArray(Charsets.US_ASCII)); b.write(suf); b.write(byteArrayOf(0x04, 0))
        }
        b.write(ByteArray(46))
        return b.toByteArray()
    }
}
