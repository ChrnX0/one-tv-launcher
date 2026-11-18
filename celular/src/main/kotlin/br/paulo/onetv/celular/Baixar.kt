package br.paulo.onetv.celular

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.widget.Toast
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * Enviar e baixar arquivos no painel dentro do app (rodada 3 de depuração, 18/11/2026). O WebView sozinho não abre o
 * seletor de arquivos nem salva downloads: "Instalar APK", "Enviar arquivo", "Restaurar backup" e "Baixar" do painel
 * não faziam nada no celular.
 *
 * Baixar tem dois caminhos:
 *  - `blob:` (backup, OPML): só a página enxerga o blob, então o JS o lê e manda em pedaços base64 por [Ponte];
 *  - endereço da TV (arquivo da aba Arquivos): baixado aqui, com o cookie da sessão, aceitando no https de casa SÓ o
 *    certificado do QR (o mesmo [Pareamento.confere] do WebView). Nada é aceito "por fora" do que o WebView aceita.
 * O arquivo vai para Downloads pelo MediaStore (Android 10+, sem permissão).
 */
object Baixar {
    /** Os tipos do `accept` da página viram tipos MIME para o seletor; extensão sem tipo conhecido abre tudo. */
    fun tiposMime(accept: Array<String>?, porExtensao: (String) -> String?): Array<String> {
        val itens = accept.orEmpty().flatMap { it.split(',') }.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
        if (itens.isEmpty()) return arrayOf("*/*")
        val tipos = itens.map { if ('/' in it) it else porExtensao(it.removePrefix(".")) }
        // Uma extensão sem tipo (ex.: .apks, .xapk) sumiria do seletor: nesse caso mostra tudo.
        if (tipos.any { it == null }) return arrayOf("*/*")
        return tipos.filterNotNull().distinct().toTypedArray()
    }

    /** Nome seguro para Downloads: sem pasta, sem caracteres de controle, nunca vazio. */
    fun nomeSeguro(bruto: String?): String {
        val n = (bruto ?: "").substringAfterLast('/').substringAfterLast('\\')
            .filter { it >= ' ' && it !in "<>:\"|?*" }.trim().trimStart('.').take(120)
        return n.ifEmpty { "arquivo" }
    }

    private fun aviso(ctx: Context, ok: Boolean, nome: String) = Handler(Looper.getMainLooper()).post {
        Toast.makeText(ctx, if (ok) ctx.getString(R.string.cel_baixado, nome) else ctx.getString(R.string.cel_baixar_falhou), Toast.LENGTH_LONG).show()
    }

