package br.paulo.tvlauncher

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Manda e responde os pacotes SSDP (Ssdp.kt) para a TV aparecer na tela "Rede" do Windows como "<nome> · arquivos".
 * Só com a unidade de rede ligada (liga e desliga junto com o AnuncioDav).
 *
 * Porta 1900: o Chromecast embutido (mediashell) já escuta nela (medido na Sala em 14/11/2026). Com reuseAddress os dois
 * recebem as buscas; se o sistema não deixar dividir, seguimos só com os anúncios periódicos (NOTIFY), que o Windows
 * também ouve. Custo: uma thread parada em receive() e 3 pacotes a cada 10 min.
 */
object AnuncioUpnp {
    private const val TAG = "TvLauncher.Upnp"
    private const val REANUNCIO_MS = 10 * 60_000L
    @Volatile private var thread: Thread? = null
    @Volatile private var escuta: MulticastSocket? = null
    private var trava: WifiManager.MulticastLock? = null

    @Synchronized fun ligar(ctx: Context) {
        if (thread != null) return
        val app = ctx.applicationContext
        val t = Thread({ rodar(app) }, "Upnp-anuncio").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        thread = t
        t.start()
    }

    @Synchronized fun desligar() {
        val t = thread ?: return
        thread = null
        t.interrupt()
        try { escuta?.close() } catch (_: Exception) {}
    }

    private fun rodar(app: Context) {
        val eu = Thread.currentThread()
        val ip = PainelWeb.ipsDaRede().minOrNull() ?: run { synchronized(this) { if (thread === eu) thread = null }; return }
        val uuid = Ssdp.uuid(PainelVizinhos.idDaTv(app))
        val local = "http://$ip:${Config.PAINEL_PORTA}${Ssdp.CAMINHO_DESCRICAO}"
        val grupo = InetAddress.getByName(Ssdp.GRUPO)
        val envio = try { DatagramSocket(InetSocketAddress(InetAddress.getByName(ip), 0)) } catch (e: Exception) { Log.w(TAG, "sem socket: $e"); return }
        fun mandar(textos: List<String>, para: InetAddress, porta: Int) = textos.forEach {
            val b = it.toByteArray(Charsets.US_ASCII)
            try { envio.send(DatagramPacket(b, b.size, para, porta)) } catch (e: Exception) { Log.w(TAG, "envio: $e") }
        }
        // Wi-Fi descarta multicast que chega sem esta trava (a placa economiza energia). No cabo, não faz diferença.
        try {
            trava = (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock("onetv-ssdp")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { Log.w(TAG, "trava multicast: $e") }
        val s = try {
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(Ssdp.PORTA))
                joinGroup(InetSocketAddress(grupo, Ssdp.PORTA), NetworkInterface.getByInetAddress(InetAddress.getByName(ip)))
                soTimeout = REANUNCIO_MS.toInt()
            }
        } catch (e: Exception) { Log.i(TAG, "porta 1900 ocupada, só anúncios: $e"); null }
        escuta = s
        try {
            mandar(Ssdp.notify(uuid, local, true), grupo, Ssdp.PORTA)
            var ultimo = System.currentTimeMillis()
            val buf = ByteArray(2048)
            while (thread === eu) {
                if (s == null) {
                    Thread.sleep(REANUNCIO_MS)
                } else {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                        val texto = String(p.data, 0, p.length, Charsets.US_ASCII)
                        // Só buscas IPv4: o socket de envio está preso ao IP v4 da TV e uma busca vinda por IPv6
                        // (o Windows também procura por ff02::c) dava EAFNOSUPPORT no sendto (visto na Sala, 14/11/2026).
                        val r = if (p.address is java.net.Inet4Address) Ssdp.respostas(texto, uuid, local) else emptyList()
                        if (r.isNotEmpty()) mandar(r, p.address, p.port)
                    } catch (_: java.net.SocketTimeoutException) {}
                }
                if (System.currentTimeMillis() - ultimo >= REANUNCIO_MS) { mandar(Ssdp.notify(uuid, local, true), grupo, Ssdp.PORTA); ultimo = System.currentTimeMillis() }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (thread === eu) Log.w(TAG, "parou: $e")
        } finally {
            mandar(Ssdp.notify(uuid, local, false), grupo, Ssdp.PORTA)
            try { s?.close() } catch (_: Exception) {}
            envio.close()
            try { trava?.release() } catch (_: Exception) {}
            synchronized(this) { if (thread === eu) thread = null }
        }
    }
}
