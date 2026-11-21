package br.paulo.tvlauncher.panel

import br.paulo.tvlauncher.player.ContaMidia
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpException
import com.jcraft.jsch.UserInfo
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.util.Base64

/**
 * SFTP (14/11/2026): pastas por SSH (PC com Linux/Mac, Windows com OpenSSH, NAS Synology/QNAP/TrueNAS com SSH ligado).
 * Biblioteca JSch (fork mwiede, licença BSD), sem dependências. Tudo cifrado, inclusive a senha: bom até fora de casa.
 *
 * Chave do servidor: o SSH não tem "autoridade de certificado"; quem garante que o servidor é o mesmo é a impressão da
 * chave dele. Na 1ª conexão (ao adicionar a conta) a impressão SHA-256 é guardada em ContaMidia.token; dali em diante,
 * chave diferente = recusa (alguém no meio, ou o servidor foi reinstalado: aí a pessoa remove e adiciona de novo).
 */
object Sftp {
    private const val TEMPO_MS = 15_000

    fun abrir(c: ContaMidia): PastaArquivos = Pasta(c)

    fun hostPorta(servidor: String): Pair<String, Int> =
        servidor.substringBefore(':') to (servidor.substringAfter(':', "").toIntOrNull() ?: 22)

    /** "SHA256:..." como o ssh mostra (base64 sem "="). */
    fun impressao(chave: ByteArray): String =
        "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(chave))

    /**
     * Primeira conexão: abre, aceita a chave e devolve a conta com a impressão no token e, sem pasta, a pasta inicial
     * do usuário no servidor (ex. /home/paulo). É o que o "Testar e salvar" grava.
     */
    fun conhecer(c: ContaMidia): ContaMidia {
        val p = Pasta(c.copy(token = ""))
        p.use {
            val inicio = c.pasta.ifEmpty { it.canal.home.trim('/') }
            it.listar(inicio)   // confere que a pasta existe e dá para ler
            return c.copy(token = it.vista ?: throw IOException("sem chave do servidor"), pasta = inicio)
        }
    }

    /** Confere a chave do servidor contra a guardada. Sem guardada (1ª vez), aceita e anota em [vista]. */
    private class Chaves(private val esperada: String) : HostKeyRepository {
        var vista: String? = null
        override fun check(host: String?, key: ByteArray): Int {
            val f = impressao(key)
            vista = f
            return when {
                esperada.isEmpty() -> HostKeyRepository.OK
                esperada == f -> HostKeyRepository.OK
                else -> HostKeyRepository.CHANGED
            }
        }
        override fun add(hostkey: HostKey?, ui: UserInfo?) {}
        override fun remove(host: String?, type: String?) {}
        override fun remove(host: String?, type: String?, key: ByteArray?) {}
        override fun getKnownHostsRepositoryID() = "onetv"
        override fun getHostKey(): Array<HostKey> = emptyArray()
        override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
    }

    private fun sessao(c: ContaMidia, chaves: Chaves): Session {
        val (host, porta) = hostPorta(c.servidor)
        val j = JSch()
        j.hostKeyRepository = chaves
        val s = j.getSession(c.usuario, host, porta)
        s.setPassword(c.senha)
        // "yes": chave diferente da guardada = erro (nunca "no", que aceitaria qualquer uma).
        s.setConfig("StrictHostKeyChecking", "yes")
        s.setConfig("PreferredAuthentications", "password,keyboard-interactive")
        s.timeout = TEMPO_MS
        try {
            s.connect(TEMPO_MS)
        } catch (e: com.jcraft.jsch.JSchException) {
            val m = e.message.orEmpty()
            throw when {
                m.contains("HostKey has been changed", true) || m.contains("reject HostKey", true) -> IOException("CHAVE_MUDOU", e)
                m.contains("Auth fail", true) || m.contains("Auth cancel", true) -> SecurityException("LOGON_FAILURE")
                else -> IOException(e)
            }
        }
        return s
    }

    private class Pasta(private val c: ContaMidia) : PastaArquivos, PastaGravavel {
        private val chaves = Chaves(c.token)
        private val s = sessao(c, chaves)
        val canal: ChannelSftp = try {
            (s.openChannel("sftp") as ChannelSftp).also { it.connect(TEMPO_MS) }
        } catch (e: Exception) { s.disconnect(); throw IOException(e) }
        val vista get() = chaves.vista

        private fun absoluto(caminho: String) = "/" + caminho.trim('/')

        override fun listar(caminho: String): List<PastaArquivos.Item> = traduz {
            canal.ls(absoluto(caminho)).filterIsInstance<ChannelSftp.LsEntry>().map {
                PastaArquivos.Item(it.filename, it.attrs.isDir, it.attrs.size)
            }
        }

        override fun tamanho(caminho: String): Long = traduz { canal.stat(absoluto(caminho)).size }

        override fun ler(caminho: String, posicao: Long): InputStream = traduz {
            // Canal próprio por leitura (o mesmo SSH): listar e tocar ao mesmo tempo não se atrapalham.
            val outro = s.openChannel("sftp") as ChannelSftp
            outro.connect(TEMPO_MS)
            val e = try { outro.get(absoluto(caminho), null, posicao) } catch (x: Exception) { outro.disconnect(); throw x }
            object : java.io.FilterInputStream(e) {
                override fun close() { runCatching { super.close() }; runCatching { outro.disconnect() } }
            }
        }

        override fun criarPastas(caminho: String) {
            var atual = ""
            for (parte in caminho.split('/').filter { it.isNotEmpty() }) {
                atual = "$atual/$parte"
                val existe = runCatching { canal.stat(atual).isDir }.getOrDefault(false)
                if (!existe) traduz { canal.mkdir(atual) }
            }
        }

        override fun escrever(caminho: String): java.io.OutputStream = traduz {
            // Canal próprio (o mesmo SSH), como a leitura: listar ou tocar enquanto grava não atrapalha.
            val outro = s.openChannel("sftp") as ChannelSftp
            outro.connect(TEMPO_MS)
            val e = try { outro.put(absoluto(caminho), ChannelSftp.OVERWRITE) } catch (x: Exception) { outro.disconnect(); throw x }
            object : java.io.FilterOutputStream(e.buffered(256 * 1024)) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() { try { super.close() } finally { runCatching { outro.disconnect() } } }
            }
        }

        override fun apagar(caminho: String) = traduz { canal.rm(absoluto(caminho)) }

        /** statvfs (extensão do OpenSSH); servidor sem ela: "não sei" ([Long.MAX_VALUE]), e a gravação segue. */
        override fun livre(caminho: String): Long = runCatching {
            canal.statVFS(absoluto(caminho)).availForNonRoot * 1024
        }.getOrNull()?.takeIf { it > 0 } ?: Long.MAX_VALUE

        private fun <T> traduz(f: () -> T): T = try { f() } catch (e: SftpException) {
            throw when (e.id) {
                ChannelSftp.SSH_FX_NO_SUCH_FILE -> IOException("BAD_NETWORK_NAME")
                ChannelSftp.SSH_FX_PERMISSION_DENIED -> SecurityException("ACCESS_DENIED")
                else -> IOException(e)
            }
        }

        override fun close() {
            runCatching { canal.disconnect() }
            runCatching { s.disconnect() }
        }
    }
}
