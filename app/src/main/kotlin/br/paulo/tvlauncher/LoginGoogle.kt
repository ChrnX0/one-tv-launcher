package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * Login do conector (MCP) pela conta Google (09/11/2026, pedido do dono: "a conta Google que coincida com a do perfil
 * rodando no dispositivo"; aceita a conta de QUALQUER perfil da TV).
 *
 * Como funciona, em português: é o mesmo login que apps de TV usam ("OAuth 2.0 para TV e aparelhos de entrada
 * limitada", https://developers.google.com/identity/protocols/oauth2/limited-input-device).
 *  1. A página de login do conector pede ao Google um código curto (device/code).
 *  2. A pessoa abre google.com/device no celular, entra com a conta Google e digita o código.
 *  3. A TV pergunta ao Google, de tempos em tempos, se já deu certo (token). Quando dá, pergunta QUAL conta entrou
 *     (userinfo). Tudo isso a TV fala direto com o Google por HTTPS: ninguém no meio consegue mentir a conta.
 *  4. Se o e-mail é de uma conta Google de algum perfil da TV, o login passa. Senão, recusa.
 *
 * Precisa de um "cliente OAuth" do tipo "TVs e aparelhos de entrada limitada" criado pelo dono no Google Cloud
 * (id + segredo, colados no painel > aba Claude). Sem ele, o login continua sendo o PIN do painel.
 */
object LoginGoogle {
    private const val TAG = "TvLauncher.LoginGoogle"
    private const val PREFS = "mcp_seguro"
    private const val MAX_SESSOES = 5
    private val aleatorio = SecureRandom()

    enum class Estado { ESPERANDO, OK, RECUSADO, ERRO }

    /** Uma tentativa de login. [pedido] guarda os campos do /authorize original (client_id, redirect_uri, state...). */
    class Sessao(val id: String, val pedido: Map<String, String>, val codigoUsuario: String, val urlVerificacao: String, val expira: Long) {
        @Volatile var estado = Estado.ESPERANDO
        @Volatile var email: String? = null
        @Volatile var mensagem: String = ""
        @Volatile var usada = false
    }

    private val sessoes = ConcurrentHashMap<String, Sessao>()

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    fun clienteId(ctx: Context): String? = prefs(ctx).getString("google_id", null)
    private fun segredo(ctx: Context): String? = prefs(ctx).getString("google_segredo", null)
    fun configurado(ctx: Context): Boolean = !clienteId(ctx).isNullOrBlank() && !segredo(ctx).isNullOrBlank()

    /** Formato do id de cliente do Google: "<números>-<letras>.apps.googleusercontent.com". */
    fun idValido(id: String): Boolean = id.length in 20..200 && id.endsWith(".apps.googleusercontent.com") &&
        id.all { it.isLetterOrDigit() || it in "-._" }
    fun segredoValido(s: String): Boolean = s.length in 10..100 && s.all { it.isLetterOrDigit() || it in "-_" }

    /** Grava (ou apaga, com null) o cliente OAuth. O segredo de cliente de TV não é secreto de verdade (o Google sabe
     *  que fica dentro do aparelho), mas fica fora do backup como o resto de mcp_seguro. */
    fun definir(ctx: Context, id: String?, segredo: String?) {
        prefs(ctx).edit().apply {
            if (id == null || segredo == null) { remove("google_id"); remove("google_segredo") }
            else { putString("google_id", id); putString("google_segredo", segredo) }
        }.apply()
        sessoes.clear()
    }

    /** Instantes dos últimos inícios de login (para o ritmo). */
    private val inicios = ArrayDeque<Long>()

    /** Até [max] inícios por [janelaMs]; registra este se couber. Pura (com a fila de fora) para o teste JVM. */
    internal fun cabeNoRitmo(fila: ArrayDeque<Long>, agora: Long, max: Int = 6, janelaMs: Long = 60_000): Boolean {
        while (fila.isNotEmpty() && agora - fila.first() >= janelaMs) fila.removeFirst()
        if (fila.size >= max) return false
        fila.addLast(agora)
        return true
    }

    fun sessao(id: String?): Sessao? = id?.let { sessoes[it] }?.takeIf { it.expira > System.currentTimeMillis() }

    /** Pede o código ao Google e começa a vigiar. Lança Exception com texto para a página se não der. */
    fun iniciar(ctx: Context, pedido: Map<String, String>): Sessao {
        val app = ctx.applicationContext
        val agora = System.currentTimeMillis()
        val id = clienteId(app) ?: throw IllegalStateException("login Google não configurado")
        // Ritmo ANTES de chamar o Google (revisão da auditoria): o /authorize é aberto pela internet; sem isto, cada GET
        // virava uma chamada ao Google + uma thread de consulta, e gastava a cota do cliente OAuth do dono.
        synchronized(inicios) {
            if (!cabeNoRitmo(inicios, agora)) throw IllegalStateException("muitos logins seguidos; espere um minuto")
        }
        val r = postar("https://oauth2.googleapis.com/device/code", mapOf("client_id" to id, "scope" to "email profile"))
        val s = Sessao(
            ByteArray(18).also { aleatorio.nextBytes(it) }.joinToString("") { "%02x".format(it) },
            pedido, r.getString("user_code"), r.optString("verification_url", "https://www.google.com/device"),
            agora + r.optLong("expires_in", 900).coerceAtMost(1800) * 1000,
        )
        // mcp-4: lotado, abre vaga em vez de recusar (antes, 5 GETs anônimos no /authorize travavam o login por 15 min).
        synchronized(sessoes) { abrirVaga(sessoes, agora); sessoes[s.id] = s }
        val deviceCode = r.getString("device_code")
        var intervalo = r.optLong("interval", 5).coerceIn(2, 30) * 1000
        Thread({
            while (s.estado == Estado.ESPERANDO && System.currentTimeMillis() < s.expira) {
                try { Thread.sleep(intervalo) } catch (_: InterruptedException) { return@Thread }
                try {
                    val t = postar("https://oauth2.googleapis.com/token", mapOf(
                        "client_id" to id, "client_secret" to (segredo(app) ?: ""), "device_code" to deviceCode,
                        "grant_type" to "urn:ietf:params:oauth:grant-type:device_code"), aceitarErro = true)
                    when (t.optString("error")) {
                        "" -> { concluir(app, s, t.getString("access_token")); return@Thread }
                        "authorization_pending" -> {}
                        "slow_down" -> intervalo += 5000
                        "access_denied" -> { s.estado = Estado.RECUSADO; s.mensagem = "o login foi negado no Google"; return@Thread }
                        else -> { s.estado = Estado.ERRO; s.mensagem = "Google respondeu: ${t.optString("error").take(60)}"; return@Thread }
                    }
                } catch (e: Exception) { Log.w(TAG, "consulta ao Google falhou: ${e.javaClass.simpleName}") }
            }
            if (s.estado == Estado.ESPERANDO) { s.estado = Estado.ERRO; s.mensagem = "o código venceu; comece de novo" }
        }, "LoginGoogle").apply { isDaemon = true; start() }
        return s
    }

    /**
     * Garante lugar para mais uma sessão em [sessoes] (auditoria 10/11/2026, mcp-4). Tira as vencidas e as já usadas.
     * Se ainda estiver cheio, despeja uma: primeiro as encerradas (recusadas/erro), depois a ESPERANDO mais antiga
     * (marcada ERRO, para a thread dela parar de perguntar ao Google), e só em último caso uma OK ainda não usada.
     * Antes, o limite recusava a sessão NOVA: qualquer um pela internet, com 5 pedidos, impedia o dono de entrar.
     * Puro (teste JVM em LoginGoogleTest).
     */
    internal fun abrirVaga(sessoes: MutableMap<String, Sessao>, agora: Long, max: Int = MAX_SESSOES) {
        sessoes.values.removeIf { it.expira < agora || it.usada }
        while (sessoes.size >= max) {
            val sai = sessoes.values.minWithOrNull(compareBy<Sessao>({
                when (it.estado) { Estado.RECUSADO, Estado.ERRO -> 0; Estado.ESPERANDO -> 1; Estado.OK -> 2 }
            }, { it.expira })) ?: break
            if (sai.estado == Estado.ESPERANDO) { sai.estado = Estado.ERRO; sai.mensagem = "outro login começou; comece de novo" }
            sessoes.remove(sai.id)
        }
    }

    private fun concluir(app: Context, s: Sessao, acesso: String) {
        val u = obter("https://openidconnect.googleapis.com/v1/userinfo", acesso)
        val email = u.optString("email").lowercase()
        s.email = email
        if (email.isEmpty() || !u.optBoolean("email_verified", false)) { s.estado = Estado.RECUSADO; s.mensagem = "o Google não confirmou o e-mail"; return }
        val daTv = contasDaTv(app)
        if (email in daTv) { s.estado = Estado.OK; Log.i(TAG, "login Google ok") }
        else { s.estado = Estado.RECUSADO; s.mensagem = "a conta $email não está em nenhum perfil desta TV" }
    }

    /** As contas Google de TODOS os perfis da TV: as que o launcher vê (perfil atual) + as do `dumpsys account` pelo
     *  shell próprio (cada perfil do Google TV é um usuário do Android; o shell enxerga todos). */
    fun contasDaTv(ctx: Context): Set<String> {
        val doApp = Perfil.contas(ctx).map { it.lowercase() }
        val doShell = GuardiaoShizuku.comando(ctx, "dumpsys account")?.let { contasDoDumpsys(it) } ?: emptySet()
        return (doApp + doShell).toSet()
    }

    /** Lê "Account {name=fulano@gmail.com, type=com.google}" da saída do `dumpsys account` (puro, teste JVM). */
    fun contasDoDumpsys(texto: String): Set<String> =
        Regex("""Account \{name=([^,\s]+@[^,\s]+), type=com\.google\}""").findAll(texto).map { it.groupValues[1].lowercase() }.toSet()

    // ---------------------------------------------------------------- HTTP com o Google

    private fun postar(url: String, campos: Map<String, String>, aceitarErro: Boolean = false): JSONObject {
        val corpo = campos.entries.joinToString("&") { "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}" }.toByteArray()
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"; connectTimeout = 10_000; readTimeout = 10_000; doOutput = true
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            c.outputStream.use { it.write(corpo) }
            val ok = c.responseCode in 200..299
            val texto = (if (ok) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText().take(64_000) } ?: "{}"
            if (!ok && !aceitarErro) throw IllegalStateException("o Google recusou (${c.responseCode}): confira o id e o segredo do cliente")
            return JSONObject(texto)
        } finally { c.disconnect() }
    }

    private fun obter(url: String, acesso: String): JSONObject {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000; readTimeout = 10_000; setRequestProperty("Authorization", "Bearer $acesso")
        }
        try {
            if (c.responseCode !in 200..299) throw IllegalStateException("userinfo ${c.responseCode}")
            return JSONObject(c.inputStream.bufferedReader().use { it.readText().take(64_000) })
        } finally { c.disconnect() }
    }
}
