package br.paulo.tvlauncher

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/**
 * HTTP mínimo do painel no navegador (ver PainelWeb.kt). Sem biblioteca de propósito: o NanoHTTPD custava 30-60 KB e
 * gravava upload em arquivo temporário (plano do painel, seção 6). Aqui só o que o painel usa: GET e POST, corpo com
 * Content-Length, sempre "Connection: close" (uma requisição por conexão, sem keep-alive para administrar).
 *
 * Este arquivo não usa NADA do Android: assim os testes rodam no computador (app/src/test, PainelHttpTest).
 */

/**
 * Requisição com os cabeçalhos já lidos. O CORPO só é lido quando a rota pede ([lerCorpo] ou [fluxoCorpo]): assim um
 * envio de arquivo de vários GB vai direto para o disco, e a rota confere sessão e PIN antes de receber um byte dele.
 * [cabecalhos] tem os nomes em minúsculas. [caminho] vem cru (sem decodificar %xx). [tamanho] = Content-Length.
 */
class Requisicao(
    val metodo: String,
    val caminho: String,
    val consulta: Map<String, String>,
    val cabecalhos: Map<String, String>,
    val tamanho: Long,
    private val entrada: InputStream?,
    private val relogio: () -> Long = System::currentTimeMillis,
) {
    private var lido: ByteArray? = if (tamanho == 0L) ByteArray(0) else null

    fun cabecalho(nome: String): String? = cabecalhos[nome.lowercase()]

    /** Lê o corpo inteiro para a memória, se couber em [limite] bytes (senão 413). Chamar de novo devolve o mesmo. */
    fun lerCorpo(limite: Int): ByteArray {
        lido?.let { return it }
        if (tamanho > limite) throw ErroHttp(413, "corpo grande demais")
        return PainelHttp.lerExato(entrada ?: throw IOException("sem corpo"), tamanho.toInt(), relogio = relogio).also { lido = it }
    }

    /**
     * Lê e joga fora o corpo (até [limite] bytes, com o mesmo prazo total de [PainelHttp.PRAZO_CORPO_MS]), sem guardar
     * na memória. Para responder um erro ANTES do corpo: se a conexão fechar com dados ainda chegando, o Linux manda
     * RST e o navegador acusa "falha de conexão" em vez de mostrar o erro (ex.: 401 "entre com o PIN").
     * Devolve false se não deu (grande demais, demorou, caiu): aí a conexão só fecha.
     */
    fun descartarCorpo(limite: Int): Boolean {
        if (lido != null) return true
        if (tamanho > limite) return false
        val dentro = entrada ?: return false
        val fim = relogio() + PainelHttp.PRAZO_CORPO_MS
        val buf = ByteArray(8192)
        var faltam = tamanho
        return try {
            while (faltam > 0) {
                if (relogio() > fim) return false
                val n = dentro.read(buf, 0, minOf(buf.size.toLong(), faltam).toInt())
                if (n < 0) return false
                faltam -= n
            }
            lido = ByteArray(0)
            true
        } catch (e: IOException) { false }
    }

    /** O corpo já lido por [lerCorpo]. */
    val corpo: ByteArray get() = lido ?: throw IllegalStateException("corpo ainda não lido")
    fun corpoTexto(): String = String(corpo, Charsets.UTF_8)

    /** O corpo como fluxo, que termina exatamente em [tamanho] bytes (para envio de arquivo grande). Uma vez só. */
    fun fluxoCorpo(): InputStream {
        check(lido == null || tamanho == 0L) { "corpo já lido" }
        lido = ByteArray(0)
        return Limitado(entrada ?: ByteArray(0).inputStream(), tamanho)
    }

    /** Não deixa ler além do Content-Length (o que viesse depois não é deste pedido). */
    private class Limitado(private val dentro: InputStream, private var faltam: Long) : InputStream() {
        override fun read(): Int {
            if (faltam <= 0) return -1
            val b = dentro.read()
            if (b < 0) throw IOException("corpo incompleto")
            faltam--
            return b
        }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (faltam <= 0) return -1
            val n = dentro.read(b, off, minOf(len.toLong(), faltam).toInt())
            if (n < 0) throw IOException("corpo incompleto (faltaram $faltam bytes)")
            faltam -= n
            return n
        }
    }
}

