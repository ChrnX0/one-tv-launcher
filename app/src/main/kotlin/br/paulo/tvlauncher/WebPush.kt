package br.paulo.tvlauncher

import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.URI
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.KeyPair
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Web Push sem servidor próprio (12/11/2026, ideia 3: avisos no celular). A conta toda, pura, para o teste JVM conferir:
 *  - cifrar a mensagem para o celular (RFC 8291, "aes128gcm"): só o navegador do celular consegue ler; o serviço de push
 *    do Google (ou da Mozilla, da Apple) só carrega bytes cifrados;
 *  - assinar o pedido com a chave da TV (RFC 8292, "VAPID"): o serviço de push só entrega se a assinatura bater com a
 *    chave que o celular aceitou ao se inscrever. Ninguém mais consegue mandar aviso para aquela inscrição.
 *
 * A chave da TV mora no AndroidKeyStore (Avisos.kt); aqui ela chega como função "assinar".
 */
object WebPush {
    /** Serviços de push aceitos (o endereço vem do navegador; sem esta lista, o painel viraria um "faça a TV acessar X"). */
    val HOSTS_ACEITOS = listOf("fcm.googleapis.com", "updates.push.services.mozilla.com", "web.push.apple.com",
        ".push.apple.com", ".notify.windows.com")

    fun hostAceito(endpoint: String): Boolean {
        val u = runCatching { URI(endpoint) }.getOrNull() ?: return false
        if (u.scheme != "https" || u.port != -1 && u.port != 443) return false
        val h = u.host?.lowercase() ?: return false
        return HOSTS_ACEITOS.any { if (it.startsWith(".")) h.endsWith(it) else h == it }
    }

    fun b64url(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun deB64url(s: String): ByteArray = Base64.getUrlDecoder().decode(s.trim().trimEnd('='))

    private val parametros: ECParameterSpec by lazy {
        AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }
            .getParameterSpec(ECParameterSpec::class.java)
    }

    /** Chave pública P-256 em 65 bytes (0x04 || X || Y), o formato do Web Push. */
    fun cru(p: PublicKey): ByteArray {
        val w = (p as ECPublicKey).w
        fun b32(n: BigInteger): ByteArray {
            val b = n.toByteArray()
            return when {
                b.size == 32 -> b
                b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
                else -> ByteArray(32 - b.size) + b
            }
        }
        return byteArrayOf(4) + b32(w.affineX) + b32(w.affineY)
    }

