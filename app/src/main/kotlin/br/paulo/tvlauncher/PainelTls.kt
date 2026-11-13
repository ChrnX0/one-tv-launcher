package br.paulo.tvlauncher

import android.content.Context
import android.provider.Settings
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.security.KeyFactory
import java.security.KeyStore
import java.security.spec.PKCS8EncodedKeySpec
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.net.ssl.SSLContext

/**
 * O certificado do painel em https na rede de casa (13/11/2026): https://tv:8443/, para instalar o painel como app
 * (PWA) no celular e no PC. O certificado é feito pela própria TV no primeiro uso ([CertificadoX509.emitir]); nada
 * disso existe no repositório.
 *
 * O que fica guardado, em noBackupFilesDir/painel-tls (fora do backup do Android e do backup do launcher):
 *  - ca.der e servidor.der: os certificados (públicos; o ca.der é o arquivo que a pessoa instala nos aparelhos).
 *  - chave.bin: a chave do servidor, CIFRADA (AES-GCM) com uma chave do Android Keystore que nunca sai da TV.
 *  - A chave da CA não existe mais depois de emitir (ver CertificadoX509): ninguém consegue assinar outro certificado.
 *
 * Renovar (botão no painel, com PIN, ou sozinho quando vence): CA nova, e cada aparelho instala o arquivo de novo.
 * Se o IP da TV mudar, https://tv:8443 continua funcionando (o nome não muda); só o endereço por IP pede renovar.
 */
object PainelTls {
    private const val TAG = "TvLauncher.PainelTls"
    private const val APELIDO = "painel_tls_cofre"
    const val PORTA = ChecagensPainel.PORTA_HTTPS

    @Volatile private var atual: CertificadoX509.Emissao? = null

    private fun pasta(ctx: Context) = File(ctx.noBackupFilesDir, "painel-tls")

    /** Os nomes que vão no certificado (e os únicos aceitos no Host da porta https, além do IP). */
    fun nomes(): List<String> = Config.PAINEL_NOMES_HTTPS

    /** O certificado em uso, se já foi carregado ou emitido. */
    fun emissao(): CertificadoX509.Emissao? = atual

    /**
     * Carrega do disco ou, na primeira vez (ou vencido, ou arquivo estragado), emite um novo. [ip]/[prefixo]: o IP da
     * TV na rede de casa agora. Devolve null se nem emitir deu (ex.: Keystore com defeito): o https fica desligado.
     */
    @Synchronized fun garantir(ctx: Context, ip: String?, prefixo: Int): CertificadoX509.Emissao? {
        val agora = System.currentTimeMillis()
        val e = atual ?: carregar(ctx)
        if (e != null && e.servidor.notAfter.time > agora) { atual = e; return e }
        if (e != null) Log.i(TAG, "certificado vencido: emitindo outro")
        return emitir(ctx, ip, prefixo)
    }

    /** Emite um certificado novo (CA nova): os aparelhos que já tinham o antigo precisam instalar o novo. */
    @Synchronized fun emitir(ctx: Context, ip: String?, prefixo: Int): CertificadoX509.Emissao? = try {
        val rotulo = Settings.Global.getString(ctx.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: "TV"
        val e = CertificadoX509.emitir(nomes(), ip, prefixo, rotulo)
        salvar(ctx, e)
        atual = e
        Log.i(TAG, "certificado novo emitido (${CertificadoX509.impressao(e.ca).take(11)}…)")
        e
    } catch (ex: Exception) {
        Log.w(TAG, "não consegui emitir o certificado: $ex")
        null
    }

    /** O contexto TLS do servidor: só TLS 1.2 e 1.3 (ver [protocolos]), a chave e a cadeia (servidor + CA). */
    fun contexto(e: CertificadoX509.Emissao): SSLContext = SSLContext.getInstance("TLS").apply {
        init(arrayOf(CertificadoX509.ChaveFixa(e.chaveServidor, arrayOf(e.servidor, e.ca))), null, null)
    }

    /** Dos protocolos que o aparelho tem, só os atuais. */
    fun protocolos(suportados: Array<String>): Array<String> =
        suportados.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()

    /**
     * O que a página do certificado e o cartão do painel mostram. [ips]: IPs da TV na rede agora; [ligado]: a porta
     * https está aberta.
     */
    fun estado(ips: Set<String>, ligado: Boolean): JSONObject {
        val o = JSONObject().put("ligado", ligado).put("porta", PORTA)
        val e = atual ?: return o.put("pronto", false)
        val noCert = CertificadoX509.nomesDo(e.servidor)
        val nome = nomes().first()
        val diasRestantes = (e.servidor.notAfter.time - System.currentTimeMillis()) / 86_400_000L
        return o.put("pronto", true)
            .put("endereco", "https://$nome:$PORTA/")
            .put("nomes", org.json.JSONArray(noCert.sorted()))
            .put("ipNoCertificado", ips.any { it in noCert })
            .put("enderecoIp", ips.firstOrNull { it in noCert }?.let { "https://$it:$PORTA/" } ?: JSONObject.NULL)
            .put("impressao", CertificadoX509.impressao(e.ca))
            .put("validoAte", e.servidor.notAfter.time)
            .put("diasRestantes", diasRestantes)
    }

    fun pemDaCa(): String? = atual?.let { CertificadoX509.pem(it.ca) }

    // ---------------------------------------------------------------- disco

    private fun carregar(ctx: Context): CertificadoX509.Emissao? = try {
        val p = pasta(ctx)
        val ca = File(p, "ca.der"); val srv = File(p, "servidor.der"); val chave = File(p, "chave.bin")
        if (!ca.exists() || !srv.exists() || !chave.exists()) null
        else {
            val pk = KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(decifrar(chave.readBytes())))
            CertificadoX509.Emissao(CertificadoX509.ler(ca.readBytes()), CertificadoX509.ler(srv.readBytes()), pk)
        }
    } catch (e: Exception) {
        Log.w(TAG, "certificado guardado ilegível, vou emitir outro: $e")
        null
    }

    /** Escreve em arquivos .novo e só então troca: uma queda no meio não deixa metade velha e metade nova. */
    private fun salvar(ctx: Context, e: CertificadoX509.Emissao) {
        val p = pasta(ctx).apply { mkdirs() }
        val arquivos = listOf("ca.der" to e.ca.encoded, "servidor.der" to e.servidor.encoded,
            "chave.bin" to cifrar(e.chaveServidor.encoded))
        for ((nome, bytes) in arquivos) File(p, "$nome.novo").writeBytes(bytes)
        for ((nome, _) in arquivos) {
            if (!File(p, "$nome.novo").renameTo(File(p, nome))) throw java.io.IOException("não troquei $nome")
        }
    }

    private fun cifrar(claro: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, chaveDoKeystore()) }
        val iv = c.iv
        return byteArrayOf(iv.size.toByte()) + iv + c.doFinal(claro)
    }

    private fun decifrar(b: ByteArray): ByteArray {
        val n = b[0].toInt()
        require(n in 12..16 && b.size > 1 + n) { "arquivo curto" }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chaveDoKeystore(), GCMParameterSpec(128, b.copyOfRange(1, 1 + n)))
        return c.doFinal(b, 1 + n, b.size - 1 - n)
    }

    private fun chaveDoKeystore(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(APELIDO, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gerador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gerador.init(KeyGenParameterSpec.Builder(APELIDO, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .build())
        return gerador.generateKey()
    }
}
