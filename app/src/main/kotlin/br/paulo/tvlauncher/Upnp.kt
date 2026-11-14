package br.paulo.tvlauncher

import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Mídias da rede, etapa 1 (07/11/2026): servidores UPnP/DLNA da casa (NAS, Plex, Jellyfin, Kodi, Windows Media Player).
 *
 * O caminho é sempre o mesmo, em três passos:
 *  1. [procurar]: um grito na rede ("M-SEARCH" no endereço de grupo 239.255.255.250:1900, protocolo SSDP) e cada servidor
 *     responde com o endereço da sua ficha (cabeçalho LOCATION).
 *  2. [ficha]: a ficha é um XML com o nome do aparelho e o endereço do serviço "ContentDirectory" (o índice de pastas).
 *  3. [navegar]: um pedido SOAP "Browse" por pasta. A resposta traz outro XML (DIDL-Lite) com as subpastas e os arquivos,
 *     cada arquivo com o endereço http de onde tocar.
 *
 * As partes que só leem texto (cabeçalhos, XML) são puras e têm teste JVM (UpnpTest). Os XML vêm de aparelhos da rede,
 * então são DADOS NÃO CONFIÁVEIS: o leitor recusa DOCTYPE (sem entidades externas, o ataque "XXE") e o tamanho é limitado.
 */
object Upnp {
    const val ST_PASTAS = "urn:schemas-upnp-org:service:ContentDirectory:1"
    private const val GRUPO = "239.255.255.250"
    private const val PORTA = 1900
    private const val MAX_BYTES = 4 * 1024 * 1024   // 4 MB por resposta: pasta com milhares de arquivos ainda cabe

    data class Servidor(val nome: String, val pastas: String)   // pastas = endereço completo do ContentDirectory
    data class Entrada(val id: String, val titulo: String, val pasta: Boolean, val url: String?, val tipo: Tipo, val capa: String?,
        /** Sinopse/resumo do servidor (Jellyfin, Emby, Plex), para a ficha ao lado da lista (ideia 29). */
        val sinopse: String? = null)
    enum class Tipo { PASTA, VIDEO, AUDIO, FOTO, OUTRO }

    // ---------------------------------------------------------------- 1. procurar (rede)

