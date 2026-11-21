package br.paulo.tvlauncher.screensaver

import br.paulo.tvlauncher.Fundo
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.home.EstadoCeu
import br.paulo.tvlauncher.home.FundoMeuRegras
import br.paulo.tvlauncher.home.FundoVivo
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.player.mostrarProximo
import br.paulo.tvlauncher.settings.CorDoFundo
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.settings.Tema
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import kotlin.random.Random

/**
 * O fundo do tema "Plugin de fundo" ([Tema.PLUGIN]): mostra o que o plugin escolhido manda ([WallpaperPlugins]).
 *
 * Mesmos cuidados do "Meu fundo" (FundoMeu.kt), porque o Chromecast é 32 bits e tem 2 GB:
 *  - vídeo e GIF só andam com a HOME visível; a troca de fundo também só corre com ela na frente;
 *  - imagem decodificada já no tamanho da tela; download com teto de [MAX_BYTES] e no máximo [MAX_ARQUIVOS] guardados;
 *  - qualquer falha (plugin sumiu, endereço morto, vídeo que o aparelho não toca) deixa o fundo escuro e a HOME segue.
 * Por cima do fundo: um véu leve (o véu forte da coluna já vem da cruz) e o crédito do autor no canto de baixo.
 */
class WallpaperPluginView(ctx: Context, @Suppress("UNUSED_PARAMETER") filaDaHome: (() -> Unit) -> Unit) : FrameLayout(ctx), FundoVivo {
    /**
     * Fila própria (não a da HOME): o plugin pode levar até 25 s para responder e a fila da rede da HOME não pode esperar.
     * Uma coisa por vez: pedido ao plugin, download e decodificação não disputam a memória da TV.
     */
    private val fila = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "FundoPlugin").apply { isDaemon = true } }
    private fun emSegundoPlano(bloco: () -> Unit) { runCatching { fila.execute(bloco) } }
    private val app = ctx.applicationContext
    private val dm = resources.displayMetrics
    private val camadas = arrayOf(imagem(), imagem())
    private var frente = 0
    private var textura: TextureView? = null
    private var tocador: MediaPlayer? = null
    private var superficie: Surface? = null
    private var animada: AnimatedImageDrawable? = null
    private val credito: TextView
    @Volatile private var provider: WallpaperPlugins.Provider? = null
    private var itens: List<WallpaperPluginContract.Item> = emptyList()
    private var atual = -1
    private var trocadoEm = 0L
    private var pedidoFoco: Runnable? = null
    private var ultimoFoco: String? = null
    private var ultimaAtualizacao = 0L
    /** Quando a lista atual chegou (relógio desde o boot): passado o cache do plugin, pede outra. */
    private var listaEm = 0L
    private var receptor: BroadcastReceiver? = null
    /** Cada pedido de exibição ganha um número: uma imagem lenta que chega depois de outra mais nova é jogada fora. */
    private var geracao = 0

    private val trocar = Runnable { if (windowVisibility == View.VISIBLE) mostrarProximo(); agendar() }

    init {
        setBackgroundColor(Estilo.FUNDO)
        camadas.forEach { addView(it, LayoutParams(-1, -1)) }
        // Véu leve: foto clara de plugin não pode apagar o texto; a coluna da cruz tem o véu forte dela.
        addView(View(ctx).apply { setBackgroundColor(0x33000000) }, LayoutParams(-1, -1))
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x99000000.toInt(), 0x00000000))
        }, LayoutParams(-1, dp(120), Gravity.BOTTOM))
        credito = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(0xCCF2EDE4.toInt())
            typeface = Fontes.nunito(500)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            maxWidth = dp(560)
            setShadowLayer(dp(4).toFloat(), 0f, 0f, 0xCC000000.toInt())
        }
        addView(credito, LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.END).apply { marginEnd = dp(48); bottomMargin = dp(18) })
        emSegundoPlano {
            val p = WallpaperPlugins.escolhido(app)
            if (p == null) {
                // O plugin escolhido sumiu (desinstalado, sem o serviço, assinatura trocada): esquece a escolha e a HOME
                // se refaz com o tema normal, em vez de ficar no fundo liso desta view para sempre.
                WallpaperPlugins.esquecer(app)
                post { (context as? android.app.Activity)?.takeIf { !it.isFinishing }?.recreate() }
                return@emSegundoPlano
            }
            provider = p
            carregarLista(p, forcar = false)
        }
    }

    override fun atualizar(e: EstadoCeu) {}   // o fundo é do plugin: não muda com o céu

    private fun dp(v: Int) = (v * dm.density + 0.5f).toInt()

    private fun imagem() = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f }

    // ---------------------------------------------------------------- lista e eventos

    /** Lista do cache (dentro do prazo do plugin) ou pedida de novo. Roda fora da thread da tela. */
    private fun carregarLista(p: WallpaperPlugins.Provider, forcar: Boolean) {
        val lista = (if (forcar) null else WallpaperPlugins.doCache(app, p))
            ?: WallpaperPlugins.buscar(app, p, WallpaperPluginContract.Event(WallpaperPluginContract.EV_TIME_ELAPSED), WallpaperPlugins.PRAZO_TEMPO_MS)
                ?.also { if (it.isNotEmpty()) WallpaperPlugins.guardarCache(app, p, it) }
            // Falhou (plugin lento para ligar logo depois do boot, sem rede): agenda mesmo assim. Sem isto o fundo ficava
            // liso até a HOME ser refeita (debug 20/11/2026); com a lista vazia o agendar pede de novo em ~1 min.
            ?: run { post { agendar() }; return }
        post {
            itens = lista
            listaEm = SystemClock.elapsedRealtime()
            atual = -1
            mostrarProximo()
            agendar()
        }
    }

    private fun agendar() {
        removeCallbacks(trocar)
        // Com 1 item (ou nenhum) também agenda: na hora em que o cache do plugin vence, ou o novo pedido da lista vazia.
        val p = provider ?: return
        postDelayed(trocar, WallpaperPluginContract.esperaMs(itens.size, SystemClock.elapsedRealtime() - listaEm, p.cacheMs))
    }

    private fun mostrarProximo() {
        val p = provider ?: return
        // O cache do plugin venceu: pede a lista nova (e ela mesma mostra o 1º).
        // Lista vazia é resposta válida ("nada agora"): pede de novo só depois de VAZIO_MS, nunca em seguida.
        if (WallpaperPluginContract.precisaPedir(itens.size, SystemClock.elapsedRealtime() - listaEm, p.cacheMs)) {
            ultimaAtualizacao = SystemClock.elapsedRealtime()
            emSegundoPlano { carregarLista(p, forcar = true) }
            if (itens.isEmpty()) return
        }
        atual = WallpaperPluginContract.proximo(itens.size, atual) { Random.nextInt(it.coerceAtLeast(1)) }
        itens.getOrNull(atual)?.let { mostrar(it) }
    }

    /**
     * Foco num item da cruz: só para plugins que pediram esse evento (os que fazem a arte do item focado). Espera a
     * pessoa parar 700 ms num item antes de perguntar: andando pela coluna, nada é pedido.
     */
    fun focar(titulo: String?, pacote: String?) {
        val p = provider ?: return
        if (!WallpaperPluginContract.quer(p.updateMode, WallpaperPluginContract.EV_CARD_FOCUSED)) return
        val chave = "$titulo|$pacote"
        if (chave == ultimoFoco) return
        ultimoFoco = chave
        pedidoFoco?.let { removeCallbacks(it) }
        val r = Runnable {
            emSegundoPlano {
                val l = WallpaperPlugins.buscar(app, p, WallpaperPluginContract.Event(WallpaperPluginContract.EV_CARD_FOCUSED,
                    titulo?.take(80), pacote), WallpaperPlugins.PRAZO_FOCO_MS)
                val primeiro = l?.firstOrNull() ?: return@emSegundoPlano
                post { if (ultimoFoco == chave) mostrar(primeiro) }
            }
        }
        pedidoFoco = r
        postDelayed(r, 700)
    }

    /** Modo ambiente da cruz ligou/desligou: só para plugins que pediram esse evento. */
    fun ocioso(sim: Boolean) {
        val p = provider ?: return
        if (!WallpaperPluginContract.quer(p.updateMode, WallpaperPluginContract.EV_LAUNCHER_IDLE_MODE_CHANGED)) return
        emSegundoPlano {
            val l = WallpaperPlugins.buscar(app, p, WallpaperPluginContract.Event(WallpaperPluginContract.EV_LAUNCHER_IDLE_MODE_CHANGED,
                idle = sim), WallpaperPlugins.PRAZO_FOCO_MS)
            l?.firstOrNull()?.let { post { mostrar(it) } }
        }
    }

    // ---------------------------------------------------------------- exibição

    private fun mostrar(item: WallpaperPluginContract.Item) {
        val g = ++geracao
        trocadoEm = SystemClock.elapsedRealtime()
        val texto = WallpaperPluginContract.credito(item)
        when (item.type) {
            WallpaperPluginContract.COLOR -> { soltarVideo(); aplicar(g, ColorDrawable(WallpaperPluginContract.cor(item.uri) ?: Estilo.FUNDO), ImageView.ScaleType.FIT_XY, texto) }
            WallpaperPluginContract.VIDEO -> mostrarVideo(g, item, texto)
            else -> emSegundoPlano {
                val d = runCatching { decodificar(item) }.getOrNull() ?: return@emSegundoPlano
                post {
                    if (g != geracao) { (d as? BitmapDrawable)?.bitmap?.recycle(); return@post }
                    soltarVideo()
                    aplicar(g, d, escala(item.displayMode), texto)
                }
            }
        }
    }

    private fun escala(modo: Int) = when (modo) {
        WallpaperPluginContract.MODE_STRETCH -> ImageView.ScaleType.FIT_XY
        WallpaperPluginContract.MODE_FIT_CENTER -> ImageView.ScaleType.FIT_CENTER
        else -> ImageView.ScaleType.CENTER_CROP   // CROP, DEFAULT, e os com desfoque (já vêm prontos no tamanho da tela)
    }

    /** Troca suave: a camada de trás recebe a imagem nova e aparece por cima; a antiga é solta no fim. */
    private fun aplicar(g: Int, d: Drawable, tipo: ImageView.ScaleType, texto: String?) {
        if (g != geracao) return
        val nova = camadas[1 - frente]; val velha = camadas[frente]
        animada?.stop(); animada = d as? AnimatedImageDrawable
        nova.animate().cancel(); velha.animate().cancel()
        nova.scaleType = tipo
        nova.setImageDrawable(d)
        // Cruzamento: a nova acende enquanto a velha apaga (sem mexer na ordem das camadas: véu e crédito ficam por cima).
        nova.alpha = 0f
        nova.animate().alpha(1f).setDuration(800).start()
        velha.animate().alpha(0f).setDuration(800).withEndAction { if (g == geracao) liberar(velha) }.start()
        frente = 1 - frente
        if (windowVisibility == View.VISIBLE) animada?.start()
        credito.text = texto ?: ""
        textura?.alpha = 0f
    }

    private fun liberar(v: ImageView) {
        val d = v.drawable
        v.setImageDrawable(null)
        (d as? AnimatedImageDrawable)?.stop()
        // Bitmap nosso (decodificado aqui): devolve a memória já, sem esperar o coletor (TV de 2 GB).
        (d as? BitmapDrawable)?.bitmap?.takeIf { !it.isRecycled }?.recycle()
    }

    /** Lê a imagem do plugin já no tamanho da tela; GIF/WebP animado vira AnimatedImageDrawable. */
    private fun decodificar(item: WallpaperPluginContract.Item): Drawable? {
        val f = arquivoDe(item.uri) ?: return null
        val w = dm.widthPixels; val h = dm.heightPixels
        if (item.type == WallpaperPluginContract.ANIMATED_DRAWABLE || f.name.endsWith(".gif")) {
            val d = ImageDecoder.decodeDrawable(ImageDecoder.createSource(f)) { dec, info, _ ->
                dec.setTargetSampleSize(FundoMeuRegras.amostragem(info.size.width, info.size.height, w, h))
            }
            return d
        }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        if (o.outWidth <= 0) return null
        var bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
            inSampleSize = FundoMeuRegras.amostragem(o.outWidth, o.outHeight, w, h)
        }) ?: return null
        runCatching { CorDoFundo.guardar(app, bmp) }   // a cor de destaque acompanha o fundo, como no Meu fundo
        bmp = when (item.displayMode) {
            WallpaperPluginContract.MODE_BLUR -> desfocar(bmp, w, h, soltar = true)
            WallpaperPluginContract.MODE_FIT_CENTER_BLURRED_BG -> encaixarSobreDesfoque(bmp, w, h)
            else -> bmp
        }
        return BitmapDrawable(resources, bmp)
    }

    /**
     * Desfoque barato (sem RenderEffect, que o Chromecast paga a cada quadro): reduz a 1/24 e amplia de volta com filtro,
     * uma vez, num bitmap do tamanho da tela, cobrindo a tela (corte central). [soltar] = devolve a memória do original.
     */
    private fun desfocar(src: Bitmap, w: Int, h: Int, soltar: Boolean): Bitmap {
        val pw = (w / 24).coerceAtLeast(8); val ph = (h / 24).coerceAtLeast(8)
        val pequeno = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888)
        val esc = maxOf(pw.toFloat() / src.width, ph.toFloat() / src.height)
        val sw = src.width * esc; val sh = src.height * esc
        Canvas(pequeno).drawBitmap(src, null, RectF((pw - sw) / 2, (ph - sh) / 2, (pw + sw) / 2, (ph + sh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        val grande = Bitmap.createScaledBitmap(pequeno, w, h, true)
        pequeno.recycle()
        if (soltar) src.recycle()
        // createScaledBitmap pode devolver um bitmap imutável: a versão "encaixada" ainda desenha por cima.
        return if (grande.isMutable) grande else grande.copy(Bitmap.Config.ARGB_8888, true).also { grande.recycle() }
    }

    /** A imagem inteira no centro, sobre ela mesma desfocada cobrindo a tela. */
    private fun encaixarSobreDesfoque(src: Bitmap, w: Int, h: Int): Bitmap {
        val fundo = desfocar(src, w, h, soltar = false)
        val esc = minOf(w.toFloat() / src.width, h.toFloat() / src.height)
        val sw = src.width * esc; val sh = src.height * esc
        Canvas(fundo).drawBitmap(src, null, RectF((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return fundo
    }

    /** O arquivo local do endereço: baixado (http/https, com cache e teto) ou copiado do provider do plugin. */
    private fun arquivoDe(uri: String): File? {
        val pasta = File(app.cacheDir, "wallpaper-plugin").apply { mkdirs() }
        val nome = MessageDigest.getInstance("SHA-1").digest(uri.toByteArray()).joinToString("") { "%02x".format(it) }.take(24) +
            if (uri.substringBefore('?').endsWith(".gif", true)) ".gif" else ""
        val f = File(pasta, nome)
        if (f.isFile && f.length() > 0) { f.setLastModified(System.currentTimeMillis()); return f }
        val tmp = File(pasta, "$nome.part")
        val ok = runCatching {
            val entrada = if (uri.startsWith("http", true)) {
                val c = (URL(uri).openConnection() as HttpURLConnection).apply { connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true }
                if (c.responseCode !in 200..299) { c.disconnect(); return null }
                if (c.contentLengthLong > MAX_BYTES) { c.disconnect(); return null }
                c.inputStream
            } else app.contentResolver.openInputStream(Uri.parse(uri)) ?: return null
            entrada.use { i -> tmp.outputStream().use { o -> copiarComTeto(i, o) } }
        }.getOrDefault(false)
        if (!ok || !tmp.renameTo(f)) { tmp.delete(); return null }
        // Guarda só os mais recentes: o cache do sistema pode crescer, mas não por nossa causa.
        pasta.listFiles()?.filter { !it.name.endsWith(".part") }?.sortedByDescending { it.lastModified() }?.drop(MAX_ARQUIVOS)?.forEach { it.delete() }
        return f
    }

    private fun copiarComTeto(i: java.io.InputStream, o: java.io.OutputStream): Boolean {
        val buf = ByteArray(64 * 1024); var total = 0L
        while (true) {
            val n = i.read(buf); if (n < 0) return true
            total += n; if (total > MAX_BYTES) return false
            o.write(buf, 0, n)
        }
    }

    private fun mostrarVideo(g: Int, item: WallpaperPluginContract.Item, texto: String?) {
        soltarVideo()
        val tv = textura ?: TextureView(context).also { t ->
            textura = t
            addView(t, 2, LayoutParams(-1, -1))   // acima das imagens, abaixo do véu e do crédito
        }
        val abrir = { st: SurfaceTexture ->
            superficie?.release()
            val s = Surface(st).also { superficie = it }
            tocador = runCatching {
                MediaPlayer().apply {
                    setDataSource(app, Uri.parse(item.uri))
                    setSurface(s)
                    isLooping = true
                    setVolume(0f, 0f)
                    setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                    setOnPreparedListener { if (g == geracao) { tv.animate().alpha(1f).setDuration(800).start(); if (windowVisibility == View.VISIBLE) it.start() } }
                    setOnErrorListener { _, _, _ -> true }   // vídeo que o aparelho não toca: fica a imagem anterior
                    prepareAsync()
                }
            }.getOrNull()
        }
        credito.text = texto ?: ""
        tv.alpha = 0f
        val st = tv.surfaceTexture
        if (st != null) abrir(st) else tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(s: SurfaceTexture, w: Int, h: Int) { if (g == geracao) abrir(s) }
            override fun onSurfaceTextureSizeChanged(s: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(s: SurfaceTexture): Boolean { soltarVideo(); superficie?.release(); superficie = null; return true }
            override fun onSurfaceTextureUpdated(s: SurfaceTexture) {}
        }
    }

    private fun soltarVideo() {
        tocador?.let { runCatching { it.release() } }
        tocador = null
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        val visivel = v == View.VISIBLE
        animada?.let { if (visivel) it.start() else it.stop() }
        tocador?.let { runCatching { if (visivel) { if (!it.isPlaying) it.start() } else if (it.isPlaying) it.pause() } }
        if (visivel && trocadoEm > 0L && SystemClock.elapsedRealtime() - trocadoEm >= WallpaperPluginContract.TROCA_MS) { mostrarProximo(); agendar() }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // O plugin avisa que mudou (preferências ou fotos novas): pede de novo, no máximo uma vez por minuto.
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                val p = provider ?: return
                val id = i?.getStringExtra(WallpaperPluginContract.EXTRA_PROVIDER_ID)
                if (id != null && p.uuid != null && id != p.uuid) return
                if (SystemClock.elapsedRealtime() - ultimaAtualizacao < 60_000L) return
                ultimaAtualizacao = SystemClock.elapsedRealtime()
                emSegundoPlano { carregarLista(p, forcar = true) }
            }
        }
        receptor = r
        val f = IntentFilter(WallpaperPluginContract.ACTION_UPDATED)
        runCatching { if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else app.registerReceiver(r, f) }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(trocar)
        pedidoFoco?.let { removeCallbacks(it) }
        receptor?.let { runCatching { app.unregisterReceiver(it) } }
        fila.shutdown()
        receptor = null
        animada?.stop()
        soltarVideo()
        superficie?.release(); superficie = null
    }

    companion object {
        const val MAX_BYTES = 25L shl 20      // 25 MB por arquivo (foto 4K ou GIF curto)
        const val MAX_ARQUIVOS = 30
    }
}
