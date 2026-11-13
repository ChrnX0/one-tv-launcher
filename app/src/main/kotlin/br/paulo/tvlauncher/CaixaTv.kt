package br.paulo.tvlauncher

import android.app.Dialog
import android.content.Context
import android.content.DialogInterface
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Pergunta por cima da tela no cartão do sistema de design (12/11/2026, passada na TV: a busca de Filmes ainda abria a
 * caixa cinza do Android com campo branco e borda laranja). Faz o papel do AlertDialog.Builder com a mesma forma de
 * usar (título, texto, campo, botões), para trocar as perguntas que sobraram sem reescrever cada uma:
 * cartão [Estilo.CARTAO], título [Estilo.Papel.TITULO], texto em LEGENDA, botões em pílula ([Estilo.botao]) com o
 * seguro (Cancelar) à esquerda e o foco nele, e os campos de texto no mesmo estilo das Configurações.
 */
class CaixaTv(private val ctx: Context, @Suppress("UNUSED_PARAMETER") tema: Int = 0) {
    private var titulo: CharSequence? = null
    private var mensagem: CharSequence? = null
    private var corpo: View? = null
    private var positivo: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var negativo: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var neutro: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>? = null
    private var aoCancelar: (() -> Unit)? = null
    private var aoFechar: (() -> Unit)? = null

    fun setTitle(t: CharSequence?) = apply { titulo = t }
    fun setTitle(id: Int) = apply { titulo = ctx.getString(id) }
    fun setMessage(m: CharSequence?) = apply { mensagem = m }
    fun setMessage(id: Int) = apply { mensagem = ctx.getString(id) }
    fun setView(v: View) = apply { corpo = v }
    fun setPositiveButton(t: CharSequence, f: ((DialogInterface, Int) -> Unit)?) = apply { positivo = t to f }
    fun setPositiveButton(id: Int, f: ((DialogInterface, Int) -> Unit)?) = setPositiveButton(ctx.getString(id), f)
    fun setNegativeButton(t: CharSequence, f: ((DialogInterface, Int) -> Unit)?) = apply { negativo = t to f }
    fun setNegativeButton(id: Int, f: ((DialogInterface, Int) -> Unit)?) = setNegativeButton(ctx.getString(id), f)
    fun setNeutralButton(t: CharSequence, f: ((DialogInterface, Int) -> Unit)?) = apply { neutro = t to f }
    fun setOnCancelListener(f: (DialogInterface) -> Unit) = apply { aoCancelar = { f(janela!!) } }
    fun setOnDismissListener(f: (DialogInterface) -> Unit) = apply { aoFechar = { f(janela!!) } }

    private var janela: Janela? = null

    /** O Dialog pronto. getButton(BUTTON_*) devolve a pílula, como no AlertDialog. */
    class Janela(ctx: Context) : Dialog(ctx) {
        internal val botoes = HashMap<Int, TextView>()
        fun getButton(qual: Int): TextView? = botoes[qual]
    }

    fun create(): Janela {
        val d = Janela(ctx)
        janela = d
        val cartao = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(Estilo.E3 + 8), dp(Estilo.E3 + 8), dp(Estilo.E3 + 8), dp(Estilo.E3 + 8))
            background = Estilo.fundo(ctx, Estilo.CARTAO, Estilo.RAIO, Estilo.VIDRO_BORDA)
        }
        titulo?.let { cartao.addView(Estilo.texto(ctx, Estilo.Papel.TITULO, it).apply { textSize = 22f }) }
        mensagem?.takeIf { it.isNotEmpty() }?.let {
            // Texto longo (diário, resultado de teste) rola dentro do cartão em vez de empurrar os botões para fora.
            cartao.addView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(Estilo.texto(ctx, Estilo.Papel.LEGENDA, it).apply { textSize = 16f; setLineSpacing(0f, 1.25f) })
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E1 + 4) })
        }
        corpo?.let { v ->
            (v.parent as? ViewGroup)?.removeView(v)
            estilizarCampos(v)
            cartao.addView(v, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
        }
        // Ordem do Estilo.dialogo: o seguro à esquerda (Cancelar), o neutro no meio, a ação à direita.
        val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END; clipChildren = false }
        fun botao(par: Pair<CharSequence, ((DialogInterface, Int) -> Unit)?>?, qual: Int, primario: Boolean) {
            par ?: return
            val b = Estilo.botao(ctx, par.first.toString(), primario)
            b.setOnClickListener { d.dismiss(); par.second?.invoke(d, qual) }
            linha.addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (linha.childCount > 0) marginStart = dp(Estilo.E2) })
            d.botoes[qual] = b
        }
        botao(negativo, DialogInterface.BUTTON_NEGATIVE, false)
        botao(neutro, DialogInterface.BUTTON_NEUTRAL, false)
        botao(positivo, DialogInterface.BUTTON_POSITIVE, true)
        if (linha.childCount > 0) cartao.addView(linha, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E3) })

        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(cartao)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setLayout(dp(640), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        aoCancelar?.let { f -> d.setOnCancelListener { f() } }
        aoFechar?.let { f -> d.setOnDismissListener { f() } }
        // Sem campo, o foco começa no botão seguro; com campo, quem chama põe o foco nele (setOnShowListener).
        if (corpo == null) d.setOnShowListener { (d.botoes[DialogInterface.BUTTON_NEGATIVE] ?: d.botoes.values.firstOrNull())?.requestFocus() }
        return d
    }

    fun show(): Janela = create().also { it.show() }

    private fun dp(v: Int) = Estilo.dp(ctx, v)

    /** Campos de texto no padrão das Configurações: superfície escura, fio claro e borda branca no foco. */
    private fun estilizarCampos(v: View) {
        if (v is EditText) {
            val normal = Estilo.fundo(ctx, Estilo.SUPERFICIE_ALTA, Estilo.RAIO_PEQUENO, Estilo.BORDA)
            val focado = Estilo.fundo(ctx, Estilo.SUPERFICIE_ALTA, Estilo.RAIO_PEQUENO, Estilo.FOCO, 2)
            v.background = if (v.isFocused) focado else normal
            v.setTextColor(Estilo.TEXTO)
            v.setHintTextColor(Estilo.TEXTO_2)
            v.textSize = 18f
            v.setPadding(dp(Estilo.E2), dp(Estilo.E1 + 4), dp(Estilo.E2), dp(Estilo.E1 + 4))
            val antes = v.onFocusChangeListener
            v.setOnFocusChangeListener { c, f -> c.background = if (f) focado else normal; antes?.onFocusChange(c, f) }
            if (v.inputType == InputType.TYPE_NULL) v.inputType = InputType.TYPE_CLASS_TEXT
            return
        }
        if (v is ViewGroup) for (i in 0 until v.childCount) estilizarCampos(v.getChildAt(i))
        if (v is TextView && v !is EditText) v.setTextColor(Estilo.TEXTO_2)
    }
}
