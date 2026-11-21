package br.paulo.tvlauncher.backup

import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.json.JSONArray
import org.json.JSONObject

/**
 * Elevação 19.7 (17/11/2026): "TV nova em 2 minutos". Regras puras (MudarDeTvRegrasTest); o Android fica em [MudarDeTv].
 *
 * Como funciona:
 *  1. a TV NOVA abre "Trazer de outra TV" e mostra um QR (e o mesmo código para digitar): o IP dela na rede de casa,
 *     um SEGREDO de uso único (8 letras) e a DIGITAL (8 letras) de uma chave pública que ela acabou de criar e guarda
 *     só na memória;
 *  2. no painel da TV ANTIGA, o dono (com o PIN de novo) lê o QR ou digita o código;
 *  3. a TV antiga busca a chave pública na nova, confere a digital (ninguém da rede troca a chave no meio), monta o
 *     backup completo e o TRANCA para aquela chave ([selar]: ECDH P-256 + AES-GCM). Só a TV nova abre;
 *  4. a TV nova confere o segredo, abre, restaura tudo (mesmo caminho do "restaurar backup completo") e põe na FILA
 *     os apps que a antiga tinha e ela não tem ([fila]): um por vez, a Play Store abre na página de cada um.
 * A conversa é na rede de casa (IP privado, mesma sub-rede: [ipDeCasa]); o conteúdo já vai cifrado de ponta a ponta,
 * então não depende de certificado nenhum. O segredo vale [VALIDADE_MS] e cai depois de [MAX_ERROS] tentativas erradas.
 */
object MudarDeTvRegras {
    const val VALIDADE_MS = 10 * 60_000L
    const val MAX_ERROS = 5
    const val MAX_APPS = 150
    const val PREFIXO = "SOFALY-TV"
    private const val ROTULO = "sofaly-mudar-tv-1"
    private const val ALFABETO = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"   // Base32 de Crockford (sem I, L, O, U)
    private val aleatorio = SecureRandom()

    // ---------------------------------------------------------------- o código (QR ou digitado)

    /** O que a TV nova mostra: onde ela está, o segredo de uso único e a digital da chave dela. */
    data class Convite(val ip: String, val porta: Int, val segredo: String, val digital: String) {
        /** Texto do QR e o que se digita: "SOFALY-TV 192.168.1.50:8080 ABCD-EFGH-JKMN-PQRS". */
        fun texto() = "$PREFIXO $ip:$porta ${codigo()}"
        fun codigo() = (segredo + digital).chunked(4).joinToString("-")
    }

    fun novoSegredo(): String = (1..8).joinToString("") { ALFABETO[aleatorio.nextInt(32)].toString() }

    /** 8 letras (40 bits) do SHA-256 da chave pública. Trocar a chave por outra com a mesma digital = 2^40 tentativas. */
    fun digital(publica: ByteArray): String {
        val h = MessageDigest.getInstance("SHA-256").digest(publica)
        var acc = 0L
        for (i in 0 until 5) acc = (acc shl 8) or (h[i].toLong() and 0xFF)
        return (0 until 8).joinToString("") { ALFABETO[((acc shr (35 - it * 5)) and 31).toInt()].toString() }
    }

    /** Letras que a pessoa troca ao digitar (O/0, I/1, L/1) e minúsculas viram o alfabeto certo. */
    private fun normalizar(t: String) = t.uppercase().replace('O', '0').replace('I', '1').replace('L', '1')

