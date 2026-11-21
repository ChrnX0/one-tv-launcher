package br.paulo.tvlauncher.backup

import br.paulo.tvlauncher.panel.recuperacao
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONObject

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

    /**
     * Tranca [claro] com a [senha]. Com [recuperacao] (a chave PÚBLICA da chave de recuperação, elevação 19.6) sai a
     * versão 2: o texto é cifrado com uma chave aleatória (a "chave do arquivo"), e essa chave vai trancada duas vezes,
     * uma pela senha e outra pela chave de recuperação. Qualquer uma das duas abre. Sem [recuperacao], a versão 1 de
     * sempre.
     */
    fun trancar(claro: String, senha: String, recuperacao: ByteArray? = null): String {
        require(senhaValida(senha)) { "a senha precisa ter de 8 a 128 caracteres" }
        val sal = ByteArray(16).also { aleatorio.nextBytes(it) }
        val b64 = Base64.getEncoder()
        if (recuperacao == null) {
            val iv = ByteArray(12).also { aleatorio.nextBytes(it) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, chave(senha, sal, VOLTAS), GCMParameterSpec(128, iv))
            return JSONObject().put("tipo", TIPO).put("versao", 1).put("voltas", VOLTAS)
                .put("sal", b64.encodeToString(sal)).put("iv", b64.encodeToString(iv))
                .put("dados", b64.encodeToString(c.doFinal(claro.toByteArray(Charsets.UTF_8)))).toString()
        }
        val doArquivo = ByteArray(32).also { aleatorio.nextBytes(it) }
        val (iv, dados) = gcm(doArquivo, claro.toByteArray(Charsets.UTF_8))
        val (ivS, porSenha) = gcm(chave(senha, sal, VOLTAS).encoded, doArquivo)
        val rec = ChaveRecuperacao.trancarParaPublica(recuperacao, doArquivo)
        doArquivo.fill(0)
        return JSONObject().put("tipo", TIPO).put("versao", 2).put("voltas", VOLTAS)
            .put("sal", b64.encodeToString(sal)).put("ivSenha", b64.encodeToString(ivS)).put("porSenha", b64.encodeToString(porSenha))
            .put("recuperacao", rec)
            .put("iv", b64.encodeToString(iv)).put("dados", b64.encodeToString(dados)).toString()
    }

    private fun gcm(chave: ByteArray, claro: ByteArray): Pair<ByteArray, ByteArray> {
        val iv = ByteArray(12).also { aleatorio.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(chave, "AES"), GCMParameterSpec(128, iv))
        return iv to c.doFinal(claro)
    }

    internal fun abrirGcm(chave: ByteArray, iv: ByteArray, dados: ByteArray): ByteArray {
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(chave, "AES"), GCMParameterSpec(128, iv))
        return try { c.doFinal(dados) } catch (e: AEADBadTagException) { throw SenhaErrada() }
    }

    /** O arquivo traz a tranca da chave de recuperação? (versão 2 com "recuperacao") */
    fun temRecuperacao(envelope: String): Boolean =
        runCatching { JSONObject(envelope).optJSONObject("recuperacao") != null }.getOrDefault(false)

    /**
     * Abre só com a chave de recuperação (o código do QR), sem a senha. Lança [SenhaErrada] se a chave não é a deste
     * arquivo; IllegalArgumentException se o arquivo não tem tranca de recuperação.
     */
    fun abrirComChave(envelope: String, codigo: String): String {
        val o = runCatching { JSONObject(envelope) }.getOrNull() ?: throw IllegalArgumentException("não é um backup cifrado")
        if (o.optString("tipo") != TIPO || o.optInt("versao") != 2) throw IllegalArgumentException("este backup não tem chave de recuperação")
        val rec = o.optJSONObject("recuperacao") ?: throw IllegalArgumentException("este backup não tem chave de recuperação")
        val doArquivo = ChaveRecuperacao.abrirComCodigo(rec, codigo)
        try { return abrirDados(o, doArquivo) } finally { doArquivo.fill(0) }
    }

    private fun abrirDados(o: JSONObject, doArquivo: ByteArray): String {
        val b64 = Base64.getDecoder()
        val iv = runCatching { b64.decode(o.getString("iv")) }.getOrNull()
        val dados = runCatching { b64.decode(o.getString("dados")) }.getOrNull()
        if (iv == null || iv.size != 12 || dados == null) throw IllegalArgumentException("arquivo de backup estragado")
        return String(abrirGcm(doArquivo, iv, dados), Charsets.UTF_8)
    }

    /**
     * Abre o cofre. Lança [SenhaErrada] se a senha não confere; IllegalArgumentException se o arquivo não é um cofre.
     * [senha] pode ser também o código da chave de recuperação (o campo de senha aceita os dois).
     */
    fun abrir(envelope: String, senha: String): String {
        // Parece código: tenta a chave; se não for, segue como senha (uma senha que começa com "sofaly-" também vale).
        if (ChaveRecuperacao.pareceCodigo(senha) && temRecuperacao(envelope)) {
            try { return abrirComChave(envelope, senha) } catch (_: SenhaErrada) { }
        }
        val o = runCatching { JSONObject(envelope) }.getOrNull() ?: throw IllegalArgumentException("não é um backup cifrado")
        if (o.optString("tipo") == TIPO && o.optInt("versao") == 2) {
            val voltas = o.optInt("voltas")
            if (voltas !in 50_000..1_000_000) throw IllegalArgumentException("arquivo com parâmetros fora do normal")
            val b64 = Base64.getDecoder()
            val sal = runCatching { b64.decode(o.getString("sal")) }.getOrNull()
            val ivS = runCatching { b64.decode(o.getString("ivSenha")) }.getOrNull()
            val porSenha = runCatching { b64.decode(o.getString("porSenha")) }.getOrNull()
            if (sal == null || sal.size != 16 || ivS == null || ivS.size != 12 || porSenha == null) throw IllegalArgumentException("arquivo de backup estragado")
            val doArquivo = abrirGcm(chave(senha, sal, voltas).encoded, ivS, porSenha)
            try { return abrirDados(o, doArquivo) } finally { doArquivo.fill(0) }
        }
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
