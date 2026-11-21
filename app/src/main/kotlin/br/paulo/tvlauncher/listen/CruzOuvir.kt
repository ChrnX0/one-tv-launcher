package br.paulo.tvlauncher.listen

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.home.HomeCruz
import br.paulo.tvlauncher.home.MolaViews
import br.paulo.tvlauncher.home.SonsCruz
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.home.prateleira
import br.paulo.tvlauncher.home.tocandoAgora
import br.paulo.tvlauncher.player.PlayerLogos
import br.paulo.tvlauncher.player.item

/**
 * A cruz das telas Rádio e Podcasts (redesenho do Ouvir, 18/11/2026; dono: "a tela de podcast e de rádio está BEM ruim").
 * O mesmo jeito de andar da tela inicial em cruz ([HomeCruz]), para quem já sabe usar a HOME não ter nada a aprender:
 *  - uma LINHA de prateleiras (Tocando, Favoritas, Recentes, Perto...) que ◀ ▶ trocam;
 *  - na prateleira em foco, uma COLUNA de itens que ▲ ▼ percorrem; o escolhido fica grande, com o ponto laranja à esquerda
 *    do nome e a informação útil em até 4 linhas embaixo; os vizinhos encolhem e somem aos poucos;
 *  - OK abre/toca no SOLTAR; OK segurado abre as opções do item (quando há) e a dica no canto diz isso;
 *  - movimento em mola ([MolaViews]), com a "onda" dos vizinhos; ícones Linha, cada um na sua cor ([IconeOuvir]).
 * Diferente da HOME: cada item tem CAPA (logo da rádio, capa do podcast, ou as iniciais sobre a cor dele), e o canto de
 * cima à direita mostra o que está TOCANDO AGORA (capa grande, nome, informação e progresso), com a arte desfocada no
 * fundo da tela. Medidas e fontes são as da HomeCruz (constantes públicas de lá), para as duas telas baterem.
 *
 * Desempenho: as views de uma prateleira só são criadas quando ela ganha o foco pela 1ª vez; quem não aparece fica GONE
 * (não mede nem desenha); cada tecla só anima translação, alfa e escala. A posição da música ([tempo]) muda só o texto
 * de largura fixa e a barra do cartão, sem refazer o layout da tela.
 */
class CruzOuvir(ctx: Context, private val fonte: Typeface, private val fonteForte: Typeface) : FrameLayout(ctx) {

    /** Um item da coluna. [abrir] roda no OK; [opcoes] no OK segurado (null = segurar é igual a apertar). */
    class Item(
        val id: String, val nome: String, val sub: String,
        val icone: IconeOuvir.Tipo? = null,
        val capa: String = "", val cor: Int = IconeOuvir.cor(IconeOuvir.Tipo.RADIO), val monograma: String = "",
        val cobrir: Boolean = false, val progresso: Float? = null,
        val abrir: () -> Unit = {}, val opcoes: (() -> Unit)? = null,
        /** Imagem já pronta (ícone de um app, na tela Jogos): fica no lugar da [capa] baixada. */
        val imagem: android.graphics.drawable.Drawable? = null,
    )
    class Prateleira(val id: String, val nome: String, val icone: IconeOuvir.Tipo, val itens: List<Item>)
    /** O cartão "Tocando agora". [progresso] null = rádio (ao vivo, sem barra); [tempo] = "12:30 de 58:00". */
    class Tocando(
        val titulo: String, val linhas: String, val capa: String, val cor: Int, val monograma: String, val cobrir: Boolean,
        val progresso: Float?, val tempo: String?, val pausado: Boolean, val aoVivo: String? = null,
        /** Id do que toca (a rádio): com o foco nele, o item já diz "Tocando agora" e o cartão sai (não repete). */
        val chave: String? = null,
    )

