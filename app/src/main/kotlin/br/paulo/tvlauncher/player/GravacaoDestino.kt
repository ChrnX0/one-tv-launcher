package br.paulo.tvlauncher.player

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.network.Rede
import br.paulo.tvlauncher.panel.PainelArquivos
import br.paulo.tvlauncher.panel.PastaGravavel
import br.paulo.tvlauncher.panel.Pastas
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Onde a gravação vai parar (14/11/2026, decisão do dono): o pendrive ou disco no hub USB; sem ele ligado, uma pasta
 * da rede que o dono escolheu. 20/11/2026: a pessoa escolhe o lugar na hora de gravar ([opcoes]), entre os pendrives e
 * todas as pastas do "Na rede" que aceitam gravação (SMB, FTP/FTPS, SFTP). A memória da TV nunca (1,8 GB livres; uma hora de canal ocupa até 3,6 GB).
 * Tudo dentro da pasta "OneTV-gravacoes".
 */
object GravacaoDestino {
    private const val PREFS = "gravacao"
    private const val CHAVE_REDE = "pasta_rede"   // id da conta SMB de reserva ("" = nenhuma)

    class Item(val nome: String, val tamanho: Long, val modificado: Long)

    interface Destino : AutoCloseable {
        /** Para a tela: "Pendrive SanDisk" ou o nome da conta. */
        val nome: String
        /** O id de [Opcao] deste lugar (para apagar ou tocar depois no lugar certo). */
        val id: String
        fun livre(): Long
        fun abrir(arquivo: String): OutputStream
        fun listar(): List<Item>
        fun apagar(arquivo: String)
        /** O endereço que o player abre (file:// ou smb://). */
        fun uri(arquivo: String): String
        /** Para copiar à nuvem depois (RecordingCloudCopy). */
        fun ler(arquivo: String): java.io.InputStream
        fun tamanho(arquivo: String): Long
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

    /** Um lugar onde dá para gravar agora: [id] = "usb:<pasta do volume>" ou "conta:<id da conta do Na rede>". */
    class Opcao(val id: String, val nome: String)

    private const val CHAVE_ULTIMO = "ultimo"   // o lugar escolhido da última vez (vem marcado na pergunta)

    fun ultimo(ctx: Context): String = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(CHAVE_ULTIMO, "").orEmpty()

    /** Lembra a escolha; sendo uma pasta da rede, ela vira também a reserva para quando não há pendrive. */
    fun lembrar(ctx: Context, id: String) {
        val e = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(CHAVE_ULTIMO, id)
        if (id.startsWith(CONTA)) e.putString(CHAVE_REDE, id.removePrefix(CONTA))
        e.apply()
    }

    private const val USB = "usb:"
    private const val CONTA = "conta:"
    private const val INTERNA = "interna"

    /**
     * A memória da TV só vira opção com bastante espaço (20/11/2026). Num Chromecast comum (1,8 GB livres) ela nunca
     * aparece; com o pendrive formatado como memória da TV (Android "armazenamento interno adotado", que leva junto a
     * área compartilhada) passa a ter dezenas de GB e aparece. Uma hora de canal ocupa até 3,6 GB.
     */
    const val MINIMO_INTERNA = 8L * 1024 * 1024 * 1024

    /** A área compartilhada da TV (/sdcard), se tiver espaço para gravar. */
    private fun interna(): File? = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        ?.takeIf { runCatching { it.usableSpace }.getOrDefault(0L) >= MINIMO_INTERNA }

    /**
     * Todos os lugares de agora (20/11/2026, pedido do dono: escolher na hora de gravar): cada pendrive/disco ligado com
     * espaço e cada pasta do "Na rede" em que dá para gravar (Pastas.GRAVAVEIS). Lê as contas do disco: fora da tela.
     */
    fun opcoes(ctx: Context): List<Opcao> {
        val usb = if (Build.VERSION.SDK_INT < 30) emptyList() else runCatching {
            ctx.getSystemService(StorageManager::class.java)?.storageVolumes.orEmpty()
                .filter { it.isRemovable && it.state == Environment.MEDIA_MOUNTED && it.directory != null }
                .filter { runCatching { it.directory!!.usableSpace }.getOrDefault(0L) >= MINIMO_LIVRE }
                .map { Opcao(USB + it.directory!!.absolutePath, it.getDescription(ctx)) }
        }.getOrDefault(emptyList())
        val rede = MidiaArmazem.contas(ctx).filter { Pastas.gravavel(it) }.map { Opcao(CONTA + it.id, it.nome) }
        val tv = listOfNotNull(interna()?.let { Opcao(INTERNA, ctx.getString(R.string.grv_memoria_tv)) })
        return GravacaoRegras.ordenarOpcoes(usb + tv + rede, ultimo(ctx)) { it.id }
    }

    /**
     * Abre o lugar [id] escolhido; sem [id] (ou se ele sumiu: pendrive tirado, conta apagada), o automático de antes:
     * pendrive se houver, senão a pasta da rede de reserva. null = nenhum. Abre conexão: fora da tela, e fechar depois.
     */
    fun abrir(ctx: Context, id: String? = null): Destino? {
        if (!id.isNullOrEmpty()) runCatching { abrirEscolhido(ctx, id) }.getOrNull()?.let { return it }
        pendrive(ctx)?.let { (nome, raiz) ->
            if (PainelArquivos.garantirAcesso(ctx.applicationContext)) return Local(nome, File(raiz, GravacaoRegras.PASTA), USB + raiz.absolutePath)
        }
        pastaRede(ctx).takeIf { it.isNotEmpty() }?.let { MidiaArmazem.conta(ctx, it) }?.takeIf { Pastas.gravavel(it) }?.let { return Rede(it) }
        // Último recurso: a memória da TV, só quando tem muito espaço (pendrive adotado como memória da TV).
        return abrirEscolhido(ctx, INTERNA)
    }

