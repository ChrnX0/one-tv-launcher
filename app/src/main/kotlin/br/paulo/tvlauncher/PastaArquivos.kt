package br.paulo.tvlauncher

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale

/**
 * Uma pasta de arquivos, venha de onde vier (14/11/2026): SMB, FTP/FTPS, SFTP, NFS e o pendrive/disco USB da TV.
 * Todos fazem só duas coisas que o resto do app precisa: listar uma pasta e ler um arquivo a partir de uma posição
 * (o player pede trechos: avançar e voltar no vídeo não baixa o arquivo inteiro).
 *
 * Caminhos: sempre relativos à raiz da conta, com "/", sem barra no começo ("" = a raiz). Cada protocolo traduz para o
 * jeito dele (SMB usa "\", FTP e SFTP usam "/" absoluto, NFS anda de pasta em pasta pelo "handle").
 */
interface PastaArquivos : AutoCloseable {
    class Item(val nome: String, val pasta: Boolean, val tamanho: Long)

    fun listar(caminho: String): List<Item>

    /** Tamanho do arquivo em bytes. */
    fun tamanho(caminho: String): Long

    /** O arquivo a partir de [posicao]. Fechar o leitor não fecha a pasta. */
    fun ler(caminho: String, posicao: Long): InputStream
}

/** O pendrive/disco USB (ou outra pasta do aparelho): arquivo comum, com o acesso a todos os arquivos. */
class PastaLocal(private val raiz: File) : PastaArquivos {
    private fun arquivo(caminho: String): File {
        require(Pastas.caminhoValido(caminho)) { "caminho inválido" }
        val f = if (caminho.isEmpty()) raiz else File(raiz, caminho)
        // canonicalPath resolve links simbólicos: um link no pendrive apontando para fora não passa.
        if (!f.canonicalPath.startsWith(raiz.canonicalPath)) throw IOException("fora da pasta")
        return f
    }

    override fun listar(caminho: String): List<PastaArquivos.Item> =
        (arquivo(caminho).listFiles() ?: throw IOException("BAD_NETWORK_NAME")).map { PastaArquivos.Item(it.name, it.isDirectory, it.length()) }

    override fun tamanho(caminho: String) = arquivo(caminho).length()

    override fun ler(caminho: String, posicao: Long): InputStream {
        val r = RandomAccessFile(arquivo(caminho), "r")
        r.seek(posicao)
        return object : InputStream() {
            override fun read() = r.read()
            override fun read(b: ByteArray, off: Int, len: Int) = r.read(b, off, len)
            override fun close() = r.close()
        }
    }

    override fun close() {}
}

object Pastas {
    /** Os tipos de conta que são pastas de arquivos (as outras são servidores de mídia ou links). */
    val TIPOS = setOf(ContaMidia.SMB, ContaMidia.FTP, ContaMidia.FTPS, ContaMidia.SFTP, ContaMidia.NFS, ContaMidia.ONEDRIVE, ContaMidia.DROPBOX)

    fun ehPasta(c: ContaMidia) = c.tipo in TIPOS

    fun abrir(c: ContaMidia): PastaArquivos = when (c.tipo) {
        ContaMidia.SMB -> Smb.Pasta(Smb.abrir(c))
        ContaMidia.FTP, ContaMidia.FTPS -> Ftp.abrir(c)
        ContaMidia.SFTP -> Sftp.abrir(c)
        ContaMidia.NFS -> Nfs.abrir(c)
        ContaMidia.ONEDRIVE -> PastaOneDrive(c)
        ContaMidia.DROPBOX -> PastaDropbox(c)
        else -> throw IOException("não é uma pasta: ${c.tipo}")
    }

    /** Onde a conta começa ("" = a raiz). SMB: a subpasta depois do compartilhamento; NFS: a raiz do export. */
    fun inicio(c: ContaMidia): String = when (c.tipo) {
        ContaMidia.SMB -> Smb.inicio(c)
        ContaMidia.NFS -> ""
        else -> c.pasta.trim('/')
    }

