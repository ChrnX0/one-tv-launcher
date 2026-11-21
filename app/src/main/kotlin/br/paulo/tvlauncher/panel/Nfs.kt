package br.paulo.tvlauncher.panel

import br.paulo.tvlauncher.player.ContaMidia
import br.paulo.tvlauncher.player.MidiaContas
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * NFS versão 3 (14/11/2026): pastas de NAS (Synology, QNAP, TrueNAS, Unraid) e de PCs Linux, sem biblioteca: o NFS são
 * poucos pedidos simples (RFC 1813) sobre o "ONC RPC" (RFC 5531), e as bibliotecas Java existentes trazem o Netty 3
 * inteiro (velho e grande). Só leitura: MOUNT, LOOKUP, GETATTR, READDIRPLUS e READ.
 *
 * O que a pessoa precisa saber (aparece na tela quando falha):
 *  - NFS não tem senha: o servidor libera pelo IP. No NAS, a regra de NFS da pasta precisa incluir o IP da TV (ou a rede
 *    da casa, ex. 192.168.1.0/24).
 *  - Por padrão o servidor só aceita pedidos vindos de "portas baixas" (< 1024), que só o sistema pode usar; app nenhum
 *    consegue. É preciso ligar no NAS "permitir conexões de portas não privilegiadas" (Synology) ou a opção "insecure"
 *    no /etc/exports (Linux). Sem isso o servidor responde "acesso negado" ao montar.
 *  - O "usuário" é um número (uid). O padrão 1000 é o 1º usuário de um Linux; no Synology o 1º é 1026.
 *
 * Partes puras com teste JVM (NfsTest): escrever e ler XDR, montar o pedido RPC e ler as respostas.
 */
object Nfs {
    const val PORTMAP = 100000
    const val MOUNT = 100005
    const val NFS = 100003
    private const val TEMPO_MS = 15_000
    private const val MAX_RESPOSTA = 2 * 1024 * 1024
    const val PEDACO = 64 * 1024

    // ---------------------------------------------------------------- XDR (tudo em blocos de 4 bytes, big-endian)

    class Escrita {
        private val b = ByteArrayOutputStream()
        fun u32(v: Int) = apply { b.write(v ushr 24); b.write(v ushr 16); b.write(v ushr 8); b.write(v) }
        fun u64(v: Long) = apply { u32((v ushr 32).toInt()); u32(v.toInt()) }
        fun opaco(d: ByteArray) = apply { u32(d.size); b.write(d); repeat((4 - d.size % 4) % 4) { b.write(0) } }
        fun fixo(d: ByteArray) = apply { b.write(d); repeat((4 - d.size % 4) % 4) { b.write(0) } }
        fun texto(s: String) = opaco(s.toByteArray(Charsets.UTF_8))
        fun bytes(): ByteArray = b.toByteArray()
    }

    class Leitura(private val d: ByteArray, var pos: Int = 0) {
        private fun precisa(n: Int) { if (n < 0 || pos + n > d.size) throw IOException("resposta NFS curta") }
        fun u32(): Int { precisa(4); val v = ((d[pos].toInt() and 0xff) shl 24) or ((d[pos + 1].toInt() and 0xff) shl 16) or
            ((d[pos + 2].toInt() and 0xff) shl 8) or (d[pos + 3].toInt() and 0xff); pos += 4; return v }
        fun u64(): Long = (u32().toLong() shl 32) or (u32().toLong() and 0xffffffffL)
        fun bool(): Boolean = u32() != 0
        fun opaco(max: Int = MAX_RESPOSTA): ByteArray {
            val n = u32(); if (n < 0 || n > max) throw IOException("campo NFS grande demais")
            precisa(n); val r = d.copyOfRange(pos, pos + n); pos += n + (4 - n % 4) % 4; return r
        }
        fun texto(max: Int = 4096) = String(opaco(max), Charsets.UTF_8)
        fun pular(n: Int) { precisa(n); pos += n }
        fun fixo(n: Int): ByteArray { precisa(n); val r = d.copyOfRange(pos, pos + n); pos += n; return r }
        val resto get() = d.size - pos
    }

    // ---------------------------------------------------------------- RPC

    /** Pedido RPC: cabeçalho + credencial AUTH_SYS (uid/gid) + argumentos, já com a "marca de registro" do TCP. */
    fun pedido(xid: Int, programa: Int, versao: Int, procedimento: Int, uid: Int, gid: Int, args: ByteArray): ByteArray {
        val cred = Escrita().u32(0).texto("onetv").u32(uid).u32(gid).u32(1).u32(gid).bytes()
        val corpo = Escrita().u32(xid).u32(0).u32(2).u32(programa).u32(versao).u32(procedimento)
            .u32(1).opaco(cred)   // AUTH_SYS
            .u32(0).u32(0)        // verificador vazio
            .fixo(args).bytes()
        // Marca de registro: 1 bit de "último pedaço" + tamanho.
        return Escrita().u32(0x80000000.toInt() or corpo.size).fixo(corpo).bytes()
    }

