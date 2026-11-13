package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MidiaContasTest {
    @Test fun enderecoSmbDeVariosJeitos() {
        assertEquals(Triple("NAS", "Filmes", ""), MidiaContas.lerEnderecoSmb("\\\\NAS\\Filmes"))
        assertEquals(Triple("192.168.0.5", "Filmes", "Séries/2024"), MidiaContas.lerEnderecoSmb("//192.168.0.5/Filmes/Séries/2024/"))
        assertEquals(Triple("nas", "filmes", ""), MidiaContas.lerEnderecoSmb("smb://nas/filmes"))
        assertEquals(Triple("nas", "filmes", ""), MidiaContas.lerEnderecoSmb("  nas/filmes "))
        assertNull(MidiaContas.lerEnderecoSmb("\\\\NAS"))
        assertNull(MidiaContas.lerEnderecoSmb("na s/filmes"))
    }

    @Test fun enderecoJellyfinGanhaEsquemaEPorta() {
        assertEquals("http://192.168.0.5:8096", MidiaContas.lerEnderecoJellyfin("192.168.0.5"))
        assertEquals("http://nas:8920", MidiaContas.lerEnderecoJellyfin("nas:8920/"))
        assertEquals("https://jf.exemplo.com", MidiaContas.lerEnderecoJellyfin("https://jf.exemplo.com"))
        assertNull(MidiaContas.lerEnderecoJellyfin("a b"))
    }

    @Test fun jsonIdaEVolta() {
        val c = listOf(ContaMidia("a1", ContaMidia.SMB, "Filmes", "nas", "Filmes/HD", "eu", "s3nh@", "CASA"),
            ContaMidia("b2", ContaMidia.JELLYFIN, "Jelly", "http://nas:8096", token = "t", usuarioId = "u"))
        assertEquals(c, MidiaContas.deJson(MidiaContas.paraJson(c)))
        assertEquals(emptyList<ContaMidia>(), MidiaContas.deJson("""[{"id":"x","tipo":"ftp","servidor":"a"}]"""))
        assertEquals(emptyList<ContaMidia>(), MidiaContas.deJson("lixo"))
    }

    @Test fun uriSmbLevaSoOIdENaoAceitaSubirPasta() {
        val c = ContaMidia("a1", ContaMidia.SMB, "F", "nas", "Filmes", senha = "segredo")
        val u = MidiaContas.uriSmb(c, "Séries/Ep 1 #1.mkv")
        assertEquals(false, u.contains("segredo"))
        assertEquals("a1" to "Séries/Ep 1 #1.mkv", MidiaContas.lerUriSmb(u))
        assertNull(MidiaContas.lerUriSmb("smb://a1/../segredos.txt"))
        assertNull(MidiaContas.lerUriSmb("http://a1/x"))
    }

    @Test fun tipoPeloNome() {
        assertEquals(Upnp.Tipo.VIDEO, MidiaContas.tipoPorNome("Filme.MKV"))
        assertEquals(Upnp.Tipo.AUDIO, MidiaContas.tipoPorNome("a.flac"))
        assertEquals(Upnp.Tipo.FOTO, MidiaContas.tipoPorNome("f.jpeg"))
        assertEquals(Upnp.Tipo.OUTRO, MidiaContas.tipoPorNome("leia-me"))
        assertEquals("a/b", MidiaContas.juntar("/a/", "/b"))
        assertEquals("b", MidiaContas.juntar("", "b"))
    }
}
