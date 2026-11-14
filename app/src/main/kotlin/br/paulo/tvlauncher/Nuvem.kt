package br.paulo.tvlauncher

import org.json.JSONObject
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Nuvem como fonte de vídeo (14/11/2026): OneDrive e Dropbox viram "pastas de arquivos" como o SMB e o FTP
 * (PastaArquivos), então a tela "Na rede" lista e o player toca pelo mesmo caminho (arq://<conta>/<caminho>).
 *
 * Login: cada dono cria o SEU app no serviço (grátis, 5 minutos) e digita o identificador dele na TV. Nada de segredo:
 * os dois são "clientes públicos" (OneDrive: código na tela, como o login do Google da TV; Dropbox: PKCE, o celular
 * mostra um código que se digita na TV). O que fica guardado (cifrado, MidiaArmazem) é o token de renovação em
 * ContaMidia.token e o id do app em ContaMidia.usuarioId. Só leitura: as permissões pedidas não deixam apagar nada.
 *
 * A parte pura (endereços, JSON, PKCE) tem teste em NuvemTest; a parte de rede só junta as peças.
 */
object Nuvem {
    // ---------------------------------------------------------------- OneDrive (Microsoft Graph)

    const val MS_LOGIN = "https://login.microsoftonline.com/common/oauth2/v2.0"
    const val MS_ESCOPOS = "Files.Read offline_access"
    const val MS_PAGINA = "https://microsoft.com/link"
    const val GRAPH = "https://graph.microsoft.com/v1.0"

    /** "Application (client) ID" do portal da Microsoft: um GUID. */
    fun idMicrosoftValido(s: String) = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$").matches(s.trim())

    private fun segmentos(caminho: String) =
        caminho.split('/').filter { it.isNotEmpty() }.joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** O item (arquivo ou pasta) no Graph. "" = a raiz do OneDrive. */
    fun graphItem(caminho: String): String =
        if (caminho.isEmpty()) "$GRAPH/me/drive/root" else "$GRAPH/me/drive/root:/${segmentos(caminho)}:"

    /** Os filhos de uma pasta, 500 por página (o Graph manda o resto em @odata.nextLink). */
    fun graphFilhos(caminho: String): String =
        (if (caminho.isEmpty()) "$GRAPH/me/drive/root" else "$GRAPH/me/drive/root:/${segmentos(caminho)}:") +
            "/children?\$top=500&\$select=name,size,folder"

    /** Uma página da lista: os itens e o endereço da próxima (null = acabou). */
    fun lerFilhosGraph(json: String): Pair<List<PastaArquivos.Item>, String?> {
        val o = JSONObject(json)
        val a = o.optJSONArray("value")
        val itens = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val e = a!!.optJSONObject(i) ?: return@mapNotNull null
            PastaArquivos.Item(e.optString("name"), e.has("folder"), e.optLong("size"))
        }
        val prox = o.optString("@odata.nextLink").takeIf { it.startsWith("$GRAPH/") }
        return itens to prox
    }

    // ---------------------------------------------------------------- Dropbox

    const val DBX_API = "https://api.dropboxapi.com"
    const val DBX_TOKEN = "$DBX_API/oauth2/token"

    /** App key do App Console do Dropbox: letras minúsculas e números. */
    fun chaveDropboxValida(s: String) = Regex("^[a-z0-9]{8,32}$").matches(s.trim())

    /** O Dropbox quer "" para a raiz e "/a/b" para o resto. */
    fun caminhoDropbox(caminho: String) = caminho.split('/').filter { it.isNotEmpty() }.let { if (it.isEmpty()) "" else "/" + it.joinToString("/") }

    /** Página de autorização (abre no celular pelo QR). Sem redirect: o Dropbox mostra um código para digitar na TV. */
    fun paginaDropbox(chave: String, desafio: String): String =
        "https://www.dropbox.com/oauth2/authorize?client_id=${URLEncoder.encode(chave, "UTF-8")}&response_type=code" +
            "&token_access_type=offline&code_challenge=$desafio&code_challenge_method=S256"

    fun lerListaDropbox(json: String): Triple<List<PastaArquivos.Item>, String?, Boolean> {
        val o = JSONObject(json)
        val a = o.optJSONArray("entries")
        val itens = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val e = a!!.optJSONObject(i) ?: return@mapNotNull null
            when (e.optString(".tag")) {
                "folder" -> PastaArquivos.Item(e.optString("name"), true, 0)
                "file" -> PastaArquivos.Item(e.optString("name"), false, e.optLong("size"))
                else -> null   // "deleted"
            }
        }
        return Triple(itens, o.optString("cursor").ifEmpty { null }, o.optBoolean("has_more"))
    }

    // ---------------------------------------------------------------- PKCE (RFC 7636)

    fun novoVerificador(): String = ByteArray(32).also { SecureRandom().nextBytes(it) }.let { b64url(it) }
    fun desafio(verificador: String): String = b64url(MessageDigest.getInstance("SHA-256").digest(verificador.toByteArray(Charsets.US_ASCII)))
    private fun b64url(b: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(b)

    // ---------------------------------------------------------------- respostas de token

    class Tokens(val acesso: String, val renovacao: String?, val expiraEmS: Long)

    fun lerTokens(json: String): Tokens {
        val o = JSONObject(json)
        val acesso = o.optString("access_token")
        if (acesso.isEmpty()) throw IOException("NUVEM_" + o.optString("error", "sem_token"))
        return Tokens(acesso, o.optString("refresh_token").ifEmpty { null }, o.optLong("expires_in", 3600))
    }

    class CodigoTv(val dispositivo: String, val usuario: String, val pagina: String, val expiraEmS: Int, val intervaloS: Int)

    fun lerCodigoTv(json: String): CodigoTv {
        val o = JSONObject(json)
        val d = o.optString("device_code"); val u = o.optString("user_code")
        if (d.isEmpty() || u.isEmpty()) throw IOException("NUVEM_" + o.optString("error", "sem_codigo"))
        return CodigoTv(d, u, o.optString("verification_uri", MS_PAGINA), o.optInt("expires_in", 900), maxOf(2, o.optInt("interval", 5)))
    }

    // ---------------------------------------------------------------- rede

    class Resposta(val codigo: Int, val corpo: String)

    private fun conexao(url: String, metodo: String, cabecalhos: Map<String, String>): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = metodo; connectTimeout = 10_000; readTimeout = 20_000; useCaches = false
            instanceFollowRedirects = false   // o token nunca segue para outro endereço
            setRequestProperty("User-Agent", "OneTV-Launcher")
            cabecalhos.forEach { (k, v) -> setRequestProperty(k, v) }
        }

    private fun ler(c: HttpURLConnection): Resposta {
        val cod = c.responseCode
        val corpo = (if (cod in 200..299) c.inputStream else c.errorStream)?.use { s ->
            s.readBytes().let { if (it.size > 8 * 1024 * 1024) throw IOException("resposta grande demais") else String(it, Charsets.UTF_8) }
        }.orEmpty()
        c.disconnect()
        return Resposta(cod, corpo)
    }

    fun postarForm(url: String, campos: Map<String, String>): Resposta {
        val corpo = campos.entries.joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8") }.toByteArray()
        val c = conexao(url, "POST", mapOf("Content-Type" to "application/x-www-form-urlencoded"))
        c.doOutput = true; c.outputStream.use { it.write(corpo) }
        return ler(c)
    }

    fun get(url: String, token: String): Resposta = ler(conexao(url, "GET", mapOf("Authorization" to "Bearer $token")))

    fun postarJson(url: String, token: String, json: String): Resposta {
        val c = conexao(url, "POST", mapOf("Authorization" to "Bearer $token", "Content-Type" to "application/json"))
        c.doOutput = true; c.outputStream.use { it.write(json.toByteArray(Charsets.UTF_8)) }
        return ler(c)
    }

    /** Erro HTTP para o resto do app: 401 = sem permissão (mensagem de senha), 404 = pasta sumiu. */
    fun conferir(r: Resposta): String {
        if (r.codigo in 200..299) return r.corpo
        throw when (r.codigo) {
            401, 403 -> SecurityException("401")
            404, 409 -> IOException("BAD_NETWORK_NAME")
            else -> IOException("HTTP ${r.codigo}")
        }
    }

    /**
     * Arquivo a partir de [posicao] num link de download que já leva a autorização dentro (OneDrive e Dropbox dão um
     * assim, válido por horas). Sem o cabeçalho Authorization: o link é de outro servidor.
     */
    fun abrirLink(url: String, posicao: Long): InputStream {
        require(url.startsWith("https://")) { "link de download sem https" }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000
            if (posicao > 0) setRequestProperty("Range", "bytes=$posicao-")
        }
        val cod = c.responseCode
        if (cod !in 200..299) { c.disconnect(); throw IOException("HTTP $cod") }
        val e = c.inputStream
        if (posicao > 0 && cod == 200) {   // o servidor ignorou o Range: pula até a posição
            var falta = posicao
            while (falta > 0) { val n = e.skip(falta); if (n <= 0) { e.close(); throw IOException("fim antes da posição") }; falta -= n }
        }
        return e
    }

    // ---------------------------------------------------------------- tokens de acesso (memória do processo)

    private val cache = HashMap<String, Pair<String, Long>>()

    /** Token de acesso da conta, renovando quando falta menos de 2 minutos. O token de renovação novo é gravado. */
    fun acesso(c: ContaMidia): String = synchronized(cache) {
        cache[c.id]?.let { (t, ate) -> if (System.currentTimeMillis() < ate) return t }
        val atual = runCatching { MidiaArmazem.conta(LauncherApp.instancia, c.id) }.getOrNull() ?: c
        val r = when (c.tipo) {
            ContaMidia.ONEDRIVE -> postarForm("$MS_LOGIN/token", mapOf("client_id" to atual.usuarioId, "grant_type" to "refresh_token",
                "refresh_token" to atual.token, "scope" to MS_ESCOPOS))
            ContaMidia.DROPBOX -> postarForm(DBX_TOKEN, mapOf("client_id" to atual.usuarioId, "grant_type" to "refresh_token",
                "refresh_token" to atual.token))
            else -> throw IOException("não é nuvem")
        }
        // invalid_grant = a pessoa tirou a permissão ou o token venceu: só entrando de novo.
        if (r.codigo == 400 || r.codigo == 401) throw SecurityException("401 NUVEM_ENTRAR_DE_NOVO")
        val t = lerTokens(conferir(r))
        if (t.renovacao != null && t.renovacao != atual.token) runCatching { MidiaArmazem.salvar(LauncherApp.instancia, atual.copy(token = t.renovacao)) }
        cache[c.id] = t.acesso to (System.currentTimeMillis() + (t.expiraEmS - 120).coerceAtLeast(60) * 1000)
        t.acesso
    }

    fun esquecer(id: String) = synchronized(cache) { cache.remove(id) }
}

