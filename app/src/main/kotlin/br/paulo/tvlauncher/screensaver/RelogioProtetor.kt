package br.paulo.tvlauncher.screensaver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import br.paulo.tvlauncher.DesignTokens
import br.paulo.tvlauncher.alarm.HoraPorExtenso
import br.paulo.tvlauncher.settings.Fontes
import java.util.Calendar
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

/**
 * O relógio do protetor em seis estilos (17/11/2026, protótipo "Protetor Vivo 2"; Paulo pediu estilos para escolher):
 * - FINO: o de antes. Hora cheia forte (Manrope 600) e minuto fino (200), dois-pontos apagados.
 * - EDITORIAL: Fraunces 300, serifa de revista.
 * - MONUMENTAL: Manrope 600 enorme e apertado.
 * - PONTEIROS: mostrador só de traços, sem números; o ponteiro dos segundos na cor da marca, andando de segundo em segundo.
 * - EXTENSO: "Nove e cinquenta e um" / "da noite" em Fraunces itálica (só em português: [HoraPorExtenso]).
 * - EMPILHADO: a hora em cima, o minuto embaixo e mais apagado.
 * Na virada do minuto o texto esmaece e volta (0,35 s + 0,6 s). Tamanhos do protótipo (960x540 dp), vezes [fator].
 */
class RelogioProtetor(ctx: Context, private val estilo: String, private val fator: Float, private val centro: Boolean) : FrameLayout(ctx) {
    private val branco = 0xFFFFFFFF.toInt()
    private val sombra = 0x59000000
    private val texto: TextView?
    private val mostrador: Mostrador?
    private var mostrado = ""

    init {
        clipChildren = false; clipToPadding = false
        if (estilo == Protetor.REL_PONTEIROS) {
            texto = null
            mostrador = Mostrador(ctx)
            val lado = dp(208 * fator)
            addView(mostrador, LayoutParams(lado, lado, if (centro) Gravity.CENTER_HORIZONTAL else Gravity.START))
        } else {
            mostrador = null
            texto = TextView(ctx).apply {
                setTextColor(branco); includeFontPadding = false
                setShadowLayer(dp(15f).toFloat(), 0f, dp(1f).toFloat(), sombra)
                gravity = if (centro) Gravity.CENTER_HORIZONTAL else Gravity.START
                when (estilo) {
                    Protetor.REL_EDITORIAL -> { typeface = Fontes.fraunces300(ctx); tamanho(140f); letterSpacing = -0.02f }
                    Protetor.REL_MONUMENTAL -> { typeface = Fontes.nunito(ctx, 600); tamanho(178f); letterSpacing = -0.06f }
                    Protetor.REL_EXTENSO -> {
                        typeface = Fontes.fraunces(ctx); tamanho(50f); letterSpacing = -0.01f
                        setLineSpacing(0f, 1.02f); maxWidth = dp(620 * fator)
                    }
                    Protetor.REL_EMPILHADO -> { typeface = Fontes.nunito(ctx, 200); tamanho(104f); letterSpacing = -0.03f; setLineSpacing(0f, 0.82f) }
                    else -> { typeface = Fontes.nunito(ctx, 200); tamanho(125f); letterSpacing = -0.045f }
                }
            }
            addView(texto, LayoutParams(-2, -2, if (centro) Gravity.CENTER_HORIZONTAL else Gravity.START))
        }
    }