/** Erro que vira uma resposta HTTP com este código (ex.: 413 corpo grande demais). */
class ErroHttp(val codigo: Int, mensagem: String) : IOException(mensagem)

object PainelHttp {
    const val LIMITE_CABECALHOS = 8 * 1024   // linha de pedido + cabeçalhos juntos
    /**
     * Prazo TOTAL para os cabeçalhos chegarem (revisão 09/11/2026). O tempo de leitura do socket vale por byte: sem
     * isto, um aparelho mandando 1 byte a cada 14 s segurava uma thread por horas, e 4 conexões assim travavam o painel.
     */
    const val PRAZO_CABECALHOS_MS = 10_000L

    /**
     * Prazo TOTAL para um corpo lido para a memória (auditoria 10/11/2026). Mesmo motivo do prazo dos cabeçalhos: o
     * tempo do socket vale por leitura, e 1 byte a cada 14 s prendia uma thread por horas. 30 s sobram para os 6 MB
     * do maior corpo (restaurar backup completo) numa rede local lenta.
     */
    const val PRAZO_CORPO_MS = 30_000L

    /**
     * Lê uma requisição INTEIRA de [entrada] (cabeçalhos e corpo). [limiteCorpo] é o maior corpo aceito (bytes).
     * Lança [ErroHttp] para pedido malformado ou grande demais, e IOException se a conexão cair no meio.
     */
    fun ler(entrada: InputStream, limiteCorpo: Int, prazoMs: Long = PRAZO_CABECALHOS_MS): Requisicao =
        lerCabecalhos(entrada, aceitarCorpo = true, prazoMs = prazoMs).also { it.lerCorpo(limiteCorpo) }

    /**
     * Lê só a linha de pedido e os cabeçalhos; o corpo fica na conexão até a rota pedir (ver [Requisicao]).
     */
    fun lerCabecalhos(entrada: InputStream, aceitarCorpo: Boolean = true, prazoMs: Long = PRAZO_CABECALHOS_MS,
                      relogio: () -> Long = System::currentTimeMillis): Requisicao {
        val linhas = linhasCabecalho(entrada, prazoMs, relogio)
        val pedido = linhas.first().split(' ')
        if (pedido.size != 3 || !pedido[2].startsWith("HTTP/1.")) throw ErroHttp(400, "linha de pedido inválida")
        val metodo = pedido[0]
        val alvo = pedido[1]
        if (!alvo.startsWith("/")) throw ErroHttp(400, "alvo inválido")

        val cabecalhos = HashMap<String, String>()
        for (linha in linhas.drop(1)) {
            val doisPontos = linha.indexOf(':')
            if (doisPontos <= 0) throw ErroHttp(400, "cabeçalho inválido")
            // "Content-Length : 5" (espaço antes dos dois-pontos): a RFC 9112 manda recusar. É truque de "request
            // smuggling" (um proxy lê um tamanho, nós outro). Antes o nome era aparado e aceito.
            if (linha[doisPontos - 1] == ' ' || linha[doisPontos - 1] == '\t') throw ErroHttp(400, "espaço antes de ':'")
            val nome = linha.substring(0, doisPontos).trim().lowercase()
            val valor = linha.substring(doisPontos + 1).trim()
            // Host repetido é o truque clássico para enganar a checagem de Host: recusa.
            if (nome == "host" && cabecalhos.containsKey("host")) throw ErroHttp(400, "Host repetido")
            // Content-Length repetido (com valores diferentes) é o truque de "request smuggling": recusa também.
            if (nome == "content-length" && cabecalhos.containsKey("content-length")) throw ErroHttp(400, "Content-Length repetido")
            cabecalhos[nome] = valor
        }
        // Corpo "chunked" não é suportado (o navegador só usa com stream; o painel sempre manda tamanho conhecido).
        if (cabecalhos.containsKey("transfer-encoding")) throw ErroHttp(411, "use Content-Length")

        val tamanho = cabecalhos["content-length"]?.let {
            // Só dígitos (o toLong aceitaria "+5"); até 19 dígitos cabe num Long.
            if (it.isEmpty() || it.length > 18 || !it.all { c -> c in '0'..'9' }) throw ErroHttp(400, "Content-Length inválido")
            it.toLong()
        } ?: 0L
        if (!aceitarCorpo && tamanho > 0) throw ErroHttp(413, "corpo grande demais")

        val interrogacao = alvo.indexOf('?')
        val caminho = if (interrogacao < 0) alvo else alvo.substring(0, interrogacao)
        val consulta = if (interrogacao < 0) emptyMap() else lerConsulta(alvo.substring(interrogacao + 1))
        return Requisicao(metodo, caminho, consulta, cabecalhos, tamanho, entrada, relogio)
    }

