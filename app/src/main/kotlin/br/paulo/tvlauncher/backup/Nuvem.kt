package br.paulo.tvlauncher.backup

import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.LauncherApp
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.panel.PastaArquivos
import br.paulo.tvlauncher.player.ContaMidia
import br.paulo.tvlauncher.player.MidiaArmazem
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.player.salvar
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONObject

/**
 * Nuvem como fonte de vídeo (14/11/2026): OneDrive e Dropbox viram "pastas de arquivos" como o SMB e o FTP
 * (PastaArquivos), então a tela "Na rede" lista e o player toca pelo mesmo caminho (arq://<conta>/<caminho>).
 *
 * Login: cada dono cria o SEU app no serviço (grátis, 5 minutos) e digita o identificador dele na TV. OneDrive e
 * Dropbox são "clientes públicos", sem segredo (OneDrive: código na tela, como o login do Google da TV; Dropbox: PKCE,
 * o celular mostra um código que se digita na TV). Box (14/11/2026, noite) exige segredo do app e um endereço de volta
 * https; o código vale só 30 s, então não dá para digitar: a página volta.html do site do projeto devolve o celular para
 * um porto que a TV abre só durante o login (RetornoNuvem). Google Drive (por último, pedido do dono) usa a mesma volta. O que fica guardado (cifrado, MidiaArmazem) é o token de
 * renovação em ContaMidia.token, o id do app em ContaMidia.usuarioId e, no Box, o segredo do app em ContaMidia.senha.
 * Só leitura: as permissões pedidas não deixam apagar nada.
 *
 * A parte pura (endereços, JSON, PKCE) tem teste em NuvemTest; a parte de rede só junta as peças.
 */
object Nuvem {
    // ---------------------------------------------------------------- OneDrive (Microsoft Graph)

    const val MS_LOGIN = "https://login.microsoftonline.com/common/oauth2/v2.0"
    /** Ler tudo; escrever só na pasta do próprio app (Aplicativos/<nome do app>), para o backup (ideia 98). */
    const val MS_ESCOPOS = "Files.Read Files.ReadWrite.AppFolder offline_access"
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

    // ---------------------------------------------------------------- volta do login pelo celular (Box e Drive)

    /** O "Redirect URI" que o dono põe no app do Box e no cliente do Google. Página estática do site (site/volta.html). */
    const val RETORNO = "https://chrnx0.github.io/sofaly/volta.html"

    /**
     * O "state" leva o caminho de volta: um número aleatório (a TV só aceita o código com ele), o IP da TV na rede de
     * casa e o porto. Formato "<numero>_<ip>_<porto>"; a volta.html só manda para IP de rede local.
     */
    fun estadoRetorno(numero: String, ip: String, porta: Int) = "${numero}_${ip}_$porta"

    // ---------------------------------------------------------------- Box (API 2.0)

    const val BOX_API = "https://api.box.com/2.0"
    const val BOX_TOKEN = "https://api.box.com/oauth2/token"
    const val BOX_AUTORIZAR = "https://account.box.com/api/oauth2/authorize"

    /** Client ID e Client Secret do Box: letras e números (32 hoje; aceita uma folga). */
    fun credencialBoxValida(s: String) = Regex("^[A-Za-z0-9]{16,64}$").matches(s.trim())

    fun paginaBox(clientId: String, estado: String): String =
        "$BOX_AUTORIZAR?client_id=${URLEncoder.encode(clientId, "UTF-8")}&response_type=code" +
            "&redirect_uri=${URLEncoder.encode(RETORNO, "UTF-8")}&state=${URLEncoder.encode(estado, "UTF-8")}"

    /** Um item de pasta de quem anda por id, não por caminho (Box e Google Drive). */
    class ItemComId(val item: PastaArquivos.Item, val id: String)