/** OneDrive como pasta (Microsoft Graph). */
class PastaOneDrive(private val c: ContaMidia) : PastaArquivos {
    private var ultimo: Triple<String, Long, String>? = null   // caminho, tamanho, link de download

    override fun listar(caminho: String): List<PastaArquivos.Item> {
        val itens = ArrayList<PastaArquivos.Item>()
        var url: String? = Nuvem.graphFilhos(caminho)
        var paginas = 0
        while (url != null && paginas++ < 40) {
            val (lote, prox) = Nuvem.lerFilhosGraph(Nuvem.conferir(Nuvem.get(url, Nuvem.acesso(c))))
            itens += lote; url = prox
        }
        return itens
    }

    private fun item(caminho: String): Triple<String, Long, String> {
        ultimo?.let { if (it.first == caminho) return it }
        val o = JSONObject(Nuvem.conferir(Nuvem.get(Nuvem.graphItem(caminho), Nuvem.acesso(c))))
        val link = o.optString("@microsoft.graph.downloadUrl")
        if (link.isEmpty()) throw IOException("BAD_NETWORK_NAME")
        return Triple(caminho, o.optLong("size"), link).also { ultimo = it }
    }

    override fun tamanho(caminho: String) = item(caminho).second
    override fun ler(caminho: String, posicao: Long): InputStream = Nuvem.abrirLink(item(caminho).third, posicao)
    override fun close() {}
}