    fun deCru(b: ByteArray): PublicKey {
        require(b.size == 65 && b[0] == 4.toByte()) { "chave pública inválida" }
        val ponto = ECPoint(BigInteger(1, b.copyOfRange(1, 33)), BigInteger(1, b.copyOfRange(33, 65)))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ponto, parametros))
    }

    private fun hmac(chave: ByteArray, dado: ByteArray): ByteArray =
        Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(chave, "HmacSHA256")) }.doFinal(dado)

    private fun ecdh(privada: PrivateKey, publica: PublicKey): ByteArray =
        KeyAgreement.getInstance("ECDH").apply { init(privada); doPhase(publica, true) }.generateSecret()

    /** Chave e nonce do conteúdo (RFC 8291 seção 3.4 + RFC 8188). */
    private fun chaves(segredoAuth: ByteArray, segredoEcdh: ByteArray, uaPublica: ByteArray, asPublica: ByteArray,
                       sal: ByteArray): Pair<ByteArray, ByteArray> {
        val prkChave = hmac(segredoAuth, segredoEcdh)
        val info = "WebPush: info".toByteArray() + byteArrayOf(0) + uaPublica + asPublica
        val ikm = hmac(prkChave, info + byteArrayOf(1))
        val prk = hmac(sal, ikm)
        val cek = hmac(prk, "Content-Encoding: aes128gcm".toByteArray() + byteArrayOf(0, 1)).copyOf(16)
        val nonce = hmac(prk, "Content-Encoding: nonce".toByteArray() + byteArrayOf(0, 1)).copyOf(12)
        return cek to nonce
    }

    /**
     * Cifra [mensagem] para a inscrição ([uaPublica] = p256dh, [segredoAuth] = auth). [efemera] e [sal] são novos a cada
     * envio (os testes passam fixos). Devolve o corpo pronto do POST (cabeçalho aes128gcm + texto cifrado, 1 registro).
     */
    fun cifrar(mensagem: ByteArray, uaPublica: ByteArray, segredoAuth: ByteArray, efemera: KeyPair, sal: ByteArray): ByteArray {
        require(sal.size == 16 && segredoAuth.size >= 16)
        require(mensagem.size <= 3900) { "mensagem grande demais para um registro" }
        val asPublica = cru(efemera.public)
        val (cek, nonce) = chaves(segredoAuth, ecdh(efemera.private, deCru(uaPublica)), uaPublica, asPublica, sal)
        val cifrado = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        }.doFinal(mensagem + byteArrayOf(2))   // 0x02 = último registro, sem enchimento
        val rs = 4096
        return ByteArrayOutputStream().apply {
            write(sal); write(byteArrayOf((rs ushr 24).toByte(), (rs ushr 16).toByte(), (rs ushr 8).toByte(), rs.toByte()))
            write(asPublica.size); write(asPublica); write(cifrado)
        }.toByteArray()
    }

    /** O contrário de [cifrar], do lado do celular. Só para o teste conferir a ida e a volta. */
    fun decifrar(corpo: ByteArray, uaPrivada: PrivateKey, uaPublica: ByteArray, segredoAuth: ByteArray): ByteArray {
        val sal = corpo.copyOfRange(0, 16)
        val idlen = corpo[20].toInt() and 0xFF
        val asPublica = corpo.copyOfRange(21, 21 + idlen)
        val (cek, nonce) = chaves(segredoAuth, ecdh(uaPrivada, deCru(asPublica)), uaPublica, asPublica, sal)
        val claro = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, SecretKeySpec(cek, "AES"), GCMParameterSpec(128, nonce))
        }.doFinal(corpo.copyOfRange(21 + idlen, corpo.size))
        val fim = claro.indexOfLast { it != 0.toByte() }
        require(fim >= 0 && claro[fim] == 2.toByte()) { "delimitador ausente" }
        return claro.copyOf(fim)
    }

    /** Assinatura ECDSA em DER (o que o Java devolve) -> R||S de 64 bytes (o que o JWT ES256 exige). */
    fun derParaCru(der: ByteArray): ByteArray {
        require(der.size >= 8 && der[0] == 0x30.toByte()) { "assinatura DER inválida" }
        var i = 2
        if (der[1].toInt() and 0x80 != 0) i = 2 + (der[1].toInt() and 0x7F)
        fun inteiro(): ByteArray {
            require(der[i] == 2.toByte()); val n = der[i + 1].toInt() and 0xFF
            val v = der.copyOfRange(i + 2, i + 2 + n); i += 2 + n
            val semZero = v.dropWhile { it == 0.toByte() }.toByteArray()
            require(semZero.size <= 32)
            return ByteArray(32 - semZero.size) + semZero
        }
        return inteiro() + inteiro()
    }

    /** Origem do endpoint ("https://fcm.googleapis.com"): é o "aud" do JWT. */
    fun audiencia(endpoint: String): String { val u = URI(endpoint); return "${u.scheme}://${u.host}" }

    /**
     * O cabeçalho Authorization do VAPID: "vapid t=<JWT>, k=<chave pública>". [assinarDer] assina com SHA256withECDSA e
     * devolve DER (a chave do AndroidKeyStore nunca sai de lá). [contato] é o "sub": uma URL pública do projeto, nada pessoal.
     */
    fun autorizacao(endpoint: String, vapidPublica: ByteArray, agoraSeg: Long, contato: String,
                    assinarDer: (ByteArray) -> ByteArray): String {
        val cab = b64url("""{"typ":"JWT","alg":"ES256"}""".toByteArray())
        val corpo = b64url(org.json.JSONObject().put("aud", audiencia(endpoint)).put("exp", agoraSeg + 12 * 3600)
            .put("sub", contato).toString().toByteArray())
        val semAssinatura = "$cab.$corpo"
        val ass = b64url(derParaCru(assinarDer(semAssinatura.toByteArray())))
        return "vapid t=$semAssinatura.$ass, k=${b64url(vapidPublica)}"
    }
}
