package br.paulo.tvlauncher

import java.util.Locale

/**
 * Parte pura (testável na JVM) do seletor de pastas e arquivos do Sofaly (21/11/2026, TvDocumentsProvider +
 * DocumentPickerActivity).
 *
 * Porquê: o Google TV não traz o seletor de documentos do Android; um app que pede "escolha uma pasta"
 * (OPEN_DOCUMENT_TREE, ex.: o RetroArch da Play) recebia "nenhum app compatível".
 * Para quê: qualquer app da TV escolhe pasta ou arquivo da memória, do pen drive ou do "Na rede", com o controle.
 *
 * Id de documento: "<tipo>:<chave>/<caminho>", ex.: "local:interna/Download/roms", "local:8991-1F25/" (raiz do pen
 * drive), "net:a1b2/Jogos". Tipo "local" = volume do aparelho (chave "interna" ou o UUID do volume); "net" = conta do
 * "Na rede" (chave = id da conta). Caminho relativo, com "/", sem barra no começo ("" = a raiz).
 */
object DocumentIds {
    const val LOCAL = "local"
    const val NET = "net"
    const val INTERNA = "interna"

    data class Id(val tipo: String, val chave: String, val caminho: String) {
        val raiz: String get() = "$tipo:$chave"
        override fun toString() = "$tipo:$chave/$caminho"
        val nome: String get() = caminho.substringAfterLast('/')
        val pai: Id? get() = if (caminho.isEmpty()) null else copy(caminho = caminho.substringBeforeLast('/', ""))
        fun filho(nome: String) = copy(caminho = if (caminho.isEmpty()) nome else "$caminho/$nome")
    }

    fun raiz(tipo: String, chave: String) = Id(tipo, chave, "")

    /** null = id malformado ou caminho que tenta sair da raiz. */
    fun ler(id: String?): Id? {
        if (id == null || id.length > 4200) return null
        val dois = id.indexOf(':')
        if (dois <= 0) return null
        val tipo = id.substring(0, dois)
        if (tipo != LOCAL && tipo != NET) return null
        val resto = id.substring(dois + 1)
        val barra = resto.indexOf('/')
        val chave = if (barra < 0) resto else resto.substring(0, barra)
        val caminho = if (barra < 0) "" else resto.substring(barra + 1).trimEnd('/')
        if (chave.isEmpty() || chave.any { it == '/' || it == ':' || it.code < 32 }) return null
        if (caminho.isNotEmpty() && !caminhoValido(caminho)) return null
        return Id(tipo, chave, caminho)
    }

    /** A mesma regra de Pastas.caminhoValido (aqui sem depender dela, para o teste puro): nunca sai da raiz. */
    fun caminhoValido(caminho: String): Boolean =
        caminho.length <= 4096 && !caminho.startsWith("/") && caminho.none { it.code < 32 || it == '\\' } &&
            caminho.split('/').none { it == ".." || it == "." || it.isEmpty() }

    /** [filho] está dentro de [pai] (ou é ele)? O Android pergunta isso a cada acesso por uma pasta escolhida. */
    fun dentro(pai: Id, filho: Id): Boolean =
        pai.tipo == filho.tipo && pai.chave == filho.chave &&
            (pai.caminho.isEmpty() || filho.caminho == pai.caminho || filho.caminho.startsWith(pai.caminho + "/"))

    /**
     * Pasta "Android" da memória interna (dados e obb dos outros apps): fica de fora, como no seletor do Android e na
     * ferramenta de arquivos do MCP. Só na memória interna: no pen drive, "Android" não guarda dados de app.
     */
    fun protegido(id: Id): Boolean =
        id.tipo == LOCAL && id.chave == INTERNA && id.caminho.substringBefore('/').equals("Android", ignoreCase = true)

    /** Nome que se pode criar numa pasta: sem barra, sem controle, sem "." / "..", até 255 caracteres. */
    fun nomeValido(nome: String): Boolean =
        nome.isNotBlank() && nome.length <= 255 && nome != "." && nome != ".." &&
            nome.none { it == '/' || it == '\\' || it.code < 32 }

    /** "foto.jpg" que já existe vira "foto (1).jpg", "foto (2).jpg"... (como o seletor do Android). */
    fun nomeLivre(nome: String, existentes: Set<String>): String {
        if (nome !in existentes) return nome
        val ponto = nome.lastIndexOf('.').takeIf { it > 0 } ?: nome.length
        val base = nome.substring(0, ponto)
        val ext = nome.substring(ponto)
        var n = 1
        while ("$base ($n)$ext" in existentes) n++
        return "$base ($n)$ext"
    }

    /**
     * O tipo do arquivo serve para o pedido? [filtros] vem do app ("image/png", "video/ *", "* / *"); vazio = aceita
     * tudo. Arquivo de tipo desconhecido ("application/octet-stream") só passa quando o app aceita tudo ou esse tipo:
     * uma ROM ".sfc" não tem tipo conhecido, e o RetroArch pede "* / *".
     */
    fun tipoServe(mime: String, filtros: List<String>): Boolean {
        val f = filtros.map { it.trim().lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (f.isEmpty()) return true
        val m = mime.lowercase(Locale.ROOT)
        return f.any { filtro ->
            filtro == "*/*" || filtro == "*" || filtro == m ||
                (filtro.endsWith("/*") && m.substringBefore('/') == filtro.substringBefore('/'))
        }
    }

    /** O que o app pediu: escolher pasta, abrir arquivo ou criar arquivo. */
    enum class Modo { PASTA, ABRIR, CRIAR }

    fun modo(acao: String?): Modo? = when (acao) {
        "android.intent.action.OPEN_DOCUMENT_TREE" -> Modo.PASTA
        "android.intent.action.OPEN_DOCUMENT", "android.intent.action.GET_CONTENT" -> Modo.ABRIR
        "android.intent.action.CREATE_DOCUMENT" -> Modo.CRIAR
        else -> null
    }
}
