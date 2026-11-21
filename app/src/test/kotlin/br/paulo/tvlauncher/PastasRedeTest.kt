package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.Nfs
import br.paulo.tvlauncher.panel.PastaArquivos
import br.paulo.tvlauncher.panel.Pastas
import br.paulo.tvlauncher.panel.Sftp
import br.paulo.tvlauncher.panel.Upnp
import br.paulo.tvlauncher.player.ContaMidia
import br.paulo.tvlauncher.player.FontesMidia
import br.paulo.tvlauncher.player.MidiaContas
import java.io.ByteArrayInputStream
import java.io.IOException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** FTP, FTPS, SFTP, NFS e pendrive em "Na rede" (14/11/2026): endereços, caminhos e o NFS por dentro. Sem rede. */
class PastasRedeTest {
    private fun end(t: String) = MidiaContas.lerEnderecoArquivos(t)

    @Test fun enderecosQueAPessoaDigita() {
        assertEquals(MidiaContas.EnderecoArquivos(ContaMidia.SFTP, "nas", "Filmes"), end("sftp://nas/Filmes/"))
        assertEquals(MidiaContas.EnderecoArquivos(ContaMidia.FTP, "192.168.0.10:2121", ""), end(" FTP://192.168.0.10:2121 "))
        assertEquals(MidiaContas.EnderecoArquivos(ContaMidia.FTPS, "nas", "Vídeos/2024"), end("ftps://nas//Vídeos/2024"))
        assertEquals(MidiaContas.EnderecoArquivos(ContaMidia.NFS, "nas", "volume1/video"), end("nfs://nas/volume1/video"))
        assertEquals(MidiaContas.EnderecoArquivos(ContaMidia.NFS, "192.168.1.5", "export/filmes"), end("192.168.1.5:/export/filmes"))
        assertNull(end("nas/Filmes"))                       // sem protocolo não dá para saber qual é
        assertNull(end("nfs://nas"))                        // NFS precisa do caminho exportado
        assertNull(end("ftp://ana:senha@nas/x"))            // senha nunca no endereço
        assertNull(end("sftp://nas/../etc"))
        assertNull(end("ftp://nas:99999"))
        assertNull(end("smb://nas/x"))
        assertNull(end("ftp://na s\tx/y"))
    }

    @Test fun caminhosNuncaSaemDaConta() {
        assertTrue(Pastas.caminhoValido(""))
        assertTrue(Pastas.caminhoValido("Filmes/Série A/ep1.mp4"))
        assertFalse(Pastas.caminhoValido("/etc"))
        assertFalse(Pastas.caminhoValido("Filmes/../../etc"))
        assertFalse(Pastas.caminhoValido("a\\b"))
        assertFalse(Pastas.caminhoValido("a\u0000b"))
    }

    @Test fun enderecoDoPlayerIdaEVolta() {
        val ftp = ContaMidia("ab12", ContaMidia.FTP, "x", "nas")
        val u = Pastas.uri(ftp, "Filmes/Série A/ep 1+2.mkv")
        assertEquals("arq://ab12/Filmes/S%C3%A9rie%20A/ep%201%2B2.mkv", u)
        assertEquals("ab12" to "Filmes/Série A/ep 1+2.mkv", Pastas.lerUri(u))
        // SMB continua smb:// (as posições salvas de "continuar" usam esse endereço).
        val smb = ContaMidia("s1", ContaMidia.SMB, "x", "nas", "Filmes")
        assertTrue(Pastas.uri(smb, "a.mkv").startsWith("smb://s1/"))
        assertEquals("s1" to "a.mkv", Pastas.lerUri(Pastas.uri(smb, "a.mkv")))
        assertNull(Pastas.lerUri("arq://ab12/x/%2E%2E/y"))
        assertNull(Pastas.lerUri("arq://../x"))
        assertNull(Pastas.lerUri("arq://ab12/"))
    }

    @Test fun pendriveSoDebaixoDeStorage() {
        assertTrue(Pastas.arquivoLocalPermitido("file:///storage/1234-ABCD/Filmes/a.mkv"))
        assertFalse(Pastas.arquivoLocalPermitido("file:///data/data/br.paulo.tvlauncher/files/player/midia.json"))
        assertFalse(Pastas.arquivoLocalPermitido("file:///storage/../data/x"))
        assertFalse(Pastas.arquivoLocalPermitido("file:///storage/%2E%2E/data/x"))
        assertTrue(FontesMidia.aceito("arq://ab12/a.mkv"))
        assertFalse(FontesMidia.aceito("file:///sdcard/../data/x"))
    }

    @Test fun listaComoATelaMostra() {
        val itens = listOf(PastaArquivos.Item("b.mkv", false, 10), PastaArquivos.Item(".DS_Store", false, 1),
            PastaArquivos.Item("Séries", true, 0), PastaArquivos.Item("A.jpg", false, 5), PastaArquivos.Item("..", true, 0),
            PastaArquivos.Item("x/y", false, 1))
        val e = Pastas.entradas(itens, "Filmes") { "arq://c/$it" }
        assertEquals(listOf("Séries", "A.jpg", "b.mkv"), e.map { it.titulo })
        assertEquals(listOf(Upnp.Tipo.PASTA, Upnp.Tipo.FOTO, Upnp.Tipo.VIDEO), e.map { it.tipo })
        assertEquals("Filmes/Séries", e[0].id); assertNull(e[0].url)
        assertEquals("arq://c/Filmes/b.mkv", e[2].url)
    }

