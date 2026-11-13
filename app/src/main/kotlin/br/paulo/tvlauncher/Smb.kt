package br.paulo.tvlauncher

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
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
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/**
 * Pasta compartilhada (SMB 2/3: Windows, NAS, roteador com disco), etapa 2 das mídias da rede (07/11/2026).
 * Biblioteca smbj (Apache-2.0). SMB1 não: é antigo, inseguro e o Windows já vem com ele desligado.
 *
 * [listar] mostra uma pasta; [Fonte] entrega um arquivo ao player em pedaços (o ExoPlayer pede trechos com posição, e a
 * leitura SMB aceita posição: dá para avançar e voltar no vídeo sem baixar tudo).
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
    fun listar(c: ContaMidia, caminho: String): List<Upnp.Entrada> = abrir(c).use { a ->
        a.pasta.list(caminho.replace('/', '\\'))
            .filter { it.fileName != "." && it.fileName != ".." && !it.fileName.startsWith(".") }
            .filter { !EnumWithValue.EnumUtils.isSet(it.fileAttributes, FileAttributes.FILE_ATTRIBUTE_HIDDEN) }
            .map { info ->
                val nome = info.fileName
                val ehPasta = EnumWithValue.EnumUtils.isSet(info.fileAttributes, FileAttributes.FILE_ATTRIBUTE_DIRECTORY)
                val sub = MidiaContas.juntar(caminho, nome)
                if (ehPasta) Upnp.Entrada(sub, nome, true, null, Upnp.Tipo.PASTA, null)
                else Upnp.Entrada(sub, nome, false, MidiaContas.uriSmb(c, sub), MidiaContas.tipoPorNome(nome), null)
            }
            .sortedWith(compareBy({ !it.pasta }, { it.titulo.lowercase() }))
    }

    /** Lê um arquivo inteiro (fotos), até [max] bytes. */
    fun lerTudo(c: ContaMidia, caminho: String, max: Int = 30 * 1024 * 1024): ByteArray = abrir(c).use { a ->
        abrirArquivo(a, caminho).use { f ->
            val tamanho = f.fileInformation.standardInformation.endOfFile
            require(tamanho <= max) { "arquivo grande demais" }
            val buf = ByteArray(tamanho.toInt())
            var lido = 0
            while (lido < buf.size) {
                val n = f.read(buf, lido.toLong(), lido, buf.size - lido)
                if (n <= 0) break
                lido += n
            }
            buf.copyOf(lido)
        }
    }

    private fun abrirArquivo(a: Aberta, caminho: String): File = a.pasta.openFile(
        caminho.replace('/', '\\'), EnumSet.of(AccessMask.GENERIC_READ), null,
        SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null)

    /**
     * Fonte de dados do ExoPlayer para endereços smb://<id da conta>/<caminho> (ver MidiaContas.uriSmb). Usuário e senha
     * vêm do MidiaArmazem, não do endereço.
     */
    @UnstableApi
    class Fonte(private val ctx: Context) : BaseDataSource(true) {
        private var aberta: Aberta? = null
        private var arquivo: File? = null
        private var uri: Uri? = null
        private var posicao = 0L
        private var restante = 0L

        override fun open(dataSpec: DataSpec): Long {
            transferInitializing(dataSpec)
            val (id, caminho) = MidiaContas.lerUriSmb(dataSpec.uri.toString()) ?: throw IOException("endereço smb inválido")
            val conta = MidiaArmazem.conta(ctx, id) ?: throw IOException("conta de mídia não encontrada")
            val a = abrir(conta)
            val f = try { abrirArquivo(a, caminho) } catch (e: Exception) { a.close(); throw IOException(e) }
            aberta = a; arquivo = f; uri = dataSpec.uri
            val tamanho = f.fileInformation.standardInformation.endOfFile
            posicao = dataSpec.position
            if (posicao > tamanho) throw IOException("posição além do fim")
            restante = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else tamanho - posicao
            transferStarted(dataSpec)
            return restante
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            if (restante <= 0) return C.RESULT_END_OF_INPUT
            val f = arquivo ?: throw IOException("fechado")
            val n = f.read(buffer, posicao, offset, minOf(length.toLong(), restante).toInt())
            if (n <= 0) return C.RESULT_END_OF_INPUT
            posicao += n; restante -= n
            bytesTransferred(n)
            return n
        }

        override fun getUri(): Uri? = uri

        override fun close() {
            val tinha = aberta != null
            runCatching { arquivo?.close() }
            aberta?.close()
            arquivo = null; aberta = null; uri = null
            if (tinha) transferEnded()
        }
    }

    /**
     * Fábrica do player: smb:// vai para a [Fonte]; o resto (http, https) para a fábrica de sempre.
     */
    @UnstableApi
    class Fabrica(private val ctx: Context, private val outra: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Escolhe(ctx.applicationContext, outra.createDataSource())
    }

    @UnstableApi
    private class Escolhe(private val ctx: Context, private val outra: DataSource) : DataSource {
        private var atual: DataSource? = null
        override fun addTransferListener(t: androidx.media3.datasource.TransferListener) { outra.addTransferListener(t) }
        override fun open(dataSpec: DataSpec): Long {
            val d = if (dataSpec.uri.scheme == "smb") Fonte(ctx) else outra
            atual = d
            return d.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = atual?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = atual?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = atual?.responseHeaders ?: emptyMap()
        override fun close() { atual?.close(); atual = null }
    }
}
