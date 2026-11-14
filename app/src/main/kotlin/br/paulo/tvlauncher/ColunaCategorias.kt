package br.paulo.tvlauncher

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.AbsListView
import android.widget.AdapterView
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView

/**
 * A coluna esquerda do player, igual em TV, Filmes e Séries (esboços aprovados pelo Paulo em 14/11/2026,
 * esboco-colunas-player, filmes/series/tv.png):
 *
 *   Filmes                      <- nome da seção
 *   ┌───────────────────────┐
 *   │ ⌕ Buscar               │   <- bloco fixo de AÇÕES (não rola; caixa própria, linhas mais baixas)
 *   │ ☰ Filtro               │
 *   │ ★ Interesses        12 │
 *   │ ▶ Continuar          4 │
 *   │ ✓ Assistidos        87 │
 *   └───────────────────────┘
 *   LISTAS                      <- só título (não recebe foco)
 *   ▦ Todos            20.975   <- FIXAS (não rolam): Todos; na TV também Esportes
 *   ───────────────────────
 *   4K                    312   <- categorias de A a Z, ROLANDO
 *   Ação  +12           1.204
 *   ...
 *
 * São três ListView empilhadas (cada uma só desenha o que cabe). O ▲▼ na ponta de uma passa para a vizinha (feito aqui,
 * não pela busca de foco do Android, que escolheria a linha mais perto em vez da primeira/última).
 *
 * @param aoParar o foco parou numa categoria (não numa ação) por 180 ms: a tela troca o catálogo da direita.
 * @param aoOk OK numa linha (ação ou categoria).
 * @param aoSegurar OK segurado numa linha (null = nada). Na TV, esconde/mostra a categoria.
 */
