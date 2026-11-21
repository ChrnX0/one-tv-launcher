package br.paulo.tvlauncher

import android.content.Context
import br.paulo.tvlauncher.panel.PastaGravavel
import br.paulo.tvlauncher.panel.Pastas
import br.paulo.tvlauncher.player.ContaMidia
import br.paulo.tvlauncher.player.MidiaArmazem
import br.paulo.tvlauncher.player.MidiaContas
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsContract.Root
import android.provider.DocumentsProvider
import android.system.ErrnoException
import android.system.OsConstants
import android.util.Log
import android.webkit.MimeTypeMap
import java.io.File
import java.io.FileNotFoundException
import java.io.InputStream
import java.util.Locale

/**
 * Provedor de documentos do Sofaly (21/11/2026): a memória da TV, cada pen drive/disco USB e as pastas do "Na rede"
 * (SMB, FTP, SFTP, NFS, nuvens) aparecem como "lugares" para os outros apps, pelo padrão do Android (SAF).
 *
 * Porquê: o Google TV não tem o seletor de documentos do sistema; sem ele, apps como o RetroArch da Play não conseguem
 * ler pasta nenhuma. Para quê: o app recebe a pasta que a pessoa escolheu no [DocumentPickerActivity] e lê/grava nela.
 * Como: o seletor devolve um endereço deste provedor com a permissão só daquela pasta (o Android confere cada acesso com
 * [isChildDocument]). Ninguém lê nada daqui sem essa escolha: o provedor exige MANAGE_DOCUMENTS (só o sistema tem).
 * Liga com: GravacaoDestino (os mesmos volumes), "Na rede" (MidiaArmazem + Pastas), PainelArquivos (a mesma regra de
 * deixar a pasta Android de fora) e o acesso a todos os arquivos que o launcher já se concede.
 */
class TvDocumentsProvider : DocumentsProvider() {
    companion object {
        private const val TAG = "TvLauncher.Docs"
        fun autoridade(ctx: Context) = ctx.packageName + ".documents"

        private val COLUNAS_RAIZ = arrayOf(Root.COLUMN_ROOT_ID, Root.COLUMN_FLAGS, Root.COLUMN_TITLE, Root.COLUMN_SUMMARY,
            Root.COLUMN_DOCUMENT_ID, Root.COLUMN_AVAILABLE_BYTES, Root.COLUMN_ICON)
        private val COLUNAS_DOC = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
            Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED, Document.COLUMN_FLAGS)

        /** Tipos do "Na rede" que são pastas de arquivos (Plex, Jellyfin, links ficam de fora: são catálogos). */
        private val TIPOS_REDE = setOf(ContaMidia.SMB, ContaMidia.FTP, ContaMidia.FTPS, ContaMidia.SFTP, ContaMidia.NFS,
            ContaMidia.ONEDRIVE, ContaMidia.DROPBOX, ContaMidia.BOX, ContaMidia.GDRIVE)

        fun tipoPorNome(nome: String): String {
            val ext = nome.substringAfterLast('.', "").lowercase(Locale.ROOT)
            return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        }

        /** Um lugar que o seletor mostra na primeira tela. */
        class Lugar(val raiz: DocumentIds.Id, val titulo: String, val resumo: String?, val gravavel: Boolean, val livre: Long?)

        /** Memória da TV, pen drives montados e pastas do "Na rede", nesta ordem. */
        fun lugares(ctx: Context): List<Lugar> {
            val lista = mutableListOf<Lugar>()
            interna()?.let { lista += Lugar(DocumentIds.raiz(DocumentIds.LOCAL, DocumentIds.INTERNA), ctx.getString(R.string.dp_memoria),
                null, true, runCatching { it.usableSpace }.getOrNull()) }
            for (v in volumesUsb(ctx)) lista += Lugar(DocumentIds.raiz(DocumentIds.LOCAL, v.first), v.second, null, true,
                runCatching { v.third.usableSpace }.getOrNull())
            for (c in contasRede(ctx)) lista += Lugar(DocumentIds.raiz(DocumentIds.NET, c.id), c.nome,
                ctx.getString(R.string.dp_rede), Pastas.gravavel(c), null)
            return lista
        }

