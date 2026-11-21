package br.paulo.tvlauncher.panel

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * A parte "pura" da TV como unidade de rede (WebDAV, 14/11/2026; o servidor é o PainelDav.kt). Aqui fica só o que não
 * depende do Android, para os testes JVM (DavLogicaTest) cobrirem: caminhos, cabeçalhos, o XML das respostas, a senha
 * do cabeçalho Basic e o corpo "chunked" que o Finder do Mac manda.
 *
 * WebDAV é o HTTP com alguns métodos a mais (PROPFIND = listar, MKCOL = criar pasta, MOVE, COPY, LOCK...). Windows
 * ("Mapear unidade de rede"), Mac ("Conectar ao Servidor"), Linux (davs://) e vários apps de arquivos do Android já
 * falam isso sem instalar nada: por isso foi o escolhido para a TV aparecer "como um disco" (SMB precisaria da porta
 * 445, proibida para apps; ver docs/unidade-de-rede.md).
 */
object DavLogica {
    /** Tudo do WebDAV mora debaixo deste caminho: o resto da porta continua sendo o painel. */
    const val PREFIXO = "/dav"

    /** Métodos que respondemos (vai no cabeçalho Allow do OPTIONS). */
    const val METODOS = "OPTIONS, GET, HEAD, PUT, DELETE, PROPFIND, PROPPATCH, MKCOL, MOVE, COPY, LOCK, UNLOCK"

    /** Pasta grande demais: o PROPFIND lista só os primeiros (o XML inteiro fica na memória da TV). */
    const val MAX_ITENS = 5000

    /** O caminho do pedido é do WebDAV? "/dav", "/dav/" e "/dav/..." sim; "/davi" não. */
    fun ehDav(caminho: String): Boolean = caminho == PREFIXO || caminho.startsWith("$PREFIXO/")

    /**
     * Caminho do pedido ("/dav/Download/F%C3%A9rias.mp4") -> caminho relativo à memória interna ("Download/Férias.mp4").
     * null = inválido ("..", barra invertida, %xx quebrado, nome proibido). "" = a raiz.
     *
     * O caminho chega como texto ISO-8859-1 (é assim que o PainelHttp lê a linha do pedido). Alguns clientes mandam o
     * UTF-8 cru, sem %xx: voltando aos bytes antes de decodificar, os dois jeitos dão o mesmo nome.
     */
    fun relativo(caminho: String): String? {
        if (!ehDav(caminho)) return null
        val bytes = decodificarPorcento(caminho.removePrefix(PREFIXO).toByteArray(Charsets.ISO_8859_1)) ?: return null
        val texto = utf8Estrito(bytes) ?: return null
        // "//" no meio vira um pedaço vazio, que o caminhoRelativoValido recusa; nas pontas a barra só é aparada.
        val limpo = texto.trim('/')
        return if (ChecagensPainel.caminhoRelativoValido(limpo)) limpo else null
    }

    /** %xx -> byte. null se um % não vier seguido de dois dígitos hexadecimais. */
    private fun decodificarPorcento(b: ByteArray): ByteArray? {
        val saida = ByteArrayOutputStream(b.size)
        var i = 0
        while (i < b.size) {
            val c = b[i].toInt() and 0xff
            if (c == '%'.code) {
                if (i + 2 >= b.size) return null
                val alto = Character.digit(b[i + 1].toInt(), 16)
                val baixo = Character.digit(b[i + 2].toInt(), 16)
                if (alto < 0 || baixo < 0) return null
                saida.write(alto * 16 + baixo)
                i += 3
            } else { saida.write(c); i++ }
        }
        return saida.toByteArray()
    }

    /** Bytes -> texto UTF-8, recusando sequência inválida (o decodificador comum troca por "?" calado). */
    private fun utf8Estrito(b: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(b)).toString()
    } catch (e: java.nio.charset.CharacterCodingException) { null }

    /**
     * Cabeçalho Destination do MOVE/COPY -> caminho relativo, ou null. Vem como URL inteira
     * ("https://tv-sala:8444/dav/Novo%20nome.mp4") ou só o caminho. Com URL, o nome e a porta têm de ser os mesmos do
     * Host do pedido: mover "para outro servidor" não existe aqui.
     */
    fun destino(cabecalho: String?, host: String?): String? {
        val d = cabecalho?.trim() ?: return null
        val caminho = when {
            d.startsWith("/") -> d
            d.startsWith("https://", ignoreCase = true) || d.startsWith("http://", ignoreCase = true) -> {
                val semEsquema = d.substringAfter("://")
                val barra = semEsquema.indexOf('/')
                if (barra < 0) return null
                val autoridade = semEsquema.substring(0, barra)
                if (host == null || !autoridade.equals(host, ignoreCase = true)) return null
                semEsquema.substring(barra)
            }
            else -> return null
        }
        return relativo(caminho.substringBefore('?'))
    }

    /** Depth do PROPFIND: "0" só o item; "1" o item e o que tem dentro. "infinity" (ou nada) vale como 1. */
    fun profundidade(cabecalho: String?): Int = if (cabecalho?.trim() == "0") 0 else 1

    /** Overwrite do MOVE/COPY: "F" = não sobrescrever. O padrão (sem cabeçalho) é sobrescrever. */
    fun sobrescrever(cabecalho: String?): Boolean = !cabecalho.equals("F", ignoreCase = true)

    // ---------------------------------------------------------------- senha (HTTP Basic)

    /**
     * A senha de um cabeçalho "Authorization: Basic <base64 de usuario:senha>". O usuário não importa (qualquer um
     * serve, "tv" por exemplo): a senha é o PIN do painel. null se não for Basic ou estiver malformado.
     */
    fun senhaBasic(cabecalho: String?): String? {
        val h = cabecalho?.trim() ?: return null
        if (!h.startsWith("Basic ", ignoreCase = true)) return null
        val texto = try { String(Base64.getDecoder().decode(h.substring(6).trim()), Charsets.UTF_8) } catch (e: IllegalArgumentException) { return null }
        val dois = texto.indexOf(':')
        return if (dois < 0) null else texto.substring(dois + 1)
    }

    /**
     * Lembra, por pouco tempo, os cabeçalhos Authorization já conferidos. Motivo: o Windows e o Finder mandam a senha
     * em TODO pedido (abrir uma pasta são dezenas), e conferir o PIN é um PBKDF2 de propósito lento (~0,3 s na TV).
     * Guarda só o SHA-256 do cabeçalho (nunca o PIN). Os errados também ficam lembrados: um cliente que repete sozinho
     * a mesma senha errada conta UMA tentativa, não vinte (o que desligaria o painel); cada senha diferente conta.
     * [limpar] quando o PIN muda ou o painel recomeça.
     */
    class CacheSenha(private val relogio: () -> Long, private val validadeMs: Long = 15 * 60_000L, private val maximo: Int = 32) {
        private val certos = LinkedHashMap<String, Long>()
        private val errados = LinkedHashMap<String, Long>()

        @Synchronized fun certo(cabecalho: String): Boolean = vale(certos, cabecalho)
        @Synchronized fun errado(cabecalho: String): Boolean = vale(errados, cabecalho)
        @Synchronized fun lembrarCerto(cabecalho: String) = guardar(certos, cabecalho)
        @Synchronized fun lembrarErrado(cabecalho: String) = guardar(errados, cabecalho)
        @Synchronized fun limpar() { certos.clear(); errados.clear() }

        private fun vale(m: LinkedHashMap<String, Long>, c: String): Boolean {
            val k = chave(c)
            val ate = m[k] ?: return false
            if (relogio() > ate) { m.remove(k); return false }
            return true
        }
        private fun guardar(m: LinkedHashMap<String, Long>, c: String) {
            m.remove(chave(c))
            while (m.size >= maximo) m.remove(m.keys.first())
            m[chave(c)] = relogio() + validadeMs
        }
        private fun chave(c: String): String =
            MessageDigest.getInstance("SHA-256").digest(c.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------- XML das respostas

    /** Um arquivo ou pasta para o PROPFIND. [rel] é o caminho relativo ("" = a raiz). */
    data class Item(val rel: String, val pasta: Boolean, val tamanho: Long, val modificado: Long)

    /** O href de [rel]: "/dav/" + cada pedaço com %xx; pasta termina em "/". */
    fun href(rel: String, pasta: Boolean): String {
        val corpo = rel.split('/').filter { it.isNotEmpty() }.joinToString("/") { codificar(it) }
        val base = if (corpo.isEmpty()) "$PREFIXO/" else "$PREFIXO/$corpo"
        return if (pasta && !base.endsWith("/")) "$base/" else base
    }

    /** Um pedaço de caminho com %xx em tudo que não é letra, número ou - . _ ~ (RFC 3986, "unreserved"). */
    fun codificar(nome: String): String {
        val sb = StringBuilder()
        for (b in nome.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if (c in 'a'.code..'z'.code || c in 'A'.code..'Z'.code || c in '0'.code..'9'.code || c.toChar() in "-._~") sb.append(c.toChar())
            else sb.append('%').append("%02X".format(c))
        }
        return sb.toString()
    }

    fun escaparXml(t: String): String {
        val sb = StringBuilder(t.length + 16)
        for (ch in t) when (ch) {
            '&' -> sb.append("&amp;"); '<' -> sb.append("&lt;"); '>' -> sb.append("&gt;")
            '"' -> sb.append("&quot;"); '\'' -> sb.append("&apos;")
            else -> sb.append(ch)
        }
        return sb.toString()
    }

    /** Data no formato do HTTP (RFC 1123), sempre em GMT e em inglês: "Sat, 14 Nov 2026 19:50:29 GMT". */
    fun dataHttp(ms: Long): String = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("GMT") }.format(Date(ms))

    /** Data RFC 1123 -> milissegundos, ou null. Para o PROPPATCH do Windows, que manda a data do arquivo original. */
    fun lerDataHttp(t: String?): Long? {
        val texto = t?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return try {
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US).apply { timeZone = TimeZone.getTimeZone("GMT"); isLenient = false }
                .parse(texto)?.time
        } catch (e: java.text.ParseException) { null }
    }

    /** Data ISO 8601 (creationdate do WebDAV): "2026-11-14T19:50:29Z". */
    fun dataIso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(ms))

    /** ETag fraco o bastante para o cliente saber se o arquivo mudou: tamanho + hora. */
    fun etag(i: Item): String = "\"${java.lang.Long.toHexString(i.tamanho)}-${java.lang.Long.toHexString(i.modificado)}\""

    /** Tipo do arquivo pela extensão (só informativo no PROPFIND). */
    fun tipo(nome: String): String = PainelHttp.tipoPrevia(nome)?.substringBefore(';') ?: "application/octet-stream"

    /**
     * A resposta 207 do PROPFIND: as propriedades de cada item. Devolvemos sempre o mesmo conjunto (o que Windows,
     * Finder, davfs2 e os apps de Android usam), qualquer que seja a pergunta; a RFC 4918 permite. [livre] e [usado]
     * vão no primeiro item (a pasta pedida): é por elas que o Windows mostra o espaço livre da "unidade".
     */
    fun multistatus(itens: List<Item>, livre: Long?, usado: Long?): String {
        val sb = StringBuilder(256 + itens.size * 600)
        sb.append("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n")
        itens.forEachIndexed { n, i ->
            val nome = i.rel.substringAfterLast('/')
            sb.append("<D:response><D:href>").append(escaparXml(href(i.rel, i.pasta))).append("</D:href><D:propstat><D:prop>")
            sb.append("<D:displayname>").append(escaparXml(if (i.rel.isEmpty()) "TV" else nome)).append("</D:displayname>")
            if (i.pasta) sb.append("<D:resourcetype><D:collection/></D:resourcetype>")
            else {
                sb.append("<D:resourcetype/>")
                sb.append("<D:getcontentlength>").append(i.tamanho).append("</D:getcontentlength>")
                sb.append("<D:getcontenttype>").append(escaparXml(tipo(nome))).append("</D:getcontenttype>")
                sb.append("<D:getetag>").append(escaparXml(etag(i))).append("</D:getetag>")
            }
            sb.append("<D:getlastmodified>").append(dataHttp(i.modificado)).append("</D:getlastmodified>")
            sb.append("<D:creationdate>").append(dataIso(i.modificado)).append("</D:creationdate>")
            sb.append("<D:supportedlock><D:lockentry><D:lockscope><D:exclusive/></D:lockscope><D:locktype><D:write/></D:locktype></D:lockentry></D:supportedlock>")
            if (n == 0 && i.pasta && livre != null) {
                sb.append("<D:quota-available-bytes>").append(livre).append("</D:quota-available-bytes>")
                sb.append("<D:quota-used-bytes>").append(usado ?: 0).append("</D:quota-used-bytes>")
            }
            sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response>\n")
        }
        sb.append("</D:multistatus>\n")
        return sb.toString()
    }

    /** PROPFIND na raiz do servidor ("/"): uma pasta, e com Depth 1 também a "/dav/" dentro dela. */
    fun multistatusRaiz(profundidade: Int, agora: Long): String {
        val dav = multistatus(listOf(Item("", true, 0, agora)), null, null)
        val corpo = dav.substringAfter("xmlns:D=\"DAV:\">\n").substringBefore("</D:multistatus>")
        val raiz = corpo.replaceFirst("<D:href>$PREFIXO/</D:href>", "<D:href>/</D:href>")
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\">\n" + raiz +
            (if (profundidade == 1) corpo else "") + "</D:multistatus>\n"
    }

    /**
     * Resposta do LOCK. Não guardamos trava nenhuma (a TV é de uma casa, não de um escritório): o Windows e o Finder
     * só se recusam a GRAVAR num servidor sem LOCK, então respondemos que travou. [token] = "opaquelocktoken:<uuid>".
     */
    fun lockXml(token: String, rel: String, pasta: Boolean, dono: String?, segundos: Int): String =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:prop xmlns:D=\"DAV:\"><D:lockdiscovery><D:activelock>" +
            "<D:locktype><D:write/></D:locktype><D:lockscope><D:exclusive/></D:lockscope><D:depth>0</D:depth>" +
            (if (dono != null) "<D:owner>${escaparXml(dono)}</D:owner>" else "") +
            "<D:timeout>Second-$segundos</D:timeout><D:locktoken><D:href>${escaparXml(token)}</D:href></D:locktoken>" +
            "<D:lockroot><D:href>${escaparXml(href(rel, pasta))}</D:href></D:lockroot>" +
            "</D:activelock></D:lockdiscovery></D:prop>\n"

    /** O texto de dentro de <owner> do pedido de LOCK (só para devolver igual), sem marcação, até 200 letras. */
    fun donoDoLock(xml: String): String? {
        val m = Regex("<(?:[A-Za-z0-9_.-]+:)?owner[^>]*>(.*?)</(?:[A-Za-z0-9_.-]+:)?owner>", RegexOption.DOT_MATCHES_ALL).find(xml) ?: return null
        return m.groupValues[1].replace(Regex("<[^>]*>"), "").trim().take(200).ifEmpty { null }
    }

    /** Uma propriedade pedida no PROPPATCH: o namespace (ex.: "urn:schemas-microsoft-com:") e o nome. */
    data class Prop(val ns: String, val nome: String, val valor: String)

    /**
     * As propriedades de um PROPPATCH (o Windows manda as datas do arquivo original depois de copiar, e o Finder
     * algumas suas). Leitura simples, sem parser de XML: os elementos filhos de cada <prop>, com o namespace pelo
     * prefixo declarado. Até 32 propriedades.
     */
    fun propsDoProppatch(xml: String): List<Prop> {
        val ns = HashMap<String, String>()
        Regex("xmlns(?::([A-Za-z0-9_.-]+))?\\s*=\\s*\"([^\"]*)\"").findAll(xml).forEach { ns[it.groupValues[1]] = it.groupValues[2] }
        val lista = ArrayList<Prop>()
        val blocos = Regex("<(?:[A-Za-z0-9_.-]+:)?prop(?:\\s[^>]*)?>(.*?)</(?:[A-Za-z0-9_.-]+:)?prop>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
        val elemento = Regex("<(?:([A-Za-z0-9_.-]+):)?([A-Za-z_][A-Za-z0-9_.-]*)(\\s[^>]*?)?(/>|>(.*?)</(?:[A-Za-z0-9_.-]+:)?\\2\\s*>)", RegexOption.DOT_MATCHES_ALL)
        for (b in blocos) for (e in elemento.findAll(b.groupValues[1])) {
            if (lista.size >= 32) return lista
            val prefixo = e.groupValues[1]
            // xmlns declarado no próprio elemento (o Windows às vezes faz assim).
            val proprio = Regex("xmlns(?::([A-Za-z0-9_.-]+))?\\s*=\\s*\"([^\"]*)\"").findAll(e.groupValues[3])
                .firstOrNull { it.groupValues[1] == prefixo }?.groupValues?.get(2)
            lista.add(Prop(proprio ?: ns[prefixo] ?: "", e.groupValues[2], e.groupValues[5].trim()))
        }
        return lista
    }

    /** A resposta 207 do PROPPATCH: tudo "200 OK" (aceitamos; só a data de modificação é aplicada de verdade). */
    fun proppatchXml(rel: String, pasta: Boolean, props: List<Prop>): String {
        val sb = StringBuilder("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<D:multistatus xmlns:D=\"DAV:\"><D:response><D:href>")
        sb.append(escaparXml(href(rel, pasta))).append("</D:href><D:propstat><D:prop>")
        props.forEachIndexed { n, p -> sb.append("<P$n:${p.nome} xmlns:P$n=\"${escaparXml(p.ns)}\"/>") }
        sb.append("</D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat></D:response></D:multistatus>\n")
        return sb.toString()
    }

    /** Data de modificação que o cliente pediu no PROPPATCH (Windows: Win32LastModifiedTime; outros: getlastmodified). */
    fun dataPedida(props: List<Prop>): Long? = props.firstOrNull {
        it.nome.equals("Win32LastModifiedTime", ignoreCase = true) || it.nome == "getlastmodified" || it.nome == "lastmodified"
    }?.let { lerDataHttp(it.valor) }

    /** Página simples ao abrir uma pasta no navegador (sem script; nomes escapados). */
    fun paginaPasta(rel: String, itens: List<Item>): String {
        val sb = StringBuilder("<!doctype html><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\">")
        sb.append("<title>").append(escaparXml("TV/" + rel)).append("</title><h1>").append(escaparXml("TV/" + rel)).append("</h1><ul>")
        if (rel.isNotEmpty()) sb.append("<li><a href=\"").append(escaparXml(href(rel.substringBeforeLast('/', ""), true))).append("\">..</a></li>")
        for (i in itens) {
            val nome = i.rel.substringAfterLast('/')
            sb.append("<li><a href=\"").append(escaparXml(href(i.rel, i.pasta))).append("\">")
                .append(escaparXml(nome + if (i.pasta) "/" else "")).append("</a></li>")
        }
        return sb.append("</ul>").toString()
    }

    /**
     * Nome que não aparece na listagem: o arquivo temporário de um envio em andamento (".nome.123.parcial", ver
     * PainelArquivos.receber). Se aparecesse, o Windows copiaria e mostraria um arquivo pela metade.
     */
    fun escondido(nome: String): Boolean = nome.startsWith(".") && nome.endsWith(".parcial")

    // ---------------------------------------------------------------- corpo "chunked"

    /**
     * Lê um corpo "Transfer-Encoding: chunked" (o Finder do Mac grava assim: manda pedaços com o tamanho em hexa na
     * frente, sem saber o total). Devolve só os dados, até o pedaço de tamanho 0. Linha de tamanho grande demais ou
     * malformada: IOException (a conexão fecha e o arquivo parcial é apagado).
     */
    class LeitorChunked(private val dentro: InputStream) : InputStream() {
        private var faltamNoPedaco = 0L
        private var fim = false

        override fun read(): Int {
            val um = ByteArray(1)
            return if (read(um, 0, 1) < 0) -1 else um[0].toInt() and 0xff
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (fim) return -1
            if (len == 0) return 0
            if (faltamNoPedaco == 0L) {
                faltamNoPedaco = lerTamanho()
                if (faltamNoPedaco == 0L) { pularTrailers(); fim = true; return -1 }
            }
            val n = dentro.read(b, off, minOf(len.toLong(), faltamNoPedaco).toInt())
            if (n < 0) throw IOException("corpo chunked incompleto")
            faltamNoPedaco -= n
            if (faltamNoPedaco == 0L) fimDeLinha()
            return n
        }

        /** "1a2b;extensao\r\n" -> 0x1a2b. */
        private fun lerTamanho(): Long {
            val linha = lerLinha()
            val hexa = linha.substringBefore(';').trim()
            if (hexa.isEmpty() || hexa.length > 12 || !hexa.all { Character.digit(it, 16) >= 0 }) throw IOException("tamanho de pedaço inválido")
            return hexa.toLong(16)
        }

        private fun fimDeLinha() { if (lerLinha().isNotEmpty()) throw IOException("pedaço sem \\r\\n no fim") }

        /** Depois do pedaço 0 podem vir cabeçalhos ("trailers") até uma linha vazia: lidos e ignorados (no máximo 32). */
        private fun pularTrailers() { repeat(32) { if (lerLinha().isEmpty()) return }; throw IOException("trailers demais") }

        private fun lerLinha(): String {
            val sb = StringBuilder()
            while (true) {
                val c = dentro.read()
                if (c < 0) throw IOException("corpo chunked incompleto")
                if (c == '\n'.code) return sb.toString().trimEnd('\r')
                if (sb.length > 256) throw IOException("linha de pedaço grande demais")
                sb.append(c.toChar())
            }
        }
    }
}