/** Dropbox como pasta (API v2). */
class PastaDropbox(private val c: ContaMidia) : PastaArquivos {
    private var ultimo: Triple<String, Long, String>? = null

    override fun listar(caminho: String): List<PastaArquivos.Item> {
        val itens = ArrayList<PastaArquivos.Item>()
        var r = Nuvem.lerListaDropbox(Nuvem.conferir(Nuvem.postarJson("${Nuvem.DBX_API}/2/files/list_folder", Nuvem.acesso(c),
            JSONObject().put("path", Nuvem.caminhoDropbox(caminho)).put("limit", 2000).toString())))
        itens += r.first
        var paginas = 0
        while (r.third && r.second != null && paginas++ < 40) {
            r = Nuvem.lerListaDropbox(Nuvem.conferir(Nuvem.postarJson("${Nuvem.DBX_API}/2/files/list_folder/continue", Nuvem.acesso(c),
                JSONObject().put("cursor", r.second).toString())))
            itens += r.first
        }
        return itens
    }

    /** Um pedido só dá o tamanho e um link de 4 horas que aceita Range (o player pula no vídeo sem baixar tudo). */
    private fun item(caminho: String): Triple<String, Long, String> {
        ultimo?.let { if (it.first == caminho) return it }
        val o = JSONObject(Nuvem.conferir(Nuvem.postarJson("${Nuvem.DBX_API}/2/files/get_temporary_link", Nuvem.acesso(c),
            JSONObject().put("path", Nuvem.caminhoDropbox(caminho)).toString())))
        val link = o.optString("link")
        if (link.isEmpty()) throw IOException("BAD_NETWORK_NAME")
        return Triple(caminho, o.optJSONObject("metadata")?.optLong("size") ?: 0L, link).also { ultimo = it }
    }

    override fun tamanho(caminho: String) = item(caminho).second
    override fun ler(caminho: String, posicao: Long): InputStream = Nuvem.abrirLink(item(caminho).third, posicao)
    override fun close() {}
}