    companion object {
        // Geometria da HomeCruz (dp numa tela de 960 x 540): o mesmo lugar da linha, da coluna e das prateleiras.
        private const val LINHA = HomeCruz.LINHA
        private const val COLUNA = HomeCruz.COLUNA
        private const val CAPA = HomeCruz.ICONE_A          // capa quadrada de 94 dp no item escolhido
        private const val CAT = HomeCruz.CAT_A             // ícone da prateleira na linha (70 dp)
        private const val ESCALA_PEQUENO = HomeCruz.ESCALA_PEQUENO
        private const val PASSO_COLUNA = HomeCruz.PASSO_COLUNA
        private const val VAO_ESCOLHIDO = HomeCruz.VAO_ESCOLHIDO
        private const val VAO_CIMA = HomeCruz.VAO_CIMA
        private const val CAT_ESQUERDA = HomeCruz.CAT_ESQUERDA
        private const val CAT_DIREITA = HomeCruz.CAT_DIREITA
        private const val PASSO_CATS = HomeCruz.PASSO_CATS
        private const val ROTULO_Y = HomeCruz.ROTULO_Y
        private const val ROTULO_VAO = 40f   // do topo do item escolhido ao topo do nome da prateleira, quando nada há acima
        private const val TEXTO_MAX = HomeCruz.TEXTO_MAX
        private const val SUB_LINHAS = HomeCruz.SUB_LINHAS
        private const val SUB_SOBRA = HomeCruz.SUB_SOBRA
        private const val TELA_L = HomeCruz.TELA_L
        private const val PONTO = HomeCruz.PONTO
        private const val LARGURA_ITEM = HomeCruz.LARGURA_ITEM
        /** O nome do escolhido em até 2 linhas, e a sobra embaixo para a 2ª (dp). */
        const val NOME_LINHAS = 2
        /** Largura do nome de uma prateleira de lado: o vão entre dois ícones vizinhos, menos uma folga. */
        val ROTULO_CAT = (PASSO_CATS - 8).toInt()
        const val NOME_SOBRA = 44
        private val ALFA_COLUNA get() = HomeCruz.ALFA_COLUNA

        // Cartão "Tocando agora" (canto de cima à direita, acima da linha das prateleiras: termina antes de y = 200).
        const val CARTAO_X = 500f
        const val CARTAO_Y = 30f
        const val CARTAO_CAPA = 120
        const val CARTAO_TEXTO = 296     // largura do texto à esquerda da capa
        const val FONTE_CARTAO_TITULO = 21f
        const val FONTE_CARTAO = 18f
        const val FONTE_TITULO = 32f     // nome da tela ("Rádio"); era 26 e perdia para o rótulo laranja (crítica 19/11/2026)
        const val ONDA_MS = HomeCruz.ONDA_MS
        const val ONDA_MAX_MS = HomeCruz.ONDA_MAX_MS
        const val APERTO = HomeCruz.APERTO
        const val APERTO_MS = HomeCruz.APERTO_MS
        /** Arte do fundo: baixada bem pequena (32 px) e esticada; o próprio esticar já desfoca, sem custo de GPU. */
        const val ARTE_LADO = 32
        const val ARTE_ALFA = 0.24f
    }

    var aoMudar: ((Prateleira, Item) -> Unit)? = null
    /** Som de cada passo ([SonsCruz]); quem usa decide se toca (a tela não pia por cima da música). */
    var aoSom: ((SonsCruz.Som) -> Unit)? = null

    private var prats: List<Prateleira> = emptyList()
    private var cat = 0
    private val sel = HashMap<String, String>()        // prateleira -> item escolhido (pelo id: a lista muda)
    private val viewsCat = ArrayList<View>()
    private val viewsItem = HashMap<String, List<ItemView>>()   // criadas sob demanda
    private var assinatura: List<String>? = null
    private val mola = MolaViews(this)
    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun dpi(v: Int) = (v * dm.density + 0.5f).toInt()
    private var amortece = MolaViews.AMORTECE

    private val arte: ImageView
    private val titulo: TextView
    private val rotulo: TextView
    private val cabecalho: LinearLayout
    private var tocandoAgora: Tocando? = null
    private val posicao: TextView
    private val cartao: LinearLayout
    private val cartaoCapa: CapaOuvir
    private val cartaoRotulo: TextView
    private val cartaoTitulo: TextView
    private val cartaoLinhas: TextView
    private val cartaoBarra: Barra
    private val cartaoTempo: TextView
    private val dicas: LinearLayout

    private inner class ItemView(val raiz: LinearLayout, val capa: CapaOuvir, val nome: TextView, val sub: TextView, val ponto: View) {
        var linhas = 1
        var linhasNome = 1
        var item: Item? = null
    }

    /** Barra de progresso do cartão (só desenha; mudar o valor não refaz o layout). */
    private inner class Barra(c: Context) : View(c) {
        var valor = 0f
            set(v) { if (field != v) { field = v.coerceIn(0f, 1f); invalidate() } }
        private val trilho = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
        private val cheio = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.ACENTO }
        private val r = android.graphics.RectF()
        override fun onDraw(c: android.graphics.Canvas) {
            val a = height.toFloat(); r.set(0f, 0f, width.toFloat(), a)
            c.drawRoundRect(r, a / 2, a / 2, trilho)
            if (valor > 0f) { r.right = maxOf(a, width * valor); c.drawRoundRect(r, a / 2, a / 2, cheio) }
        }
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES

