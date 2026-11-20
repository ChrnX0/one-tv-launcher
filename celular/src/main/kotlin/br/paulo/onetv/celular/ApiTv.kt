package br.paulo.onetv.celular

import android.content.Context
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Conversa direta com o painel da TV, sem a página (estudo global 20/11/2026, ideias 14-16): o controle nativo, a
 * notificação e o bloco das Configurações rápidas mandam teclas por aqui.
 *
 * Segurança igual à do WebView ([PainelActivity]): só o https de casa com o certificado que veio no QR ([Pareamento.confere]),
 * o mesmo cookie de sessão que o painel ganhou ao entrar com o PIN, e os cabeçalhos que o painel exige num POST
 * (Origin igual ao próprio endereço e X-Painel: 1). Sem cookie, a TV responde 401 e o app abre o painel para entrar.
 * Nunca roda na thread da tela: quem chama usa [emSegundoPlano].
 */
object ApiTv {
    /** Última TV usada no controle: a notificação e o bloco rápido falam com ela. */
    fun ultima(ctx: Context): Pareamento.Tv? =
        Tvs.porChave(ctx, ctx.getSharedPreferences("controle", Context.MODE_PRIVATE).getString("ultima", null))

    fun lembrar(ctx: Context, tv: Pareamento.Tv) =
        ctx.getSharedPreferences("controle", Context.MODE_PRIVATE).edit().putString("ultima", Pareamento.chave(tv)).apply()

    /** O controle nativo só existe com a TV pareada pelo QR (certificado fixado). Sem isso, o painel com o PIN. */
    fun podeUsar(tv: Pareamento.Tv): Boolean = tv.fp.isNotEmpty()

    class Resposta(val codigo: Int, val json: JSONObject?)

    /** Chamada bloqueante. Código 0 = não chegou na TV (desligada, fora de casa, certificado diferente). */
    fun chamar(tv: Pareamento.Tv, metodo: String, caminho: String, corpo: JSONObject? = null): Resposta {
        if (!podeUsar(tv)) return Resposta(0, null)
        val base = "https://${tv.ip}:${Pareamento.PORTA_HTTPS}"
        return try {
            val c = URL(base + caminho).openConnection() as HttpsURLConnection
            val fixo = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<X509Certificate>, tipo: String) = throw java.security.cert.CertificateException()
                override fun checkServerTrusted(chain: Array<X509Certificate>, tipo: String) {
                    if (chain.isEmpty() || !Pareamento.confere(tv, chain[0].encoded)) throw java.security.cert.CertificateException("não é o certificado da TV")
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            c.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(fixo), null) }.socketFactory
            c.hostnameVerifier = javax.net.ssl.HostnameVerifier { h, _ -> h == tv.ip }   // o certificado já foi fixado
            c.instanceFollowRedirects = false
            c.connectTimeout = 4_000; c.readTimeout = 8_000
            c.requestMethod = metodo
            CookieManager.getInstance().getCookie("$base/")?.let { c.setRequestProperty("Cookie", it) }
            if (metodo == "POST") {
                c.setRequestProperty("Origin", base); c.setRequestProperty("X-Painel", "1")
                c.setRequestProperty("Content-Type", "application/json")
                c.doOutput = true
                c.outputStream.use { it.write((corpo ?: JSONObject()).toString().toByteArray()) }
            }
            try {
                val cod = c.responseCode
                val txt = runCatching { (if (cod in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } }.getOrNull()
                Resposta(cod, txt?.let { runCatching { JSONObject(it) }.getOrNull() })
            } finally { c.disconnect() }
        } catch (_: Exception) { Resposta(0, null) }
    }

    fun tecla(tv: Pareamento.Tv, nome: String): Resposta = chamar(tv, "POST", "/api/tela/tecla", JSONObject().put("tecla", nome))
    fun texto(tv: Pareamento.Tv, t: String): Resposta = chamar(tv, "POST", "/api/tela/texto", JSONObject().put("texto", t))
    fun estado(tv: Pareamento.Tv): Resposta = chamar(tv, "GET", "/api/estado")

    fun emSegundoPlano(bloco: () -> Unit) = Thread(bloco, "ApiTv").start()
}