    private fun abrirEscolhido(ctx: Context, id: String): Destino? = when {
        id.startsWith(USB) -> {
            val raiz = File(id.removePrefix(USB))
            val vol = if (Build.VERSION.SDK_INT < 30) null else ctx.getSystemService(StorageManager::class.java)?.storageVolumes
                ?.firstOrNull { it.state == Environment.MEDIA_MOUNTED && it.directory?.absolutePath == raiz.absolutePath }
            if (vol != null && PainelArquivos.garantirAcesso(ctx.applicationContext)) Local(vol.getDescription(ctx), File(raiz, GravacaoRegras.PASTA), id) else null
        }
        id == INTERNA -> interna()?.takeIf { PainelArquivos.garantirAcesso(ctx.applicationContext) }
            ?.let { Local(ctx.getString(R.string.grv_memoria_tv), File(it, GravacaoRegras.PASTA), INTERNA) }
        id.startsWith(CONTA) -> MidiaArmazem.conta(ctx, id.removePrefix(CONTA))?.takeIf { Pastas.gravavel(it) }?.let { Rede(it) }
        else -> null
    }

    /**
     * "Gravar onde?" (20/11/2026): lê os lugares fora da tela e, com dois ou mais, pergunta numa lista com o da última vez
     * marcado. Um só: segue com ele sem perguntar. Nenhum: [semLugar] (a tela diz o motivo). [aoEscolher] recebe a
     * [Opcao] na thread da tela; null = nenhum lugar agora (agendar ainda pode, e o automático decide na hora).
     */
    fun perguntar(ctx: Context, aoEscolher: (Opcao?) -> Unit) {
        val app = ctx.applicationContext
        val tela = android.os.Handler(android.os.Looper.getMainLooper())
        Fundo.rodar("gravacao-onde") {
            val l = runCatching { opcoes(app) }.getOrDefault(emptyList())
            tela.post {
                if (!GravacaoRegras.perguntarOnde(l.size)) { aoEscolher(l.firstOrNull()); return@post }
                Estilo.lista(ctx, ctx.getString(R.string.grv_onde_pergunta), l.map { it.nome }, listOf(0)) { i ->
                    lembrar(app, l[i].id); aoEscolher(l[i])
                }
            }
        }
    }

    /** Descrição sem abrir conexão (para a linha "Onde grava" da tela). */
    fun descricao(ctx: Context): String? = pendrive(ctx)?.first
        ?: pastaRede(ctx).takeIf { it.isNotEmpty() }?.let { id -> MidiaArmazem.conta(ctx, id)?.nome }
        ?: interna()?.let { ctx.getString(R.string.grv_memoria_tv) }

    private class Local(override val nome: String, private val dir: File, override val id: String) : Destino {
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
        override fun ler(arquivo: String): java.io.InputStream = java.io.FileInputStream(arquivo(arquivo))
        override fun tamanho(arquivo: String): Long = arquivo(arquivo).let { if (it.isFile) it.length() else throw java.io.FileNotFoundException(arquivo) }
        override fun close() {}
    }

    /** Uma pasta do "Na rede" (SMB, FTP/FTPS ou SFTP), tudo dentro de "OneTV-gravacoes". */
    private class Rede(private val c: ContaMidia) : Destino {
        private val p = Pastas.abrir(c)
        private val g = p as? PastaGravavel ?: run { runCatching { p.close() }; throw IOException("não grava em ${c.tipo}") }
        private val base = MidiaContas.juntar(Pastas.inicio(c), GravacaoRegras.PASTA)
        private var pastaCriada = false
        private fun caminho(n: String): String {
            require(n.isNotEmpty() && '/' !in n && '\\' !in n && n != "." && n != "..") { "nome inválido" }
            return MidiaContas.juntar(base, n)
        }
        override val nome get() = c.nome
        override val id get() = CONTA + c.id
        override fun livre(): Long = runCatching { g.livre(base) }.getOrDefault(Long.MAX_VALUE)
        override fun abrir(arquivo: String): java.io.OutputStream {
            if (!pastaCriada) { g.criarPastas(base); pastaCriada = true }
            return g.escrever(caminho(arquivo))
        }
        override fun listar(): List<Item> = runCatching {
            p.listar(base).filter { !it.pasta && it.nome.endsWith(GravacaoRegras.EXTENSAO) }.map { Item(it.nome, it.tamanho, 0L) }
        }.getOrDefault(emptyList())
        override fun apagar(arquivo: String) { runCatching { g.apagar(caminho(arquivo)) } }
        override fun uri(arquivo: String): String = Pastas.uri(c, caminho(arquivo))
        override fun ler(arquivo: String): java.io.InputStream = p.ler(caminho(arquivo), 0)
        override fun tamanho(arquivo: String): Long = p.tamanho(caminho(arquivo))
        override fun close() = p.close()
    }
}
