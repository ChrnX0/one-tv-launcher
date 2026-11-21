package br.paulo.tvlauncher

import br.paulo.tvlauncher.panel.Mtp
import br.paulo.tvlauncher.panel.MtpArmazem
import br.paulo.tvlauncher.panel.MtpLoja
import br.paulo.tvlauncher.panel.MtpServico
import br.paulo.tvlauncher.panel.MtpSessao
import br.paulo.tvlauncher.panel.PainelArquivos
import br.paulo.tvlauncher.panel.PtpIp
import br.paulo.tvlauncher.panel.Ssdp
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.ServerSocket
import org.junit.Assume
import org.junit.Test

/**
 * Bancada para o Windows de verdade (16/11/2026): o servidor MTP + o anúncio UPnP rodando num PC, sem TV, para
 * descobrir o que faz o Windows RECONECTAR depois que o servidor cai e volta (na TV ele ficava com a pasta vazia
 * até alguém reiniciar o dispositivo no Gerenciador de Dispositivos).
 *
 * Não roda nos testes normais: só com ONETV_BANCADA_IP=<IPv4 do PC na rede>. Comandos: uma linha por vez no arquivo
 * ONETV_BANCADA_PASTA/cmd.txt (o teste lê e apaga); o que acontece vai para ONETV_BANCADA_PASTA/log.txt.
 *   mtp_parar | mtp_subir | byebye | alive | bye1 | alive1 | uuid <texto> | responder sim|nao | validade <n> | sair
 *   armazem <nome>|<volume>|<0 ou 1 = de tirar>   (um armazenamento a mais, avisado ao Windows na hora)
 *   tirar <número do armazenamento em hexa>
 * ONETV_BANCADA_ARMAZENS="nome|volume|0;nome|volume|1" já sobe com esses armazenamentos a mais.
 */
class MtpBancadaWindowsTest {
    private companion object { const val NOME = "Bancada Sofaly" }