    /** Lista de /folders/{id}/items: os itens e o total (para pedir a página seguinte). Links ("web_link") ficam de fora. */
    fun lerListaBox(json: String): Pair<List<ItemComId>, Int> {
        val o = JSONObject(json)
        val a = o.optJSONArray("entries")
        val itens = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val e = a!!.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            when (e.optString("type")) {
                "folder" -> ItemComId(PastaArquivos.Item(e.optString("name"), true, 0), id)
                "file" -> ItemComId(PastaArquivos.Item(e.optString("name"), false, e.optLong("size")), id)
                else -> null
            }
        }
        return itens to o.optInt("total_count", itens.size)
    }

    fun boxItens(idPasta: String, offset: Int) = "$BOX_API/folders/${URLEncoder.encode(idPasta, "UTF-8")}/items?fields=name,size,type&limit=1000&offset=$offset"

    // ---------------------------------------------------------------- Google Drive (API v3, só leitura)

    const val GOOGLE_AUTORIZAR = "https://accounts.google.com/o/oauth2/v2/auth"
    const val GOOGLE_TOKEN = "https://oauth2.googleapis.com/token"
    const val DRIVE_API = "https://www.googleapis.com/drive/v3"
    /** Ler qualquer arquivo do Drive (o drive.file do backup só vê o que o próprio app criou). */
    const val DRIVE_ESCOPO = "https://www.googleapis.com/auth/drive.readonly"
    private const val PASTA_GOOGLE = "application/vnd.google-apps.folder"

    /** "ID do cliente" do Google Cloud: termina em .apps.googleusercontent.com. */
    fun clienteGoogleValido(s: String) = Regex("^[0-9]+-[a-z0-9]+\\.apps\\.googleusercontent\\.com$").matches(s.trim())
    /** "Chave secreta do cliente": hoje GOCSPX-..., letras, números, - e _. */
    fun segredoGoogleValido(s: String) = Regex("^[A-Za-z0-9_-]{16,64}$").matches(s.trim())

    /** Página de autorização: PKCE + offline (para vir o token de renovação) + consent (sempre vem de novo). */
    fun paginaDrive(clientId: String, desafio: String, estado: String): String =
        "$GOOGLE_AUTORIZAR?client_id=${URLEncoder.encode(clientId, "UTF-8")}&response_type=code" +
            "&redirect_uri=${URLEncoder.encode(RETORNO, "UTF-8")}&scope=${URLEncoder.encode(DRIVE_ESCOPO, "UTF-8")}" +
            "&access_type=offline&prompt=consent&code_challenge=$desafio&code_challenge_method=S256" +
            "&state=${URLEncoder.encode(estado, "UTF-8")}"

    /** Filhos da pasta [idPasta] ("root" = Meu Drive), sem os da lixeira; pastas primeiro. */
    fun driveFilhos(idPasta: String, pagina: String?): String {
        val q = "'${idPasta.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}' in parents and trashed=false"
        return "$DRIVE_API/files?q=${URLEncoder.encode(q, "UTF-8")}&fields=${URLEncoder.encode("nextPageToken,files(id,name,size,mimeType)", "UTF-8")}" +
            "&pageSize=1000&orderBy=folder,name" + (pagina?.let { "&pageToken=" + URLEncoder.encode(it, "UTF-8") } ?: "")
    }

    /** Lista do Drive. Documentos, planilhas e atalhos do Google (não são arquivos de verdade) ficam de fora. */
    fun lerListaDrive(json: String): Pair<List<ItemComId>, String?> {
        val o = JSONObject(json)
        val a = o.optJSONArray("files")
        val itens = (0 until (a?.length() ?: 0)).mapNotNull { i ->
            val e = a!!.optJSONObject(i) ?: return@mapNotNull null
            val id = e.optString("id").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            val tipo = e.optString("mimeType")
            when {
                tipo == PASTA_GOOGLE -> ItemComId(PastaArquivos.Item(e.optString("name"), true, 0), id)
                tipo.startsWith("application/vnd.google-apps.") -> null
                else -> ItemComId(PastaArquivos.Item(e.optString("name"), false, e.optString("size").toLongOrNull() ?: 0L), id)
            }
        }
        return itens to o.optString("nextPageToken").ifEmpty { null }
    }

    fun driveConteudo(idArquivo: String) = "$DRIVE_API/files/${idArquivo.filter { it.isLetterOrDigit() || it == '-' || it == '_' }}?alt=media"

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
            setRequestProperty("User-Agent", Http.USER_AGENT)
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

    /** Pedido com corpo qualquer (envio do backup, ideia 98). [extras]: cabeçalhos a mais (ex. Dropbox-API-Arg). */
    fun enviar(url: String, metodo: String, token: String, corpo: ByteArray?, tipo: String?, extras: Map<String, String> = emptyMap()): Resposta {
        val c = conexao(url, metodo, mapOf("Authorization" to "Bearer $token") + (tipo?.let { mapOf("Content-Type" to it) } ?: emptyMap()) + extras)
        if (corpo != null) { c.doOutput = true; c.setFixedLengthStreamingMode(corpo.size); c.outputStream.use { it.write(corpo) } }
        return ler(c)
    }

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
    fun abrirLink(url: String, posicao: Long, token: String? = null): InputStream {
        require(url.startsWith("https://")) { "link de download sem https" }
        // Com token (Google Drive), só para o próprio Drive, e sem seguir redirecionamento.
        if (token != null) require(url.startsWith("$DRIVE_API/")) { "token só vai para o Drive" }
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 30_000
            if (token != null) { setRequestProperty("Authorization", "Bearer $token"); instanceFollowRedirects = false }
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

    /**
     * Box: /files/{id}/content responde 302 com o endereço do arquivo (vale ~15 min). Não seguimos o 302 sozinhos
     * para o token não ir junto ao outro servidor; o endereço já leva a autorização dentro.
     */
    fun linkBox(idArquivo: String, token: String): String {
        val c = conexao("$BOX_API/files/${URLEncoder.encode(idArquivo, "UTF-8")}/content", "GET", mapOf("Authorization" to "Bearer $token"))
        try {
            val cod = c.responseCode
            if (cod == 302 || cod == 301 || cod == 303 || cod == 307) return c.getHeaderField("Location") ?: throw IOException("sem endereço")
            conferir(Resposta(cod, ""))
            throw IOException("HTTP $cod")   // 202 = o Box ainda está preparando o arquivo
        } finally { c.disconnect() }
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
            // O Box troca o token de renovação a cada uso (o velho deixa de valer): por isso o novo é gravado logo abaixo.
            ContaMidia.BOX -> postarForm(BOX_TOKEN, mapOf("client_id" to atual.usuarioId, "client_secret" to atual.senha,
                "grant_type" to "refresh_token", "refresh_token" to atual.token))
            ContaMidia.GDRIVE -> postarForm(GOOGLE_TOKEN, mapOf("client_id" to atual.usuarioId, "client_secret" to atual.senha,
                "grant_type" to "refresh_token", "refresh_token" to atual.token))
            else -> throw IOException("não é nuvem")
        }
        // invalid_grant = a pessoa tirou a permissão ou o token venceu: só entrando de novo.
        if (r.codigo == 400 || r.codigo == 401) throw SecurityException("401 NUVEM_ENTRAR_DE_NOVO")
        val t = lerTokens(conferir(r))
        if (t.renovacao != null && t.renovacao != atual.token) {
            // O Box invalida o token velho: se não gravar o novo, a conta pede login de novo na próxima renovação.
            val gravou = runCatching { MidiaArmazem.salvar(LauncherApp.instancia, atual.copy(token = t.renovacao)) }.getOrNull() != null
            if (!gravou) android.util.Log.w("TvLauncher.Nuvem", "não gravei o token de renovação novo (${c.tipo})")
        }
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

/**
 * Pasta de quem anda por id, não por caminho (Box e Google Drive): os caminhos viram ids pelo que já foi listado (ou
 * andando da raiz). [raiz] é o id da raiz ("0" no Box, "root" no Drive).
 */
abstract class PastaPorId(raiz: String) : PastaArquivos {
    private val ids = HashMap<String, Pair<String, Long>>().apply { put("", raiz to 0L) }   // caminho -> (id, tamanho)

    protected abstract fun filhos(idPasta: String): List<Nuvem.ItemComId>
    protected abstract fun abrir(idArquivo: String, posicao: Long): InputStream

    private fun normal(caminho: String) = caminho.split('/').filter { it.isNotEmpty() }.joinToString("/")

    private fun guardar(pai: String, lista: List<Nuvem.ItemComId>) =
        lista.forEach { f -> ids[if (pai.isEmpty()) f.item.nome else "$pai/${f.item.nome}"] = f.id to f.item.tamanho }

    protected fun id(caminho: String): Pair<String, Long> = synchronized(ids) {
        val n = normal(caminho)
        ids[n]?.let { return it }
        val pai = n.substringBeforeLast('/', "")
        guardar(pai, filhos(id(pai).first))
        ids[n] ?: throw IOException("BAD_NETWORK_NAME")
    }

    override fun listar(caminho: String): List<PastaArquivos.Item> {
        val n = normal(caminho)
        val lista = filhos(id(n).first)
        synchronized(ids) { guardar(n, lista) }
        return lista.map { it.item }
    }

    override fun tamanho(caminho: String) = id(caminho).second
    override fun ler(caminho: String, posicao: Long): InputStream = abrir(id(caminho).first, posicao)
    override fun close() {}
}

/** Box como pasta (API 2.0). */
class PastaBox(private val c: ContaMidia) : PastaPorId("0") {
    private var ultimo: Triple<String, String, Long>? = null   // id, link, quando pegou (o link vale ~15 min)

    override fun filhos(idPasta: String): List<Nuvem.ItemComId> {
        val itens = ArrayList<Nuvem.ItemComId>()
        var paginas = 0
        while (paginas++ < 40) {
            val (lote, total) = Nuvem.lerListaBox(Nuvem.conferir(Nuvem.get(Nuvem.boxItens(idPasta, itens.size), Nuvem.acesso(c))))
            itens += lote
            if (lote.isEmpty() || itens.size >= total) break
        }
        return itens
    }

    override fun abrir(idArquivo: String, posicao: Long): InputStream {
        val agora = System.currentTimeMillis()
        val link = ultimo?.takeIf { it.first == idArquivo && agora - it.third < 10 * 60_000 }?.second
            ?: Nuvem.linkBox(idArquivo, Nuvem.acesso(c)).also { ultimo = Triple(idArquivo, it, agora) }
        return Nuvem.abrirLink(link, posicao)
    }
}

/** Google Drive como pasta (API v3, drive.readonly). O arquivo vem do próprio Drive, com o token e Range. */
class PastaDrive(private val c: ContaMidia) : PastaPorId("root") {
    override fun filhos(idPasta: String): List<Nuvem.ItemComId> {
        val itens = ArrayList<Nuvem.ItemComId>()
        var pagina: String? = null
        var n = 0
        do {
            val (lote, prox) = Nuvem.lerListaDrive(Nuvem.conferir(Nuvem.get(Nuvem.driveFilhos(idPasta, pagina), Nuvem.acesso(c))))
            itens += lote; pagina = prox
        } while (pagina != null && n++ < 40)
        return itens
    }

    override fun abrir(idArquivo: String, posicao: Long): InputStream =
        Nuvem.abrirLink(Nuvem.driveConteudo(idArquivo), posicao, Nuvem.acesso(c))
}
