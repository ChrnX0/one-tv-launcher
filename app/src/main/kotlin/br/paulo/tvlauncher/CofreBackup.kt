package br.paulo.tvlauncher

import org.json.JSONObject
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/**
 * O "cofre" do backup completo (10/11/2026): tranca um texto com uma senha escolhida pelo dono.
 *
 * Em português: a senha vira uma chave de 256 bits pelo PBKDF2 (o mesmo "resumo lento" do PIN do painel, aqui com mais
 * voltas, para adivinhar a senha por força bruta custar caro) e o texto é cifrado com AES-GCM, que além de esconder
 * também detecta qualquer byte alterado. Sem a senha certa o arquivo não abre, e não há porta dos fundos.
 *
 * Por que não o Keystore do Android (como o UniFi e o IPTV usam): a chave do Keystore nunca sai do aparelho, e um reset
 * de fábrica a apaga. O backup existe justamente para sobreviver ao reset, então a chave tem que vir de fora: a senha.
 *
 * Puro (sem Android): testado na JVM em CofreBackupTest.
 */
object CofreBackup {
    const val TIPO = "tv-launcher-backup-cifrado"
    private const val VOLTAS = 150_000          // ~1 a 2 s na TV de 32 bits; o PIN usa 100 mil
    private val aleatorio = SecureRandom()

    class SenhaErrada : Exception("senha errada ou arquivo alterado")

    /** Senha aceita: 8 a 128 caracteres. Curta demais cairia em força bruta mesmo com o PBKDF2. */
    fun senhaValida(s: String): Boolean = s.length in 8..128

    /** É um arquivo deste cofre? (só olha o envelope; não precisa da senha) */
    fun eCifrado(texto: String): Boolean = runCatching { JSONObject(texto).optString("tipo") == TIPO }.getOrDefault(false)

    fun trancar(claro: String, senha: String): String {
        require(senhaValida(senha)) { "a senha precisa ter de 8 a 128 caracteres" }
        val sal = ByteArray(16).also { aleatorio.nextBytes(it) }
        val iv = ByteArray(12).also { aleatorio.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, chave(senha, sal, VOLTAS), GCMParameterSpec(128, iv))
        val b64 = Base64.getEncoder()
        return JSONObject().put("tipo", TIPO).put("versao", 1).put("voltas", VOLTAS)
            .put("sal", b64.encodeToString(sal)).put("iv", b64.encodeToString(iv))
            .put("dados", b64.encodeToString(c.doFinal(claro.toByteArray(Charsets.UTF_8)))).toString()
    }

    /** Abre o cofre. Lança [SenhaErrada] se a senha não confere; IllegalArgumentException se o arquivo não é um cofre. */
    fun abrir(envelope: String, senha: String): String {
        val o = runCatching { JSONObject(envelope) }.getOrNull() ?: throw IllegalArgumentException("não é um backup cifrado")
        if (o.optString("tipo") != TIPO || o.optInt("versao") != 1) throw IllegalArgumentException("não é um backup cifrado desta versão")
        val voltas = o.optInt("voltas")
        // Limite de voltas: um arquivo forjado com 2 bilhões de voltas travaria a TV por horas.
        if (voltas !in 50_000..1_000_000) throw IllegalArgumentException("arquivo com parâmetros fora do normal")
        val b64 = Base64.getDecoder()
        val sal = runCatching { b64.decode(o.getString("sal")) }.getOrNull()
        val iv = runCatching { b64.decode(o.getString("iv")) }.getOrNull()
        val dados = runCatching { b64.decode(o.getString("dados")) }.getOrNull()
        if (sal == null || sal.size != 16 || iv == null || iv.size != 12 || dados == null) throw IllegalArgumentException("arquivo de backup estragado")
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, chave(senha, sal, voltas), GCMParameterSpec(128, iv))
        return try { String(c.doFinal(dados), Charsets.UTF_8) } catch (e: AEADBadTagException) { throw SenhaErrada() }
    }

    private fun chave(senha: String, sal: ByteArray, voltas: Int): SecretKeySpec {
        val spec = PBEKeySpec(senha.toCharArray(), sal, voltas, 256)
        try {
            return SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded, "AES")
        } finally { spec.clearPassword() }
    }
}
