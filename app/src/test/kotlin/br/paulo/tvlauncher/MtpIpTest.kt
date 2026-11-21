package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.EscritaAdiantada
import br.paulo.tvlauncher.panel.Mtp
import br.paulo.tvlauncher.panel.MtpArmazem
import br.paulo.tvlauncher.panel.MtpLoja
import br.paulo.tvlauncher.panel.MtpServico
import br.paulo.tvlauncher.panel.PainelArquivos
import br.paulo.tvlauncher.panel.PtpIp
import br.paulo.tvlauncher.panel.entrar
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test

/**
 * O servidor MTP/IP inteiro (MtpIp.kt) no ar na JVM, numa pasta temporária, com um "computador" de teste que fala o
 * protocolo byte a byte. Para um cliente real (gphoto2 --port ptpip:127.0.0.1): ONETV_MTP_SERVIR=1 ONETV_MTP_RAIZ=<pasta>
 * roda só [servirParaClientesReais] na porta 15740, até ser morto.
 */
class MtpIpTest {
    private lateinit var raiz: File
    private lateinit var ss: ServerSocket
    private lateinit var servico: MtpServico
    private val pedidos = ArrayList<String>()
    @Volatile private var deixa = true
    private val ap = Mtp.Aparelho("Sala de teste", "One TV", "1", "abc", ByteArray(16) { it.toByte() }, byteArrayOf(0, 0, 1, 0, 9))
    /** Os pendrives "espetados" no teste. */
    private val pendrives = java.util.concurrent.CopyOnWriteArrayList<MtpArmazem>()

    private fun subir(pasta: File, porta: Int = 0) {
        raiz = pasta
        PainelArquivos.raizTeste = raiz
        ss = ServerSocket(porta, 16, InetAddress.getLoopbackAddress())
        servico = MtpServico(ss, ap, MtpLoja({ listOf(MtpArmazem.interno()) + pendrives }) {}, { true }, { id, nome, _ -> synchronized(pedidos) { pedidos.add("$id $nome") }; deixa })
        Thread { servico.rodar() }.apply { isDaemon = true }.start()
    }

    @Before fun antes() {
        val t = Files.createTempDirectory("mtp").toFile()
        File(t, "Download").mkdir(); File(t, "Movies").mkdir(); File(t, "Android/data").mkdirs()
        File(t, "Download/ola.txt").writeText("olá, mundo")
        File(t, "Download/.oculto.parcial").writeText("x")
        File(t, "Android/data/segredo").writeText("não")
        subir(t)
    }

    @After fun depois() { servico.parar(); PainelArquivos.raizTeste = null; raiz.deleteRecursively() }

