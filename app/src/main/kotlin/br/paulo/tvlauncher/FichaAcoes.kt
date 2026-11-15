package br.paulo.tvlauncher

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Fichas de filme e série no estilo C "Botões grandes" (15/11/2026, maquete escolhida para quem vê de longe): à esquerda
 * uma lista de ações em letra grande, uma embaixo da outra (▲▼ anda, OK faz), nada cortado nem empurrado para o lado;
 * à direita as informações. Peças comuns às duas telas.
 */
object FichaAcoes {
    /** Largura da coluna de ações (dp). */
    const val LARGURA_DP = 330
    const val ALTURA_DP = 54
    const val TEXTO_SP = 20f
    const val DETALHE_SP = 15f

    private fun dp(ctx: Context, v: Int) = Estilo.dp(ctx, v)

    /**
     * Uma ação: faixa larga com o texto à esquerda e um detalhe curto à direita ("de 7:01", "Temporada 2").
     * Com foco: branca e texto escuro (o foco é a coisa mais óbvia da tela); sem foco: apagada.
     */
    fun acao(ctx: Context, texto: String, detalhe: String? = null, marca: String, aoApertar: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(ctx, 20), 0, dp(ctx, 18), 0)
            isFocusable = true; isClickable = true; tag = marca
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(ctx, 14).toFloat(); setColor(Estilo.TEXTO) })
                addState(intArrayOf(), GradientDrawable().apply { cornerRadius = dp(ctx, 14).toFloat(); setColor(Estilo.SUPERFICIE); setStroke(dp(ctx, 1), Estilo.BORDA) })
            }
            addView(TextView(ctx).apply {
                text = texto; textSize = TEXTO_SP; typeface = Fontes.manrope(500); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                isDuplicateParentStateEnabled = true
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Estilo.TEXTO_NO_FOCO, Estilo.TEXTO)))
            }, LinearLayout.LayoutParams(0, -2, 1f))
            if (!detalhe.isNullOrEmpty()) addView(TextView(ctx).apply {
                text = detalhe; textSize = DETALHE_SP; maxLines = 1; isDuplicateParentStateEnabled = true
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Estilo.TEXTO_NO_FOCO_2, Estilo.TEXTO_3)))
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(ctx, 10) })
            setOnClickListener { aoApertar() }
        }

    /** A coluna das ações (rola sozinha com o foco se não couber). Devolve (o que vai na tela, onde pôr as ações). */
    fun coluna(ctx: Context): Pair<ScrollView, LinearLayout> {
        val lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val rolagem = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; addView(lista) }
        return rolagem to lista
    }

    fun adicionar(lista: LinearLayout, v: LinearLayout) {
        val ctx = lista.context
        lista.addView(v, LinearLayout.LayoutParams(-1, dp(ctx, ALTURA_DP)).apply { if (lista.childCount > 0) topMargin = dp(ctx, 10) })
    }

    /** A sinopse cortada passou de [linhas]? Só se sabe depois de desenhada. */
    fun cortada(t: TextView): Boolean = t.layout?.let { l -> l.lineCount > 0 && l.getEllipsisCount(l.lineCount - 1) > 0 } == true

    /** "Sinopse inteira": o texto todo num cartão por cima, letra grande; OK ou VOLTAR fecha. */
    fun mostrarSinopse(ctx: Context, titulo: String, texto: String) {
        val d = Dialog(ctx)
        val fechar = Estilo.botao(ctx, ctx.getString(R.string.fechar))
        fechar.setOnClickListener { d.dismiss() }
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        d.setContentView(Estilo.dialogo(ctx, null, titulo, null, listOf(fechar), larguraDp = 820).also { tela ->
            // O texto vai num ScrollView próprio (sinopse longa não passa da tela), alinhado à esquerda, letra de leitura.
            val cartao = tela.getChildAt(0) as LinearLayout
            cartao.addView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(TextView(ctx).apply { text = texto; textSize = Estilo.Leitura.SINOPSE + 2; setTextColor(Estilo.TEXTO); setLineSpacing(0f, 1.3f) })
            }, 1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 16) })
            (cartao.getChildAt(1) as ScrollView).layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(ctx, 16) }
            cartao.layoutParams = (cartao.layoutParams as android.widget.FrameLayout.LayoutParams).apply { height = dp(ctx, 440) }
        })
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        // ▲▼ rolam o texto (o botão Fechar é o único com foco; sem isto uma sinopse longa não teria como ser lida).
        d.setOnKeyListener { _, k, e ->
            val rol = ((d.window?.decorView as? ViewGroup)?.let { achar(it) })
            if (rol != null && (k == android.view.KeyEvent.KEYCODE_DPAD_DOWN || k == android.view.KeyEvent.KEYCODE_DPAD_UP)) {
                if (e.action == android.view.KeyEvent.ACTION_DOWN) rol.smoothScrollBy(0, if (k == android.view.KeyEvent.KEYCODE_DPAD_DOWN) dp(ctx, 90) else -dp(ctx, 90))
                true
            } else false
        }
        d.setOnShowListener { fechar.requestFocus() }
        d.show()
    }

    private fun achar(g: ViewGroup): ScrollView? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is ScrollView) return c
            if (c is ViewGroup) achar(c)?.let { return it }
        }
        return null
    }
}