    /** Cria o arquivo em Downloads (pendente até [fechar]). */
    private fun abrir(ctx: Context, nome: String, mime: String?): Pair<Uri, OutputStream>? {
        val v = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, nome)
            put(MediaStore.Downloads.MIME_TYPE, mime?.takeIf { '/' in it } ?: "application/octet-stream")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v) ?: return null
        val s = ctx.contentResolver.openOutputStream(uri) ?: run { ctx.contentResolver.delete(uri, null, null); return null }
        return uri to s
    }

    private fun fechar(ctx: Context, uri: Uri, s: OutputStream, ok: Boolean) {
        try { s.close() } catch (_: Exception) {}
        if (ok) ctx.contentResolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        else try { ctx.contentResolver.delete(uri, null, null) } catch (_: Exception) {}
    }

    /** Arquivo servido pela TV: baixa aqui, em segundo plano. */
    fun direto(ctx: Context, tv: Pareamento.Tv, url: String, nome: String, mime: String?) {
        val app = ctx.applicationContext
        Thread({
            var ok = false
            var destino: Pair<Uri, OutputStream>? = null
            try {
                val u = URL(url)
                val c = u.openConnection() as HttpURLConnection
                if (c is HttpsURLConnection && u.host == tv.ip) {
                    // https de casa: certificado próprio da TV. Aceita só o do QR, como o WebView (onReceivedSslError).
                    val fixo = object : X509TrustManager {
                        override fun checkClientTrusted(chain: Array<X509Certificate>, tipo: String) = throw java.security.cert.CertificateException()
                        override fun checkServerTrusted(chain: Array<X509Certificate>, tipo: String) {
                            if (chain.isEmpty() || !Pareamento.confere(tv, chain[0].encoded)) throw java.security.cert.CertificateException("não é o certificado da TV")
                        }
                        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                    }
                    c.sslSocketFactory = SSLContext.getInstance("TLS").apply { init(null, arrayOf(fixo), null) }.socketFactory
                    c.hostnameVerifier = javax.net.ssl.HostnameVerifier { h, _ -> h == tv.ip }   // o certificado já foi fixado
                }
                CookieManager.getInstance().getCookie(url)?.let { c.setRequestProperty("Cookie", it) }
                c.connectTimeout = 15_000; c.readTimeout = 60_000
                if (c.responseCode == 200) {
                    destino = abrir(app, nome, mime ?: c.contentType)
                    destino?.let { (_, s) -> c.inputStream.use { it.copyTo(s, 64 * 1024) }; ok = true }
                }
                c.disconnect()
            } catch (_: Exception) { ok = false }
            destino?.let { (uri, s) -> fechar(app, uri, s, ok) }
            aviso(app, ok, nome)
        }, "Baixar").start()
    }

    /** JS que lê um `blob:` da página e o passa em pedaços para [Ponte]. */
    fun jsDoBlob(url: String, nome: String, mime: String?): String {
        fun q(s: String) = org.json.JSONObject.quote(s)
        return """(async function(){var id=-1;try{var r=await fetch(${q(url)});var b=await r.blob();
id=OneTvBaixar.inicio(window.__oneTvNome||${q(nome)},b.type||${q(mime ?: "")});window.__oneTvNome=null;if(id<0)return;
var t=393216;for(var i=0;i<b.size;i+=t){var a=new Uint8Array(await b.slice(i,i+t).arrayBuffer());var s='';
for(var j=0;j<a.length;j+=8192)s+=String.fromCharCode.apply(null,a.subarray(j,j+8192));OneTvBaixar.parte(id,btoa(s));}
OneTvBaixar.fim(id,true);}catch(e){OneTvBaixar.fim(id,false);}})()"""
    }

    /** Guarda o nome do `<a download>` antes do clique (o WebView não o repassa ao DownloadListener para `blob:`). */
    const val JS_NOME = "(function(){if(window.__oneTvClique)return;window.__oneTvClique=1;var c=HTMLAnchorElement.prototype.click;" +
        "HTMLAnchorElement.prototype.click=function(){if(this.download)window.__oneTvNome=this.download;return c.apply(this,arguments);};})()"

    /** Recebe o blob em pedaços. Só a página da TV fica no WebView ([PainelActivity] manda o resto ao navegador). */
    class Ponte(private val ctx: Context) {
        private val abertos = HashMap<Int, Triple<Uri, OutputStream, String>>()
        private var proximo = 1

        @JavascriptInterface @Synchronized fun inicio(nome: String?, mime: String?): Int {
            val n = nomeSeguro(nome)
            val d = try { abrir(ctx, n, mime) } catch (_: Exception) { null } ?: run { aviso(ctx, false, n); return -1 }
            val id = proximo++
            abertos[id] = Triple(d.first, d.second, n)
            return id
        }

        @JavascriptInterface @Synchronized fun parte(id: Int, base64: String?) {
            val a = abertos[id] ?: return
            try { a.second.write(Base64.decode(base64 ?: "", Base64.DEFAULT)) } catch (_: Exception) { fim(id, false) }
        }

        @JavascriptInterface @Synchronized fun fim(id: Int, ok: Boolean) {
            val a = abertos.remove(id) ?: run { if (id < 0) aviso(ctx, false, ""); return }
            fechar(ctx, a.first, a.second, ok)
            aviso(ctx, ok, a.third)
        }
    }
}
