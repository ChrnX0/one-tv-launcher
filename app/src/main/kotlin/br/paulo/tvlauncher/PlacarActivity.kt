package br.paulo.tvlauncher

import android.app.Activity
import android.os.Bundle
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.paulo.tvlauncher.observer.Falhas
import br.paulo.tvlauncher.observer.FalhasTv
import br.paulo.tvlauncher.observer.MedidasTv
import br.paulo.tvlauncher.observer.Placar
import br.paulo.tvlauncher.observer.VitaisLocais
import br.paulo.tvlauncher.observer.VitaisTv
import br.paulo.tvlauncher.settings.Fontes
import java.text.DecimalFormatSymbols
import java.util.Locale

/**
 * "Como esta TV está indo" (elevação 2.1, onda 0, 17/11/2026): os 7 pilares do produto, cada um com o número medido
 * nesta TV, o alvo e a data da medida. Pilar sem medida diz "ainda não medido" (nunca o alvo no lugar do número).
 * As regras moram em Placar.kt; as medidas, em MedidasTv. Aberta pelas Configurações (aba Sistema) e por
 * `EXTRAS --es o placar_tela`.
 *
 * Nunca só pela cor: cada linha leva também o sinal (✓ dentro do alvo, ! fora, — sem medida) e a palavra.
 */
class PlacarActivity : Activity() {
    companion object {
        /** Vírgula ou ponto, conforme a língua da TV ("0,8 s" / "0.8 s"). */
        fun decimal(): Char = DecimalFormatSymbols.getInstance(Locale.getDefault()).decimalSeparator
    }

