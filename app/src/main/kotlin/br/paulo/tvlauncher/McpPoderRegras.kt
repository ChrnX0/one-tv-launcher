package br.paulo.tvlauncher

/**
 * Regras puras das ferramentas "de poder" do MCP (McpPoder.kt, 10/11/2026). Nada de Android aqui: os testes rodam no PC
 * (McpPoderRegrasTest). Cada função recusa o que não serve e devolve null, em vez de deixar passar algo estranho para o
 * shell ou para o instalador.
 */
object McpPoderRegras {
    const val MAX_TECLAS = 30
    const val MAX_TEXTO = 500
    const val MAX_LER_BYTES = 256 * 1024          // ler arquivo de texto: até 256 KB por chamada
    const val MAX_ESCREVER_BYTES = 1024 * 1024    // escrever arquivo de texto: até 1 MB

    /** URL do APK: só HTTPS (o launcher baixa com a verificação de TLS normal do Android) e com host. */
    fun urlApkValida(url: String?): Boolean {
        val u = url?.trim() ?: return false
        if (u.length > 2000 || !u.startsWith("https://", ignoreCase = true)) return false
        val host = u.substring(8).substringBefore('/').substringBefore('?').substringBefore('#')
        return host.isNotEmpty() && !host.contains('@') && host.none { it.isWhitespace() }
    }

    /**
     * Link para abrir na TV (YouTube, Netflix, site...). Recusa esquemas que leem arquivos ou rodam código no lugar de
     * abrir uma tela: file, content, javascript, data e intent (este último monta qualquer intent, inclusive interno).
     */
    private val ESQUEMAS_PROIBIDOS = setOf("file", "content", "javascript", "data", "intent", "android-app")

    fun linkValido(url: String?): Boolean {
        val u = url?.trim() ?: return false
        if (u.isEmpty() || u.length > 2000 || u.any { it.isWhitespace() || it.isISOControl() }) return false
        val esquema = u.substringBefore(':', "").lowercase()
        if (esquema.isEmpty() || !esquema.matches(Regex("[a-z][a-z0-9+.-]*"))) return false
        return esquema !in ESQUEMAS_PROIBIDOS
    }

    /**
     * Argumento pronto para `input text '<aqui>'`. O `input text` só digita ASCII (sem acento): fora disso, null.
     * Espaço vira %s (é como o input entende espaço) e a aspa simples sai da string ('\'') para o shell não quebrar.
     */
    fun textoParaInput(t: String?): String? {
        if (t.isNullOrEmpty() || t.length > MAX_TEXTO) return null
        if (t.any { it.code < 0x20 || it.code > 0x7E }) return null
        return t.replace("'", "'\\''").replace(" ", "%s")
    }

    /** "07:30" ou "7:05" -> (7, 30). */
    fun hora(t: String?): Pair<Int, Int>? {
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(t?.trim() ?: return null) ?: return null
        val h = m.groupValues[1].toInt(); val min = m.groupValues[2].toInt()
        return if (h in 0..23 && min in 0..59) h to min else null
    }

    private val DIAS = listOf("dom", "seg", "ter", "qua", "qui", "sex", "sab")

    /**
     * Dias da semana -> bits do Despertador (bit 0 = domingo). Aceita "seg", "segunda", "sábado", "todos", "uteis" e
     * "fim de semana". Lista vazia = toca uma vez só (0). Dia desconhecido = null.
     */
    fun dias(lista: List<String>): Int? {
        var bits = 0
        for (bruto in lista) {
            val d = bruto.trim().lowercase().replace("á", "a").replace("ç", "c").replace("-feira", "")
            bits = bits or when {
                d == "todos" || d == "todo dia" || d == "diario" -> 0b1111111
                d == "uteis" || d == "úteis" || d == "dias uteis" -> 0b0111110
                d == "fim de semana" || d == "fds" -> 0b1000001
                d.length >= 3 && DIAS.indexOf(d.take(3)) >= 0 -> 1 shl DIAS.indexOf(d.take(3))
                else -> return null
            }
        }
        return bits
    }

    /**
     * O texto da pergunta na TV antes de um comando de shell. Espaços e quebras de linha em sequência viram um espaço só
     * (senão "echo ok;" + 200 espaços + "pm uninstall x" aparecia como inofensivo) e, se ainda for longo, avisa quanto
     * ficou de fora (auditoria 10/11/2026).
     */
    fun textoPerguntaShell(cmd: String, max: Int = 220): String {
        val visivel = cmd.replace(Regex("\\s+"), " ").trim()
        val corpo = if (visivel.length <= max) visivel else visivel.take(max) + " … [+${visivel.length - max} caracteres não mostrados]"
        return "Rodar no shell: $corpo"
    }

    fun sha256Valido(s: String?): Boolean = s != null && s.matches(Regex("^[0-9a-fA-F]{64}$"))

    /** Texto de arquivo que dá para devolver ao modelo: sem bytes nulos (binário) e UTF-8 decodificável. */
    fun pareceTexto(b: ByteArray): Boolean {
        if (b.any { it == 0.toByte() }) return false
        return try {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(b)); true
        } catch (e: java.nio.charset.CharacterCodingException) { false }
    }
}

/** Regras puras da segunda leva (McpPoder2.kt): MAC do Wake-on-LAN, pacote mágico e nome do arquivo baixado. */
object McpPoderRegras2 {
    /** "AA:BB:CC:DD:EE:FF", "aa-bb-..." ou "aabbccddeeff" -> 6 bytes. */
    fun mac(t: String?): ByteArray? {
        val limpo = t?.trim()?.replace(":", "")?.replace("-", "")?.lowercase() ?: return null
        if (!limpo.matches(Regex("^[0-9a-f]{12}$"))) return null
        return ByteArray(6) { limpo.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** O pacote mágico do Wake-on-LAN: 6 bytes 0xFF e o MAC repetido 16 vezes (102 bytes). */
    fun pacoteMagico(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        return ByteArray(102) { i -> if (i < 6) 0xFF.toByte() else mac[(i - 6) % 6] }
    }

    /** Nome do arquivo a partir da URL, quando não vem um: último pedaço do caminho, sem ?query. */
    fun nomeDaUrl(url: String): String? {
        // Só o caminho (sem esquema e host): "https://x.com/" não vira um arquivo chamado "x.com".
        val caminho = url.substringBefore('?').substringBefore('#').substringAfter("://").substringAfter('/', "").trimEnd('/')
        val nome = java.net.URLDecoder.decode(caminho.substringAfterLast('/'), "UTF-8")
        return nome.takeIf { it.isNotBlank() && it != "." && it != ".." && it.none { c -> c == '/' || c == '\\' || c.code < 0x20 } && it.length <= 200 }
    }

    /** Elemento da tela casa com o texto pedido? Igualdade vence "contém" (quem chama ordena por isso). Sem acento/caixa. */
    fun normalizar(t: String): String = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "").lowercase().replace(Regex("\\s+"), " ").trim()

    fun nota(alvo: String, texto: String): Int {
        val a = normalizar(alvo); val t = normalizar(texto)
        if (a.isEmpty() || t.isEmpty()) return 0
        return when { t == a -> 3; t.startsWith(a) -> 2; t.contains(a) -> 1; else -> 0 }
    }
}
