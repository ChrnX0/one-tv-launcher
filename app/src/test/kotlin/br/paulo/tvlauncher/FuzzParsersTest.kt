package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.kxml2.io.KXmlParser
import org.xml.sax.SAXException
import org.xmlpull.v1.XmlPullParserException
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PrintStream
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Random
import java.util.zip.GZIPOutputStream

/**
 * "Fuzz" dos leitores que recebem dados de FORA (10/11/2026): milhares de entradas aleatórias, quase certas e
 * estragadas de propósito, contra o HTTP do painel, o JsonSeguro, o jsonRaso, o guia XMLTV, a lista M3U e o UPnP.
 *
 * O que se exige de cada leitor: ou devolve um resultado coerente, ou recusa com o erro ESPERADO dele (ex.: ErroHttp,
 * XmlPullParserException). Qualquer outra coisa (NullPointerException, índice fora, StackOverflowError) é bug: na TV
 * isso derruba a thread ou o launcher. O `timeout` de cada teste pega laço infinito e leitura lenta demais.
 *
 * Reproduzível: a semente é fixa e cada caso usa Random(SEMENTE + número do caso). Quando um caso falha, a mensagem
 * traz o número e a entrada; para repetir só ele, é rodar o mesmo gerador com aquele número.
 */
class FuzzParsersTest {

    // ------------------------------------------------------------------ ferramentas do fuzz

    private companion object {
        const val SEMENTE = 20261110L

        /** Random próprio de cada caso: o caso N gera sempre a mesma entrada, rode o teste quantas vezes for. */
        fun rnd(teste: Int, caso: Int) = Random(SEMENTE * 1_000_003L + teste * 100_000L + caso)

        /** Entrada legível na mensagem de erro (controles viram \xNN), cortada para não inundar o relatório. */
        fun mostrar(t: String): String {
            val sb = StringBuilder()
            for (c in t.take(400)) {
                if (c < ' ' || c.code == 0x7f) sb.append("\\x%02x".format(c.code)) else sb.append(c)
            }
            if (t.length > 400) sb.append("…(+${t.length - 400})")
            return sb.toString()
        }

        fun mostrar(b: ByteArray) = mostrar(String(b, Charsets.ISO_8859_1))

        /**
         * Roda [bloco]; uma exceção da lista [esperadas] é a recusa normal do leitor. Qualquer outra vira falha do
         * teste com o caso e a entrada, para dar para reproduzir.
         */
        inline fun caso(nome: String, n: Int, entrada: () -> String, esperadas: List<Class<out Throwable>>, bloco: () -> Unit) {
            try {
                bloco()
            } catch (t: Throwable) {
                if (t is AssertionError) throw AssertionError("$nome, caso $n: ${t.message}\nentrada: ${entrada()}", t)
                if (esperadas.none { it.isInstance(t) })
                    throw AssertionError("$nome, caso $n: ${t.javaClass.name}: ${t.message}\nentrada: ${entrada()}", t)
            }
        }

        /** Estraga [base] com 1 a 6 mutações: trocar byte, inserir pedaço "perigoso", apagar, duplicar, cortar. */
        fun mutar(r: Random, base: ByteArray, pedacos: List<String>): ByteArray {
            var b = base
            repeat(1 + r.nextInt(6)) {
                val pos = if (b.isEmpty()) 0 else r.nextInt(b.size + 1)
                b = when (r.nextInt(7)) {
                    0 -> if (b.isEmpty()) b else b.copyOf().also { it[minOf(pos, it.size - 1)] = r.nextInt(256).toByte() }
                    1, 2 -> inserir(b, pos, pedacos[r.nextInt(pedacos.size)].toByteArray(Charsets.UTF_8))
                    3 -> if (pos >= b.size) b else b.copyOfRange(0, pos) + b.copyOfRange(minOf(b.size, pos + 1 + r.nextInt(16)), b.size)
                    4 -> { // duplica um trecho (repetição costuma achar contagem errada)
                        val fim = minOf(b.size, pos + 1 + r.nextInt(64))
                        inserir(b, pos, b.copyOfRange(minOf(pos, fim), fim))
                    }
                    5 -> b.copyOfRange(0, pos)     // corta (arquivo/conexão que parou no meio)
                    else -> inserir(b, pos, ByteArray(1 + r.nextInt(8)) { r.nextInt(256).toByte() })
                }
            }
            return b
        }

        fun inserir(b: ByteArray, pos: Int, x: ByteArray): ByteArray = b.copyOfRange(0, pos) + x + b.copyOfRange(pos, b.size)

        fun escolher(r: Random, vararg op: String) = op[r.nextInt(op.size)]
    }

    /** Fluxo que entrega no máximo [passo] bytes por leitura: a rede de verdade chega em pedaços. */
    private class EmPedacos(dados: ByteArray, private val passo: Int) : InputStream() {
        private val dentro = ByteArrayInputStream(dados)
        override fun read() = dentro.read()
        override fun read(b: ByteArray, off: Int, len: Int) = dentro.read(b, off, minOf(len, passo))
        fun restante(): ByteArray = dentro.readBytes()
    }

    /**
     * O parser de XML do JDK escreve "[Fatal Error]" no console a cada XML ruim; milhares disso só atrapalham.
     * Atenção: System.setErr vale para o processo inteiro. Funciona porque o JUnit 4 roda os testes um de cada vez; se
     * um dia rodarem em paralelo, os outros testes perdem as mensagens de erro enquanto este bloco roda.
     */
    private inline fun <T> semConsole(bloco: () -> T): T {
        val antigo = System.err
        System.setErr(PrintStream(OutputStream.nullOutputStream()))
        try { return bloco() } finally { System.setErr(antigo) }
    }

