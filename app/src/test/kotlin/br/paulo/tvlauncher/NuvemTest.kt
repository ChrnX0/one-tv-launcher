package br.paulo.tvlauncher

import br.paulo.tvlauncher.backup.Nuvem
import br.paulo.tvlauncher.panel.Pastas
import br.paulo.tvlauncher.player.ContaMidia
import br.paulo.tvlauncher.player.MidiaContas
import br.paulo.tvlauncher.player.item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** OneDrive e Dropbox como pasta (Nuvem.kt): endereços, respostas e PKCE. Sem rede. */
class NuvemTest {
    @Test fun idDaMicrosoftEhUmGuid() {
        assertTrue(Nuvem.idMicrosoftValido("0a1b2c3d-4e5f-6789-abcd-ef0123456789"))
        assertTrue(Nuvem.idMicrosoftValido(" 0A1B2C3D-4E5F-6789-ABCD-EF0123456789 "))
        assertFalse(Nuvem.idMicrosoftValido("0a1b2c3d4e5f6789abcdef0123456789"))
        assertFalse(Nuvem.idMicrosoftValido("0a1b2c3d-4e5f-6789-abcd-ef012345678g"))
    }

    @Test fun enderecosDoGraphCodificamCadaPedaco() {
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root", Nuvem.graphItem(""))
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root:/Filmes/A%20Origem%20%282010%29.mkv:",
            Nuvem.graphItem("Filmes/A Origem (2010).mkv"))
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root:/S%C3%A9ries%23/x%3Fy:", Nuvem.graphItem("Séries#/x?y"))
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/root/children?\$top=500&\$select=name,size,folder", Nuvem.graphFilhos(""))
        assertTrue(Nuvem.graphFilhos("Fotos").startsWith("https://graph.microsoft.com/v1.0/me/drive/root:/Fotos:/children?"))
    }

    @Test fun listaDoGraphComPastaArquivoEProximaPagina() {
        val (itens, prox) = Nuvem.lerFilhosGraph("""{"value":[{"name":"Filmes","size":0,"folder":{"childCount":3}},
            {"name":"a.mkv","size":1234}],"@odata.nextLink":"https://graph.microsoft.com/v1.0/me/drive/items/x/children?skip=2"}""")
        assertEquals(2, itens.size)
        assertTrue(itens[0].pasta); assertEquals("Filmes", itens[0].nome)
        assertFalse(itens[1].pasta); assertEquals(1234L, itens[1].tamanho)
        assertEquals("https://graph.microsoft.com/v1.0/me/drive/items/x/children?skip=2", prox)
    }

    @Test fun proximaPaginaForaDoGraphEhIgnorada() {
        // O token vai junto no pedido da próxima página: só se ela for do próprio Graph.
        assertNull(Nuvem.lerFilhosGraph("""{"value":[],"@odata.nextLink":"https://outro.site/roubar"}""").second)
    }

    @Test fun caminhoDoDropbox() {
        assertEquals("", Nuvem.caminhoDropbox(""))
        assertEquals("/Filmes/a b.mkv", Nuvem.caminhoDropbox("Filmes/a b.mkv"))
        assertTrue(Nuvem.chaveDropboxValida("abc123xyz9"))
        assertFalse(Nuvem.chaveDropboxValida("ABC 123"))
    }

    @Test fun listaDoDropboxIgnoraApagados() {
        val (itens, cursor, mais) = Nuvem.lerListaDropbox("""{"entries":[{".tag":"folder","name":"Séries"},
            {".tag":"file","name":"b.mp4","size":99},{".tag":"deleted","name":"velho"}],"cursor":"C1","has_more":true}""")
        assertEquals(listOf("Séries", "b.mp4"), itens.map { it.nome })
        assertTrue(itens[0].pasta); assertEquals(99L, itens[1].tamanho)
        assertEquals("C1", cursor); assertTrue(mais)
    }

    @Test fun pkceDaRfc7636() {
        // Exemplo do apêndice B da RFC 7636.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", Nuvem.desafio("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val v = Nuvem.novoVerificador()
        assertEquals(43, v.length)
        assertTrue(v.all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test fun paginaDoDropboxPedeTokenDeRenovacaoEPkce() {
        val u = Nuvem.paginaDropbox("abc123xyz9", "DESAFIO")
        assertTrue(u.startsWith("https://www.dropbox.com/oauth2/authorize?client_id=abc123xyz9&response_type=code"))
        assertTrue(u.contains("token_access_type=offline"))
        assertTrue(u.contains("code_challenge=DESAFIO&code_challenge_method=S256"))
        assertFalse(u.contains("redirect_uri"))
    }

    @Test fun respostasDeToken() {
        val t = Nuvem.lerTokens("""{"access_token":"A","refresh_token":"R","expires_in":3599}""")
        assertEquals("A", t.acesso); assertEquals("R", t.renovacao); assertEquals(3599L, t.expiraEmS)
        assertNull(Nuvem.lerTokens("""{"access_token":"A"}""").renovacao)
        val erro = runCatching { Nuvem.lerTokens("""{"error":"invalid_grant"}""") }.exceptionOrNull()
        assertTrue(erro?.message == "NUVEM_invalid_grant")
    }

    @Test fun codigoDeTvDaMicrosoft() {
        val c = Nuvem.lerCodigoTv("""{"device_code":"D","user_code":"ABCD1234","verification_uri":"https://microsoft.com/devicelogin",
            "expires_in":900,"interval":5}""")
        assertEquals("ABCD1234", c.usuario); assertEquals("D", c.dispositivo); assertEquals(5, c.intervaloS)
        assertEquals("https://microsoft.com/devicelogin", c.pagina)
    }

    @Test fun contaDaNuvemSobreviveAoJson() {
        val c = ContaMidia("ab12", ContaMidia.ONEDRIVE, "OneDrive", "OneDrive", "", "Ana", "", "", "RT", "0a1b2c3d-4e5f-6789-abcd-ef0123456789")
        val volta = MidiaContas.deJson(MidiaContas.paraJson(listOf(c)))
        assertEquals(listOf(c), volta)
        assertTrue(Pastas.ehPasta(c))
    }

    @Test fun paginaDoBoxLevaRetornoEEstado() {
        val e = Nuvem.estadoRetorno("ab".repeat(16), "192.168.0.20", 8095)
        assertEquals("${"ab".repeat(16)}_192.168.0.20_8095", e)
        val u = Nuvem.paginaBox("cid123", e)
        assertTrue(u.startsWith("https://account.box.com/api/oauth2/authorize?client_id=cid123&response_type=code"))
        assertTrue(u.contains("redirect_uri=https%3A%2F%2Fchrnx0.github.io%2Fsofaly%2Fvolta.html"))
        assertTrue(u.endsWith("&state=$e"))
        assertTrue(Nuvem.credencialBoxValida("abcdefghijklmnopqrstuvwxyz012345"))
        assertFalse(Nuvem.credencialBoxValida("abc def"))
    }

    @Test fun listaDoBoxGuardaIdsEIgnoraLinks() {
        val (itens, total) = Nuvem.lerListaBox("""{"total_count":5,"entries":[{"type":"folder","id":"11","name":"Filmes"},
            {"type":"file","id":"22","name":"a.mkv","size":700},{"type":"web_link","id":"33","name":"site"}],"offset":0,"limit":1000}""")
        assertEquals(5, total)
        assertEquals(listOf("11", "22"), itens.map { it.id })
        assertTrue(itens[0].item.pasta); assertEquals(700L, itens[1].item.tamanho)
        assertTrue(Nuvem.boxItens("0", 1000).endsWith("/folders/0/items?fields=name,size,type&limit=1000&offset=1000"))
    }

    @Test fun driveLePastasEArquivosEIgnoraDocsDoGoogle() {
        val (itens, prox) = Nuvem.lerListaDrive("""{"nextPageToken":"P2","files":[
            {"id":"f1","name":"Filmes","mimeType":"application/vnd.google-apps.folder"},
            {"id":"f2","name":"a.mp4","mimeType":"video/mp4","size":"123456789012"},
            {"id":"f3","name":"Notas","mimeType":"application/vnd.google-apps.document"}]}""")
        assertEquals(listOf("f1", "f2"), itens.map { it.id })
        assertTrue(itens[0].item.pasta); assertEquals(123456789012L, itens[1].item.tamanho)
        assertEquals("P2", prox)
        assertNull(Nuvem.lerListaDrive("""{"files":[]}""").second)
    }

    @Test fun enderecosDoDrive() {
        val u = Nuvem.driveFilhos("root", null)
        assertTrue(u.startsWith("https://www.googleapis.com/drive/v3/files?q=%27root%27+in+parents+and+trashed%3Dfalse"))
        assertFalse(u.contains("pageToken"))
        assertTrue(Nuvem.driveFilhos("x'y", "T").contains("q=%27xy%27") )   // aspas no id não escapam da consulta
        assertTrue(Nuvem.driveFilhos("a", "T 1").endsWith("&pageToken=T+1"))
        assertEquals("https://www.googleapis.com/drive/v3/files/ab-C_1x?alt=media", Nuvem.driveConteudo("ab-C_1/../x"))   // barras e pontos saem
        val p = Nuvem.paginaDrive("123-abc.apps.googleusercontent.com", "DES", "EST")
        assertTrue(p.contains("scope=https%3A%2F%2Fwww.googleapis.com%2Fauth%2Fdrive.readonly"))
        assertTrue(p.contains("access_type=offline") && p.contains("code_challenge=DES&code_challenge_method=S256") && p.endsWith("&state=EST"))
        assertTrue(Nuvem.clienteGoogleValido("123456-abc123def.apps.googleusercontent.com"))
        assertFalse(Nuvem.clienteGoogleValido("123456-abc.apps.googleusercontent.com.evil"))
        assertTrue(Nuvem.segredoGoogleValido("GOCSPX-abcdEFGH1234_xyz"))
    }
}