    /** O computador de teste: conexão de comandos + de eventos, e uma função por tipo de conversa. */
    private inner class Pc {
        val c = Socket(InetAddress.getLoopbackAddress(), ss.localPort)
        val e: InputStream = c.getInputStream()
        val s: OutputStream = c.getOutputStream()
        var ev: Socket? = null
        private var tid = 0
        var numero = 0

        fun entrar(): Int {
            val w = Mtp.Escritor().cru(ByteArray(16) { 7 })
            "PC de teste".forEach { w.u16(it.code) }
            PtpIp.escrever(s, PtpIp.INICIO_COMANDO, w.u16(0).u32(0x10000).bytes()); s.flush()
            val (t, b) = PtpIp.ler(e)
            if (t == PtpIp.INICIO_COMANDO_OK) {
                numero = Mtp.Leitor(b).u32()
                ev = Socket(InetAddress.getLoopbackAddress(), ss.localPort).also {
                    PtpIp.escrever(it.getOutputStream(), PtpIp.INICIO_EVENTO, Mtp.Escritor().u32(numero).bytes())
                    assertEquals(PtpIp.INICIO_EVENTO_OK, PtpIp.ler(it.getInputStream()).first)
                }
            }
            return t
        }

        /** Pedido sem dados de ida. Devolve (código, parâmetros, dados recebidos). */
        fun op(codigo: Int, vararg p: Int, mandar: ByteArray? = null): Triple<Int, List<Int>, ByteArray> {
            val id = ++tid
            val w = Mtp.Escritor().u32(if (mandar != null) 2 else 1).u16(codigo).u32(id)
            p.forEach { w.u32(it) }
            PtpIp.escrever(s, PtpIp.OPERACAO, w.bytes())
            if (mandar != null) {
                PtpIp.escrever(s, PtpIp.DADOS_COMECO, Mtp.Escritor().u32(id).u64(mandar.size.toLong()).bytes())
                val meio = mandar.size / 2   // em dois pacotes, para exercitar a remontagem
                PtpIp.escrever(s, PtpIp.DADOS, Mtp.Escritor().u32(id).cru(mandar.copyOfRange(0, meio)).bytes())
                PtpIp.escrever(s, PtpIp.DADOS_FIM, Mtp.Escritor().u32(id).cru(mandar.copyOfRange(meio, mandar.size)).bytes())
            }
            s.flush()
            val dados = ByteArrayOutputStream()
            while (true) {
                val (t, b) = PtpIp.ler(e)
                when (t) {
                    PtpIp.DADOS_COMECO -> {}
                    PtpIp.DADOS, PtpIp.DADOS_FIM -> dados.write(b, 4, b.size - 4)
                    PtpIp.RESPOSTA -> {
                        val l = Mtp.Leitor(b); val cod = l.u16(); assertEquals(id, l.u32())
                        val ps = ArrayList<Int>(); while (l.resto >= 4) ps.add(l.u32())
                        return Triple(cod, ps, dados.toByteArray())
                    }
                    else -> error("pacote $t")
                }
            }
        }

        fun handles(pai: Int): List<Int> {
            val l = Mtp.Leitor(op(Mtp.OP_HANDLES, Mtp.TODOS, 0, pai).third)
            return List(l.u32()) { l.u32() }
        }
        fun nome(h: Int): String = Mtp.lerInfoObjeto(op(Mtp.OP_INFO_OBJETO, h).third).nome
        fun achar(pai: Int, nome: String): Int = handles(pai).first { nome(it) == nome }
        fun fechar() { c.close(); ev?.close() }
    }

    private fun infoPara(nome: String, tamanho: Int, pasta: Boolean = false): ByteArray =
        Mtp.infoObjeto(Mtp.Objeto(0, 0, nome, pasta, tamanho.toLong(), 1_700_000_000_000L, ByteArray(16)))

    @Test fun textoENumerosVaoEVoltam() {
        val b = Mtp.Escritor().u16(0xBEEF).u32(-2).u64(1L shl 40).texto("ação").texto("").bytes()
        val l = Mtp.Leitor(b)
        assertEquals(0xBEEF, l.u16()); assertEquals(-2, l.u32()); assertEquals(1L shl 40, l.u64())
        assertEquals("ação", l.texto()); assertEquals("", l.texto()); assertEquals(0, l.resto)
    }

    @Test fun apertoDeMaoEListagem() {
        val pc = Pc()
        assertEquals(PtpIp.INICIO_COMANDO_OK, pc.entrar())
        assertTrue(pedidos.single().endsWith("PC de teste"))
        // antes de abrir a sessão só a ficha do aparelho responde
        assertEquals(Mtp.SESSAO_FECHADA, pc.op(Mtp.OP_ARMAZENS).first)
        val info = pc.op(Mtp.OP_INFO_APARELHO)
        assertEquals(Mtp.OK, info.first)
        val l = Mtp.Leitor(info.third); l.u16(); assertEquals(6, l.u32()); l.u16(); assertEquals("microsoft.com: 1.0;", l.texto())
        assertEquals(Mtp.OK, pc.op(Mtp.OP_ABRIR_SESSAO, 1).first)
        val raizNomes = pc.handles(Mtp.TODOS).map { pc.nome(it) }
        assertEquals(listOf("Download", "Movies"), raizNomes)   // Android/ não aparece
        val d = pc.achar(Mtp.TODOS, "Download")
        assertEquals(listOf("ola.txt"), pc.handles(d).map { pc.nome(it) })   // o .parcial escondido não aparece
        // "tudo de uma vez" (pai 0) traz a árvore inteira
        assertEquals(3, pc.handles(0).size)
        pc.fechar()
    }

