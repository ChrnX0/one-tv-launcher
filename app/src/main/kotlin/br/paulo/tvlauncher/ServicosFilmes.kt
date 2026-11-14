package br.paulo.tvlauncher

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Serviços de filmes e séries (14/11/2026, pedido do dono: "faz nosso launcher se integrar a todos esses serviços e
 * facilita o login e comunicação, na TV e no painel web"): TMDB, Trakt, Simkl, MDBList e OpenSubtitles.
 *
 * Só lógica pura (sem Android), testada em ServicosFilmesTest: endereços, cabeçalhos, leitura das respostas e a regra de
 * renovar o token. Quem fala com a rede e grava no disco é o [ServicosContas].
 *
 * Chaves: a chave/ID do APLICATIVO é uma só, do projeto, e entra no build (pessoal.properties no PC; secrets do GitHub
 * no release.yml), nunca no git. O OpenSubtitles proíbe app que pede a chave ao usuário (opensubtitles.stoplight.io,
 * "don't allow users to insert their API key"). Sem a chave no build, o serviço some da tela. O que a PESSOA faz é só
 * entrar na conta dela: Trakt e Simkl por código no celular, OpenSubtitles com usuário e senha (opcional), MDBList com a
 * chave pessoal da conta (a API deles é por usuário).
 */
object ServicosFilmes {
    enum class Servico(val id: String, val nome: String) {
        TRAKT("trakt", "Trakt"),
        SIMKL("simkl", "Simkl"),
        MDBLIST("mdblist", "MDBList"),
        TMDB("tmdb", "TMDB"),
        OPENSUBTITLES("opensubtitles", "OpenSubtitles");

        companion object { fun de(id: String?): Servico? = entries.firstOrNull { it.id == id } }
    }

    /** As chaves do aplicativo (BuildConfig). Vazio = o serviço não existe neste build. */
    data class ChavesApp(
        val tmdb: String = "", val traktId: String = "", val traktSegredo: String = "",
        val simklId: String = "", val openSubtitles: String = "", val mdblist: String = "",
    ) {
        fun disponivel(s: Servico): Boolean = when (s) {
            Servico.TRAKT -> traktId.isNotBlank() && traktSegredo.isNotBlank()
            Servico.SIMKL -> simklId.isNotBlank()
            Servico.TMDB -> tmdb.isNotBlank()
            Servico.OPENSUBTITLES -> openSubtitles.isNotBlank()
            // A do app (do desenvolvedor) quando o build tem; sem ela, a pessoa pode pôr a dela (mdblist.com/preferences).
            Servico.MDBLIST -> true
        }
    }

    const val APP_NOME = "OneTVLauncher"

    /** "OneTVLauncher v1.0 (457800)": o OpenSubtitles e o Trakt pedem o nome e a versão do app no User-Agent. */
    fun agente(versao: Int): String = "$APP_NOME v1.0 ($versao)"

    private fun cod(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

    // ================================================================ login por código (Trakt e Simkl)

    /** O código que a pessoa digita no celular. [intervaloS] = de quanto em quanto tempo perguntar se já entrou. */
    data class Codigo(val codigoUsuario: String, val url: String, val codigoAparelho: String, val expiraEmS: Int, val intervaloS: Int) {
        /** Endereço já com o código, para o QR (o celular abre a página com o código preenchido quando o site aceita). */
        val urlQr: String get() = when {
            url.contains("trakt.tv") -> url.trimEnd('/') + "/" + codigoUsuario
            url.contains("simkl.com") -> url.trimEnd('/') + "/" + codigoUsuario
            else -> url
        }
    }

    /** Resultado de uma pergunta "já entrou?". */
    sealed class Espera {
        object Pendente : Espera()
        /** Pediram para perguntar mais devagar (Trakt 429): soma 5 s ao intervalo. */
        object MaisDevagar : Espera()
        class Token(val acesso: String, val renovar: String?, val expiraEmMs: Long?) : Espera()
        /** O código venceu, foi recusado ou já foi usado: pedir outro. [motivo] curto, para a tela. */
        class Fim(val motivo: Motivo) : Espera()
    }

    enum class Motivo { VENCEU, RECUSADO, USADO, INVALIDO, ERRO }

    // ---------------------------------------------------------------- Trakt (trakt.docs.apiary.io, Authentication - Devices)

    const val TRAKT_API = "https://api.trakt.tv"
    const val TRAKT_REDIRECT = "urn:ietf:wg:oauth:2.0:oob"

    fun traktCabecalhos(clienteId: String, versao: Int, token: String? = null): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("trakt-api-version", "2")
        put("trakt-api-key", clienteId)
        put("User-Agent", agente(versao))
        if (token != null) put("Authorization", "Bearer $token")
    }

    fun traktCorpoCodigo(clienteId: String): String = JSONObject().put("client_id", clienteId).toString()

    fun traktCorpoToken(codigoAparelho: String, clienteId: String, segredo: String): String =
        JSONObject().put("code", codigoAparelho).put("client_id", clienteId).put("client_secret", segredo).toString()

    fun traktCorpoRenovar(renovar: String, clienteId: String, segredo: String): String = JSONObject()
        .put("refresh_token", renovar).put("client_id", clienteId).put("client_secret", segredo)
        .put("redirect_uri", TRAKT_REDIRECT).put("grant_type", "refresh_token").toString()

    fun traktCorpoRevogar(token: String, clienteId: String, segredo: String): String =
        JSONObject().put("token", token).put("client_id", clienteId).put("client_secret", segredo).toString()

    /** Resposta de POST /oauth/device/code. */
    fun traktLerCodigo(json: String): Codigo? = runCatching {
        val o = JSONObject(json)
        Codigo(o.getString("user_code"), o.optString("verification_url", "https://trakt.tv/activate"), o.getString("device_code"),
            o.optInt("expires_in", 600), o.optInt("interval", 5).coerceIn(1, 60))
    }.getOrNull()?.takeIf { it.codigoUsuario.isNotBlank() && it.codigoAparelho.isNotBlank() && it.url.startsWith("https://") }

    /**
     * Resposta de POST /oauth/device/token, pelo código HTTP (é assim que o Trakt avisa): 200 entrou; 400 ainda não;
     * 404 código inválido; 409 já usado; 410 venceu; 418 a pessoa recusou; 429 perguntando rápido demais.
     */
    fun traktLerEspera(http: Int, json: String, agoraMs: Long): Espera = when (http) {
        200 -> lerToken(json, agoraMs)?.let { it } ?: Espera.Fim(Motivo.ERRO)
        400 -> Espera.Pendente
        429 -> Espera.MaisDevagar
        404 -> Espera.Fim(Motivo.INVALIDO)
        409 -> Espera.Fim(Motivo.USADO)
        410 -> Espera.Fim(Motivo.VENCEU)
        418 -> Espera.Fim(Motivo.RECUSADO)
        else -> Espera.Fim(Motivo.ERRO)
    }

    /** Token OAuth comum (Trakt): access_token, refresh_token, expires_in (s) e created_at (s, opcional). */
    fun lerToken(json: String, agoraMs: Long): Espera.Token? = runCatching {
        val o = JSONObject(json)
        val acesso = o.getString("access_token").takeIf { it.isNotBlank() } ?: return null
        val exp = o.optLong("expires_in", 0).takeIf { it > 0 }
        val criado = o.optLong("created_at", 0).takeIf { it > 0 }?.times(1000) ?: agoraMs
        Espera.Token(acesso, o.optString("refresh_token").takeIf { it.isNotBlank() && it != "null" }, exp?.let { criado + it * 1000 })
    }.getOrNull()

    /** Nome da conta em GET /users/settings do Trakt: user.username (ou user.name). */
    fun traktLerUsuario(json: String): String? = runCatching {
        val u = JSONObject(json).getJSONObject("user")
        u.optString("username").ifBlank { u.optString("name") }
    }.getOrNull()?.takeIf { it.isNotBlank() }?.take(60)

    // ---------------------------------------------------------------- Simkl (api.simkl.org/api-reference/auth-v1, PIN)

    const val SIMKL_API = "https://api.simkl.com"

    private fun simklParametros(clienteId: String, versao: Int) =
        "client_id=${cod(clienteId)}&app-name=${cod(APP_NOME.lowercase())}&app-version=${cod("1.0.$versao")}"

    fun simklUrlCodigo(clienteId: String, versao: Int): String = "$SIMKL_API/oauth/pin?" + simklParametros(clienteId, versao)

    fun simklUrlEspera(codigoUsuario: String, clienteId: String, versao: Int): String =
        "$SIMKL_API/oauth/pin/${cod(codigoUsuario)}?" + simklParametros(clienteId, versao)

    fun simklUrlUsuario(clienteId: String, versao: Int): String = "$SIMKL_API/users/settings?" + simklParametros(clienteId, versao)

    fun simklCabecalhos(clienteId: String, versao: Int, token: String? = null): Map<String, String> = buildMap {
        put("Content-Type", "application/json")
        put("simkl-api-key", clienteId)
        put("User-Agent", agente(versao))
        if (token != null) put("Authorization", "Bearer $token")
    }

    /** Resposta de GET /oauth/pin: user_code, verification_url (simkl.com/pin), expires_in 900, interval 5. */
    fun simklLerCodigo(json: String): Codigo? = runCatching {
        val o = JSONObject(json)
        if (o.optString("result") != "OK") return null
        val url = o.optString("verification_url").ifBlank { o.optString("verification_uri") }.ifBlank { "https://simkl.com/pin" }
        Codigo(o.getString("user_code"), url, o.optString("device_code"), o.optInt("expires_in", 900), o.optInt("interval", 5).coerceIn(1, 60))
    }.getOrNull()?.takeIf { it.codigoUsuario.isNotBlank() && it.url.startsWith("https://") }

    /**
     * Resposta de GET /oauth/pin/{code}: {"result":"KO"} = ainda não; {"result":"OK","access_token":...} = entrou.
     * ATENÇÃO (doc do Simkl): perguntar por um código que já sumiu devolve um código NOVO (com device_code), não erro:
     * isso quer dizer que o nosso venceu.
     */
    fun simklLerEspera(http: Int, json: String): Espera {
        if (http == 429) return Espera.MaisDevagar
        if (http !in 200..299) return Espera.Fim(if (http == 404) Motivo.VENCEU else Motivo.ERRO)
        val o = runCatching { JSONObject(json) }.getOrNull() ?: return Espera.Fim(Motivo.ERRO)
        val acesso = o.optString("access_token")
        if (o.optString("result") == "OK" && acesso.isNotBlank() && acesso != "null") return Espera.Token(acesso, null, null)   // o do Simkl não vence
        if (o.has("device_code") || o.has("user_code")) return Espera.Fim(Motivo.VENCEU)
        return Espera.Pendente
    }

    /** Nome em users/settings do Simkl: user.name. */
    fun simklLerUsuario(json: String): String? = runCatching { JSONObject(json).getJSONObject("user").optString("name") }
        .getOrNull()?.takeIf { it.isNotBlank() && it != "null" }?.take(60)

    // ---------------------------------------------------------------- MDBList (api.mdblist.com/docs)

    const val MDBLIST_API = "https://api.mdblist.com"
    const val MDBLIST_PREFERENCIAS = "https://mdblist.com/preferences/"

    /** A chave pessoal do MDBList: letras e números, ~25 caracteres. Aceita uma faixa larga, sem espaços. */
    fun mdblistChaveValida(chave: String): Boolean = chave.trim().matches(Regex("[A-Za-z0-9]{16,64}"))

    fun mdblistUrlUsuario(chave: String): String = "$MDBLIST_API/user?apikey=${cod(chave.trim())}"

    /** GET /user: o nome vem em "name" ou "username" (lido com folga: a doc não fixa). */
    fun mdblistLerUsuario(json: String): String? = runCatching {
        val o = JSONObject(json)
        listOf("name", "username", "user_name").map { o.optString(it) }.firstOrNull { it.isNotBlank() && it != "null" }
    }.getOrNull()?.take(60)

    /** Limite de consultas por dia, se a resposta trouxer ("api_requests"). */
    fun mdblistLerLimite(json: String): Int? = runCatching { JSONObject(json).optInt("api_requests", -1) }.getOrNull()?.takeIf { it > 0 }

    // ================================================================ renovação (Trakt)

    /** Folga para renovar antes de vencer: um dia (o trabalho periódico roda de poucas em poucas horas). */
    const val FOLGA_RENOVAR_MS = 24L * 3_600_000

    /** Renova se tem como renovar e falta menos de [FOLGA_RENOVAR_MS] para vencer (ou já venceu). */
    fun precisaRenovar(expiraEmMs: Long?, temRenovar: Boolean, agoraMs: Long): Boolean =
        temRenovar && expiraEmMs != null && expiraEmMs - agoraMs < FOLGA_RENOVAR_MS

    // ================================================================ conta guardada (o que vai cifrado para o disco)

    /** Uma conta conectada. [usuario] é só para mostrar "conectado como"; nunca vai para log. */
    data class Conta(val acesso: String, val renovar: String? = null, val expiraEmMs: Long? = null, val usuario: String = "",
                     val senha: String = "") {
        fun json(): JSONObject = JSONObject().put("acesso", acesso).put("renovar", renovar ?: "").put("expira", expiraEmMs ?: 0L)
            .put("usuario", usuario).put("senha", senha)
    }

    fun contasParaJson(contas: Map<Servico, Conta>): String = JSONObject().apply { for ((s, c) in contas) put(s.id, c.json()) }.toString()

    fun contasDeJson(json: String?): Map<Servico, Conta> {
        val o = runCatching { JSONObject(json ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val saida = LinkedHashMap<Servico, Conta>()
        for (s in Servico.entries) {
            val c = o.optJSONObject(s.id) ?: continue
            val acesso = c.optString("acesso")
            val usuario = c.optString("usuario")
            if (acesso.isBlank() && usuario.isBlank()) continue
            saida[s] = Conta(acesso, c.optString("renovar").takeIf { it.isNotBlank() }, c.optLong("expira", 0).takeIf { it > 0 }, usuario,
                c.optString("senha"))
        }
        return saida
    }
}