    /**
     * Lê o texto do QR ou o que a pessoa digitou ("192.168.1.50 abcd efgh jkmn pqrs", com ou sem a porta).
     * Null se não tem IP de casa ou o código não tem 16 letras do alfabeto.
     */
    fun ler(texto: String, portaPadrao: Int): Convite? {
        val t = texto.trim().take(200)
        val m = Regex("""(\d{1,3}(?:\.\d{1,3}){3})(?::(\d{1,5}))?""").find(t) ?: return null
        val ip = m.groupValues[1]
        if (!ipDeCasa(ip)) return null
        val porta = m.groupValues[2].ifEmpty { null }?.toIntOrNull() ?: portaPadrao
        if (porta !in 1024..65535) return null
        val resto = normalizar(t.removeRange(m.range).replace(PREFIXO, "", ignoreCase = true)).filter { it.isLetterOrDigit() }
        if (resto.length != 16 || resto.any { it !in ALFABETO }) return null
        return Convite(ip, porta, resto.take(8), resto.drop(8))
    }

    /** Só IPv4 privado (10/8, 172.16/12, 192.168/16): o envio nunca sai da rede de casa. */
    fun ipDeCasa(ip: String): Boolean {
        val p = ip.split('.').map { it.toIntOrNull() ?: return false }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        return p[0] == 10 || (p[0] == 172 && p[1] in 16..31) || (p[0] == 192 && p[1] == 168)
    }

    // ---------------------------------------------------------------- a porta da TV nova

    enum class Conferencia { OK, ERRADO, VENCIDO }

    /** O segredo como ele entra na conta (o mesmo que a pessoa digitaria: sem traços, maiúsculo, O->0, I/L->1). */
    fun segredoNormal(s: String) = normalizar(s).filter { it.isLetterOrDigit() }

    /**
     * O que vai no cabeçalho X-Mudar: o SHA-256 do segredo, nunca o segredo (rodada 15 da depuração). O segredo em si
     * entra como AAD do envelope ([selar]): quem copia o cabeçalho na rede não consegue montar um envelope que a TV nova
     * aceite (precisaria do segredo, 80 bits, que não passa pela rede).
     */
    fun prova(segredo: String): String =
        MessageDigest.getInstance("SHA-256").digest((ROTULO + "|prova|" + segredoNormal(segredo)).toByteArray()).joinToString("") { "%02x".format(it) }

    /** O segredo em vigor na TV nova. Uso único: depois do OK, [usado] = true e nada mais entra. */
    class Recepcao(val segredo: String, val criadoEm: Long) {
        var erros = 0; private set
        var usado = false; private set

        fun conferir(tentado: String?, agora: Long): Conferencia {
            if (usado || erros >= MAX_ERROS || agora - criadoEm !in 0..VALIDADE_MS) return Conferencia.VENCIDO
            val t = (tentado ?: "").trim().lowercase()
            if (!MessageDigest.isEqual(t.toByteArray(), prova(segredo).toByteArray())) { erros++; return Conferencia.ERRADO }
            usado = true
            return Conferencia.OK
        }

        fun vale(agora: Long) = !usado && erros < MAX_ERROS && agora - criadoEm in 0..VALIDADE_MS
    }

    // ---------------------------------------------------------------- trancar para a TV nova