    // ------------------------------------------------------------------ 1. HTTP do painel (PainelHttp)

    private val pedacosHttp = listOf(
        "\r\n", "\n", "\r", "\r\n\r\n", ":", " ", "\t", "?", "&", "=", "%", "%zz", "%C3%A9", "%", "/", "HTTP/1.1",
        "Content-Length: ", "Content-Length: 5\r\n", "content-length:999999999999999999\r\n", "Content-Length: -1\r\n",
        "Content-Length: +5\r\n", "Content-Length: 99999999999999999999\r\n", "Host: x\r\n", "HOST: y\r\n",
        "Transfer-Encoding: chunked\r\n", "\u0000", "\u00ff", "GET ", "POST ", "a=b; c=d", "é",
    )

    private val pedidosHttp = listOf(
        "GET / HTTP/1.1\r\nHost: tv:8080\r\n\r\n",
        "POST /api/x?a=1&b=%C3%A9&c HTTP/1.1\r\nHost: tv\r\nContent-Length: 5\r\nOrigin: http://tv:8080\r\nX-Painel: 1\r\n\r\nabcde",
        "GET /icone?p=com.x HTTP/1.0\r\nCookie: s=abc; t=1\r\nAccept: */*\r\n\r\n",
        "\r\n\r\nGET /a/b/c HTTP/1.1\nHost: a\n\n",
        "POST /up HTTP/1.1\r\nHost: tv\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nabcde\r\n0\r\n\r\n",
        "POST /api/login HTTP/1.1\r\nHost: tv\r\nContent-Length: 0\r\n\r\n",
    )

    private fun conferirRequisicao(req: Requisicao, limite: Int) {
        assertTrue("caminho sem barra: ${req.caminho}", req.caminho.startsWith("/"))
        assertTrue("tamanho fora: ${req.tamanho}", req.tamanho in 0..limite.toLong())
        assertEquals(req.tamanho, req.corpo.size.toLong())
        assertTrue(req.cabecalhos.keys.all { it == it.lowercase() })
        assertFalse("chunked passou", req.cabecalhos.containsKey("transfer-encoding"))
    }

    @Test(timeout = 20_000)
    fun httpPedidosEstragadosNuncaQuebram() {
        val limite = 4096
        for (n in 0 until 6000) {
            val r = rnd(1, n)
            val bytes = mutar(r, pedidosHttp[r.nextInt(pedidosHttp.size)].toByteArray(Charsets.UTF_8), pedacosHttp)
            caso("http", n, { mostrar(bytes) }, listOf(IOException::class.java)) {   // ErroHttp também é IOException
                // prazo 0 = sem relógio (o teste do prazo é outro); passo aleatório = rede em pedaços
                val req = PainelHttp.ler(EmPedacos(bytes, 1 + r.nextInt(7)), limite, prazoMs = 0)
                conferirRequisicao(req, limite)
            }
        }
    }

    @Test(timeout = 20_000)
    fun httpBytesAleatoriosNuncaQuebram() {
        for (n in 0 until 4000) {
            val r = rnd(2, n)
            val bytes = ByteArray(r.nextInt(300)) { (if (r.nextInt(4) == 0) "\r\n: /?%"[r.nextInt(7)].code else r.nextInt(256)).toByte() }
            caso("http-aleatório", n, { mostrar(bytes) }, listOf(IOException::class.java)) {
                conferirRequisicao(PainelHttp.ler(ByteArrayInputStream(bytes), 1024, prazoMs = 0), 1024)
            }
        }
    }

    /** O leitor nunca pode engolir bytes DEPOIS do Content-Length (seriam de outro pedido) nem ler corpo a menos. */
    @Test(timeout = 20_000)
    fun httpCorpoParaExatamenteNoContentLength() {
        for (n in 0 until 3000) {
            val r = rnd(3, n)
            val corpo = ByteArray(r.nextInt(2000)) { r.nextInt(256).toByte() }
            val sb = StringBuilder("POST /api/c?x=${n} HTTP/1.1\r\nHost: tv\r\n")
            repeat(r.nextInt(6)) { sb.append("X-C$it:${" ".repeat(r.nextInt(3))}v$it${" ".repeat(r.nextInt(3))}\r\n") }
            sb.append(escolher(r, "Content-Length", "content-length", "CONTENT-LENGTH")).append(": ").append(corpo.size).append("\r\n\r\n")
            val depois = "PROXIMO".toByteArray()
            val fluxo = EmPedacos(sb.toString().toByteArray() + corpo + depois, 1 + r.nextInt(50))
            val req = PainelHttp.ler(fluxo, 4096, prazoMs = 0)
            assertTrue("caso $n", corpo.contentEquals(req.corpo))
            assertTrue("caso $n: leu além do corpo", depois.contentEquals(fluxo.restante()))
        }
    }

    /** fluxoCorpo (envio de arquivo grande) também para no Content-Length, e conexão que cai no meio vira IOException. */
    @Test(timeout = 20_000)
    fun httpFluxoCorpoRespeitaTamanho() {
        for (n in 0 until 1500) {
            val r = rnd(4, n)
            val tam = r.nextInt(5000)
            val enviado = r.nextInt(tam + 20)   // às vezes menos que o anunciado (conexão caiu)
            val cab = "POST /api/arquivos/enviar HTTP/1.1\r\nHost: tv\r\nContent-Length: $tam\r\n\r\n".toByteArray()
            val fluxo = EmPedacos(cab + ByteArray(enviado) { 7 }, 1 + r.nextInt(300))
            val req = PainelHttp.lerCabecalhos(fluxo, prazoMs = 0)
            try {
                val lidos = req.fluxoCorpo().readBytes()
                assertTrue("caso $n: leu ${lidos.size}, anunciado $tam, enviado $enviado", enviado >= tam && lidos.size == tam)
            } catch (e: IOException) {
                assertTrue("caso $n: IOException com corpo completo", enviado < tam)
            }
        }
    }

