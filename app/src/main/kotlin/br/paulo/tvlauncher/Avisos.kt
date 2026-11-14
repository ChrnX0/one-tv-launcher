package br.paulo.tvlauncher

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/**
 * Avisos no celular (12/11/2026, ideia 3). O painel instalado na tela inicial do celular (PWA) se inscreve para
 * receber notificação; a TV manda, cifrada, pelo serviço de push do navegador ([WebPush]). Sem servidor nosso.
 *
 * - Inscrever exige HTTPS: no painel pelo endereço .ts.net do Tailscale (porta 8443). Depois de inscrito, o aviso chega
 *   em qualquer rede, porque quem entrega é o serviço de push (no Android, o do Google).
 * - A chave que assina (VAPID) nasce no AndroidKeyStore e nunca sai de lá.
 * - Inscrições ficam nas prefs "avisos_celular", FORA do backup: o endereço de push é como um número de telefone.
 * - [enviar] nunca trava quem chama: roda numa thread, com 1 envio por tipo a cada [INTERVALO_MIN_MS] (menos lembrete e
 *   pedido de ajuda, que são pontuais).
 */
object Avisos {
    private const val TAG = "TvLauncher.Avisos"
    private const val PREFS = "avisos_celular"
    private const val ALIAS = "vapid_avisos"
    const val MAX_INSCRICOES = 8
    private const val INTERVALO_MIN_MS = 10 * 60_000L
    /** "sub" do VAPID: endereço público do projeto (o serviço de push usa para contato). Nada pessoal. */
    private const val CONTATO = "https://github.com/ChrnX0/one-tv-launcher"

    enum class Tipo(val rotulo: String, val padrao: Boolean, val semLimite: Boolean = false) {
        LEMBRETE("Lembretes da grade", true, semLimite = true),
        ALARME_PERDIDO("Despertador que não tocou", true),
        CHUVA("Aviso de chuva", false),
        ERRO_NOVO("Erro novo no launcher", true),
        TV_MADRUGADA("TV ligada de madrugada", true),
        VERSAO_NOVA("Versão nova instalada", false),
        PRESENCA("TV desligada porque todos saíram", true),
        AJUDA("Pedido de ajuda (modo simples)", true, semLimite = true),
        DESEMPENHO("Launcher ficou mais lento", true),
        INTERNET_VOLTOU("Internet voltou (quanto ficou fora)", true),
        ARMAZENAMENTO("Armazenamento da TV enchendo", true),
    }

    data class Inscricao(val endpoint: String, val p256dh: String, val auth: String, val nome: String, val desde: Long)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun inscricoes(ctx: Context): List<Inscricao> {
        val a = runCatching { JSONArray(prefs(ctx).getString("inscricoes", "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            Inscricao(o.optString("endpoint"), o.optString("p256dh"), o.optString("auth"), o.optString("nome"), o.optLong("desde"))
                .takeIf { WebPush.hostAceito(it.endpoint) }
        }
    }

    private fun gravar(ctx: Context, l: List<Inscricao>) = prefs(ctx).edit().putString("inscricoes", JSONArray().apply {
        l.forEach { put(JSONObject().put("endpoint", it.endpoint).put("p256dh", it.p256dh).put("auth", it.auth)
            .put("nome", it.nome).put("desde", it.desde)) }
    }.toString()).apply()

    /** Nova inscrição vinda do painel. Erro em texto, ou null se deu certo. Mesmo endpoint = atualiza. */
    fun inscrever(ctx: Context, o: JSONObject, nome: String): String? = synchronized(this) {
        val endpoint = o.optString("endpoint")
        if (!WebPush.hostAceito(endpoint)) return "serviço de push desconhecido"
        val chaves = o.optJSONObject("keys") ?: return "faltam as chaves"
        val p256 = chaves.optString("p256dh"); val auth = chaves.optString("auth")
        val ok = runCatching { WebPush.deCru(WebPush.deB64url(p256)); WebPush.deB64url(auth).size >= 16 }.getOrDefault(false)
        if (!ok) return "chaves inválidas"
        val l = inscricoes(ctx).filterNot { it.endpoint == endpoint }
        if (l.size >= MAX_INSCRICOES) return "já há $MAX_INSCRICOES aparelhos; remova um antes"
        gravar(ctx, l + Inscricao(endpoint, p256, auth, nome.take(40).ifBlank { "celular" }, System.currentTimeMillis()))
        null
    }

    fun remover(ctx: Context, endpoint: String): Boolean = synchronized(this) {
        val l = inscricoes(ctx)
        val nova = l.filterNot { it.endpoint == endpoint }
        gravar(ctx, nova)
        nova.size != l.size
    }

    fun ligado(ctx: Context, t: Tipo): Boolean = prefs(ctx).getBoolean("tipo_${t.name}", t.padrao)
    fun definir(ctx: Context, t: Tipo, sim: Boolean) = prefs(ctx).edit().putBoolean("tipo_${t.name}", sim).apply()

    /** A chave pública VAPID (65 bytes em base64url), que o navegador pede ao se inscrever. Cria na 1ª vez. */
    fun chavePublica(): String = WebPush.b64url(WebPush.cru(chave().second))

    private fun chave(): Pair<PrivateKey, java.security.PublicKey> {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (!ks.containsAlias(ALIAS)) {
            KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore").apply {
                initialize(KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(ECGenParameterSpec("secp256r1"))
                    .setDigests(KeyProperties.DIGEST_SHA256).build())
            }.generateKeyPair()
        }
        val e = ks.getEntry(ALIAS, null) as KeyStore.PrivateKeyEntry
        return e.privateKey to e.certificate.publicKey
    }

