package br.paulo.onetv.celular

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.KeyEvent
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast

/**
 * O painel da TV dentro do app (WebView). Só três coisas próprias:
 *  - certificado: no https de casa (porta 8444), aceita SÓ o certificado cuja impressão veio no QR da TV
 *    ([Pareamento.confere]). Qualquer outro erro de TLS, ou outro endereço, é recusado como num navegador.
 *  - volume: os botões de volume do celular apertam volume+/volume- na TV (a mesma rota do Controle do painel).
 *  - fora de casa: se a TV de casa não responde e a TV mandou o nome do Tailscale, tenta o https do Tailscale.
 */
class PainelActivity : Activity() {
    companion object {
        const val EXTRA_IP = "ip"
        const val EXTRA_FRAGMENTO = "fragmento"
        const val EXTRA_TITULO = "titulo"
        const val EXTRA_TEXTO = "texto"
    }

    private lateinit var web: WebView
    private lateinit var tv: Pareamento.Tv
    private var tentouFora = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Visual.FUNDO
        window.navigationBarColor = Visual.FUNDO
        val ip = intent.getStringExtra(EXTRA_IP)
        tv = Tvs.porIp(this, ip) ?: ip?.takeIf { Pareamento.privado(it) }?.let { Pareamento.Tv("TV", it) } ?: run { finish(); return }
        web = WebView(this).apply {
            setBackgroundColor(Visual.FUNDO)
            settings.javaScriptEnabled = true      // o painel é uma página com scripts
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = Cliente()
        }
        CookieManager.getInstance().setAcceptCookie(true)
        setContentView(web)
        web.loadUrl(primeiroEndereco())
    }

    /** Casa + o código do QR (entrar sem PIN) e, se veio de "Compartilhar", o que mandar (o painel pergunta antes). */
    private fun primeiroEndereco(): String {
        val fragmento = intent.getStringExtra(EXTRA_FRAGMENTO).orEmpty()
        // Primeira configuração: a TV ainda não tem https pronto para o assistente; o QR é http e vale assim.
        val base = if (fragmento.startsWith("#primeira=")) "http://${tv.ip}:${Pareamento.PORTA_HTTP}/" else Pareamento.enderecoCasa(tv)
        val titulo = intent.getStringExtra(EXTRA_TITULO).orEmpty()
        val texto = intent.getStringExtra(EXTRA_TEXTO).orEmpty()
        val consulta = if (titulo.isEmpty() && texto.isEmpty()) "" else {
            val url = Regex("https?://\\S+").find(texto)?.value.orEmpty()
            "?titulo=" + Uri.encode(titulo) + "&texto=" + Uri.encode(if (url.isNotEmpty()) "" else texto) + "&url=" + Uri.encode(url)
        }
        return base + consulta + fragmento
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val sobe = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP
        if (sobe || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (e.action == KeyEvent.ACTION_DOWN) {
                // A mesma chamada do botão de volume do Controle do painel (cookie da sessão e X-Painel incluídos).
                val tecla = Pareamento.teclaDoVolume(sobe)
                web.evaluateJavascript("typeof chamar==='function'&&chamar('POST','/api/tela/tecla',{tecla:'$tecla'}).catch(()=>{})", null)
            }
            return true   // o volume do celular não muda
        }
        return super.dispatchKeyEvent(e)
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onBackPressed() { if (web.canGoBack()) web.goBack() else @Suppress("DEPRECATION") super.onBackPressed() }

    override fun onDestroy() { if (::web.isInitialized) web.destroy(); super.onDestroy() }

    private inner class Cliente : WebViewClient() {
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, erro: SslError) {
            val u = Uri.parse(erro.url)
            val daTv = u.scheme == "https" && u.host == tv.ip && u.port == Pareamento.PORTA_HTTPS
            val der = try { erro.certificate.x509Certificate?.encoded } catch (_: Exception) { null }
            if (daTv && Pareamento.confere(tv, der)) { handler.proceed(); return }
            handler.cancel()
            Toast.makeText(this@PainelActivity, R.string.cel_cert_outro, Toast.LENGTH_LONG).show()
        }

        // Só endereços da própria TV ficam no app; o resto (ajuda, sites) abre no navegador.
        override fun shouldOverrideUrlLoading(view: WebView, req: WebResourceRequest): Boolean {
            val h = req.url.host
            if (h == tv.ip || (tv.ts.isNotEmpty() && h == tv.ts)) return false
            try { startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, req.url)) } catch (_: Exception) {}
            return true
        }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, erro: WebResourceError) {
            if (!req.isForMainFrame || tentouFora) return
            val fora = Pareamento.enderecoFora(tv) ?: return
            tentouFora = true
            view.loadUrl(fora)   // fora de casa: o https do Tailscale (o celular precisa estar na conta Tailscale)
        }
    }
}
