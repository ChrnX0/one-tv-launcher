package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Plex (10/11/2026, dono: "o que falta cobrir? Plex?"). O servidor de mídia mais comum em casa.
 *
 *  1. [procurar]: o Plex responde a um grito UDP na porta 32414 (protocolo "GDM") com nome, porta e identificador.
 *  2. Login SEM digitar senha na TV: [pedirCodigo] pede ao plex.tv um código de 4 letras; a pessoa abre plex.tv/link
 *     no celular e digita o código; [conferirCodigo] fica perguntando até o plex.tv devolver o token da CONTA.
 *  3. [servidores]: com o token da conta, o plex.tv lista os servidores dela, cada um com o token PRÓPRIO do servidor e
 *     os endereços (local, remoto). [escolherEndereco] testa e fica com o primeiro que responde (o local antes).
 *     Guardamos só o token do servidor (cifrado, MidiaArmazem); o da conta é descartado.
 *  4. [listar]: bibliotecas, pastas, séries, temporadas; "Continuar assistindo" = /library/onDeck.
 *  5. Tocar: o player recebe plex://<conta>/<item>?p=<arquivo> e só na hora vira o endereço com o token ([urlTocar]).
 *  6. [informar]: conta ao servidor onde parou (/:/timeline), que é o que move o "Continuar" do próprio Plex.
 *
 * Partes de texto puras, com teste JVM (PlexTest). Tudo que vem do servidor é DADO NÃO CONFIÁVEL: caminhos conferidos.
 */
object Plex {
    const val CONTINUAR = "*continuar"
    private const val PORTA_GDM = 32414
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val PLEX_TV = "https://plex.tv/api/v2"

    data class Achado(val nome: String, val endereco: String, val id: String)
    data class Codigo(val id: Long, val codigo: String, val expiraEmS: Int)
    data class Endereco(val uri: String, val local: Boolean, val relay: Boolean)
    data class Servidor(val nome: String, val id: String, val token: String, val enderecos: List<Endereco>)
    data class Alvo(val conta: String, val item: String, val parte: String)

    // ---------------------------------------------------------------- 1. procurar (GDM)