    /** Linhas até a linha vazia, sem o \r\n. Lê byte a byte (só os cabeçalhos; poucos KB) para não engolir o corpo. */
    private fun linhasCabecalho(entrada: InputStream, prazoMs: Long, relogio: () -> Long): List<String> {
        val linhas = ArrayList<String>()
        val atual = ByteArrayOutputStream()
        var total = 0
        // O prazo começa no PRIMEIRO byte, não na conexão: o navegador abre conexões de reserva e só manda o pedido
        // depois; uma reserva parada não pode virar 408 (a espera até o 1º byte já é limitada pelo soTimeout).
        var limite = Long.MAX_VALUE
        while (true) {
            val b = entrada.read()
            if (limite == Long.MAX_VALUE && prazoMs > 0) limite = relogio() + prazoMs
            else if (relogio() > limite) throw ErroHttp(408, "cabeçalhos demoraram demais")
            if (b < 0) {
                if (total == 0) throw IOException("conexão fechada sem pedido")
                throw ErroHttp(400, "cabeçalhos incompletos")
            }
            if (++total > LIMITE_CABECALHOS) throw ErroHttp(431, "cabeçalhos grandes demais")
            if (b == '\n'.code) {
                var linha = atual.toString("ISO-8859-1")
                if (linha.endsWith("\r")) linha = linha.dropLast(1)
                atual.reset()
                if (linha.isEmpty()) {
                    if (linhas.isEmpty()) continue   // linhas vazias antes do pedido: a RFC manda ignorar
                    return linhas
                }
                linhas.add(linha)
            } else {
                atual.write(b)
            }
        }
    }

    internal fun lerExato(entrada: InputStream, n: Int, prazoMs: Long = PRAZO_CORPO_MS,
                          relogio: () -> Long = System::currentTimeMillis): ByteArray {
        val corpo = ByteArray(n)
        val limite = relogio() + prazoMs
        var lidos = 0
        while (lidos < n) {
            if (relogio() > limite) throw ErroHttp(408, "corpo demorou demais")
            val r = entrada.read(corpo, lidos, n - lidos)
            if (r < 0) throw IOException("corpo incompleto ($lidos de $n bytes)")
            lidos += r
        }
        return corpo
    }

    private fun lerConsulta(texto: String): Map<String, String> {
        val mapa = HashMap<String, String>()
        for (par in texto.split('&')) {
            if (par.isEmpty()) continue
            val igual = par.indexOf('=')
            try {
                val nome = URLDecoder.decode(if (igual < 0) par else par.substring(0, igual), "UTF-8")
                val valor = if (igual < 0) "" else URLDecoder.decode(par.substring(igual + 1), "UTF-8")
                mapa[nome] = valor
            } catch (e: IllegalArgumentException) {
                throw ErroHttp(400, "consulta inválida")
            }
        }
        return mapa
    }