    /** Grita na rede e junta os endereços das fichas que responderem em [esperaMs]. Precisa do MulticastLock (quem chama). */
    fun procurar(esperaMs: Int = 3000): List<String> {
        val pedido = ("M-SEARCH * HTTP/1.1\r\nHOST: $GRUPO:$PORTA\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $ST_PASTAS\r\n\r\n").toByteArray()
        val achados = LinkedHashSet<String>()
        DatagramSocket().use { s ->
            s.soTimeout = 400
            val grupo = InetAddress.getByName(GRUPO)
            repeat(2) { s.send(DatagramPacket(pedido, pedido.size, grupo, PORTA)) }   // UDP perde pacote: manda duas vezes
            val fim = System.currentTimeMillis() + esperaMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < fim) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                localDaResposta(String(p.data, 0, p.length, Charsets.UTF_8))?.let { achados += it }
            }
        }
        return achados.toList()
    }

    /** O LOCATION de uma resposta SSDP (só http, só da rede local por conta de quem responde). */
    fun localDaResposta(texto: String): String? = texto.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("LOCATION:", ignoreCase = true) }
        ?.substringAfter(':')?.trim()
        ?.takeIf { it.startsWith("http://") }

    // ---------------------------------------------------------------- 2. ficha

    fun ficha(local: String): Servidor? = runCatching { lerFicha(baixar(local), local) }.getOrNull()

    /** Nome e endereço do ContentDirectory a partir do XML da ficha. null se o aparelho não tem pastas. */
    fun lerFicha(xml: ByteArray, local: String): Servidor? {
        val doc = documento(xml)
        val base = texto(doc.documentElement, "URLBase")?.takeIf { it.startsWith("http") } ?: local
        val nome = texto(doc.documentElement, "friendlyName") ?: URL(local).host
        val servicos = doc.getElementsByTagNameNS("*", "service")
        for (i in 0 until servicos.length) {
            val s = servicos.item(i) as Element
            val tipo = texto(s, "serviceType") ?: continue
            if (!tipo.startsWith("urn:schemas-upnp-org:service:ContentDirectory:")) continue
            val controle = texto(s, "controlURL") ?: continue
            return Servidor(nome, URL(URL(base), controle).toString())
        }
        return null
    }

    // ---------------------------------------------------------------- 3. navegar

    /** Filhos da pasta [id] ("0" = raiz). Pede de 500 em 500 até acabar (ou até [limite], para não travar num NAS gigante). */
    fun navegar(s: Servidor, id: String, limite: Int = 5000): List<Entrada> {
        val todos = ArrayList<Entrada>()
        var inicio = 0
        while (todos.size < limite) {
            val resposta = soap(s.pastas, corpoNavegar(id, inicio, 500))
            val (didl, total) = lerRespostaNavegar(resposta)
            val pagina = lerDidl(didl)
            todos += pagina
            inicio += pagina.size
            if (pagina.isEmpty() || inicio >= total) break
        }
        return todos
    }

    fun corpoNavegar(id: String, inicio: Int, quantos: Int): String =
        """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/" s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            """<s:Body><u:Browse xmlns:u="$ST_PASTAS">""" +
            "<ObjectID>${escapar(id)}</ObjectID><BrowseFlag>BrowseDirectChildren</BrowseFlag><Filter>*</Filter>" +
            "<StartingIndex>$inicio</StartingIndex><RequestedCount>$quantos</RequestedCount><SortCriteria></SortCriteria>" +
            "</u:Browse></s:Body></s:Envelope>"

    /** O DIDL (que vem como texto dentro do SOAP) e o total de filhos que a pasta diz ter. */
    fun lerRespostaNavegar(xml: ByteArray): Pair<String, Int> {
        val doc = documento(xml)
        val didl = texto(doc.documentElement, "Result").orEmpty()
        val total = texto(doc.documentElement, "TotalMatches")?.toIntOrNull() ?: 0
        return didl to total
    }

    /** Subpastas primeiro (na ordem do servidor), depois os arquivos. Arquivo sem endereço http fica de fora. */
    fun lerDidl(didl: String): List<Entrada> {
        if (didl.isBlank()) return emptyList()
        val raiz = documento(didl.toByteArray(Charsets.UTF_8)).documentElement
        val pastas = ArrayList<Entrada>()
        val arquivos = ArrayList<Entrada>()
        var n: Node? = raiz.firstChild
        while (n != null) {
            if (n is Element) {
                val id = n.getAttribute("id")
                val titulo = texto(n, "title") ?: "?"
                when (n.localName ?: n.nodeName.substringAfter(':')) {
                    "container" -> pastas += Entrada(id, titulo, true, null, Tipo.PASTA, capa(n))
                    "item" -> {
                        val classe = texto(n, "class").orEmpty()
                        val url = melhorRes(n)
                        if (url != null) arquivos += Entrada(id, titulo, false, url, tipoDaClasse(classe), capa(n))
                    }
                }
            }
            n = n.nextSibling
        }
        return pastas + arquivos
    }

    fun tipoDaClasse(c: String): Tipo = when {
        c.startsWith("object.item.videoItem") -> Tipo.VIDEO
        c.startsWith("object.item.audioItem") -> Tipo.AUDIO
        c.startsWith("object.item.imageItem") -> Tipo.FOTO
        else -> Tipo.OUTRO
    }

    /** O primeiro <res> http. (Servidores mandam vários: o original e versões convertidas; o 1º costuma ser o original.) */
    private fun melhorRes(item: Element): String? {
        val res = item.getElementsByTagNameNS("*", "res")
        for (i in 0 until res.length) {
            val u = res.item(i).textContent.trim()
            if (u.startsWith("http://") || u.startsWith("https://")) return u
        }
        return null
    }

    private fun capa(e: Element): String? = texto(e, "albumArtURI")?.takeIf { it.startsWith("http") }

    // ---------------------------------------------------------------- XML e HTTP

    private fun documento(xml: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Sem DOCTYPE = sem entidades externas nem "bilhão de risadas": o XML vem de qualquer aparelho da rede.
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(conferirXml(xml)))

    /**
     * O parser do Android ignora o setFeature acima: recusar aqui também. Antes olhava só os 2048 primeiros bytes, e um
     * comentário grande antes do DOCTYPE (ou o XML em UTF-16, com um byte zero entre as letras) passava. Agora olha o
     * arquivo inteiro (já limitado a MAX_BYTES) e ignora os bytes zero, assim "<!DOCTYPE" em UTF-16/UTF-32 também aparece.
     */
    internal fun semDoctype(xml: ByteArray, texto: String = semZeros(xml)): ByteArray {
        require(!texto.contains("<!DOCTYPE", ignoreCase = true) && !texto.contains("<!ENTITY", ignoreCase = true)) { "DOCTYPE recusado" }
        return xml
    }

    /**
     * Tudo que o XML de fora passa antes do leitor: [semDoctype] e o teto de níveis. Fuzz de 10/11/2026: milhares de
     * elementos um dentro do outro estouravam a pilha (StackOverflowError) no textContent, e o leitor de XML do Android
     * monta a árvore de forma recursiva. Recusa antes de ler, com o mesmo IllegalArgumentException do DOCTYPE.
     */
    internal fun conferirXml(xml: ByteArray): ByteArray {
        val texto = semZeros(xml)
        semDoctype(xml, texto)
        require(niveis(texto) <= MAX_NIVEIS) { "XML fundo demais" }
        return xml
    }

    /** Bytes lidos como ISO-8859-1, sem os bytes zero (assim "<!DOCTYPE" em UTF-16/UTF-32 também aparece). */
    private fun semZeros(xml: ByteArray): String {
        val sb = StringBuilder(xml.size)
        for (b in xml) if (b.toInt() != 0) sb.append((b.toInt() and 0xFF).toChar())
        return sb.toString()
    }

    /** XML de verdade (ficha, SOAP, DIDL) tem poucos níveis; 100 sobra muito. */
    private const val MAX_NIVEIS = 100

    /**
     * Maior número de elementos abertos um dentro do outro. Conta "<nome" (menos o "<nome/>", que fecha na hora) e
     * desconta "</"; pula comentário, CDATA, "<?" e "<!". Aspas dentro da tag não confundem (um ">" no valor de um
     * atributo é permitido). Para assim que passa do teto: não precisa olhar o resto.
     */
    internal fun niveis(t: String): Int {
        var nivel = 0
        var maior = 0
        var i = 0
        fun pularAte(fim: String, de: Int): Int = t.indexOf(fim, de).let { if (it < 0) t.length else it + fim.length }
        while (i < t.length) {
            if (t[i] != '<') { i++; continue }
            when {
                t.startsWith("<!--", i) -> i = pularAte("-->", i + 4)
                t.startsWith("<![CDATA[", i) -> i = pularAte("]]>", i + 9)
                t.startsWith("<?", i) || t.startsWith("<!", i) -> i = pularAte(">", i + 2)
                t.startsWith("</", i) -> { nivel--; i = pularAte(">", i + 2) }
                else -> {
                    var j = i + 1
                    var aspa = ' '
                    while (j < t.length) {
                        val c = t[j]
                        if (aspa != ' ') { if (c == aspa) aspa = ' ' } else if (c == '"' || c == '\'') aspa = c else if (c == '>') break
                        j++
                    }
                    if (!(j < t.length && t[j - 1] == '/')) {
                        nivel++
                        if (nivel > maior) maior = nivel
                        if (maior > MAX_NIVEIS) return maior
                    }
                    i = j + 1
                }
            }
        }
        return maior
    }

    /** Texto do 1º elemento com esse nome (qualquer namespace) dentro de [e]. */
    private fun texto(e: Element, nome: String): String? {
        val l = e.getElementsByTagNameNS("*", nome)
        return if (l.length == 0) null else l.item(0).textContent.trim().ifEmpty { null }
    }

    private fun escapar(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun baixar(url: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 8000
        return c.inputStream.use { limitado(it) }.also { c.disconnect() }
    }

    private fun soap(url: String, corpo: String): ByteArray {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 4000; c.readTimeout = 15000
        c.requestMethod = "POST"; c.doOutput = true
        c.setRequestProperty("Content-Type", "text/xml; charset=\"utf-8\"")
        c.setRequestProperty("SOAPACTION", "\"$ST_PASTAS#Browse\"")
        c.outputStream.use { it.write(corpo.toByteArray(Charsets.UTF_8)) }
        return c.inputStream.use { limitado(it) }.also { c.disconnect() }
    }

    private fun limitado(i: InputStream): ByteArray {
        val saida = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = i.read(buf)
            if (n < 0) break
            saida.write(buf, 0, n)
            require(saida.size() <= MAX_BYTES) { "resposta grande demais" }
        }
        return saida.toByteArray()
    }
}
