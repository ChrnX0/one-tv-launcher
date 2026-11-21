package br.paulo.tvlauncher.alarm

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.settings.Fontes

/**
 * Lista de opções do editor de alarme (21/11/2026, mistura das propostas A e B que o Paulo escolheu): uma linha por opção,
 * o NOME em cinza numa coluna fixa e o VALOR em branco ao lado, todas sempre à vista (nada rola nem some). A linha em
 * foco ganha o fundo claro e a barrinha laranja à esquerda. A explicação da opção em foco NÃO fica aqui: quem usa a
 * lista mostra [Item.info] num lugar fixo (ver [aoMudar]).
 *
 * Por que não a CrossColumn (a coluna da cruz): nela as opções longe do foco encolhem e apagam, e na foto do Paulo
 * (21/11) nome e valor tinham o mesmo peso; ficou confuso. Aqui é o padrão das Configurações do Google TV e do Relógio
 * do Android: rótulo à esquerda, valor à direita.
 */
class OptionList(ctx: Context) : LinearLayout(ctx) {

    /** [rotulo] vazio = linha de ação ("Salvar", "Apagar"): só o [valor], em branco. */
    class Item(val rotulo: CharSequence, val valor: CharSequence, val info: CharSequence = "")

    companion object {
        const val FONTE_ROTULO = 18f
        const val FONTE_VALOR = 22f
        const val ALTURA = 38          // dp por linha
        const val LARGURA_ROTULO = 150 // dp da coluna dos nomes
        const val COR_ROTULO = 0xFFA39C95.toInt()
    }

    private class Linha(val raiz: LinearLayout, val barra: View, val rotulo: TextView, val valor: TextView)

    private val linhas = ArrayList<Linha>()
    private var itens: List<Item> = emptyList()
    var escolhido = 0
        private set
    /** Chamado quando o foco muda de linha ou os itens mudam: quem usa mostra o [Item.info] do escolhido. */
    var aoMudar: (Item?) -> Unit = {}
    var ativa = true
        set(v) { field = v; pintar() }

    init { orientation = VERTICAL }

    val tamanho get() = itens.size

    fun definir(novos: List<Item>, escolher: Int = escolhido) {
        itens = novos
        while (linhas.size < novos.size) linhas.add(criarLinha().also { addView(it.raiz) })
        while (linhas.size > novos.size) { removeView(linhas.last().raiz); linhas.removeAt(linhas.size - 1) }
        for ((i, it) in novos.withIndex()) {
            val l = linhas[i]
            l.rotulo.text = it.rotulo
            l.rotulo.visibility = if (it.rotulo.isEmpty()) GONE else VISIBLE
            l.valor.text = it.valor
        }
        escolhido = escolher.coerceIn(0, (novos.size - 1).coerceAtLeast(0))
        pintar()
    }

    /** Anda [delta] linhas; false = já está na ponta (quem chama toca o som de borda). */
    fun mover(delta: Int): Boolean {
        val n = escolhido + delta
        if (n !in itens.indices) return false
        escolhido = n
        pintar()
        return true
    }

    fun descricao(): String = itens.getOrNull(escolhido)?.let {
        listOf(it.rotulo, it.valor, it.info).filter { t -> t.isNotEmpty() }.joinToString(". ")
    }.orEmpty()

    private fun pintar() {
        for ((i, l) in linhas.withIndex()) {
            val foco = i == escolhido && ativa
            l.raiz.background = if (foco) fundoFoco() else null
            l.barra.visibility = if (foco) VISIBLE else INVISIBLE
            l.valor.typeface = Fontes.nunito(if (foco) 800 else 700)
            l.valor.alpha = if (i == escolhido || ativa) 1f else 0.85f
        }
        aoMudar(itens.getOrNull(escolhido))
    }

    private fun fundoFoco() = GradientDrawable().apply { setColor(Estilo.SUPERFICIE_ALTA); cornerRadius = dp(10f) }

    private fun criarLinha(): Linha {
        val barra = View(context).apply { background = GradientDrawable().apply { setColor(Estilo.ACENTO); cornerRadius = dp(2f) } }
        val rotulo = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_ROTULO); setTextColor(COR_ROTULO); typeface = Fontes.nunito(700)
            includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val valor = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_VALOR); setTextColor(Estilo.TEXTO); typeface = Fontes.nunito(700)
            includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }
        val raiz = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(barra, LayoutParams(dpi(4), dpi(22)).apply { marginStart = dpi(6); marginEnd = dpi(12) })
            addView(rotulo, LayoutParams(dpi(LARGURA_ROTULO), LayoutParams.WRAP_CONTENT))
            addView(valor, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpi(12) })
        }
        raiz.layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, dpi(ALTURA))
        return Linha(raiz, barra, rotulo, valor)
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density
    private fun dpi(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