    /**
     * Pedido "chunked" (com o nome escrito de vários jeitos) é sempre recusado com 411: o painel não sabe ler chunked.
     * Com espaço antes dos dois-pontos ("Transfer-Encoding :"), a recusa vem antes, com 400 (cabeçalho malformado,
     * RFC 9112; ver httpEspacoAntesDosDoisPontosDeveSerRecusado). Os dois são recusa; só o código muda.
     */
    @Test(timeout = 20_000)
    fun httpChunkedSempreRecusado() {
        for (n in 0 until 2000) {
            val r = rnd(5, n)
            val nome = "transfer-encoding".map { if (r.nextBoolean()) it.uppercaseChar() else it }.joinToString("")
            val espaco = r.nextInt(2)
            val pedido = "POST /x HTTP/1.1\r\nHost: tv\r\n$nome${" ".repeat(espaco)}:${" ".repeat(r.nextInt(3))}" +
                escolher(r, "chunked", "gzip, chunked", "", "identity") + "\r\nContent-Length: 3\r\n\r\nabc"
            val e = runCatching { PainelHttp.ler(ByteArrayInputStream(pedido.toByteArray()), 4096, prazoMs = 0) }.exceptionOrNull()
            assertTrue("caso $n: ${mostrar(pedido)}", e is ErroHttp && e.codigo == (if (espaco > 0) 400 else 411))
        }
    }

    /** Cliente que manda 1 byte por "segundo" (relógio falso) tem de levar 408, nunca segurar a thread para sempre. */
    @Test(timeout = 20_000)
    fun httpConexaoLentaLevaPrazo() {
        for (n in 0 until 300) {
            val r = rnd(6, n)
            var agora = 0L
            val passo = 1 + r.nextInt(2000).toLong()
            val bytes = ("GET /" + "a".repeat(5000) + " HTTP/1.1\r\n\r\n").toByteArray()
            val fluxo = object : InputStream() {
                var i = 0
                override fun read(): Int { agora += passo; return if (i < bytes.size) bytes[i++].toInt() else -1 }
            }
            val e = runCatching { PainelHttp.lerCabecalhos(fluxo, prazoMs = 10_000, relogio = { agora }) }.exceptionOrNull()
            assertTrue("caso $n: $e", e is ErroHttp && e.codigo == 408)
        }
    }

    /** Nome de arquivo qualquer (acento, emoji, aspas, controle) vira um Content-Disposition que o escritor aceita. */
    @Test(timeout = 20_000)
    fun httpNomeParaBaixarSempreAscii() {
        val letras = "aZ09 ._-\"\\%;=\r\n\téãç日本\uD83C\uDFAC\u0000\u007f\u00ff"
        for (n in 0 until 3000) {
            val r = rnd(7, n)
            val nome = String(CharArray(r.nextInt(60)) { letras[r.nextInt(letras.length)] })
            caso("nomeParaBaixar", n, { mostrar(nome) }, emptyList()) {
                PainelHttp.escreverCabecalho(ByteArrayOutputStream(), 200, "application/octet-stream", 0,
                    mapOf("Content-Disposition" to PainelHttp.nomeParaBaixar(nome)))
                PainelHttp.cookie(nome, "s")
            }
        }
    }

    /**
     * ACHADO (corrigido no lote de 10/11/2026: PainelHttp.lerCabecalhos recusa). A RFC 9112 manda recusar espaço entre o nome do cabeçalho e
     * os dois-pontos (é um truque de "request smuggling" quando há um proxy na frente). Hoje o nome é aparado e aceito.
     * Risco baixo aqui (o painel não fica atrás de proxy), mas o caso mínimo fica registrado.
     */
    @Test fun httpEspacoAntesDosDoisPontosDeveSerRecusado() {
        val e = runCatching {
            PainelHttp.ler(ByteArrayInputStream("POST / HTTP/1.1\r\nHost: tv\r\nContent-Length : 5\r\n\r\nabcde".toByteArray()), 100, prazoMs = 0)
        }.exceptionOrNull()
        assertTrue(e is ErroHttp && e.codigo == 400)
    }

    // ------------------------------------------------------------------ 2. JsonSeguro

    /** JSON estrito aleatório; devolve o texto e a profundidade de [ e { que ele tem. */
    private fun gerarJson(r: Random, prof: Int, alvo: Int): Pair<String, Int> {
        val tipo = if (prof >= alvo) r.nextInt(4) else 4 + r.nextInt(2)
        return when (tipo) {
            0 -> escolher(r, "0", "-1", "2.5", "1e5", "-0.5E-3", "12345678901234567890123", "3.0e+2") to 0
            1 -> {
                val partes = listOf("a", "é", "\\\"", "\\\\", "\\n", "\\u00e9", "[", "{", "'", "/*", "#", " ", "日", "\uD83D\uDE00")
                ("\"" + (0 until r.nextInt(6)).joinToString("") { partes[r.nextInt(partes.size)] } + "\"") to 0
            }
            2 -> escolher(r, "true", "false", "null") to 0
            3 -> "\"\"" to 0
            4 -> {
                val filhos = filhosJson(r, prof, alvo)
                ("[" + filhos.joinToString(escolher(r, ",", " , ", ",\n")) { it.first } + "]") to 1 + (filhos.maxOfOrNull { it.second } ?: 0)
            }
            else -> {
                val filhos = filhosJson(r, prof, alvo)
                ("{" + filhos.withIndex().joinToString(",") { (i, f) -> "\"k$i\":${f.first}" } + "}") to 1 + (filhos.maxOfOrNull { it.second } ?: 0)
            }
        }
    }

