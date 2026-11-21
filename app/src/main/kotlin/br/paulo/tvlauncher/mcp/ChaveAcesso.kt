package br.paulo.tvlauncher.mcp

import java.security.KeyFactory
import java.security.MessageDigest
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.interfaces.ECPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import org.json.JSONArray
import org.json.JSONObject

/**
 * Entrar no painel com a digital do celular (ideia 99, 14/11/2026): chave de acesso (passkey, WebAuthn) só pelo
 * endereço do Tailscale (https://<nome>.ts.net:8443). Pelo IP de casa não dá: a regra do WebAuthn exige que o "RP ID"
 * seja um nome de domínio válido num contexto seguro, e IP não é nome de domínio. O PIN continua valendo sempre.
 *
 * Só a verificação, sem Android (testada na JVM em ChaveAcessoTest):
 *  - cadastro: a sessão já entrou com o PIN; o celular cria a chave (ES256, P-256, verificação do usuário obrigatória) e
 *    manda a chave pública (SPKI, getPublicKey() do navegador) + authenticatorData + clientDataJSON. Conferimos tipo,
 *    desafio, origem, o resumo do RP ID e as marcas UP/UV/AT, e que o id da chave é o mesmo de dentro do authData.
 *    Atestação "none": não queremos saber a marca do celular, só a chave.
 *  - entrada: desafio de uso único (2 min); conferimos a assinatura ECDSA sobre authData || SHA-256(clientDataJSON),
 *    o tipo, o desafio, a origem, o RP ID, UP e UV, e o contador (se o celular usa contador, ele só pode subir).
 */
object ChaveAcesso {
    const val MAX_CHAVES = 10
    const val DESAFIO_MS = 2 * 60_000L
    private const val UP = 0x01   // usuário presente (tocou)
    private const val UV = 0x04   // usuário verificado (digital, rosto ou o bloqueio de tela)
    private const val AT = 0x40   // authData traz a chave nova (só no cadastro)

    data class Chave(val id: String, val chavePublica: String, val nome: String, val criada: Long, val contador: Long, val usadaEm: Long = 0)

    /** Uma chave recusada diz o motivo (vai para o registro de acessos; para a pessoa sai uma frase só). */
    class Recusa(motivo: String) : Exception(motivo)

    private val aleatorio = SecureRandom()

    fun b64(b: ByteArray): String = Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    fun deB64(s: String?): ByteArray = try { Base64.getUrlDecoder().decode(s.orEmpty().trimEnd('=')) } catch (e: IllegalArgumentException) { throw Recusa("base64 inválido") }
    fun novoDesafio(): String = b64(ByteArray(32).also { aleatorio.nextBytes(it) })
    private fun sha256(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b)

    /** RP ID = o nome sem a porta ("onetv-sala.tailXXXX.ts.net"). */
    fun rpId(host: String): String = host.lowercase().substringBeforeLast(':')
    /** Origem esperada no clientDataJSON: "https://" + o Host inteiro (com a porta 8443). */
    fun origem(host: String): String = "https://" + host.lowercase()

    // ---------------------------------------------------------------- partes comuns

    private fun conferirCliente(clientDataJSON: ByteArray, tipo: String, desafio: String, origem: String) {
        val c = try { JSONObject(String(clientDataJSON, Charsets.UTF_8)) } catch (e: Exception) { throw Recusa("clientDataJSON inválido") }
        if (c.optString("type") != tipo) throw Recusa("tipo errado")
        if (!MessageDigest.isEqual(c.optString("challenge").toByteArray(), desafio.toByteArray())) throw Recusa("desafio errado")
        if (c.optString("origin") != origem) throw Recusa("origem errada")
        if (c.optBoolean("crossOrigin", false)) throw Recusa("crossOrigin")
    }

    private fun conferirAuthData(a: ByteArray, rpId: String, cadastro: Boolean): Int {
        if (a.size < 37) throw Recusa("authData curto")
        if (!MessageDigest.isEqual(a.copyOfRange(0, 32), sha256(rpId.toByteArray()))) throw Recusa("RP ID errado")
        val f = a[32].toInt() and 0xff
        if (f and UP == 0) throw Recusa("sem presença")
        if (f and UV == 0) throw Recusa("sem verificação do usuário")
        if (cadastro && f and AT == 0) throw Recusa("sem chave no authData")
        return f
    }

    private fun contador(a: ByteArray): Long =
        ((a[33].toLong() and 0xff) shl 24) or ((a[34].toLong() and 0xff) shl 16) or ((a[35].toLong() and 0xff) shl 8) or (a[36].toLong() and 0xff)

    // ---------------------------------------------------------------- cadastro

