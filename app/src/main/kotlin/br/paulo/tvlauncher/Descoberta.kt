package br.paulo.tvlauncher

import android.content.Context
import android.net.ConnectivityManager
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Achar sozinho os computadores e NAS com pasta compartilhada (10/11/2026), para a pessoa não precisar saber o IP.
 *
 * Dois caminhos, porque cada sistema anuncia de um jeito:
 *  - [netbios]: o Windows responde ao "quem é você?" do NetBIOS (UDP 137) quando o compartilhamento de arquivos está
 *    ligado. Pergunta a cada endereço da rede da casa (/24: 254 pacotes pequenos) e fica com quem diz ser servidor de
 *    arquivos (o nome com sufixo 0x20).
 *  - [mdns]: Mac, Linux (Samba com Avahi) e os NAS Synology/QNAP anunciam "_smb._tcp" por mDNS (NsdManager do Android).
 *
 * A leitura do pacote NetBIOS é pura, com teste JVM (DescobertaTest).
 */
object Descoberta {
    data class Maquina(val nome: String, val ip: String)

    /** Os dois caminhos juntos, sem repetir IP. Leva ~3 s. */
    fun pastas(ctx: Context): List<Maquina> {
        var dns: List<Maquina> = emptyList()
        val t = Thread { dns = runCatching { mdns(ctx) }.getOrDefault(emptyList()) }.apply { start() }
        val nb = runCatching { netbios(ctx) }.getOrDefault(emptyList())
        t.join(5000)
        return (nb + dns.filter { d -> nb.none { it.ip == d.ip } }).sortedBy { it.nome.lowercase() }
    }

    // ---------------------------------------------------------------- NetBIOS

    /** Pedido "node status" para o nome "*" (todos os nomes da máquina). */
    fun pedidoNodeStatus(id: Int): ByteArray {
        val p = ByteArray(50)
        p[0] = (id shr 8).toByte(); p[1] = id.toByte()
        p[5] = 1                                            // 1 pergunta
        p[12] = 0x20                                        // nome codificado: 32 letras
        val nome = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (i in 0 until 16) {
            val b = nome[i].toInt() and 0xFF
            p[13 + 2 * i] = ('A'.code + (b shr 4)).toByte()
            p[14 + 2 * i] = ('A'.code + (b and 0x0F)).toByte()
        }
        p[45] = 0                                           // fim do nome
        p[46] = 0; p[47] = 0x21                             // tipo NBSTAT
        p[48] = 0; p[49] = 1                                // classe IN
        return p
    }

    /**
     * Nome da máquina se ela anuncia o serviço de arquivos (sufixo 0x20, nome único), senão null.
     * Formato: cabeçalho (12) + nome (34) + tipo/classe/TTL/tamanho (10) + quantos nomes (1) + 18 bytes por nome.
     */
    fun lerNodeStatus(r: ByteArray, tamanho: Int = r.size): String? {
        if (tamanho < 57) return null
        if ((r[2].toInt() and 0x80) == 0) return null       // não é resposta
        var i = 12
        if ((r[i].toInt() and 0xC0) == 0xC0) i += 2 else { while (i < tamanho && r[i].toInt() != 0) i += (r[i].toInt() and 0xFF) + 1; i++ }
        if (i + 11 > tamanho) return null
        if (r[i].toInt() != 0 || r[i + 1].toInt() != 0x21) return null
        i += 10
        val n = r[i].toInt() and 0xFF
        i++
        for (k in 0 until n) {
            val o = i + k * 18
            if (o + 18 > tamanho) return null
            val sufixo = r[o + 15].toInt() and 0xFF
            val grupo = (r[o + 16].toInt() and 0x80) != 0
            if (sufixo == 0x20 && !grupo) {
                val nome = String(r, o, 15, Charsets.ISO_8859_1).trim()
                return nome.takeIf { s -> s.isNotEmpty() && s.all { it.isLetterOrDigit() || it in "-_." } }
            }
        }
        return null
    }

    /** O IPv4 da TV e o tamanho da rede (ex.: 192.168.1.50/24). */
    fun redeLocal(ctx: Context): Pair<Inet4Address, Int>? {
        val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val lp = cm.getLinkProperties(cm.activeNetwork) ?: return null
        val a = lp.linkAddresses.firstOrNull { it.address is Inet4Address && !it.address.isLoopbackAddress } ?: return null
        return (a.address as Inet4Address) to a.prefixLength
    }

    /** Os vizinhos de uma rede /24 (ou da /24 da TV, se a rede for maior), sem a própria TV. */
    fun vizinhos(eu: Inet4Address): List<InetAddress> {
        val b = eu.address
        return (1..254).filter { it != (b[3].toInt() and 0xFF) }.map { InetAddress.getByAddress(byteArrayOf(b[0], b[1], b[2], it.toByte())) }
    }

    fun netbios(ctx: Context, esperaMs: Int = 2500): List<Maquina> {
        val (eu, _) = redeLocal(ctx) ?: return emptyList()
        val achados = LinkedHashMap<String, Maquina>()
        DatagramSocket().use { s ->
            s.soTimeout = 300
            val pedido = pedidoNodeStatus(0x4C54)
            for (ip in vizinhos(eu)) runCatching { s.send(DatagramPacket(pedido, pedido.size, ip, 137)) }
            val fim = System.currentTimeMillis() + esperaMs
            val buf = ByteArray(1024)
            while (System.currentTimeMillis() < fim) {
                val p = DatagramPacket(buf, buf.size)
                try { s.receive(p) } catch (_: SocketTimeoutException) { continue }
                val ip = p.address?.hostAddress ?: continue
                lerNodeStatus(p.data, p.length)?.let { achados[ip] = Maquina(it, ip) }
            }
        }
        return achados.values.toList()
    }

    // ---------------------------------------------------------------- mDNS

    fun mdns(ctx: Context, esperaMs: Long = 2500): List<Maquina> {
        val nsd = ctx.getSystemService(Context.NSD_SERVICE) as NsdManager
        val nomes = LinkedHashSet<NsdServiceInfo>()
        val ouvinte = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(s: NsdServiceInfo) { synchronized(nomes) { if (nomes.size < 12) nomes += s } }
            override fun onServiceLost(s: NsdServiceInfo) {}
            override fun onDiscoveryStarted(t: String) {}
            override fun onDiscoveryStopped(t: String) {}
            override fun onStartDiscoveryFailed(t: String, e: Int) {}
            override fun onStopDiscoveryFailed(t: String, e: Int) {}
        }
        nsd.discoverServices("_smb._tcp", NsdManager.PROTOCOL_DNS_SD, ouvinte)
        try { Thread.sleep(esperaMs) } finally { runCatching { nsd.stopServiceDiscovery(ouvinte) } }
        val lista = synchronized(nomes) { nomes.toList() }
        // Resolver um de cada vez: o Android antigo recusa dois "resolve" ao mesmo tempo.
        return lista.mapNotNull { s ->
            val trava = CountDownLatch(1)
            var achado: Maquina? = null
            @Suppress("DEPRECATION")
            nsd.resolveService(s, object : NsdManager.ResolveListener {
                override fun onResolveFailed(i: NsdServiceInfo, e: Int) { trava.countDown() }
                override fun onServiceResolved(i: NsdServiceInfo) {
                    @Suppress("DEPRECATION")
                    val ip = (i.host as? Inet4Address)?.hostAddress
                    if (ip != null) achado = Maquina(i.serviceName.take(40), ip)
                    trava.countDown()
                }
            })
            trava.await(1500, TimeUnit.MILLISECONDS)
            achado
        }
    }
}