    /**
     * Filhos de um [ ou {: o 1º desce até o alvo, os outros ficam rasos (senão a árvore crescia 3^40 e acabava a
     * memória do próprio teste).
     */
    private fun filhosJson(r: Random, prof: Int, alvo: Int): List<Pair<String, Int>> =
        (0 until 1 + r.nextInt(3)).map { i -> gerarJson(r, prof + 1, if (i == 0) alvo else minOf(alvo, prof + 2)) }

    /** Profundidade do que o org.json montou (para conferir que nunca passa do teto que o JsonSeguro prometeu). */
    private fun profundidade(v: Any?): Int = when (v) {
        is JSONObject -> 1 + (v.keys().asSequence().maxOfOrNull { profundidade(v.get(it)) } ?: 0)
        is JSONArray -> 1 + ((0 until v.length()).maxOfOrNull { profundidade(v.get(it)) } ?: 0)
        else -> 0
    }

    /** Sem falso negativo: JSON estrito de verdade, até o teto, sempre passa e o org.json lê. Acima do teto, recusa. */
    @Test(timeout = 20_000)
    fun jsonEstritoAleatorioEhAceitoAteOTeto() {
        for (n in 0 until 3000) {
            val r = rnd(10, n)
            val (texto, prof) = gerarJson(r, 0, r.nextInt(40))
            val max = 1 + r.nextInt(40)
            caso("json-estrito", n, { mostrar(texto) }, emptyList()) {
                assertEquals("valido(max=$max, prof=$prof)", prof <= max, JsonSeguro.valido(texto, max))
                val lido = JsonSeguro.ler(texto, max) { JSONTokener(it).nextValue() }
                if (prof <= max) assertEquals(prof, profundidade(lido)) else assertNull(lido)
            }
        }
    }

    private val pedacosJson = listOf(
        "[", "]", "{", "}", "\"", "\\", "'", "/*", "*/", "//", "#", "\n", ":", ",", "e", "E", "true", "nul", "-", "+", ".",
        "\u0000", "é", "[".repeat(40), "{\"a\":".repeat(40), "]".repeat(10), "\\u12", "\uD800",
    )

    /**
     * JSON estragado: valido/ler/objeto nunca lançam, e se o JsonSeguro deixou passar, o org.json lê SEM estourar a
     * pilha (chamado direto, sem a rede de proteção do ler) e a profundidade fica no teto.
     */
    @Test(timeout = 20_000)
    fun jsonEstragadoNuncaQuebraNemPassaDoTeto() {
        for (n in 0 until 6000) {
            val r = rnd(11, n)
            val base = gerarJson(r, 0, 1 + r.nextInt(8)).first
            val texto = String(mutar(r, base.toByteArray(Charsets.UTF_8), pedacosJson), Charsets.UTF_8)
            val max = 1 + r.nextInt(32)
            caso("json-estragado", n, { mostrar(texto) }, emptyList()) {
                JsonSeguro.objeto(texto, max)
                val lido = JsonSeguro.ler(texto, max) { JSONTokener(it).nextValue() }
                if (lido != null) assertTrue(profundidade(lido) <= max)
                if (JsonSeguro.valido(texto, max)) {
                    // Só Exception é aceitável aqui (JSONException): StackOverflowError seria o furo que o JsonSeguro fecha.
                    val v = try { JSONTokener(texto).nextValue() } catch (e: Exception) { null }
                    if (v != null) assertTrue(profundidade(v) <= max)
                }
            }
        }
    }

    /** Lixo puro e aninhamento gigante: resposta rápida (o teste tem prazo) e sem exceção. */
    @Test(timeout = 20_000)
    fun jsonLixoEAninhamentoGigante() {
        val letras = "[]{}\"\\'/*#:,0e-.tnfrul \n\u0000é"
        for (n in 0 until 3000) {
            val r = rnd(12, n)
            val texto = String(CharArray(r.nextInt(400)) { letras[r.nextInt(letras.length)] })
            caso("json-lixo", n, { mostrar(texto) }, emptyList()) { JsonSeguro.objeto(texto) }
        }
        for (abre in listOf("[", "{\"a\":", "[{\"b\":")) {
            val fundo = abre.repeat(200_000)
            assertFalse(JsonSeguro.valido(fundo))
            assertNull(JsonSeguro.objeto(fundo + "1"))
        }
        assertFalse(JsonSeguro.valido(""))
        assertFalse(JsonSeguro.valido(" \n\t"))
    }

    /**
     * corpoAceitavel (para o painel, que recebe corpo que pode não ser JSON): se aprovou, o org.json tolerante lê sem
     * estourar a pilha e sem passar do teto, mesmo com comentário, aspa simples e lixo antes do "[" ou "{". E tudo que o
     * JSON estrito aceita, ele também aceita.
     */
    @Test(timeout = 20_000)
    fun corpoAceitavelNuncaDeixaPassarFundoDemais() {
        val antes = listOf("", " ", "\n", "/* x */", "// x\n", "// x\r", "# x\n", "#\r", "/*", "//", "#", "'", "\"", "a=1&", "/")
        for (n in 0 until 6000) {
            val r = rnd(13, n)
            val base = gerarJson(r, 0, 1 + r.nextInt(8)).first
            val estragado = if (r.nextBoolean()) base else String(mutar(r, base.toByteArray(Charsets.UTF_8), pedacosJson), Charsets.UTF_8)
            val texto = (0 until r.nextInt(3)).joinToString("") { antes[r.nextInt(antes.size)] } + estragado
            val max = 1 + r.nextInt(32)
            caso("corpo", n, { mostrar(texto) }, emptyList()) {
                val aceito = JsonSeguro.corpoAceitavel(texto, max)
                if (JsonSeguro.valido(texto, max)) assertTrue("estrito passou e o corpo recusou", aceito)
                if (aceito) {
                    // Do jeito que uma rota leria: StackOverflowError aqui seria o furo.
                    val v = try { JSONTokener(texto).nextValue() } catch (e: Exception) { null }
                    if (v != null) assertTrue(profundidade(v) <= max)
                    try { JSONObject(texto) } catch (e: Exception) { }
                    try { JSONArray(texto) } catch (e: Exception) { }
                }
            }
        }
    }

