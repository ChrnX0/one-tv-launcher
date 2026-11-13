package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Acordar o computador pela TV: Wake-on-LAN (10/11/2026, ideia P). O tv-remote usa WOL para ligar a TV; aqui é o
 * contrário: a TV manda o "pacote mágico" para o PC (o PC de build), que liga e pode compilar e instalar a versão nova do
 * launcher sozinho (proposta pendente da atualização automática).
 *
 * O pacote mágico: 6 bytes 0xFF seguidos do endereço MAC da placa de rede repetido 16 vezes (102 bytes), por UDP para o
 * endereço de difusão da rede, porta 9. O MAC fica nas preferências da TV ([CHAVE_MAC]), nunca no código nem no git.
 *
 * Não verificado: se o PC de build está com WOL ligado na BIOS e na placa. WOL costuma funcionar só pelo CABO; pelo Wi-Fi, a
 * maioria das placas não acorda.
 */
object AcordarPc {
    private const val TAG = "TvLauncher.AcordarPc"
    const val CHAVE_MAC = "wol_mac"
    const val PORTA = 9

    /** "AA:BB:CC:DD:EE:FF" (ou com "-", ou sem separador) -> 6 bytes; null se inválido. Puro. */
    fun mac(texto: String): ByteArray? {
        val limpo = texto.trim().replace(":", "").replace("-", "").replace(".", "")
        if (limpo.length != 12 || !limpo.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
        return ByteArray(6) { limpo.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** O pacote mágico de 102 bytes. Puro. */
    fun pacoteMagico(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        return ByteArray(102) { i -> if (i < 6) 0xFF.toByte() else mac[(i - 6) % 6] }
    }

    fun macGuardado(ctx: Context): String = Extras.texto(ctx, CHAVE_MAC)
    fun guardarMac(ctx: Context, texto: String): Boolean {
        if (mac(texto) == null) return false
        Extras.definirTexto(ctx, CHAVE_MAC, texto.trim().uppercase()); return true
    }

    /** Manda o pacote (3 vezes, para o caso de perder um). Fora da thread da tela. Devolve se conseguiu enviar. */
    fun acordar(ctx: Context): Boolean {
        val m = mac(macGuardado(ctx)) ?: return false
        val dados = pacoteMagico(m)
        return try {
            DatagramSocket().use { s ->
                s.broadcast = true
                val destinos = listOfNotNull(InetAddress.getByName("255.255.255.255"), difusaoDaRede(ctx))
                repeat(3) { destinos.forEach { d -> s.send(DatagramPacket(dados, dados.size, d, PORTA)) } }
            }
            Log.i(TAG, "pacote mágico enviado"); true
        } catch (e: Exception) { Log.w(TAG, "falhou: $e"); false }
    }

    /** Endereço de difusão da rede Wi-Fi (ex.: 192.168.1.255), pela máscara do link atual. */
    private fun difusaoDaRede(ctx: Context): InetAddress? = try {
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        val wifi = cm.allNetworks.firstOrNull {
            cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
        }
        val la = cm.getLinkProperties(wifi)?.linkAddresses?.firstOrNull { it.address is java.net.Inet4Address }
        la?.let { InetAddress.getByAddress(difusao(it.address.address, it.prefixLength)) }
    } catch (e: Exception) { null }

    /** Endereço IPv4 + tamanho do prefixo -> endereço de difusão. Puro. */
    fun difusao(ip: ByteArray, prefixo: Int): ByteArray {
        val v = ((ip[0].toInt() and 0xFF) shl 24) or ((ip[1].toInt() and 0xFF) shl 16) or ((ip[2].toInt() and 0xFF) shl 8) or (ip[3].toInt() and 0xFF)
        val mascara = if (prefixo <= 0) 0 else (-1 shl (32 - prefixo.coerceAtMost(32)))
        val b = v or mascara.inv()
        return byteArrayOf((b ushr 24).toByte(), (b ushr 16).toByte(), (b ushr 8).toByte(), b.toByte())
    }
}