    /** Lê o cabeçalho da resposta e devolve o leitor posicionado nos resultados. Recusa = exceção com o motivo. */
    fun resposta(d: ByteArray, xid: Int): Leitura {
        val l = Leitura(d)
        if (l.u32() != xid) throw IOException("resposta NFS de outro pedido")
        if (l.u32() != 1) throw IOException("não é resposta RPC")
        if (l.u32() != 0) {   // MSG_DENIED
            val motivo = l.u32()
            // AUTH_ERROR com "TOOWEAK" quase sempre é a porta alta (opção "insecure" desligada no servidor).
            throw SecurityException(if (motivo == 1) "NFS_PORTA" else "NFS_RECUSADO")
        }
        l.u32(); l.opaco(400)   // verificador
        when (val aceito = l.u32()) {
            0 -> return l
            1 -> throw IOException("NFS: programa indisponível")
            2 -> throw IOException("NFS: versão 3 indisponível")
            else -> throw IOException("NFS: erro $aceito")
        }
    }

    /** Uma conexão TCP com um programa RPC (portmap, mount ou nfs). */
    class Conexao(host: String, porta: Int, private val programa: Int, private val versao: Int, private val uid: Int, private val gid: Int) : AutoCloseable {
        // Sem fechar na falha, cada tentativa com o NAS desligado (cada busca no vídeo) vazava um descritor.
        private val s = Socket().apply {
            try { soTimeout = 30_000; tcpNoDelay = true; connect(InetSocketAddress(host, porta), TEMPO_MS) }
            catch (e: Exception) { runCatching { close() }; throw e }
        }
        private val sai: OutputStream = s.getOutputStream()
        private val entra = DataInputStream(s.getInputStream().buffered(64 * 1024))
        private var xid = (System.nanoTime() and 0x7fffffff).toInt()

        @Synchronized
        fun chamar(procedimento: Int, args: ByteArray): Leitura {
            val id = ++xid
            sai.write(pedido(id, programa, versao, procedimento, uid, gid, args)); sai.flush()
            while (true) {
                val d = lerRegistro(entra)
                // Resposta atrasada de um pedido antigo (cancelado por tempo): descarta e espera a nossa.
                if (d.size >= 4 && Leitura(d).u32() != id) continue
                return resposta(d, id)
            }
        }

        override fun close() { runCatching { s.close() } }
    }

    /** Junta os pedaços de um registro RPC (marca de 4 bytes antes de cada pedaço). */
    fun lerRegistro(e: InputStream): ByteArray {
        val saida = ByteArrayOutputStream()
        val d = if (e is DataInputStream) e else DataInputStream(e)
        while (true) {
            val marca = try { d.readInt() } catch (x: EOFException) { throw IOException("servidor NFS fechou a conexão") }
            val tam = marca and 0x7fffffff
            if (tam < 0 || saida.size() + tam > MAX_RESPOSTA) throw IOException("resposta NFS grande demais")
            val buf = ByteArray(tam); d.readFully(buf); saida.write(buf)
            if (marca < 0) return saida.toByteArray()   // bit alto = último pedaço
        }
    }

    /** Porta de um programa pelo portmapper (porta 111). null = não registrado. */
    fun portaDe(host: String, programa: Int, versao: Int): Int? = runCatching {
        Conexao(host, 111, PORTMAP, 2, 0, 0).use { c ->
            c.chamar(3, Escrita().u32(programa).u32(versao).u32(6).u32(0).bytes()).u32().takeIf { it in 1..65535 }
        }
    }.getOrNull()

    // ---------------------------------------------------------------- NFS v3

    class Atributos(val tipo: Int, val tamanho: Long) {
        val pasta get() = tipo == 2
    }

    /** fattr3: tipo, modo, links, uid, gid, tamanho, usado, rdev, fsid, fileid e 3 datas. Só tipo e tamanho importam. */
    fun lerAtributos(l: Leitura): Atributos {
        val tipo = l.u32(); l.pular(16); val tam = l.u64(); l.pular(8 + 8 + 8 + 8 + 24)
        return Atributos(tipo, tam)
    }

    private fun lerPosOp(l: Leitura): Atributos? = if (l.bool()) lerAtributos(l) else null

    /** Status NFS diferente de 0 vira o erro que a tela entende. */
    fun conferir(status: Int) {
        when (status) {
            0 -> return
            1, 13 -> throw SecurityException("ACCESS_DENIED")       // NFS3ERR_PERM, NFS3ERR_ACCES
            2, 20 -> throw IOException("BAD_NETWORK_NAME")         // NOENT, NOTDIR
            70 -> throw IOException("NFS: handle velho")           // STALE
            else -> throw IOException("NFS: erro $status")
        }
    }

    class Item(val nome: String, val atributos: Atributos?, val handle: ByteArray?)

    /** Resposta do READDIRPLUS: itens, cookie e verificador para pedir o resto, e se acabou. */
    class Pagina(val itens: List<Item>, val cookie: Long, val verificador: ByteArray, val fim: Boolean)

