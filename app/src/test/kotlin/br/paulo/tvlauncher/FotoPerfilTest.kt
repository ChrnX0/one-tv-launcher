package br.paulo.tvlauncher

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Partes puras da foto do perfil (FotoPerfil): de onde aceitamos baixar, nome do arquivo e tipo da imagem. */
class FotoPerfilTest {
    @Test fun soBaixaDoGoogleEPorHttps() {
        assertTrue(FotoPerfil.urlDoGoogle("https://lh3.googleusercontent.com/a/ABC=s96-c"))
        assertFalse("http puro", FotoPerfil.urlDoGoogle("http://lh3.googleusercontent.com/a/ABC"))
        assertFalse("outro site", FotoPerfil.urlDoGoogle("https://exemplo.com/a.jpg"))
        assertFalse("nome parecido", FotoPerfil.urlDoGoogle("https://googleusercontent.com.exemplo.com/a.jpg"))
        assertFalse("sufixo colado", FotoPerfil.urlDoGoogle("https://malgoogleusercontent.com/a.jpg"))
        assertFalse("usuário no endereço", FotoPerfil.urlDoGoogle("https://lh3.googleusercontent.com@192.168.0.1/a"))
        assertFalse("porta estranha", FotoPerfil.urlDoGoogle("https://lh3.googleusercontent.com:8080/a"))
        assertFalse("rede de casa", FotoPerfil.urlDoGoogle("https://192.168.0.1/a.jpg"))
        assertFalse("arquivo local", FotoPerfil.urlDoGoogle("file:///sdcard/a.jpg"))
        assertFalse(FotoPerfil.urlDoGoogle(""))
        assertFalse(FotoPerfil.urlDoGoogle("isto não é endereço"))
    }

    @Test fun pedeAFotoNoTamanhoDaBolinha() {
        assertEquals("https://lh3.googleusercontent.com/a/ABC=s256-c", FotoPerfil.urlNoTamanho("https://lh3.googleusercontent.com/a/ABC=s96-c"))
        assertEquals("https://lh3.googleusercontent.com/a/ABC=s256-c", FotoPerfil.urlNoTamanho("https://lh3.googleusercontent.com/a/ABC=s96"))
        assertEquals("https://lh3.googleusercontent.com/a/ABC=s256-c", FotoPerfil.urlNoTamanho("https://lh3.googleusercontent.com/a/ABC"))
        // Formato desconhecido: não inventa, devolve igual.
        assertEquals("https://lh3.googleusercontent.com/a/ABC?x=1", FotoPerfil.urlNoTamanho("https://lh3.googleusercontent.com/a/ABC?x=1"))
        // O endereço continua sendo do Google depois da troca.
        assertTrue(FotoPerfil.urlDoGoogle(FotoPerfil.urlNoTamanho("https://lh3.googleusercontent.com/a/ABC=s96-c")))
    }

    @Test fun nomeDoArquivoSaiDaContaENuncaViraCaminho() {
        assertEquals("perfil-fulano.silva.jpg", FotoPerfil.nomeArquivo("Fulano.Silva@gmail.com"))
        assertEquals("perfil-a+b.jpg", FotoPerfil.nomeArquivo("a+b@exemplo.com"))
        assertNull(FotoPerfil.nomeArquivo("../../etc/passwd@x.com"))
        assertNull(FotoPerfil.nomeArquivo("a/b@x.com"))
        assertNull(FotoPerfil.nomeArquivo(".oculto@x.com"))
        assertNull(FotoPerfil.nomeArquivo("@x.com"))
        assertNull("sem @ não é conta", FotoPerfil.nomeArquivo("fulano"))
        assertNull(FotoPerfil.nomeArquivo("a".repeat(65) + "@x.com"))
        assertNull(FotoPerfil.nomeArquivo("ação@x.com"))
    }

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun reconheceImagemPelosPrimeirosBytes() {
        assertTrue("jpeg", FotoPerfil.pareceImagem(bytes(0xFF, 0xD8, 0xFF, 0xE0)))
        assertTrue("png", FotoPerfil.pareceImagem(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A)))
        assertTrue("webp", FotoPerfil.pareceImagem("RIFF".toByteArray() + bytes(1, 2, 3, 4) + "WEBP".toByteArray()))
        assertFalse("html", FotoPerfil.pareceImagem("<html><script>".toByteArray()))
        assertFalse("svg", FotoPerfil.pareceImagem("<svg xmlns=".toByteArray()))
        assertFalse("riff que não é webp", FotoPerfil.pareceImagem("RIFF".toByteArray() + bytes(1, 2, 3, 4) + "WAVE".toByteArray()))
        assertFalse("curto demais", FotoPerfil.pareceImagem(bytes(0xFF, 0xD8)))
        assertFalse(FotoPerfil.pareceImagem(ByteArray(0)))
    }

    @Test fun reduzFotoGrandeSemPassarDoPonto() {
        assertEquals(1, FotoPerfil.amostra(256, 256))
        assertEquals(1, FotoPerfil.amostra(1000, 800))     // 800/2 = 400 < 512: não reduz
        assertEquals(2, FotoPerfil.amostra(1200, 1024))    // 1024/2 = 512: ainda sobra o dobro do alvo
        assertEquals(4, FotoPerfil.amostra(4000, 6000))    // foto de celular: 4000/4 = 1000; mais uma vez daria 500 < 512
        // Depois de reduzir, o menor lado nunca fica abaixo do alvo.
        for ((l, a) in listOf(4000 to 3000, 12000 to 9000, 513 to 5000, 300 to 300)) {
            val n = FotoPerfil.amostra(l, a)
            assertTrue("$l x $a / $n", minOf(l, a) / n >= minOf(minOf(l, a), FotoPerfil.LADO))
        }
    }

    /** A sessão "só foto" é marcada como tal (o LoginGoogle a deixa fora da lista que o conector consulta). */
    @Test fun sessaoSoFotoNaoEDeAcesso() {
        val comum = LoginGoogle.Sessao("a", emptyMap(), "ABCD", "https://www.google.com/device", 1L)
        val foto = LoginGoogle.Sessao("b", emptyMap(), "ABCD", "https://www.google.com/device", 1L, LoginGoogle.Finalidade.FOTO)
        assertFalse(comum.soFoto); assertTrue(foto.soFoto); assertFalse(foto.fotoGravada)
    }
}
