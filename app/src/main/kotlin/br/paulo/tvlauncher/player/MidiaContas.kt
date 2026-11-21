package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.panel.Upnp
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

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
 *  - [FTP], [FTPS], [SFTP], [NFS] (14/11/2026): pastas de arquivos como o SMB (ver PastaArquivos.kt). servidor = nome ou
 *    IP com porta opcional; pasta = onde começa (NFS: o caminho exportado, ex. /volume1/video). FTPS confere o
 *    certificado como qualquer https. SFTP guarda em [token] a impressão da chave do servidor na 1ª conexão e recusa
 *    se ela mudar depois (é o "known_hosts" do ssh). NFS não tem senha (o servidor confia no IP da TV).
 *  - [ONEDRIVE], [DROPBOX] (14/11/2026): nuvem como pasta (Nuvem.kt). servidor = o nome do serviço (só para mostrar),
 *    usuario = e-mail/nome da conta, token = token de renovação, usuarioId = o id do app que o dono criou no serviço.
 *  - [BOX], [GDRIVE] (14/11/2026): como a de cima, mais o segredo do app (Client Secret) em senha.
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
        const val FTP = "ftp"
        const val FTPS = "ftps"
        const val SFTP = "sftp"
        const val NFS = "nfs"
        const val ONEDRIVE = "onedrive"
        const val DROPBOX = "dropbox"
        const val BOX = "box"
        const val GDRIVE = "gdrive"
        val TIPOS = setOf(SMB, JELLYFIN, EMBY, PLEX, WEBDAV, LINK, FTP, FTPS, SFTP, NFS, ONEDRIVE, DROPBOX, BOX, GDRIVE)
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

    /** O que a pessoa digitou para FTP, FTPS, SFTP ou NFS: tipo de conta, servidor (com porta, se veio) e pasta. */
    data class EnderecoArquivos(val tipo: String, val servidor: String, val pasta: String)

    /**
     * "ftp://nas/Filmes", "ftps://nas:990", "sftp://paulo-pc/home/paulo/Vídeos", "nfs://nas/volume1/video" ou o jeito
     * clássico do NFS "nas:/volume1/video". Sem esquema não dá para saber o protocolo: null. Usuário e senha nunca vão
     * no endereço (ftp://ana:senha@nas): null também, eles têm campo próprio.
     */
    fun lerEnderecoArquivos(texto: String): EnderecoArquivos? {
        val t = texto.trim()
        if (t.isEmpty() || t.any { it.isWhitespace() && it != ' ' } || t.contains('\\')) return null
        val baixo = t.lowercase(Locale.ROOT)
        val esquema = listOf("sftp", "ftps", "ftp", "nfs").firstOrNull { baixo.startsWith("$it://") }
        val (tipo, resto) = when {
            esquema != null -> esquema to t.substring(esquema.length + 3)
            Regex("^[A-Za-z0-9._-]+:/([^/].*)?$").matches(t) -> ContaMidia.NFS to t.replaceFirst(":/", "/")   // nas:/volume1/video
            else -> return null
        }
        val servidor = resto.substringBefore('/')
        if (servidor.contains('@') || !servidorValido(servidor)) return null
        val porta = servidor.substringAfter(':', "")
        if (porta.isNotEmpty() && (porta.toIntOrNull() ?: 0) !in 1..65535) return null
        val pasta = resto.substringAfter('/', "").split('/').filter { it.isNotEmpty() }
        if (pasta.any { it == ".." || it == "." }) return null
        if (tipo == ContaMidia.NFS && pasta.isEmpty()) return null   // NFS precisa do caminho exportado
        return EnderecoArquivos(tipo, servidor, pasta.joinToString("/"))
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
        // 17/11/2026: o player já tocava estes (libavformat e Media3), mas a lista os mostrava como "arquivo" (ex.: .ac3).
        "mp4", "mkv", "avi", "mov", "m4v", "webm", "ts", "m2ts", "mts", "mpg", "mpeg", "wmv", "flv", "3gp",
        "asf", "vob", "ogv", "rm", "rmvb", "divx", "f4v", "m2v" -> Upnp.Tipo.VIDEO
        "mp3", "m4a", "aac", "flac", "ogg", "opus", "wav", "wma", "ac3", "eac3", "dts", "mka", "m4b", "aif", "aiff", "oga" -> Upnp.Tipo.AUDIO
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
