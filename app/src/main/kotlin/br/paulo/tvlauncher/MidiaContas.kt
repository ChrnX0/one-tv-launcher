package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Mídias da rede (07/11/2026): os servidores que a pessoa ADICIONA à mão na tela "Na rede" (os UPnP/DLNA aparecem
 * sozinhos e não ficam aqui). Feito para qualquer casa: nada fixo, tudo vem do que a pessoa digita.
 *
 *  - [SMB]: pasta compartilhada do Windows, de um NAS ou do roteador com disco. servidor = nome ou IP, pasta = o nome do
 *    compartilhamento ("Filmes"), usuário/senha (vazios = convidado).
 *  - [JELLYFIN] e [EMBY]: endereço do servidor (http://ip:8096) e o token do login (a senha não é guardada; ver Jellyfin.kt).
 *  - [PLEX] (10/11/2026): endereço do servidor, token do servidor (vem do login por código em plex.tv/link, ver Plex.kt) e,
 *    em usuarioId, o identificador do servidor (para reconhecer o mesmo servidor achado na rede).
 *  - [WEBDAV] (10/11/2026): endereço base (Nextcloud: https://nuvem/remote.php/dav/files/<usuário>), usuário e senha.
 *  - [LINK] (10/11/2026): um endereço de vídeo (http/https/HLS) ou de uma lista M3U, sem login.
 *
 * Só texto e regras aqui (testes em MidiaContasTest). Quem grava cifrado é o MidiaArmazem.
 */
data class ContaMidia(
    val id: String,
    val tipo: String,
    val nome: String,
    val servidor: String,
    val pasta: String = "",
    val usuario: String = "",
    val senha: String = "",
    val dominio: String = "",
    val token: String = "",
    val usuarioId: String = "",
) {
    companion object {
        const val SMB = "smb"
        const val JELLYFIN = "jellyfin"
        const val EMBY = "emby"
        const val PLEX = "plex"
        const val WEBDAV = "webdav"
        const val LINK = "link"
        val TIPOS = setOf(SMB, JELLYFIN, EMBY, PLEX, WEBDAV, LINK)
    }

    /** Jellyfin e Emby falam a mesma língua (o Jellyfin nasceu do Emby): o mesmo código serve aos dois. */
    val ehJellyOuEmby get() = tipo == JELLYFIN || tipo == EMBY
}

object MidiaContas {
    const val MAX = 20

    fun paraJson(contas: List<ContaMidia>): String = JSONArray().apply {
        contas.forEach { c ->
            put(JSONObject().put("id", c.id).put("tipo", c.tipo).put("nome", c.nome).put("servidor", c.servidor)
                .put("pasta", c.pasta).put("usuario", c.usuario).put("senha", c.senha).put("dominio", c.dominio)
                .put("token", c.token).put("usuarioId", c.usuarioId))
        }
    }.toString()

    fun deJson(texto: String?): List<ContaMidia> {
        if (texto.isNullOrBlank()) return emptyList()
        val a = runCatching { JSONArray(texto) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val tipo = o.optString("tipo")
            if (tipo !in ContaMidia.TIPOS) return@mapNotNull null
            ContaMidia(o.optString("id"), tipo, o.optString("nome"), o.optString("servidor"), o.optString("pasta"),
                o.optString("usuario"), o.optString("senha"), o.optString("dominio"), o.optString("token"), o.optString("usuarioId"))
        }.filter { it.id.isNotEmpty() && it.servidor.isNotEmpty() }.take(MAX)
    }

    /**
     * Lê o endereço de uma pasta do jeito que a pessoa escrever: "\\NAS\Filmes", "//192.168.0.5/Filmes/Séries",
     * "smb://nas/filmes" ou "nas/filmes". Devolve servidor, compartilhamento e a subpasta (pode ser vazia), ou null se
     * faltar o servidor ou o compartilhamento.
     */
    fun lerEnderecoSmb(texto: String): Triple<String, String, String>? {
        var t = texto.trim().replace('\\', '/')
        if (t.lowercase(Locale.ROOT).startsWith("smb://")) t = t.substring(6)
        t = t.trimStart('/')
        val partes = t.split('/').map { it.trim() }.filter { it.isNotEmpty() }
        if (partes.size < 2) return null
        val servidor = partes[0]
        if (!servidorValido(servidor)) return null
        return Triple(servidor, partes[1], partes.drop(2).joinToString("/"))
    }

    /** Nome de máquina, IPv4 ou IPv4:porta. Sem espaços, barras nem caracteres de controle. */
    fun servidorValido(s: String): Boolean = s.isNotEmpty() && s.length <= 253 && s.all { it.isLetterOrDigit() || it in ".-_:" }

    /** O endereço do Jellyfin como a pessoa digitar ("192.168.0.5", "nas:8096", "https://jf.casa.com"): com esquema e porta. */
    fun lerEnderecoJellyfin(texto: String): String? {
        var t = texto.trim().trimEnd('/')
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        if (!t.startsWith("http://") && !t.startsWith("https://")) t = "http://$t"
        val semEsquema = t.substringAfter("://")
        val host = semEsquema.substringBefore('/').substringBefore(':')
        if (!servidorValido(host)) return null
        val temPorta = semEsquema.substringBefore('/').contains(':')
        return if (temPorta || t.startsWith("https://")) t else t.replaceFirst(host, "$host:8096")
    }

    /**
     * Endereço do WebDAV como a pessoa digitar: "nuvem.casa.com/remote.php/dav/files/ana", "http://nas:5005/video".
     * Sem esquema = https (WebDAV pela internet sem TLS mandaria a senha em claro). Devolve sem a barra do fim, ou null.
     */
    fun lerEnderecoWebDav(texto: String): String? {
        var t = texto.trim().trimEnd('/')
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val baixo = t.lowercase(Locale.ROOT)
        t = when {
            baixo.startsWith("davs://") -> "https://" + t.substring(7)
            baixo.startsWith("dav://") -> "http://" + t.substring(6)
            baixo.startsWith("http://") || baixo.startsWith("https://") -> t
            else -> "https://$t"
        }
        val host = t.substringAfter("://").substringBefore('/').substringBefore(':')
        if (!servidorValido(host) || t.contains('@')) return null   // usuário e senha vão nos campos, nunca no endereço
        return t
    }

    /** Link de vídeo ou lista: só http/https (o player não toca rtmp/udp), sem espaços. */
    fun lerLink(texto: String): String? {
        val t = texto.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() }) return null
        val baixo = t.lowercase(Locale.ROOT)
        if (!baixo.startsWith("http://") && !baixo.startsWith("https://")) return null
        val host = t.substringAfter("://").substringBefore('/').substringBefore('?').substringAfterLast('@').substringBefore(':')
        return if (host.isNotEmpty() && servidorValido(host)) t else null
    }

    /** Nome curto de um link para a lista: o nome do arquivo sem extensão, ou o servidor. */
    fun nomeDoLink(url: String): String {
        val caminho = url.substringAfter("://").substringBefore('?').substringBefore('#')
        val arquivo = caminho.substringAfterLast('/', "").substringBeforeLast('.').let {
            runCatching { java.net.URLDecoder.decode(it, "UTF-8") }.getOrDefault(it)
        }
        return arquivo.ifBlank { caminho.substringBefore('/') }
    }

    /** Vídeo, música, foto ou outro, pela extensão do arquivo (SMB não diz o tipo). */
    fun tipoPorNome(nome: String): Upnp.Tipo = when (nome.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "wmv", "flv", "3gp" -> Upnp.Tipo.VIDEO
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma" -> Upnp.Tipo.AUDIO
        "jpg", "jpeg", "png", "webp", "gif", "bmp", "heic" -> Upnp.Tipo.FOTO
        else -> Upnp.Tipo.OUTRO
    }

    /** Junta pasta e nome com "/", sem barras duplas. */
    fun juntar(pasta: String, nome: String): String = listOf(pasta.trim('/'), nome.trim('/')).filter { it.isNotEmpty() }.joinToString("/")

    /**
     * O endereço que vai para o player: smb://<id da conta>/<caminho>. Leva o ID da conta, nunca a senha: quem abre o
     * arquivo (SmbFonte) busca usuário e senha no armazém cifrado.
     */
    fun uriSmb(conta: ContaMidia, caminho: String): String =
        "smb://${conta.id}/" + caminho.split('/').joinToString("/") { java.net.URLEncoder.encode(it, "UTF-8").replace("+", "%20") }

    /** O contrário de [uriSmb]: id da conta e caminho dentro do compartilhamento. */
    fun lerUriSmb(uri: String): Pair<String, String>? {
        if (!uri.startsWith("smb://")) return null
        val resto = uri.substring(6)
        val id = resto.substringBefore('/')
        val caminho = resto.substringAfter('/', "").split('/').joinToString("/") { java.net.URLDecoder.decode(it, "UTF-8") }
        if (id.isEmpty() || caminho.isEmpty() || caminho.split('/').any { it == ".." }) return null
        return id to caminho
    }
}