    /** Os contornos do jsonRaso são recusados; corpo que não é JSON (texto, form) continua passando. */
    @Test(timeout = 20_000)
    fun corpoAceitavelCasosDoPainel() {
        val fundo = "[".repeat(5000) + "]".repeat(5001)
        for (t in listOf("['\"',$fundo", "[/*\"*/$fundo", "[# \"\n$fundo", "[\"$fundo", "]".repeat(16) + "[".repeat(32) + "]".repeat(32),
            "/* x */$fundo", "// x\r$fundo", "# x\n$fundo", " \n{'a':1}", "{a:1}"))
            assertFalse(mostrar(t), JsonSeguro.corpoAceitavel(t, 16))
        for (t in listOf("abc", "a=1&b=2", "\"texto\"", "", "  ", "/* sem fim [[[", "// so comentario", "{\"a\":[1,2,{\"b\":null}]}", " [1, 2.5e3, \"x\"] "))
            assertTrue(mostrar(t), JsonSeguro.corpoAceitavel(t, 16))
    }

    // ------------------------------------------------------------------ 3. ChecagensPainel.jsonRaso (de outro PR)

    /** O jsonRaso nunca lança, e tudo que o JSON estrito aceita ele também aceita (trocar um pelo outro não quebra nada). */
    @Test(timeout = 20_000)
    fun jsonRasoNuncaQuebraEAceitaOEstrito() {
        val letras = "[]{}\"\\'/*#:,0 \n"
        for (n in 0 until 4000) {
            val r = rnd(20, n)
            val texto = if (r.nextBoolean()) String(CharArray(r.nextInt(3000)) { letras[r.nextInt(letras.length)] })
            else gerarJson(r, 0, r.nextInt(20)).first
            caso("jsonRaso", n, { mostrar(texto) }, emptyList()) {
                val raso = ChecagensPainel.jsonRaso(texto)
                if (JsonSeguro.valido(texto, 16)) assertTrue("estrito passou e o raso recusou", raso)
            }
        }
    }

    /*
     * Contornos do jsonRaso (corrigidos no lote de 10/11/2026: o jsonRaso virou JsonSeguro.corpoAceitavel). Todos fazem o
     * org.json TOLERANTE do Android descer milhares de níveis depois de o jsonRaso dizer "raso". A correção pronta:
     * no PainelWeb (corpo pode não ser JSON) JsonSeguro.corpoAceitavel(texto, 16); nos backups e no UniFi (sempre JSON)
     * JsonSeguro.valido(texto, 16). Ou o corpo do jsonRaso vira corpoAceitavel (ver o relatório do fuzz).
     */
    @Test fun jsonRasoContornoAspaSimples() {
        assertFalse(ChecagensPainel.jsonRaso("['\"'," + "[".repeat(5000) + "]".repeat(5001)))
    }

    @Test fun jsonRasoContornoComentario() {
        assertFalse(ChecagensPainel.jsonRaso("[/*\"*/" + "[".repeat(5000) + "]".repeat(5001)))
        assertFalse(ChecagensPainel.jsonRaso("[# \"\n" + "[".repeat(5000) + "]".repeat(5001)))
    }

    @Test fun jsonRasoContornoNivelNegativo() {
        // O org.json para no 1º ']' e hoje não desce; mas o contador não garante o que promete (32 níveis com teto 16).
        assertFalse(ChecagensPainel.jsonRaso("]".repeat(16) + "[".repeat(32) + "]".repeat(32)))
    }

    @Test fun jsonRasoContornoStringSemFim() {
        assertFalse(ChecagensPainel.jsonRaso("[\"" + "[".repeat(5000)))
    }

    // ------------------------------------------------------------------ 4. Guia XMLTV (Xmltv.ler)

    private fun dataXmltv(r: Random): String = when (r.nextInt(10)) {
        0 -> escolher(r, "", "x", "2026", "20261310000000", "20261110256000 +0000", "202611101800", "20261110180000 -03")
        else -> "%04d%02d%02d%02d%02d%02d%s".format(2026, 11, 10 + r.nextInt(2), r.nextInt(24), r.nextInt(60), r.nextInt(60),
            escolher(r, "", " +0000", " -0300", " +0530", "-0300", " Z"))
    }

    private fun gerarGuia(r: Random): String {
        val sb = StringBuilder(escolher(r, "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n", "", "\uFEFF<?xml version=\"1.0\"?>"))
        sb.append("<tv generator-info-name=\"x\">\n<channel id=\"c1\"><display-name>Canal 1</display-name></channel>\n")
        repeat(r.nextInt(30)) {
            sb.append("<programme start=\"${dataXmltv(r)}\" stop=\"${dataXmltv(r)}\" channel=\"c${r.nextInt(4)}\">")
            repeat(r.nextInt(4)) {
                sb.append(when (r.nextInt(8)) {
                    0, 1 -> "<title lang=\"pt\">${escolher(r, "Jornal", "Tom & Jerry", "Tom &amp; Jerry", "<![CDATA[A < B]]>", " ", "Fé &#233; &#x1F600;")}</title>"
                    2 -> "<desc>${"Descrição longa ".repeat(r.nextInt(40))}</desc>"
                    3 -> "<category>Notícias</category>"
                    4 -> "<credits><actor>Fulano</actor><director/></credits>"
                    5 -> "<icon src=\"http://x/y.png\"/>"
                    6 -> "<!-- comentário --><?pi x?>"
                    else -> "<title><b>dentro</b></title>"
                })
            }
            sb.append("</programme>\n")
        }
        return sb.append("</tv>").toString()
    }

