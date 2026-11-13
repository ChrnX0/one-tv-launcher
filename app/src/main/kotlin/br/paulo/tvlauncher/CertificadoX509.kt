package br.paulo.tvlauncher

import java.net.Socket
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedKeyManager

/**
 * Certificado do painel em https (13/11/2026), gerado NA TV, sem nada no repositório e sem biblioteca de fora.
 * Lógica pura (só java.security), testada na JVM em CertificadoX509Test.
 *
 * Como funciona:
 *  - [emitir] cria duas chaves EC P-256: a da "autoridade" (CA) e a do servidor. A CA assina o próprio certificado e o
 *    do servidor e é JOGADA FORA na mesma hora (nunca vai para disco nem fica na memória). Assim, mesmo que alguém
 *    roube tudo o que está na TV, não consegue assinar certificado para outro site: quem instalou a CA no celular só
 *    passa a confiar neste certificado do servidor, e em nada mais.
 *  - Defesa extra: a CA leva "Name Constraints" (restrição de nomes, crítica): só vale para os nomes da TV e para a
 *    sub-rede da casa. O Chrome e o Windows respeitam isso em CA instalada à mão.
 *  - O certificado do servidor tem os nomes (SAN) "tv", "tv.lan", "tv.local" e o IP da TV, uso "servidor TLS" e
 *    validade de [DIAS_VALIDADE] dias (a Apple recusa mais de 825 em CA instalada à mão).
 *  - Renovar = emitir de novo: CA nova, e cada aparelho instala o certificado novo (é o preço de jogar a CA fora).
 *
 * O formato é DER (ASN.1) montado à mão: só o pedaço do X.509 de que precisamos.
 */
object CertificadoX509 {
    const val DIAS_VALIDADE = 820

    /** O que fica guardado na TV: os dois certificados e a chave do servidor (a da CA já não existe). */
    class Emissao(val ca: X509Certificate, val servidor: X509Certificate, val chaveServidor: PrivateKey)

    /**
     * Gera CA + certificado do servidor. [nomes] vão no SAN como DNS; [ip] (ex.: "192.168.1.50") com [prefixo] (ex.: 24)
     * vai no SAN e a sub-rede vai na restrição de nomes da CA. [rotulo] aparece no nome da CA (ex.: nome da TV), para
     * a pessoa reconhecer na lista de certificados do celular.
     */
    fun emitir(nomes: List<String>, ip: String?, prefixo: Int, rotulo: String, agora: Long = System.currentTimeMillis(),
               aleatorio: SecureRandom = SecureRandom()): Emissao {
        require(nomes.isNotEmpty()) { "sem nomes" }
        val ipBytes = ip?.let { ipv4(it) }
        val chaveCa = novaChave(aleatorio)
        val chaveSrv = novaChave(aleatorio)
        val inicio = agora - 24 * 3600_000L                    // um dia antes: relógio do celular um pouco atrás
        val fim = agora + DIAS_VALIDADE * 24 * 3600_000L
        val nomeCa = nome("One TV $rotulo (painel)".take(60))
        val nomeSrv = nome(nomes.first())
        val skiCa = idChave(chaveCa)
        val skiSrv = idChave(chaveSrv)

        val sanServidor = ArrayList<ByteArray>()
        for (n in nomes) sanServidor += implicito(2, n.toByteArray(Charsets.US_ASCII))   // dNSName
        if (ipBytes != null) sanServidor += implicito(7, ipBytes)                       // iPAddress

        val permitidos = ArrayList<ByteArray>()
        for (n in nomes) permitidos += seq(implicito(2, n.toByteArray(Charsets.US_ASCII)))
        if (ipBytes != null) permitidos += seq(implicito(7, sub(ipBytes, prefixo) + mascara(prefixo)))

        val extCa = listOf(
            extensao(OID_BASIC, true, seq(booleano(true), inteiro(byteArrayOf(0)))),   // CA, sem CA abaixo
            extensao(OID_KEY_USAGE, true, bits(0x06, 1)),                             // keyCertSign + cRLSign
            extensao(OID_NAME_CONSTRAINTS, true, seq(contexto(0, concat(permitidos)))),
            extensao(OID_SKI, false, octetos(skiCa)),
        )
        val extSrv = listOf(
            extensao(OID_BASIC, true, seq()),                                         // não é CA
            extensao(OID_KEY_USAGE, true, bits(0x80, 7)),                             // digitalSignature
            extensao(OID_EXT_KEY_USAGE, false, seq(oid(OID_SERVER_AUTH))),
            extensao(OID_SAN, false, seq(*sanServidor.toTypedArray())),
            extensao(OID_SKI, false, octetos(skiSrv)),
            extensao(OID_AKI, false, seq(implicito(0, skiCa))),
        )
        val ca = assinar(tbs(serial(aleatorio), nomeCa, nomeCa, inicio, fim + 24 * 3600_000L, chaveCa, extCa), chaveCa.private)
        val srv = assinar(tbs(serial(aleatorio), nomeCa, nomeSrv, inicio, fim, chaveSrv, extSrv), chaveCa.private)
        // chaveCa sai de escopo aqui: nada a guarda.
        return Emissao(ler(ca), ler(srv), chaveSrv.private)
    }