    /**
     * Confere o cadastro e devolve a [Chave] para guardar. [idB64] = rawId; [chavePublicaB64] = SPKI DER; os dois
     * campos do navegador em base64url.
     */
    fun cadastrar(idB64: String, chavePublicaB64: String, alg: Int, authDataB64: String, clientDataB64: String,
                  desafio: String, rpId: String, origem: String, nome: String, agora: Long): Chave {
        if (alg != -7) throw Recusa("algoritmo não suportado (só ES256)")
        val id = deB64(idB64)
        if (id.isEmpty() || id.size > 1023) throw Recusa("id inválido")
        val a = deB64(authDataB64)
        conferirCliente(deB64(clientDataB64), "webauthn.create", desafio, origem)
        conferirAuthData(a, rpId, cadastro = true)
        // authData: rpIdHash(32) flags(1) contador(4) aaguid(16) tamanho do id(2) id(L) chave COSE(...)
        if (a.size < 55) throw Recusa("authData sem credencial")
        val l = ((a[53].toInt() and 0xff) shl 8) or (a[54].toInt() and 0xff)
        if (a.size < 55 + l || !MessageDigest.isEqual(a.copyOfRange(55, 55 + l), id)) throw Recusa("id não confere com o authData")
        val spki = deB64(chavePublicaB64)
        chaveEc(spki)   // lê agora: chave quebrada recusa no cadastro, não na hora de entrar
        return Chave(b64(id), b64(spki), nome.take(40).ifBlank { "Celular" }, agora, contador(a))
    }

    private fun chaveEc(spki: ByteArray): PublicKey {
        val k = try { KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(spki)) } catch (e: Exception) { throw Recusa("chave pública inválida") }
        if (k !is ECPublicKey || k.params.curve.field.fieldSize != 256) throw Recusa("só P-256")
        return k
    }

    // ---------------------------------------------------------------- entrada

    /** Confere a entrada; devolve o contador novo para gravar. Lança [Recusa] em qualquer erro. */
    fun conferirEntrada(chave: Chave, authDataB64: String, clientDataB64: String, assinaturaB64: String,
                        desafio: String, rpId: String, origem: String): Long {
        val a = deB64(authDataB64)
        val cliente = deB64(clientDataB64)
        conferirCliente(cliente, "webauthn.get", desafio, origem)
        conferirAuthData(a, rpId, cadastro = false)
        val ok = try {
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(chaveEc(deB64(chave.chavePublica)))
                update(a); update(sha256(cliente))
                verify(deB64(assinaturaB64))
            }
        } catch (e: Recusa) { throw e } catch (e: Exception) { false }
        if (!ok) throw Recusa("assinatura não confere")
        val novo = contador(a)
        // Contador zero = o celular não usa (passkeys sincronizadas do Google e da Apple). Se usa, só pode subir:
        // descer é sinal de chave copiada.
        if ((novo != 0L || chave.contador != 0L) && novo <= chave.contador) throw Recusa("contador não subiu")
        return novo
    }

    // ---------------------------------------------------------------- guardar (JSON nas prefs do painel)

    fun paraJson(l: List<Chave>): String = JSONArray().apply {
        for (c in l) put(JSONObject().put("id", c.id).put("pk", c.chavePublica).put("nome", c.nome)
            .put("criada", c.criada).put("contador", c.contador).put("usada", c.usadaEm))
    }.toString()

    fun deJson(s: String?): List<Chave> = try {
        val a = JSONArray(s ?: "[]")
        (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let {
            Chave(it.optString("id"), it.optString("pk"), it.optString("nome"), it.optLong("criada"), it.optLong("contador"), it.optLong("usada"))
        } }.filter { it.id.isNotEmpty() && it.chavePublica.isNotEmpty() }
    } catch (e: Exception) { emptyList() }
}

/** Desafios em aberto (uso único, [ChaveAcesso.DESAFIO_MS]). Poucos de cada vez: quem pede demais tira os mais velhos. */
class DesafiosChave(private val relogio: () -> Long, private val maximo: Int = 16) {
    private val mapa = LinkedHashMap<String, Long>()

    @Synchronized fun novo(): String {
        limpar()
        while (mapa.size >= maximo) mapa.remove(mapa.keys.first())
        return ChaveAcesso.novoDesafio().also { mapa[it] = relogio() + ChaveAcesso.DESAFIO_MS }
    }

    /** O desafio existe e está no prazo? Gasta na hora (não vale de novo). */
    @Synchronized fun usar(d: String?): Boolean {
        if (d == null) return false
        val vence = mapa.remove(d) ?: return false
        return relogio() <= vence
    }

    private fun limpar() { val t = relogio(); mapa.values.removeAll { it < t } }
}
