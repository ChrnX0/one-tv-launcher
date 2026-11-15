package br.paulo.tvlauncher

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * A TV em "Este Computador" do Windows, igual a um celular ligado no cabo, só que pelo Wi-Fi (14/11/2026, pedido do
 * dono: tem que aparecer sozinha, sem certificado, sem serviço para ligar, porque quem usa é leigo).
 *
 * O celular no cabo fala MTP (Media Transfer Protocol) por USB. O Windows também fala MTP pela rede ("MTP/IP", driver
 * wpdmtpip.dll, que já vem instalado): é o mesmo protocolo dentro de pacotes PTP/IP (norma CIPA DC-005) na porta TCP
 * 15740. Quem conversa é o "iniciador" (o computador) com o "respondedor" (nós).
 *
 * Aqui está o respondedor inteiro, sem Android, para os testes JVM (MtpIpTest sobe o servidor de verdade e conversa
 * com ele; o gphoto2 serve de cliente real). A parte Android (permissão na tela, anúncio na rede) fica no MtpNaRede.kt.
 *
 * As pastas são as mesmas da aba Arquivos do painel e passam pelas mesmas barreiras (PainelArquivos.resolver): nada
 * fora da memória interna, nada de Android/, links simbólicos não saem da raiz.
 *
 * O MTP/IP NÃO tem senha nem criptografia (decisão do dono em 14/11/2026: em vez do PIN, a TV pergunta na tela uma
 * vez por computador). Por isso: só rede de casa e só computador aceito na TV.
 */
object PtpIp {
    const val PORTA = 15740
    const val INICIO_COMANDO = 1
    const val INICIO_COMANDO_OK = 2
    const val INICIO_EVENTO = 3
    const val INICIO_EVENTO_OK = 4
    const val INICIO_RECUSADO = 5
    const val OPERACAO = 6
    const val RESPOSTA = 7
    const val EVENTO = 8
    const val DADOS_COMECO = 9
    const val DADOS = 10
    const val CANCELAR = 11
    const val DADOS_FIM = 12
    const val SONDA = 13
    const val SONDA_OK = 14

    /** Pacote de controle maior que isto é lixo ou ataque (os de dados de arquivo são lidos aos pedaços, sem este teto). */
    const val MAX_CONTROLE = 1 shl 20

    class Cabecalho(val tamanho: Int, val tipo: Int)   // tamanho = só o corpo, sem os 8 bytes do cabeçalho

    fun lerCabecalho(e: InputStream): Cabecalho {
        val b = lerExato(e, 8)
        val total = Mtp.Leitor(b).u32()
        val tipo = Mtp.Leitor(b, 4).u32()
        if (total < 8) throw IOException("pacote PTP/IP com tamanho $total")
        return Cabecalho(total - 8, tipo)
    }

    fun lerExato(e: InputStream, n: Int): ByteArray {
        val b = ByteArray(n)
        var lido = 0
        while (lido < n) {
            val r = e.read(b, lido, n - lido)
            if (r < 0) throw EOFException()
            lido += r
        }
        return b
    }

    /** Um pacote de controle inteiro: (tipo, corpo). */
    fun ler(e: InputStream): Pair<Int, ByteArray> {
        val c = lerCabecalho(e)
        return c.tipo to lerCorpo(e, c)
    }

    /**
     * O corpo de um pacote que vai inteiro para a memória, com o teto [MAX_CONTROLE]. Sem ele, um cabeçalho dizendo
     * ~2 GB faria a TV tentar alocar isso (OutOfMemoryError derruba o processo inteiro, que é a HOME).
     */
    fun lerCorpo(e: InputStream, c: Cabecalho): ByteArray {
        if (c.tamanho > MAX_CONTROLE) throw IOException("pacote de controle grande demais: ${c.tamanho}")
        return lerExato(e, c.tamanho)
    }

    fun escrever(s: OutputStream, tipo: Int, corpo: ByteArray, de: Int = 0, n: Int = corpo.size) {
        val c = Mtp.Escritor().u32(n + 8).u32(tipo).bytes()
        s.write(c)
        s.write(corpo, de, n)
    }
}

/** Os números e os "datasets" (blocos de dados) do MTP 1.1. Tudo little-endian (byte menos significativo primeiro). */
object Mtp {
    // Operações
    const val OP_INFO_APARELHO = 0x1001
    const val OP_ABRIR_SESSAO = 0x1002
    const val OP_FECHAR_SESSAO = 0x1003
    const val OP_ARMAZENS = 0x1004
    const val OP_INFO_ARMAZEM = 0x1005
    const val OP_QUANTOS = 0x1006
    const val OP_HANDLES = 0x1007
    const val OP_INFO_OBJETO = 0x1008
    const val OP_OBJETO = 0x1009
    const val OP_MINIATURA = 0x100A
    const val OP_APAGAR = 0x100B
    const val OP_MANDAR_INFO = 0x100C
    const val OP_MANDAR_OBJETO = 0x100D
    const val OP_DESC_PROP_APARELHO = 0x1014
    const val OP_VALOR_PROP_APARELHO = 0x1015
    const val OP_MOVER = 0x1019
    const val OP_PEDACO = 0x101B
    const val OP_PROPS_DO_FORMATO = 0x9801
    const val OP_DESC_PROP = 0x9802
    const val OP_VALOR_PROP = 0x9803
    const val OP_MUDAR_PROP = 0x9804
    const val OP_LISTA_PROPS = 0x9805
    const val OP_MANDAR_LISTA_PROPS = 0x9808

    val OPERACOES = intArrayOf(OP_INFO_APARELHO, OP_ABRIR_SESSAO, OP_FECHAR_SESSAO, OP_ARMAZENS, OP_INFO_ARMAZEM, OP_QUANTOS,
        OP_HANDLES, OP_INFO_OBJETO, OP_OBJETO, OP_APAGAR, OP_MANDAR_INFO, OP_MANDAR_OBJETO, OP_DESC_PROP_APARELHO,
        OP_VALOR_PROP_APARELHO, OP_MOVER, OP_PEDACO, OP_PROPS_DO_FORMATO, OP_DESC_PROP, OP_VALOR_PROP, OP_MUDAR_PROP,
        OP_LISTA_PROPS, OP_MANDAR_LISTA_PROPS)

