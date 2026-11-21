package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.DavLogica
import br.paulo.tvlauncher.panel.Mtp
import br.paulo.tvlauncher.panel.PainelHttp
import br.paulo.tvlauncher.panel.PtpIp
import br.paulo.tvlauncher.player.GuiaExtra
import br.paulo.tvlauncher.player.M3u
import br.paulo.tvlauncher.player.Xmltv
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random
import org.junit.Assert.fail
import org.junit.Test
import org.kxml2.io.KXmlParser

/**
 * Elevação 2.5 (17/11/2026): fuzz dos leitores escritos à mão, os que recebem bytes de fora:
 * HTTP do painel, WebDAV, MTP/PTP-IP, M3U, XMLTV (guia e guia extra).
 *
 * Como funciona: parte de entradas boas (a "semente") e estraga cada uma de muitos jeitos (troca, apaga, repete,
 * insere bytes e pedaços perigosos: \r\n, ":", "%", tamanhos enormes, "<!DOCTYPE", "&"). O contrato de cada leitor:
 *  - ou lê, ou recusa com IOException (inclui ErroHttp); NUNCA outra exceção (índice fora, número inválido, falta
 *    de memória), que no aparelho derruba a thread ou o processo da HOME;
 *  - e termina em menos de [PRAZO_MS] (nada de laço infinito com entrada maliciosa).
 * A semente aleatória é fixa (o CI repete igual). Para rodar mais: FUZZ_ITERACOES=200000 FUZZ_SEMENTE=7.
 * Quando falha, imprime a entrada em base64 para reproduzir.
 */
