package br.paulo.onetv.celular

import android.annotation.SuppressLint
import android.app.Activity
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.view.KeyEvent
import android.content.Intent
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
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
 *  - arquivos: o seletor do `<input type=file>` e os downloads ([Baixar]).
 */
class PainelActivity : Activity() {
    companion object {
        const val EXTRA_IP = "ip"
        /** Pareamento.chave da TV salva (várias casas: o IP sozinho pode ser de outra casa). */
        const val EXTRA_CHAVE = "chave"
        const val EXTRA_FRAGMENTO = "fragmento"
        const val EXTRA_TITULO = "titulo"
        const val EXTRA_TEXTO = "texto"
    }

    private lateinit var web: WebView
    private lateinit var tv: Pareamento.Tv
    private var tentouFora = false
    /** Quem espera a resposta do seletor de arquivos aberto pela página (no máximo um por vez). */
    private var escolha: ValueCallback<Array<Uri>>? = null
    private val pedidoArquivo = 41

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Visual.FUNDO
        window.navigationBarColor = Visual.FUNDO
        val ip = intent.getStringExtra(EXTRA_IP)
        tv = Tvs.porChave(this, intent.getStringExtra(EXTRA_CHAVE)) ?: Tvs.porIp(this, ip) ?: ip?.takeIf { Pareamento.privado(it) }?.let { Pareamento.Tv("TV", it) } ?: run { finish(); return }
        web = WebView(this).apply {
            setBackgroundColor(Visual.FUNDO)
            settings.javaScriptEnabled = true      // o painel é uma página com scripts
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            webViewClient = Cliente()
            webChromeClient = Seletor()
            // Ponte só de escrita em Downloads; só a TV carrega aqui (shouldOverrideUrlLoading manda o resto para fora).
            addJavascriptInterface(Baixar.Ponte(applicationContext), "OneTvBaixar")
            setDownloadListener { url, _, disposicao, mime, _ ->
                val nome = Baixar.nomeSeguro(URLUtil.guessFileName(url, disposicao, mime))
                if (url.startsWith("blob:")) evaluateJavascript(Baixar.jsDoBlob(url, nome, mime), null)
                else if (daTv(Uri.parse(url))) Baixar.direto(this@PainelActivity, tv, url, nome, mime)
            }
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

    private fun daTv(u: Uri): Boolean = u.host == tv.ip || (tv.ts.isNotEmpty() && u.host == tv.ts)

    private inner class Seletor : WebChromeClient() {
        override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
            escolha?.onReceiveValue(null)   // um seletor esquecido aberto travaria o próximo clique da página
            escolha = cb
            val tipos = Baixar.tiposMime(p.acceptTypes) { MimeTypeMap.getSingleton().getMimeTypeFromExtension(it) }
            val i = Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).apply {
                type = if (tipos.size == 1) tipos[0] else "*/*"
                if (tipos.size > 1) putExtra(Intent.EXTRA_MIME_TYPES, tipos)
                putExtra(Intent.EXTRA_ALLOW_MULTIPLE, p.mode == FileChooserParams.MODE_OPEN_MULTIPLE)
            }
            return try { @Suppress("DEPRECATION") startActivityForResult(i, pedidoArquivo); true }
            catch (_: Exception) { escolha = null; cb.onReceiveValue(null); true }
        }
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onActivityResult(pedido: Int, resultado: Int, dados: Intent?) {
        if (pedido != pedidoArquivo) { @Suppress("DEPRECATION") super.onActivityResult(pedido, resultado, dados); return }
        val cb = escolha ?: return
        escolha = null
        val clip = dados?.clipData
        val uris = when {
            resultado != RESULT_OK -> null
            clip != null && clip.itemCount > 0 -> Array(clip.itemCount) { clip.getItemAt(it).uri }
            dados?.data != null -> arrayOf(dados.data!!)
            else -> null
        }
        cb.onReceiveValue(uris)
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

        override fun onPageFinished(view: WebView, url: String?) { view.evaluateJavascript(Baixar.JS_NOME, null) }

        override fun onReceivedError(view: WebView, req: WebResourceRequest, erro: WebResourceError) {
            if (!req.isForMainFrame || tentouFora) return
            val fora = Pareamento.enderecoFora(tv) ?: return
            tentouFora = true
            view.loadUrl(fora)   // fora de casa: o https do Tailscale (o celular precisa estar na conta Tailscale)
        }
    }
}
