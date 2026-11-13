package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.widget.FrameLayout

/**
 * Fonte do "vidro fosco" (glassmorphism) do protetor: uma cópia pequena e desfocada da pintura, mais a conta que diz
 * onde a pintura está na tela agora (ela se move no efeito Ken Burns).
 *
 * Por que não o desfoque "de verdade" do Android (RenderEffect)? Ele desfoca o PRÓPRIO conteúdo da view, não o que está
 * atrás dela; desfocar o fundo exigiria redesenhar a pintura inteira desfocada a cada quadro, caro demais para o
 * Chromecast. Aqui o desfoque é calculado UMA vez por pintura (fora da thread da tela), numa imagem de ~120 px de
 * largura, e cada painel só pinta o pedaço que fica atrás dele, ampliado (a ampliação suaviza o resto).
 */
class FonteVidro {
    /** Imagem desfocada (pequena) e o fator que a leva de volta ao tamanho da pintura original. */
    @Volatile var desfocada: Bitmap? = null; private set
    private var fator = 1f
    /** Quem desenha a pintura: devolve a matriz pintura-original -> tela. Nulo = sem pintura (vidro só translúcido). */
    var matrizNaTela: (() -> Matrix?)? = null
    val paineis = mutableListOf<Vidro>()

    /** Gera a versão desfocada. Chamar fora da thread da tela; [pronto] roda quando terminar (em qualquer thread). */
    fun preparar(original: Bitmap, pronto: () -> Unit) {
        val larg = 120
        val alt = (original.height * larg / original.width.toFloat()).toInt().coerceAtLeast(1)
        // Reduz em dois passos (1/4 e depois o resto) com filtro: reduzir direto pula pixels e cria serrilhado.
        val meio = Bitmap.createScaledBitmap(original, (original.width / 4).coerceAtLeast(larg), (original.height / 4).coerceAtLeast(alt), true)
        val pequeno = Bitmap.createScaledBitmap(meio, larg, alt, true)
        if (meio != original && meio != pequeno) meio.recycle()
        val px = IntArray(larg * alt)
        pequeno.getPixels(px, 0, larg, 0, 0, larg, alt)
        repeat(3) { caixa(px, larg, alt, 3) }   // 3 passadas de média em caixa ≈ desfoque gaussiano
        // Saturação +40 % (como o "saturate(140%)" do CSS): o vidro fica com a cor viva da pintura, não acinzentado.
        val saida = Bitmap.createBitmap(larg, alt, Bitmap.Config.ARGB_8888)
        val cm = ColorMatrix().apply { setSaturation(1.4f) }
        val temp = Bitmap.createBitmap(px, larg, alt, Bitmap.Config.ARGB_8888)
        Canvas(saida).drawBitmap(temp, 0f, 0f, Paint().apply { colorFilter = ColorMatrixColorFilter(cm) })
        temp.recycle(); if (pequeno != original) pequeno.recycle()
        fator = original.width / larg.toFloat()
        desfocada = saida
        pronto()
    }

    /** Matriz imagem-desfocada -> tela: amplia de volta ao tamanho original e aplica onde a pintura está. */
    fun matriz(saida: Matrix): Boolean {
        val m = matrizNaTela?.invoke() ?: return false
        saida.setScale(fator, fator); saida.postConcat(m)
        return true
    }

    fun redesenhar() { for (p in paineis) p.invalidate() }

    fun soltar() { paineis.clear(); desfocada = null; matrizNaTela = null }

    /** Média em caixa, horizontal e vertical, de raio [r] (pixels nas bordas repetem o último). */
    private fun caixa(px: IntArray, w: Int, h: Int, r: Int) {
        val tmp = IntArray(px.size)
        fun passo(src: IntArray, dst: IntArray, comp: Int, outro: Int, idx: (Int, Int) -> Int) {
            val n = 2 * r + 1
            for (j in 0 until outro) {
                var sr = 0; var sg = 0; var sb = 0
                for (k in -r..r) { val c = src[idx(k.coerceIn(0, comp - 1), j)]; sr += c shr 16 and 255; sg += c shr 8 and 255; sb += c and 255 }
                for (i in 0 until comp) {
                    dst[idx(i, j)] = (0xFF shl 24) or ((sr / n) shl 16) or ((sg / n) shl 8) or (sb / n)
                    val sai = src[idx((i - r).coerceIn(0, comp - 1), j)]; val entra = src[idx((i + r + 1).coerceIn(0, comp - 1), j)]
                    sr += (entra shr 16 and 255) - (sai shr 16 and 255)
                    sg += (entra shr 8 and 255) - (sai shr 8 and 255)
                    sb += (entra and 255) - (sai and 255)
                }
            }
        }
        passo(px, tmp, w, h) { i, j -> j * w + i }
        passo(tmp, px, h, w) { i, j -> i * w + j }
    }
}

/**
 * Painel de vidro: cantos arredondados, o pedaço desfocado da pintura atrás dele, um véu branco de 10 %, borda fina e um
 * brilho na beirada de cima. Os filhos (textos, ícones) são desenhados por cima, como num FrameLayout comum.
 */
class Vidro(ctx: Context, private val fonte: FonteVidro, private val raioDp: Float) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val retangulo = RectF()
    private val m = Matrix()
    private val local = IntArray(2)
    private val pintura = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var shaderDe: Bitmap? = null
    private val veu = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x1AFFFFFF }
    private val escurece = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26000000 }
    private val borda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = Estilo.VIDRO_BORDA }
    private val brilho = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1f * d; color = 0x40FFFFFF }

    init { setWillNotDraw(false); fonte.paineis.add(this) }

    override fun onDraw(canvas: Canvas) {
        val r = raioDp * d
        retangulo.set(0f, 0f, width.toFloat(), height.toFloat())
        val bmp = fonte.desfocada
        if (bmp != null && fonte.matriz(m)) {
            if (shaderDe !== bmp) { pintura.shader = BitmapShader(bmp, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP); shaderDe = bmp }
            // A matriz da fonte leva até a TELA; o canvas daqui começa no canto do painel: desconta onde ele está.
            getLocationOnScreen(local)
            m.postTranslate(-local[0].toFloat(), -local[1].toFloat())
            pintura.shader.setLocalMatrix(m)
            canvas.drawRoundRect(retangulo, r, r, pintura)
            canvas.drawRoundRect(retangulo, r, r, veu)
        } else {
            // Sem pintura (protetor com as fotos do painel): só o vidro translúcido, um pouco mais escuro.
            canvas.drawRoundRect(retangulo, r, r, escurece)
            canvas.drawRoundRect(retangulo, r, r, veu)
        }
        val meio = borda.strokeWidth / 2
        retangulo.inset(meio, meio)
        canvas.drawRoundRect(retangulo, r, r, borda)
        // Brilho só no arco de cima: luz batendo na borda do vidro.
        canvas.save(); canvas.clipRect(0f, 0f, width.toFloat(), r)
        canvas.drawRoundRect(retangulo, r, r, brilho)
        canvas.restore()
    }

    override fun onDetachedFromWindow() { super.onDetachedFromWindow(); fonte.paineis.remove(this) }
}