    fun ler(der: ByteArray): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

    /** Formato texto (PEM), o que celular e Windows aceitam como arquivo .crt. */
    fun pem(c: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(c.encoded) +
            "\n-----END CERTIFICATE-----\n"

    /** Impressão digital SHA-256 ("AB:CD:..."), para conferir na tela da TV que o arquivo baixado é o certo. */
    fun impressao(c: X509Certificate): String =
        MessageDigest.getInstance("SHA-256").digest(c.encoded).joinToString(":") { "%02X".format(it.toInt() and 0xff) }

    /** Os nomes e IPs do SAN do certificado (para saber se o IP atual da TV está nele). */
    fun nomesDo(c: X509Certificate): Set<String> =
        c.subjectAlternativeNames?.mapNotNull { (it.getOrNull(1) as? String)?.lowercase() }?.toSet() ?: emptySet()

    /**
     * Entrega sempre a mesma chave e cadeia ao TLS do Java/Android (sem KeyStore de arquivo, que muda de formato
     * entre JVM e Android). Só para chaves EC: em TLS 1.2 o servidor pergunta por tipo, e RSA não temos.
     */
    class ChaveFixa(private val chave: PrivateKey, private val cadeia: Array<X509Certificate>) : X509ExtendedKeyManager() {
        private fun serve(tipo: String?) = tipo == null || tipo.startsWith("EC")
        override fun chooseServerAlias(tipo: String?, emissores: Array<out Principal>?, s: Socket?) = if (serve(tipo)) APELIDO else null
        override fun chooseEngineServerAlias(tipo: String?, emissores: Array<out Principal>?, e: SSLEngine?) = if (serve(tipo)) APELIDO else null
        override fun getServerAliases(tipo: String?, emissores: Array<out Principal>?) = if (serve(tipo)) arrayOf(APELIDO) else null
        override fun getCertificateChain(apelido: String?) = if (apelido == APELIDO) cadeia.clone() else null
        override fun getPrivateKey(apelido: String?) = if (apelido == APELIDO) chave else null
        override fun getClientAliases(tipo: String?, emissores: Array<out Principal>?): Array<String>? = null
        override fun chooseClientAlias(tipos: Array<out String>?, emissores: Array<out Principal>?, s: Socket?): String? = null
        private companion object { const val APELIDO = "painel" }
    }

    /** "192.168.1.50" -> 4 bytes; recusa o que não for IPv4 escrito em números (sem DNS). */
    fun ipv4(ip: String): ByteArray {
        val partes = ip.split('.')
        require(partes.size == 4 && partes.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && p.toInt() <= 255 }) { "IP inválido" }
        return ByteArray(4) { partes[it].toInt().toByte() }
    }

    // ---------------------------------------------------------------- peças do certificado

