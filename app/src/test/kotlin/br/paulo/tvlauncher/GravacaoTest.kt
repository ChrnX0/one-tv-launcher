package br.paulo.tvlauncher

import br.paulo.tvlauncher.player.CanoAoVivo
import br.paulo.tvlauncher.player.GravacaoCanal
import br.paulo.tvlauncher.player.GravacaoRegras
import br.paulo.tvlauncher.player.Gravador
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gravação da TV ao vivo (14/11/2026): nomes, pedaços, espaço, agendamentos e o motor que copia o canal. */
class GravacaoTest {
    private val sp = TimeZone.getTimeZone("America/Sao_Paulo")
    private val h = 60 * 60_000L
    private val min = 60_000L

    // ------------------------------------------------------------ nomes e pedaços

    @Test fun nomeSemCaracteresQueOPendriveRecusa() {
        val t = 1_794_699_000_000L   // 14/11/2026 20:30 em São Paulo
        assertEquals("2026-11-14 2030 Globo - Jornal Nacional.ts", GravacaoRegras.nomeArquivo(t, "Globo", "Jornal Nacional", 1, sp))
        assertEquals("2026-11-14 2030 Globo - Jornal Nacional (parte 2).ts", GravacaoRegras.nomeArquivo(t, "Globo", "Jornal Nacional", 2, sp))
        assertEquals("2026-11-14 2030 SBT HD.ts", GravacaoRegras.nomeArquivo(t, "SBT HD", null, 1, sp))
        assertEquals("2026-11-14 2030 A B - C D.ts", GravacaoRegras.nomeArquivo(t, "A/B", "C:*?\"<>|D", 1, sp))
        assertEquals("gravacao", GravacaoRegras.limpar(" .. "))
        assertEquals("x", GravacaoRegras.limpar("x\u0000\n"))
    }

    @Test fun partesNaOrdem() {
        val p = "2026-11-14 2030 Globo.ts"
        val nomes = listOf("2026-11-14 2030 Globo (parte 10).ts", p, "outro.ts", "2026-11-14 2030 Globo (parte 2).ts")
        assertEquals(listOf(p, "2026-11-14 2030 Globo (parte 2).ts", "2026-11-14 2030 Globo (parte 10).ts"), GravacaoRegras.partes(nomes, p))
    }

    @Test fun pedacoDeUmaHoraOuPertoDoLimiteDoFat32() {
        assertFalse(GravacaoRegras.novoPedaco(59 * min, 1_000_000))
        assertTrue(GravacaoRegras.novoPedaco(h, 1))
        assertTrue(GravacaoRegras.novoPedaco(1, 3_800_000_000L))
    }

    @Test fun espacoEBitrate() {
        assertEquals(8_000_000L, GravacaoRegras.bitrate(1_000_000, 10_000))       // amostra curta: o pior caso
        assertEquals(4_000_000L, GravacaoRegras.bitrate(30_000_000, 60_000))
        assertEquals(300_000_000L, GravacaoRegras.bytesPara(10 * min, 4_000_000))  // 10 min a 4 Mbps = 300 MB
        assertTrue(GravacaoRegras.cabe(300_000_000L, 4_000_000))
        assertFalse(GravacaoRegras.cabe(299_999_999L, 4_000_000))
        assertEquals(18L, GravacaoRegras.horasQueCabem(41_000_000_000L, 5_000_000))   // 41 GB a 5 Mbps (2,25 GB/h)
    }

    // ------------------------------------------------------------ agendamentos

    private fun ag(id: String, ini: Long, fim: Long, semanal: Boolean = false) = GravacaoRegras.Agendada(id, "c$id", "Canal", "Prog", ini, fim, semanal)

    @Test fun conflitoContaAsFolgas() {
        val a = ag("a", 10 * h, 11 * h)
        assertEquals(listOf(a), GravacaoRegras.conflitos(ag("b", 11 * h + 2 * min, 12 * h), listOf(a)))   // 2 min depois: a folga de 5 encosta
        assertEquals(emptyList<Any>(), GravacaoRegras.conflitos(ag("c", 11 * h + 8 * min, 12 * h), listOf(a)))
        assertEquals(emptyList<Any>(), GravacaoRegras.conflitos(a, listOf(a)))   // a própria não conta
    }