    /** Sem "..", sem caracteres de controle, sem barra invertida (o caminho nunca sai da conta). */
    fun caminhoValido(caminho: String): Boolean =
        caminho.length <= 4096 && !caminho.startsWith("/") && caminho.none { it.code < 32 || it == '\\' } &&
            caminho.split('/').none { it == ".." || it == "." }

    /** Itens de uma pasta como a tela "Na rede" mostra: sem ocultos, pastas primeiro, por nome. */
    fun entradas(itens: List<PastaArquivos.Item>, caminho: String, uri: (String) -> String): List<Upnp.Entrada> = itens
        .filter { it.nome.isNotEmpty() && it.nome != "." && it.nome != ".." && !it.nome.startsWith(".") && '/' !in it.nome }
        .map { i ->
            val sub = MidiaContas.juntar(caminho, i.nome)
            if (i.pasta) Upnp.Entrada(sub, i.nome, true, null, Upnp.Tipo.PASTA, null)
            else Upnp.Entrada(sub, i.nome, false, uri(sub), MidiaContas.tipoPorNome(i.nome), null)
        }
        .sortedWith(compareBy({ !it.pasta }, { it.titulo.lowercase(Locale.ROOT) }))

    fun listar(c: ContaMidia, caminho: String): List<Upnp.Entrada> {
        require(caminhoValido(caminho)) { "caminho inválido" }
        return abrir(c).use { entradas(it.listar(caminho), caminho) { sub -> uri(c, sub) } }
    }

    /** Arquivo inteiro (fotos), até [max] bytes. */
    fun lerTudo(p: PastaArquivos, caminho: String, max: Int = 30 * 1024 * 1024): ByteArray {
        val tamanho = p.tamanho(caminho)
        require(tamanho <= max) { "arquivo grande demais" }
        return p.ler(caminho, 0).use { e ->
            val buf = ByteArray(tamanho.toInt())
            var lido = 0
            while (lido < buf.size) { val n = e.read(buf, lido, buf.size - lido); if (n < 0) break; lido += n }
            buf.copyOf(lido)
        }
    }

    fun lerTudo(c: ContaMidia, caminho: String, max: Int = 30 * 1024 * 1024): ByteArray = abrir(c).use { lerTudo(it, caminho, max) }

    // ---------------------------------------------------------------- endereço que vai para o player

    /**
     * smb://<conta>/<caminho> (o de sempre, para não perder as posições salvas) ou arq://<conta>/<caminho> para os
     * outros. Leva o ID da conta, nunca a senha: quem abre (FontePastas) busca a conta no armazém cifrado.
     */
    fun uri(c: ContaMidia, caminho: String): String =
        if (c.tipo == ContaMidia.SMB) MidiaContas.uriSmb(c, caminho)
        else "arq://${c.id}/" + caminho.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** O contrário de [uri]: id da conta e caminho. */
    fun lerUri(u: String): Pair<String, String>? {
        if (u.startsWith("smb://")) return MidiaContas.lerUriSmb(u)
        if (!u.startsWith("arq://")) return null
        val resto = u.substring(6)
        val id = resto.substringBefore('/')
        val caminho = runCatching { resto.substringAfter('/', "").split('/').joinToString("/") { URLDecoder.decode(it, "UTF-8") } }.getOrNull() ?: return null
        if (id.isEmpty() || !id.all { it.isLetterOrDigit() || it == '-' } || caminho.isEmpty() || !caminhoValido(caminho)) return null
        return id to caminho
    }

    /** Pendrive/disco da TV vira file:///storage/...: só debaixo de /storage (nunca os dados do app). */
    fun arquivoLocalPermitido(u: String): Boolean {
        if (!u.startsWith("file:///storage/")) return false
        val caminho = runCatching { URLDecoder.decode(u.substring(7), "UTF-8") }.getOrNull() ?: return false
        return caminho.split('/').none { it == ".." || it == "." } && caminho.none { it.code < 32 }
    }
}