    @Test fun pendriveViraArmazenamentoSeparadoEAvisaQuandoEntraESai() {
        val pen = Files.createTempDirectory("pen").toFile()
        File(pen, "Fotos").mkdir(); File(pen, "Fotos/a.jpg").writeText("jpg"); File(pen, "Android").mkdir()
        val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
        fun lista(r: Triple<Int, List<Int>, ByteArray>) = Mtp.Leitor(r.third).let { l -> List(l.u32()) { l.u32() } }
        fun evento(): Pair<Int, Int> {
            val (t, b) = PtpIp.ler(pc.ev!!.getInputStream()); assertEquals(PtpIp.EVENTO, t)
            val l = Mtp.Leitor(b); val cod = l.u16(); l.u32(); return cod to l.u32()
        }
        assertEquals(listOf(Mtp.ARMAZEM), lista(pc.op(Mtp.OP_ARMAZENS)))
        // espeta o pendrive: o computador é avisado e passa a ver dois armazenamentos
        val a = MtpArmazem(MtpArmazem.idDoPendrive(1), "Pendrive", "8991-1F25", true) { pen }
        pendrives.add(a); servico.avisarArmazem(a.id, true)
        assertEquals(Mtp.EV_ARMAZEM_ENTROU to 0x20001, evento())
        assertEquals(listOf(Mtp.ARMAZEM, 0x20001), lista(pc.op(Mtp.OP_ARMAZENS)))
        val info = Mtp.Leitor(pc.op(Mtp.OP_INFO_ARMAZEM, a.id).third)
        assertEquals(0x0004, info.u16())   // de tirar
        // cada um lista só o que é seu; a pasta Android do pendrive também fica de fora
        val noPen = lista(pc.op(Mtp.OP_HANDLES, a.id, 0, Mtp.TODOS))
        assertEquals(listOf("Fotos"), noPen.map { pc.nome(it) })
        assertEquals(listOf("Download", "Movies"), lista(pc.op(Mtp.OP_HANDLES, Mtp.ARMAZEM, 0, Mtp.TODOS)).map { pc.nome(it) })
        assertEquals(a.id, Mtp.Leitor(pc.op(Mtp.OP_INFO_OBJETO, noPen[0]).third).u32())
        assertEquals(listOf("a.jpg"), pc.handles(noPen[0]).map { pc.nome(it) })
        // grava no pendrive
        val r = pc.op(Mtp.OP_MANDAR_INFO, a.id, noPen[0], mandar = infoPara("b.txt", 3))
        assertEquals(Mtp.OK, r.first); assertEquals(a.id, r.second[0])
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MANDAR_OBJETO, mandar = "abc".toByteArray()).first)
        assertEquals("abc", File(pen, "Fotos/b.txt").readText())
        // mover do pendrive para a memória interna: recusado (o computador copia e apaga)
        val d = pc.achar(Mtp.TODOS, "Download")
        assertTrue(pc.op(Mtp.OP_MOVER, r.second[2], Mtp.ARMAZEM, d).first != Mtp.OK)
        assertTrue(File(pen, "Fotos/b.txt").exists())
        // tira o pendrive: aviso, e os números dele deixam de valer
        pendrives.clear(); servico.avisarArmazem(a.id, false)
        assertEquals(Mtp.EV_ARMAZEM_SAIU to 0x20001, evento())
        assertEquals(Mtp.HANDLE_INVALIDO, pc.op(Mtp.OP_INFO_OBJETO, noPen[0]).first)
        assertEquals(Mtp.ARMAZEM_INVALIDO, pc.op(Mtp.OP_INFO_ARMAZEM, a.id).first)
        assertEquals(listOf("Download", "Movies"), pc.handles(Mtp.TODOS).map { pc.nome(it) })
        pc.fechar(); pen.deleteRecursively()
    }

    @Test fun iconeDaMarcaVaiComoPropriedadeDoAparelho() {
        val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
        val v = pc.op(Mtp.OP_VALOR_PROP_APARELHO, Mtp.PA_ICONE)
        assertEquals(Mtp.OK, v.first)
        assertArrayEquals(Mtp.listaBytes(ap.icone!!), v.third)
        val d = Mtp.Leitor(pc.op(Mtp.OP_DESC_PROP_APARELHO, Mtp.PA_ICONE).third)
        assertEquals(Mtp.PA_ICONE, d.u16()); assertEquals(Mtp.T_BYTES, d.u16())
        pc.fechar()
    }

    @Test fun baixaInteiroEAosPedacos() {
        val grande = ByteArray(700_000) { (it * 31).toByte() }
        File(raiz, "Movies/filme.bin").writeBytes(grande)
        val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
        val h = pc.achar(pc.achar(Mtp.TODOS, "Movies"), "filme.bin")
        assertArrayEquals(grande, pc.op(Mtp.OP_OBJETO, h).third)
        val pedaco = pc.op(Mtp.OP_PEDACO, h, 1000, 5000)
        assertEquals(listOf(5000), pedaco.second)
        assertArrayEquals(grande.copyOfRange(1000, 6000), pedaco.third)
        // arquivo vazio também
        File(raiz, "Movies/vazio").writeBytes(ByteArray(0))
        assertEquals(0, pc.op(Mtp.OP_OBJETO, pc.achar(pc.achar(Mtp.TODOS, "Movies"), "vazio")).third.size)
        pc.fechar()
    }

    @Test fun enviaRenomeiaMoveEApaga() {
        val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
        val movies = pc.achar(Mtp.TODOS, "Movies")
        val conteudo = ByteArray(2_500_000) { (it % 251).toByte() }
        val r = pc.op(Mtp.OP_MANDAR_INFO, Mtp.ARMAZEM, movies, mandar = infoPara("novo vídeo.mp4", conteudo.size))
        assertEquals(Mtp.OK, r.first)
        val h = r.second[2]
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MANDAR_OBJETO, mandar = conteudo).first)
        assertArrayEquals(conteudo, File(raiz, "Movies/novo vídeo.mp4").readBytes())
        assertEquals(1_700_000_000_000L, File(raiz, "Movies/novo vídeo.mp4").lastModified())
        assertFalse(File(raiz, "Movies").list()!!.any { it.endsWith(".parcial") })
        // mesmo nome de novo: recusa, não sobrescreve
        assertEquals(Mtp.NEGADO, pc.op(Mtp.OP_MANDAR_INFO, Mtp.ARMAZEM, movies, mandar = infoPara("novo vídeo.mp4", 1)).first)
        // pasta nova
        val p = pc.op(Mtp.OP_MANDAR_INFO, Mtp.ARMAZEM, movies, mandar = infoPara("Ferias", 0, pasta = true))
        assertEquals(Mtp.OK, p.first); assertTrue(File(raiz, "Movies/Ferias").isDirectory)
        // renomear (propriedade "nome do arquivo") e o handle continua valendo
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MUDAR_PROP, h, Mtp.P_ARQUIVO, mandar = Mtp.Escritor().texto("final.mp4").bytes()).first)
        assertEquals("final.mp4", pc.nome(h))
        // mover para a pasta nova
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MOVER, h, Mtp.ARMAZEM, p.second[2]).first)
        assertTrue(File(raiz, "Movies/Ferias/final.mp4").isFile)
        // apagar a pasta com tudo dentro
        assertEquals(Mtp.OK, pc.op(Mtp.OP_APAGAR, p.second[2]).first)
        assertFalse(File(raiz, "Movies/Ferias").exists())
        assertEquals(Mtp.HANDLE_INVALIDO, pc.op(Mtp.OP_INFO_OBJETO, h).first)
        // pasta padrão do topo não se apaga
        assertEquals(Mtp.NEGADO, pc.op(Mtp.OP_APAGAR, movies).first)
        pc.fechar()
    }

    @Test fun envioPorListaDePropriedades() {
        val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
        val d = pc.achar(Mtp.TODOS, "Download")
        val lista = Mtp.Escritor().u32(2)
            .u32(0).u16(Mtp.P_ARQUIVO).u16(Mtp.T_TEXTO).texto("lista.bin")
            .u32(0).u16(Mtp.P_PROTECAO).u16(Mtp.T_U16).u16(0).bytes()
        val r = pc.op(Mtp.OP_MANDAR_LISTA_PROPS, Mtp.ARMAZEM, d, Mtp.F_INDEFINIDO, 0, 10, mandar = lista)
        assertEquals(Mtp.OK, r.first)
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MANDAR_OBJETO, mandar = ByteArray(10) { 9 }).first)
        assertEquals(10, File(raiz, "Download/lista.bin").length())
        pc.fechar()
    }

    @Test fun nomesQueTentamSairDaPastaSaoRecusados() {
        val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
        val d = pc.achar(Mtp.TODOS, "Download")
        for (ruim in listOf("..", "../fora.txt", "a/b.txt", "a\\b.txt", "", ".x.parcial"))
            assertTrue(ruim, pc.op(Mtp.OP_MANDAR_INFO, Mtp.ARMAZEM, d, mandar = infoPara(ruim, 1)).first != Mtp.OK)
        // "Android" na raiz é reservado
        assertTrue(pc.op(Mtp.OP_MANDAR_INFO, Mtp.ARMAZEM, Mtp.TODOS, mandar = infoPara("android", 0, pasta = true)).first != Mtp.OK ||
            !File(raiz, "android").exists())
        assertEquals(Mtp.NEGADO, pc.op(Mtp.OP_MUDAR_PROP, pc.achar(d, "ola.txt"), Mtp.P_ARQUIVO, mandar = Mtp.Escritor().texto("../x").bytes()).first)
        assertFalse(File(raiz.parentFile, "fora.txt").exists())
        // tamanho combinado diferente do que chega: nada fica gravado
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MANDAR_INFO, Mtp.ARMAZEM, d, mandar = infoPara("curto.bin", 100)).first)
        try { pc.op(Mtp.OP_MANDAR_OBJETO, mandar = ByteArray(50)) } catch (_: Exception) {}
        Thread.sleep(200)
        assertFalse(File(raiz, "Download/curto.bin").exists())
        assertFalse(File(raiz, "Download").list()!!.any { it.endsWith(".parcial") && it != ".oculto.parcial" })
    }

    @Test fun linkSimbolicoParaForaNaoAparece() {
        val fora = Files.createTempDirectory("fora").toFile().apply { File(this, "segredo.txt").writeText("x") }
        try {
            Files.createSymbolicLink(File(raiz, "Download/atalho").toPath(), fora.toPath())
            val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
            val nomes = pc.handles(0).map { pc.op(Mtp.OP_INFO_OBJETO, it) }.filter { it.first == Mtp.OK }.map { Mtp.lerInfoObjeto(it.third).nome }
            assertFalse("segredo.txt" in nomes)
            pc.fechar()
        } finally { fora.deleteRecursively() }
    }

    @Test fun listaDePropriedadesDaPasta() {
        val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
        val d = pc.achar(Mtp.TODOS, "Download")
        val r = pc.op(Mtp.OP_LISTA_PROPS, d, 0, Mtp.TODOS, 0, 1)
        assertEquals(Mtp.OK, r.first)
        val l = Mtp.Leitor(r.third)
        assertEquals(Mtp.PROPS.size, l.u32())
        var nome = ""; var tamanho = -1L
        repeat(Mtp.PROPS.size) {
            l.u32(); val prop = l.u16(); val tipo = l.u16()
            when (prop) { Mtp.P_ARQUIVO -> nome = l.texto(); Mtp.P_TAMANHO -> tamanho = l.u64(); else -> l.pularValor(tipo) }
        }
        assertEquals("ola.txt", nome); assertEquals(File(raiz, "Download/ola.txt").length(), tamanho); assertEquals(0, l.resto)
        for (p in Mtp.PROPS.keys) assertNotNull(Mtp.descProp(p))
        assertNull(Mtp.descProp(0xDC99))
        pc.fechar()
    }

    @Test fun computadorNaoPermitidoNaoEntra() {
        deixa = false
        val pc = Pc()
        assertEquals(PtpIp.INICIO_RECUSADO, pc.entrar())
        pc.fechar()
    }

    @Test fun lixoNaoDerrubaOServidor() {
        Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { it.getOutputStream().write("GET / HTTP/1.1\r\n\r\n".toByteArray()) }
        Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { it.getOutputStream().write(Mtp.Escritor().u32(0x7FFFFFFF).u32(1).bytes()) }
        // conexão de eventos com número que não existe
        Socket(InetAddress.getLoopbackAddress(), ss.localPort).use {
            PtpIp.escrever(it.getOutputStream(), PtpIp.INICIO_EVENTO, Mtp.Escritor().u32(999).bytes())
            assertEquals(PtpIp.INICIO_RECUSADO, PtpIp.ler(it.getInputStream()).first)
        }
        val pc = Pc()
        assertEquals(PtpIp.INICIO_COMANDO_OK, pc.entrar())
        pc.fechar()
    }

    /** 15/11/2026: a conexão de eventos de uma sessão é uma só; um segundo aparelho não entra nela. Número sorteado. */
    /** Conexão que o servidor recusou é fechada na hora (leitura devolve -1); a aceita fica aberta esperando o aperto de mão. */
    private fun foiRecusada(s: Socket): Boolean {
        s.soTimeout = 700
        return try { s.getInputStream().read() == -1 } catch (_: java.net.SocketTimeoutException) { false } catch (_: java.io.IOException) { true }
    }

    @Test fun umSoIpNaoOcupaTodasAsConexoes() {
        val mudas = List(MtpServico.MAX_POR_IP) { Socket(InetAddress.getLoopbackAddress(), ss.localPort) }
        try {
            assertTrue(mudas.none { foiRecusada(it) })
            Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { assertTrue("a que passa do teto por IP é recusada", foiRecusada(it)) }
            mudas[0].close()
            // o servidor percebe a saída na thread da conexão: tenta por até 3 s
            var entrou = false
            val fim = System.currentTimeMillis() + 3000
            while (!entrou && System.currentTimeMillis() < fim) {
                Socket(InetAddress.getLoopbackAddress(), ss.localPort).use { entrou = !foiRecusada(it) }
            }
            assertTrue("liberou a vaga quando uma conexão saiu", entrou)
        } finally { mudas.forEach { try { it.close() } catch (_: Exception) {} } }
    }

    @Test fun eventosSoDoDonoDaSessao() {
        val pc = Pc()
        assertEquals(PtpIp.INICIO_COMANDO_OK, pc.entrar())
        assertTrue("número sorteado, não sequencial", pc.numero !in 1..3)
        Socket(InetAddress.getLoopbackAddress(), ss.localPort).use {
            PtpIp.escrever(it.getOutputStream(), PtpIp.INICIO_EVENTO, Mtp.Escritor().u32(pc.numero).bytes())
            assertEquals(PtpIp.INICIO_RECUSADO, PtpIp.ler(it.getInputStream()).first)
        }
        // Outro computador da casa continua entrando com a própria sessão.
        val outro = Pc()
        assertEquals(PtpIp.INICIO_COMANDO_OK, outro.entrar())
        assertTrue(outro.numero != pc.numero)
        outro.fechar(); pc.fechar()
    }

    /** Cabeçalho de dados/cancelar dizendo ~2 GB: recusa sem alocar (antes: OutOfMemoryError derrubava a HOME). */
    @Test fun corpoGrandeDemaisERecusadoSemAlocar() {
        val cab = PtpIp.lerCabecalho(Mtp.Escritor().u32(0x7FFFFFF0).u32(PtpIp.DADOS_COMECO).bytes().inputStream())
        val erro = runCatching { PtpIp.lerCorpo(ByteArray(0).inputStream(), cab) }.exceptionOrNull()
        assertTrue("esperava IOException, veio $erro", erro is java.io.IOException)
        val pequeno = PtpIp.lerCabecalho(Mtp.Escritor().u32(8 + 3).u32(PtpIp.CANCELAR).bytes().inputStream())
        assertArrayEquals(byteArrayOf(1, 2, 3), PtpIp.lerCorpo(byteArrayOf(1, 2, 3).inputStream(), pequeno))
    }

    /** Fica no ar para clientes reais. Ver o comentário da classe. */
    @Test fun servirParaClientesReais() {
        Assume.assumeTrue(System.getenv("ONETV_MTP_SERVIR") != null)
        servico.parar()
        subir(File(System.getenv("ONETV_MTP_RAIZ") ?: error("ONETV_MTP_RAIZ")).apply { mkdirs() }, PtpIp.PORTA)
        println("MTP/IP de teste em 127.0.0.1:${PtpIp.PORTA} (pasta $raiz)")
        Thread.sleep(Long.MAX_VALUE)
    }

    @Test fun escritaAdiantadaEntregaTudoNaOrdemMesmoComDiscoLento() {
        val dados = ByteArray(3 * 1024 * 1024 + 123).also { java.util.Random(7).nextBytes(it) }
        val lento = object : java.io.ByteArrayOutputStream() {
            override fun write(b: ByteArray, off: Int, len: Int) { Thread.sleep(2); super.write(b, off, len) }
        }
        EscritaAdiantada(lento, blocos = 3, tamanho = 64 * 1024).use { e ->
            var o = 0
            while (o < dados.size) { val n = minOf(100_000, dados.size - o); e.write(dados, o, n); o += n }   // pedaços desalinhados
        }
        assertTrue(dados.contentEquals(lento.toByteArray()))
    }

    @Test fun escritaAdiantadaRepassaOErroDoDiscoSemTravar() {
        val cheio = object : java.io.OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) { throw java.io.IOException("sem espaço") }
        }
        val e = EscritaAdiantada(cheio, blocos = 2, tamanho = 1024)
        val erro = try { repeat(100) { e.write(ByteArray(1024)) }; e.close(); null } catch (x: java.io.IOException) { x }
        assertEquals("sem espaço", erro?.message)
        try { e.close() } catch (_: java.io.IOException) {}   // fechar depois do erro não trava
    }

    // ---- rodada 3 de depuração (18/11/2026) ----

    /** A pasta sumiu entre o "vou mandar" e o arquivo: responde, lê os dados que já vinham e a conversa continua. */
    @Test fun pastaSumiuNoMeioNaoDerrubaAConexao() {
        val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
        val movies = pc.achar(Mtp.TODOS, "Movies")
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MANDAR_INFO, Mtp.ARMAZEM, movies, mandar = infoPara("a.bin", 3000)).first)
        File(raiz, "Movies").deleteRecursively()
        assertTrue(pc.op(Mtp.OP_MANDAR_OBJETO, mandar = ByteArray(3000)).first != Mtp.OK)
        assertEquals(Mtp.OK, pc.op(Mtp.OP_ARMAZENS).first)   // antes: "pacote 10 fora de hora" e a conexão caía
        pc.fechar()
    }

    /** Data antes de 1970 lançava depois do arquivo gravado: a resposta saía ERRO e o Windows tentava de novo. */
    @Test fun dataAntigaNaoViraErro() {
        val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
        val movies = pc.achar(Mtp.TODOS, "Movies")
        val info = Mtp.infoObjeto(Mtp.Objeto(0, 0, "velho.txt", false, 3, -86_400_000L, ByteArray(16)))
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MANDAR_INFO, Mtp.ARMAZEM, movies, mandar = info).first)
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MANDAR_OBJETO, mandar = byteArrayOf(1, 2, 3)).first)
        assertTrue(File(raiz, "Movies/velho.txt").isFile)
        pc.fechar()
    }

    /** ".parcial" de um envio interrompido há horas some no envio seguinte para a mesma pasta; o recente fica. */
    @Test fun parcialEsquecidoEApagado() {
        val velho = File(raiz, "Movies/.filme.mp4.123.parcial").apply { writeText("x"); setLastModified(System.currentTimeMillis() - 2 * 3_600_000L) }
        val novo = File(raiz, "Movies/.outro.mp4.456.parcial").apply { writeText("x") }
        val pc = Pc(); pc.entrar(); pc.op(Mtp.OP_ABRIR_SESSAO, 1)
        val movies = pc.achar(Mtp.TODOS, "Movies")
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MANDAR_INFO, Mtp.ARMAZEM, movies, mandar = infoPara("b.txt", 2)).first)
        assertEquals(Mtp.OK, pc.op(Mtp.OP_MANDAR_OBJETO, mandar = byteArrayOf(1, 2)).first)
        assertFalse(velho.exists()); assertTrue(novo.exists())
        pc.fechar()
    }

    /** O mesmo computador entrando de novo derruba a conexão antiga (ela morreu sem aviso e prenderia a vaga). */
    @Test(timeout = 10_000) fun mesmoComputadorDeNovoSoltaAConexaoAntiga() {
        val velho = Pc(); assertEquals(PtpIp.INICIO_COMANDO_OK, velho.entrar())
        val novo = Pc(); assertEquals(PtpIp.INICIO_COMANDO_OK, novo.entrar())
        assertEquals(-1, try { velho.e.read() } catch (_: java.io.IOException) { -1 })
        novo.op(Mtp.OP_ABRIR_SESSAO, 1)
        assertEquals(Mtp.OK, novo.op(Mtp.OP_ARMAZENS).first)
        velho.fechar(); novo.fechar()
    }

    /** Um erro que não é IOException no disco não pode matar a thread de gravação e travar quem escreve. */
    @Test(timeout = 5_000) fun escritaAdiantadaNaoTravaComErroInesperado() {
        val quebra = object : java.io.OutputStream() {
            override fun write(b: Int) {}
            override fun write(b: ByteArray, off: Int, len: Int) { throw IllegalStateException("bug") }
        }
        val e = EscritaAdiantada(quebra, blocos = 2, tamanho = 1024)
        val erro = try { repeat(100) { e.write(ByteArray(1024)) }; e.close(); null } catch (x: java.io.IOException) { x }
        assertTrue(erro?.cause is IllegalStateException)
    }
}
