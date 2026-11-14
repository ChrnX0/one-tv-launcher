package br.paulo.onetv.celular

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * O que o app lê do QR da TV e guarda de cada TV (sem Android: testado na JVM em PareamentoTest).
 *
 * O QR de "Entrar pelo celular" (EntrarPeloQrActivity do launcher) é um endereço do painel:
 *   http://192.168.1.50:8080/#qr=<código de 48>[&ir=<aba>][&fp=<SHA-256 do certificado https, 64 hex>][&ts=<nome.ts.net>]
 * e o da primeira configuração (TV sem PIN): http://<ip>:8080/#primeira=<código de 48>.
 *
 * Fixação do certificado: o "fp" é a impressão do certificado https da TV (porta 8444), lida no QR, que só quem está
 * na frente da TV vê. Daí em diante o app só aceita ESSE certificado naquele endereço; qualquer outro (até um
 * válido de outra CA) é recusado. Sem "fp" (TV antiga), o app usa o http da rede de casa, como o navegador.
 */
object Pareamento {
    const val PORTA_HTTP = 8080
    const val PORTA_HTTPS = 8444
    const val PORTA_TAILSCALE = 8443
    const val MAX_TVS = 8

    /** Uma TV salva. [fp] vazio = sem https (TV antiga ou sem certificado ainda). [ts] = nome no Tailscale, se houver. */
    data class Tv(val nome: String, val ip: String, val fp: String = "", val ts: String = "", val id: String = "")

    /** O que veio no QR: a TV e o código de uso único para entrar sem PIN (ou criar o PIN, se [primeira]). */
    data class Lido(val ip: String, val codigo: String, val primeira: Boolean, val fp: String, val ts: String, val ir: String)

    private val IPV4 = Regex("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$")
    private val HEX48 = Regex("^[0-9a-f]{48}$")
    private val HEX64 = Regex("^[0-9a-f]{64}$")
    private val NOME_TS = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*\\.ts\\.net$")
    private val IR = Regex("^[a-zA-Z0-9]+(\\.[a-zA-Z0-9]+)?$")

    /**
     * Lê o QR. Null se não é um QR da TV: só http, IPv4 da rede local (privado), porta 8080 e os campos no formato
     * certo. Assim um QR qualquer da rua não faz o app abrir um site de fora como se fosse a TV.
     */
    fun ler(texto: String?): Lido? {
        val t = texto?.trim() ?: return null
        if (!t.startsWith("http://")) return null
        val semEsquema = t.removePrefix("http://")
        val hostPorta = semEsquema.substringBefore('/')
        val fragmento = t.substringAfter('#', "")
        val ip = hostPorta.substringBefore(':')
        if (hostPorta.substringAfter(':', "") != PORTA_HTTP.toString()) return null
        if (!IPV4.matches(ip) || !privado(ip)) return null
        val campos = fragmento.split('&').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
        val primeira = campos.containsKey("primeira")
        val codigo = (if (primeira) campos["primeira"] else campos["qr"]) ?: return null
        if (!HEX48.matches(codigo)) return null
        val fp = campos["fp"]?.lowercase().orEmpty().takeIf { HEX64.matches(it) }.orEmpty()
        val ts = campos["ts"]?.lowercase().orEmpty().takeIf { NOME_TS.matches(it) }.orEmpty()
        val ir = campos["ir"].orEmpty().takeIf { IR.matches(it) }.orEmpty()
        return Lido(ip, codigo, primeira, fp, ts, ir)
    }

    /** 10.x, 172.16-31.x, 192.168.x: o painel só atende a rede de casa. */
    fun privado(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    /** O endereço que o app abre para esta TV em casa: https fixado quando há "fp", senão http. */
    fun enderecoCasa(tv: Tv): String =
        if (tv.fp.isNotEmpty()) "https://${tv.ip}:$PORTA_HTTPS/" else "http://${tv.ip}:$PORTA_HTTP/"

    /** Fora de casa: o https do Tailscale (certificado de verdade, sem fixação), se a TV mandou o nome. */
    fun enderecoFora(tv: Tv): String? = tv.ts.takeIf { it.isNotEmpty() }?.let { "https://$it:$PORTA_TAILSCALE/" }

    /** O fim do endereço para entrar com o código lido (o painel tira do endereço na hora). */
    fun fragmento(l: Lido): String =
        if (l.primeira) "#primeira=${l.codigo}" else "#qr=${l.codigo}" + (if (l.ir.isNotEmpty()) "&ir=${l.ir}" else "")

    /** SHA-256 do certificado (DER), em hex minúsculo: o mesmo que a TV põe no "fp". */
    fun impressao(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    /**
     * O certificado que chegou é o da TV pareada? Só compara a impressão: nome, validade e CA não importam, porque a
     * confiança vem de ter lido o QR na tela da TV. Comparação em tempo constante.
     */
    fun confere(tv: Tv, der: ByteArray?): Boolean =
        tv.fp.isNotEmpty() && der != null && MessageDigest.isEqual(impressao(der).toByteArray(), tv.fp.toByteArray())

    // ---------------------------------------------------------------- lista de TVs

    /** Junta a TV lida à lista: a mesma TV (mesmo IP) é atualizada no lugar; a mais recente vai para o topo. */
    fun guardar(lista: List<Tv>, nova: Tv): List<Tv> =
        (listOf(nova) + lista.filter { it.ip != nova.ip && (nova.id.isEmpty() || it.id != nova.id) }).take(MAX_TVS)

    fun paraJson(l: List<Tv>): String = JSONArray().apply {
        for (t in l) put(JSONObject().put("nome", t.nome).put("ip", t.ip).put("fp", t.fp).put("ts", t.ts).put("id", t.id))
    }.toString()

    fun deJson(s: String?): List<Tv> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let {
            Tv(it.optString("nome"), it.optString("ip"), it.optString("fp"), it.optString("ts"), it.optString("id"))
        } }.filter { IPV4.matches(it.ip) }
    } catch (e: Exception) { emptyList() }

    /** Tecla da TV para cada botão de volume do celular (nomes do PainelControle.TECLAS do launcher). */
    fun teclaDoVolume(sobe: Boolean): String = if (sobe) "volume+" else "volume-"
}
