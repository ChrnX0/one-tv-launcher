package br.paulo.tvlauncher.panel

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import br.paulo.tvlauncher.Config
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface

/**
 * Manda e responde os pacotes SSDP (Ssdp.kt) para a TV aparecer na tela "Rede" do Windows como "<nome> · painel".
 * Desde 18/11/2026 liga junto com o painel (PainelWeb), sem depender de chave nenhuma: o duplo clique ali abre o painel,
 * para quem não sabe o IP. A TV em "Este Computador" (aparelho MTP) entra no anúncio só com a unidade de rede ligada
 * ([mostrarMtp], chamado pelo AnuncioDav). O IP é conferido a cada minuto: mudou, anuncia de novo com o endereço novo.
 *
 * Porta 1900: o Chromecast embutido (mediashell) já escuta nela (medido na Sala em 14/11/2026). Com reuseAddress os dois
 * recebem as buscas; se o sistema não deixar dividir, seguimos só com os anúncios periódicos (NOTIFY), que o Windows
 * também ouve. Custo: uma thread parada em receive() e 3 pacotes a cada 10 min.
 */
object AnuncioUpnp {
    private const val TAG = "TvLauncher.Upnp"
    private const val REANUNCIO_MS = 10 * 60_000L
    /** De quanto em quanto tempo o IP é conferido (e quanto se espera a rede aparecer). */
    private const val CONFERE_IP_MS = 60_000L
    @Volatile private var thread: Thread? = null
    @Volatile private var escuta: MulticastSocket? = null
    /** A TV também em "Este Computador" (unidade de rede ligada). */
    @Volatile private var comMtp = false
    @Volatile private var contexto: Context? = null
    /** O MTP acabou de sair do anúncio: o próximo anúncio manda o "saí" dele (só uma vez). */
    @Volatile private var tirarMtp = false

    @Synchronized fun ligar(ctx: Context) {
        val app = ctx.applicationContext
        contexto = app
        if (thread != null) return
        val t = Thread({ rodar(app) }, "Upnp-anuncio").apply { isDaemon = true; priority = Thread.MIN_PRIORITY }
        thread = t
        t.start()
    }

    /**
     * Põe ou tira o aparelho MTP do anúncio (unidade de rede ligada/desligada). Recomeça o anúncio se o painel está no
     * ar; o anúncio novo manda o "saí" do MTP quando ele sai, para o Windows tirar a TV de "Este Computador".
     */
    @Synchronized fun mostrarMtp(sim: Boolean, ctx: Context? = null) {
        ctx?.let { contexto = it.applicationContext }
        if (comMtp == sim) return
        comMtp = sim
        tirarMtp = !sim
        val app = contexto ?: return
        if (thread != null) desligar()
        if (PainelWeb.ligado()) ligar(app)
    }

    @Synchronized fun desligar() {
        val t = thread ?: return
        thread = null
        t.interrupt()
        try { escuta?.close() } catch (_: Exception) {}
    }