    private fun TextView.tamanho(sp: Float) = setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * fator)

    /** A cada minuto (e ao abrir). [animar] = esmaece e volta; sem animação na primeira vez. */
    fun atualizar(agora: Long, animar: Boolean) {
        val c = Calendar.getInstance().apply { timeInMillis = agora }
        val h = c.get(Calendar.HOUR_OF_DAY); val m = c.get(Calendar.MINUTE)
        mostrador?.let { it.horaMin = h * 60 + m; it.invalidate(); return }
        val t = texto ?: return
        val novo = conteudo(h, m)
        if (novo.toString() == mostrado) return
        mostrado = novo.toString()
        if (!animar) { t.text = novo; return }
        t.animate().cancel()
        t.animate().alpha(0f).translationY(-dp(6f).toFloat()).setDuration(350).withLayer().withEndAction {
            t.text = novo
            t.translationY = dp(6f).toFloat()
            t.animate().alpha(1f).translationY(0f).setDuration(600).withLayer().start()
        }.start()
    }

    private fun conteudo(h: Int, m: Int): CharSequence {
        val hh = String.format(Locale.ROOT, "%02d", h); val mm = String.format(Locale.ROOT, "%02d", m)
        return when (estilo) {
            Protetor.REL_EDITORIAL, Protetor.REL_MONUMENTAL -> "$hh:$mm"
            Protetor.REL_EXTENSO -> {
                val portugal = Locale.getDefault().country.equals("PT", true)
                val (frase, periodo) = HoraPorExtenso.texto(h, m, portugal)
                SpannableStringBuilder(frase).apply {
                    if (periodo.isNotEmpty()) {
                        val i = length; append("\n").append(periodo)
                        setSpan(RelativeSizeSpan(0.62f), i, length, 0)
                        setSpan(ForegroundColorSpan(0x9EFFFFFF.toInt()), i, length, 0)
                    }
                }
            }
            Protetor.REL_EMPILHADO -> SpannableStringBuilder(hh).apply {
                if (android.os.Build.VERSION.SDK_INT >= 28) setSpan(TypefaceSpan(Fontes.nunito(context, 600)), 0, length, 0)
                val i = length; append("\n").append(mm)
                setSpan(ForegroundColorSpan(0x9EFFFFFF.toInt()), i, length, 0)
            }
            else -> SpannableStringBuilder().apply {
                // Fino (o de antes): hora cheia forte, minuto fino, ":" apagado com um espaço finíssimo antes.
                append(hh); if (android.os.Build.VERSION.SDK_INT >= 28) setSpan(TypefaceSpan(Fontes.nunito(context, 600)), 0, length, 0)
                val i = length; append(" :"); setSpan(ForegroundColorSpan(0xB3FFFFFF.toInt()), i, length, 0)
                append(mm)
            }
        }
    }

    fun soltar() { mostrador?.parar(); texto?.animate()?.cancel() }

    /** O mostrador de ponteiros. Redesenha uma vez por segundo (o ponteiro dos segundos "pula", como um relógio de quartzo). */
    private inner class Mostrador(ctx: Context) : View(ctx) {
        var horaMin = 0
        private val traco = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = branco }
        private val fundo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2E080A10 }
        private val marca = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = COR_MARCA; style = Paint.Style.FILL }
        private val tique = object : Runnable {
            override fun run() { invalidate(); postDelayed(this, 1000 - System.currentTimeMillis() % 1000 + 5) }
        }

        init { setLayerType(LAYER_TYPE_SOFTWARE, null) }   // a sombra dos ponteiros (setShadowLayer) pede desenho em software
        override fun onAttachedToWindow() { super.onAttachedToWindow(); tique.run() }
        override fun onDetachedFromWindow() { parar(); super.onDetachedFromWindow() }
        fun parar() = removeCallbacks(tique)

        override fun onDraw(c: Canvas) {
            val r = width / 2f; val cx = r; val cy = height / 2f; val k = r / 100f
            c.drawCircle(cx, cy, 99 * k, fundo)
            traco.setShadowLayer(6 * k, 0f, 1.5f * k, 0x73000000)
            for (i in 0 until 60) {
                val cheia = i % 5 == 0
                val a = Math.toRadians(i * 6.0)
                val r1 = (if (cheia) 84 else 90) * k; val r2 = 96 * k
                traco.strokeWidth = (if (cheia) 3f else 1.2f) * k
                traco.alpha = if (cheia) 242 else 102
                c.drawLine(cx + r1 * sin(a).toFloat(), cy - r1 * cos(a).toFloat(), cx + r2 * sin(a).toFloat(), cy - r2 * cos(a).toFloat(), traco)
            }
            traco.alpha = 255
            val s = (System.currentTimeMillis() / 1000) % 60
            ponteiro(c, cx, cy, ((horaMin / 60f) % 12) * 30f, 50 * k, 12 * k, 7 * k, traco)
            ponteiro(c, cx, cy, (horaMin % 60) * 6f + s / 10f, 78 * k, 14 * k, 4.5f * k, traco)
            traco.color = COR_MARCA
            ponteiro(c, cx, cy, s * 6f, 84 * k, 24 * k, 1.8f * k, traco)
            traco.color = branco
            c.drawCircle(cx, cy, 4.5f * k, marca)
        }

        private fun ponteiro(c: Canvas, cx: Float, cy: Float, graus: Float, frente: Float, tras: Float, largura: Float, p: Paint) {
            val a = Math.toRadians(graus.toDouble())
            val sx = sin(a).toFloat(); val cy2 = cos(a).toFloat()
            p.strokeWidth = largura
            c.drawLine(cx - tras * sx, cy + tras * cy2, cx + frente * sx, cy - frente * cy2, p)
        }
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private companion object { const val COR_MARCA = DesignTokens.ORANGE }
}