        // Arte do que está tocando, desfocada, atrás de tudo (some sem nada tocando).
        arte = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(arte, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Véus: escurece a arte em cima e embaixo, e mais à esquerda (o texto da coluna fica sempre legível).
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x66121528, 0x22121528, 0xAA121528.toInt()))
        }, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xCC121528.toInt(), 0x99121528.toInt(), 0x00121528))
        }, LayoutParams(dpi(600), LayoutParams.MATCH_PARENT))

        titulo = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_TITULO); setTextColor(Estilo.TEXTO); typeface = fonteForte
            includeFontPadding = false; maxLines = 1
            setShadowLayer(dp(8f), 0f, 0f, 0x99000000.toInt())
        }
        addView(titulo, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        titulo.translationX = dp(COLUNA - CAPA / 2f); titulo.translationY = dp(34f)

        rotulo = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HomeCruz.FONTE_ROTULO); setTextColor(Estilo.ACENTO); typeface = fonteForte
            letterSpacing = 0.16f; includeFontPadding = false; maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        posicao = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HomeCruz.FONTE_POSICAO); setTextColor(HomeCruz.COR_SUB); typeface = fonte
            includeFontPadding = false; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
        }
        cabecalho = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
            addView(rotulo, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(posicao, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(14) })
        }
        addView(cabecalho, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        cabecalho.translationX = dp(COLUNA - CAPA / 2f); cabecalho.translationY = dp(ROTULO_Y)

        // ---- cartão "Tocando agora": texto à esquerda, capa grande à direita
        cartaoRotulo = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f); setTextColor(Estilo.ACENTO); typeface = fonteForte
            letterSpacing = 0.14f; includeFontPadding = false; maxLines = 1
        }
        cartaoTitulo = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_CARTAO_TITULO); setTextColor(Estilo.TEXTO); typeface = fonteForte
            maxLines = 2; includeFontPadding = false; setLineSpacing(dp(2f), 1f)
            setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
        }
        cartaoLinhas = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_CARTAO); setTextColor(HomeCruz.COR_SUB); typeface = fonte
            maxLines = 2; includeFontPadding = false; setLineSpacing(dp(2f), 1f)
            setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
        }
        cartaoBarra = Barra(ctx)
        cartaoTempo = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_CARTAO); setTextColor(HomeCruz.COR_SUB); typeface = fonte
            maxLines = 1; includeFontPadding = false
        }
        val textoCartao = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(cartaoRotulo, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            addView(cartaoTitulo, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8) })
            addView(cartaoLinhas, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6) })
            addView(cartaoBarra, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(5)).apply { topMargin = dpi(10) })
            // Largura e altura fixas: o texto do tempo muda a cada segundo sem pedir layout novo da tela.
            addView(cartaoTempo, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, dpi(22)).apply { topMargin = dpi(6) })
        }
        cartaoCapa = CapaOuvir(ctx, 16f).apply { fonte(fonteForte); elevation = dp(8f) }
        cartao = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.TOP; alpha = 0f; visibility = View.INVISIBLE
            clipChildren = false; clipToPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(textoCartao, LinearLayout.LayoutParams(dpi(CARTAO_TEXTO), LayoutParams.WRAP_CONTENT))
            addView(cartaoCapa, LinearLayout.LayoutParams(dpi(CARTAO_CAPA), dpi(CARTAO_CAPA)).apply { marginStart = dpi(16) })
        }
        addView(cartao, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        cartao.translationX = dp(CARTAO_X); cartao.translationY = dp(CARTAO_Y)

        dicas = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END or Gravity.CENTER_VERTICAL }
        // Fica à direita: a coluna da cruz desce pelo canto de baixo à esquerda e as dicas passariam por cima dela
        // (tentado na crítica de 19/11/2026; o teste de telas acusou a sobreposição). Margem = a das outras telas.
        addView(dicas, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dpi(Estilo.MARGEM_LATERAL); bottomMargin = dpi(20)
        })

        setOnFocusChangeListener { _, _ -> posicionar(animar = false); anunciar() }
    }

    fun definirTitulo(t: String) { titulo.text = t; contentDescription = t }

    /** A barra de dicas do canto (tecla -> o que faz). Sempre visível: o OK segurado não fica escondido. */
    fun definirDicas(vararg pares: Pair<String, String>) {
        dicas.removeAllViews()
        dicas.addView(Estilo.barraDicas(context, *pares))   // a mesma barra de todo o sistema (crítica de design 19/11/2026)
    }

    // ------------------------------------------------------------------ conteúdo

    /**
     * Troca as prateleiras. Mesmos ids: atualiza textos e capas no lugar (música nova, episódio novo), sem mexer no foco.
     * Ids diferentes: refaz as views e fica no mesmo item (ou no vizinho, se ele sumiu). [focar]: (prateleira, item?)
     * para onde levar o foco agora (resultado de uma busca, o tocando que começou).
     */
    fun definir(novas: List<Prateleira>, focar: Pair<String, String?>? = null) {
        val sig = novas.flatMap { p -> listOf("#" + p.id) + p.itens.map { it.id } }
        val antesPrat = prats.getOrNull(cat)?.id
        val antesItem = itemAtual()
        if (sig == assinatura && focar == null) {
            prats = novas
            for (p in novas) viewsItem[p.id]?.let { vs -> p.itens.forEachIndexed { i, it -> vs.getOrNull(i)?.let { v -> preencher(v, it) } } }
            for ((j, p) in novas.withIndex()) (viewsCat.getOrNull(j) as? ViewGroup)?.let { caberPalavra(it.getChildAt(1) as TextView, p.nome) }
            val agora = itemAtual()
            if (agora != null && (antesItem?.nome != agora.nome || antesItem.sub != agora.sub)) avisarMudanca()
            posicionar(animar = false)
            return
        }
        // A prateleira que sumiu deixa o foco na vizinha (pela posição); o item, pelo id ou pela mesma posição.
        val antesIdx = cat
        val antigas = prats
        prats = novas
        assinatura = sig
        for (v in viewsCat) { mola.esquecer(v); removeView(v) }
        viewsCat.clear()
        for (vs in viewsItem.values) for (v in vs) { mola.esquecer(v.raiz); removeView(v.raiz) }
        viewsItem.clear()
        for (p in novas) {
            viewsCat += criarCategoria(p).also { addView(it, indexOfChild(cartao), LayoutParams(dpi(150), LayoutParams.WRAP_CONTENT)) }
        }
        // posição de cada prateleira: a lembrada (id), ou a mesma posição na lista nova
        for (p in novas) {
            val lembrado = sel[p.id]
            if (lembrado == null || p.itens.none { it.id == lembrado }) {
                val antiga = antigas.firstOrNull { it.id == p.id }
                val i = antiga?.itens?.indexOfFirst { it.id == lembrado }?.takeIf { it >= 0 } ?: 0
                p.itens.getOrNull(i.coerceAtMost(p.itens.size - 1))?.let { sel[p.id] = it.id }
            }
        }
        cat = when {
            focar != null -> novas.indexOfFirst { it.id == focar.first }.takeIf { it >= 0 }
            antesPrat != null -> novas.indexOfFirst { it.id == antesPrat }.takeIf { it >= 0 }
            else -> null
        } ?: antesIdx.coerceIn(0, (novas.size - 1).coerceAtLeast(0))
        focar?.second?.let { id -> novas.getOrNull(cat)?.takeIf { p -> p.itens.any { it.id == id } }?.let { sel[it.id] = id } }
        posicionar(animar = false)
        avisarMudanca()
    }

    /** Leva o foco para (prateleira, item) sem refazer nada; false se não existe. */
    fun ir(prateleira: String, item: String? = null, animar: Boolean = true): Boolean {
        val j = prats.indexOfFirst { it.id == prateleira }.takeIf { it >= 0 } ?: return false
        if (item != null && prats[j].itens.any { it.id == item }) sel[prateleira] = item
        amortece = MolaViews.AMORTECE_SALTO
        cat = j
        posicionar(animar); avisarMudanca()
        return true
    }

    fun itemAtual(): Item? = prats.getOrNull(cat)?.let { p -> p.itens.firstOrNull { it.id == sel[p.id] } ?: p.itens.firstOrNull() }
    fun prateleiraAtual(): Prateleira? = prats.getOrNull(cat)
    private fun indiceSel(p: Prateleira): Int = p.itens.indexOfFirst { it.id == sel[p.id] }.coerceAtLeast(0)

    /** O cartão "Tocando agora" (null = some, e a arte do fundo também). */
    fun tocando(t: Tocando?) {
        val animar = isAttachedToWindow && !MolaViews.semAnimacao(this)
        tocandoAgora = t
        if (t == null) {
            mola.mover(cartao, dp(CARTAO_X + 24f), dp(CARTAO_Y), 0f, 1f, animar)
            arte.animate().alpha(0f).setDuration(if (animar) 400 else 0).start()
            return
        }
        cartaoRotulo.text = t.aoVivo ?: context.getString(if (t.pausado) R.string.ouv_pausado else R.string.ouv_tocando_agora).uppercase()
        cartaoRotulo.setTextColor(if (t.pausado) HomeCruz.COR_SUB else Estilo.ACENTO)
        val titulo = caber(t.titulo, cartaoTitulo, dpi(CARTAO_TEXTO), 2)
        if (cartaoTitulo.text.toString() != titulo) cartaoTitulo.text = titulo
        val linhas = caber(t.linhas, cartaoLinhas, dpi(CARTAO_TEXTO), 2)
        if (cartaoLinhas.text.toString() != linhas) cartaoLinhas.text = linhas
        cartaoLinhas.visibility = if (t.linhas.isEmpty()) View.GONE else View.VISIBLE
        cartaoCapa.cor = t.cor; cartaoCapa.monograma = t.monograma; cartaoCapa.cobrir = t.cobrir
        cartaoCapa.carregar(t.capa, 300)
        // Cartão só de texto (o "Esta semana" da tela Jogos): sem capa, o quadro vazio some.
        cartaoCapa.visibility = if (t.capa.isEmpty() && t.monograma.isEmpty()) View.GONE else View.VISIBLE
        tempo(t.progresso, t.tempo)
        mostrarCartao(animar)
        if (arte.tag != t.capa) {
            if (t.capa.isEmpty()) { arte.setImageDrawable(null); arte.tag = "" } else PlayerLogos.carregar(context, t.capa, arte, ARTE_LADO)
        }
        arte.animate().alpha(if (t.capa.isEmpty()) 0f else ARTE_ALFA).setDuration(if (animar) 600 else 0).start()
    }

    /** Com o foco no item que está tocando, o cartão sai: a linha do item já diz o mesmo (pente fino 19/11/2026). */
    private fun repete(): Boolean {
        val c = tocandoAgora?.chave ?: return false
        val id = itemAtual()?.id ?: return false
        return id == c || id.endsWith(":$c")
    }

    private fun mostrarCartao(animar: Boolean) {
        if (tocandoAgora == null) return
        val some = repete()
        val alvo = if (some) 0f else 1f
        if (cartao.alpha != alvo || (!some && cartao.visibility != View.VISIBLE))
            mola.mover(cartao, dp(if (some) CARTAO_X + 24f else CARTAO_X), dp(CARTAO_Y), alvo, 1f, animar)
    }

    /** A cada segundo: só a barra e o texto do tempo (sem layout). */
    fun tempo(progresso: Float?, texto: String?) {
        cartaoBarra.visibility = if (progresso == null) View.GONE else View.VISIBLE
        if (progresso != null) cartaoBarra.valor = progresso
        val t = caber(texto.orEmpty(), cartaoTempo, dpi(CARTAO_TEXTO), 1)
        if (cartaoTempo.text.toString() != t) cartaoTempo.text = t
        cartaoTempo.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------------ teclas

    private var repeticao = 0

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        repeticao = event.repeatCount
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> andarPrateleira(-1)
            KeyEvent.KEYCODE_DPAD_RIGHT -> andarPrateleira(1)
            KeyEvent.KEYCODE_DPAD_UP -> andarItem(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> andarItem(1)
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> event.startTracking()
            else -> return super.onKeyDown(keyCode, event)
        }
        return true
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode != KeyEvent.KEYCODE_DPAD_CENTER && keyCode != KeyEvent.KEYCODE_ENTER && keyCode != KeyEvent.KEYCODE_NUMPAD_ENTER) return false
        val op = itemAtual()?.opcoes ?: return false   // sem opções: o soltar abre normalmente
        op()
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (!ok) return super.onKeyUp(keyCode, event)
        if (event.isTracking && !event.isCanceled) abrir()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); abrir(); return true }

    private fun abrir() {
        val it = itemAtual() ?: return
        aoSom?.invoke(SonsCruz.Som.ABRIR)
        apertar()
        it.abrir()
    }

    private fun andarPrateleira(d: Int) {
        val n = cat + d
        if (n !in prats.indices) { if (repeticao == 0) aoSom?.invoke(SonsCruz.Som.BORDA); return }
        if (repeticao < HomeCruz.REPETICAO_RAPIDA || repeticao % HomeCruz.SOM_A_CADA == 0) aoSom?.invoke(SonsCruz.Som.GRUPO)
        cat = n
        amortece = MolaViews.AMORTECE_SALTO
        posicionar(animar = true); avisarMudanca()
    }

    private fun andarItem(d: Int) {
        val p = prats.getOrNull(cat) ?: return
        val n = indiceSel(p) + d
        if (n !in p.itens.indices) { if (repeticao == 0) aoSom?.invoke(SonsCruz.Som.BORDA); return }
        if (repeticao < HomeCruz.REPETICAO_RAPIDA || repeticao % HomeCruz.SOM_A_CADA == 0) aoSom?.invoke(SonsCruz.Som.ANDAR)
        sel[p.id] = p.itens[n].id
        amortece = MolaViews.AMORTECE
        posicionar(animar = true); avisarMudanca()
    }

    /** O "aperto" do OK: a capa encolhe e volta, no mesmo quadro do toque. */
    private fun apertar() {
        val v = viewAtual()?.capa ?: return
        if (MolaViews.semAnimacao(this)) return
        v.animate().cancel()
        v.animate().scaleX(APERTO).scaleY(APERTO).setDuration(APERTO_MS).withEndAction {
            v.animate().scaleX(1f).scaleY(1f).setDuration(APERTO_MS * 2).start()
        }.start()
    }

    private fun viewAtual(): ItemView? = prats.getOrNull(cat)?.let { p -> viewsItem[p.id]?.getOrNull(indiceSel(p)) }

    private fun avisarMudanca() {
        val p = prats.getOrNull(cat) ?: return
        val i = itemAtual() ?: return
        aoMudar?.invoke(p, i)
        anunciar()
    }

    // ------------------------------------------------------------------ desenho

    /**
     * Corta [texto] para caber em [linhas] na largura [largura] SEM reticências do layout: a parte (linha do texto) mais
     * comprida perde o fim primeiro, com "…" escrito no próprio texto. Era a regra da HOME (até 18/11/2026, quando a HOME passou a uma linha só), com
     * largura e linhas livres, porque aqui o nome também quebra em duas e o cartão tem outra largura.
     */
    private fun caber(texto: String, tv: TextView, largura: Int, linhas: Int): String {
        if (texto.isEmpty() || largura <= 0) return texto
        fun layout(s: String) = android.text.StaticLayout.Builder.obtain(s, 0, s.length, tv.paint, largura).setIncludePad(false).build()
        fun conta(s: String) = if (s.isEmpty()) 1 else layout(s).lineCount
        val partes = texto.split('\n').take(linhas).toMutableList()
        val n = partes.map(::conta).toMutableList()
        while (n.sum() > linhas) {
            val i = n.indices.filter { n[it] > 1 }.maxByOrNull { n[it] } ?: break
            val cabe = maxOf(1, linhas - (n.sum() - n[i]))
            val l = layout(partes[i])
            val inicio = l.getLineStart(cabe - 1)
            partes[i] = partes[i].substring(0, inicio) +
                TextUtils.ellipsize(partes[i].substring(inicio), tv.paint, largura.toFloat(), TextUtils.TruncateAt.END)
            n[i] = conta(partes[i])
        }
        return partes.joinToString("\n")
    }

    /** Nome de prateleira de lado: quebra entre palavras; uma palavra que não cabe na largura diminui a letra (até 15 sp). */
    private fun caberPalavra(tv: TextView, nome: String) {
        tv.text = nome
        var sp = HomeCruz.FONTE_CATEGORIA
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        fun maior() = nome.split(' ', '\n').maxOfOrNull { tv.paint.measureText(it) } ?: 0f
        while (sp > 15f && maior() > dpi(ROTULO_CAT)) {
            sp -= 0.5f; tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }
    }

    private fun criarCategoria(p: Prateleira): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(ImageView(context).apply { setImageDrawable(IconeOuvir(p.icone)) }, LinearLayout.LayoutParams(dpi(CAT), dpi(CAT)))
        addView(TextView(context).apply {
            text = p.nome
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HomeCruz.FONTE_CATEGORIA)
            setTextColor(HomeCruz.COR_CATEGORIA)
            typeface = fonteForte
            maxLines = 2
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
            maxWidth = dpi(ROTULO_CAT)
            setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
            caberPalavra(this, p.nome)
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6) })
        pivotX = dp(75f); pivotY = dp(CAT / 2f)
        alpha = 0f; visibility = View.INVISIBLE
    }

    /** As views da prateleira [j], criadas na 1ª vez que ela aparece (recolhidas no ícone dela, invisíveis). */
    private fun garantirItens(j: Int): List<ItemView> {
        val p = prats[j]
        viewsItem[p.id]?.let { return it }
        val vs = p.itens.map { item ->
            criarItem().also { v ->
                preencher(v, item)
                addView(v.raiz, indexOfChild(cartao), LayoutParams(dpi(LARGURA_ITEM), dpi(CAPA + SUB_SOBRA + NOME_SOBRA)))
                val cx = cxPrateleira(j - cat)
                mola.mover(v.raiz, dp(cx - CAPA * ESCALA_PEQUENO / 2f), dp(LINHA - CAPA / 2f), 0f, ESCALA_PEQUENO, animar = false)
            }
        }
        viewsItem[p.id] = vs
        return vs
    }

    private fun criarItem(): ItemView {
        val capa = CapaOuvir(context).apply { fonte(fonteForte) }
        val nome = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HomeCruz.FONTE_ESCOLHIDO)
            setTextColor(Estilo.TEXTO)
            typeface = fonteForte
            // Até 2 linhas (título de episódio é longo; na HOME o nome cabe em uma). A 2ª empurra os vizinhos de baixo.
            maxLines = NOME_LINHAS
            includeFontPadding = false
            maxWidth = dpi(TEXTO_MAX)
            setLineSpacing(dp(2f), 1f)
            setShadowLayer(dp(8f), 0f, 0f, 0x99000000.toInt())
        }
        val ponto = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Estilo.ACENTO) } }
        val sub = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, HomeCruz.FONTE_SUB)
            setTextColor(HomeCruz.COR_SUB)
            typeface = fonte
            maxLines = SUB_LINHAS
            includeFontPadding = false
            setLineSpacing(dp(3f), 1f)
            setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
        }
        val linhaNome = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            // o ponto fica na altura da 1ª linha do nome (com duas linhas, não desce para o meio)
            addView(ponto, LinearLayout.LayoutParams(dpi(PONTO), dpi(PONTO)).apply { marginEnd = dpi(10); topMargin = dpi(14) })
            addView(nome, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        val textos = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPaddingRelative(0, dpi(HomeCruz.TEXTO_TOPO), 0, 0)
            addView(linhaNome, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(sub, LinearLayout.LayoutParams(dpi(TEXTO_MAX), LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8); marginStart = dpi(PONTO + 10) })
        }
        val raiz = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(capa, LinearLayout.LayoutParams(dpi(CAPA), dpi(CAPA)))
            addView(textos, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dpi(20) })
            pivotX = 0f; pivotY = dp(CAPA / 2f)
            visibility = View.INVISIBLE
            alpha = 0f
        }
        return ItemView(raiz, capa, nome, sub, ponto).also { iv ->
            // O texto mudou de tamanho (outra rádio, outra língua): a coluna se rearruma com as linhas novas.
            sub.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val n = sub.lineCount.coerceIn(1, SUB_LINHAS)
                if (n != iv.linhas) { iv.linhas = n; post { posicionar(animar = true) } }
            }
            nome.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val n = nome.lineCount.coerceIn(1, NOME_LINHAS)
                if (n != iv.linhasNome) { iv.linhasNome = n; post { posicionar(animar = true) } }
            }
        }
    }

    private fun preencher(v: ItemView, item: Item) {
        v.item = item
        val nome = caber(item.nome, v.nome, dpi(TEXTO_MAX - PONTO - 10), NOME_LINHAS)
        if (v.nome.text.toString() != nome) v.nome.text = nome
        val sub = caber(item.sub, v.sub, dpi(TEXTO_MAX), SUB_LINHAS)
        if (v.sub.text.toString() != sub) v.sub.text = sub
        v.sub.visibility = if (item.sub.isEmpty()) View.GONE else View.VISIBLE
        val tipo = item.icone
        if (tipo == null) { if (v.capa.icone != null) v.capa.icone = null }
        else if ((v.capa.icone as? IconeOuvir)?.tipo != tipo) v.capa.icone = IconeOuvir(tipo)
        v.capa.cor = item.cor
        v.capa.monograma = item.monograma
        v.capa.cobrir = item.cobrir
        v.capa.progresso = item.progresso
        item.imagem?.let { v.capa.mostrar(it) }
        v.capa.invalidateOutline()
    }

    private fun cxPrateleira(k: Int): Float = when {
        k == 0 -> COLUNA
        k < 0 -> CAT_ESQUERDA + (k + 1) * PASSO_CATS
        else -> CAT_DIREITA + (k - 1) * PASSO_CATS
    }

    private fun onda(distancia: Int) = minOf(distancia * ONDA_MS, ONDA_MAX_MS)

    /** Põe cada view no lugar do estado atual (animado ou de uma vez). */
    private fun posicionar(animar: Boolean) {
        val focada = hasFocus() || isFocused
        val semMovimento = MolaViews.semAnimacao(this)
        val p = prats.getOrNull(cat)
        rotulo.text = p?.nome?.replace("\n", " · ")?.uppercase().orEmpty()   // nome em 2 linhas ("Sua turma\n0 online") vira uma linha no título
        posicao.text = p?.takeIf { it.itens.size > 1 }?.let { context.getString(R.string.cruz_posicao, indiceSel(it) + 1, it.itens.size) }.orEmpty()
        // Sem item acima do escolhido, o nome da prateleira desce para junto dele (pente fino 19/11/2026: ficava um vão
        // grande entre "RECENTES" e o 1º item). Com item acima, volta ao lugar de sempre, acima do vizinho.
        val semCima = p == null || indiceSel(p) == 0
        mola.mover(cabecalho, dp(COLUNA - CAPA / 2f), dp(if (semCima) LINHA - CAPA / 2f - ROTULO_VAO else ROTULO_Y), 1f, 1f, animar, amortece = amortece)
        mostrarCartao(animar)
        for ((j, v) in viewsCat.withIndex()) {
            val k = j - cat
            val cx = cxPrateleira(k)
            val visivel = k != 0 && cx > 30f && cx < TELA_L - 40f
            mola.mover(v, dp(cx - 75f), dp(LINHA - CAT / 2f), if (visivel) 1f else 0f, 1f, animar, onda(kotlin.math.abs(k)), amortece = amortece)
            ((v as? ViewGroup)?.getChildAt(0) as? ImageView)?.drawable.let { it as? IconeOuvir }
                ?.animar(visivel && focada && !semMovimento && kotlin.math.abs(k) <= 2, 1200L + kotlin.math.abs(k) * 1900L)
        }
        if (p != null) garantirItens(cat)
        for ((j, pr) in prats.withIndex()) {
            val lista = viewsItem[pr.id] ?: continue
            val k = j - cat
            val s = indiceSel(pr)
            val extra = lista.getOrNull(s)?.takeIf { k == 0 }?.let {
                val sub = if (it.sub.visibility == View.VISIBLE) (it.linhas - 1).coerceAtLeast(0) * it.sub.lineHeight else 0
                (sub + (it.linhasNome - 1).coerceAtLeast(0) * it.nome.lineHeight) / dm.density
            } ?: 0f
            for ((i, iv) in lista.withIndex()) {
                val rel = i - s
                val ic = iv.capa.icone as? IconeOuvir
                if (k != 0) {
                    // prateleira fora de foco: os itens se recolhem no ícone dela e somem
                    val cx = cxPrateleira(k)
                    esconder(iv, dp(cx - CAPA * ESCALA_PEQUENO / 2f), dp(LINHA - CAPA / 2f), animar, onda(kotlin.math.abs(rel)))
                    ic?.animar(false)
                    continue
                }
                val cy = when {
                    rel == 0 -> LINHA
                    rel < 0 -> LINHA - VAO_CIMA + (rel + 1) * PASSO_COLUNA
                    else -> LINHA + VAO_ESCOLHIDO + extra + (rel - 1) * PASSO_COLUNA
                }
                val alfa = if (rel == 0) (if (focada) 1f else HomeCruz.ALFA_ESCOLHIDO_FORA) else ALFA_COLUNA[rel] ?: 0f
                val escala = if (rel == 0) 1f else ESCALA_PEQUENO
                val x = if (rel == 0) COLUNA - CAPA / 2f else COLUNA - CAPA * ESCALA_PEQUENO / 2f
                if (alfa == 0f) { esconder(iv, dp(x), dp(cy - CAPA / 2f), animar, onda(kotlin.math.abs(rel)), escala); ic?.animar(false); continue }
                mola.mover(iv.raiz, dp(x), dp(cy - CAPA / 2f), alfa, escala, animar, onda(kotlin.math.abs(rel)), amortece = amortece)
                iv.ponto.alpha = if (rel == 0 && focada) 1f else 0f
                iv.sub.alpha = if (rel == 0) 1f else 0f
                ic?.animar(rel == 0 && focada && !semMovimento)
                // a capa só é baixada quando o item aparece (uma lista de 60 rádios não baixa 60 logos de uma vez)
                iv.item?.let { if (it.icone == null && it.imagem == null) iv.capa.carregar(it.capa) }
            }
        }
    }

    /** Item que não aparece: anda até o lugar dele invisível e, já invisível, sai do layout (GONE: não mede nem desenha). */
    private fun esconder(iv: ItemView, x: Float, y: Float, animar: Boolean, atraso: Long, escala: Float = ESCALA_PEQUENO) {
        val r = iv.raiz
        if (r.visibility == View.GONE) return
        if (r.visibility == View.INVISIBLE && r.alpha == 0f) { r.visibility = View.GONE; return }
        mola.mover(r, x, y, 0f, escala, animar, atraso, amortece = amortece)
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        mola.pararTudo()
    }

    // ------------------------------------------------------------------ TalkBack

    private fun anunciar() {
        val p = prats.getOrNull(cat) ?: return
        val i = itemAtual() ?: return
        contentDescription = listOf(titulo.text.toString(), p.nome, i.nome, i.sub.replace('\n', ' '), posicao.text.toString())
            .filter { it.isNotEmpty() }.joinToString(", ")
        if (isFocused) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
        info.isClickable = true
    }
}
