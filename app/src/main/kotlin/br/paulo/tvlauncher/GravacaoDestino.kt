package br.paulo.tvlauncher

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.EnumSet

/**
 * Onde a gravação vai parar (14/11/2026, decisão do dono): o pendrive ou disco no hub USB; sem ele ligado, uma pasta
 * da rede (SMB) que o dono escolheu. A memória da TV nunca (1,8 GB livres; uma hora de canal ocupa até 3,6 GB).
 * Tudo dentro da pasta "OneTV-gravacoes".
 */
object GravacaoDestino {
    private const val PREFS = "gravacao"
    private const val CHAVE_REDE = "pasta_rede"   // id da conta SMB de reserva ("" = nenhuma)

    class Item(val nome: String, val tamanho: Long, val modificado: Long)

    interface Destino : AutoCloseable {
        /** Para a tela: "Pendrive SanDisk" ou o nome da conta SMB. */
        val nome: String
        fun livre(): Long
        fun abrir(arquivo: String): OutputStream
        fun listar(): List<Item>
        fun apagar(arquivo: String)
        /** O endereço que o player abre (file:// ou smb://). */
        fun uri(arquivo: String): String
    }

    fun pastaRede(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAVE_REDE, "").orEmpty()

    fun escolherPastaRede(ctx: Context, idConta: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHAVE_REDE, idConta).apply()
    }

    /** Menos que isso livre não serve (15/11/2026: um pendrive do Rufus mostrava só a parte de boot, 0,9 MB). */
    const val MINIMO_LIVRE = 512L * 1024 * 1024

    /**
     * Pendrive ou disco montado agora: (nome para a tela, pasta raiz). Com várias partes (pendrive com 2 partições, hub
     * com 2 discos), fica com a de mais espaço livre; parte com menos de [MINIMO_LIVRE] não conta.
     */
    fun pendrive(ctx: Context): Pair<String, File>? {
        if (Build.VERSION.SDK_INT < 30) return null
        val sm = ctx.getSystemService(StorageManager::class.java) ?: return null
        val v = sm.storageVolumes.filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.directory != null }
            .map { it to runCatching { it.directory!!.usableSpace }.getOrDefault(0L) }
            .filter { it.second >= MINIMO_LIVRE }.maxByOrNull { it.second }?.first ?: return null
        return v.getDescription(ctx) to v.directory!!
    }

    /**
     * Por que o pendrive ligado não serve (para a mensagem da tela), ou null se não há pendrive ou ele serve:
     * formato que a TV não lê (NTFS e afins aparecem "unmountable") ou cheio/minúsculo.
     */
    fun problemaPendrive(ctx: Context): String? {
        if (Build.VERSION.SDK_INT < 30 || pendrive(ctx) != null) return null
        val vols = ctx.getSystemService(StorageManager::class.java)?.storageVolumes?.filter { it.isRemovable } ?: return null
        val ilegivel = vols.firstOrNull { it.state == Environment.MEDIA_UNMOUNTABLE || it.state == Environment.MEDIA_NOFS }
        if (ilegivel != null) return ctx.getString(R.string.grv_pendrive_formato, ilegivel.getDescription(ctx))
        val pequeno = vols.firstOrNull { it.state == Environment.MEDIA_MOUNTED } ?: return null
        return ctx.getString(R.string.grv_pendrive_cheio, pequeno.getDescription(ctx))
    }

    /**
     * O destino de agora: pendrive se houver, senão a pasta da rede escolhida. null = nenhum (a tela pede para ligar um
     * pendrive ou escolher a pasta). Abre conexão (SMB): chamar fora da thread da tela e fechar depois.
     */
    fun abrir(ctx: Context): Destino? {
        pendrive(ctx)?.let { (nome, raiz) ->
            if (PainelArquivos.garantirAcesso(ctx.applicationContext)) return Local(nome, File(raiz, GravacaoRegras.PASTA))
        }
        val id = pastaRede(ctx).ifEmpty { return null }
        val conta = MidiaArmazem.conta(ctx, id)?.takeIf { it.tipo == ContaMidia.SMB } ?: return null
        return Rede(conta)
    }

    /** Descrição sem abrir conexão (para a linha "Onde grava" da tela). */
    fun descricao(ctx: Context): String? = pendrive(ctx)?.first
        ?: pastaRede(ctx).takeIf { it.isNotEmpty() }?.let { id -> MidiaArmazem.conta(ctx, id)?.nome }

    private class Local(override val nome: String, private val dir: File) : Destino {
        private fun arquivo(n: String): File {
            require(n.isNotEmpty() && '/' !in n && n != "." && n != "..") { "nome inválido" }
            return File(dir, n)
        }
        override fun livre(): Long = (if (dir.exists()) dir else dir.parentFile ?: dir).usableSpace
        override fun abrir(arquivo: String): OutputStream {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("não criei a pasta no pendrive")
            return FileOutputStream(arquivo(arquivo)).buffered(256 * 1024)
        }
        override fun listar(): List<Item> = dir.listFiles()?.filter { it.isFile && it.name.endsWith(GravacaoRegras.EXTENSAO) }
            ?.map { Item(it.name, it.length(), it.lastModified()) }.orEmpty()
        override fun apagar(arquivo: String) { arquivo(arquivo).delete() }
        override fun uri(arquivo: String): String = Uri.fromFile(arquivo(arquivo)).toString()
        override fun close() {}
    }

    private class Rede(private val c: ContaMidia) : Destino {
        private val a = Smb.abrir(c)
        private val base = MidiaContas.juntar(Smb.inicio(c), GravacaoRegras.PASTA)
        private fun win(n: String) = (if (n.isEmpty()) base else "$base/$n").replace('/', '\\')
        override val nome get() = c.nome
        override fun livre(): Long = runCatching { a.pasta.shareInformation.freeSpace }.getOrDefault(Long.MAX_VALUE)
        override fun abrir(arquivo: String): OutputStream {
            require('/' !in arquivo && '\\' !in arquivo && arquivo.isNotEmpty()) { "nome inválido" }
            var caminho = ""
            for (parte in base.split('/').filter { it.isNotEmpty() }) {
                caminho = MidiaContas.juntar(caminho, parte)
                val w = caminho.replace('/', '\\')
                if (!a.pasta.folderExists(w)) a.pasta.mkdir(w)
            }
            val f = a.pasta.openFile(win(arquivo), EnumSet.of(AccessMask.GENERIC_WRITE), null, SMB2ShareAccess.ALL,
                SMB2CreateDisposition.FILE_OVERWRITE_IF, null)
            val s = f.outputStream
            return object : java.io.FilterOutputStream(s.buffered(256 * 1024)) {
                override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
                override fun close() { runCatching { super.close() }; runCatching { f.close() } }
            }
        }
        override fun listar(): List<Item> = runCatching {
            a.pasta.list(win("")).filter { it.fileName.endsWith(GravacaoRegras.EXTENSAO) }
                .map { Item(it.fileName, it.endOfFile, it.lastWriteTime.toEpochMillis()) }
        }.getOrDefault(emptyList())
        override fun apagar(arquivo: String) { runCatching { a.pasta.rm(win(arquivo)) } }
        override fun uri(arquivo: String): String = Pastas.uri(c, MidiaContas.juntar(base, arquivo))
        override fun close() = a.close()
    }
}