    // Respostas
    const val OK = 0x2001
    const val ERRO = 0x2002
    const val SESSAO_FECHADA = 0x2003
    const val NAO_SUPORTADA = 0x2005
    const val PARAMETRO_NAO_SUPORTADO = 0x2006
    const val ARMAZEM_INVALIDO = 0x2008
    const val HANDLE_INVALIDO = 0x2009
    const val PROP_APARELHO_INVALIDA = 0x200A
    const val CHEIO = 0x200C
    const val NEGADO = 0x200F
    const val SEM_MINIATURA = 0x2010
    const val SEM_INFO_ANTES = 0x2015
    const val PAI_INVALIDO = 0x201A
    const val PARAMETRO_INVALIDO = 0x201D
    const val SESSAO_JA_ABERTA = 0x201E
    const val CANCELADA = 0x201F
    const val PROP_INVALIDA = 0xA801
    const val FORMATO_PROP_INVALIDO = 0xA802
    const val DADOS_INVALIDOS = 0xA806
    const val PROFUNDIDADE_NAO_SUPORTADA = 0xA808

    // Formatos de objeto
    const val F_INDEFINIDO = 0x3000
    const val F_PASTA = 0x3001
    private val FORMATOS = mapOf("txt" to 0x3004, "htm" to 0x3005, "html" to 0x3005, "wav" to 0x3008, "mp3" to 0x3009,
        "avi" to 0x300A, "mpg" to 0x300B, "mpeg" to 0x300B, "jpg" to 0x3801, "jpeg" to 0x3801, "bmp" to 0x3804,
        "gif" to 0x3807, "png" to 0x380B, "wma" to 0xB901, "ogg" to 0xB902, "aac" to 0xB903, "flac" to 0xB906,
        "wmv" to 0xB981, "mp4" to 0xB982, "m4v" to 0xB982, "3gp" to 0xB984)
    fun formato(nome: String, pasta: Boolean): Int =
        if (pasta) F_PASTA else FORMATOS[nome.substringAfterLast('.', "").lowercase()] ?: F_INDEFINIDO

    // Tipos de dado
    const val T_U16 = 0x0004
    const val T_U32 = 0x0006
    const val T_U64 = 0x0008
    const val T_U128 = 0x000A
    const val T_TEXTO = 0xFFFF

    // Propriedades de objeto (o Windows lista as pastas por elas, não pelo ObjectInfo)
    const val P_ARMAZEM = 0xDC01
    const val P_FORMATO = 0xDC02
    const val P_PROTECAO = 0xDC03
    const val P_TAMANHO = 0xDC04
    const val P_ARQUIVO = 0xDC07
    const val P_MODIFICADO = 0xDC09
    const val P_PAI = 0xDC0B
    const val P_ID_FIXO = 0xDC41
    const val P_NOME = 0xDC44
    val PROPS = linkedMapOf(P_ARMAZEM to T_U32, P_FORMATO to T_U16, P_PROTECAO to T_U16, P_TAMANHO to T_U64,
        P_ARQUIVO to T_TEXTO, P_MODIFICADO to T_TEXTO, P_PAI to T_U32, P_ID_FIXO to T_U128, P_NOME to T_TEXTO)

    /** Propriedade do aparelho: o nome amigável (o que aparece em Este Computador). */
    const val PA_NOME = 0xD402

    /** Um armazém só: a memória interna. O número é (unidade física 1, partição 1). */
    const val ARMAZEM = 0x00010001
    const val TODOS = -1   // 0xFFFFFFFF

    class Aparelho(val nome: String, val modelo: String, val versao: String, val serie: String, val guid: ByteArray)

    /** Escreve números little-endian e textos do MTP num vetor de bytes. */
    class Escritor {
        private val b = ByteArrayOutputStream()
        fun u8(v: Int) = apply { b.write(v and 0xFF) }
        fun u16(v: Int) = apply { u8(v); u8(v shr 8) }
        fun u32(v: Int) = apply { u16(v); u16(v ushr 16) }
        fun u64(v: Long) = apply { u32(v.toInt()); u32((v ushr 32).toInt()) }
        fun cru(v: ByteArray) = apply { b.write(v) }
        /** Texto MTP: 1 byte com o número de letras (contando o zero final), depois UTF-16. Vazio = um byte 0. */
        fun texto(t: String) = apply {
            if (t.isEmpty()) { u8(0); return@apply }
            val c = t.take(254)
            u8(c.length + 1)
            for (ch in c) u16(ch.code)
            u16(0)
        }
        fun lista16(v: IntArray) = apply { u32(v.size); v.forEach { u16(it) } }
        fun lista32(v: List<Int>) = apply { u32(v.size); v.forEach { u32(it) } }
        fun bytes(): ByteArray = b.toByteArray()
    }