class ColunaCategorias(
    private val act: Activity,
    titulo: String,
    private val aoParar: (String) -> Unit,
    private val aoOk: (String) -> Unit,
    private val aoSegurar: ((String) -> Unit)? = null,
) {
    /**
     * Uma linha. [icone]: IconePlayer.Tipo ou IconeMidia.Tipo (null = sem ícone). [quantos] < 0 = sem número.
     * [novos] > 0 = etiqueta "+N" (títulos novos desde a última visita). [destaque] = cor de atenção mesmo sem estar
     * escolhida (ex.: Filtro ativo). [detalhe] = texto curto no lugar do número (ex.: o filtro em vigor).
     */
    data class Linha(
        val chave: String, val nome: String, val quantos: Int = -1, val icone: Any? = null,
        val novos: Int = 0, val destaque: Boolean = false, val detalhe: String = "",
    )

    private val tela = Handler(Looper.getMainLooper())
    private val papel = Estilo.TEXTO
    private val apagado = Estilo.TEXTO_3
    private val ambar = Estilo.ATENCAO
    private val fonteMedia = Fontes.manrope(500)

    private var grupos: Array<List<Linha>> = arrayOf(emptyList(), emptyList(), emptyList())
    /** A categoria cujo catálogo está à direita (cor de atenção). */
    var escolhida: String = ""
        private set

    private val alturaAcao = dp(40)
    private val alturaLinha = dp(46)

    private val listas: Array<ListView> = Array(3) { i -> lista(i) }
    private val acoes get() = listas[0]
    private val fixas get() = listas[1]
    private val rola get() = listas[2]
    private val tituloListas = TextView(act).apply {
        text = act.getString(R.string.coluna_listas); textSize = Estilo.Leitura.ROTULO; typeface = Fontes.manrope(600)
        letterSpacing = 0.12f; setTextColor(apagado); setPadding(dp(14), dp(14), dp(10), dp(4))
    }
    private val divisoria = View(act).apply { setBackgroundColor(Estilo.BORDA) }

    val raiz: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(TextView(act).apply {
            text = titulo; textSize = 24f; typeface = Fontes.manrope(700); setTextColor(papel); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END; setPadding(dp(6), 0, dp(6), dp(10))
        })
        // O bloco fixo de ações numa caixa própria, um pouco mais clara que o fundo (o esboço aprovado).
        addView(acoes.apply {
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Estilo.SUPERFICIE); setStroke(dp(1), Estilo.BORDA) }
            setPadding(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
        }, LinearLayout.LayoutParams(-1, 0))
        addView(tituloListas)
        addView(fixas, LinearLayout.LayoutParams(-1, 0))
        addView(divisoria, LinearLayout.LayoutParams(-1, dp(1)).apply { setMargins(dp(8), dp(2), dp(8), dp(4)) })
        addView(rola, LinearLayout.LayoutParams(-1, 0, 1f))
    }

    /**
     * Troca o conteúdo. Mesmas linhas de antes = só recolore (não religa as linhas: medido na Sala, 13/11/2026, era a
     * maior parte do tempo de trocar de categoria). Sem foco na coluna, a seleção vai para a [escolhida].
     */
    fun mostrar(acoes: List<Linha>, fixas: List<Linha>, rolando: List<Linha>, escolhida: String) {
        val novo = arrayOf(acoes, fixas, rolando)
        val mudou = (0..2).map { novo[it] != grupos[it] }
        this.escolhida = escolhida
        grupos = novo
        for (i in 0..2) {
            if (mudou[i]) (listas[i].adapter as BaseAdapter).notifyDataSetChanged() else recolorir(i)
        }
        ajustarAltura(this.acoes, acoes.size, alturaAcao, dp(8))
        ajustarAltura(this.fixas, fixas.size, alturaLinha, 0)
        tituloListas.visibility = if (fixas.isEmpty() && rolando.isEmpty()) View.GONE else View.VISIBLE
        divisoria.visibility = if (fixas.isEmpty() || rolando.isEmpty()) View.GONE else View.VISIBLE
        if (!temFoco()) onde(escolhida)?.let { (i, pos) -> listas[i].setSelection(pos) }
    }

    /** Muda só a categoria escolhida (a cor), sem mexer nas linhas. */
    fun escolher(chave: String) { escolhida = chave; for (i in 0..2) recolorir(i) }

    fun temFoco(): Boolean = listas.any { it.hasFocus() }

    /** Foco na linha da categoria escolhida (ou na primeira ação, se ela não estiver na coluna). */
    fun focar() {
        val (i, pos) = onde(escolhida) ?: (0 to 0)
        val l = listas[i].takeIf { grupos[i].isNotEmpty() } ?: listas.firstOrNull { it.count > 0 } ?: return
        l.requestFocus(); l.setSelection(if (l === listas[i]) pos else 0)
    }

    /** Foco numa ação do bloco fixo (ex.: "Continuar" ao abrir a tela). */
    fun focarLinha(chave: String) {
        val (i, pos) = onde(chave) ?: return
        listas[i].requestFocus(); listas[i].setSelection(pos)
    }

    /** A chave da linha em foco, ou null se o foco não está na coluna. */
    fun chaveEmFoco(): String? {
        val i = listas.indexOfFirst { it.hasFocus() }.takeIf { it >= 0 } ?: return null
        return grupos[i].getOrNull(listas[i].selectedItemPosition)?.chave
    }

    fun parar() = tela.removeCallbacksAndMessages(null)

    private fun onde(chave: String): Pair<Int, Int>? {
        for (i in 0..2) { val p = grupos[i].indexOfFirst { it.chave == chave }; if (p >= 0) return i to p }
        return null
    }

    private fun ajustarAltura(l: ListView, n: Int, h: Int, extra: Int) {
        val alvo = if (n == 0) 0 else n * h + extra
        l.visibility = if (n == 0) View.GONE else View.VISIBLE
        if (l.layoutParams.height != alvo) { l.layoutParams = l.layoutParams.apply { height = alvo } }
    }

    private fun recolorir(i: Int) {
        val l = listas[i]; val primeiro = l.firstVisiblePosition
        for (k in 0 until l.childCount) {
            val linha = grupos[i].getOrNull(primeiro + k) ?: continue
            pintar(l.getChildAt(k), linha)
        }
    }

    // ---------------------------------------------------------------- linhas

    private class Pecas(val icone: ImageView, val nome: TextView, val novos: TextView, val numero: TextView)

    private fun adaptador(i: Int) = object : BaseAdapter() {
        override fun getCount() = grupos[i].size
        override fun getItem(p: Int) = grupos[i][p]
        override fun getItemId(p: Int) = p.toLong()
        override fun getView(p: Int, reuso: View?, pai: ViewGroup?): View {
            val v = reuso ?: novaLinha(if (i == 0) alturaAcao else alturaLinha)
            pintar(v, grupos[i][p])
            return v
        }
    }

    private fun novaLinha(altura: Int): View {
        val linha = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), 0, dp(12), 0)
            layoutParams = AbsListView.LayoutParams(-1, altura)
        }
        val icone = ImageView(act)
        linha.addView(icone, LinearLayout.LayoutParams(dp(18), dp(18)).apply { marginEnd = dp(12) })
        val nome = TextView(act).apply { textSize = Estilo.Leitura.CATEGORIA; typeface = fonteMedia; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        linha.addView(nome, LinearLayout.LayoutParams(0, -2, 1f))
        // "+12": títulos novos desde a última visita, numa pílula verde (esboço direita-filmes.png).
        val novos = TextView(act).apply {
            textSize = 11.5f; typeface = Fontes.manrope(700); setTextColor(Estilo.FUNDO); setPadding(dp(6), dp(1), dp(6), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(COR_NOVOS) }
        }
        linha.addView(novos, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        // Folga entre o nome cortado e o número (12/11: "Reality show / Program…" encostava no 304).
        val numero = TextView(act).apply { textSize = Estilo.Leitura.CONTAGEM; setTextColor(apagado); maxLines = 1 }
        linha.addView(numero, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        linha.tag = Pecas(icone, nome, novos, numero)
        return linha
    }

    private fun pintar(v: View, l: Linha) {
        val p = v.tag as? Pecas ?: return
        val cor = if (l.chave == escolhida || l.destaque) ambar else papel
        p.nome.text = l.nome
        p.nome.setTextColor(cor)
        if (l.icone == null) p.icone.visibility = View.GONE else {
            p.icone.visibility = View.VISIBLE; p.icone.setImageDrawable(icone(l.icone, if (l.chave == escolhida || l.destaque) ambar else COR_ICONE))
        }
        p.novos.visibility = if (l.novos > 0) View.VISIBLE else View.GONE
        if (l.novos > 0) p.novos.text = act.getString(R.string.coluna_novos, milhar(l.novos))
        p.numero.text = when { l.detalhe.isNotEmpty() -> l.detalhe; l.quantos < 0 -> ""; else -> milhar(l.quantos) }
        p.numero.setTextColor(if (l.detalhe.isNotEmpty()) ambar else apagado)
    }

    // Um Drawable por ícone e cor (as linhas são recicladas; criar um a cada desenho seria lixo à toa na TV de 2 GB).
    private val icones = HashMap<Pair<Any, Int>, Drawable>()

    private fun icone(tipo: Any, cor: Int): Drawable = icones.getOrPut(tipo to cor) {
        when (tipo) {
            is IconePlayer.Tipo -> IconePlayer(tipo, cor)
            else -> IconeMidia(tipo as IconeMidia.Tipo, cor)
        }.apply { setBounds(0, 0, dp(18), dp(18)) }
    }

    // ---------------------------------------------------------------- listas e teclas

    private val avisarParada = Runnable {
        val i = listas.indexOfFirst { it.hasFocus() }.takeIf { it > 0 } ?: return@Runnable   // ações não trocam o catálogo
        grupos[i].getOrNull(listas[i].selectedItemPosition)?.let { aoParar(it.chave) }
    }

    private fun lista(i: Int) = ListView(act).apply {
        divider = null; isVerticalScrollBarEnabled = false; itemsCanFocus = false; isFocusable = true
        choiceMode = AbsListView.CHOICE_MODE_NONE; cacheColorHint = Color.TRANSPARENT; setDrawSelectorOnTop(false)
        // A moldura branca do foco só na lista que tem o foco (é o que diz em que lugar da coluna a pessoa está).
        val moldura = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(Estilo.SUPERFICIE_ALTA); setStroke(dp(2), Estilo.FOCO) }
        val nenhuma = ColorDrawable(Color.TRANSPARENT)
        selector = nenhuma
        setOnFocusChangeListener { _, tem ->
            selector = if (tem) moldura else nenhuma; invalidate()
            tela.removeCallbacks(avisarParada); if (tem && i > 0) tela.postDelayed(avisarParada, ESPERA_MS)
        }
        adapter = adaptador(i)
        onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                // Trocar o catálogo a cada passo travaria ao descer rápido: espera o dedo parar.
                tela.removeCallbacks(avisarParada)
                if (hasFocus() && i > 0) tela.postDelayed(avisarParada, ESPERA_MS)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        setOnItemClickListener { _, _, pos, _ -> grupos[i].getOrNull(pos)?.let { tela.removeCallbacks(avisarParada); aoOk(it.chave) } }
        isLongClickable = aoSegurar != null
        setOnItemLongClickListener { _, _, pos, _ -> grupos[i].getOrNull(pos)?.let { aoSegurar?.invoke(it.chave) }; true }
        setOnKeyListener { _, codigo, e ->
            if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (codigo) {
                KeyEvent.KEYCODE_DPAD_DOWN -> if (selectedItemPosition >= count - 1) pularPara(i, +1) else false
                KeyEvent.KEYCODE_DPAD_UP -> if (selectedItemPosition <= 0) pularPara(i, -1) else false
                else -> false
            }
        }
    }

    /** ▲▼ na ponta da lista [de]: vai para a próxima lista não vazia no [sentido]. Sem vizinha, fica (consome a tecla). */
    private fun pularPara(de: Int, sentido: Int): Boolean {
        var j = de + sentido
        while (j in 0..2 && grupos[j].isEmpty()) j += sentido
        if (j !in 0..2) return true
        val l = listas[j]
        l.requestFocus()
        l.setSelection(if (sentido > 0) 0 else grupos[j].size - 1)
        return true
    }

    /** 20975 -> "20.975" (pt) / "20,975" (en): o separador de milhar do idioma da TV. */
    private fun milhar(n: Int) = String.format(java.util.Locale.getDefault(), "%,d", n)

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val ESPERA_MS = 180L
        const val COR_NOVOS = 0xFF5FD3B0.toInt()   // o verde-água dos ícones no esboço
        const val COR_ICONE = 0xFF5FD3B0.toInt()
    }
}

/** Texto de janela (Estilo.lista) com um ícone desenhado na frente, como o olho riscado de "Mostrar ocultas". */
object JanelaIcones {
    fun comIcone(ctx: android.content.Context, tipo: IconePlayer.Tipo, texto: String): CharSequence {
        val lado = (20 * ctx.resources.displayMetrics.density).toInt()
        val d = IconePlayer(tipo, Estilo.TEXTO).apply { setBounds(0, 0, lado, lado) }
        return android.text.SpannableString("\u00A0  $texto").apply {
            setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }
}
