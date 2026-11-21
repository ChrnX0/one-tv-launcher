package br.paulo.tvlauncher.player

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
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import br.paulo.tvlauncher.ConfigLauncherActivity
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.Rtl
import br.paulo.tvlauncher.home.IconeMidia
import br.paulo.tvlauncher.settings.Fontes

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
    /** Modo simples no player (PlayerAcessivel.escala): letra e linhas maiores. 1 = como sempre. */
    private val escala: Float = 1f,
    /**
     * Linhas mais baixas (TV, 18/11/2026: "os itens estão muito grandes, a lista fica limitada a dois itens"). A letra
     * continua do mesmo tamanho (idosos): só sai o ar entre as linhas.
     */
    compacta: Boolean = false,
) {
    /**
     * Uma linha. [icone]: IconePlayer.Tipo ou IconeMidia.Tipo (null = sem ícone). [quantos] < 0 = sem número.
     * [novos] > 0 = etiqueta "+N" (títulos novos desde a última visita). [destaque] = cor de atenção mesmo sem estar
     * escolhida (ex.: Filtro ativo). [detalhe] = texto curto no lugar do número (ex.: o filtro em vigor).
     * [etiqueta] = selo antes do nome (listas de fora: "Trakt", "MDBList"; esboço listas-a.png).
     */
    data class Linha(
        val chave: String, val nome: String, val quantos: Int = -1, val icone: Any? = null,
        val novos: Int = 0, val destaque: Boolean = false, val detalhe: String = "", val etiqueta: String = "",
    )

    private val tela = Handler(Looper.getMainLooper())
    private val papel = Estilo.TEXTO
    private val apagado = Estilo.TEXTO_3
    // A escolhida na cor ÚNICA de acento da marca (18/11/2026, identidade: era o âmbar de "atenção", outra cor só aqui).
    private val ambar get() = Estilo.ACENTO
    private val fonteMedia = Fontes.nunito(500)

    private var grupos: Array<List<Linha>> = arrayOf(emptyList(), emptyList(), emptyList())
    /** A categoria cujo catálogo está à direita (cor de atenção). */
    var escolhida: String = ""
        private set

    private val alturaAcao = dp(((if (compacta) 36 else 40) * escala).toInt())
    private val alturaLinha = dp(((if (compacta) 40 else 46) * escala).toInt())

    private val listas: Array<ListView> = Array(3) { i -> lista(i) }
    private val acoes get() = listas[0]
    /** A caixa das ações (Interesses, Continuar…): a ficha rápida da direita alinha o topo com ela (PainelCatalogo.alinharCom). */
    val caixaAcoes: View get() = acoes
    private val fixas get() = listas[1]
    private val rola get() = listas[2]
    private val textoListas = TextView(act).apply {
        text = act.getString(R.string.coluna_listas); textSize = Estilo.Leitura.ROTULO; typeface = Fontes.nunito(600)
        letterSpacing = 0.12f; setTextColor(apagado); setPaddingRelative(dp(14), dp(14), dp(10), dp(4))
    }
    /**
     * Botões redondos ao lado de "LISTAS" (TV, 18/11/2026, pedido do Paulo: "assim como no TV, com os ícones na frente"):
     * Todos e Guia, que saíram das linhas para abrir espaço para as categorias.
     */
    private val caixaBotoesListas = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private val tituloListas = LinearLayout(act).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        addView(textoListas, LinearLayout.LayoutParams(0, -2, 1f))
        addView(caixaBotoesListas, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8); bottomMargin = dp(2) })
    }
    private val divisoria = View(act).apply { setBackgroundColor(Estilo.BORDA) }

    /**
     * Botões redondos à direita do título (14/11/2026, pedido do Paulo na TV: "TV  ⌕ ☰"): ações que saíram do bloco
     * para abrir espaço. Chega neles com ▲ a partir da primeira linha; ◀ ▶ entre eles; ▼ volta para o bloco.
     */
    private val caixaBotoes = LinearLayout(act).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private var botoes: List<Pair<Linha, FrameLayout>> = emptyList()
    private var botoesListas: List<Pair<Linha, FrameLayout>> = emptyList()

    val raiz: LinearLayout = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL
        addView(LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(0, 0, 0, dp(10))
            addView(TextView(act).apply {
                text = titulo; textSize = Estilo.Papel.TITULO.sp; typeface = Fontes.nunito(700); setTextColor(papel); maxLines = 1
                ellipsize = TextUtils.TruncateAt.END; setPaddingRelative(dp(6), 0, dp(6), 0)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(caixaBotoes, LinearLayout.LayoutParams(-2, -2))
        })
        // O bloco fixo de ações numa caixa própria, um pouco mais clara que o fundo (o esboço aprovado).
        addView(acoes.apply {
            background = GradientDrawable().apply { cornerRadius = dp(14).toFloat(); setColor(Estilo.SUPERFICIE); setStroke(dp(1), Estilo.BORDA) }
            setPaddingRelative(dp(4), dp(4), dp(4), dp(4)); clipToPadding = false
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
        botoes.forEach { (l, v) -> pintarBotao(v, l) }; botoesListas.forEach { (l, v) -> pintarBotao(v, l) }
        for (i in 0..2) {
            if (mudou[i]) (listas[i].adapter as BaseAdapter).notifyDataSetChanged() else recolorir(i)
        }
        ajustarAltura(this.acoes, acoes.size, alturaAcao, dp(8))
        ajustarAltura(this.fixas, fixas.size, alturaLinha, 0)
        tituloListas.visibility = if (fixas.isEmpty() && rolando.isEmpty() && botoesListas.isEmpty()) View.GONE else View.VISIBLE
        divisoria.visibility = if (fixas.isEmpty() || rolando.isEmpty()) View.GONE else View.VISIBLE
        if (!temFoco()) onde(escolhida)?.let { (i, pos) -> listas[i].setSelection(pos) }
    }

    /** Os botões ao lado do título ([Linha.icone] obrigatório; [Linha.destaque] = ícone em âmbar, ex.: filtro ligado). */
    fun mostrarBotoes(lista: List<Linha>) { botoes = trocarBotoes(caixaBotoes, botoes, lista, PARADA_TITULO) }

    /** Os botões ao lado de "LISTAS" (a [escolhida] também fica em âmbar, como uma linha escolhida). */
    fun mostrarBotoesListas(lista: List<Linha>) {
        botoesListas = trocarBotoes(caixaBotoesListas, botoesListas, lista, PARADA_LISTAS)
        if (lista.isNotEmpty()) tituloListas.visibility = View.VISIBLE
    }

    private fun trocarBotoes(caixa: LinearLayout, atuais: List<Pair<Linha, FrameLayout>>, lista: List<Linha>, parada: Int): List<Pair<Linha, FrameLayout>> {
        if (lista.map { it.chave to it.destaque } == atuais.map { it.first.chave to it.first.destaque }) {
            val novos = atuais.mapIndexed { k, (_, v) -> lista[k] to v }
            novos.forEach { (l, v) -> pintarBotao(v, l) }
            return novos
        }
        val focoEm = atuais.indexOfFirst { it.second.hasFocus() }
        caixa.removeAllViews()
        val novos = lista.mapIndexed { k, l -> l to botao(l, k, lista.size, parada) }
        novos.forEach { (_, v) -> caixa.addView(v, LinearLayout.LayoutParams(ladoBotao, ladoBotao).apply { marginStart = dp(8) }) }
        if (focoEm >= 0) novos.getOrNull(focoEm.coerceAtMost(novos.size - 1))?.second?.requestFocus()
        return novos
    }

    private fun filaDe(parada: Int) = if (parada == PARADA_TITULO) botoes else botoesListas

    private val ladoBotao = dp((44 * escala).toInt())

    // Drawable próprio (fora do cache das linhas): a ImageView muda os limites dele, e um Drawable dividido com
    // as linhas sairia do tamanho certo lá.
    private fun iconeBotao(l: Linha, cor: Int): Drawable = when (val t = l.icone ?: IconePlayer.Tipo.FILTROS) {
        is IconePlayer.Tipo -> IconePlayer(t, cor)
        else -> IconeMidia(t as IconeMidia.Tipo, cor)
    }

    private fun corBotao(l: Linha) = if (l.destaque || l.chave == escolhida) ambar else COR_ICONE

    /** Recolore o ícone de um botão (a escolhida mudou, ou o filtro ligou) sem refazer o botão. */
    private fun pintarBotao(v: FrameLayout, l: Linha) {
        val img = v.getChildAt(0) as? ImageView ?: return
        val cor = corBotao(l)
        if (img.tag != cor) { img.tag = cor; img.setImageDrawable(iconeBotao(l, cor)) }
    }

    private fun botao(l: Linha, k: Int, total: Int, parada: Int): FrameLayout {
        val fundoNormal = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Estilo.SUPERFICIE); setStroke(dp(1), Estilo.BORDA) }
        val fundoFoco = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Estilo.FOCO) }
        val corIcone = corBotao(l)
        val img = ImageView(act).apply { setImageDrawable(iconeBotao(l, corIcone)); tag = corIcone }
        val focado = ImageView(act).apply { setImageDrawable(iconeBotao(l, Estilo.FUNDO)); visibility = View.GONE }
        val lado = dp((22 * escala).toInt())
        return FrameLayout(act).apply {
            isFocusable = true; isClickable = true; contentDescription = l.nome; background = fundoNormal
            addView(img, FrameLayout.LayoutParams(lado, lado, Gravity.CENTER))
            addView(focado, FrameLayout.LayoutParams(lado, lado, Gravity.CENTER))
            // Foco = disco branco com o ícone escuro (o mesmo contraste das linhas e das janelas).
            setOnFocusChangeListener { _, tem ->
                background = if (tem) fundoFoco else fundoNormal
                img.visibility = if (tem) View.GONE else View.VISIBLE; focado.visibility = if (tem) View.VISIBLE else View.GONE
            }
            setOnClickListener { filaDe(parada).getOrNull(k)?.first?.let { aoOk(it.chave) } }
            setOnKeyListener { v, codigo, e ->
                if (e.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
                val fila = filaDe(parada)
                when (Rtl.tecla(codigo, v)) {   // 16.8
                    KeyEvent.KEYCODE_DPAD_DOWN -> pular(parada, +1)
                    KeyEvent.KEYCODE_DPAD_UP -> pular(parada, -1)
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (k > 0) fila.getOrNull(k - 1)?.second?.requestFocus(); true }
                    // ▶ no último botão: deixa a tela levar o foco para os canais (como nas linhas).
                    KeyEvent.KEYCODE_DPAD_RIGHT -> if (k < total - 1) { fila.getOrNull(k + 1)?.second?.requestFocus(); true } else parada == PARADA_TITULO
                    else -> false
                }
            }
        }
    }

    /** Muda só a categoria escolhida (a cor), sem mexer nas linhas. */
    fun escolher(chave: String) {
        escolhida = chave; for (i in 0..2) recolorir(i)
        botoes.forEach { (l, v) -> pintarBotao(v, l) }; botoesListas.forEach { (l, v) -> pintarBotao(v, l) }
    }

    fun temFoco(): Boolean = listas.any { it.hasFocus() } || botoes.any { it.second.hasFocus() } || botoesListas.any { it.second.hasFocus() }

    /** Foco na linha da categoria escolhida (ou na primeira ação, se ela não estiver na coluna). */
    fun focar() {
        // A escolhida é um botão (ex.: Todos ao lado de LISTAS): o foco vai nele.
        (botoesListas + botoes).firstOrNull { it.first.chave == escolhida }?.let { it.second.requestFocus(); return }
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
        (botoes + botoesListas).firstOrNull { it.second.hasFocus() }?.let { return it.first.chave }
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

    private class Pecas(val icone: ImageView, val etiqueta: TextView, val nome: TextView, val novos: TextView, val numero: TextView)

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
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(dp(12), 0, dp(12), 0)
            layoutParams = AbsListView.LayoutParams(-1, altura)
        }
        val icone = ImageView(act)
        linha.addView(icone, LinearLayout.LayoutParams(dp((18 * escala).toInt()), dp((18 * escala).toInt())).apply { marginEnd = dp(12) })
        // Selo do serviço (listas de fora): fundo claro, letra escura, pequeno.
        val etiqueta = TextView(act).apply {
            textSize = Estilo.Leitura.DICA * escala; typeface = Fontes.nunito(700); setTextColor(Estilo.FUNDO); setPaddingRelative(dp(6), dp(1), dp(6), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(6).toFloat(); setColor(COR_ETIQUETA) }
            visibility = View.GONE
        }
        linha.addView(etiqueta, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(10) })
        val nome = TextView(act).apply { textSize = Estilo.Leitura.CATEGORIA * escala; typeface = fonteMedia; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        linha.addView(nome, LinearLayout.LayoutParams(0, -2, 1f))
        // "+12": títulos novos desde a última visita, numa pílula verde (esboço direita-filmes.png).
        val novos = TextView(act).apply {
            textSize = Estilo.Leitura.DICA; typeface = Fontes.nunito(700); setTextColor(Estilo.FUNDO); setPaddingRelative(dp(6), dp(1), dp(6), dp(1))
            background = GradientDrawable().apply { cornerRadius = dp(8).toFloat(); setColor(COR_NOVOS) }
        }
        linha.addView(novos, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
        // Folga entre o nome cortado e o número (12/11: "Reality show / Program…" encostava no 304).
        val numero = TextView(act).apply { textSize = Estilo.Leitura.CONTAGEM * escala; setTextColor(apagado); maxLines = 1 }
        linha.addView(numero, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(10) })
        linha.tag = Pecas(icone, etiqueta, nome, novos, numero)
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
        p.etiqueta.visibility = if (l.etiqueta.isEmpty()) View.GONE else View.VISIBLE
        p.etiqueta.text = l.etiqueta
        p.novos.visibility = if (l.novos > 0) View.VISIBLE else View.GONE
        if (l.novos > 0) p.novos.text = act.getString(R.string.coluna_novos, milhar(l.novos))
        p.numero.text = when { l.detalhe.isNotEmpty() -> l.detalhe; l.quantos < 0 -> ""; else -> milhar(l.quantos) }
        p.numero.setTextColor(if (l.detalhe.isNotEmpty()) papel else apagado)   // contagem é valor: sem laranja
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
                KeyEvent.KEYCODE_DPAD_DOWN -> if (selectedItemPosition >= count - 1) pular(PARADA_DA_LISTA[i], +1) else false
                KeyEvent.KEYCODE_DPAD_UP -> if (selectedItemPosition <= 0) pular(PARADA_DA_LISTA[i], -1) else false
                else -> false
            }
        }
    }

    /**
     * ▲▼ na ponta de um trecho: vai para o próximo não vazio no [sentido]. A ordem, de cima para baixo: botões do título,
     * ações, botões de LISTAS, fixas, categorias. Sem vizinho, fica (consome a tecla).
     */
    private fun pular(de: Int, sentido: Int): Boolean {
        var j = de + sentido
        while (j in 0 until PARADAS && vazia(j)) j += sentido
        if (j !in 0 until PARADAS) return true
        when (j) {
            PARADA_TITULO, PARADA_LISTAS -> filaDe(j).firstOrNull()?.second?.requestFocus()
            else -> {
                val i = PARADA_DA_LISTA.indexOf(j)
                listas[i].requestFocus()
                listas[i].setSelection(if (sentido > 0) 0 else grupos[i].size - 1)
            }
        }
        return true
    }

    private fun vazia(parada: Int): Boolean = when (parada) {
        PARADA_TITULO, PARADA_LISTAS -> filaDe(parada).isEmpty()
        else -> grupos[PARADA_DA_LISTA.indexOf(parada)].isEmpty()
    }

    /** 20975 -> "20.975" (pt) / "20,975" (en): o separador de milhar do idioma da TV. */
    private fun milhar(n: Int) = String.format(java.util.Locale.getDefault(), "%,d", n)

    private fun dp(v: Int) = (v * act.resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val ESPERA_MS = 180L
        const val COR_NOVOS = 0xFF5FD3B0.toInt()   // o verde-água dos ícones no esboço
        const val COR_ETIQUETA = 0xFFCFD6E0.toInt()   // selo das listas de fora: cinza claro (cores de marca ficam fora)
        // Ícones em repouso no tom do texto secundário (era verde-água): a cor fica só para a escolhida, como na cruz.
        const val COR_ICONE = 0xB3FFF3E6.toInt()
        // Os trechos da coluna, de cima para baixo (▲▼ anda nesta ordem).
        const val PARADA_TITULO = 0
        const val PARADA_LISTAS = 2
        const val PARADAS = 5
        val PARADA_DA_LISTA = intArrayOf(1, 3, 4)   // ações, fixas, categorias
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

/**
 * Tela vazia de Filmes/Séries (crítica de design 19/11/2026): no lugar do aviso "só vêm de conta Xtream" (jargão, sem
 * saída), o estado vazio padrão do sistema com o que fazer e um botão que leva direto à aba Conta das Configurações,
 * onde a lista de TV é adicionada. [titulo] = "Nenhum filme ainda" / "Nenhuma série ainda".
 */
fun vazioDoCatalogo(act: Activity, titulo: String): View {
    val botao = Estilo.botao(act, act.getString(R.string.vod_adicionar_lista)).apply {
        isFocusable = true
        setOnClickListener {
            act.startActivity(android.content.Intent(act, ConfigLauncherActivity::class.java)
                .putExtra(ConfigLauncherActivity.EXTRA_ABA, ConfigLauncherActivity.ABA_CONTA))
        }
    }
    return Estilo.estadoVazio(act, null, titulo, act.getString(R.string.vod_vazio_texto), botao)
}