    /** Lê o que o Escritor escreve. Ler além do fim lança IOException (dados cortados ou malformados). */
    class Leitor(private val b: ByteArray, private var p: Int = 0) {
        val resto get() = b.size - p
        private fun precisa(n: Int) { if (n < 0 || resto < n) throw IOException("dados MTP cortados") }
        fun u8(): Int { precisa(1); return b[p++].toInt() and 0xFF }
        fun u16(): Int = u8() or (u8() shl 8)
        fun u32(): Int = u16() or (u16() shl 16)
        fun u64(): Long = (u32().toLong() and 0xFFFFFFFFL) or (u32().toLong() shl 32)
        fun pular(n: Int) { precisa(n); p += n }
        fun cru(n: Int): ByteArray { precisa(n); return b.copyOfRange(p, p + n).also { p += n } }
        fun texto(): String {
            val n = u8()
            if (n == 0) return ""
            val sb = StringBuilder(n)
            repeat(n) { val c = u16(); if (c != 0) sb.append(c.toChar()) }
            return sb.toString()
        }
        /** Texto UTF-16 terminado em zero (o nome do computador no começo da conexão). No máximo [max] letras. */
        fun textoAteZero(max: Int = 80): String {
            val sb = StringBuilder()
            while (resto >= 2) { val c = u16(); if (c == 0) break; if (sb.length < max) sb.append(c.toChar()) }
            return sb.toString()
        }
        /** Pula um valor do tipo [tipo] (para as propriedades que não usamos). */
        fun pularValor(tipo: Int) {
            when (tipo) {
                0x0001, 0x0002 -> pular(1)
                0x0003, T_U16 -> pular(2)
                0x0005, T_U32 -> pular(4)
                0x0007, T_U64 -> pular(8)
                0x0009, T_U128 -> pular(16)
                T_TEXTO -> texto()
                else -> throw IOException("tipo MTP não suportado: $tipo")
            }
        }
    }

    private val DATA = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd'T'HHmmss", Locale.US) }
    fun data(ms: Long): String = DATA.get()!!.format(Date(ms))
    fun lerData(t: String): Long? = try { DATA.get()!!.parse(t.take(15))?.time } catch (_: Exception) { null }

    fun infoAparelho(a: Aparelho): ByteArray = Escritor()
        .u16(100)                      // versão do padrão: 1.00
        .u32(6)                        // extensão da Microsoft: é isto que diz "sou MTP", não só uma câmera PTP
        .u16(100)
        .texto("microsoft.com: 1.0;")
        .u16(0)                        // modo de funcionamento padrão
        .lista16(OPERACOES)
        .lista16(intArrayOf())         // eventos: nenhum (o computador relê a pasta quando a pessoa atualiza)
        .lista16(intArrayOf(PA_NOME))
        .lista16(intArrayOf())         // formatos de captura (não somos câmera)
        .lista16(intArrayOf(F_INDEFINIDO, F_PASTA) + FORMATOS.values.distinct().sorted().toIntArray())
        .texto("One TV Launcher").texto(a.modelo).texto(a.versao).texto(a.serie)
        .bytes()

    fun infoArmazem(total: Long, livre: Long, nome: String): ByteArray = Escritor()
        .u16(0x0003)                   // memória fixa
        .u16(0x0002)                   // sistema de arquivos com pastas
        .u16(0x0000)                   // leitura e escrita
        .u64(total).u64(livre).u32(TODOS)
        .texto(nome).texto("onetv")
        .bytes()

    class Objeto(val handle: Int, val pai: Int, val nome: String, val pasta: Boolean, val tamanho: Long, val modificado: Long, val idFixo: ByteArray) {
        val formato get() = formato(nome, pasta)
    }

    fun infoObjeto(o: Objeto): ByteArray = Escritor()
        .u32(ARMAZEM).u16(o.formato).u16(0)
        .u32(if (o.tamanho > 0xFFFFFFFFL) -1 else o.tamanho.toInt())   // 4 GB ou mais: o tamanho certo vai na propriedade
        .u16(0).u32(0).u32(0).u32(0)   // miniatura: não há
        .u32(0).u32(0).u32(0)          // imagem: largura, altura, bits
        .u32(o.pai)
        .u16(if (o.pasta) 1 else 0).u32(0).u32(0)
        .texto(o.nome).texto(data(o.modificado)).texto(data(o.modificado)).texto("")
        .bytes()

    fun valorProp(w: Escritor, o: Objeto, prop: Int): Boolean {
        when (prop) {
            P_ARMAZEM -> w.u32(ARMAZEM)
            P_FORMATO -> w.u16(o.formato)
            P_PROTECAO -> w.u16(0)
            P_TAMANHO -> w.u64(o.tamanho)
            P_ARQUIVO, P_NOME -> w.texto(o.nome)
            P_MODIFICADO -> w.texto(data(o.modificado))
            P_PAI -> w.u32(o.pai)
            P_ID_FIXO -> w.cru(o.idFixo)
            else -> return false
        }
        return true
    }

    fun descProp(prop: Int): ByteArray? {
        val tipo = PROPS[prop] ?: return null
        val w = Escritor().u16(prop).u16(tipo).u8(if (prop == P_ARQUIVO) 1 else 0)   // só o nome do arquivo pode mudar
        when (tipo) {
            T_U16 -> w.u16(0); T_U32 -> w.u32(0); T_U64 -> w.u64(0); T_U128 -> w.cru(ByteArray(16)); else -> w.texto("")
        }
        return w.u32(0).u8(if (prop == P_MODIFICADO) 3 else 0).bytes()   // 3 = "é uma data"
    }

    /** Lista de propriedades de vários objetos de uma vez (é assim que o Windows lê uma pasta inteira). */
    fun listaProps(objetos: List<Objeto>, props: List<Int>): ByteArray {
        val w = Escritor().u32(objetos.size * props.size)
        for (o in objetos) for (p in props) { w.u32(o.handle).u16(p).u16(PROPS.getValue(p)); valorProp(w, o, p) }
        return w.bytes()
    }

    /** O que um ObjectInfo vindo do computador diz do arquivo novo. */
    class Novo(val formato: Int, val tamanho: Long, val nome: String, val modificado: Long?)

    fun lerInfoObjeto(b: ByteArray): Novo {
        val l = Leitor(b)
        l.u32(); val formato = l.u16(); l.u16()
        val t = l.u32().toLong() and 0xFFFFFFFFL
        l.pular(2 + 4 * 6 + 4 + 2 + 4 + 4)   // miniatura, imagem, pai (vem no parâmetro), associação, sequência
        val nome = l.texto()
        val criado = if (l.resto > 0) l.texto() else ""
        val mod = if (l.resto > 0) l.texto() else criado
        return Novo(formato, if (t == 0xFFFFFFFFL) -1 else t, nome, lerData(mod.ifEmpty { criado }))
    }

