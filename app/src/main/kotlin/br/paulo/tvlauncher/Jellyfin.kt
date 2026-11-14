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
import java.net.URLEncoder

/**
 * Jellyfin, etapa 3 das mídias da rede (07/11/2026). Servidor de mídia livre (GPL), comum em NAS de quem monta em casa.
 *
 *  1. [procurar]: o Jellyfin responde a um grito UDP na porta 7359 ("who is JellyfinServer?") com nome e endereço.
 *  2. [entrar]: login com usuário e senha; o servidor devolve um TOKEN. Guardamos o token (cifrado), nunca a senha.
 *  3. [listar]: bibliotecas (Filmes, Séries, Música...), pastas, temporadas e episódios; tudo pelo mesmo "ParentId".
 *  4. Tocar: o player recebe jellyfin://<id da conta>/<id do item> e só na hora de tocar vira o endereço com o token
 *     ([urlTocar]): o token não passa pelo Intent nem fica na lista de posições salvas.
 *
 * As partes de texto (JSON, cabeçalho) são puras e têm teste JVM (JellyfinTest).
 */
object Jellyfin {
    const val CONTINUAR = "*continuar"
    /** Ideia 29: "Adicionados há pouco" e busca no servidor ("*busca:" + o texto). */
    const val RECENTES = "*recentes"
    const val BUSCA = "*busca:"
    private const val PORTA_BUSCA = 7359
    private const val MAX_BYTES = 8 * 1024 * 1024

    /** Servidor achado na rede, ainda sem login. [emby] = é um Emby (mesma API; muda só o nome e a busca). */
    data class Achado(val nome: String, val endereco: String, val emby: Boolean = false)

    // ---------------------------------------------------------------- 1. procurar

    /** Jellyfin e Emby ao mesmo tempo (cada um responde ao seu grito). Se um endereço responder aos dois, fica Jellyfin. */
    fun procurarTodos(esperaMs: Int = 2500): List<Achado> {
        var emby: List<Achado> = emptyList()
        val t = Thread { emby = runCatching { procurar(esperaMs, true) }.getOrDefault(emptyList()) }.apply { start() }
        val jelly = runCatching { procurar(esperaMs, false) }.getOrDefault(emptyList())
        t.join(esperaMs + 1500L)
        return jelly + emby.filter { e -> jelly.none { it.endereco == e.endereco } }
    }