    fun procurar(esperaMs: Int = 2500): List<Achado> {
        val pedido = "M-SEARCH * HTTP/1.1\r\n\r\n".toByteArray()
        val achados = LinkedHashMap<String, Achado>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 400
            // Os apps oficiais mandam para os dois: o endereço de grupo do GDM e o "todo mundo" da rede.
            runCatching { s.send(DatagramPacket(pedido, pedido.size, InetAddress.getByName("239.0.0.250"), PORTA_GDM)) }
            runCatching { s.send(DatagramPacket(pedido, pedido.size, InetAddress.getByName("255.255.255.255"), PORTA_GDM)) }
            val fim = System.currentTimeMillis() + esperaMs
            val buf = ByteArray(2048)
            while (System.currentTimeMillis() < fim) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                val ip = p.address?.hostAddress ?: continue
                lerAchado(String(p.data, 0, p.length, Charsets.UTF_8), ip)?.let { achados[it.id] = it }
            }
        }
        return achados.values.toList()
    }

    /** Resposta do GDM ("HTTP/1.0 200 OK", "Name: Sala", "Port: 32400", "Resource-Identifier: abc"). Só servidor. */
    fun lerAchado(texto: String, ip: String): Achado? {
        val cab = texto.lineSequence().mapNotNull { l ->
            val i = l.indexOf(':'); if (i <= 0) null else l.substring(0, i).trim().lowercase() to l.substring(i + 1).trim()
        }.toMap()
        if (cab["content-type"]?.contains("plex/media-server") != true) return null
        val porta = cab["port"]?.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
        val id = cab["resource-identifier"]?.takeIf { idValido(it) } ?: return null
        if (!MidiaContas.servidorValido(ip)) return null
        return Achado(cab["name"]?.take(60)?.ifBlank { null } ?: ip, "http://$ip:$porta", id)
    }

    private fun idValido(s: String) = s.isNotEmpty() && s.length <= 64 && s.all { it.isLetterOrDigit() || it == '-' }

    // ---------------------------------------------------------------- 2. login por código (plex.tv/link)

    /** Cabeçalhos que o Plex exige de todo app. [aparelho] = identificador fixo desta TV. */
    fun cabecalhos(aparelho: String, token: String = ""): Map<String, String> = buildMap {
        put("X-Plex-Product", "One TV Launcher")
        put("X-Plex-Version", "1.0")
        put("X-Plex-Client-Identifier", aparelho)
        put("X-Plex-Platform", "Android")
        put("X-Plex-Device", "Android TV")
        put("X-Plex-Device-Name", "One TV")
        put("Accept", "application/json")
        if (token.isNotEmpty()) put("X-Plex-Token", token)
    }

    fun pedirCodigo(aparelho: String): Codigo = lerCodigo(pedir("$PLEX_TV/pins?strong=false", cabecalhos(aparelho), post = true))

    /** null = a pessoa ainda não digitou o código; senão, o token da conta. */
    fun conferirCodigo(aparelho: String, id: Long): String? = lerTokenDoCodigo(pedir("$PLEX_TV/pins/$id", cabecalhos(aparelho)))

    fun lerCodigo(texto: String): Codigo {
        val o = JSONObject(texto)
        val codigo = o.optString("code")
        require(o.optLong("id") > 0 && codigo.length in 4..32 && codigo.all { it.isLetterOrDigit() }) { "código inválido" }
        return Codigo(o.getLong("id"), codigo.uppercase(), o.optInt("expiresIn", 900))
    }

    fun lerTokenDoCodigo(texto: String): String? =
        runCatching { JSONObject(texto).optString("authToken") }.getOrNull()?.takeIf { it.isNotBlank() && it != "null" }

    /**
     * Página que a pessoa abre no celular (QR code): já vem com o código preenchido. Quem não quiser ler o QR digita o
     * código em plex.tv/link, que é o caminho que o Plex documenta para TV.
     */
    fun paginaDoCodigo(aparelho: String, codigo: String): String =
        "https://app.plex.tv/auth#?clientID=${enc(aparelho)}&code=${enc(codigo)}&context%5Bdevice%5D%5Bproduct%5D=One%20TV%20Launcher"

    // ---------------------------------------------------------------- 3. servidores da conta

    fun servidores(aparelho: String, tokenConta: String): List<Servidor> =
        lerServidores(pedir("$PLEX_TV/resources?includeHttps=1&includeRelay=0", cabecalhos(aparelho, tokenConta)))

    /** Só o que "provides" server e tem token. Endereços locais primeiro; relay (lento, limitado) por último. */
    fun lerServidores(texto: String): List<Servidor> {
        val a = runCatching { JSONArray(texto) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (!o.optString("provides").split(',').contains("server")) return@mapNotNull null
            val token = o.optString("accessToken").takeIf { it.isNotBlank() && it != "null" } ?: return@mapNotNull null
            val id = o.optString("clientIdentifier").takeIf { idValido(it) } ?: return@mapNotNull null
            val cons = o.optJSONArray("connections") ?: JSONArray()
            val ends = ArrayList<Endereco>()
            for (j in 0 until cons.length()) {
                val c = cons.optJSONObject(j) ?: continue
                val local = c.optBoolean("local"); val relay = c.optBoolean("relay")
                val uri = c.optString("uri").trimEnd('/')
                if (uri.startsWith("https://") || uri.startsWith("http://")) ends += Endereco(uri, local, relay)
                // O endereço https *.plex.direct depende do DNS da casa resolver esse nome (alguns roteadores bloqueiam).
                // Na rede local, o http://ip:porta direto também serve (Plex com "conexões seguras: preferidas").
                val ip = c.optString("address"); val porta = c.optInt("port", 0)
                if (local && !relay && MidiaContas.servidorValido(ip) && porta in 1..65535) ends += Endereco("http://$ip:$porta", true, false)
            }
            val ordem = ends.distinctBy { it.uri }.sortedWith(compareBy({ it.relay }, { !it.local }, { !it.uri.startsWith("https") }))
            Servidor(o.optString("name").ifBlank { "Plex" }.take(60), id, token, ordem)
        }
    }

    /** O primeiro endereço que responde ao /identity com o token. null = nenhum (servidor desligado ou fora da rede). */
    fun escolherEndereco(s: Servidor, aparelho: String): String? = s.enderecos.firstOrNull { e ->
        runCatching { pedir("${e.uri}/identity", cabecalhos(aparelho, s.token), conectarMs = 2500, lerMs = 4000) }.isSuccess
    }?.uri

    /** Conta pronta para guardar. [usuarioId] guarda o id do servidor (para casar com o achado na rede). */
    fun conta(s: Servidor, endereco: String): ContaMidia =
        ContaMidia("", ContaMidia.PLEX, s.nome, endereco, token = s.token, usuarioId = s.id)

    // ---------------------------------------------------------------- 4. listar

    /** Filhos de [pai]: "" = Continuar + bibliotecas; CONTINUAR = onDeck; o resto = um caminho /library/... do servidor. */
    fun listar(c: ContaMidia, pai: String, aparelho: String): List<Upnp.Entrada> {
        val cab = cabecalhos(aparelho, c.token)
        return when (pai) {
            "" -> listOf(Upnp.Entrada(CONTINUAR, "Continuar assistindo", true, null, Upnp.Tipo.PASTA, null)) +
                lerBibliotecas(pedir("${c.servidor}/library/sections", cab))
            CONTINUAR -> lerItens(pedir("${c.servidor}/library/onDeck", cab), c, comSerie = true)
            else -> {
                require(caminhoValido(pai)) { "caminho inválido" }
                val sep = if (pai.contains('?')) '&' else '?'
                lerItens(pedir("${c.servidor}$pai${sep}X-Plex-Container-Start=0&X-Plex-Container-Size=5000", cab), c)
            }
        }
    }

    /** Caminho de pasta que veio do servidor: só /library/..., sem "..", sem esquema (não deixa o servidor nos mandar a outro lugar). */
    fun caminhoValido(p: String): Boolean =
        p.startsWith("/library/") && !p.contains("..") && !p.contains("://") && !p.contains('#') && p.length <= 512 &&
            p.none { it.isWhitespace() || it.code < 32 }

    fun lerBibliotecas(texto: String): List<Upnp.Entrada> {
        val dirs = runCatching { JSONObject(texto).optJSONObject("MediaContainer")?.optJSONArray("Directory") }.getOrNull() ?: return emptyList()
        return (0 until dirs.length()).mapNotNull { i ->
            val o = dirs.optJSONObject(i) ?: return@mapNotNull null
            val chave = o.optString("key").takeIf { k -> k.isNotEmpty() && k.all { it.isDigit() } } ?: return@mapNotNull null
            Upnp.Entrada("/library/sections/$chave/all", o.optString("title").ifBlank { "?" }, true, null, Upnp.Tipo.PASTA, null)
        }
    }

    /** Itens do Plex. Com arquivo (Media > Part) = tocável; sem = pasta (série, temporada, álbum), aberta pelo "key". */
    fun lerItens(texto: String, c: ContaMidia, comSerie: Boolean = false): List<Upnp.Entrada> {
        val mc = runCatching { JSONObject(texto).optJSONObject("MediaContainer") }.getOrNull() ?: return emptyList()
        val itens = mc.optJSONArray("Metadata") ?: JSONArray()
        val lista = ArrayList<Upnp.Entrada>()
        for (i in 0 until itens.length()) {
            val o = itens.optJSONObject(i) ?: continue
            val nome = nomeExibido(o, comSerie)
            val parte = o.optJSONArray("Media")?.optJSONObject(0)?.optJSONArray("Part")?.optJSONObject(0)?.optString("key").orEmpty()
            val rk = o.optString("ratingKey").takeIf { k -> k.isNotEmpty() && k.all { it.isDigit() } }
            if (parte.isNotEmpty() && rk != null) {
                if (!parteValida(parte)) continue
                val tipo = when (o.optString("type")) {
                    "track" -> Upnp.Tipo.AUDIO
                    "photo" -> Upnp.Tipo.FOTO
                    else -> Upnp.Tipo.VIDEO
                }
                lista += Upnp.Entrada(rk, nome, false, uri(c.id, rk, parte), tipo, null)
            } else {
                val chave = o.optString("key")
                if (caminhoValido(chave)) lista += Upnp.Entrada(chave, nome, true, null, Upnp.Tipo.PASTA, null)
            }
        }
        // Pastas também podem vir como "Directory" (navegação por pasta do disco).
        val dirs = mc.optJSONArray("Directory") ?: JSONArray()
        for (i in 0 until dirs.length()) {
            val o = dirs.optJSONObject(i) ?: continue
            val chave = o.optString("key").let { k -> if (k.startsWith("/")) k else null } ?: continue
            if (caminhoValido(chave)) lista += Upnp.Entrada(chave, o.optString("title").ifBlank { "?" }, true, null, Upnp.Tipo.PASTA, null)
        }
        return lista.filter { it.pasta } + lista.filter { !it.pasta }
    }

    /** "T2E5 · Episódio" (com a série na frente no Continuar), "Filme (2021)", "Álbum" etc. */
    fun nomeExibido(o: JSONObject, comSerie: Boolean = false): String {
        val nome = o.optString("title").ifBlank { "?" }
        return when (o.optString("type")) {
            "episode" -> {
                val t = o.optInt("parentIndex", -1); val e = o.optInt("index", -1)
                val serie = o.optString("grandparentTitle")
                val marca = if (t >= 0 && e >= 0) "T${t}E$e · " else ""
                if (comSerie && serie.isNotEmpty()) "$serie · $marca$nome" else "$marca$nome"
            }
            "movie" -> o.optInt("year", 0).let { if (it > 0) "$nome ($it)" else nome }
            "season" -> if (comSerie && o.optString("parentTitle").isNotEmpty()) "${o.optString("parentTitle")} · $nome" else nome
            "track" -> o.optInt("index", 0).let { if (it > 0) "$it. $nome" else nome }
            else -> nome
        }
    }

    // ---------------------------------------------------------------- 5. tocar

    fun parteValida(p: String): Boolean =
        p.startsWith("/library/parts/") && !p.contains("..") && !p.contains("://") && !p.contains('#') && p.length <= 1024 &&
            p.none { it.isWhitespace() || it.code < 32 }

    /** plex://<conta>/<item>?p=<arquivo>. O token nunca entra aqui (este endereço vai no Intent e nas posições salvas). */
    fun uri(conta: String, item: String, parte: String) = "plex://$conta/$item?p=${enc(parte)}"

    fun lerUri(u: String): Alvo? {
        if (!u.startsWith("plex://")) return null
        val resto = u.substring(7)
        val conta = resto.substringBefore('/')
        val item = resto.substringAfter('/', "").substringBefore('?')
        val parte = runCatching { URLDecoder.decode(resto.substringAfter("?p=", ""), "UTF-8") }.getOrNull() ?: return null
        if (conta.isEmpty() || !conta.all { it.isLetterOrDigit() || it == '-' }) return null
        if (item.isEmpty() || !item.all { it.isDigit() } || !parteValida(parte)) return null
        return Alvo(conta, item, parte)
    }

    /** O endereço com o token, só na hora de tocar. */
    fun urlTocar(c: ContaMidia, parte: String): String =
        c.servidor + parte + (if (parte.contains('?')) "&" else "?") + "X-Plex-Token=" + enc(c.token)

    // ---------------------------------------------------------------- 6. contar ao servidor onde parou

    enum class Estado(val valor: String) { TOCANDO("playing"), PAUSADO("paused"), PAROU("stopped") }

    fun caminhoLinhaDoTempo(item: String, estado: Estado, ms: Long, duracao: Long): String =
        "/:/timeline?ratingKey=$item&key=${enc("/library/metadata/$item")}&state=${estado.valor}" +
            "&time=${ms.coerceAtLeast(0)}&duration=${duracao.coerceAtLeast(0)}"

    fun informar(c: ContaMidia, aparelho: String, item: String, estado: Estado, ms: Long, duracao: Long) {
        pedir(c.servidor + caminhoLinhaDoTempo(item, estado, ms, duracao), cabecalhos(aparelho, c.token))
    }

    // ---------------------------------------------------------------- HTTP

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun pedir(url: String, cab: Map<String, String>, post: Boolean = false, conectarMs: Int = 6000, lerMs: Int = 20000): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = conectarMs; c.readTimeout = lerMs
        c.instanceFollowRedirects = false   // redirecionamento levaria o token junto para outro lugar
        cab.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (post) { c.requestMethod = "POST"; c.doOutput = true; c.setFixedLengthStreamingMode(0); c.outputStream.close() }
        try {
            val codigo = c.responseCode
            if (codigo == 401) throw SecurityException("401")
            if (codigo !in 200..299) throw java.io.IOException("http $codigo")
            return c.inputStream.use { limitado(it) }
        } finally { c.disconnect() }
    }

    private fun limitado(i: InputStream): String {
        val saida = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = i.read(buf); if (n < 0) break
            saida.write(buf, 0, n)
            require(saida.size() <= MAX_BYTES) { "resposta grande demais" }
        }
        return saida.toString("UTF-8")
    }
}