    /** O mesmo, quando o computador manda por lista de propriedades (arquivos de 4 GB ou mais). */
    fun lerListaProps(b: ByteArray, formato: Int, tamanho: Long): Novo {
        val l = Leitor(b)
        val n = l.u32()
        if (n < 0 || n > 64) throw IOException("lista de propriedades com $n itens")
        var nome = ""; var mod: Long? = null
        repeat(n) {
            l.u32(); val prop = l.u16(); val tipo = l.u16()
            if (tipo == T_TEXTO) {
                val v = l.texto()
                if (prop == P_ARQUIVO || (prop == P_NOME && nome.isEmpty())) nome = v
                if (prop == P_MODIFICADO) mod = lerData(v)
            } else l.pularValor(tipo)
        }
        return Novo(formato, tamanho, nome, mod)
    }
}

/**
 * Os arquivos vistos pelo MTP: cada arquivo ou pasta ganha um número (o "handle") na primeira vez que aparece, e o
 * computador só fala por esses números. O caminho de verdade nunca vem do computador: ele manda só um handle que nós
 * demos e, ao criar, um nome solto (conferido por nomeArquivoValido). Não há como pedir "../../algo".
 */
class MtpLoja(private val avisar: (File) -> Unit) {
    companion object {
        /** Teto de objetos numa resposta só (pasta gigante ou "liste tudo"): protege a memória da TV. */
        const val MAX_OBJETOS = 20_000
    }

    private val porCaminho = HashMap<String, Int>()
    private val porHandle = HashMap<Int, String>()
    private var proximo = 1

    class Pendente(val handle: Int, val pasta: File, val nome: String, val tamanho: Long, val modificado: Long?)

    @Synchronized private fun handleDe(rel: String): Int = porCaminho.getOrPut(rel) { val h = proximo++; porHandle[h] = rel; h }
    @Synchronized private fun relDe(h: Int): String? = porHandle[h]
    @Synchronized private fun esquecer(rel: String) {
        val alvo = porCaminho.keys.filter { it == rel || it.startsWith("$rel/") }
        for (r in alvo) porCaminho.remove(r)?.let { porHandle.remove(it) }
    }
    /** Depois de mover/renomear: os handles continuam valendo, com o caminho novo. */
    @Synchronized private fun trocar(velho: String, novo: String) {
        val alvo = porCaminho.keys.filter { it == velho || it.startsWith("$velho/") }
        for (r in alvo) {
            val h = porCaminho.remove(r) ?: continue
            val n = novo + r.substring(velho.length)
            porCaminho.remove(n)?.let { porHandle.remove(it) }
            porCaminho[n] = h; porHandle[h] = n
        }
    }

    private fun arquivoDe(rel: String): File? = try { PainelArquivos.resolver(rel) } catch (_: PainelArquivos.Erro) { esquecer(rel); null }

    private fun paiDe(rel: String): Int = if ('/' in rel) handleDe(rel.substringBeforeLast('/')) else 0

    private fun idFixo(rel: String): ByteArray = MessageDigest.getInstance("MD5").digest(rel.toByteArray())

    private fun objeto(rel: String, f: File): Mtp.Objeto =
        Mtp.Objeto(handleDe(rel), paiDe(rel), f.name, f.isDirectory, if (f.isFile) f.length() else 0, f.lastModified(), idFixo(rel))

    /** O caminho relativo da pasta [pai] (0 ou TODOS = a raiz), ou null se o handle não é uma pasta nossa. */
    private fun relDaPasta(pai: Int): String? {
        if (pai == 0 || pai == Mtp.TODOS) return ""
        val rel = relDe(pai) ?: return null
        return if (arquivoDe(rel)?.isDirectory == true) rel else null
    }