    fun procurar(esperaMs: Int = 2500, emby: Boolean = false): List<Achado> {
        val pedido = (if (emby) "who is EmbyServer?" else "who is JellyfinServer?").toByteArray()
        val achados = LinkedHashMap<String, Achado>()
        DatagramSocket().use { s ->
            s.broadcast = true
            s.soTimeout = 400
            s.send(DatagramPacket(pedido, pedido.size, InetAddress.getByName("255.255.255.255"), PORTA_BUSCA))
            val fim = System.currentTimeMillis() + esperaMs
            val buf = ByteArray(4096)
            while (System.currentTimeMillis() < fim) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                lerAchado(String(p.data, 0, p.length, Charsets.UTF_8), emby)?.let { achados[it.endereco] = it }
            }
        }
        return achados.values.toList()
    }

    fun lerAchado(texto: String, emby: Boolean = false): Achado? = runCatching {
        val o = JSONObject(texto)
        val end = o.optString("Address").trimEnd('/')
        if (!end.startsWith("http://") && !end.startsWith("https://")) null else Achado(o.optString("Name").ifEmpty { end }, end, emby)
    }.getOrNull()

    // ---------------------------------------------------------------- 2. entrar

    /** Cabeçalho que o Jellyfin exige em todo pedido: quem é o app (e o token, depois do login). */
    fun cabecalho(aparelho: String, token: String = ""): String =
        "MediaBrowser Client=\"TvLauncher\", Device=\"Android TV\", DeviceId=\"$aparelho\", Version=\"1.0\"" +
            (if (token.isNotEmpty()) ", Token=\"$token\"" else "")

    /**
     * Faz o login e devolve a conta com token e id do usuário (sem a senha). [nome] null = perguntar ao servidor o nome
     * dele (/System/Info/Public, sem login); se nem isso vier, fica o endereço sem "http://". [tipo] = JELLYFIN ou EMBY.
     * Emby instalado atrás de proxy às vezes só responde em "/emby/...": se a raiz der 404, tenta com o prefixo.
     */
    fun entrar(servidor: String, usuario: String, senha: String, aparelho: String, nome: String?,
               tipo: String = ContaMidia.JELLYFIN): ContaMidia {
        val corpo = JSONObject().put("Username", usuario).put("Pw", senha).toString()
        var base = servidor
        val r = try {
            pedir("$base/Users/AuthenticateByName", cabecalho(aparelho), corpo)
        } catch (e: java.io.IOException) {
            if (tipo != ContaMidia.EMBY || e.message != "http 404" || base.endsWith("/emby")) throw e
            base = "$servidor/emby"
            pedir("$base/Users/AuthenticateByName", cabecalho(aparelho), corpo)
        }
        val (token, uid) = lerLogin(r)
        val nomeFinal = nome ?: runCatching { lerNomeServidor(pedir("$base/System/Info/Public", cabecalho(aparelho))) }.getOrNull()
            ?: servidor.substringAfter("://")
        return ContaMidia("", tipo, nomeFinal, base, usuario = usuario, token = token, usuarioId = uid)
    }

    /** O nome que o dono deu ao servidor ("ServerName"), ou null. */
    fun lerNomeServidor(texto: String): String? = runCatching { JSONObject(texto).optString("ServerName").trim().ifEmpty { null } }.getOrNull()

    fun lerLogin(texto: String): Pair<String, String> {
        val o = JSONObject(texto)
        val token = o.optString("AccessToken")
        val uid = o.optJSONObject("User")?.optString("Id").orEmpty()
        require(token.isNotEmpty() && uid.isNotEmpty()) { "login sem token" }
        return token to uid
    }

    // ---------------------------------------------------------------- 3. listar

    /** Filhos de [pai]: "" = as bibliotecas (com "Continuar assistindo" na frente), CONTINUAR = os começados. */
    fun listar(c: ContaMidia, pai: String, aparelho: String): List<Upnp.Entrada> {
        val base = "${c.servidor}/Users/${c.usuarioId}"
        // Ideia 29: sinopse (Overview) e só a imagem principal (pôster), para a ficha ao lado da lista.
        val campos = "Fields=MediaSources,Overview&EnableImageTypes=Primary&ImageTypeLimit=1"
        return when {
            pai == "" -> listOf(Upnp.Entrada(CONTINUAR, "Continuar assistindo", true, null, Upnp.Tipo.PASTA, null),
                Upnp.Entrada(RECENTES, "Adicionados há pouco", true, null, Upnp.Tipo.PASTA, null)) +
                lerItens(pedir("$base/Views", cabecalho(aparelho, c.token)), c)
            pai == CONTINUAR -> lerItens(pedir("$base/Items/Resume?Limit=40&$campos", cabecalho(aparelho, c.token)), c, comSerie = true)
            pai == RECENTES -> lerItens(pedir("$base/Items?SortBy=DateCreated&SortOrder=Descending&Recursive=true" +
                "&IncludeItemTypes=Movie,Episode,MusicAlbum&Limit=40&$campos", cabecalho(aparelho, c.token)), c, comSerie = true)
            pai.startsWith(BUSCA) -> lerItens(pedir("$base/Items?searchTerm=${enc(pai.removePrefix(BUSCA).take(100))}&Recursive=true" +
                "&IncludeItemTypes=Movie,Series,Episode,MusicAlbum,Audio&Limit=60&$campos", cabecalho(aparelho, c.token)), c, comSerie = true)
            else -> lerItens(pedir("$base/Items?ParentId=${enc(pai)}&SortBy=SortName&SortOrder=Ascending&$campos",
                cabecalho(aparelho, c.token)), c)
        }
    }

    /** Itens do JSON do Jellyfin viram entradas da lista. Pastas (biblioteca, série, temporada) primeiro. */
    fun lerItens(texto: String, c: ContaMidia, comSerie: Boolean = false): List<Upnp.Entrada> {
        val itens: JSONArray = runCatching { JSONObject(texto).optJSONArray("Items") }.getOrNull() ?: return emptyList()
        val lista = (0 until itens.length()).mapNotNull { i ->
            val o = itens.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("Id").ifEmpty { return@mapNotNull null }
            val nome = nomeExibido(o, comSerie)
            // Pôster: caminho no servidor (sem o endereço nem o token; quem baixa junta os dois). Só id seguro.
            val capa = if (o.optJSONObject("ImageTags")?.has("Primary") == true && id.all { it.isLetterOrDigit() || it == '-' })
                "/Items/$id/Images/Primary?maxHeight=450" else null
            val sinopse = o.optString("Overview").trim().take(700).ifEmpty { null }
            if (o.optBoolean("IsFolder")) Upnp.Entrada(id, nome, true, null, Upnp.Tipo.PASTA, capa, sinopse)
            else {
                val tipo = when (o.optString("MediaType")) {
                    "Video" -> Upnp.Tipo.VIDEO
                    "Audio" -> Upnp.Tipo.AUDIO
                    "Photo" -> Upnp.Tipo.FOTO
                    else -> Upnp.Tipo.OUTRO
                }
                val sufixo = if (tipo == Upnp.Tipo.AUDIO) "/audio" else ""
                Upnp.Entrada(id, nome, false, "jellyfin://${c.id}/$id$sufixo", tipo, capa, sinopse)
            }
        }
        return lista.filter { it.pasta } + lista.filter { !it.pasta }
    }

    /** "T2E5 · Nome do episódio" (com o nome da série na frente em "Continuar"); "Nome (2021)" para filme. */
    fun nomeExibido(o: JSONObject, comSerie: Boolean = false): String {
        val nome = o.optString("Name").ifEmpty { "?" }
        return when (o.optString("Type")) {
            "Episode" -> {
                val t = o.optInt("ParentIndexNumber", -1); val e = o.optInt("IndexNumber", -1)
                val serie = o.optString("SeriesName")
                val marca = if (t >= 0 && e >= 0) "T${t}E$e · " else ""
                if (comSerie && serie.isNotEmpty()) "$serie · $marca$nome" else "$marca$nome"
            }
            "Movie" -> o.optInt("ProductionYear", 0).let { if (it > 0) "$nome ($it)" else nome }
            else -> nome
        }
    }

    // ---------------------------------------------------------------- 4. tocar

    /** jellyfin://<conta>/<item> -> o endereço do arquivo original (sem converter: o Chromecast toca quase tudo). */
    fun urlTocar(c: ContaMidia, item: String, audio: Boolean): String =
        "${c.servidor}/${if (audio) "Audio" else "Videos"}/${enc(item)}/stream?static=true&api_key=${enc(c.token)}"

    data class Alvo(val conta: String, val item: String, val audio: Boolean)

    // ---------------------------------------------------------------- 5. contar ao servidor o que foi assistido

    /** Momentos que o player conta ao Jellyfin: começou, ainda tocando (a cada salvar) e parou. */
    enum class Momento(val caminho: String) { COMECOU("/Sessions/Playing"), TOCANDO("/Sessions/Playing/Progress"), PAROU("/Sessions/Playing/Stopped") }

    /**
     * Corpo do aviso. O Jellyfin conta o tempo em "ticks" (1 ms = 10 mil ticks). É com isso que ele monta o
     * "Continuar assistindo" e marca como visto quando a posição chega perto do fim.
     */
    fun corpoInforme(item: String, ms: Long, pausado: Boolean): String = JSONObject()
        .put("ItemId", item).put("PositionTicks", ms.coerceAtLeast(0) * 10_000).put("IsPaused", pausado)
        .put("PlayMethod", "DirectPlay").put("CanSeek", true).toString()

    /** Avisa o servidor. Falha de rede aqui não atrapalha o vídeo: quem chama ignora o erro. */
    fun informar(c: ContaMidia, aparelho: String, item: String, momento: Momento, ms: Long, pausado: Boolean = false) {
        pedir(c.servidor + momento.caminho, cabecalho(aparelho, c.token), corpoInforme(item, ms, pausado))
    }

    /** jellyfin://<conta>/<item>[/audio]. Id do item só com letras, números e "-" (o Jellyfin usa hexadecimal). */
    fun lerUri(uri: String): Alvo? {
        if (!uri.startsWith("jellyfin://")) return null
        val partes = uri.substring(11).split('/')
        if (partes.size !in 2..3) return null
        val (conta, item) = partes
        val audio = partes.getOrNull(2) == "audio"
        if (partes.size == 3 && !audio) return null
        if (conta.isEmpty() || item.isEmpty() || !item.all { it.isLetterOrDigit() || it == '-' }) return null
        return Alvo(conta, item, audio)
    }

    // ---------------------------------------------------------------- HTTP

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun pedir(url: String, auth: String, corpoJson: String? = null): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 20000
        c.setRequestProperty("Authorization", auth)
        c.setRequestProperty("X-Emby-Authorization", auth)   // servidores antigos só leem este
        c.setRequestProperty("Accept", "application/json")
        if (corpoJson != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(corpoJson.toByteArray(Charsets.UTF_8)) }
        }
        val codigo = c.responseCode
        if (codigo == 401) throw SecurityException("401")
        if (codigo !in 200..299) throw java.io.IOException("http $codigo")
        if (codigo == 204) { c.disconnect(); return "" }   // os avisos de reprodução respondem sem corpo
        return c.inputStream.use { limitado(it) }.also { c.disconnect() }
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
