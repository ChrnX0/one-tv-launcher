package br.paulo.tvlauncher

import org.w3c.dom.Element
import java.io.BufferedInputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URI
import java.net.URLDecoder
import java.util.Locale
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.xml.parsers.DocumentBuilderFactory

/**
 * WebDAV e Nextcloud (10/11/2026): pastas na nuvem da casa (Nextcloud, ownCloud, NAS Synology/QNAP com WebDAV ligado).
 *
 *  - Listar uma pasta é um pedido "PROPFIND" com "Depth: 1". O HttpURLConnection do Android recusa esse método (só aceita
 *    GET, POST, PUT...), então este arquivo fala HTTP/1.1 direto num soquete ([pedidoCru]). Com https, o certificado E o
 *    nome do servidor são conferidos, como em qualquer pedido do app (nunca desligar a verificação).
 *  - Tocar é um GET comum com o cabeçalho "Authorization: Basic"; quem toca é o player (Media3), com o cabeçalho que
 *    [FontesMidia] monta na hora. O endereço que circula é webdav://<id da conta>/<caminho>, sem a senha.
 *
 * Partes puras com teste JVM (WebDavTest): leitura da resposta HTTP (inclusive "chunked") e do XML "multistatus".
 */
object WebDav {
    private const val MAX_BYTES = 8 * 1024 * 1024

    private const val CORPO_PROPFIND = """<?xml version="1.0" encoding="utf-8"?>""" +
        """<d:propfind xmlns:d="DAV:"><d:prop><d:displayname/><d:resourcetype/><d:getcontenttype/></d:prop></d:propfind>"""

    data class Resposta(val codigo: Int, val cabecalhos: Map<String, String>, val corpo: ByteArray)

    // ---------------------------------------------------------------- listar

    /** Filhos de [caminho] ("" = a pasta base da conta). Pastas primeiro, em ordem alfabética. */
    fun listar(c: ContaMidia, caminho: String): List<Upnp.Entrada> {
        val base = URI(c.servidor)
        val alvo = if (caminho.isEmpty()) caminhoBase(c) + "/" else caminho
        require(caminhoPermitido(c, alvo)) { "caminho fora da conta" }
        val r = pedidoCru("PROPFIND", origem(base) + alvo, cabecalhoAuth(c), mapOf("Depth" to "1",
            "Content-Type" to "application/xml; charset=utf-8"), CORPO_PROPFIND.toByteArray())
        if (r.codigo == 401) throw SecurityException("401")
        if (r.codigo == 404) throw IOException("BAD_NETWORK_NAME")
        if (r.codigo != 207) throw IOException("http ${r.codigo}")
        return lerMultistatus(r.corpo, c, alvo)
    }

    /** O caminho (já codificado, como o servidor o escreve) da pasta base da conta, sem a barra do fim. */
    fun caminhoBase(c: ContaMidia): String = URI(c.servidor).rawPath.orEmpty().trimEnd('/')

    /** Só caminhos debaixo da pasta da conta: o servidor não consegue nos mandar ler outro lugar. */
    fun caminhoPermitido(c: ContaMidia, caminho: String): Boolean {
        if (!caminho.startsWith("/") || caminho.contains("://") || caminho.length > 2048) return false
        val decod = runCatching { URLDecoder.decode(caminho.replace("+", "%2B"), "UTF-8") }.getOrNull() ?: return false
        if (decod.split('/').any { it == ".." } || decod.any { it.code < 32 }) return false
        return caminho == caminhoBase(c) + "/" || caminho.startsWith(caminhoBase(c) + "/")
    }

    /** "https://nuvem:443" (sem caminho). */
    fun origem(u: URI): String = u.scheme + "://" + u.rawAuthority

    fun lerMultistatus(xml: ByteArray, c: ContaMidia, pedido: String): List<Upnp.Entrada> {
        val doc = documento(xml)
        val respostas = doc.getElementsByTagNameNS("DAV:", "response")
        val pastas = ArrayList<Upnp.Entrada>(); val arquivos = ArrayList<Upnp.Entrada>()
        val pedidoSemBarra = pedido.trimEnd('/')
        for (i in 0 until respostas.length) {
            val r = respostas.item(i) as Element
            var href = texto(r, "href") ?: continue
            // Alguns servidores devolvem o endereço completo (http://host/caminho): fica só o caminho.
            if (href.startsWith("http://") || href.startsWith("https://")) href = runCatching { URI(href).rawPath }.getOrNull() ?: continue
            if (href.trimEnd('/') == pedidoSemBarra) continue              // a própria pasta
            if (!caminhoPermitido(c, href)) continue
            val pasta = r.getElementsByTagNameNS("DAV:", "collection").length > 0
            val ultimo = href.trimEnd('/').substringAfterLast('/')
            val nomeArquivo = runCatching { URLDecoder.decode(ultimo.replace("+", "%2B"), "UTF-8") }.getOrDefault(ultimo)
            val nome = texto(r, "displayname")?.takeIf { it.isNotBlank() } ?: nomeArquivo
            if (nome.startsWith(".")) continue                               // .DS_Store, .nextcloud etc.
            if (pasta) pastas += Upnp.Entrada(href, nome, true, null, Upnp.Tipo.PASTA, null)
            else {
                val tipo = tipoPorConteudo(texto(r, "getcontenttype").orEmpty()) ?: MidiaContas.tipoPorNome(nomeArquivo)
                arquivos += Upnp.Entrada(href, nome, false, "webdav://${c.id}$href", tipo, null)
            }
        }
        return pastas.sortedBy { it.titulo.lowercase(Locale.ROOT) } + arquivos.sortedBy { it.titulo.lowercase(Locale.ROOT) }
    }