    @Test fun todaSemana() {
        val a = ag("a", 10 * h, 11 * h, semanal = true)
        assertEquals(a, GravacaoRegras.proxima(a, 10 * h))
        val p = GravacaoRegras.proxima(a, 11 * h + 6 * min)!!
        assertEquals(10 * h + GravacaoRegras.SEMANA_MS, p.inicio)
        val muito = GravacaoRegras.proxima(a, 11 * h + 3 * GravacaoRegras.SEMANA_MS)!!   // TV ficou 3 semanas desligada
        assertTrue(muito.terminaEm > 11 * h + 3 * GravacaoRegras.SEMANA_MS && muito.inicio - 10 * h < 4 * GravacaoRegras.SEMANA_MS + 1)
        assertNull(GravacaoRegras.proxima(a.copy(semanal = false), 12 * h))
    }

    @Test fun agoraEProximaEJson() {
        val a = ag("a", 10 * h, 11 * h); val b = ag("b", 20 * h, 21 * h, true)
        assertEquals(a, GravacaoRegras.gravandoAgora(listOf(a, b), 10 * h - min))   // já dentro da folga de 2 min
        assertNull(GravacaoRegras.gravandoAgora(listOf(a, b), 15 * h))
        assertEquals(b, GravacaoRegras.proximaAComecar(listOf(a, b), 15 * h))
        assertEquals(listOf(a, b), GravacaoRegras.deJson(GravacaoRegras.paraJson(listOf(a, b))))
        assertEquals(emptyList<Any>(), GravacaoRegras.deJson("""[{"id":"x","canal":"c","inicio":5,"fim":4}]"""))
        assertEquals(emptyList<Any>(), GravacaoRegras.deJson("lixo"))
    }

    // ------------------------------------------------------------ o motor

    /** Relógio falso: cada leitura do canal "leva" [passo] ms. */
    private class Relogio(var agora: Long = 0L)

