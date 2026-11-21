package br.paulo.tvlauncher.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.graphics.drawable.BitmapDrawable
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.ImageView
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.screensaver.FotosTerra
import br.paulo.tvlauncher.weather.Astronomia
import java.util.Calendar

/**
 * Fundo "A Terra vista do espaço" do protetor (17/11/2026): as fotos da NASA de [FotosTerra], uma por minuto, escolhidas
 * pela hora e pelo tempo. Duas camadas: a de trás recebe a foto nova e aparece por cima em [FADE_MS], chegando um pouco
 * mais perto (aproxima 4 %) até se assentar. Cada foto anda devagar (Ken Burns: aproxima de 5 % a 14 % e desliza em
 * diagonal, num sentido sorteado), e assim nenhum pixel fica parado (anti-burn-in).
 *
 * O movimento anda em passos de 100 ms (10 por segundo), como o Ken Burns da pintura (13/11/2026: 6x menos trabalho para
 * a GPU e para a thread das teclas); só durante a troca os passos são de 40 ms, para o esmaecer não "degrauzar".
 * A foto é lida do APK fora da thread da tela ([executar]). Só há duas fotos na memória (~7 MB cada), nunca mais.
 */
class FundoTerra(ctx: Context, private val executar: (Runnable) -> Unit) : FrameLayout(ctx) {
    private val camadas = arrayOf(imagem(), imagem())
    private val inicio = LongArray(2)
    private val sentido = arrayOf(floatArrayOf(1f, 1f), floatArrayOf(-1f, 1f))
    private var frente = 0
    private var foto: FotosTerra.Foto? = null
    private var grupo: FotosTerra.Grupo? = null
    private var trocaDesde = -1L
    private var lendo = false
    private val sorteio = java.util.Random()

    /** Foto nova assentada (para o vidro dos painéis desfocar ela). */
    var aoTrocarImagem: ((Bitmap) -> Unit)? = null
    /** A cada passo do movimento (para o vidro acompanhar). */
    var aoPasso: (() -> Unit)? = null

    init {
        setBackgroundColor(0xFF000000.toInt())
        camadas.forEach { addView(it, LayoutParams(-1, -1)) }
    }

    private fun imagem() = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f
        setLayerType(LAYER_TYPE_HARDWARE, null)   // a GPU só move a textura
    }

    /** O tempo ou a hora mudaram (a cada 15 min pelo protetor): troca já se o grupo de fotos mudou. */
    fun atualizar(e: EstadoCeu) {
        val c = Calendar.getInstance().apply { timeInMillis = e.agoraMs }
        val g = FotosTerra.grupo(e.agoraMs, Astronomia.nascerEPor(e.agoraMs, e.lat, e.lon),
            FotosTerra.nublado(e.codigo, e.chovendo), c.get(Calendar.HOUR_OF_DAY))
        if (g != grupo) { grupo = g; trocar() }
    }

    private val proximaFoto = Runnable { trocar() }

    private fun trocar() {
        val g = grupo ?: return
        removeCallbacks(proximaFoto)
        postDelayed(proximaFoto, TROCA_MS)
        if (lendo) return
        val nova = FotosTerra.proxima(g, foto?.id)
        lendo = true
        executar(Runnable {
            val bmp = try {
                context.assets.open("terra/${nova.id}.jpg").use { BitmapFactory.decodeStream(it) }
            } catch (e: Exception) { android.util.Log.w(TAG, "foto ${nova.id}: $e"); null }
            post {
                lendo = false
                if (bmp == null || !isAttachedToWindow) return@post
                foto = nova
                val tras = 1 - frente
                camadas[tras].setImageBitmap(bmp)
                camadas[tras].alpha = 0f
                camadas[tras].bringToFront()
                inicio[tras] = SystemClock.uptimeMillis()
                sentido[tras][0] = if (sorteio.nextBoolean()) 1f else -1f
                sentido[tras][1] = if (sorteio.nextBoolean()) 1f else -1f
                trocaDesde = SystemClock.uptimeMillis()
                // Primeira foto: nada por trás; aparece do preto do mesmo jeito.
                removeCallbacks(passo); passo.run()
            }
        })
    }

    private val passo = object : Runnable {
        override fun run() {
            val agora = SystemClock.uptimeMillis()
            val desliza = 12 * resources.displayMetrics.density
            for (i in 0..1) {
                val v = camadas[i]
                if (v.drawable == null) continue
                val t = ((agora - inicio[i]) / DURACAO_KB.toFloat()).coerceIn(0f, 1.3f)
                var esc = 1.05f + 0.09f * t
                if (i != frente && trocaDesde >= 0) {
                    val k = ((agora - trocaDesde) / FADE_MS.toFloat()).coerceIn(0f, 1f)
                    val suave = k * k * (3 - 2 * k)
                    v.alpha = suave
                    esc += 0.04f * (1 - suave)   // chega um pouco mais perto e se assenta
                }
                v.scaleX = esc; v.scaleY = esc
                v.translationX = sentido[i][0] * (t - 0.5f) * 2 * desliza
                v.translationY = sentido[i][1] * (t - 0.5f) * desliza
            }
            if (trocaDesde >= 0 && agora - trocaDesde >= FADE_MS) {
                // A nova assentou: vira a da frente e a velha sai da memória.
                val velha = frente
                frente = 1 - frente
                camadas[frente].alpha = 1f
                camadas[velha].setImageDrawable(null); camadas[velha].alpha = 0f
                trocaDesde = -1L
                (camadas[frente].drawable as? BitmapDrawable)?.bitmap?.let { aoTrocarImagem?.invoke(it) }
            }
            aoPasso?.invoke()
            postDelayed(this, if (trocaDesde >= 0) 40L else 100L)
        }
    }

    /** Pixels da foto da frente -> tela (para o vidro desfocar o pedaço certo). null antes da primeira foto. */
    fun matrizNaTela(): Matrix? {
        val v = camadas[frente]
        val dr = v.drawable as? BitmapDrawable ?: return null
        val b = dr.bitmap ?: return null
        if (v.width == 0) return null
        return Matrix().apply {
            setScale(dr.intrinsicWidth / b.width.toFloat(), dr.intrinsicHeight / b.height.toFloat())
            postConcat(v.imageMatrix)
            postConcat(v.matrix)
            postTranslate(v.left.toFloat(), v.top.toFloat())
            val loc = IntArray(2); getLocationOnScreen(loc); postTranslate(loc[0].toFloat(), loc[1].toFloat())
        }
    }

    /** Legenda da foto à vista: (título, "Foto: NASA, da Estação Espacial"). */
    fun legenda(): Pair<String, String>? = foto?.let { context.getString(it.titulo) to context.getString(R.string.terra_credito) }

    fun soltar() {
        removeCallbacks(passo); removeCallbacks(proximaFoto)
        camadas.forEach { it.setImageDrawable(null) }
    }

    private companion object {
        const val TAG = "TvLauncher.Terra"
        const val TROCA_MS = 60_000L
        const val FADE_MS = 4_500L
        /** Uma volta do Ken Burns: a foto inteira (1 min) mais a troca e uma folga. */
        const val DURACAO_KB = TROCA_MS + FADE_MS + 5_000L
    }
}
