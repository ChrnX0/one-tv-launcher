package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * O alto do painel direito de Filmes e Séries (esboço direita-filmes.png, aprovado pelo Paulo em 14/11/2026):
 *
 *   Ação  1.204   [Ordem: mais recentes] [Só não vistos] [★ 7+]      <- o que está na grade e por quê
 *   ┌──────────────────────────────────────────────────────────┐
 *   │ Velocidade Máxima   ★ 7,4   2024 · 1 h 58 · Ação          │  <- ficha rápida do pôster em foco
 *   │ Um piloto aposentado volta às pistas para salvar o irmão… │     (2 linhas de sinopse, se já guardada)
 *   └──────────────────────────────────────────────────────────┘
 *
 * Letra grande de propósito (pedido do Paulo: gente mais velha usa a TV): nada abaixo dos tamanhos de [Estilo.Leitura].
 */
class PainelCatalogo(private val act: Activity) {
    private val titulo = TextView(act).apply {
        textSize = TITULO_SP; typeface = Fontes.manrope(700); setTextColor(Estilo.TEXTO); maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    private val quantos = TextView(act).apply { textSize = TITULO_SP; typeface = Fontes.manrope(500); setTextColor(Estilo.TEXTO_2); maxLines = 1 }
    private val pilulas = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val info = TextView(act).apply { textSize = Estilo.Leitura.FOCADO; setTextColor(Estilo.TEXTO_2); maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val sinopse = TextView(act).apply {
        textSize = SINOPSE_SP; setTextColor(Estilo.TEXTO); maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setLineSpacing(0f, 1.1f)
    }

    /** "Ação 1.204 [pílulas]": uma linha, as pílulas rolam de lado se não couberem (nunca encolhem a letra). */
    val topo: View = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(4), 0, 0, dp(10))
        addView(titulo, LinearLayout.LayoutParams(-2, -2).apply { weight = 0f })
        addView(quantos, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        addView(HorizontalScrollView(act).apply { isHorizontalScrollBarEnabled = false; isFocusable = false; addView(pilulas) },
            LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(14) })
    }

    /** A faixa da ficha rápida: caixa um pouco mais clara que o fundo, como o bloco de ações da coluna. */
    val faixa: View = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(10), dp(18), dp(12))
        background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Estilo.SUPERFICIE); setStroke(dp(1), Estilo.BORDA) }
        addView(info)
        addView(sinopse, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
    }

    /** O título do catálogo, o total e as pílulas ([destaque] = filtro que está escondendo títulos, em âmbar). */
    fun mostrarTopo(nome: String, total: Int, pilulas: List<Pair<String, Boolean>>) {
        titulo.text = nome
        quantos.text = String.format(java.util.Locale.getDefault(), "%,d", total)
        this.pilulas.removeAllViews()
        pilulas.forEach { (t, destaque) ->
            this.pilulas.addView(TextView(act).apply {
                text = t; textSize = PILULA_SP; typeface = Fontes.manrope(600); maxLines = 1
                setTextColor(if (destaque) Estilo.FUNDO else Estilo.TEXTO)
                setPadding(dp(14), dp(5), dp(14), dp(6))
                background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(if (destaque) Estilo.ATENCAO else Estilo.SUPERFICIE_ALTA) }
            }, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(8) })
        }
    }

    /** A ficha rápida: a linha de informação e a sinopse (vazia = só a linha; a faixa não muda de altura). */
    fun mostrarFoco(linha: CharSequence, textoSinopse: String) {
        info.text = linha
        sinopse.text = textoSinopse
        sinopse.visibility = if (textoSinopse.isEmpty()) View.INVISIBLE else View.VISIBLE
    }

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    companion object {
        const val TITULO_SP = 24f
        const val PILULA_SP = 16f
        const val SINOPSE_SP = 17f

        /**
         * "Ir para a letra" (acréscimo 3): letras grandes numa janela por cima, só enquanto escolhe. Só oferece as letras
         * que existem na grade. [aoEscolher] recebe a letra ('#' = números e símbolos).
         */
        fun escolherLetra(act: Activity, presentes: Set<Char>, aoEscolher: (Char) -> Unit) {
            val letras = ColunaRegras.LETRAS.filter { it in presentes }
            if (letras.isEmpty()) return
            Estilo.lista(act, act.getString(R.string.coluna_letra_titulo), letras.map { it.toString() }) { i -> aoEscolher(letras[i]) }
        }
    }
}
