package br.paulo.tvlauncher

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException

/** Testes do HTTP mínimo do painel (sem Android: roda no computador). */
class PainelHttpTest {
    private fun ler(texto: String, limite: Int = 1024) =
        PainelHttp.ler(ByteArrayInputStream(texto.toByteArray(Charsets.ISO_8859_1)), limite)

    private fun codigoDoErro(texto: String, limite: Int = 1024): Int = try {
        ler(texto, limite); fail("devia ter dado erro"); 0
    } catch (e: ErroHttp) { e.codigo }

    @Test fun getSimples() {
        val r = ler("GET /api/apps?x=1&nome=a%20b HTTP/1.1\r\nHost: 192.168.1.50:8080\r\nCookie: a=1; painel_sessao=abc\r\n\r\n")
        assertEquals("GET", r.metodo)
        assertEquals("/api/apps", r.caminho)
        assertEquals("1", r.consulta["x"])
        assertEquals("a b", r.consulta["nome"])
        assertEquals("192.168.1.50:8080", r.cabecalho("Host"))
        assertEquals("abc", PainelHttp.cookie(r.cabecalho("cookie"), "painel_sessao"))
        assertEquals(0, r.corpo.size)
    }

    @Test fun postComCorpo() {
        val corpo = """{"pin":"123456"}"""
        val r = ler("POST /api/entrar HTTP/1.1\r\nHost: x\r\nContent-Length: ${corpo.length}\r\n\r\n$corpo")
        assertEquals("POST", r.metodo)
        assertEquals(corpo, r.corpoTexto())
    }

    @Test fun corpoNaoEngoleOQueVemDepois() {
        val entrada = ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 3\r\n\r\nabcEXTRA".toByteArray())
        val r = PainelHttp.ler(entrada, 100)
        assertArrayEquals("abc".toByteArray(), r.corpo)
    }

    @Test fun aceitaSoLF() {
        val r = ler("GET / HTTP/1.1\nHost: x\n\n")
        assertEquals("/", r.caminho)
        assertEquals("x", r.cabecalho("host"))
    }

    @Test fun ignoraLinhaVaziaAntesDoPedido() {
        assertEquals("/", ler("\r\nGET / HTTP/1.1\r\n\r\n").caminho)
    }

    @Test fun corpoGrandeDemais413() = assertEquals(413, codigoDoErro("POST / HTTP/1.1\r\nContent-Length: 2000\r\n\r\n", 1024))

    @Test fun cabecalhosGrandesDemais431() =
        assertEquals(431, codigoDoErro("GET / HTTP/1.1\r\nX: ${"a".repeat(9000)}\r\n\r\n"))

    @Test fun chunkedRecusado() =
        assertEquals(411, codigoDoErro("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n0\r\n\r\n"))

    @Test fun hostRepetidoRecusado() =
        assertEquals(400, codigoDoErro("GET / HTTP/1.1\r\nHost: a\r\nHost: b\r\n\r\n"))

    @Test fun contentLengthInvalido() {
        assertEquals(400, codigoDoErro("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n"))
        assertEquals(400, codigoDoErro("POST / HTTP/1.1\r\nContent-Length: abc\r\n\r\n"))
        // Desde a fase 2 o tamanho é Long (envio de arquivo grande): 99999999999 é válido, mas não cabe na memória.
        assertEquals(413, codigoDoErro("POST / HTTP/1.1\r\nContent-Length: 99999999999\r\n\r\n"))
    }

    @Test fun pedidoMalformado() {
        assertEquals(400, codigoDoErro("GET\r\n\r\n"))
        assertEquals(400, codigoDoErro("GET http://evil/ HTTP/1.1\r\n\r\n"))
        assertEquals(400, codigoDoErro("GET / FTP/1.0\r\n\r\n"))
        assertEquals(400, codigoDoErro("GET / HTTP/1.1\r\nsemdoispontos\r\n\r\n"))
        assertEquals(400, codigoDoErro("GET /?a=%zz HTTP/1.1\r\n\r\n"))
    }

    @Test fun corpoIncompletoEConexaoVazia() {
        try { ler("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc"); fail() } catch (e: ErroHttp) { fail("não é erro HTTP") } catch (e: IOException) {}
        try { ler(""); fail() } catch (e: ErroHttp) { fail("não é erro HTTP") } catch (e: IOException) {}
        assertEquals(400, codigoDoErro("GET / HTTP/1.1\r\nHost: x"))   // cabeçalhos cortados
    }

    @Test fun cookie() {
        assertEquals("v", PainelHttp.cookie("painel_sessao=v", "painel_sessao"))
        assertEquals("v", PainelHttp.cookie("x=1;  painel_sessao=v ; y=2", "painel_sessao"))
        assertNull(PainelHttp.cookie("xpainel_sessao=v", "painel_sessao"))
        assertNull(PainelHttp.cookie(null, "painel_sessao"))
        assertNull(PainelHttp.cookie("", "painel_sessao"))
    }

