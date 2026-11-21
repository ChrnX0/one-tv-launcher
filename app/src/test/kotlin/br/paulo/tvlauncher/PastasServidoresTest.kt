package br.paulo.tvlauncher

import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.panel.PastaGravavel
import br.paulo.tvlauncher.panel.Pastas
import br.paulo.tvlauncher.panel.Sftp
import br.paulo.tvlauncher.player.ContaMidia
import br.paulo.tvlauncher.player.MidiaContas
import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * FTP, FTPS, SFTP e NFS contra servidores DE VERDADE (14/11/2026). Só roda com ONETV_SERVIDORES=1 e os servidores de
 * teste ligados na própria máquina (pyftpdlib nas portas 2121/2122, sshd na 2222, nfs-ganesha com /srv/dados e /srv/tls);
 * no CI e no PC comum ele é pulado. Para subir os servidores: scripts/servidores-teste.sh.
 */
class PastasServidoresTest {
    private val dados = File("/srv/dados/Filmes/vídeo 1.mkv")

    @Before fun so() = assumeTrue(System.getenv("ONETV_SERVIDORES") == "1")

    private fun conferir(c: ContaMidia, base: String) {
        val nomes = Pastas.listar(c, MidiaContas.juntar(base, "Filmes")).map { it.titulo }
        assertEquals(listOf("Série A", "capa.jpg", "vídeo 1.mkv"), nomes)   // pastas primeiro, ocultos fora
        Pastas.abrir(c).use { p ->
            val cam = MidiaContas.juntar(base, "Filmes/vídeo 1.mkv")
            assertEquals(dados.length(), p.tamanho(cam))
            val esperado = dados.readBytes()
            // Ler do meio (o que o player faz ao avançar) e do começo.
            for (pos in listOf(123_457L, 0L)) {
                val lido = p.ler(cam, pos).use { it.readBytes() }
                assertArrayEquals(esperado.copyOfRange(pos.toInt(), esperado.size), lido)
            }
        }
        assertEquals("foto\n", String(Pastas.lerTudo(c, MidiaContas.juntar(base, "Filmes/capa.jpg"))))
    }

    @Test fun ftpAnonimoEComSenha() {
        conferir(ContaMidia("f1", ContaMidia.FTP, "x", "127.0.0.1:2121"), "")
        conferir(ContaMidia("f2", ContaMidia.FTP, "x", "127.0.0.1:2121", usuario = "teste", senha = "Senh4!"), "")
        val r = runCatching { Pastas.listar(ContaMidia("f3", ContaMidia.FTP, "x", "127.0.0.1:2121", usuario = "teste", senha = "errada"), "") }
        assertTrue(r.exceptionOrNull() is SecurityException)
    }

    @Test fun ftpsConfereCertificadoENome() {
        conferir(ContaMidia("s1", ContaMidia.FTPS, "x", "localhost:2122", usuario = "teste", senha = "Senh4!"), "")
        // O certificado é de "localhost": pelo IP, o nome não bate e a conexão é recusada (nada de aceitar qualquer um).
        val r = runCatching { Pastas.listar(ContaMidia("s2", ContaMidia.FTPS, "x", "127.0.0.1:2122", usuario = "teste", senha = "Senh4!"), "") }
        assertTrue(r.isFailure)
    }

    @Test fun sftpGuardaAChaveERecusaOutra() {
        val c = Sftp.conhecer(ContaMidia("h1", ContaMidia.SFTP, "x", "127.0.0.1:2222", usuario = "teste", senha = "Senh4!"))
        assertTrue(c.token.startsWith("SHA256:"))
        assertEquals("home/teste", c.pasta)
        val raiz = c.copy(pasta = "")
        conferir(raiz, "srv/dados")
        val outra = raiz.copy(token = "SHA256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
        val r = runCatching { Pastas.listar(outra, "srv") }
        assertEquals("CHAVE_MUDOU", r.exceptionOrNull()?.message)
        val senha = runCatching { Pastas.listar(raiz.copy(senha = "errada"), "") }
        assertTrue(senha.exceptionOrNull() is SecurityException)
    }

    /** 20/11/2026: a gravação da TV ao vivo em FTP, FTPS e SFTP (cria a pasta, grava, lê de volta, apaga). */
    private fun gravarELer(c: ContaMidia, base: String) {
        val bytes = java.util.Random(11).let { r -> ByteArray(1_500_000).also { r.nextBytes(it) } }
        val pasta = MidiaContas.juntar(base, "OneTV-gravacoes/sub")
        val cam = MidiaContas.juntar(pasta, "2026-11-20 Canal.ts")
        Pastas.abrir(c).use { p ->
            val g = p as PastaGravavel
            g.criarPastas(pasta)
            g.criarPastas(pasta)   // de novo: já existir não é erro
            g.escrever(cam).use { o -> bytes.toList().chunked(64 * 1024).forEach { o.write(it.toByteArray()) } }
            assertEquals(bytes.size.toLong(), p.tamanho(cam))
            assertArrayEquals(bytes, p.ler(cam, 0).use { it.readBytes() })
            assertTrue(g.livre(pasta) > 0)
            g.apagar(cam)
            assertTrue(p.listar(pasta).none { it.nome == "2026-11-20 Canal.ts" })
        }
    }

    @Test fun gravaEmFtpFtpsESftp() {
        gravarELer(ContaMidia("g1", ContaMidia.FTP, "x", "127.0.0.1:2121", usuario = "grava", senha = "Senh4!"), "")
        gravarELer(ContaMidia("g2", ContaMidia.FTPS, "x", "localhost:2122", usuario = "grava", senha = "Senh4!"), "")
        val c = Sftp.conhecer(ContaMidia("g3", ContaMidia.SFTP, "x", "127.0.0.1:2222", usuario = "teste", senha = "Senh4!"))
        gravarELer(c, c.pasta)
    }

    @Test fun nfsLeEExplicaAPortaAlta() {
        conferir(ContaMidia("n1", ContaMidia.NFS, "x", "127.0.0.1", pasta = "srv/dados"), "")
        // Export com PrivilegedPort=true (o padrão de quase todo NAS): app não usa porta baixa, o servidor recusa.
        val r = runCatching { Pastas.listar(ContaMidia("n2", ContaMidia.NFS, "x", "127.0.0.1", pasta = "srv/tls"), "") }
        assertTrue(r.exceptionOrNull().toString(), r.exceptionOrNull()?.message in setOf("NFS_PORTA", "NFS_IP"))
        val nao = runCatching { Pastas.listar(ContaMidia("n3", ContaMidia.NFS, "x", "127.0.0.1", pasta = "nao/existe"), "") }
        assertTrue(nao.isFailure)
    }
}
