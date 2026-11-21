package br.paulo.tvlauncher.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import br.paulo.tvlauncher.Gravacoes
import java.io.IOException
import java.io.InputStream

/**
 * Entrega ao player o canal que está sendo gravado (14/11/2026): "gravando://ao-vivo" lê do [CanoAoVivo] da gravação
 * em andamento, no lugar de abrir o canal de novo (a lista só deixa 1 conexão). Fluxo sem fim, como o canal.
 */
@UnstableApi
class FonteGravacao : BaseDataSource(true) {
    companion object { const val URI = "gravando://ao-vivo" }

    private var leitor: InputStream? = null
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val s = Gravacoes.atual ?: throw IOException("nenhuma gravação em andamento")
        leitor = s.cano.leitor()
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return C.LENGTH_UNSET.toLong()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = (leitor ?: throw IOException("fechado")).read(buffer, offset, length)
        if (n < 0) return C.RESULT_END_OF_INPUT
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        val tinha = leitor != null
        runCatching { leitor?.close() }
        leitor = null; uri = null
        if (tinha) transferEnded()
    }
}