    private fun listar(rel: String): List<Pair<String, File>> {
        val pasta = arquivoDe(rel) ?: return emptyList()
        return (pasta.listFiles() ?: emptyArray()).filter {
            !(rel.isEmpty() && it.name.lowercase() in ChecagensPainel.PASTAS_FECHADAS) &&
                ChecagensPainel.nomeArquivoValido(it.name) && !DavLogica.escondido(it.name)
        }.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase() }))
            .map { (if (rel.isEmpty()) it.name else "$rel/${it.name}") to it }
    }

    fun objeto(h: Int): Mtp.Objeto? { val rel = relDe(h) ?: return null; val f = arquivoDe(rel) ?: return null; return objeto(rel, f) }
    fun arquivo(h: Int): File? = relDe(h)?.let { arquivoDe(it) }

    /** Os objetos dentro de [pai], ou null se [pai] não é pasta. */
    fun filhos(pai: Int): List<Mtp.Objeto>? {
        val rel = relDaPasta(pai) ?: return null
        return listar(rel).take(MAX_OBJETOS).map { (r, f) -> objeto(r, f) }
    }

    /** Tudo, pasta por pasta (alguns programas pedem a árvore inteira de uma vez), até [MAX_OBJETOS]. */
    fun todos(): List<Mtp.Objeto> {
        val saida = ArrayList<Mtp.Objeto>()
        val fila = ArrayDeque(listOf(""))
        while (fila.isNotEmpty() && saida.size < MAX_OBJETOS) {
            for ((r, f) in listar(fila.removeFirst())) {
                if (saida.size >= MAX_OBJETOS) break
                saida.add(objeto(r, f))
                // link simbólico de pasta: não entra (o resolver já barra o que aponta para fora; isto evita laço)
                if (f.isDirectory && !java.nio.file.Files.isSymbolicLink(f.toPath())) fila.add(r)
            }
        }
        return saida
    }

    fun total(): Long = PainelArquivos.raiz().totalSpace
    fun livre(): Long = PainelArquivos.livre()

    /** Código MTP para um erro do PainelArquivos. */
    private fun codigo(e: PainelArquivos.Erro): Int = when (e.codigo) {
        404 -> Mtp.HANDLE_INVALIDO; 507 -> Mtp.CHEIO; else -> Mtp.NEGADO
    }

    fun apagar(h: Int): Int {
        val rel = relDe(h) ?: return Mtp.HANDLE_INVALIDO
        return try { PainelArquivos.apagar(rel, avisar); esquecer(rel); Mtp.OK } catch (e: PainelArquivos.Erro) { codigo(e) }
    }

    /** Move para a pasta [novoPai] e/ou troca o nome ([novoNome] null = mantém). */
    fun mover(h: Int, novoPai: Int?, novoNome: String?): Int {
        val rel = relDe(h) ?: return Mtp.HANDLE_INVALIDO
        val destino = if (novoPai == null) rel.substringBeforeLast('/', "") else relDaPasta(novoPai) ?: return Mtp.PAI_INVALIDO
        return try {
            val novo = PainelArquivos.mover(rel, destino, novoNome ?: rel.substringAfterLast('/'), avisar)
            trocar(rel, novo); Mtp.OK
        } catch (e: PainelArquivos.Erro) { codigo(e) }
    }

    /** Pasta nova: criada na hora. Devolve (código, handle). */
    fun criarPasta(pai: Int, nome: String): Pair<Int, Int> {
        val rel = relDaPasta(pai) ?: return Mtp.PAI_INVALIDO to 0
        if (rel.isEmpty() && nome.lowercase() in ChecagensPainel.PASTAS_FECHADAS) return Mtp.NEGADO to 0
        return try {
            PainelArquivos.criarPasta(rel, nome)
            Mtp.OK to handleDe(if (rel.isEmpty()) nome else "$rel/$nome")
        } catch (e: PainelArquivos.Erro) { codigo(e) to 0 }
    }

    /** Arquivo novo: só reserva o handle; os bytes chegam na operação seguinte ([receber]). */
    fun prepararArquivo(pai: Int, novo: Mtp.Novo): Pair<Int, Pendente?> {
        val rel = relDaPasta(pai) ?: return Mtp.PAI_INVALIDO to null
        if (!ChecagensPainel.nomeArquivoValido(novo.nome) || DavLogica.escondido(novo.nome)) return Mtp.NEGADO to null
        if (rel.isEmpty() && novo.nome.lowercase() in ChecagensPainel.PASTAS_FECHADAS) return Mtp.NEGADO to null
        val pasta = arquivoDe(rel) ?: return Mtp.PAI_INVALIDO to null
        if (File(pasta, novo.nome).exists()) return Mtp.NEGADO to null   // nunca sobrescreve: o Windows apaga antes, se a pessoa mandar
        if (novo.tamanho > livre()) return Mtp.CHEIO to null
        val h = handleDe(if (rel.isEmpty()) novo.nome else "$rel/${novo.nome}")
        return Mtp.OK to Pendente(h, pasta, novo.nome, novo.tamanho, novo.modificado)
    }

    /**
     * Grava o arquivo pendente. [encher] escreve os bytes na saída e devolve quantos foram (ou lança). Grava num
     * ".parcial" escondido e só dá o nome certo no fim: conexão caída no meio não deixa arquivo pela metade.
     */
    fun receber(p: Pendente, encher: (OutputStream) -> Long): Int {
        val final = File(p.pasta, p.nome)
        val parcial = try { File.createTempFile(".${p.nome.take(40)}.", ".parcial", p.pasta) } catch (_: IOException) { return Mtp.NEGADO }
        try {
            FileOutputStream(parcial).use { s ->
                val n = encher(s)
                if (p.tamanho >= 0 && n != p.tamanho) throw IOException("chegaram $n de ${p.tamanho} bytes")
                s.fd.sync()
            }
            if (final.exists() || !parcial.renameTo(final)) { parcial.delete(); return Mtp.NEGADO }
        } catch (e: Exception) {
            parcial.delete()
            throw e
        }
        p.modificado?.let { final.setLastModified(it) }
        avisar(final)
        return Mtp.OK
    }

    /** O envio não aconteceu: o handle reservado deixa de existir. */
    fun desistir(p: Pendente) { relDe(p.handle)?.let { if (!File(p.pasta, p.nome).exists()) esquecer(it) } }
}

/** A conversa com UM computador, depois do aperto de mão: lê operações e responde, até a conexão fechar. */
class MtpSessao(private val loja: MtpLoja, private val ap: Mtp.Aparelho, private val e: InputStream, private val s: OutputStream) {
    /** Número da transação que o computador mandou cancelar (chega pela conexão de eventos, noutra thread). */
    @Volatile var cancelada = 0

    private var aberta = false
    private var pendente: MtpLoja.Pendente? = null
    private val bloco = ByteArray(256 * 1024)

    fun rodar() {
        while (true) {
            val (tipo, corpo) = try { PtpIp.ler(e) } catch (_: EOFException) { return }
            when (tipo) {
                PtpIp.OPERACAO -> operacao(Mtp.Leitor(corpo))
                PtpIp.SONDA -> { PtpIp.escrever(s, PtpIp.SONDA_OK, ByteArray(0)); s.flush() }
                PtpIp.CANCELAR -> {}   // cancelamento de algo que já acabou
                else -> throw IOException("pacote $tipo fora de hora")
            }
        }
    }

    private fun responder(codigo: Int, tid: Int, vararg params: Int) {
        val w = Mtp.Escritor().u16(codigo).u32(tid)
        params.forEach { w.u32(it) }
        PtpIp.escrever(s, PtpIp.RESPOSTA, w.bytes())
        s.flush()
    }

    /** Fase de dados para o computador: um bloco pequeno, inteiro. */
    private fun mandar(tid: Int, dados: ByteArray) {
        PtpIp.escrever(s, PtpIp.DADOS_COMECO, Mtp.Escritor().u32(tid).u64(dados.size.toLong()).bytes())
        PtpIp.escrever(s, PtpIp.DADOS_FIM, Mtp.Escritor().u32(tid).cru(dados).bytes())
    }