    fun lerReaddirplus(l: Leitura): Pagina {
        conferir(l.u32())
        lerPosOp(l)
        val verif = l.fixo(8)
        val itens = ArrayList<Item>()
        var cookie = 0L
        while (l.bool()) {
            l.u64()   // fileid
            val nome = l.texto(1024)
            cookie = l.u64()
            val at = lerPosOp(l)
            val fh = if (l.bool()) l.opaco(64) else null
            itens += Item(nome, at, fh)
        }
        return Pagina(itens, cookie, verif, l.bool())
    }

    // ---------------------------------------------------------------- a pasta

    fun abrir(c: ContaMidia): PastaArquivos = Pasta(c)

    /** "1026" ou "1026:100" -> (uid, gid). Vazio = 1000:1000. */
    fun usuario(texto: String): Pair<Int, Int> {
        val u = texto.substringBefore(':').trim().toIntOrNull()?.takeIf { it >= 0 } ?: 1000
        val g = texto.substringAfter(':', "").trim().toIntOrNull()?.takeIf { it >= 0 } ?: u
        return u to g
    }

    private class Pasta(c: ContaMidia) : PastaArquivos {
        private val host = c.servidor.substringBefore(':')
        private val uid: Int
        private val gid: Int
        private val nfs: Conexao
        private val raiz: ByteArray
        private val handles = HashMap<String, ByteArray>()

        init {
            usuario(c.usuario).let { uid = it.first; gid = it.second }
            val portaNfs = c.servidor.substringAfter(':', "").toIntOrNull() ?: portaDe(host, NFS, 3) ?: 2049
            // MOUNT: a porta varia (o portmapper diz). Pede o "handle" da pasta exportada.
            val portaMount = portaDe(host, MOUNT, 3) ?: throw IOException("NFS: o servidor não respondeu na porta 111")
            raiz = Conexao(host, portaMount, MOUNT, 3, uid, gid).use { m ->
                val l = m.chamar(1, Escrita().texto("/" + c.pasta.trim('/')).bytes())
                when (val st = l.u32()) {
                    0 -> l.opaco(64)
                    2 -> throw IOException("BAD_NETWORK_NAME")
                    1, 13 -> throw SecurityException("NFS_IP")   // o IP da TV não está na regra do NAS
                    else -> throw IOException("NFS mount: erro $st")
                }
            }
            nfs = Conexao(host, portaNfs, NFS, 3, uid, gid)
            handles[""] = raiz
        }

        /** Anda pasta por pasta (LOOKUP) até o caminho, guardando os handles do caminho. */
        private fun handle(caminho: String): ByteArray {
            handles[caminho]?.let { return it }
            val pai = handle(caminho.substringBeforeLast('/', ""))
            val l = nfs.chamar(3, Escrita().opaco(pai).texto(caminho.substringAfterLast('/')).bytes())
            conferir(l.u32())
            return l.opaco(64).also { handles[caminho] = it }
        }

        override fun listar(caminho: String): List<PastaArquivos.Item> {
            val dir = handle(caminho)
            val todos = ArrayList<PastaArquivos.Item>()
            var cookie = 0L; var verif = ByteArray(8)
            for (volta in 0 until 200) {   // 200 páginas de 32 KB: pasta enorme não trava a tela para sempre
                val p = lerReaddirplus(nfs.chamar(17, Escrita().opaco(dir).u64(cookie).fixo(verif).u32(8 * 1024).u32(32 * 1024).bytes()))
                for (i in p.itens) {
                    if (i.nome == "." || i.nome == "..") continue
                    i.handle?.let { handles[MidiaContas.juntar(caminho, i.nome)] = it }
                    todos += PastaArquivos.Item(i.nome, i.atributos?.pasta ?: false, i.atributos?.tamanho ?: 0)
                }
                if (p.fim || p.itens.isEmpty()) break
                cookie = p.cookie; verif = p.verificador
            }
            return todos
        }

        override fun tamanho(caminho: String): Long {
            val l = nfs.chamar(1, Escrita().opaco(handle(caminho)).bytes())
            conferir(l.u32())
            return lerAtributos(l).tamanho
        }

        override fun ler(caminho: String, posicao: Long): InputStream {
            val fh = handle(caminho)
            return object : InputStream() {
                var pos = posicao
                var buf = ByteArray(0); var i = 0; var acabou = false
                override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (len == 0) return 0
                    if (i >= buf.size) {
                        if (acabou) return -1
                        val l = nfs.chamar(6, Escrita().opaco(fh).u64(pos).u32(PEDACO).bytes())
                        conferir(l.u32()); lerPosOp(l)
                        l.u32(); acabou = l.bool()
                        buf = l.opaco(PEDACO * 2); i = 0; pos += buf.size
                        if (buf.isEmpty()) return -1
                    }
                    val n = minOf(len, buf.size - i)
                    System.arraycopy(buf, i, b, off, n); i += n
                    return n
                }
            }
        }

        override fun close() = nfs.close()
    }
}
