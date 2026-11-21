package br.paulo.tvlauncher.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.Rtl
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Barra + tempo (13/11/2026): a barra dá a parte pelo todo e o texto diz quanto falta ("faltam 22 min · termina às
 * 22:41"). Uma peça só, usada nos canais, nas fichas de filme e série e nos cards da grade, para ficar igual em tudo.
 */
object BarraTempo {

    /** Espessura da barra (era 3 dp, fina demais de longe). */
    const val ESPESSURA_DP = 5

    /**
     * "22 min", "1 h", "1 h 5 min". Menos de 1 min vira "1 min" (nunca "0 min" com o programa ainda no ar).
     * É o formato ÚNICO do app (18/11/2026): a Sala mostrava o mesmo programa como "1 h 3 min" na HOME, "1 h 04 min" na
     * barra do player, "1 h 04" na lista de canais e "64 min" no detalhe do canal.
     */
    fun duracao(ms: Long): String {
        val min = ((ms + 59_999) / 60_000).coerceAtLeast(1)
        return when {
            min < 60 -> "$min min"
            min % 60 == 0L -> "${min / 60} h"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    /**
     * Modelo "faltam X" com a concordância certa: "falta 1 min" / "falta 1 h 20 min" no singular, "faltam 5 min" no plural
     * (crítica de design 19/11/2026). O verbo concorda com o primeiro número; em inglês e na maioria das línguas os dois
     * textos são iguais.
     */
    fun modeloFaltam(valor: String): Int =
        if (valor.startsWith("1 ")) R.string.tempo_falta_um else R.string.tempo_faltam

    /** "faltam 22 min" / "falta 1 min", pronto. */
    fun faltam(ctx: Context, valor: String): String = ctx.getString(modeloFaltam(valor), valor)

    /** Fração já vista/passada, entre 0 e 1 (duração 0 ou negativa = 0). */
    fun fracao(passado: Long, total: Long): Float = if (total <= 0) 0f else (passado.toFloat() / total).coerceIn(0f, 1f)

    /**
     * "faltam **22 min**" ou "faltam **1 h 01** · termina às **22:41**" (com [terminaEm]). Os números vão em destaque
     * (negrito e cor do texto principal) e o resto na cor suave da peça.
     */
    fun textoFaltam(ctx: Context, restanteMs: Long, terminaEm: Long? = null): CharSequence {
        val sb = SpannableStringBuilder()
        val d = duracao(restanteMs)
        anexar(sb, ctx.getString(modeloFaltam(d)), d)
        terminaEm?.let {
            sb.append(" · ")
            anexar(sb, ctx.getString(R.string.tempo_termina_as), SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(it)))
        }
        return sb
    }

    /** "**20 de 62** episódios". */
    fun textoEpisodios(ctx: Context, vistos: Int, total: Int): CharSequence {
        val sb = SpannableStringBuilder()
        anexar(sb, ctx.getString(R.string.tempo_episodios_vistos), ctx.getString(R.string.tempo_x_de_y, vistos, total))
        return sb
    }

    /** Põe [modelo] (com um %1$s) em [sb], com o [valor] em destaque. */
    private fun anexar(sb: SpannableStringBuilder, modelo: String, valor: String) {
        val i = modelo.indexOf("%1\$s")
        if (i < 0) { sb.append(modelo); return }
        sb.append(modelo, 0, i)
        val ini = sb.length
        sb.append(valor)
        sb.setSpan(StyleSpan(Typeface.BOLD), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(ForegroundColorSpan(Estilo.TEXTO), ini, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append(modelo, i + 4, modelo.length)
    }

    /** A barra sozinha, arredondada (o ProgressBar do Android tem ponta reta e não muda de espessura direito). */
    class Barra(ctx: Context) : View(ctx) {
        var fracao = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidate() }
        // O quanto já passou/foi visto: o acento único da marca, igual à barra da cruz (era um vermelho só dos players).
        var cor = Estilo.ACENTO
            set(v) { field = v; invalidate() }
        private val fundo = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2EFFFFFF }
        private val cheio = Paint(Paint.ANTI_ALIAS_FLAG)
        private val r = RectF()

        override fun onDraw(c: Canvas) = Rtl.espelhar(this, c) { desenhar(c) }   // 16.8: enche da direita no árabe

        private fun desenhar(c: Canvas) {
            val h = height.toFloat(); val raio = h / 2
            r.set(0f, 0f, width.toFloat(), h); c.drawRoundRect(r, raio, raio, fundo)
            if (fracao <= 0f) return
            cheio.color = cor
            r.set(0f, 0f, maxOf(h, width * fracao), h); c.drawRoundRect(r, raio, raio, cheio)
        }
    }

    /** Barra + texto lado a lado. [texto] fica vazio e some quando não há o que dizer. */
    class Linha(ctx: Context, textoSp: Float = Estilo.Leitura.ITEM_DETALHE) : LinearLayout(ctx) {
        val barra = Barra(ctx)
        val texto = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, textoSp); setTextColor(Estilo.TEXTO_2); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

        init {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(barra, LayoutParams(0, Estilo.dp(ctx, ESPESSURA_DP), 1f))
            addView(texto, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = Estilo.dp(ctx, 10) })
        }

        fun mostrar(fracao: Float, t: CharSequence?) {
            barra.fracao = fracao
            texto.text = t ?: ""
            texto.visibility = if (t.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
    }
}