    fun novoPar(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1"), aleatorio) }.generateKeyPair()

    private fun kek(compartilhado: ByteArray, efemera: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(ROTULO.toByteArray() + compartilhado + efemera)

    /** Tranca [dados] para a chave pública [publica] (X.509) da TV nova. Par descartável a cada envio (ECIES). */
    fun selar(publica: ByteArray, dados: ByteArray, segredo: String): JSONObject {
        val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(publica))
        val efemero = novoPar()
        val acordo = KeyAgreement.getInstance("ECDH").apply { init(efemero.private); doPhase(pub, true) }
        val iv = ByteArray(12).also { aleatorio.nextBytes(it) }
        val c = Cipher.getInstance("AES/GCM/NoPadding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(kek(acordo.generateSecret(), efemero.public.encoded), "AES"), GCMParameterSpec(128, iv))
        c.updateAAD((ROTULO + "|" + segredoNormal(segredo)).toByteArray())
        val b64 = Base64.getEncoder()
        return JSONObject().put("v", 1).put("efemera", b64.encodeToString(efemero.public.encoded))
            .put("iv", b64.encodeToString(iv)).put("dados", b64.encodeToString(c.doFinal(dados)))
    }

    /** Abre com a chave privada da TV nova. Qualquer defeito (outra chave, byte trocado) = IllegalArgumentException. */
    fun abrir(privada: PrivateKey, envelope: JSONObject, segredo: String): ByteArray {
        try {
            if (envelope.optInt("v") != 1) throw IllegalArgumentException("formato desconhecido")
            val b64 = Base64.getDecoder()
            val efemera = b64.decode(envelope.getString("efemera"))
            val iv = b64.decode(envelope.getString("iv"))
            if (iv.size != 12) throw IllegalArgumentException("formato desconhecido")
            val pub = KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(efemera))
            val acordo = KeyAgreement.getInstance("ECDH").apply { init(privada); doPhase(pub, true) }
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, SecretKeySpec(kek(acordo.generateSecret(), efemera), "AES"), GCMParameterSpec(128, iv))
            c.updateAAD((ROTULO + "|" + segredoNormal(segredo)).toByteArray())
            return c.doFinal(b64.decode(envelope.getString("dados")))
        } catch (e: IllegalArgumentException) { throw e
        } catch (e: Exception) { throw IllegalArgumentException("não abriu: ${e.javaClass.simpleName}") }
    }

    // ---------------------------------------------------------------- o que viaja

    data class App(val pacote: String, val nome: String)

    /** O pacote que vai dentro do envelope: o backup completo (trancado por [senha], descartável) e a lista de apps. */
    fun pacote(senha: String, backup: String, apps: List<App>, de: String): ByteArray = JSONObject()
        .put("v", 1).put("senha", senha).put("backup", backup).put("de", de.take(60))
        .put("apps", JSONArray().apply { apps.take(MAX_APPS).forEach { put(JSONObject().put("p", it.pacote).put("n", it.nome.take(60))) } })
        .toString().toByteArray(Charsets.UTF_8)

    class Pacote(val senha: String, val backup: String, val apps: List<App>, val de: String)

    fun lerPacote(b: ByteArray): Pacote {
        val o = runCatching { JSONObject(String(b, Charsets.UTF_8)) }.getOrNull() ?: throw IllegalArgumentException("conteúdo ilegível")
        if (o.optInt("v") != 1) throw IllegalArgumentException("versão desconhecida")
        val senha = o.optString("senha"); val backup = o.optString("backup")
        if (senha.length < 16 || backup.isEmpty()) throw IllegalArgumentException("conteúdo incompleto")
        return Pacote(senha, backup, appsDeJson(o.optJSONArray("apps")), o.optString("de").take(60))
    }

    // ---------------------------------------------------------------- a fila de apps

    private val PACOTE = Regex("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")
    fun pacoteValido(p: String) = p.length <= 150 && PACOTE.matches(p)

    /** Os apps da TV antiga que faltam nesta, na ordem em que vieram, sem repetidos, sem o próprio launcher. */
    fun fila(apps: List<App>, instalados: Set<String>, meuPacote: String): List<App> =
        apps.filter { pacoteValido(it.pacote) && it.pacote !in instalados && it.pacote != meuPacote }
            .distinctBy { it.pacote }.take(MAX_APPS)

    /** O próximo a instalar (o primeiro que ainda falta e que a pessoa não pulou), ou null quando acabou. */
    fun proximo(fila: List<App>, instalados: Set<String>, pulados: Set<String>): App? =
        fila.firstOrNull { it.pacote !in instalados && it.pacote !in pulados }

    fun appsParaJson(l: List<App>): String = JSONArray().apply { l.forEach { put(JSONObject().put("p", it.pacote).put("n", it.nome)) } }.toString()

    fun appsDeJson(a: JSONArray?): List<App> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val o = a!!.optJSONObject(i) ?: return@mapNotNull null
        val p = o.optString("p"); if (!pacoteValido(p)) return@mapNotNull null
        App(p, o.optString("n").take(60).ifBlank { p })
    }.take(MAX_APPS)
}
