package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.style.AbsoluteSizeSpan
import android.text.style.ForegroundColorSpan
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.paulo.tvlauncher.mcp.ComandosIa
import br.paulo.tvlauncher.network.Cenas
import br.paulo.tvlauncher.network.CenasProntas
import br.paulo.tvlauncher.player.CanalBusca

/**
 * Tela "Cenas" na TV (14/11/2026, ideia 4). Antes a cena só rodava por link; agora também pelo controle: abre pelo
 * tile Cenas do painel rápido ou por voz ("Ok Google, abrir Cenas", alias VozCenas em AtalhoVozActivity).
 * Uma linha por cena (nome + o que ela faz); OK roda pelo mesmo caminho do link ([Cenas.rodar] → PedidoIa, com as
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
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.cenas_titulo)))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(if (cenas.isEmpty()) R.string.cenas_vazio else R.string.cenas_det)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        val lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(8), dp(4), dp(8), dp(4)); clipChildren = false; clipToPadding = false }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(16) })
        // O que a cena faz, em vez de "3 ações" (Sala, 17/11/2026: as prontas diziam o que faziam e, criadas, viravam um
        // número). As mesmas frases do aviso que a TV mostra ao rodar; sem conseguir ler, fica a contagem.
        val apps = if (cenas.isEmpty()) emptyMap() else Cenas.appsInstalados(this)
        for (c in cenas) {
            val n = Cenas.contarAcoes(c.acoes)
            val faz = try { ComandosIa.conferir(c.acoes, apps).acoes.joinToString(" · ") { it.descricao } } catch (_: Exception) { "" }
            lista.addView(linha(c.nome, faz.ifEmpty { resources.getQuantityString(R.plurals.cenas_acoes, n, n) }) {
                Fundo.rodar { Cenas.rodar(applicationContext, c.nome, "tela") }   // lê disco e confere: fora da thread da tela
                finish()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        // Degrau 3: as cenas prontas que ainda não existem, com um OK (as que pedem canal perguntam o nome antes).
        val nomes = mapOf(CenasProntas.Modelo.DORMIR to getString(R.string.cpr_dormir),
            CenasProntas.Modelo.NOVELA to getString(R.string.cpr_novela), CenasProntas.Modelo.JOGO to getString(R.string.cpr_jogo))
        val faltam = CenasProntas.faltando(nomes, cenas.map { it.nome })
        if (faltam.isNotEmpty()) lista.addView(Estilo.secao(this, getString(R.string.cpr_secao)))
        for (m in faltam) {
            val det = getString(when (m) { CenasProntas.Modelo.DORMIR -> R.string.cpr_dormir_det
                CenasProntas.Modelo.NOVELA -> R.string.cpr_novela_det; CenasProntas.Modelo.JOGO -> R.string.cpr_jogo_det })
            lista.addView(linha(nomes.getValue(m), det) {
                if (m.pedeCanal) pedirCanal(nomes.getValue(m)) { canal -> criar(m, nomes.getValue(m), canal) } else criar(m, nomes.getValue(m), null)
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
        }
        setContentView(raiz)
        lista.getChildAt(0)?.requestFocus()
    }

    private fun pedirCanal(titulo: String, aoEscolher: (String) -> Unit) {
        val campo = android.widget.EditText(this).apply {
            hint = getString(R.string.cpr_canal_dica); isSingleLine = true
            filters = arrayOf(android.text.InputFilter.LengthFilter(CanalBusca.MAX_TERMO))
        }
        CaixaTv(this).setTitle(getString(R.string.cpr_canal, titulo))
            .setView(android.widget.FrameLayout(this).apply { setPaddingRelative(dp(20), dp(8), dp(20), 0); addView(campo) })
            .setNegativeButton(getString(R.string.cl_cancelar), null)
            .setPositiveButton("OK") { _, _ -> campo.text.toString().trim().takeIf { it.isNotEmpty() }?.let(aoEscolher) }
            .show()
    }

    /** Cria a cena e reabre a tela (a nova aparece em cima, pronta para o OK). O token do link não é mostrado aqui. */
    private fun criar(m: CenasProntas.Modelo, nome: String, canal: String?) {
        val r = Cenas.criar(this, nome, CenasProntas.acoes(m, canal), Cenas.appsInstalados(this))
        if (r.startsWith("erro:")) { android.widget.Toast.makeText(this, r.removePrefix("erro:").trim(), android.widget.Toast.LENGTH_LONG).show(); return }
        recreate()
    }

    private fun linha(titulo: String, detalhe: String, aoClicar: () -> Unit): TextView = TextView(this).apply {
        isFocusable = true
        isClickable = true
        background = Estilo.superficie(context, false)
        typeface = Estilo.fonte(500)
        setPaddingRelative(dp(20), dp(12), dp(20), dp(12))
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
