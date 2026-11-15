package br.paulo.tvlauncher

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

/**
 * O fundo do tema "Meu fundo" ([Tema.MEU], [FundoMeuRegras]): foto, GIF ou vídeo escolhido pela pessoa.
 *
 * Cuidados (ressalvas do plano):
 *  - O vídeo e o GIF só andam com a HOME visível: [onWindowVisibilityChanged] pausa quando outra tela cobre a HOME ou
 *    a TV dorme. Assim não gastam CPU/bateria fora da HOME e não seguram a tela acesa (o protetor começa normalmente).
 *  - Vídeo sem som, em laço, pelo MediaPlayer do sistema (decodificador de hardware; nada do player de filmes).
 *  - Imagem decodificada já reduzida ao tamanho da tela ([FundoMeuRegras.amostragem]): uma foto de 12 MP não ocupa
 *    48 MB num aparelho de 2 GB.
 *  - Arquivo apagado ou ilegível: fica preto e a HOME segue (nada aqui pode derrubar o launcher).
 */
class FundoMeu(ctx: Context, private val emSegundoPlano: (() -> Unit) -> Unit) : FrameLayout(ctx), FundoVivo {
    private val arquivo: File? = FundoMeuDados.arquivo(ctx)
    private val tipo = arquivo?.let { FundoMeuRegras.tipo(it.name) }
    private var imagem: ImageView? = null
    private var animada: AnimatedImageDrawable? = null
    private var textura: TextureView? = null
    private var tocador: MediaPlayer? = null

    /** Filtro de brilho/contraste: o criarFundo usa como pintura da camada de hardware (null = sem filtro). */
    val pintura: Paint? = FundoMeuRegras.matriz(FundoMeuDados.brilho(ctx), FundoMeuDados.contraste(ctx))?.let {
        Paint().apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(it)) }
    }

    init {
        when (tipo) {
            FundoMeuRegras.Tipo.IMAGEM, FundoMeuRegras.Tipo.ANIMADA -> montarImagem()
            FundoMeuRegras.Tipo.VIDEO -> montarVideo()
            null -> {}
        }
    }

    override fun atualizar(e: EstadoCeu) {}   // o fundo é da pessoa: não muda com o céu

    private fun montarImagem() {
        val iv = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
        imagem = iv
        addView(iv, LayoutParams(-1, -1))
        val f = arquivo ?: return
        val app = context.applicationContext
        val dm = resources.displayMetrics
        emSegundoPlano {
            val d = runCatching {
                if (tipo == FundoMeuRegras.Tipo.ANIMADA) ImageDecoder.decodeDrawable(ImageDecoder.createSource(f))
                else {
                    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(f.path, o)
                    val bmp = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply {
                        inSampleSize = FundoMeuRegras.amostragem(o.outWidth, o.outHeight, dm.widthPixels, dm.heightPixels)
                    })
                    bmp?.let { runCatching { CorDoFundo.guardar(app, it) } }
                    bmp?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
                }
            }.getOrNull() ?: return@emSegundoPlano
            post {
                iv.setImageDrawable(d)
                (d as? AnimatedImageDrawable)?.let { a -> animada = a; if (windowVisibility == View.VISIBLE) a.start() }
            }
        }
    }

    private fun montarVideo() {
        val f = arquivo ?: return
        val app = context.applicationContext
        // Cor do fundo: o quadro do meio do vídeo, uma vez, fora da thread da tela.
        emSegundoPlano {
            runCatching {
                MediaMetadataRetriever().use { r -> r.setDataSource(f.path); r.frameAtTime?.let { CorDoFundo.guardar(app, it) } }
            }
        }
        val tv = TextureView(context)
        textura = tv
        tv.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = abrirVideo(f, Surface(st))
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { soltarVideo(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        addView(tv, LayoutParams(-1, -1))
    }

    private fun abrirVideo(f: File, s: Surface) {
        soltarVideo()
        tocador = runCatching {
            MediaPlayer().apply {
                setDataSource(f.path)
                setSurface(s)
                isLooping = true
                setVolume(0f, 0f)
                setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                setOnPreparedListener { if (windowVisibility == View.VISIBLE) it.start() }
                setOnErrorListener { _, _, _ -> true }   // vídeo que o aparelho não toca: fica preto, sem caixa de erro
                prepareAsync()
            }
        }.getOrNull()
    }

    private fun soltarVideo() {
        tocador?.let { runCatching { it.release() } }
        tocador = null
    }

    override fun onWindowVisibilityChanged(v: Int) {
        super.onWindowVisibilityChanged(v)
        val visivel = v == View.VISIBLE
        animada?.let { if (visivel) it.start() else it.stop() }
        tocador?.let { runCatching { if (visivel) { if (!it.isPlaying) it.start() } else if (it.isPlaying) it.pause() } }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animada?.stop()
        soltarVideo()
    }
}

/** Onde fica a escolha do "Meu fundo" (prefs "fundo_meu"). */
object FundoMeuDados {
    private const val PREFS = "fundo_meu"
    private fun p(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun arquivo(ctx: Context): File? = p(ctx).getString("arquivo", null)?.let { File(it) }?.takeIf { it.isFile && it.canRead() }
    fun definirArquivo(ctx: Context, f: File?) = p(ctx).edit().putString("arquivo", f?.path).apply()
    fun brilho(ctx: Context) = p(ctx).getInt("brilho", 0)
    fun contraste(ctx: Context) = p(ctx).getInt("contraste", 0)
    fun definirBrilho(ctx: Context, v: Int) = p(ctx).edit().putInt("brilho", v).apply()
    fun definirContraste(ctx: Context, v: Int) = p(ctx).edit().putInt("contraste", v).apply()
    /** Muda quando arquivo, brilho ou contraste mudam: a HOME se refaz ao voltar (MainActivity.onResume). */
    fun assinatura(ctx: Context) = "${arquivo(ctx)?.path}|${brilho(ctx)}|${contraste(ctx)}"

    /** Fotos, GIFs e vídeos das pastas comuns ([FundoMeuRegras.PASTAS]), os mais novos primeiro, no máximo 60. */
    fun candidatos(): List<File> {
        val raiz = PainelArquivos.raiz()
        val achados = ArrayList<File>()
        fun varrer(d: File, nivel: Int) {
            if (nivel > 2 || achados.size > 500) return
            d.listFiles()?.forEach { f ->
                if (f.name.startsWith(".")) return@forEach
                if (f.isDirectory) varrer(f, nivel + 1) else if (FundoMeuRegras.tipo(f.name) != null) achados += f
            }
        }
        FundoMeuRegras.PASTAS.forEach { varrer(File(raiz, it), 0) }
        return achados.sortedByDescending { it.lastModified() }.take(FundoMeuRegras.MAX_ARQUIVOS)
    }
}