    /** Valor do cookie [nome] num cabeçalho Cookie ("a=1; b=2"), ou null. */
    fun cookie(cabecalho: String?, nome: String): String? {
        if (cabecalho == null) return null
        for (parte in cabecalho.split(';')) {
            val igual = parte.indexOf('=')
            if (igual > 0 && parte.substring(0, igual).trim() == nome) return parte.substring(igual + 1).trim()
        }
        return null
    }

    fun textoStatus(codigo: Int): String = when (codigo) {
        200 -> "OK"; 204 -> "No Content"; 206 -> "Partial Content"; 304 -> "Not Modified"
        400 -> "Bad Request"; 401 -> "Unauthorized"; 403 -> "Forbidden"; 404 -> "Not Found"
        405 -> "Method Not Allowed"; 408 -> "Request Timeout"; 409 -> "Conflict"; 411 -> "Length Required"; 413 -> "Payload Too Large"
        421 -> "Misdirected Request"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
        415 -> "Unsupported Media Type"; 416 -> "Range Not Satisfiable"
        500 -> "Internal Server Error"; 503 -> "Service Unavailable"; 507 -> "Insufficient Storage"
        else -> "Status"
    }

    /**
     * Cabeçalhos de segurança que vão em TODA resposta. A CSP só deixa a página carregar arquivos do próprio painel
     * (nada de script de fora, nada de script inline) e proíbe abrir o painel dentro de iframe de outro site
     * (clickjacking). no-store: nada do painel fica no cache do navegador, exceto o que a rota liberar (ícones).
     */
    val CABECALHOS_SEGURANCA = linkedMapOf(
        "Content-Security-Policy" to "default-src 'self'; img-src 'self' blob:; style-src 'self'; script-src 'self'; " +
            "connect-src 'self'; media-src 'self' blob:; frame-ancestors 'none'; base-uri 'none'; form-action 'self'",
        "X-Content-Type-Options" to "nosniff",
        "X-Frame-Options" to "DENY",
        "Referrer-Policy" to "no-referrer",
        "Cache-Control" to "no-store",
    )

    /** Escreve a resposta inteira. [extras] pode sobrescrever os de segurança (ex.: Cache-Control dos ícones). */
    fun escrever(saida: OutputStream, codigo: Int, tipo: String?, corpo: ByteArray, extras: Map<String, String> = emptyMap()) {
        escreverCabecalho(saida, codigo, tipo, corpo.size.toLong(), extras)
        saida.write(corpo)
        saida.flush()
    }