    @Test fun bancada() {
        val ip = System.getenv("ONETV_BANCADA_IP")
        Assume.assumeTrue(ip != null)
        val pasta = File(System.getenv("ONETV_BANCADA_PASTA") ?: error("ONETV_BANCADA_PASTA")).apply { mkdirs() }
        val log = File(pasta, "log.txt")
        val cmd = File(pasta, "cmd.txt")
        fun anotar(t: String) = synchronized(log) { log.appendText("${java.time.LocalTime.now().withNano(0)} $t\n") }

        val raiz = File(pasta, "arquivos").apply { mkdirs() }
        File(raiz, "Download").mkdir(); File(raiz, "Movies").mkdir()
        File(raiz, "Download/ola.txt").writeText("bancada")
        PainelArquivos.raizTeste = raiz

        val portaHttp = 18080
        var semente = "bancada"
        fun uuid() = Ssdp.uuidMtp(semente)
        val local = "http://$ip:$portaHttp${Ssdp.CAMINHO_MTP}"
        var responder = true
        var validade = (System.getenv("ONETV_BANCADA_VALIDADE") ?: "1800").toInt()

        // A descrição do aparelho (o que o LOCATION do anúncio aponta): um HTTP mínimo, um pedido por conexão.
        val http = ServerSocket(portaHttp, 8, InetAddress.getByName(ip))
        Thread {
            while (true) {
                val c = try { http.accept() } catch (_: Exception) { break }
                try {
                    c.soTimeout = 5000
                    val linha = c.getInputStream().bufferedReader().readLine() ?: ""
                    anotar("http $linha de ${c.inetAddress.hostAddress}")
                    val caminho = linha.split(" ").getOrElse(1) { "" }
                    val corpo = when (caminho) {
                        Ssdp.CAMINHO_MTP -> Ssdp.descricaoMtp(NOME, uuid(), "http://$ip:$portaHttp/", "bancada-1")
                        Ssdp.CAMINHO_MTP_SERVICO -> Ssdp.SERVICO_MTP
                        else -> null
                    }
                    val icone = Ssdp.ARQUIVOS_ICONE.keys.firstOrNull { Ssdp.CAMINHO_ICONES + it == caminho }?.let { File("src/main/assets/upnp/$it").readBytes() }
                    val b = icone ?: (corpo ?: "nada").toByteArray()
                    c.getOutputStream().write(("HTTP/1.1 ${if (corpo == null && icone == null) "404 Not Found" else "200 OK"}\r\nContent-Type: ${if (icone != null) "image/png" else "text/xml; charset=utf-8"}\r\n" +
                        "Content-Length: ${b.size}\r\nConnection: close\r\n\r\n").toByteArray() + b)
                    c.getOutputStream().flush()
                } catch (_: Exception) {} finally { try { c.close() } catch (_: Exception) {} }
            }
        }.apply { isDaemon = true }.start()

        // SSDP: anúncios e respostas às buscas.
        val grupo = InetAddress.getByName(Ssdp.GRUPO)
        val envio = DatagramSocket(InetSocketAddress(InetAddress.getByName(ip), 0))
        fun mandar(textos: List<String>, para: InetAddress, porta: Int) = textos.forEach {
            val b = it.toByteArray(Charsets.US_ASCII); envio.send(DatagramPacket(b, b.size, para, porta))
        }
        fun anunciar(vivo: Boolean) {
            repeat(2) { mandar(Ssdp.notify(uuid(), local, vivo, Ssdp.TIPO_MTP, validade), grupo, Ssdp.PORTA) }
            anotar(if (vivo) "alive enviado (${uuid()})" else "byebye enviado (${uuid()})")
        }
        val escuta = try {
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(Ssdp.PORTA))
                joinGroup(InetSocketAddress(grupo, Ssdp.PORTA), NetworkInterface.getByInetAddress(InetAddress.getByName(ip)))
            }
        } catch (e: Exception) { anotar("sem escuta na 1900: $e"); null }
        if (escuta != null) Thread {
            val buf = ByteArray(2048)
            while (true) {
                val p = DatagramPacket(buf, buf.size)
                try { escuta.receive(p) } catch (_: Exception) { break }
                if (!responder || p.address !is java.net.Inet4Address) continue
                val r = Ssdp.respostas(String(p.data, 0, p.length, Charsets.US_ASCII), uuid(), local, Ssdp.TIPO_MTP, validade)
                if (r.isNotEmpty()) { mandar(r, p.address, p.port); anotar("busca respondida a ${p.address.hostAddress} (${r.size})") }
            }
        }.apply { isDaemon = true }.start()

        // O servidor MTP, que sobe e cai a pedido.
        val ap = Mtp.Aparelho(NOME, "Bancada", "1", "bancada-1", ByteArray(16) { (it + 40).toByte() },
            File("src/main/assets/upnp/sofaly.ico").takeIf { it.isFile }?.readBytes())
        var servico: MtpServico? = null
        val armazens = java.util.concurrent.CopyOnWriteArrayList(listOf(MtpArmazem.interno()))
        var pendrives = 0
        fun novoArmazem(texto: String): MtpArmazem {
            val (nome, volume, tirar) = (texto.split("|") + listOf("", "", "")).take(3)
            val n = ++pendrives
            val dir = File(pasta, "armazem-$n").apply { mkdirs() }
            File(dir, "Pasta $n").mkdir(); File(dir, "Pasta $n/dentro.txt").writeText("armazem $n"); File(dir, "solto-$n.txt").writeText("x")
            return MtpArmazem(MtpArmazem.idDoPendrive(n), nome.ifEmpty { "Pendrive $n" }, volume, tirar == "1") { dir }.also { armazens.add(it) }
        }
        System.getenv("ONETV_BANCADA_ARMAZENS")?.split(";")?.filter { it.isNotBlank() }?.forEach { novoArmazem(it) }
        fun subir() {
            if (servico != null) return
            val ss = ServerSocket(PtpIp.PORTA, 16, InetAddress.getByName(ip))
            val s = MtpServico(ss, ap, MtpLoja({ armazens.toList() }) {}, { true }, { _, _, _ -> true }, { anotar("mtp $it") })
            servico = s
            Thread { s.rodar() }.apply { isDaemon = true }.start()
            anotar("mtp no ar em $ip:${PtpIp.PORTA}")
        }
        fun parar() { servico?.parar(); servico = null; anotar("mtp parado") }

        File(raiz, "Download/sub").mkdir(); File(raiz, "Download/sub/dentro.txt").writeText("x")
        MtpSessao.rastro = { anotar(it) }
        subir(); anunciar(true)
        anotar("bancada pronta")
        while (true) {
            Thread.sleep(500)
            if (!cmd.exists()) continue
            val linha = try { cmd.readText().trim() } catch (_: Exception) { continue }
            cmd.delete()
            anotar("> $linha")
            val partes = linha.split(" ", limit = 2)
            try {
                when (partes[0]) {
                    "mtp_parar" -> parar()
                    "mtp_subir" -> subir()
                    "byebye" -> anunciar(false)
                    "alive" -> anunciar(true)
                    "uuid" -> { semente = partes.getOrElse(1) { "bancada" }; anotar("uuid agora ${uuid()}") }
                    "responder" -> { responder = partes.getOrNull(1) == "sim"; anotar("responder=$responder") }
                    "bye1" -> { mandar(listOf(Ssdp.avisoDoAparelho(uuid(), local, false)), grupo, Ssdp.PORTA); anotar("bye1 enviado") }
                    "alive1" -> { mandar(listOf(Ssdp.avisoDoAparelho(uuid(), local, true, validade)), grupo, Ssdp.PORTA); anotar("alive1 enviado") }
                    "armazem" -> { val a = novoArmazem(partes.getOrElse(1) { "" }); servico?.avisarArmazem(a.id, true); anotar("armazem %X = ${a.nome} | ${a.volume}".format(a.id)) }
                    "tirar" -> { val id = partes[1].toInt(16); armazens.removeIf { it.id == id }; servico?.avisarArmazem(id, false) }
                    "validade" -> { validade = partes[1].toInt(); anotar("validade=$validade") }
                    "sair" -> { anunciar(false); parar(); http.close(); PainelArquivos.raizTeste = null; return }
                    else -> anotar("comando desconhecido")
                }
            } catch (e: Exception) { anotar("erro: $e") }
        }
    }
}