    private val pedacosXml = listOf(
        "<", ">", "</", "/>", "&", "&#", "&#x110000;", "&amp", "&lt;", "&nada;", "]]>", "<![CDATA[", "<!--", "-->", "<?", "?>",
        "\"", "'", "=", "<programme start=\"20261110180000\" stop=\"20261110190000\" channel=\"c1\">", "</programme>",
        "<title>", "</title>", "</tv>", "<tv>", "\u0000", "\u00c3(", "<!DOCTYPE tv [<!ENTITY a \"aaaaaaaa\">]>", "&a;",
        "<?xml version=\"1.0\" encoding=\"bogus\"?>", "<?xml version=\"1.0\" encoding=\"UTF-16\"?>", "xmlns:x=\"u\"", "<x:y>",
    )

    private fun conferirGuia(lista: List<Programa>, canais: Set<String>, desde: Long, ate: Long) {
        assertTrue(lista.size <= Xmltv.MAX_PROGRAMAS)
        for (p in lista) {
            assertTrue("fim antes do início", p.fim > p.inicio)
            assertTrue("fora da janela", p.fim > desde && p.inicio < ate)
            assertTrue("canal fora do filtro", canais.isEmpty() || p.guiaId in canais)
            assertTrue("título vazio", p.titulo.isNotEmpty())
            assertTrue("descrição longa", p.descricao.length <= Xmltv.MAX_DESCRICAO)
        }
        assertEquals(lista.sortedWith(compareBy({ it.guiaId }, { it.inicio })), lista)
    }