    /** Manda o aviso a todos os aparelhos inscritos (se o tipo estiver ligado). Qualquer thread; não espera. */
    fun enviar(ctx: Context, tipo: Tipo, titulo: String, texto: String) {
        val app = ctx.applicationContext
        if (!ligado(app, tipo)) return
        val agora = System.currentTimeMillis()
        if (!tipo.semLimite) {
            val ultimo = prefs(app).getLong("ultimo_${tipo.name}", 0L)
            if (agora - ultimo in 0 until INTERVALO_MIN_MS) return
        }
        val lista = inscricoes(app)
        if (lista.isEmpty()) return
        prefs(app).edit().putLong("ultimo_${tipo.name}", agora).apply()
        Fundo.rodar("avisos") { lista.forEach { enviarUm(app, it, tipo, titulo, texto) } }
    }

    /**
     * Um envio. 404/410 = o celular cancelou a inscrição (desinstalou o painel, limpou o navegador): sai da lista.
     * Devolve o código HTTP (ou -1 em erro de rede).
     */
    fun enviarUm(app: Context, i: Inscricao, tipo: Tipo, titulo: String, texto: String): Int = try {
        val corpoClaro = JSONObject().put("titulo", titulo.take(80)).put("texto", texto.take(240)).put("tipo", tipo.name)
            .put("tv", runCatching { android.provider.Settings.Global.getString(app.contentResolver, "device_name") }.getOrNull() ?: "TV").toString().toByteArray()
        val efemera = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
        val sal = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val corpo = WebPush.cifrar(corpoClaro, WebPush.deB64url(i.p256dh), WebPush.deB64url(i.auth), efemera, sal)
        val (privada, publica) = chave()
        val auth = WebPush.autorizacao(i.endpoint, WebPush.cru(publica), System.currentTimeMillis() / 1000, CONTATO) { dado ->
            Signature.getInstance("SHA256withECDSA").apply { initSign(privada); update(dado) }.sign()
        }
        val c = URL(i.endpoint).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000; c.requestMethod = "POST"; c.doOutput = true
        c.instanceFollowRedirects = false
        c.setRequestProperty("Content-Type", "application/octet-stream")
        c.setRequestProperty("Content-Encoding", "aes128gcm")
        c.setRequestProperty("TTL", if (tipo == Tipo.LEMBRETE) "600" else "86400")
        c.setRequestProperty("Urgency", if (tipo == Tipo.LEMBRETE || tipo == Tipo.AJUDA) "high" else "normal")
        c.setRequestProperty("Authorization", auth)
        c.outputStream.use { it.write(corpo) }
        val codigo = c.responseCode
        c.disconnect()
        if (codigo == 404 || codigo == 410) remover(app, i.endpoint)
        Log.i(TAG, "${tipo.name} -> ${WebPush.audiencia(i.endpoint)}: $codigo")
        codigo
    } catch (e: Exception) {
        Log.w(TAG, "envio falhou: ${e.javaClass.simpleName}"); -1
    }

    /** Para o painel: chave pública, aparelhos e tipos. O endpoint inteiro não sai (só o serviço e o fim, para remover). */
    fun json(ctx: Context): JSONObject = JSONObject()
        .put("chave", runCatching { chavePublica() }.getOrNull() ?: JSONObject.NULL)
        .put("aparelhos", JSONArray().apply {
            inscricoes(ctx).forEach { put(JSONObject().put("nome", it.nome).put("servico", WebPush.audiencia(it.endpoint))
                .put("id", it.endpoint.takeLast(16)).put("desde", it.desde)) }
        })
        .put("tipos", JSONArray().apply {
            Tipo.values().forEach { put(JSONObject().put("tipo", it.name).put("rotulo", it.rotulo).put("ligado", ligado(ctx, it))) }
        })

    /** Remove pelo "id" que o painel conhece (os últimos 16 caracteres do endpoint). */
    fun removerPorId(ctx: Context, id: String): Boolean =
        inscricoes(ctx).firstOrNull { it.endpoint.takeLast(16) == id }?.let { remover(ctx, it.endpoint) } ?: false

    /** Teste do painel: manda "Teste" para todos agora, e diz quantos o serviço aceitou. Bloqueia: chamar fora da tela. */
    fun testar(ctx: Context): String {
        val l = inscricoes(ctx)
        if (l.isEmpty()) return "nenhum aparelho inscrito"
        val r = l.map { enviarUm(ctx.applicationContext, it, Tipo.LEMBRETE, "Teste", "Os avisos da TV chegam aqui.") }
        return "${r.count { it in 200..299 }} de ${l.size} aceitos"
    }
}