    private lateinit var lista: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL))
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.elv_placar_titulo)))
        raiz.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.elv_placar_sub)).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPaddingRelative(0, dp(6), 0, 0)
        })
        lista = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false }
        raiz.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(lista) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = dp(14) })
        setContentView(raiz)
        // Sete arquivos de poucos centésimos de KB: ler na hora custa menos que um quadro e a tela já abre completa.
        val linhas = runCatching { MedidasTv.linhas(this) }.getOrElse { Placar.linhas(Placar.Entradas()) }
        val quedas = runCatching { VitaisTv.resumo30(this) }.getOrNull()
        // Elevação 2.3: o que precisa de atenção vem antes dos pilares. Sem rede primeiro (abre na hora); depois, com a
        // hora da internet (relógio errado), redesenha se mudou.
        val falhas = runCatching { FalhasTv.atuais(this, comRede = false) }.getOrDefault(emptyList())
        desenhar(linhas, quedas, falhas)
        Fundo.rodar("placar-falhas") {
            val comRede = runCatching { FalhasTv.atuais(this, comRede = true) }.getOrNull() ?: return@rodar
            if (comRede != falhas) runOnUiThread { if (!isDestroyed) desenhar(linhas, quedas, comRede) }
        }
    }

    private fun nome(p: Placar.Pilar) = getString(when (p) {
        Placar.Pilar.LIGA -> R.string.elv_pilar_liga
        Placar.Pilar.AO_VIVO -> R.string.elv_pilar_ao_vivo
        Placar.Pilar.PLAYER -> R.string.elv_pilar_player
        Placar.Pilar.CASA -> R.string.elv_pilar_casa
        Placar.Pilar.CONTROLE -> R.string.elv_pilar_controle
        Placar.Pilar.TODOS -> R.string.elv_pilar_todos
        Placar.Pilar.INSTALA -> R.string.elv_pilar_instala
    })

    private fun promessa(p: Placar.Pilar) = getString(when (p) {
        Placar.Pilar.LIGA -> R.string.elv_prom_liga
        Placar.Pilar.AO_VIVO -> R.string.elv_prom_ao_vivo
        Placar.Pilar.PLAYER -> R.string.elv_prom_player
        Placar.Pilar.CASA -> R.string.elv_prom_casa
        Placar.Pilar.CONTROLE -> R.string.elv_prom_controle
        Placar.Pilar.TODOS -> R.string.elv_prom_todos
        Placar.Pilar.INSTALA -> R.string.elv_prom_instala
    })

    private fun numero(n: Placar.Numero): String = when (n) {
        is Placar.Numero.Tempo -> Placar.tempoTexto(n.ms, decimal())
        is Placar.Numero.Contagem -> getString(R.string.elv_n_de_n, n.certos, n.total)
        is Placar.Numero.Quedas -> resources.getQuantityString(R.plurals.elv_quedas, n.quedas, n.quedas, n.dias)
    }

    private fun desenhar(linhas: List<Placar.Linha>, quedas: VitaisLocais.Resumo?, falhas: List<Falhas.Tipo> = emptyList()) {
        lista.removeAllViews()
        var primeira: View? = null
        if (falhas.isNotEmpty()) {
            lista.addView(Estilo.secao(this, getString(R.string.elv_f_titulo)).apply { setPaddingRelative(dp(4), 0, 0, dp(8)) })
            for (f in falhas) {
                val l = linha(FalhasTv.frase(this, f), FalhasTv.causa(this, f), "▶  " + FalhasTv.botao(this, f), false, falha = true)
                l.setOnClickListener { FalhasTv.consertar(this, f) }
                lista.addView(l, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
                if (primeira == null) primeira = l
            }
            lista.addView(Estilo.secao(this, getString(R.string.elv_placar_titulo)).apply { setPaddingRelative(dp(4), dp(12), 0, dp(8)) })
        }
        linhas.forEachIndexed { i, l ->
            val medida = when {
                l.numero == null -> getString(R.string.elv_nao_medido)
                else -> {
                    val alvo = l.alvo?.let { getString(R.string.elv_alvo, numero(it)) }.orEmpty()
                    val agora = System.currentTimeMillis()
                    val quando = l.medidoEm?.let {
                        if (agora - it < DateUtils.MINUTE_IN_MILLIS) getString(R.string.elv_agora)
                        else DateUtils.getRelativeTimeSpanString(it, agora, DateUtils.MINUTE_IN_MILLIS)
                    }
                    listOfNotNull(numero(l.numero), alvo, quando?.toString()).joinToString("  ·  ")
                }
            }
            var detalhe = promessa(l.pilar)
            if (l.pilar == Placar.Pilar.LIGA && quedas != null) {
                val n = quedas.quedas + quedas.travadas
                detalhe += "\n" + if (n == 0) getString(R.string.elv_sem_quedas, VitaisLocais.DIAS)
                    else resources.getQuantityString(R.plurals.elv_quedas, n, n, VitaisLocais.DIAS)
            }
            val linha = linha("${i + 1}. ${nome(l.pilar)}", detalhe, medida, l.dentro)
            lista.addView(linha, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(8) })
            if (i == 0 && primeira == null) primeira = linha
        }
        primeira?.let { v -> v.requestFocus(); v.post { v.requestFocus() } }
    }

    /** Linha focável (a lista rola pelo foco); sinal + palavra à direita, nunca só a cor. */
    private fun linha(titulo: String, detalhe: String, medida: String, dentro: Boolean?, falha: Boolean = false): View {
        val (sinal, palavra, cor) = when (dentro) {
            true -> Triple("✓", getString(R.string.elv_dentro), Estilo.ACENTO)
            false -> Triple("!", if (falha) "" else getString(R.string.elv_fora), Estilo.ATENCAO)
            null -> Triple("—", "", Estilo.TEXTO_3)
        }
        val tTitulo = Estilo.texto(this, Estilo.Papel.CORPO, titulo).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f) }
        val tDetalhe = Estilo.texto(this, Estilo.Papel.LEGENDA, detalhe).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setPaddingRelative(0, dp(4), 0, 0) }
        val tMedida = Estilo.texto(this, Estilo.Papel.CORPO, medida).apply { setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); setPaddingRelative(0, dp(6), 0, 0) }
        val tSinal = TextView(this).apply {
            text = if (palavra.isEmpty()) sinal else "$sinal  $palavra"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f); typeface = Fontes.nunito(700); setTextColor(cor); gravity = Gravity.END
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPaddingRelative(dp(Estilo.E3), dp(14), dp(Estilo.E3), dp(14))
            isFocusable = true
            background = Estilo.fundo(context, Estilo.SUPERFICIE, Estilo.RAIO, Estilo.BORDA)
            addView(LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; addView(tTitulo); addView(tDetalhe); addView(tMedida) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(tSinal, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(16) })
            setOnFocusChangeListener { v, foco ->
                v.background = if (foco) Estilo.fundo(context, Estilo.FOCO, Estilo.RAIO) else Estilo.fundo(context, Estilo.SUPERFICIE, Estilo.RAIO, Estilo.BORDA)
                val claro = if (foco) Estilo.FUNDO else Estilo.TEXTO
                tTitulo.setTextColor(claro); tMedida.setTextColor(claro)
                tDetalhe.setTextColor(if (foco) Estilo.FUNDO else Estilo.TEXTO_2)
                tSinal.setTextColor(if (foco) Estilo.FUNDO else cor)
            }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