    /** Fase de dados para o computador: [n] bytes do arquivo a partir de [de], aos pedaços. false = cancelada. */
    private fun mandarArquivo(tid: Int, f: File, de: Long, n: Long): Boolean {
        PtpIp.escrever(s, PtpIp.DADOS_COMECO, Mtp.Escritor().u32(tid).u64(n).bytes())
        val id = Mtp.Escritor().u32(tid).bytes()
        RandomAccessFile(f, "r").use { r ->
            r.seek(de)
            var falta = n
            while (true) {
                if (cancelada == tid) return false
                val lido = if (falta == 0L) 0 else r.read(bloco, 0, minOf(bloco.size.toLong(), falta).toInt())
                if (lido < 0) throw IOException("o arquivo encolheu durante a leitura")
                falta -= lido
                // O último pedaço vai no pacote "fim dos dados"; os outros, em pacotes "dados".
                val c = Mtp.Escritor().u32(lido + 12).u32(if (falta == 0L) PtpIp.DADOS_FIM else PtpIp.DADOS).bytes()
                s.write(c); s.write(id); s.write(bloco, 0, lido)
                if (falta == 0L) return true
            }
        }
    }

    private class Cancelou : Exception()

    /**
     * Fase de dados vinda do computador: entrega os bytes a [para] e devolve o total. Lê pedaço a pedaço, sem
     * guardar o arquivo na memória. [max] = teto (para os blocos pequenos, que vão para a memória).
     */
    private fun receber(tid: Int, max: Long, para: OutputStream): Long {
        var total = 0L
        var anunciado = -1L
        while (true) {
            val c = PtpIp.lerCabecalho(e)
            when (c.tipo) {
                PtpIp.DADOS_COMECO -> {
                    val l = Mtp.Leitor(PtpIp.lerCorpo(e, c)); l.u32(); anunciado = l.u64()
                    if (anunciado in 0..Long.MAX_VALUE && anunciado > max) throw IOException("dados maiores que o permitido")
                }
                PtpIp.DADOS, PtpIp.DADOS_FIM -> {
                    if (c.tamanho < 4) throw IOException("pacote de dados sem transação")
                    PtpIp.lerExato(e, 4)
                    var falta = c.tamanho - 4
                    total += falta
                    if (total > max) throw IOException("dados maiores que o permitido")
                    while (falta > 0) {
                        val n = e.read(bloco, 0, minOf(bloco.size, falta))
                        if (n < 0) throw EOFException()
                        para.write(bloco, 0, n); falta -= n
                    }
                    if (c.tipo == PtpIp.DADOS_FIM) return total
                }
                PtpIp.CANCELAR -> { PtpIp.lerCorpo(e, c); throw Cancelou() }
                else -> throw IOException("pacote ${c.tipo} no meio dos dados")
            }
        }
    }

    private fun receberPequeno(tid: Int): ByteArray {
        val b = ByteArrayOutputStream()
        receber(tid, 64 * 1024L, b)
        return b.toByteArray()
    }

    private object Nada : OutputStream() { override fun write(b: Int) {}; override fun write(b: ByteArray, o: Int, n: Int) {} }

    private fun operacao(l: Mtp.Leitor) {
        val fase = l.u32()   // 2 = o computador vai mandar dados depois deste pedido
        val op = l.u16()
        val tid = l.u32()
        val p = IntArray(5) { if (l.resto >= 4) l.u32() else 0 }
        try {
            if (!aberta && op != Mtp.OP_INFO_APARELHO && op != Mtp.OP_ABRIR_SESSAO) {
                if (fase == 2) receber(tid, Long.MAX_VALUE, Nada)
                return responder(Mtp.SESSAO_FECHADA, tid)
            }
            executar(op, tid, p, fase)
        } catch (_: Cancelou) {
            pendente?.let { loja.desistir(it) }; pendente = null
            responder(Mtp.CANCELADA, tid)
        } catch (e: RuntimeException) {
            // Um erro inesperado numa operação (nome que o sistema de arquivos recusa, por exemplo) não derruba a conversa.
            responder(Mtp.ERRO, tid)
        }
    }

