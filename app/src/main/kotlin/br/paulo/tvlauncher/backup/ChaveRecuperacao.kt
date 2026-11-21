package br.paulo.tvlauncher.backup

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPrivateKey
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPrivateKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.KeyAgreement
import org.json.JSONObject

/**
 * Elevação 19.6 (17/11/2026): a chave de recuperação do backup. Esqueceu a senha do backup? A chave abre.
 *
 * Como fica segura sem morar na TV: é um par de chaves (curva P-256, a padrão de TLS e do Android).
 *  - a parte SECRETA vira o código do QR ("SOFALY-XXXXX-..."): aparece UMA vez, no celular/computador do dono (painel),
 *    para imprimir ou guardar. A TV não guarda, não mostra na tela e não manda a servidor nenhum;
 *  - a TV guarda só a parte PÚBLICA. Com ela consegue trancar a chave de cada backup novo, mas não consegue abrir.
 * Trancar para a pública (ECIES): a cada backup um par de chaves descartável; o segredo combinado (ECDH) + SHA-256
 * vira a chave AES-GCM que tranca a "chave do arquivo" ([CofreBackup.trancar] versão 2).
 * Puro (sem Android): testado em ChaveRecuperacaoTest.
 */
object ChaveRecuperacao {
    const val PREFIXO = "SOFALY"
    private const val ROTULO = "sofaly-recuperacao-1"
    private const val ALFABETO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"   // Base32 de Crockford: sem I, L, O e U
    private val aleatorio = SecureRandom()

    private val parametros: ECParameterSpec by lazy {
        val g = KeyPairGenerator.getInstance("EC"); g.initialize(ECGenParameterSpec("secp256r1"))
        (g.generateKeyPair().public as ECPublicKey).params
    }

    /** Uma chave nova: o código (só para o dono) e a pública (para a TV guardar). */
    class Nova(val codigo: String, val publica: ByteArray)

    fun gerar(): Nova {
        val g = KeyPairGenerator.getInstance("EC"); g.initialize(ECGenParameterSpec("secp256r1"), aleatorio)
        val par = g.generateKeyPair()
        val s = (par.private as ECPrivateKey).s
        val bruto = paraBytes32(s)
        val codigo = codigoDe(bruto)
        bruto.fill(0)
        return Nova(codigo, par.public.encoded)
    }