    @Test fun contasNovasSobrevivemAoJson() {
        val c = ContaMidia("h1", ContaMidia.SFTP, "PC", "pc:2222", "home/ana", "ana", "s", token = "SHA256:abc")
        assertEquals(listOf(c), MidiaContas.deJson(MidiaContas.paraJson(listOf(c))))
        for (t in listOf(ContaMidia.FTP, ContaMidia.FTPS, ContaMidia.NFS)) assertTrue(Pastas.ehPasta(c.copy(tipo = t)))
    }

    // ------------------------------------------------------------ NFS por dentro

    @Test fun xdrEscreveELe() {
        val b = Nfs.Escrita().u32(7).u64(0x1_0000_0002L).texto("abcde").opaco(byteArrayOf(1, 2, 3, 4)).bytes()
        assertEquals(4 + 8 + (4 + 8) + (4 + 4), b.size)   // "abcde" ocupa 8 (completa até múltiplo de 4)
        val l = Nfs.Leitura(b)
        assertEquals(7, l.u32()); assertEquals(0x1_0000_0002L, l.u64()); assertEquals("abcde", l.texto())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), l.opaco()); assertEquals(0, l.resto)
        assertTrue(runCatching { Nfs.Leitura(Nfs.Escrita().u32(1000).bytes()).opaco() }.isFailure)   // diz 1000, não tem
    }

    @Test fun pedidoRpcComMarcaDeRegistro() {
        val p = Nfs.pedido(42, Nfs.NFS, 3, 6, 1026, 100, byteArrayOf(9, 9, 9, 9))
        val l = Nfs.Leitura(p)
        assertEquals(0x80000000.toInt() or (p.size - 4), l.u32())
        assertEquals(42, l.u32()); assertEquals(0, l.u32()); assertEquals(2, l.u32())
        assertEquals(Nfs.NFS, l.u32()); assertEquals(3, l.u32()); assertEquals(6, l.u32())
        assertEquals(1, l.u32())   // AUTH_SYS
        val cred = Nfs.Leitura(l.opaco())
        cred.u32(); assertEquals("onetv", cred.texto()); assertEquals(1026, cred.u32()); assertEquals(100, cred.u32())
        assertEquals(0, l.u32()); assertEquals(0, l.u32())
        assertEquals(0x09090909, l.u32())
        // Registro em dois pedaços (o 1º sem o bit de "último") volta inteiro.
        val dois = Nfs.Escrita().u32(4).u32(0x11).u32(0x80000000.toInt() or 4).u32(0x22).bytes()
        assertArrayEquals(Nfs.Escrita().u32(0x11).u32(0x22).bytes(), Nfs.lerRegistro(ByteArrayInputStream(dois)))
    }

    private fun cabecalho(xid: Int) = Nfs.Escrita().u32(xid).u32(1).u32(0).u32(0).u32(0).u32(0)

    @Test fun respostaRecusadaViraMotivoLegivel() {
        assertEquals(5, Nfs.resposta(cabecalho(9).u32(5).bytes(), 9).u32())
        val porta = Nfs.Escrita().u32(9).u32(1).u32(1).u32(1).u32(5).bytes()   // MSG_DENIED, AUTH_ERROR, TOOWEAK
        assertEquals("NFS_PORTA", runCatching { Nfs.resposta(porta, 9) }.exceptionOrNull()?.message)
        assertTrue(runCatching { Nfs.resposta(cabecalho(8).bytes(), 9) }.exceptionOrNull() is IOException)
        assertEquals("ACCESS_DENIED", runCatching { Nfs.conferir(13) }.exceptionOrNull()?.message)
        assertEquals("BAD_NETWORK_NAME", runCatching { Nfs.conferir(2) }.exceptionOrNull()?.message)
    }

    private fun fattr(tipo: Int, tam: Long) = Nfs.Escrita().u32(tipo).u32(0x1ed).u32(1).u32(0).u32(0).u64(tam).u64(tam)
        .u32(0).u32(0).u64(1).u64(99).u32(0).u32(0).u32(0).u32(0).u32(0).u32(0)

    @Test fun readdirplusComPastaEArquivo() {
        val w = Nfs.Escrita().u32(0).u32(0)   // NFS3_OK, sem atributos da pasta
            .u64(0x0102030405060708L)           // cookieverf
        w.u32(1).u64(11).texto("Séries").u64(100).u32(1).fixo(fattr(2, 4096).bytes()).u32(1).opaco(byteArrayOf(1, 2))
        w.u32(1).u64(12).texto("a.mkv").u64(200).u32(1).fixo(fattr(1, 123456789L).bytes()).u32(0)
        w.u32(0).u32(1)   // fim da lista, eof
        val p = Nfs.lerReaddirplus(Nfs.Leitura(w.bytes()))
        assertEquals(listOf("Séries", "a.mkv"), p.itens.map { it.nome })
        assertTrue(p.itens[0].atributos!!.pasta); assertArrayEquals(byteArrayOf(1, 2), p.itens[0].handle)
        assertEquals(123456789L, p.itens[1].atributos!!.tamanho); assertNull(p.itens[1].handle)
        assertEquals(200L, p.cookie); assertTrue(p.fim)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), p.verificador)
    }

    @Test fun usuarioNfs() {
        assertEquals(1000 to 1000, Nfs.usuario(""))
        assertEquals(1026 to 100, Nfs.usuario("1026:100"))
        assertEquals(1026 to 1026, Nfs.usuario(" 1026 "))
        assertEquals(1000 to 1000, Nfs.usuario("paulo"))
    }

    @Test fun sftpImpressaoComoOSsh() {
        assertEquals("SHA256:47DEQpj8HBSa+/TImW+5JCeuQeRkm5NMpJWZG3hSuFU", Sftp.impressao(ByteArray(0)))
    }
}