    @Test fun respostaTemCabecalhosDeSeguranca() {
        val saida = ByteArrayOutputStream()
        PainelHttp.escrever(saida, 200, "text/plain", "oi".toByteArray(), mapOf("Cache-Control" to "private, max-age=60"))
        val texto = saida.toString("ISO-8859-1")
        assertTrue(texto.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue("Content-Security-Policy: default-src 'self'" in texto)
        assertTrue("frame-ancestors 'none'" in texto)
        assertTrue("X-Content-Type-Options: nosniff\r\n" in texto)
        assertTrue("Cache-Control: private, max-age=60\r\n" in texto)   // extras sobrescrevem
        assertTrue("Content-Length: 2\r\n" in texto)
        assertTrue("Connection: close\r\n" in texto)
        assertTrue(texto.endsWith("\r\n\r\noi"))
    }

    @Test fun recusaQuebraDeLinhaEmCabecalho() {
        try {
            PainelHttp.escrever(ByteArrayOutputStream(), 200, null, ByteArray(0), mapOf("X" to "a\r\nSet-Cookie: x=1"))
            fail()
        } catch (e: IllegalArgumentException) {}
    }

    // ---------------------------------------------------------------- fase 2: corpo em fluxo, download

    @Test fun cabecalhosSemLerCorpoEFluxoParaNoTamanho() {
        val entrada = ByteArrayInputStream("POST /api/arquivos/enviar?nome=a.txt HTTP/1.1\r\nHost: x\r\nContent-Length: 5\r\n\r\nolá!LIXO".toByteArray(Charsets.UTF_8))
        val r = PainelHttp.lerCabecalhos(entrada)
        assertEquals(5L, r.tamanho)
        assertEquals("a.txt", r.consulta["nome"])
        // "olá!" em UTF-8 tem 5 bytes; o fluxo para ali e não entrega o "LIXO" que vem depois.
        assertEquals("olá!", String(r.fluxoCorpo().readBytes(), Charsets.UTF_8))
    }

    @Test fun fluxoCurtoDaErro() {
        val r = PainelHttp.lerCabecalhos(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 10\r\n\r\nabc".toByteArray()))
        try { r.fluxoCorpo().readBytes(); fail() } catch (e: IOException) {}
    }

    @Test fun contentLengthGrandeCabeEmLong() {
        val r = PainelHttp.lerCabecalhos(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 5000000000\r\n\r\n".toByteArray()))
        assertEquals(5_000_000_000L, r.tamanho)
        // Mas lerCorpo (memória) recusa com 413.
        try { r.lerCorpo(4096); fail() } catch (e: ErroHttp) { assertEquals(413, e.codigo) }
    }

    @Test fun contentLengthEsquisitoOuRepetido() {
        for (cl in listOf("+5", "-1", "0x10", "", "1".repeat(19))) {
            try { ler("POST / HTTP/1.1\r\nContent-Length:$cl\r\n\r\n"); fail("aceitou [$cl]") } catch (e: ErroHttp) { assertEquals(400, e.codigo) }
        }
        try { ler("POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\nab"); fail() } catch (e: ErroHttp) { assertEquals(400, e.codigo) }
    }

    @Test fun nomeParaBaixarComAcento() {
        val v = PainelHttp.nomeParaBaixar("Férias \"2026\".mp4")
        assertEquals("attachment; filename=\"F_rias _2026_.mp4\"; filename*=UTF-8''F%C3%A9rias%20%222026%22.mp4", v)
        // E passa pela barreira de cabeçalho (só ASCII, sem quebra de linha).
        PainelHttp.escrever(ByteArrayOutputStream(), 200, null, ByteArray(0), mapOf("Content-Disposition" to v))
    }

    @Test fun cabecalhoComAcentoCruERecusado() {
        try { PainelHttp.escrever(ByteArrayOutputStream(), 200, null, ByteArray(0), mapOf("X" to "Férias")); fail() } catch (e: IllegalArgumentException) {}
    }

    /** Revisão 09/11/2026: cabeçalho que chega devagar demais (1 byte por vez, "slowloris") é cortado no prazo total. */
    @Test fun cabecalhoLentoEstouraOPrazo() {
        var agora = 0L
        val texto = "GET / HTTP/1.1\r\nHost: x\r\n\r\n".toByteArray()
        val lento = object : java.io.InputStream() {
            var i = 0
            override fun read(): Int { agora += 1_000; return if (i < texto.size) texto[i++].toInt() else -1 }
        }
        try { PainelHttp.lerCabecalhos(lento, prazoMs = 10_000, relogio = { agora }); fail() } catch (e: ErroHttp) { assertEquals(408, e.codigo) }
        // O mesmo pedido chegando rápido passa.
        val r = PainelHttp.lerCabecalhos(ByteArrayInputStream(texto), prazoMs = 10_000, relogio = { 0L })
        assertEquals("/", r.caminho)
    }

    @Test fun faixaDoVideo() {
        assertNull(PainelHttp.faixa(null, 1000))
        assertEquals(0L to 999L, PainelHttp.faixa("bytes=0-", 1000))
        assertEquals(100L to 199L, PainelHttp.faixa("bytes=100-199", 1000))
        assertEquals(900L to 999L, PainelHttp.faixa("bytes=900-5000", 1000))   // fim além do arquivo: corta no fim
        assertEquals(800L to 999L, PainelHttp.faixa("bytes=-200", 1000))      // os últimos 200
        assertEquals(0L to 999L, PainelHttp.faixa("bytes=-5000", 1000))
        assertEquals(-1L to -1L, PainelHttp.faixa("bytes=1000-", 1000))       // começa depois do fim: 416
        assertEquals(-1L to -1L, PainelHttp.faixa("bytes=-0", 1000))
        // Inválidos viram "arquivo inteiro" (200), nunca erro nem conta negativa.
        assertNull(PainelHttp.faixa("bytes=200-100", 1000))
        assertNull(PainelHttp.faixa("bytes=0-1,5-6", 1000))
        assertNull(PainelHttp.faixa("itens=0-1", 1000))
        assertNull(PainelHttp.faixa("bytes=-", 1000))
        assertNull(PainelHttp.faixa("bytes=a-b", 1000))
        assertNull(PainelHttp.faixa("bytes=99999999999999999999-", 1000))
        assertNull(PainelHttp.faixa("bytes=0-524287", 0))   // texto vazio na prévia: 200 com 0 bytes, não 416
    }

    @Test fun previaSoParaTiposSeguros() {
        assertEquals("image/jpeg", PainelHttp.tipoPrevia("Férias.JPG"))
        assertEquals("video/mp4", PainelHttp.tipoPrevia("filme.mp4"))
        assertEquals("text/plain; charset=utf-8", PainelHttp.tipoPrevia("legenda.srt"))
        // Nada que o navegador execute: página, SVG, XML, script, e sem extensão.
        for (n in listOf("x.html", "x.htm", "x.svg", "x.xml", "x.js", "x.xhtml", "x.mht", "x.pdf", "semextensao", "x.apk"))
            assertNull(n, PainelHttp.tipoPrevia(n))
    }

    // Auditoria 10/11/2026: a foto da tela no Controle é um blob: (URL.createObjectURL); sem blob: no img-src, nunca aparecia.
    @Test fun cspPermiteFotoBlob() {
        val csp = PainelHttp.CABECALHOS_SEGURANCA.getValue("Content-Security-Policy")
        val img = csp.split(';').map { it.trim() }.first { it.startsWith("img-src") }
        assertTrue(img, img.split(' ').contains("blob:"))
        val script = csp.split(';').map { it.trim() }.first { it.startsWith("script-src") }
        assertEquals("script-src 'self'", script)   // script continua só do próprio painel
    }

    // Auditoria 10/11/2026: 1 byte a cada poucos segundos segurava a thread por horas. Agora o corpo tem prazo total.
    @Test fun corpoLentoEstouraOPrazo() {
        var agora = 0L
        val relogio = { agora }
        val cabecalhos = "POST /api/x HTTP/1.1\r\nHost: a\r\nContent-Length: 4096\r\n\r\n".toByteArray()
        val dentro = ByteArrayInputStream(cabecalhos + ByteArray(4096))
        val gotejando = object : java.io.InputStream() {
            override fun read(): Int = dentro.read()
            override fun read(b: ByteArray, off: Int, len: Int): Int { agora += 5_000; return dentro.read(b, off, minOf(len, 1)) }
        }
        val req = PainelHttp.lerCabecalhos(gotejando, relogio = relogio)
        try {
            req.lerCorpo(8192)
            fail("devia estourar o prazo")
        } catch (e: ErroHttp) {
            assertEquals(408, e.codigo)
        }
    }

    @Test fun corpoNormalDentroDoPrazo() {
        val req = PainelHttp.lerCabecalhos(ByteArrayInputStream("POST /api/x HTTP/1.1\r\nHost: a\r\nContent-Length: 2\r\n\r\n{}".toByteArray()))
        assertEquals("{}", String(req.lerCorpo(10)))
    }

    @Test fun descartarCorpoDrenaSemGuardar() {
        val dentro = ByteArrayInputStream("POST /api/x HTTP/1.1\r\nHost: a\r\nContent-Length: 20000\r\n\r\n".toByteArray() + ByteArray(20000) + "resto".toByteArray())
        val req = PainelHttp.lerCabecalhos(dentro)
        assertTrue(req.descartarCorpo(30000))
        assertEquals("resto", String(dentro.readBytes()))   // leu exatamente o corpo, nem um byte a mais
        assertTrue(!PainelHttp.lerCabecalhos(ByteArrayInputStream("POST / HTTP/1.1\r\nContent-Length: 9\r\n\r\n123456789".toByteArray())).descartarCorpo(5))
    }
}