        private fun interna(): File? = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()

        /** (uuid, nome, pasta) de cada volume removível montado (os mesmos que a gravação usa). */
        private fun volumesUsb(ctx: Context): List<Triple<String, String, File>> =
            ctx.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
                .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.directory != null && it.uuid != null }
                .map { Triple(it.uuid!!, it.getDescription(ctx), it.directory!!) }

        private fun contasRede(ctx: Context): List<ContaMidia> = MidiaArmazem.contas(ctx).filter { it.tipo in TIPOS_REDE }

        /** A pasta do aparelho de um id "local", ou null. Nunca sai da raiz do volume (links simbólicos inclusive). */
        fun arquivoLocal(ctx: Context, id: DocumentIds.Id): File? {
            if (id.tipo != DocumentIds.LOCAL || DocumentIds.protegido(id)) return null
            val raiz = (if (id.chave == DocumentIds.INTERNA) interna() else volumesUsb(ctx).firstOrNull { it.first == id.chave }?.third)
                ?: return null
            val f = if (id.caminho.isEmpty()) raiz else File(raiz, id.caminho)
            val cr = raiz.canonicalPath
            return f.takeIf { val c = it.canonicalPath; c == cr || c.startsWith("$cr/") }
        }

        fun conta(ctx: Context, id: DocumentIds.Id): ContaMidia? =
            if (id.tipo != DocumentIds.NET) null else contasRede(ctx).firstOrNull { it.id == id.chave }

        /** Caminho dentro da conta ("" = onde a conta começa, ex.: a subpasta do compartilhamento SMB). */
        fun caminhoRede(c: ContaMidia, id: DocumentIds.Id): String = MidiaContas.juntar(Pastas.inicio(c), id.caminho)

        /** Item de uma pasta, para o seletor e para o provedor. */
        class Item(val id: DocumentIds.Id, val nome: String, val pasta: Boolean, val tamanho: Long, val mudou: Long)

        /** Conteúdo de uma pasta (sem ocultos, pastas primeiro, por nome). Rede = I/O: nunca na thread da tela. */
        fun listar(ctx: Context, pai: DocumentIds.Id): List<Item> {
            val itens = when (pai.tipo) {
                DocumentIds.LOCAL -> {
                    val dir = arquivoLocal(ctx, pai) ?: throw FileNotFoundException(pai.toString())
                    (dir.listFiles() ?: throw FileNotFoundException(pai.toString())).map {
                        Item(pai.filho(it.name), it.name, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified())
                    }
                }
                else -> {
                    val c = conta(ctx, pai) ?: throw FileNotFoundException(pai.toString())
                    Pastas.abrir(c).use { p -> p.listar(caminhoRede(c, pai)).map { Item(pai.filho(it.nome), it.nome, it.pasta, it.tamanho, 0) } }
                }
            }
            return itens.filter { it.nome.isNotEmpty() && !it.nome.startsWith(".") && '/' !in it.nome && !DocumentIds.protegido(it.id) }
                .sortedWith(compareBy({ !it.pasta }, { it.nome.lowercase(Locale.ROOT) }))
        }
    }

    private val linha by lazy { HandlerThread("docs-rede").apply { start() } }

    override fun onCreate(): Boolean = true

    override fun queryRoots(projection: Array<out String>?): Cursor {
        val ctx = context ?: return MatrixCursor(projection ?: COLUNAS_RAIZ)
        val c = MatrixCursor(projection ?: COLUNAS_RAIZ)
        for (l in lugares(ctx)) {
            var flags = Root.FLAG_SUPPORTS_IS_CHILD
            if (l.gravavel) flags = flags or Root.FLAG_SUPPORTS_CREATE
            if (l.raiz.tipo == DocumentIds.LOCAL) flags = flags or Root.FLAG_LOCAL_ONLY
            c.newRow().add(Root.COLUMN_ROOT_ID, l.raiz.raiz).add(Root.COLUMN_FLAGS, flags).add(Root.COLUMN_TITLE, l.titulo)
                .add(Root.COLUMN_SUMMARY, l.resumo).add(Root.COLUMN_DOCUMENT_ID, l.raiz.toString())
                .add(Root.COLUMN_AVAILABLE_BYTES, l.livre).add(Root.COLUMN_ICON, R.mipmap.ic_onetv)
        }
        return c
    }

    private fun id(docId: String?): DocumentIds.Id = DocumentIds.ler(docId) ?: throw FileNotFoundException("id inválido")

    private fun gravavel(ctx: Context, id: DocumentIds.Id): Boolean =
        if (id.tipo == DocumentIds.LOCAL) arquivoLocal(ctx, id)?.canWrite() == true else conta(ctx, id)?.let { Pastas.gravavel(it) } == true

    private fun linhaDoc(c: MatrixCursor, ctx: Context, i: Item) {
        var flags = 0
        val grava = gravavel(ctx, i.id)
        if (i.pasta && grava) flags = flags or Document.FLAG_DIR_SUPPORTS_CREATE
        if (!i.pasta && grava) flags = flags or Document.FLAG_SUPPORTS_WRITE
        if (grava && i.id.caminho.isNotEmpty()) flags = flags or Document.FLAG_SUPPORTS_DELETE or
            (if (i.id.tipo == DocumentIds.LOCAL) Document.FLAG_SUPPORTS_RENAME else 0)
        c.newRow().add(Document.COLUMN_DOCUMENT_ID, i.id.toString()).add(Document.COLUMN_DISPLAY_NAME, i.nome)
            .add(Document.COLUMN_MIME_TYPE, if (i.pasta) Document.MIME_TYPE_DIR else tipoPorNome(i.nome))
            .add(Document.COLUMN_SIZE, if (i.pasta) null else i.tamanho)
            .add(Document.COLUMN_LAST_MODIFIED, i.mudou.takeIf { it > 0 }).add(Document.COLUMN_FLAGS, flags)
    }

    override fun queryDocument(documentId: String?, projection: Array<out String>?): Cursor {
        val ctx = context ?: throw FileNotFoundException()
        val i = id(documentId)
        val c = MatrixCursor(projection ?: COLUNAS_DOC)
        val item = if (i.caminho.isEmpty()) {
            val l = lugares(ctx).firstOrNull { it.raiz.raiz == i.raiz } ?: throw FileNotFoundException(documentId)
            Item(i, l.titulo, true, 0, 0)
        } else if (i.tipo == DocumentIds.LOCAL) {
            val f = arquivoLocal(ctx, i)?.takeIf { it.exists() } ?: throw FileNotFoundException(documentId)
            Item(i, f.name, f.isDirectory, if (f.isDirectory) 0 else f.length(), f.lastModified())
        } else {
            // Rede: o item vem da listagem da pasta de cima (os protocolos não têm um "stat" comum).
            val pai = i.pai ?: throw FileNotFoundException(documentId)
            listarOuFalhar(ctx, pai).firstOrNull { it.nome == i.nome } ?: throw FileNotFoundException(documentId)
        }
        linhaDoc(c, ctx, item)
        return c
    }

    private fun listarOuFalhar(ctx: Context, pai: DocumentIds.Id): List<Item> = try {
        listar(ctx, pai)
    } catch (e: FileNotFoundException) { throw e } catch (e: Exception) {
        Log.w(TAG, "listar ${pai.tipo}: ${e.javaClass.simpleName}")
        throw FileNotFoundException(e.message ?: "falhou")
    }

    override fun queryChildDocuments(parentDocumentId: String?, projection: Array<out String>?, sortOrder: String?): Cursor {
        val ctx = context ?: throw FileNotFoundException()
        val pai = id(parentDocumentId)
        val c = MatrixCursor(projection ?: COLUNAS_DOC)
        for (item in listarOuFalhar(ctx, pai)) linhaDoc(c, ctx, item)
        c.setNotificationUri(ctx.contentResolver, DocumentsContract.buildChildDocumentsUri(autoridade(ctx), pai.toString()))
        return c
    }

    override fun isChildDocument(parentDocumentId: String?, documentId: String?): Boolean {
        val pai = DocumentIds.ler(parentDocumentId) ?: return false
        val filho = DocumentIds.ler(documentId) ?: return false
        return DocumentIds.dentro(pai, filho) && !DocumentIds.protegido(filho)
    }

    override fun getDocumentType(documentId: String?): String {
        val i = id(documentId)
        if (i.caminho.isEmpty()) return Document.MIME_TYPE_DIR
        if (i.tipo == DocumentIds.LOCAL) {
            val f = arquivoLocal(context ?: throw FileNotFoundException(), i) ?: throw FileNotFoundException(documentId)
            return if (f.isDirectory) Document.MIME_TYPE_DIR else tipoPorNome(f.name)
        }
        return super.getDocumentType(documentId)   // rede: pergunta ao queryDocument (lista a pasta de cima)
    }

    override fun openDocument(documentId: String?, mode: String?, signal: CancellationSignal?): ParcelFileDescriptor {
        val ctx = context ?: throw FileNotFoundException()
        val i = id(documentId)
        val escrever = mode.orEmpty().contains('w') || mode.orEmpty().contains('a')
        if (i.tipo == DocumentIds.LOCAL) {
            val f = arquivoLocal(ctx, i) ?: throw FileNotFoundException(documentId)
            return ParcelFileDescriptor.open(f, ParcelFileDescriptor.parseMode(mode ?: "r"))
        }
        val c = conta(ctx, i) ?: throw FileNotFoundException(documentId)
        val caminho = caminhoRede(c, i)
        return if (escrever) escreverRede(c, caminho) else lerRede(ctx, c, caminho)
    }

    /**
     * Leitura da rede com posição livre (o app pode pular para o meio do arquivo): um descritor "proxy" do Android; cada
     * pedido de leitura vem para cá com a posição, e reaproveitamos o fluxo aberto enquanto a leitura segue em ordem.
     */
    private fun lerRede(ctx: Context, c: ContaMidia, caminho: String): ParcelFileDescriptor {
        val pasta = try { Pastas.abrir(c) } catch (e: Exception) { throw FileNotFoundException(e.message ?: "rede") }
        val tamanho = try { pasta.tamanho(caminho) } catch (e: Exception) { pasta.close(); throw FileNotFoundException(e.message ?: "rede") }
        val sm = ctx.getSystemService(StorageManager::class.java) ?: run { pasta.close(); throw FileNotFoundException("storage") }
        return sm.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
            private var fluxo: InputStream? = null
            private var pos = -1L
            override fun onGetSize() = tamanho
            override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                try {
                    if (offset >= tamanho) return 0
                    if (fluxo == null || pos != offset) { fluxo?.close(); fluxo = pasta.ler(caminho, offset); pos = offset }
                    var lidos = 0
                    while (lidos < size) {
                        val n = fluxo!!.read(data, lidos, size - lidos)
                        if (n < 0) break
                        lidos += n
                    }
                    pos += lidos
                    return lidos
                } catch (e: Exception) {
                    Log.w(TAG, "ler rede: ${e.javaClass.simpleName}")
                    runCatching { fluxo?.close() }; fluxo = null
                    throw ErrnoException("read", OsConstants.EIO)
                }
            }
            override fun onRelease() { runCatching { fluxo?.close() }; runCatching { pasta.close() } }
        }, Handler(linha.looper))
    }

    /** Gravação na rede: um cano; o app escreve numa ponta e uma linha nossa manda para a pasta da rede. */
    private fun escreverRede(c: ContaMidia, caminho: String): ParcelFileDescriptor {
        if (!Pastas.gravavel(c)) throw FileNotFoundException("só leitura")
        val (ler, escrever) = ParcelFileDescriptor.createReliablePipe()
        Fundo.rodar("docs-gravar") {
            try {
                Pastas.abrir(c).use { p ->
                    (p as PastaGravavel).escrever(caminho).use { saida ->
                        ParcelFileDescriptor.AutoCloseInputStream(ler).use { it.copyTo(saida, 64 * 1024) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "gravar rede: ${e.javaClass.simpleName}")
                runCatching { ler.closeWithError("falhou") }
            }
        }
        return escrever
    }

    override fun createDocument(parentDocumentId: String?, mimeType: String?, displayName: String?): String {
        val ctx = context ?: throw FileNotFoundException()
        val pai = id(parentDocumentId)
        val nome = displayName?.trim().orEmpty()
        if (!DocumentIds.nomeValido(nome)) throw FileNotFoundException("nome inválido")
        val pasta = mimeType == Document.MIME_TYPE_DIR
        val existentes = listarOuFalhar(ctx, pai).map { it.nome }.toSet()
        // O app manda "jogo.srm" com tipo conhecido; se o nome não tem extensão e o tipo tem, a extensão entra.
        val ext = if (pasta || nome.contains('.')) null else mimeType?.let { MimeTypeMap.getSingleton().getExtensionFromMimeType(it) }
        val novo = pai.filho(DocumentIds.nomeLivre(if (ext != null) "$nome.$ext" else nome, existentes))
        if (novo.tipo == DocumentIds.LOCAL) {
            val f = arquivoLocal(ctx, novo) ?: throw FileNotFoundException("fora da pasta")
            val ok = if (pasta) f.mkdir() else f.createNewFile()
            if (!ok) throw FileNotFoundException("não criou")
        } else {
            val c = conta(ctx, novo)?.takeIf { Pastas.gravavel(it) } ?: throw FileNotFoundException("só leitura")
            Pastas.abrir(c).use { p ->
                val g = p as PastaGravavel
                if (pasta) g.criarPastas(caminhoRede(c, novo)) else g.escrever(caminhoRede(c, novo)).close()
            }
        }
        avisarMudanca(ctx, pai)
        return novo.toString()
    }

    override fun deleteDocument(documentId: String?) {
        val ctx = context ?: throw FileNotFoundException()
        val i = id(documentId)
        if (i.caminho.isEmpty()) throw FileNotFoundException("raiz")
        if (i.tipo == DocumentIds.LOCAL) {
            val f = arquivoLocal(ctx, i) ?: throw FileNotFoundException(documentId)
            if (!f.deleteRecursively()) throw FileNotFoundException("não apagou")
        } else {
            val c = conta(ctx, i)?.takeIf { Pastas.gravavel(it) } ?: throw FileNotFoundException("só leitura")
            Pastas.abrir(c).use { (it as PastaGravavel).apagar(caminhoRede(c, i)) }
        }
        i.pai?.let { avisarMudanca(ctx, it) }
    }

    override fun renameDocument(documentId: String?, displayName: String?): String {
        val ctx = context ?: throw FileNotFoundException()
        val i = id(documentId)
        val nome = displayName?.trim().orEmpty()
        if (i.tipo != DocumentIds.LOCAL || i.caminho.isEmpty() || !DocumentIds.nomeValido(nome)) throw FileNotFoundException("não renomeia")
        val pai = i.pai!!
        val novo = pai.filho(nome)
        val de = arquivoLocal(ctx, i) ?: throw FileNotFoundException(documentId)
        val para = arquivoLocal(ctx, novo)?.takeIf { !it.exists() } ?: throw FileNotFoundException("já existe")
        if (!de.renameTo(para)) throw FileNotFoundException("não renomeou")
        avisarMudanca(ctx, pai)
        return novo.toString()
    }

    private fun avisarMudanca(ctx: Context, pai: DocumentIds.Id) {
        runCatching { ctx.contentResolver.notifyChange(DocumentsContract.buildChildDocumentsUri(autoridade(ctx), pai.toString()), null) }
    }
}
