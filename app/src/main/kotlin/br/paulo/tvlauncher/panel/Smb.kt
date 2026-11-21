package br.paulo.tvlauncher.panel

import br.paulo.tvlauncher.player.ContaMidia
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.protocol.commons.EnumWithValue
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.hierynomus.smbj.share.File
import java.io.IOException
import java.io.InputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Pasta compartilhada (SMB 2/3: Windows, NAS, roteador com disco), etapa 2 das mídias da rede (07/11/2026).
 * Biblioteca smbj (Apache-2.0). SMB1 não: é antigo, inseguro e o Windows já vem com ele desligado.
 *
 * [Pasta] lista e lê com posição (o molde comum de PastaArquivos.kt); quem entrega ao player é a FontePastas.
 */
object Smb {
    private fun config() = SmbConfig.builder()
        .withTimeout(15, TimeUnit.SECONDS)
        .withSoTimeout(30, TimeUnit.SECONDS)
        .build()

    /** Uma conexão aberta com a pasta. Fechar fecha tudo (pasta, sessão, conexão e cliente). */
    class Aberta(private val cliente: SMBClient, private val conexao: Connection, private val sessao: Session, val pasta: DiskShare) : AutoCloseable {
        override fun close() {
            runCatching { pasta.close() }; runCatching { sessao.close() }; runCatching { conexao.close() }; runCatching { cliente.close() }
        }
    }

    fun abrir(c: ContaMidia): Aberta {
        val cliente = SMBClient(config())
        try {
            val host = c.servidor.substringBefore(':')
            val porta = c.servidor.substringAfter(':', "").toIntOrNull()
            val conexao = if (porta != null) cliente.connect(host, porta) else cliente.connect(host)
            val auth = if (c.usuario.isEmpty()) AuthenticationContext.guest()
                else AuthenticationContext(c.usuario, c.senha.toCharArray(), c.dominio.ifEmpty { null })
            val sessao = conexao.authenticate(auth)
            // c.pasta = "Compartilhamento" ou "Compartilhamento/sub/pasta" (a subpasta é só o ponto de partida, ver [inicio]).
            val pasta = sessao.connectShare(c.pasta.substringBefore('/')) as? DiskShare ?: throw IOException("não é uma pasta de arquivos")
            return Aberta(cliente, conexao, sessao, pasta)
        } catch (e: Exception) {
            runCatching { cliente.close() }
            throw e
        }
    }

    /** Onde a conta começa dentro do compartilhamento ("" = a raiz). */
    fun inicio(c: ContaMidia): String = c.pasta.substringAfter('/', "")

    /** Conteúdo de [caminho] ("" = a raiz do compartilhamento): pastas primeiro, depois arquivos, por nome. */
    fun listar(c: ContaMidia, caminho: String): List<Upnp.Entrada> = Pastas.listar(c, caminho)

    /** Lê um arquivo inteiro (fotos), até [max] bytes. */
    fun lerTudo(c: ContaMidia, caminho: String, max: Int = 30 * 1024 * 1024): ByteArray = Pastas.lerTudo(c, caminho, max)

    /** A pasta compartilhada no molde comum (PastaArquivos.kt): listar e ler com posição. */
    class Pasta(private val a: Aberta) : PastaArquivos, PastaGravavel {
        private fun win(caminho: String) = caminho.trim('/').replace('/', '\\')

        override fun criarPastas(caminho: String) {
            var c = ""
            for (parte in caminho.split('/').filter { it.isNotEmpty() }) {
                c = if (c.isEmpty()) parte else "$c/$parte"
                if (!a.pasta.folderExists(win(c))) a.pasta.mkdir(win(c))
            }
        }

        override fun escrever(caminho: String): java.io.OutputStream {
            val f = a.pasta.openFile(win(caminho), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
            return object : java.io.FilterOutputStream(f.outputStream.buffered(256 * 1024)) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() { runCatching { super.close() }; runCatching { f.close() } }
            }
        }

        override fun apagar(caminho: String) = a.pasta.rm(win(caminho))

        override fun livre(caminho: String): Long = runCatching { a.pasta.shareInformation.freeSpace }.getOrDefault(Long.MAX_VALUE)

        override fun listar(caminho: String): List<PastaArquivos.Item> = a.pasta.list(caminho.replace('/', '\\'))
            .filter { !EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN) }
            .map { PastaArquivos.Item(it.fileName, EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY), it.endOfFile) }

        override fun tamanho(caminho: String): Long = abrirArquivo(a, caminho).use { it.fileInformation.standardInformation.endOfFile }

        override fun ler(caminho: String, posicao: Long): InputStream {
            val f = abrirArquivo(a, caminho)
            return object : InputStream() {
                var pos = posicao
                override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    val n = f.read(b, pos, off, len)
                    if (n <= 0) return -1
                    pos += n; return n
                }
                override fun close() { runCatching { f.close() } }
            }
        }

        override fun close() = a.close()
    }

    private fun abrirArquivo(a: Aberta, caminho: String): File = a.pasta.openFile(
        caminho.replace('/', '\\'), EnumSet.of(AccessMask.GENERIC_READ), null,
        SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)
}
