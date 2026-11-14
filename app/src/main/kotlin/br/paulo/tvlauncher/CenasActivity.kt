package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Tela "Cenas" na TV (14/11/2026, ideia 4). Antes a cena só rodava por link; agora também pelo controle: abre pelo
 * tile Cenas do painel rápido ou por voz ("Ok Google, abrir Cenas", alias VozCenas em AtalhoVozActivity).
 * Uma linha por cena (nome + quantas ações); OK roda pelo mesmo caminho do link ([Cenas.rodar] → PedidoIa, com as
 * Confirmações e o aviso do que fez) e fecha a tela, para a cena abrir o que precisar por cima da HOME.
 * Criar e editar continua no painel web (Extras › Cenas): montar ação por ação com o controle seria lento demais.
 * Visual igual ao das Rotinas (Estilo), copiado de propósito.
 */
class CenasActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro: tela que derrubou esta versão (VigiaAtualizacao)
        val cenas = Cenas.lista(this)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.cenas_titulo)))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(if (cenas.isEmpty()) R.string.cenas_vazio else R.string.cenas_det)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)); clipChildren = false; clipToPadding = false }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
        for (c in cenas) {
            val n = Cenas.contarAcoes(c.acoes)
            lista.addView(linha(c.nome, resources.getQuantityString(R.plurals.cenas_acoes, n, n)) {
                Fundo.rodar { Cenas.rodar(applicationContext, c.nome, "tela") }   // lê disco e confere: fora da thread da tela
                finish()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        setContentView(raiz)
        lista.getChildAt(0)?.requestFocus()
    }

    private fun linha(titulo: String, detalhe: String, aoClicar: () -> Unit): TextView = TextView(this).apply {
        isFocusable = true
        isClickable = true
        background = Estilo.superficie(context, false)
        typeface = Estilo.fonte(500)
        setPadding(dp(20), dp(12), dp(20), dp(12))
        fun pintar(foco: Boolean) {
            val sb = SpannableStringBuilder(titulo)
            sb.setSpan(AbsoluteSizeSpan(dp(17), false), 0, sb.length, 0)
            sb.setSpan(ForegroundColorSpan(if (foco) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO), 0, sb.length, 0)
            val ini = sb.length
            sb.append("\n").append(detalhe)
            sb.setSpan(AbsoluteSizeSpan(dp(13), false), ini, sb.length, 0)
            sb.setSpan(ForegroundColorSpan(if (foco) Estilo.TEXTO_NO_FOCO_2 else Estilo.TEXTO_2), ini, sb.length, 0)
            text = sb
        }
        pintar(false)
        setOnClickListener { aoClicar() }
        setOnFocusChangeListener { v, temFoco ->
            Estilo.focarLinha(v, temFoco)
            pintar(temFoco)   // as cores estão nos spans: refaz com as do foco (texto escuro na pílula branca)
            v.animate().scaleX(if (temFoco) Estilo.ESCALA_LINHA else 1f).scaleY(if (temFoco) Estilo.ESCALA_LINHA else 1f).setDuration(Estilo.FOCO_MS).start()
        }
    }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()
}