class FuzzLeitoresTest {
    private val iteracoes = System.getenv("FUZZ_ITERACOES")?.toIntOrNull() ?: 1500
    private val semente = System.getenv("FUZZ_SEMENTE")?.toIntOrNull() ?: 2026_11_17
    private val PRAZO_MS = 2000L
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "fuzz").apply { isDaemon = true } }

    private val PERIGOSOS = listOf("\r\n", "\n", "\r\n\r\n", ":", " ", "%", "%00", "%zz", "\u0000", "-1", "0",
        "99999999999999999999", "4294967295", "2147483648", "chunked", "Transfer-Encoding: chunked\r\n",
        "Content-Length: 5\r\n", "<", ">", "&", "&amp;", "&#x0;", "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]>",
        "]]>", "<![CDATA[", "#EXTINF:", "#EXTM3U", ",", "\"", "=", "tvg-id=\"", "../", "//", "\\", "ÿ", "￿",
        "Basic ", "Basic !!!", "Infinity", "Bearer ").map { it.toByteArray(Charsets.UTF_8) }

    private fun mutar(r: Random, base: ByteArray): ByteArray {
        var b = base
        repeat(1 + r.nextInt(4)) {
            b = when (r.nextInt(7)) {
                0 -> if (b.isEmpty()) b else b.copyOf().also { it[r.nextInt(it.size)] = r.nextInt(256).toByte() }
                1 -> if (b.isEmpty()) b else { val i = r.nextInt(b.size); val n = r.nextInt(minOf(64, b.size - i) + 1); b.copyOfRange(0, i) + b.copyOfRange(i + n, b.size) }
                2 -> { val i = r.nextInt(b.size + 1); b.copyOfRange(0, i) + PERIGOSOS[r.nextInt(PERIGOSOS.size)] + b.copyOfRange(i, b.size) }
                3 -> if (b.isEmpty()) b else { val i = r.nextInt(b.size); val n = r.nextInt(minOf(32, b.size - i) + 1); val p = b.copyOfRange(i, i + n)
                    b.copyOfRange(0, i) + p + p + b.copyOfRange(i, b.size) }
                4 -> b.copyOfRange(0, r.nextInt(b.size + 1))
                5 -> { val i = r.nextInt(b.size + 1); b.copyOfRange(0, i) + r.nextBytes(r.nextInt(16)) + b.copyOfRange(i, b.size) }
                else -> if (b.size < 4) b else b.copyOf().also { val i = r.nextInt(b.size - 3); for (k in 0 until 4) it[i + k] = 0xFF.toByte() }
            }
        }
        return b
    }

    /** Roda [ler] em cada entrada estragada; [aceita] diz quais exceções são recusa legítima. */
    private fun fuzz(nome: String, sementes: List<ByteArray>, aceita: (Throwable) -> Boolean = { it is IOException },
                     ler: (ByteArray) -> Unit) {
        val r = Random(semente + nome.hashCode())
        val problemas = ArrayList<String>()
        for (i in 0 until iteracoes) {
            val entrada = if (i < sementes.size) sementes[i] else mutar(r, sementes[r.nextInt(sementes.size)])
            val f = executor.submit<Throwable?> { try { ler(entrada); null } catch (t: Throwable) { t } }
            val erro = try { f.get(PRAZO_MS, TimeUnit.MILLISECONDS) } catch (e: TimeoutException) {
                f.cancel(true); problemas += "demorou mais de $PRAZO_MS ms: ${Base64.getEncoder().encodeToString(entrada)}"; break
            }
            if (erro != null && !aceita(erro)) {
                problemas += "${erro::class.java.simpleName}: ${erro.message?.take(80)}  entrada=${Base64.getEncoder().encodeToString(entrada.take(400).toByteArray())}"
                if (problemas.size >= 5) break
            }
        }
        if (problemas.isNotEmpty()) fail("$nome (${problemas.size}):\n" + problemas.joinToString("\n"))
    }

    private fun b(t: String) = t.toByteArray(Charsets.UTF_8)

    @Test fun httpDoPainel() = fuzz("http", listOf(
        b("GET /api/estado HTTP/1.1\r\nHost: tv.lan:8080\r\nCookie: s=abc\r\n\r\n"),
        b("POST /api/entrar HTTP/1.1\r\nHost: 192.168.1.2\r\nX-Painel: 1\r\nContent-Type: application/json\r\nContent-Length: 15\r\n\r\n{\"pin\":\"123456\"}"),
        b("PUT /dav/a%20b.txt HTTP/1.1\r\nHost: tv\r\nTransfer-Encoding: chunked\r\n\r\n5\r\nolá!\r\n0\r\n\r\n"),
        b("GET /api/arquivos?caminho=%2Fsdcard%2F&x=1 HTTP/1.1\r\nHost: tv\r\n\r\n"),
    )) { e ->
        val req = PainelHttp.lerCabecalhos(ByteArrayInputStream(e), prazoMs = 1000, chunkedEm = { it.startsWith("/dav/") })
        req.lerCorpo(64 * 1024)
    }

    @Test fun webDav() {
        val xmls = listOf(
            b("<?xml version=\"1.0\"?><D:propertyupdate xmlns:D=\"DAV:\" xmlns:Z=\"urn:schemas-microsoft-com:\"><D:set><D:prop>" +
                "<Z:Win32LastModifiedTime>Tue, 17 Nov 2026 10:00:00 GMT</Z:Win32LastModifiedTime></D:prop></D:set></D:propertyupdate>"),
            b("<?xml version=\"1.0\"?><D:lockinfo xmlns:D=\"DAV:\"><D:lockscope><D:exclusive/></D:lockscope><D:owner><D:href>http://x/</D:href></D:owner></D:lockinfo>"),
        )
        fuzz("dav-xml", xmls, { it is IOException }) { e ->
            val t = String(e, Charsets.UTF_8)
            DavLogica.propsDoProppatch(t); DavLogica.donoDoLock(t)
        }
        fuzz("dav-cabecalhos", listOf(b("https://tv.lan:8444/dav/pasta/a%20b.txt"), b("Basic dXNlcjoxMjM0NTY="),
            b("Tue, 17 Nov 2026 10:00:00 GMT"), b("/dav/../etc"))) { e ->
            val t = String(e, Charsets.UTF_8)
            DavLogica.destino(t, "tv.lan:8444"); DavLogica.senhaBasic(t); DavLogica.lerDataHttp(t)
            DavLogica.relativo(t); DavLogica.profundidade(t); DavLogica.sobrescrever(t)
        }
        fuzz("dav-chunked", listOf(b("5\r\nolá!\r\n0\r\n\r\n"), b("a;ext=1\r\n0123456789\r\n0\r\n\r\n"))) { e ->
            DavLogica.LeitorChunked(ByteArrayInputStream(e)).use { it.readBytes() }
        }
    }

    @Test fun mtp() {
        val info = Mtp.Escritor().u32(0x10001).u16(0x3000).u16(0).u32(1234).cru(ByteArray(2 + 4 * 6 + 4 + 2 + 4 + 4))
            .texto("Férias 2026.mp4").texto("20261117T100000").texto("20261117T100500").bytes()
        val lista = Mtp.Escritor().u32(2).u32(0).u16(0xDC07).u16(0xFFFF).texto("a.txt").u32(0).u16(0xDC09).u16(0xFFFF)
            .texto("20261117T100000").bytes()
        fuzz("mtp-objeto", listOf(info)) { e -> Mtp.lerInfoObjeto(e) }
        fuzz("mtp-props", listOf(lista)) { e -> Mtp.lerListaProps(e, 0x3000, 10L) }
        val pacote = Mtp.Escritor().u32(8 + 4).u32(1).u32(42).bytes()
        fuzz("ptpip", listOf(pacote, pacote + pacote)) { e -> val s = ByteArrayInputStream(e); PtpIp.ler(s); PtpIp.ler(s) }
    }

    @Test fun m3u() = fuzz("m3u", listOf(
        b("#EXTM3U url-tvg=\"https://guia.exemplo/epg.xml.gz\"\n#EXTINF:-1 tvg-id=\"globo.br\" tvg-logo=\"https://x/l.png\" group-title=\"Abertos\",Globo HD\nhttp://servidor:8080/live/u/p/1.ts\n" +
            "#EXTINF:-1 group-title=\"Filmes\",Matrix (1999) [Dublado]\nhttp://servidor:8080/movie/u/p/2.mp4\n#EXTVLCOPT:http-user-agent=X\n#EXTINF:0,Série S01E02\nhttps://x/3.mkv\n"),
    ), { it is IOException }) { e ->
        val t = String(e, Charsets.UTF_8)
        M3u.ler(t); M3u.guiaDaLista(t)
    }

    private val XMLTV = b("<?xml version=\"1.0\" encoding=\"UTF-8\"?><tv><channel id=\"globo.br\"><display-name>Globo</display-name></channel>" +
        "<programme start=\"20261117100000 -0300\" stop=\"20261117110000 -0300\" channel=\"globo.br\"><title lang=\"pt\">Jornal &amp; Cia</title>" +
        "<desc>Notícias</desc><category>Jornalismo</category><episode-num system=\"xmltv_ns\">1.2.</episode-num></programme></tv>")

    /** XMLTV: XmlPullParserException sem nada lido também é recusa (o PlayerCarga trata: "este guia não serve"). */
    private val recusaXml: (Throwable) -> Boolean = { it is IOException || it is org.xmlpull.v1.XmlPullParserException }

    @Test fun xmltv() {
        fuzz("xmltv", listOf(XMLTV), recusaXml) { e -> Xmltv.lerEmFluxo(KXmlParser(), ByteArrayInputStream(e)) {} }
        fuzz("xmltv-extra", listOf(XMLTV), recusaXml) { e ->
            Xmltv.lerExtraEmFluxo(KXmlParser(), ByteArrayInputStream(e), GuiaExtra.Mescla(mapOf("globo.br" to "Globo")),
                Long.MIN_VALUE, Long.MAX_VALUE, 1000) {}
        }
    }
}
