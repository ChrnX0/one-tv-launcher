package br.paulo.onetv.celular

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.widget.TextView

/** As poucas peças das telas nativas do app, nas cores do painel (fundo grafite, botão branco). Letra grande. */
object Visual {
    const val FUNDO = 0xFF0B0F17.toInt()
    const val SUPERFICIE = 0xFF161B26.toInt()
    const val TEXTO = 0xFFE8ECF2.toInt()
    const val TEXTO_2 = 0xFF9AA3B2.toInt()

    fun dp(ctx: Context, v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    fun texto(ctx: Context, s: String, sp: Float = 17f, cor: Int = TEXTO, negrito: Boolean = false) = TextView(ctx).apply {
        text = s; setTextColor(cor); setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (negrito) typeface = Typeface.DEFAULT_BOLD
        setLineSpacing(0f, 1.2f)
    }

    fun botao(ctx: Context, s: String, primario: Boolean = true, acao: () -> Unit) = TextView(ctx).apply {
        text = s; gravity = Gravity.CENTER; setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f); typeface = Typeface.DEFAULT_BOLD
        setTextColor(if (primario) FUNDO else TEXTO)
        minHeight = dp(ctx, 56); setPadding(dp(ctx, 20), 0, dp(ctx, 20), 0)
        background = GradientDrawable().apply { cornerRadius = dp(ctx, 28).toFloat(); setColor(if (primario) Color.WHITE else SUPERFICIE) }
        isClickable = true; isFocusable = true
        setOnClickListener { acao() }
    }

    fun cartao(ctx: Context) = GradientDrawable().apply { cornerRadius = dp(ctx, 18).toFloat(); setColor(SUPERFICIE) }
}