    @Test(timeout = 20_000)
    fun guiaXmltvEstragadoNuncaQuebra() {
        for (n in 0 until 3000) {
            val r = rnd(30, n)
            val bytes = gerarGuia(r).toByteArray(Charsets.UTF_8).let { if (r.nextInt(5) == 0) it else mutar(r, it, pedacosXml) }
            val canais = if (r.nextBoolean()) emptySet() else setOf("c1", "c2")
            val desde = if (r.nextBoolean()) Long.MIN_VALUE else Xmltv.data("20261110120000")!!
            val ate = if (r.nextBoolean()) Long.MAX_VALUE else Xmltv.data("20261111060000")!!
            caso("xmltv", n, { mostrar(bytes) }, listOf(XmlPullParserException::class.java, IOException::class.java)) {
                // Às vezes compactado (o guia de IPTV costuma vir .gz), às vezes com o .gz cortado no meio.
                val entrada: InputStream = if (r.nextInt(4) == 0) {
                    val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(bytes) } }.toByteArray()
                    Xmltv.abrir(ByteArrayInputStream(if (r.nextBoolean()) gz else gz.copyOf(r.nextInt(gz.size + 1))))
                } else Xmltv.abrir(EmPedacos(bytes, 1 + r.nextInt(64)))
                conferirGuia(Xmltv.ler(KXmlParser(), entrada, canais, desde, ate), canais, desde, ate)
            }
        }
    }

    /** Data do XMLTV: nunca lança, e para datas válidas bate com o java.time (conta de dias feita à mão). */
    @Test(timeout = 20_000)
    fun guiaDataBateComJavaTime() {
        val letras = "0123456789 +-Z:\u0663\u06F1x"
        for (n in 0 until 20000) {
            val r = rnd(31, n)
            val lixo = String(CharArray(r.nextInt(25)) { letras[r.nextInt(letras.length)] })
            caso("xmltv-data-lixo", n, { mostrar(lixo) }, emptyList()) { Xmltv.data(lixo) }

            val ano = 1900 + r.nextInt(300); val mes = 1 + r.nextInt(12)
            val dia = 1 + r.nextInt(java.time.YearMonth.of(ano, mes).lengthOfMonth())
            val h = r.nextInt(24); val mi = r.nextInt(60); val s = r.nextInt(60)
            val fusoMin = (r.nextInt(27) - 12) * 60 + escolher(r, "0", "30", "45").toInt()
            val sinal = if (fusoMin < 0) "-" else "+"
            val texto = "%04d%02d%02d%02d%02d%02d %s%02d%02d".format(ano, mes, dia, h, mi, s, sinal, Math.abs(fusoMin) / 60, Math.abs(fusoMin) % 60)
            val esperado = OffsetDateTime.of(ano, mes, dia, h, mi, s, 0,
                ZoneOffset.ofTotalSeconds((if (fusoMin < 0) -1 else 1) * (Math.abs(fusoMin) / 60 * 3600 + Math.abs(fusoMin) % 60 * 60)))
                .toInstant().toEpochMilli()
            assertEquals("caso $n: $texto", esperado, Xmltv.data(texto))
        }
    }

    // ------------------------------------------------------------------ 5. Lista M3U (M3u.ler)

    private fun gerarM3u(r: Random): String {
        val sb = StringBuilder(escolher(r, "#EXTM3U x-tvg-url=\"http://guia/a.xml,https://b/c.xml.gz\"\n", "#EXTM3U\r\n", "\uFEFF#EXTM3U url-tvg=\"x\"\n", ""))
        val fim = escolher(r, "\n", "\r\n")
        repeat(r.nextInt(40)) {
            sb.append(when (r.nextInt(12)) {
                0, 1, 2 -> "#EXTINF:-1 tvg-id=\"${escolher(r, "a", "b", "a#2", "", "c.br")}\" tvg-chno=\"${escolher(r, "1", "2", "0", "-5", "99999", "100000", "x", "2147483648")}\"" +
                    " tvg-logo=\"${escolher(r, "http://l/x.png", "file:///data/x", "", "http://a b")}\" group-title=\"${escolher(r, "Esportes", "A, B", "")}\"," +
                    escolher(r, "Canal", "", "Nome, com vírgula", "\"aspas\"")
                3, 4, 5 -> escolher(r, "http://srv/c.m3u8", "https://s/x.ts?t=1", "rtmp://x/y", "file:///sdcard/a", "http://", "HTTP://SRV/Q.M3U8", "http://a b/c")
                6 -> "#EXTGRP:${escolher(r, "Filmes", "", ":")}"
                7 -> "#EXTVLCOPT:http-user-agent=${escolher(r, "VLC/3", "=", "")}"
                8 -> "#KODIPROP:inputstream=x"
                9 -> "#EXTINF:" + escolher(r, "", ",", "\"", "-1 a=\"b", "-1,", "-1 tvg-id=\"x\"")
                10 -> ""
                else -> "lixo qualquer ${r.nextInt()}"
            }).append(fim)
        }
        return sb.toString()
    }

    private fun conferirCanais(lista: List<Canal>) {
        assertTrue(lista.size <= M3u.MAX_CANAIS)
        assertEquals("id repetido", lista.size, lista.map { it.id }.toSet().size)
        assertEquals("número repetido", lista.size, lista.map { it.numero }.toSet().size)
        for (c in lista) {
            assertTrue("url não aceita: ${c.url}", M3u.urlAceita(c.url))
            assertTrue("número inválido: ${c.numero}", c.numero >= 1)
            assertTrue("logo não aceita: ${c.logo}", c.logo.isEmpty() || M3u.urlAceita(c.logo))
            assertTrue(c.nome.isNotEmpty())
        }
    }

    private val pedacosM3u = listOf("\n", "\r\n", "#EXTINF:", ",", "\"", "=", "tvg-id=\"a\"", "tvg-chno=\"1\"", "http://x/y", "#", "\uFEFF", "\u0000")

    @Test(timeout = 20_000)
    fun listaM3uEstragadaNuncaQuebra() {
        for (n in 0 until 3000) {
            val r = rnd(40, n)
            val texto = gerarM3u(r).let { if (r.nextBoolean()) it else String(mutar(r, it.toByteArray(Charsets.UTF_8), pedacosM3u), Charsets.UTF_8) }
            caso("m3u", n, { mostrar(texto) }, emptyList()) {
                conferirCanais(M3u.ler(texto))
                M3u.guiaDaLista(texto)?.let { assertTrue(M3u.urlAceita(it)) }
            }
        }
    }

    /** Achado do fuzz: o tvg-id "a#2" de verdade colidia com o "a#2" que a desduplicação inventa para o 2º "a". */
    @Test fun listaM3uIdsSempreUnicos() {
        val texto = "#EXTINF:-1 tvg-id=\"a\",A\nhttp://srv/c1\n#EXTINF:-1 tvg-id=\"a\",A2\nhttp://srv/c2\n#EXTINF:-1 tvg-id=\"a#2\",A3\nhttp://srv/c3\n"
        val ids = M3u.ler(texto).map { it.id }
        assertEquals(listOf("a", "a#2", "a#2#2"), ids)   // os dois primeiros não mudam (favoritos antigos continuam valendo)
        // Muitos repetidos: continua rápido (o contador por id evita recomeçar do #2 a cada um).
        val muitos = (1..20_000).joinToString("") { "#EXTINF:-1 tvg-id=\"x\",X\nhttp://srv/c$it\n" }
        val lidos = M3u.ler(muitos)
        assertEquals(20_000, lidos.map { it.id }.toSet().size)
    }

    /**
     * Achado do fuzz: linha #EXTINF enorme sem "=" (lista de 30 MB baixada da internet) fazia a expressão regular voltar
     * atrás a cada letra: tempo QUADRÁTICO (200 mil letras = dezenas de segundos travando a carga dos canais).
     */
    @Test(timeout = 5_000)
    fun listaM3uLinhaEnormeEhRapida() {
        val linha = "#EXTINF:-1 " + "a".repeat(300_000) + " tvg-id=\"x\"," + "Nome"
        val (attrs, nome) = M3u.lerExtinf(linha)
        assertEquals("x", attrs["tvg-id"])
        assertEquals("Nome", nome)
        M3u.ler("#EXTINF:-1 " + "a-".repeat(200_000) + "\nhttp://srv/c1\n")
        M3u.guiaDaLista("#EXTM3U " + "x".repeat(300_000))
    }

    // ------------------------------------------------------------------ 6. UPnP (ficha, SOAP e DIDL)

    private val upnpEsperadas = listOf(SAXException::class.java, IOException::class.java, IllegalArgumentException::class.java)

    private fun gerarFicha(r: Random) = """<?xml version="1.0"?><root xmlns="urn:schemas-upnp-org:device-1-0">""" +
        escolher(r, "", "<URLBase>http://10.0.0.5:8200/</URLBase>", "<URLBase>httpx:/bad</URLBase>", "<URLBase>ftp://x/</URLBase>") +
        "<device><friendlyName>${escolher(r, "NAS", "", "A &amp; B", "<![CDATA[x]]>")}</friendlyName><serviceList>" +
        (0 until r.nextInt(4)).joinToString("") {
            "<service><serviceType>urn:schemas-upnp-org:service:${escolher(r, "ContentDirectory:1", "AVTransport:1", "ContentDirectory:")}</serviceType>" +
                "<controlURL>${escolher(r, "/ctl", "http://outro:1/c", "", "::://", "%zz", "http://[::1", "//h/c")}</controlURL></service>"
        } + "</serviceList></device></root>"

    private fun gerarDidl(r: Random): String {
        val itens = (0 until r.nextInt(8)).joinToString("") {
            if (r.nextBoolean()) "<container id=\"c$it\"><dc:title>Pasta $it</dc:title><upnp:albumArtURI>${escolher(r, "http://a/c.jpg", "file:///x", "")}</upnp:albumArtURI></container>"
            else "<item id=\"i$it\"><dc:title>${escolher(r, "Filme", "", "Férias &amp; praia")}</dc:title>" +
                "<upnp:class>${escolher(r, "object.item.videoItem", "object.item.audioItem.musicTrack", "object.item.imageItem", "x")}</upnp:class>" +
                (0 until r.nextInt(3)).joinToString("") { "<res>${escolher(r, "http://n/1.mp4", "https://n/2", "rtsp://x", "", " http://n/3 ")}</res>" } + "</item>"
        }
        return """<DIDL-Lite xmlns="urn:schemas-upnp-org:metadata-1-0/DIDL-Lite/" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:upnp="urn:schemas-upnp-org:metadata-1-0/upnp/">$itens</DIDL-Lite>"""
    }

    private fun gerarSoap(r: Random): String {
        val didl = gerarDidl(r).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        return """<?xml version="1.0"?><s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"><s:Body><u:BrowseResponse xmlns:u="urn:x">""" +
            "<Result>$didl</Result><NumberReturned>1</NumberReturned><TotalMatches>${escolher(r, "3", "-1", "x", "99999999999", "")}</TotalMatches>" +
            "</u:BrowseResponse></s:Body></s:Envelope>"
    }

    private fun conferirDidl(lista: List<Upnp.Entrada>) {
        val primeiroArquivo = lista.indexOfFirst { !it.pasta }
        if (primeiroArquivo >= 0) assertTrue("pasta depois de arquivo", lista.drop(primeiroArquivo).none { it.pasta })
        for (e in lista) {
            if (!e.pasta) assertTrue("url: ${e.url}", e.url!!.startsWith("http://") || e.url.startsWith("https://"))
            assertTrue("capa: ${e.capa}", e.capa == null || e.capa.startsWith("http"))
        }
    }

    @Test(timeout = 20_000)
    fun upnpXmlEstragadoNuncaQuebra() = semConsole {
        for (n in 0 until 4500) {
            val r = rnd(50, n)
            val tipo = n % 3
            val base = when (tipo) { 0 -> gerarFicha(r); 1 -> gerarSoap(r); else -> gerarDidl(r) }
            val bytes = base.toByteArray(Charsets.UTF_8).let { if (r.nextInt(4) == 0) it else mutar(r, it, pedacosXml) }
            caso("upnp-$tipo", n, { mostrar(bytes) }, upnpEsperadas) {
                when (tipo) {
                    0 -> Upnp.lerFicha(bytes, "http://10.0.0.5:8200/d.xml")?.let { assertTrue(it.pastas.isNotEmpty()) }
                    1 -> conferirDidl(Upnp.lerDidl(Upnp.lerRespostaNavegar(bytes).first))
                    else -> conferirDidl(Upnp.lerDidl(String(bytes, Charsets.UTF_8)))
                }
            }
        }
    }

    /** Resposta SSDP qualquer: nunca lança, e o LOCATION devolvido é sempre http. */
    @Test(timeout = 20_000)
    fun upnpRespostaSsdpAleatoria() {
        val partes = listOf("HTTP/1.1 200 OK\r\n", "LOCATION:", "Location: http://10.0.0.2/d.xml\r\n", "location:file:///x\r\n", ":", "\r\n", "\n", " ", "http://", "\u0000")
        for (n in 0 until 5000) {
            val r = rnd(51, n)
            val texto = (0 until r.nextInt(12)).joinToString("") { partes[r.nextInt(partes.size)] }
            caso("ssdp", n, { mostrar(texto) }, emptyList()) { Upnp.localDaResposta(texto)?.let { assertTrue(it.startsWith("http://")) } }
        }
    }

    /**
     * Aparelho da rede mandando XML fundo demais (milhares de elementos um dentro do outro): o leitor tem de recusar
     * com erro normal. Antes, o textContent (recursivo) estourava a pilha com StackOverflowError.
     */
    @Test(timeout = 20_000)
    fun upnpXmlFundoDemaisEhRecusado() = semConsole {
        val fundo = "<b>".repeat(100_000) + "x" + "</b>".repeat(100_000)
        val didl = """<DIDL-Lite xmlns:dc="http://purl.org/dc/elements/1.1/"><item id="1"><dc:title>$fundo</dc:title><res>http://n/1</res></item></DIDL-Lite>"""
        val ficha = """<root><device><friendlyName>$fundo</friendlyName></device></root>"""
        for ((nome, bloco) in listOf<Pair<String, () -> Unit>>(
            "didl" to { Upnp.lerDidl(didl) },
            "ficha" to { Upnp.lerFicha(ficha.toByteArray(), "http://10.0.0.5/d.xml") },
            "soap" to { Upnp.lerRespostaNavegar("<e><Result>$fundo</Result></e>".toByteArray()) },
        )) caso("upnp-fundo-$nome", 0, { "(100 mil níveis)" }, upnpEsperadas) { bloco() }
        // Profundidade normal (DIDL de verdade tem 3 ou 4 níveis) continua passando.
        assertEquals(1, Upnp.lerDidl(didl.replace(fundo, "<b>".repeat(20) + "Filme" + "</b>".repeat(20))).size)
    }
}