    private fun rodar(app: Context) {
        val eu = Thread.currentThread()
        // O painel pode ligar antes do Wi-Fi (TV acordando): espera a rede em vez de desistir de anunciar.
        var ipAchado = PainelWeb.ipsDaRede().minOrNull()
        while (ipAchado == null) {
            try { Thread.sleep(CONFERE_IP_MS) } catch (_: InterruptedException) { synchronized(this) { if (thread === eu) thread = null }; return }
            if (thread !== eu) return
            ipAchado = PainelWeb.ipsDaRede().minOrNull()
        }
        val ip: String = ipAchado
        var trocouIp = false
        // Até dois aparelhos: o atalho para o painel (Basic, sempre) e a TV como dispositivo portátil (MTP pela rede,
        // só com a unidade de rede ligada).
        val id = PainelVizinhos.idDaTv(app)
        val base = "http://$ip:${Config.PAINEL_PORTA}"
        val mtp = Triple(Ssdp.uuidMtp(id), base + Ssdp.CAMINHO_MTP, Ssdp.TIPO_MTP)
        val comMtpAgora = comMtp
        val aparelhos = listOfNotNull(
            Triple(Ssdp.uuid(id), base + Ssdp.CAMINHO_DESCRICAO, Ssdp.TIPO),
            mtp.takeIf { comMtpAgora },
        )
        fun anuncios(vivo: Boolean) = aparelhos.flatMap { (u, l, t) -> Ssdp.notify(u, l, vivo, t) }
        val grupo = InetAddress.getByName(Ssdp.GRUPO)
        // Sem socket: solta a vaga (thread = null) para o próximo ligar() tentar de novo; antes o anúncio nunca mais voltava
        // (debug 20/11/2026), o que ficou mais provável com a volta a cada troca de IP.
        val envio = try { DatagramSocket(InetSocketAddress(InetAddress.getByName(ip), 0)) } catch (e: Exception) {
            Log.w(TAG, "sem socket: $e"); synchronized(this) { if (thread === eu) thread = null }; return
        }
        fun mandar(textos: List<String>, para: InetAddress, porta: Int) = textos.forEach {
            val b = it.toByteArray(Charsets.US_ASCII)
            try { envio.send(DatagramPacket(b, b.size, para, porta)) } catch (e: Exception) { Log.w(TAG, "envio: $e") }
        }
        // Wi-Fi descarta multicast que chega sem esta trava (a placa economiza energia). No cabo, não faz diferença.
        // Local de cada thread (18/11/2026): num desligar+ligar rápido a thread velha soltava a trava da nova.
        val trava = try { (app.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.createMulticastLock("onetv-ssdp")?.apply { setReferenceCounted(false); acquire() }
        } catch (e: Exception) { Log.w(TAG, "trava multicast: $e"); null }
        // Só responde a buscas da rede de casa (18/11/2026): a resposta vai para o endereço de origem, que pode ser forjado.
        val minha = InetAddress.getByName(ip)
        val prefixo = try { NetworkInterface.getByInetAddress(minha)?.interfaceAddresses?.firstOrNull { it.address == minha }?.networkPrefixLength?.toInt() } catch (_: Exception) { null } ?: 24
        val s = try {
            MulticastSocket(null as java.net.SocketAddress?).apply {
                reuseAddress = true
                bind(InetSocketAddress(Ssdp.PORTA))
                joinGroup(InetSocketAddress(grupo, Ssdp.PORTA), NetworkInterface.getByInetAddress(InetAddress.getByName(ip)))
                soTimeout = CONFERE_IP_MS.toInt()
            }
        } catch (e: Exception) { Log.i(TAG, "porta 1900 ocupada, só anúncios: $e"); null }
        escuta = s
        try {
            // O servidor MTP acabou de subir (processo novo, atualização do launcher): o Windows ainda acha que a conexão
            // antiga vale. "Saí" + "estou aqui" do aparelho MTP faz ele soltar e reconectar sozinho (ver Ssdp.avisoDoAparelho).
            // Com alguém já conectado (este anúncio religou sem o servidor cair) não mexe: derrubaria uma cópia no meio.
            val (uMtp, lMtp, tMtp) = mtp
            if (comMtpAgora) {
                if (!MtpNaRede.temSessao()) {
                    mandar(listOf(Ssdp.avisoDoAparelho(uMtp, lMtp, false)), grupo, Ssdp.PORTA)
                    Thread.sleep(Ssdp.PAUSA_RECONEXAO_MS)
                }
                mandar(listOf(Ssdp.avisoDoAparelho(uMtp, lMtp, true)), grupo, Ssdp.PORTA)
                Thread.sleep(300)
            } else if (tirarMtp) {
                // Unidade de rede acabou de ser desligada: "saí" do aparelho MTP (o Windows tira a TV de "Este Computador").
                tirarMtp = false
                mandar(Ssdp.notify(uMtp, lMtp, false, tMtp), grupo, Ssdp.PORTA)
            }
            mandar(anuncios(true), grupo, Ssdp.PORTA)
            var ultimo = System.currentTimeMillis()
            var conferiuIp = ultimo
            val buf = ByteArray(2048)
            // Uma resposta por origem a cada segundo: uma busca de ~100 B rendia 6 respostas de ~250 B, e com a origem
            // forjada a TV virava refletor contra outro aparelho da casa. O Windows repete a busca: nada se perde.
            val respondido = HashMap<String, Long>()
            while (thread === eu) {
                if (s == null) {
                    Thread.sleep(CONFERE_IP_MS)
                } else {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                        val texto = String(p.data, 0, p.length, Charsets.US_ASCII)
                        // Só buscas IPv4: o socket de envio está preso ao IP v4 da TV e uma busca vinda por IPv6
                        // (o Windows também procura por ff02::c) dava EAFNOSUPPORT no sendto (visto na Sala, 14/11/2026).
                        val daCasa = p.address is java.net.Inet4Address && p.port != 0 &&
                            ChecagensPainel.mesmaRede(minha.address, p.address.address, prefixo)
                        val r = if (daCasa) aparelhos.flatMap { (u, l, t) -> Ssdp.respostas(texto, u, l, t) } else emptyList()
                        val chave = p.address.hostAddress + ":" + p.port
                        val agora = System.currentTimeMillis()
                        if (r.isNotEmpty() && agora - (respondido[chave] ?: 0L) >= 1000) {
                            if (respondido.size > 256) respondido.clear()
                            respondido[chave] = agora
                            mandar(r, p.address, p.port)
                        }
                    } catch (_: java.net.SocketTimeoutException) {}
                }
                if (System.currentTimeMillis() - ultimo >= REANUNCIO_MS) { mandar(anuncios(true), grupo, Ssdp.PORTA); ultimo = System.currentTimeMillis() }
                if (System.currentTimeMillis() - conferiuIp >= CONFERE_IP_MS) {
                    conferiuIp = System.currentTimeMillis()
                    // O roteador deu outro IP à TV (ou a rede caiu): o endereço anunciado deixou de valer.
                    if (PainelWeb.ipsDaRede().minOrNull() != ip) { trocouIp = true; break }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Exception) {
            if (thread === eu) Log.w(TAG, "parou: $e")
        } finally {
            // "Saí" só se ninguém tomou o lugar: num desligar+ligar rápido o adeus da thread velha chegava depois do olá
            // da nova e o Windows soltava a TV.
            if (thread == null || thread === eu) mandar(anuncios(false), grupo, Ssdp.PORTA)
            try { s?.close() } catch (_: Exception) {}
            envio.close()
            try { trava?.release() } catch (_: Exception) {}
            synchronized(this) { if (thread === eu) thread = null }
        }
        // Recomeça com o IP novo (o "saí" acima foi com o endereço velho, que o Windows ainda tem guardado).
        if (trocouIp && PainelWeb.ligado()) { Log.i(TAG, "IP mudou: anunciando de novo"); ligar(app) }
    }
}