    private fun novaChave(aleatorio: SecureRandom): KeyPair =
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), aleatorio) }.generateKeyPair()

    private fun tbs(serial: ByteArray, emissor: ByteArray, sujeito: ByteArray, inicio: Long, fim: Long,
                    chave: KeyPair, extensoes: List<ByteArray>): ByteArray = seq(
        contexto(0, inteiro(byteArrayOf(2))),           // versão 3
        inteiro(serial),
        seq(oid(OID_ECDSA_SHA256)),
        emissor,
        seq(tempo(inicio), tempo(fim)),
        sujeito,
        chave.public.encoded,                                 // SubjectPublicKeyInfo pronto, do próprio Java
        contexto(3, seq(*extensoes.toTypedArray())),
    )

    private fun assinar(tbs: ByteArray, chave: PrivateKey): ByteArray {
        val s = Signature.getInstance("SHA256withECDSA").apply { initSign(chave); update(tbs) }.sign()
        return seq(tbs, seq(oid(OID_ECDSA_SHA256)), bitString(s))
    }

    /** CN (e O fixo), em UTF8String. */
    private fun nome(cn: String): ByteArray = seq(
        set(seq(oid(OID_O), tlv(0x0c, "One TV Launcher".toByteArray(Charsets.UTF_8)))),
        set(seq(oid(OID_CN), tlv(0x0c, cn.toByteArray(Charsets.UTF_8)))),
    )

    /** Número de série: 16 bytes aleatórios, positivo e sem zero na frente. */
    private fun serial(aleatorio: SecureRandom): ByteArray {
        val b = ByteArray(16).also { aleatorio.nextBytes(it) }
        b[0] = ((b[0].toInt() and 0x7f) or 0x40).toByte()
        return b
    }

    /** Identificador da chave: SHA-1 da chave pública (o jeito do RFC 5280, 4.2.1.2). */
    private fun idChave(k: KeyPair): ByteArray = MessageDigest.getInstance("SHA-1").digest(k.public.encoded)

    private fun sub(ip: ByteArray, prefixo: Int): ByteArray {
        val m = mascara(prefixo)
        return ByteArray(4) { (ip[it].toInt() and m[it].toInt()).toByte() }
    }

    private fun mascara(prefixo: Int): ByteArray {
        val p = prefixo.coerceIn(8, 32)
        val v = if (p == 32) -1 else (-1 shl (32 - p))
        return byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    }

    private fun tempo(ms: Long): ByteArray {
        val utc = TimeZone.getTimeZone("UTC")
        val ano = java.util.Calendar.getInstance(utc).apply { timeInMillis = ms }.get(java.util.Calendar.YEAR)
        // RFC 5280: até 2049 em UTCTime (ano com 2 dígitos), de 2050 em diante GeneralizedTime.
        val (tag, formato) = if (ano < 2050) 0x17 to "yyMMddHHmmss'Z'" else 0x18 to "yyyyMMddHHmmss'Z'"
        val f = SimpleDateFormat(formato, Locale.US).apply { timeZone = utc }
        return tlv(tag, f.format(Date(ms)).toByteArray(Charsets.US_ASCII))
    }

    private fun extensao(id: String, critica: Boolean, valor: ByteArray): ByteArray =
        if (critica) seq(oid(id), booleano(true), octetos(valor)) else seq(oid(id), octetos(valor))

    // ---------------------------------------------------------------- DER

    private fun tlv(tag: Int, conteudo: ByteArray): ByteArray = byteArrayOf(tag.toByte()) + tamanho(conteudo.size) + conteudo

    private fun tamanho(n: Int): ByteArray = when {
        n < 0x80 -> byteArrayOf(n.toByte())
        n < 0x100 -> byteArrayOf(0x81.toByte(), n.toByte())
        n < 0x10000 -> byteArrayOf(0x82.toByte(), (n shr 8).toByte(), n.toByte())
        else -> byteArrayOf(0x83.toByte(), (n shr 16).toByte(), (n shr 8).toByte(), n.toByte())
    }

    private fun concat(partes: List<ByteArray>): ByteArray {
        val saida = java.io.ByteArrayOutputStream()
        for (p in partes) saida.write(p)
        return saida.toByteArray()
    }

    private fun seq(vararg partes: ByteArray) = tlv(0x30, concat(partes.toList()))
    private fun set(vararg partes: ByteArray) = tlv(0x31, concat(partes.toList()))
    private fun booleano(v: Boolean) = tlv(0x01, byteArrayOf(if (v) 0xff.toByte() else 0))
    private fun octetos(b: ByteArray) = tlv(0x04, b)
    private fun bitString(b: ByteArray) = tlv(0x03, byteArrayOf(0) + b)
    /** BIT STRING curta (keyUsage): um byte de bits e quantos bits do fim não contam. */
    private fun bits(byte: Int, sobra: Int) = tlv(0x03, byteArrayOf(sobra.toByte(), byte.toByte()))
    /** INTEGER positivo: põe um 0 na frente se o primeiro bit estiver ligado (senão seria negativo). */
    private fun inteiro(b: ByteArray) = tlv(0x02, if (b.isNotEmpty() && b[0] < 0) byteArrayOf(0) + b else b)
    /** [n] IMPLICIT primitivo (GeneralName: 2 = dNSName, 7 = iPAddress; 0 = keyIdentifier). */
    private fun implicito(n: Int, b: ByteArray) = tlv(0x80 or n, b)
    /** [n] construído (EXPLICIT, ou IMPLICIT de uma SEQUENCE). */
    private fun contexto(n: Int, b: ByteArray) = tlv(0xa0 or n, b)

    private fun oid(texto: String): ByteArray {
        val n = texto.split('.').map { it.toLong() }
        val saida = java.io.ByteArrayOutputStream()
        saida.write((n[0] * 40 + n[1]).toInt())
        for (v in n.drop(2)) {
            val pedacos = ArrayList<Int>()
            var x = v
            do { pedacos.add((x and 0x7f).toInt()); x = x shr 7 } while (x > 0)
            for (i in pedacos.indices.reversed()) saida.write(pedacos[i] or (if (i > 0) 0x80 else 0))
        }
        return tlv(0x06, saida.toByteArray())
    }

    private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_CN = "2.5.4.3"
    private const val OID_O = "2.5.4.10"
    private const val OID_BASIC = "2.5.29.19"
    private const val OID_KEY_USAGE = "2.5.29.15"
    private const val OID_EXT_KEY_USAGE = "2.5.29.37"
    private const val OID_SERVER_AUTH = "1.3.6.1.5.5.7.3.1"
    private const val OID_SAN = "2.5.29.17"
    private const val OID_NAME_CONSTRAINTS = "2.5.29.30"
    private const val OID_SKI = "2.5.29.14"
    private const val OID_AKI = "2.5.29.35"
}