    private fun canal(r: Relogio, bytes: ByteArray, passo: Long, falharEm: Int = -1): InputStream = object : InputStream() {
        var i = 0
        override fun read(): Int = throw UnsupportedOperationException()
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (i == falharEm) { i++; throw IOException("caiu") }
            if (i >= bytes.size) return -1
            val n = minOf(len, 1000, bytes.size - i)
            System.arraycopy(bytes, i, b, off, n); i += n; r.agora += passo
            return n
        }
    }

    @Test fun copiaTudoAteOHorarioEDivideEmPedacos() {
        val r = Relogio()
        val dados = ByteArray(10_000) { it.toByte() }
        val pedacos = ArrayList<ByteArrayOutputStream>()
        val vistos = ByteArrayOutputStream()
        // 1000 bytes a cada 15 min: 10 leituras = 2 h 30. Termina em 2 h: 8 leituras.
        val g = Gravador({ canal(r, dados, 15 * min) }, { ByteArrayOutputStream().also { pedacos += it } }, { Long.MAX_VALUE }, { r.agora },
            fimMs = 2 * h, aoGravar = { b, n -> vistos.write(b, 0, n) })
        val res = g.rodar()
        assertEquals(Gravador.Motivo.FIM, res.motivo)
        assertEquals(8000L, res.bytes)
        assertEquals(2, res.partes)   // 2ª parte começa ao passar de 1 h
        val junto = ByteArrayOutputStream().apply { pedacos.forEach { write(it.toByteArray()) } }.toByteArray()
        assertArrayEquals(dados.copyOf(8000), junto)
        assertArrayEquals(junto, vistos.toByteArray())   // o player recebe exatamente o que foi gravado
    }

    @Test fun quedaAbrePedacoNovoESoGravaPacotesInteiros() {
        // Sala, 17/11/2026: a reconexão ia para o mesmo arquivo; o relógio do vídeo recomeçava e o pacote cortado
        // desalinhava o resto (1 h aparecia como 5:36:28). Agora: parte nova, e o pedaço de pacote cortado fica de fora.
        val r = Relogio(); var aberturas = 0
        val primeiro = ByteArray(1000) { 1 }
        val segundo = ByteArray(3000) { (it % 100).toByte() }
        val pedacos = ArrayList<ByteArrayOutputStream>()
        val res = Gravador({ aberturas++; if (aberturas == 1) canal(r, primeiro + ByteArray(10), min, falharEm = 1000) else canal(r, segundo, min) },
            { ByteArrayOutputStream().also { pedacos += it } }, { Long.MAX_VALUE }, { r.agora }, fimMs = 4 * min, esperar = {}).rodar()
        assertEquals(Gravador.Motivo.FIM, res.motivo)
        assertEquals(2, res.partes)
        assertEquals(5 * 188, pedacos[0].size())          // 1000 bytes = 5 pacotes + 60 cortados (descartados)
        assertArrayEquals(segundo, pedacos[1].toByteArray())   // a 2ª conexão inteira, com o fim do arquivo
        assertEquals(5L * 188 + 3000, res.bytes)
    }

    @Test fun reconectaQuandoACaiEDesisteDepoisDeOito() {
        val r = Relogio(); var aberturas = 0; val esperas = ArrayList<Long>()
        val g = Gravador({ aberturas++; canal(r, ByteArray(3000), min, falharEm = if (aberturas == 1) 1000 else -1) },
            { ByteArrayOutputStream() }, { Long.MAX_VALUE }, { r.agora }, fimMs = h, esperar = { esperas += it })
        val res = g.rodar()
        // Canal que acaba (fim do fluxo) conta como queda: 1ª queda no meio, depois 3 fins seguidos sem dado novo... na
        // prática cada reabertura traz 3000 bytes, então as falhas zeram; o horário (1 h) encerra.
        assertTrue(res.motivo == Gravador.Motivo.FIM || res.motivo == Gravador.Motivo.CONEXAO)
        assertTrue(aberturas >= 2); assertEquals(2000L, esperas.first())
        val semCanal = Gravador({ throw IOException("fora do ar") }, { ByteArrayOutputStream() }, { Long.MAX_VALUE }, { r.agora },
            fimMs = Long.MAX_VALUE, esperar = { esperas += it }).rodar()
        assertEquals(Gravador.Motivo.CONEXAO, semCanal.motivo)
        assertEquals(listOf(2000L, 4000L, 8000L, 16000L, 30000L, 30000L, 30000L), esperas.takeLast(7))
    }

    @Test fun paraSemEspacoEComDestinoQuebrado() {
        val r = Relogio()
        val semEspaco = Gravador({ canal(r, ByteArray(5000), min) }, { ByteArrayOutputStream() }, { 1000L }, { r.agora }, fimMs = h).rodar()
        assertEquals(Gravador.Motivo.SEM_ESPACO, semEspaco.motivo); assertEquals(0L, semEspaco.bytes)
        val quebrado = object : OutputStream() { override fun write(b: Int) = throw IOException("pendrive saiu") }
        val res = Gravador({ canal(r, ByteArray(5000), min) }, { quebrado }, { Long.MAX_VALUE }, { r.agora }, fimMs = Long.MAX_VALUE).rodar()
        assertEquals(Gravador.Motivo.DESTINO, res.motivo)
        val parar = AtomicBoolean(true)
        assertEquals(Gravador.Motivo.PARADO, Gravador({ ByteArrayInputStream(ByteArray(10)) }, { ByteArrayOutputStream() }, { Long.MAX_VALUE },
            { 0L }, fimMs = h, parar = parar).rodar().motivo)
    }

    // ------------------------------------------------------------ cano ao vivo (player lendo o que o gravador baixa)

    @Test fun canoEntregaOQueFoiEscritoEFimAoFechar() {
        val cano = CanoAoVivo(capacidade = 64, recuo = 16)
        val l = cano.leitor(esperaMs = 1000)
        val dados = ByteArray(40) { it.toByte() }
        cano.escrever(dados, 40)
        cano.fechar()
        assertArrayEquals(dados, l.readBytes())
        assertFalse(cano.aberto)
    }

    @Test fun canoLeitorNovoComecaNoRecuo() {
        val cano = CanoAoVivo(capacidade = 64, recuo = 16)
        cano.escrever(ByteArray(50) { it.toByte() }, 50)
        cano.fechar()
        assertArrayEquals(ByteArray(16) { (34 + it).toByte() }, cano.leitor(1000).readBytes())
    }

    @Test fun canoLeitorAtrasadoPulaParaOMaisAntigo() {
        val cano = CanoAoVivo(capacidade = 32, recuo = 32)
        val l = cano.leitor(1000)
        cano.escrever(ByteArray(100) { it.toByte() }, 100)   // dá a volta 3 vezes
        cano.fechar()
        assertArrayEquals(ByteArray(32) { (68 + it).toByte() }, l.readBytes())
    }

    @Test fun canoLeitorEsperaOGravador() {
        val cano = CanoAoVivo(capacidade = 64, recuo = 0)
        val l = cano.leitor(5000)
        val t = Thread { Thread.sleep(100); cano.escrever(byteArrayOf(7, 8), 2); cano.fechar() }
        t.start()
        assertArrayEquals(byteArrayOf(7, 8), l.readBytes())
        t.join()
    }

    @Test(expected = IOException::class) fun canoSemDadosDaErro() {
        CanoAoVivo(capacidade = 64, recuo = 0).leitor(esperaMs = 50).read()
    }

    // ------------------------------------------------------------ tela Gravações e HLS

    @Test fun lerNomeDesfazONomeDoArquivo() {
        val t = 1_794_699_000_000L
        val n = GravacaoRegras.nomeArquivo(t, "Globo", "Jornal Nacional", 2, sp)
        assertEquals(t to "Globo - Jornal Nacional", GravacaoRegras.lerNome(n, sp))
        assertEquals(null to "meu video", GravacaoRegras.lerNome("meu video.ts", sp))
    }

    @Test fun agruparPartesDaMesmaGravacao() {
        val nomes = listOf("2026-11-13 2228 A.ts", "2026-11-14 2030 B.ts", "2026-11-14 2030 B (parte 3).ts", "2026-11-14 2030 B (parte 2).ts")
        assertEquals(listOf(listOf("2026-11-14 2030 B.ts", "2026-11-14 2030 B (parte 2).ts", "2026-11-14 2030 B (parte 3).ts"),
            listOf("2026-11-13 2228 A.ts")), GravacaoRegras.agrupar(nomes))
    }

    @Test fun hlsMestraEscolheAMaiorQualidade() {
        val m = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=800000\nbaixa/i.m3u8\n#EXT-X-STREAM-INF:BANDWIDTH=4000000,RESOLUTION=1920x1080\nalta/i.m3u8\n"
        assertEquals("http://s/c/alta/i.m3u8", GravacaoCanal.variante("http://s/c/mestra.m3u8", m))
        assertNull(GravacaoCanal.variante("http://s/c/x.m3u8", "#EXTM3U\n#EXTINF:6,\na.ts\n"))
    }

    @Test fun hlsSoPedacosNovos() {
        val l = GravacaoCanal.lerLista("http://s/c/i.m3u8", "#EXTM3U\n#EXT-X-TARGETDURATION:6\n#EXT-X-MEDIA-SEQUENCE:100\n#EXTINF:6,\na.ts\n#EXTINF:6,\nb.ts\n#EXTINF:6,\nhttp://o/c.ts\n")
        assertEquals(6, l.duracaoAlvoS); assertFalse(l.fim)
        assertEquals(listOf(101L to "http://s/c/b.ts", 102L to "http://o/c.ts"), GravacaoCanal.novos(l, -1))
        assertEquals(listOf(102L to "http://o/c.ts"), GravacaoCanal.novos(l, 101))
        assertTrue(GravacaoCanal.novos(l, 102).isEmpty())
    }

    @Test(expected = IOException::class) fun hlsFmp4Recusado() {
        GravacaoCanal.lerLista("http://s/i.m3u8", "#EXTM3U\n#EXT-X-MAP:URI=\"init.mp4\"\n#EXTINF:6,\na.m4s\n")
    }

    @Test fun ehHlsPelaExtensaoOuTipo() {
        assertTrue(GravacaoCanal.ehHls("http://s/c.m3u8?t=1", ""))
        assertTrue(GravacaoCanal.ehHls("http://s/live/1", "application/vnd.apple.mpegurl"))
        assertFalse(GravacaoCanal.ehHls("http://s/live/1.ts", "video/mp2t"))
    }

    // ---------------------------------------------------------------- "Gravar onde?" (20/11/2026)

    @Test fun lugarDaUltimaVezVemPrimeiro() {
        val l = listOf("usb:/a", "conta:1", "conta:2")
        assertEquals(listOf("conta:2", "usb:/a", "conta:1"), GravacaoRegras.ordenarOpcoes(l, "conta:2") { it })
        assertEquals(l, GravacaoRegras.ordenarOpcoes(l, "usb:/a") { it })
        assertEquals(l, GravacaoRegras.ordenarOpcoes(l, "sumiu") { it })   // pendrive tirado: ordem de sempre
    }

    @Test fun soPerguntaComEscolha() {
        assertFalse(GravacaoRegras.perguntarOnde(0)); assertFalse(GravacaoRegras.perguntarOnde(1)); assertTrue(GravacaoRegras.perguntarOnde(2))
    }

    @Test fun agendadaGuardaOLugar() {
        val a = GravacaoRegras.Agendada("x", "c1", "Canal", "Jornal", 10_000_000L, 13_600_000L, false, "conta:9")
        assertEquals("conta:9", GravacaoRegras.deJson(GravacaoRegras.paraJson(listOf(a))).single().destino)
        // Agendada de antes (sem o campo): lugar automático.
        val velha = """[{"id":"y","canal":"c1","canalNome":"Canal","programa":"P","inicio":10000000,"fim":13600000,"semanal":false}]"""
        assertEquals("", GravacaoRegras.deJson(velha).single().destino)
    }
}