    private fun executar(op: Int, tid: Int, p: IntArray, fase: Int) {
        when (op) {
            Mtp.OP_INFO_APARELHO -> { mandar(tid, Mtp.infoAparelho(ap)); responder(Mtp.OK, tid) }
            Mtp.OP_ABRIR_SESSAO -> if (aberta) responder(Mtp.SESSAO_JA_ABERTA, tid) else { aberta = true; responder(Mtp.OK, tid) }
            Mtp.OP_FECHAR_SESSAO -> { aberta = false; responder(Mtp.OK, tid) }
            Mtp.OP_ARMAZENS -> { mandar(tid, Mtp.Escritor().lista32(listOf(Mtp.ARMAZEM)).bytes()); responder(Mtp.OK, tid) }
            Mtp.OP_INFO_ARMAZEM ->
                if (p[0] != Mtp.ARMAZEM) responder(Mtp.ARMAZEM_INVALIDO, tid)
                else { mandar(tid, Mtp.infoArmazem(loja.total(), loja.livre(), "Armazenamento interno")); responder(Mtp.OK, tid) }
            Mtp.OP_HANDLES, Mtp.OP_QUANTOS -> {
                if (p[0] != Mtp.ARMAZEM && p[0] != Mtp.TODOS) return responder(Mtp.ARMAZEM_INVALIDO, tid)
                // pai 0 = "tudo, em todas as pastas"; pai FFFFFFFF = só a raiz; outro = só aquela pasta
                var lista = (if (p[2] == 0) loja.todos() else loja.filhos(p[2])) ?: return responder(Mtp.PAI_INVALIDO, tid)
                if (p[1] != 0) lista = lista.filter { it.formato == p[1] }
                if (op == Mtp.OP_QUANTOS) return responder(Mtp.OK, tid, lista.size)
                mandar(tid, Mtp.Escritor().lista32(lista.map { it.handle }).bytes()); responder(Mtp.OK, tid)
            }
            Mtp.OP_INFO_OBJETO -> {
                val o = loja.objeto(p[0]) ?: return responder(Mtp.HANDLE_INVALIDO, tid)
                mandar(tid, Mtp.infoObjeto(o)); responder(Mtp.OK, tid)
            }
            Mtp.OP_OBJETO, Mtp.OP_PEDACO -> {
                val f = loja.arquivo(p[0])?.takeIf { it.isFile } ?: return responder(Mtp.HANDLE_INVALIDO, tid)
                if (!f.canRead()) return responder(Mtp.NEGADO, tid)
                val tam = f.length()
                val de = if (op == Mtp.OP_PEDACO) (p[1].toLong() and 0xFFFFFFFFL) else 0L
                if (de > tam) return responder(Mtp.PARAMETRO_INVALIDO, tid)
                val n = if (op == Mtp.OP_PEDACO) minOf(p[2].toLong() and 0xFFFFFFFFL, tam - de) else tam
                if (!mandarArquivo(tid, f, de, n)) return responder(Mtp.CANCELADA, tid)
                if (op == Mtp.OP_PEDACO) responder(Mtp.OK, tid, n.toInt()) else responder(Mtp.OK, tid)
            }
            Mtp.OP_MINIATURA -> responder(Mtp.SEM_MINIATURA, tid)
            Mtp.OP_APAGAR -> responder(loja.apagar(p[0]), tid)
            Mtp.OP_MOVER -> if (p[1] != Mtp.ARMAZEM) responder(Mtp.ARMAZEM_INVALIDO, tid) else responder(loja.mover(p[0], p[2], null), tid)
            Mtp.OP_MANDAR_INFO, Mtp.OP_MANDAR_LISTA_PROPS -> {
                val dados = receberPequeno(tid)
                pendente?.let { loja.desistir(it) }; pendente = null
                if (p[0] != 0 && p[0] != Mtp.ARMAZEM) return responder(Mtp.ARMAZEM_INVALIDO, tid)
                val novo = try {
                    if (op == Mtp.OP_MANDAR_INFO) Mtp.lerInfoObjeto(dados)
                    else Mtp.lerListaProps(dados, p[2], (p[3].toLong() shl 32) or (p[4].toLong() and 0xFFFFFFFFL))
                } catch (_: IOException) { return responder(Mtp.DADOS_INVALIDOS, tid) }
                val paiDito = if (p[1] == 0) Mtp.TODOS else p[1]
                if (novo.formato == Mtp.F_PASTA) {
                    val (cod, h) = loja.criarPasta(p[1], novo.nome)
                    return if (cod == Mtp.OK) responder(cod, tid, Mtp.ARMAZEM, paiDito, h) else responder(cod, tid)
                }
                val (cod, pend) = loja.prepararArquivo(p[1], novo)
                if (pend == null) return responder(cod, tid)
                pendente = pend
                responder(Mtp.OK, tid, Mtp.ARMAZEM, paiDito, pend.handle)
            }
            Mtp.OP_MANDAR_OBJETO -> {
                val pend = pendente
                pendente = null
                if (pend == null) { receber(tid, Long.MAX_VALUE, Nada); return responder(Mtp.SEM_INFO_ANTES, tid) }
                val cod = try {
                    // teto: o tamanho combinado ou, se o computador não disse, o espaço livre de agora
                    loja.receber(pend) { saida -> receber(tid, if (pend.tamanho >= 0) pend.tamanho else loja.livre(), saida) }
                } catch (e: Cancelou) { loja.desistir(pend); throw e
                } catch (e: IOException) { loja.desistir(pend); throw e }
                if (cod != Mtp.OK) loja.desistir(pend)
                responder(cod, tid)
            }
            Mtp.OP_DESC_PROP_APARELHO ->
                if (p[0] != Mtp.PA_NOME) responder(Mtp.PROP_APARELHO_INVALIDA, tid)
                else {
                    mandar(tid, Mtp.Escritor().u16(Mtp.PA_NOME).u16(Mtp.T_TEXTO).u8(0).texto(ap.nome).texto(ap.nome).u8(0).bytes())
                    responder(Mtp.OK, tid)
                }
            Mtp.OP_VALOR_PROP_APARELHO ->
                if (p[0] != Mtp.PA_NOME) responder(Mtp.PROP_APARELHO_INVALIDA, tid)
                else { mandar(tid, Mtp.Escritor().texto(ap.nome).bytes()); responder(Mtp.OK, tid) }
            Mtp.OP_PROPS_DO_FORMATO -> { mandar(tid, Mtp.Escritor().lista16(Mtp.PROPS.keys.toIntArray()).bytes()); responder(Mtp.OK, tid) }
            Mtp.OP_DESC_PROP -> {
                val d = Mtp.descProp(p[0]) ?: return responder(Mtp.PROP_INVALIDA, tid)
                mandar(tid, d); responder(Mtp.OK, tid)
            }
            Mtp.OP_VALOR_PROP -> {
                val o = loja.objeto(p[0]) ?: return responder(Mtp.HANDLE_INVALIDO, tid)
                val w = Mtp.Escritor()
                if (!Mtp.valorProp(w, o, p[1])) return responder(Mtp.PROP_INVALIDA, tid)
                mandar(tid, w.bytes()); responder(Mtp.OK, tid)
            }
            Mtp.OP_MUDAR_PROP -> {
                val dados = receberPequeno(tid)
                if (p[1] != Mtp.P_ARQUIVO) return responder(if (p[1] in Mtp.PROPS) Mtp.NEGADO else Mtp.PROP_INVALIDA, tid)
                val nome = try { Mtp.Leitor(dados).texto() } catch (_: IOException) { return responder(Mtp.DADOS_INVALIDOS, tid) }
                responder(loja.mover(p[0], null, nome), tid)
            }
            Mtp.OP_LISTA_PROPS -> {
                // p: handle, formato, propriedade (FFFFFFFF = todas), grupo, profundidade
                if (p[3] != 0 && p[2] == 0) return responder(Mtp.PARAMETRO_NAO_SUPORTADO, tid)   // por grupo: não temos
                val props = if (p[2] == Mtp.TODOS) Mtp.PROPS.keys.toList()
                    else if (p[2] in Mtp.PROPS) listOf(p[2]) else return responder(Mtp.PROP_INVALIDA, tid)
                var objs: List<Mtp.Objeto> = when {
                    p[4] == 0 && p[0] != 0 && p[0] != Mtp.TODOS -> listOf(loja.objeto(p[0]) ?: return responder(Mtp.HANDLE_INVALIDO, tid))
                    p[4] == 1 -> loja.filhos(p[0]) ?: return responder(Mtp.HANDLE_INVALIDO, tid)
                    (p[4] == 0 || p[4] == Mtp.TODOS) && (p[0] == 0 || p[0] == Mtp.TODOS) -> loja.todos()
                    else -> return responder(Mtp.PROFUNDIDADE_NAO_SUPORTADA, tid)
                }
                if (p[1] != 0) objs = objs.filter { it.formato == p[1] }
                mandar(tid, Mtp.listaProps(objs, props)); responder(Mtp.OK, tid)
            }
            else -> {
                if (fase == 2) receber(tid, Long.MAX_VALUE, Nada)
                responder(Mtp.NAO_SUPORTADA, tid)
            }
        }
    }
}

