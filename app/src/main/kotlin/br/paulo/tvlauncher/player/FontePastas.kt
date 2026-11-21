package br.paulo.tvlauncher.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.panel.PastaArquivos
import br.paulo.tvlauncher.panel.Pastas
import java.io.IOException
import java.io.InputStream

/**
 * Entrega ao player (Media3) os arquivos das pastas da rede: smb:// e arq:// (FTP, SFTP, NFS). 14/11/2026, no lugar
 * do Smb.Fonte: o mesmo jeito para todos os protocolos (ver PastaArquivos.kt).
 *
 * O player pede um trecho (posição + tamanho); avançar no vídeo fecha e abre de novo em outra posição. Usuário e senha
 * vêm do MidiaArmazem pelo ID da conta que está no endereço, nunca do endereço.
 */
@UnstableApi
class FontePastas(private val ctx: Context) : BaseDataSource(true) {
    private var pasta: PastaArquivos? = null
    private var leitor: InputStream? = null
    private var uri: Uri? = null
    private var restante = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val (id, caminho) = Pastas.lerUri(dataSpec.uri.toString()) ?: throw IOException("endereço inválido")
        val conta = MidiaArmazem.conta(ctx, id) ?: throw IOException("conta de mídia não encontrada")
        val p = Pastas.abrir(conta)
        try {
            val tamanho = p.tamanho(caminho)
            if (dataSpec.position > tamanho) throw IOException("posição além do fim")
            leitor = p.ler(caminho, dataSpec.position)
            restante = if (dataSpec.length != C.LENGTH_UNSET.toLong()) dataSpec.length else tamanho - dataSpec.position
        } catch (e: Exception) { runCatching { p.close() }; throw if (e is IOException) e else IOException(e) }
        pasta = p; uri = dataSpec.uri
        transferStarted(dataSpec)
        return restante
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (restante <= 0) return C.RESULT_END_OF_INPUT
        val e = leitor ?: throw IOException("fechado")
        val n = e.read(buffer, offset, minOf(length.toLong(), restante).toInt())
        if (n < 0) return C.RESULT_END_OF_INPUT
        restante -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val tinha = pasta != null
        runCatching { leitor?.close() }
        runCatching { pasta?.close() }
        leitor = null; pasta = null; uri = null
        if (tinha) transferEnded()
    }

    /** Fábrica do player: smb://, arq:// e gravando:// vêm para cá; o resto (http, https, file) vai para a fábrica de sempre. */
    class Fabrica(private val ctx: Context, private val outra: DataSource.Factory) : DataSource.Factory {
        override fun createDataSource(): DataSource = Escolhe(ctx.applicationContext, outra.createDataSource())
    }

    private class Escolhe(private val ctx: Context, private val outra: DataSource) : DataSource {
        private var atual: DataSource? = null
        override fun addTransferListener(t: TransferListener) { outra.addTransferListener(t) }
        override fun open(dataSpec: DataSpec): Long {
            val d = when (dataSpec.uri.scheme) {
                "smb", "arq" -> FontePastas(ctx)
                "gravando" -> FonteGravacao()   // o canal que está gravando, lido da gravação (GravacaoServico)
                else -> outra
            }
            atual = d
            return d.open(dataSpec)
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int) = atual?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT
        override fun getUri(): Uri? = atual?.uri
        override fun getResponseHeaders(): Map<String, List<String>> = atual?.responseHeaders ?: emptyMap()
        override fun close() { atual?.close(); atual = null }
    }
}