    private fun tipoPorConteudo(ct: String): Upnp.Tipo? = when {
        ct.startsWith("video/") -> Upnp.Tipo.VIDEO
        ct.startsWith("audio/") -> Upnp.Tipo.AUDIO
        ct.startsWith("image/") -> Upnp.Tipo.FOTO
        else -> null
    }

    // ---------------------------------------------------------------- tocar

    data class Alvo(val conta: String, val caminho: String)

    fun lerUri(u: String): Alvo? {
        if (!u.startsWith("webdav://")) return null
        val resto = u.substring(9)
        val conta = resto.substringBefore('/')
        val caminho = "/" + resto.substringAfter('/', "")
        if (conta.isEmpty() || !conta.all { it.isLetterOrDigit() || it == '-' } || caminho == "/") return null
        return Alvo(conta, caminho)
    }

    fun urlTocar(c: ContaMidia, caminho: String): String? =
        if (caminhoPermitido(c, caminho)) origem(URI(c.servidor)) + caminho else null

    fun cabecalhoAuth(c: ContaMidia): Map<String, String> =
        if (c.usuario.isEmpty() && c.senha.isEmpty()) emptyMap()
        else mapOf("Authorization" to "Basic " + java.util.Base64.getEncoder().encodeToString("${c.usuario}:${c.senha}".toByteArray(Charsets.UTF_8)))

    // ---------------------------------------------------------------- HTTP cru (só para o PROPFIND)

    fun pedidoCru(metodo: String, url: String, auth: Map<String, String>, extras: Map<String, String>, corpo: ByteArray): Resposta {
        val u = URI(url)
        val https = u.scheme.equals("https", true)
        val host = u.host ?: throw IOException("endereço sem servidor")
        val porta = if (u.port > 0) u.port else if (https) 443 else 80
        val bruto = Socket().apply { soTimeout = 20_000; connect(InetSocketAddress(host, porta), 6_000) }
        val s: Socket = if (https) {
            val tls = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(bruto, host, porta, true) as SSLSocket
            tls.startHandshake()
            // createSocket não confere o NOME do certificado: sem isto, qualquer certificado válido de outro site passaria.
            if (!HttpsURLConnection.getDefaultHostnameVerifier().verify(host, tls.session)) {
                tls.close(); throw IOException("certificado não é deste servidor")
            }
            tls
        } else bruto
        s.use { sock ->
            val alvo = (u.rawPath ?: "/").ifEmpty { "/" } + (u.rawQuery?.let { "?$it" } ?: "")
            val cab = StringBuilder("$metodo $alvo HTTP/1.1\r\nHost: ${u.rawAuthority}\r\n")
            (auth + extras).forEach { (k, v) -> cab.append(k).append(": ").append(v).append("\r\n") }
            cab.append("User-Agent: TvLauncher/1.0\r\nContent-Length: ${corpo.size}\r\nConnection: close\r\n\r\n")
            val saida = sock.getOutputStream()
            saida.write(cab.toString().toByteArray(Charsets.UTF_8)); saida.write(corpo); saida.flush()
            return lerResposta(BufferedInputStream(sock.getInputStream()))
        }
    }

    /** Lê status, cabeçalhos e corpo (Content-Length, "chunked" ou até fechar). Teto de [MAX_BYTES]. */
    fun lerResposta(e: InputStream): Resposta {
        val status = linha(e) ?: throw IOException("sem resposta")
        val codigo = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("resposta inválida")
        val cab = LinkedHashMap<String, String>()
        while (true) {
            val l = linha(e) ?: break
            if (l.isEmpty()) break
            val i = l.indexOf(':'); if (i > 0) cab[l.substring(0, i).trim().lowercase(Locale.ROOT)] = l.substring(i + 1).trim()
        }
        val corpo = ByteArrayOutputStream()
        when {
            cab["transfer-encoding"]?.contains("chunked", true) == true -> while (true) {
                val tam = linha(e)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("chunk inválido")
                if (tam == 0) break
                copiar(e, corpo, tam.toLong())
                linha(e)   // o \r\n depois do pedaço
            }
            cab["content-length"] != null -> copiar(e, corpo, cab["content-length"]!!.toLongOrNull() ?: 0L)
            else -> copiar(e, corpo, Long.MAX_VALUE)
        }
        return Resposta(codigo, cab, corpo.toByteArray())
    }

    private fun copiar(e: InputStream, saida: ByteArrayOutputStream, quanto: Long) {
        var falta = quanto
        val buf = ByteArray(16 * 1024)
        while (falta > 0) {
            val n = e.read(buf, 0, minOf(buf.size.toLong(), falta).toInt())
            if (n < 0) { if (quanto == Long.MAX_VALUE) return else throw IOException("resposta cortada") }
            saida.write(buf, 0, n); falta -= n
            if (saida.size() > MAX_BYTES) throw IOException("resposta grande demais")
        }
    }

    private fun linha(e: InputStream): String? {
        val b = ByteArrayOutputStream()
        while (true) {
            val c = e.read()
            if (c < 0) return if (b.size() == 0) null else b.toString("UTF-8")
            if (c == '\n'.code) return b.toString("UTF-8").trimEnd('\r')
            b.write(c)
            if (b.size() > 16 * 1024) throw IOException("linha grande demais")
        }
    }

    // ---------------------------------------------------------------- XML (mesmas defesas do Upnp: sem DOCTYPE)

    private fun documento(xml: ByteArray) = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        isExpandEntityReferences = false
    }.newDocumentBuilder().parse(ByteArrayInputStream(Upnp.semDoctype(xml)))

    private fun texto(e: Element, nome: String): String? {
        val l = e.getElementsByTagNameNS("DAV:", nome)
        return if (l.length == 0) null else l.item(0).textContent.trim().ifEmpty { null }
    }
}