    /**
     * Só a linha de status e os cabeçalhos; quem chama escreve depois exatamente [tamanho] bytes de corpo (é assim
     * que um arquivo grande sai da TV sem ir inteiro para a memória).
     */
    fun escreverCabecalho(saida: OutputStream, codigo: Int, tipo: String?, tamanho: Long, extras: Map<String, String> = emptyMap()) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 ").append(codigo).append(' ').append(textoStatus(codigo)).append("\r\n")
        val todos = LinkedHashMap(CABECALHOS_SEGURANCA)
        todos.putAll(extras)
        if (tipo != null) todos["Content-Type"] = tipo
        todos["Content-Length"] = tamanho.toString()
        todos["Connection"] = "close"
        for ((nome, valor) in todos) {
            // Um \r ou \n num valor permitiria injetar cabeçalhos. Nenhum valor nosso tem, mas a barreira fica aqui.
            require('\r' !in valor && '\n' !in valor) { "quebra de linha no cabeçalho $nome" }
            // Cabeçalho HTTP é ISO-8859-1: nome de arquivo com acento vai codificado (ver [nomeParaBaixar]).
            require(valor.all { it.code in 0x20..0x7e }) { "caractere fora de ASCII no cabeçalho $nome" }
            sb.append(nome).append(": ").append(valor).append("\r\n")
        }
        sb.append("\r\n")
        saida.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
    }

    /**
     * Content-Disposition para baixar um arquivo com o nome dele, inclusive com acento ("Férias.mp4"): o padrão
     * (RFC 6266) é um nome só ASCII de reserva e o nome de verdade em UTF-8 codificado com %xx em filename*.
     */
    fun nomeParaBaixar(nome: String): String {
        val reserva = nome.map { if (it.code in 0x20..0x7e && it != '"' && it != '\\' && it != '%') it else '_' }.joinToString("")
        val utf8 = StringBuilder()
        for (b in nome.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xff
            if ((c in 'a'.code..'z'.code) || (c in 'A'.code..'Z'.code) || (c in '0'.code..'9'.code) || c.toChar() in "-._~")
                utf8.append(c.toChar())
            else utf8.append('%').append("%02X".format(c))
        }
        return "attachment; filename=\"$reserva\"; filename*=UTF-8''$utf8"
    }

    /**
     * Tipo para a PRÉVIA de um arquivo no navegador (aba Arquivos), ou null se esse tipo não tem prévia.
     * Lista fechada de propósito: HTML, SVG, XML e afins NUNCA são mostrados (rodariam script no endereço do painel,
     * com a sessão de quem está logado). Texto vai como text/plain, que o navegador não executa.
     */
    fun tipoPrevia(nome: String): String? = when (nome.substringAfterLast('.', "").lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"; "png" -> "image/png"; "gif" -> "image/gif"; "webp" -> "image/webp"; "bmp" -> "image/bmp"
        "mp4", "m4v" -> "video/mp4"; "webm" -> "video/webm"; "mkv" -> "video/x-matroska"; "mov" -> "video/quicktime"
        "mp3" -> "audio/mpeg"; "m4a", "aac" -> "audio/mp4"; "ogg", "oga", "opus" -> "audio/ogg"; "wav" -> "audio/wav"; "flac" -> "audio/flac"
        "txt", "log", "md", "csv", "json", "srt", "vtt", "m3u", "m3u8", "properties", "conf", "ini", "nfo" -> "text/plain; charset=utf-8"
        else -> null
    }

    /**
     * Lê o cabeçalho Range ("bytes=INICIO-FIM", "bytes=INICIO-" ou "bytes=-ULTIMOS") contra um arquivo de [tamanho]
     * bytes. Devolve o intervalo [inicio, fim] (fim incluso), null se não há Range válido para atender (manda o arquivo
     * inteiro, 200), ou o intervalo vazio (-1, -1) se o pedido cai fora do arquivo (416). Só um intervalo: o player
     * de vídeo do navegador nunca pede mais de um, e vários intervalos complicariam a resposta sem ganho.
     */
    fun faixa(range: String?, tamanho: Long): Pair<Long, Long>? {
        val r = range?.trim() ?: return null
        if (!r.startsWith("bytes=") || ',' in r) return null
        val partes = r.removePrefix("bytes=").split('-')
        if (partes.size != 2) return null
        val (a, b) = partes[0].trim() to partes[1].trim()
        if (a.isEmpty() && b.isEmpty()) return null
        if ((a.isNotEmpty() && !a.all(Char::isDigit)) || (b.isNotEmpty() && !b.all(Char::isDigit)) || a.length > 18 || b.length > 18) return null
        if (tamanho <= 0) return null   // arquivo vazio: manda o "arquivo inteiro" (0 bytes), não 416
        if (a.isEmpty()) {   // os últimos N bytes
            val n = b.toLong()
            if (n == 0L) return -1L to -1L
            return maxOf(0L, tamanho - n) to tamanho - 1
        }
        val inicio = a.toLong()
        if (inicio >= tamanho) return -1L to -1L
        val fim = if (b.isEmpty()) tamanho - 1 else minOf(b.toLong(), tamanho - 1)
        if (fim < inicio) return null
        return inicio to fim
    }
}