    /** Impressão digital curta da pública (para mostrar "chave criada em ... · 3F7A"), sem revelar nada. */
    fun digital(publica: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(publica).take(2).joinToString("") { "%02X".format(it) }

    // ---------------------------------------------------------------- código

    private fun paraBytes32(n: BigInteger): ByteArray {
        val b = n.toByteArray()
        return when {
            b.size == 32 -> b
            b.size > 32 -> b.copyOfRange(b.size - 32, b.size)
            else -> ByteArray(32 - b.size) + b
        }
    }

    private fun verificacao(bruto: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bruto).copyOf(2)

    /** 32 bytes + 2 de verificação -> Base32 em grupos de 5: "SOFALY-XXXXX-XXXXX-...". Digitado errado = recusado. */
    internal fun codigoDe(bruto: ByteArray): String {
        val dados = bruto + verificacao(bruto)
        val sb = StringBuilder()
        var acc = 0; var bits = 0
        for (b in dados) {
            acc = (acc shl 8) or (b.toInt() and 0xFF); bits += 8
            while (bits >= 5) { bits -= 5; sb.append(ALFABETO[(acc shr bits) and 31]) }
        }
        if (bits > 0) sb.append(ALFABETO[(acc shl (5 - bits)) and 31])
        return PREFIXO + "-" + sb.chunked(5).joinToString("-")
    }

    /** Parece um código de recuperação (para o campo de senha aceitar os dois)? */
    fun pareceCodigo(t: String): Boolean = t.trim().uppercase().startsWith("$PREFIXO-") && t.length >= 50

    /** Os 32 bytes da chave secreta, ou null se o código está incompleto ou com letra trocada. */
    internal fun brutoDoCodigo(codigo: String): ByteArray? {
        val t = codigo.trim().uppercase().removePrefix(PREFIXO).replace(Regex("[^0-9A-Z]"), "")
            .replace('O', '0').replace('I', '1').replace('L', '1')
        var acc = 0; var bits = 0
        val saida = java.io.ByteArrayOutputStream()
        for (ch in t) {
            val v = ALFABETO.indexOf(ch); if (v < 0) return null
            acc = (acc shl 5) or v; bits += 5
            if (bits >= 8) { bits -= 8; saida.write((acc shr bits) and 0xFF) }
            acc = acc and 0xFFFF
        }
        val d = saida.toByteArray()
        if (d.size != 34) return null
        val bruto = d.copyOf(32)
        return if (verificacao(bruto).contentEquals(d.copyOfRange(32, 34))) bruto else null
    }

    fun codigoValido(codigo: String) = brutoDoCodigo(codigo) != null

    // ---------------------------------------------------------------- trancar e abrir

    private fun kek(compartilhado: ByteArray, efemera: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(ROTULO.toByteArray() + compartilhado + efemera)

    /** Tranca [segredo] (a chave de um arquivo) para a [publica]. Devolve o pedaço "recuperacao" do envelope. */
    fun trancarParaPublica(publica: ByteArray, segredo: ByteArray): JSONObject {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publica))
        val g = KeyPairGenerator.getInstance("EC"); g.initialize(ECGenParameterSpec("secp256r1"), aleatorio)
        val efemero = g.generateKeyPair()
        val acordo = KeyAgreement.getInstance("ECDH").apply { init(efemero.private); doPhase(pub, true) }
        val chave = kek(acordo.generateSecret(), efemero.public.encoded)
        val iv = ByteArray(12).also { aleatorio.nextBytes(it) }
        val c = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding")
        c.init(javax.crypto.Cipher.ENCRYPT_MODE, javax.crypto.spec.SecretKeySpec(chave, "AES"), javax.crypto.spec.GCMParameterSpec(128, iv))
        val b64 = Base64.getEncoder()
        return JSONObject().put("digital", digital(publica)).put("efemera", b64.encodeToString(efemero.public.encoded))
            .put("iv", b64.encodeToString(iv)).put("chave", b64.encodeToString(c.doFinal(segredo)))
    }

    /** Abre o pedaço "recuperacao" com o código. Lança [CofreBackup.SenhaErrada] se não é a chave deste arquivo. */
    fun abrirComCodigo(rec: JSONObject, codigo: String): ByteArray {
        val bruto = brutoDoCodigo(codigo) ?: throw CofreBackup.SenhaErrada()
        // Código errado que passa na verificação de 16 bits (1 em 65536) pode virar escalar 0 ou >= n: é "chave errada".
        val priv = try {
            KeyFactory.getInstance("EC").generatePrivate(ECPrivateKeySpec(BigInteger(1, bruto), parametros))
        } catch (e: java.security.GeneralSecurityException) { throw CofreBackup.SenhaErrada()
        } finally { bruto.fill(0) }
        val b64 = Base64.getDecoder()
        val efemera = runCatching { b64.decode(rec.getString("efemera")) }.getOrNull()
        val iv = runCatching { b64.decode(rec.getString("iv")) }.getOrNull()
        val trancada = runCatching { b64.decode(rec.getString("chave")) }.getOrNull()
        if (efemera == null || iv == null || iv.size != 12 || trancada == null) throw IllegalArgumentException("arquivo de backup estragado")
        val pubEfemera = runCatching { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(efemera)) }.getOrNull()
            ?: throw IllegalArgumentException("arquivo de backup estragado")
        // init recusa a NOSSA chave (código errado); doPhase recusa a do arquivo (arquivo estragado).
        val acordo = try { KeyAgreement.getInstance("ECDH").apply { init(priv) } }
            catch (e: java.security.GeneralSecurityException) { throw CofreBackup.SenhaErrada() }
        try { acordo.doPhase(pubEfemera, true) }
            catch (e: java.security.GeneralSecurityException) { throw IllegalArgumentException("arquivo de backup estragado") }
        return CofreBackup.abrirGcm(kek(acordo.generateSecret(), efemera), iv, trancada)
    }
}