/**
 * O servidor: aceita conexões na porta 15740 e faz o aperto de mão do PTP/IP. Cada computador abre DUAS conexões:
 * a de comandos (pedidos e arquivos) e a de eventos (por onde ele manda "cancela").
 *
 * [aceitaRede]: a conexão veio da rede de casa? [permitir]: este computador (identificador, nome, IP) pode entrar?
 * Pode demorar (a TV pergunta na tela); devolver false recusa.
 */
class MtpServico(
    private val ss: ServerSocket,
    private val ap: Mtp.Aparelho,
    private val loja: MtpLoja,
    private val aceitaRede: (Socket) -> Boolean,
    private val permitir: (id: String, nome: String, ip: String) -> Boolean,
    private val registro: (String) -> Unit = {},
) {
    companion object {
        const val MAX_CONEXOES = 12
        const val VERSAO = 0x00010000
    }

    private val sessoes = ConcurrentHashMap<Int, MtpSessao>()
    private val numero = AtomicInteger(1)
    private val vivas = AtomicInteger(0)
    private val abertas = ConcurrentHashMap.newKeySet<Socket>()

    /** Fica aceitando até o ServerSocket ser fechado. */
    fun rodar() {
        while (!ss.isClosed) {
            val c = try { ss.accept() } catch (_: IOException) { break }
            if (vivas.get() >= MAX_CONEXOES || !aceitaRede(c)) { try { c.close() } catch (_: IOException) {}; continue }
            vivas.incrementAndGet()
            abertas.add(c)
            Thread({
                try { atender(c) } catch (e: Exception) { if (e !is EOFException && e !is java.net.SocketException) registro("conexão caiu: $e") }
                finally { abertas.remove(c); vivas.decrementAndGet(); try { c.close() } catch (_: IOException) {} }
            }, "Mtp-conexao").apply { isDaemon = true }.start()
        }
    }

    /** Derruba tudo (a chave foi desligada, ou um computador perdeu a permissão). */
    fun parar() {
        try { ss.close() } catch (_: IOException) {}
        for (c in abertas) try { c.close() } catch (_: IOException) {}
    }

    private fun atender(c: Socket) {
        c.tcpNoDelay = true
        c.keepAlive = true
        c.soTimeout = 30_000   // o aperto de mão tem meio minuto; depois dele a conexão pode ficar parada à vontade
        val e = c.getInputStream().buffered(64 * 1024)
        val s = c.getOutputStream().buffered(64 * 1024)
        val (tipo, corpo) = PtpIp.ler(e)
        val l = Mtp.Leitor(corpo)
        when (tipo) {
            PtpIp.INICIO_COMANDO -> {
                val id = l.cru(16).joinToString("") { "%02x".format(it) }
                val nome = l.textoAteZero().filter { it >= ' ' }.ifEmpty { "computador" }
                val ip = c.inetAddress.hostAddress ?: "?"
                c.soTimeout = 0
                if (!permitir(id, nome, ip)) {
                    PtpIp.escrever(s, PtpIp.INICIO_RECUSADO, Mtp.Escritor().u32(1).bytes()); s.flush()
                    registro("recusado: $nome ($ip)")
                    return
                }
                val n = numero.getAndIncrement()
                val sessao = MtpSessao(loja, ap, e, s)
                sessoes[n] = sessao
                try {
                    val w = Mtp.Escritor().u32(n).cru(ap.guid)
                    for (ch in ap.nome.take(40)) w.u16(ch.code)
                    PtpIp.escrever(s, PtpIp.INICIO_COMANDO_OK, w.u16(0).u32(VERSAO).bytes()); s.flush()
                    registro("entrou: $nome ($ip)")
                    sessao.rodar()
                } finally { sessoes.remove(n); registro("saiu: $nome ($ip)") }
            }
            PtpIp.INICIO_EVENTO -> {
                val sessao = sessoes[l.u32()]
                if (sessao == null) { PtpIp.escrever(s, PtpIp.INICIO_RECUSADO, Mtp.Escritor().u32(1).bytes()); s.flush(); return }
                PtpIp.escrever(s, PtpIp.INICIO_EVENTO_OK, ByteArray(0)); s.flush()
                c.soTimeout = 0
                while (true) {
                    val (t, b) = try { PtpIp.ler(e) } catch (_: EOFException) { return }
                    when (t) {
                        PtpIp.CANCELAR -> if (b.size >= 4) sessao.cancelada = Mtp.Leitor(b).u32()
                        PtpIp.SONDA -> { PtpIp.escrever(s, PtpIp.SONDA_OK, ByteArray(0)); s.flush() }
                    }
                }
            }
            else -> throw IOException("primeiro pacote inesperado: $tipo")
        }
    }
}
