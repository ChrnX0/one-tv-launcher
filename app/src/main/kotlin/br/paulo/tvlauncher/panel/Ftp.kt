package br.paulo.tvlauncher.panel

import br.paulo.tvlauncher.player.ContaMidia
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyStore
import java.time.Duration
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPFile
import org.apache.commons.net.ftp.FTPReply
import org.apache.commons.net.ftp.FTPSClient

/**
 * FTP e FTPS (14/11/2026), com a biblioteca Apache Commons Net (Apache-2.0).
 *
 *  - FTP comum manda a senha sem cifrar: serve dentro de casa (roteador com disco, NAS antigo). Fora de casa, FTPS ou SFTP.
 *  - FTPS: "explícito" (porta 21, o servidor passa para TLS com AUTH TLS) ou "implícito" (porta 990, TLS desde o começo).
 *    O certificado é conferido como em qualquer https: cadeia pelas autoridades do Android e o NOME do servidor. Nunca
 *    se aceita certificado inválido; NAS com certificado próprio (autoassinado) não entra: use SFTP ou FTP em casa.
 *  - Modo passivo sempre (o servidor abre a porta de dados; a TV atrás do roteador não precisa receber conexão).
 *  - Ler a partir de uma posição = comando REST antes do RETR. Cada leitura usa uma conexão própria (o FTP só transfere
 *    um arquivo por vez por conexão), e fechar o leitor fecha essa conexão.
 */
object Ftp {
    private const val TEMPO_MS = 15_000

    fun abrir(c: ContaMidia): PastaArquivos = Pasta(c)

    /** "nas:2121" -> ("nas", 2121); sem porta: 21, ou 990 no FTPS implícito não dá para adivinhar, então 21. */
    fun hostPorta(servidor: String): Pair<String, Int> =
        servidor.substringBefore(':') to (servidor.substringAfter(':', "").toIntOrNull() ?: 21)

    /** Caminho da conta ("Filmes/2024") para o do servidor ("/Filmes/2024"). */
    fun absoluto(caminho: String): String = "/" + caminho.trim('/')

    private fun conectar(c: ContaMidia): FTPClient {
        val (host, porta) = hostPorta(c.servidor)
        val cli = if (c.tipo == ContaMidia.FTPS) FTPSClient("TLS", porta == 990).apply {
            trustManager = confiancaDoSistema()
            // Nome do servidor: a checagem "HTTPS" do próprio TLS (Java e Android). No Android, também o verificador do
            // https do sistema, por garantia; no Java de PC esse verificador padrão recusa tudo (só serve de reserva).
            isEndpointCheckingEnabled = true
            if (System.getProperty("java.vm.name").orEmpty().contains("Dalvik", true)) hostnameVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        } else FTPClient()
        cli.controlEncoding = "UTF-8"
        cli.connectTimeout = TEMPO_MS
        cli.setDefaultTimeout(TEMPO_MS)
        cli.setDataTimeout(Duration.ofMillis(30_000))
        try {
            cli.connect(host, porta)
            if (!FTPReply.isPositiveCompletion(cli.replyCode)) throw IOException("ftp recusou: ${cli.replyCode}")
            cli.soTimeout = 30_000
            if (cli is FTPSClient) { cli.execPBSZ(0); cli.execPROT("P") }   // dados também cifrados, não só a senha
            val (u, s) = if (c.usuario.isEmpty()) "anonymous" to "anonymous@" else c.usuario to c.senha
            if (!cli.login(u, s)) throw SecurityException("LOGON_FAILURE")
            runCatching { cli.sendCommand("OPTS UTF8 ON") }
            cli.setFileType(FTP.BINARY_FILE_TYPE)
            cli.enterLocalPassiveMode()
            return cli
        } catch (e: Exception) {
            runCatching { cli.disconnect() }
            throw e
        }
    }

    /** As autoridades de certificado do Android (as mesmas do https). O padrão da biblioteca só olhava a data. */
    private fun confiancaDoSistema(): X509TrustManager {
        val f = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        f.init(null as KeyStore?)
        return f.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private class Pasta(private val c: ContaMidia) : PastaArquivos, PastaGravavel {
        private val cli = conectar(c)

        override fun criarPastas(caminho: String) {
            var atual = ""
            for (parte in caminho.split('/').filter { it.isNotEmpty() }) {
                atual = "$atual/$parte"
                // MKD numa pasta que já existe responde 550: não é erro; quem falha de verdade é o envio logo depois.
                cli.makeDirectory(atual)
            }
        }

        override fun escrever(caminho: String): java.io.OutputStream {
            // Conexão própria, como a leitura: o FTP só transfere um arquivo por vez em cada conexão.
            val outra = conectar(c)
            try {
                val s = outra.storeFileStream(absoluto(caminho)) ?: throw IOException("ftp não criou o arquivo: ${outra.replyCode}")
                return object : java.io.FilterOutputStream(s.buffered(256 * 1024)) {
                    override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                    override fun close() {
                        try {
                            super.close()
                            if (!outra.completePendingCommand()) throw IOException("ftp não confirmou o envio: ${outra.replyCode}")
                        } finally { runCatching { outra.disconnect() } }
                    }
                }
            } catch (e: Exception) {
                runCatching { outra.disconnect() }
                throw e
            }
        }

        override fun apagar(caminho: String) {
            if (!cli.deleteFile(absoluto(caminho))) throw IOException("ftp não apagou: ${cli.replyCode}")
        }

        override fun listar(caminho: String): List<PastaArquivos.Item> {
            val dir = absoluto(caminho)
            // MLSD é o jeito moderno (formato fixo); servidor antigo só tem LIST (texto estilo "ls -l", que a biblioteca lê).
            val lista: Array<FTPFile> = runCatching { cli.mlistDir(dir) }.getOrNull()?.takeIf { FTPReply.isPositiveCompletion(cli.replyCode) }
                ?: cli.listFiles(dir).also { if (!FTPReply.isPositiveCompletion(cli.replyCode)) throw IOException("BAD_NETWORK_NAME") }
            return lista.filterNotNull().map { PastaArquivos.Item(it.name.substringAfterLast('/'), it.isDirectory, it.size) }
        }

        override fun tamanho(caminho: String): Long {
            val r = cli.getSize(absoluto(caminho))?.trim()?.toLongOrNull()
            if (r != null) return r
            // Sem o comando SIZE: procura na lista da pasta.
            val nome = caminho.substringAfterLast('/')
            return listar(caminho.substringBeforeLast('/', "")).firstOrNull { it.nome == nome }?.tamanho ?: throw IOException("arquivo não encontrado")
        }

        override fun ler(caminho: String, posicao: Long): InputStream {
            // Conexão própria: a desta pasta continua livre para listar enquanto o vídeo toca.
            val outra = conectar(c)
            try {
                outra.restartOffset = posicao
                val e = outra.retrieveFileStream(absoluto(caminho)) ?: throw IOException("ftp não abriu o arquivo: ${outra.replyCode}")
                return object : FilterInputStream(e) {
                    override fun close() {
                        // Fechar no meio da transferência: o servidor responde "426 abortado". Não importa, a conexão sai.
                        runCatching { super.close() }
                        runCatching { outra.disconnect() }
                    }
                }
            } catch (e: Exception) {
                runCatching { outra.disconnect() }
                throw e
            }
        }

        override fun close() {
            runCatching { cli.logout() }
            runCatching { cli.disconnect() }
        }
    }
}
