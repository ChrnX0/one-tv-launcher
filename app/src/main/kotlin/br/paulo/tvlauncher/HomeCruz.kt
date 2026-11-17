package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * A tela inicial em cruz (17/11/2026): a linha de categorias e a coluna da categoria em foco. A lógica das teclas é a
 * de [CruzLogica]; aqui só o desenho.
 *
 * Desempenho (aceite da especificação): todas as views são criadas UMA vez por conjunto de itens ([definir]) e cada
 * tecla só anima translationX/Y, alpha e escala (propriedades do RenderNode, sem medir nem desenhar de novo). Os
 * itens que não aparecem ficam INVISIBLE (não desenham). A view inteira é UM foco só: a cruz trata as setas sozinha e
 * devolve ▲ do 1º item ao Android, que leva o foco para a barra de cima.
 *
 * Medidas em dp numa tela de 960 x 540 (a amostra é de 1280 x 720, x 0,75), todas aqui em cima para o dono refinar.
 */
class HomeCruz(ctx: Context, private val fonte: Typeface, private val fonteForte: Typeface) : FrameLayout(ctx) {

    /** Um item da coluna. [abrir] roda no OK; [opcoes] no OK segurado (null = segurar é igual a apertar). */
    class Item(
        val id: String, val nome: String, val sub: String, val icone: Drawable,
        val abrir: () -> Unit, val opcoes: (() -> Unit)? = null, val aoEscolher: (() -> Unit)? = null,
    )
    class Categoria(val cat: CruzLogica.Categoria, val nome: String, val icone: Drawable, val itens: List<Item>)

    companion object {
        // ---- posições (dp) e tamanhos: o lugar único para refinar
        const val LINHA = 250f          // centro vertical da linha de categorias (onde a coluna cruza)
        const val COLUNA = 225f         // centro horizontal da coluna (onde a linha cruza)
        const val ICONE_L = 112         // ícone do item escolhido (o grande da cruz)
        const val ICONE_A = 94
        const val CAT_L = 84            // ícone de categoria na linha
        const val CAT_A = 70
        const val ESCALA_PEQUENO = 0.6f // item fora da escolha: 112 -> 67 dp; nome 32 -> 19 sp
        const val PASSO_COLUNA = 71f    // distância entre itens pequenos na coluna
        const val VAO_ESCOLHIDO = 94f   // do centro do escolhido ao centro do vizinho de baixo (o grande ocupa mais)
        const val VAO_CIMA = 89f        // do centro do escolhido ao centro do vizinho de cima
        const val CAT_ESQUERDA = 97f    // centro da categoria logo à esquerda da coluna
        const val CAT_DIREITA = 670f    // centro da 1ª categoria à direita (depois do texto mais longo)
        const val PASSO_CATS = 104f     // entre categorias à direita
        const val ROTULO_Y = 96f        // nome da categoria em foco, acima da linha à esquerda
        const val LARGURA_ITEM = 470    // ícone + texto do item
        const val TEXTO_MAX = 300       // largura máxima do nome/linha de baixo (termina antes das categorias da direita)
        const val TELA_L = 960f
        // ---- fontes (sp): tabela do estudo 32 / 20 / 16 / 18
        const val FONTE_ESCOLHIDO = 32f // nome do item escolhido (fora da escolha fica 0,6 x = ~19)
        const val FONTE_ROTULO = 20f    // nome da categoria em foco
        const val FONTE_SUB = 16f       // linha de baixo do item escolhido
        const val FONTE_CATEGORIA = 18f // nome das categorias na linha
        // ---- transparência dos vizinhos na coluna (posição relativa -> alfa)
        val ALFA_COLUNA = mapOf(-1 to 0.5f, 1 to 0.9f, 2 to 0.65f, 3 to 0.35f)
        const val ANIMA_MS = 220L
        const val COR_CATEGORIA = 0xFFE8E2DA.toInt()
    }

    var aoSubirTopo: (() -> Unit)? = null
    /** Mudou o item em foco (para o destaque/capa de quem usa) */
    var aoMudar: ((Categoria, Item) -> Unit)? = null
    /** OK num item (antes de [Item.abrir]): quem usa grava o "lembrado" da categoria. */
    var aoAbrir: ((CruzLogica.Categoria, String) -> Unit)? = null
    /** Itens lembrados (id da categoria -> id do item), lidos por quem usa; valem só na 1ª montagem. */
    var lembrados: Map<String, String> = emptyMap()

    private var cats: List<Categoria> = emptyList()
    private var mapa = CruzLogica.Mapa(emptyList(), emptyList())
    private var estado = CruzLogica.Estado(0, emptyList())
    private var assinatura: List<String>? = null

    private val viewsCat = ArrayList<View>()
    private val viewsItem = ArrayList<List<ItemView>>()
    private val rotulo: TextView
    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun dpi(v: Int) = (v * dm.density + 0.5f).toInt()
    private val interp = DecelerateInterpolator(1.6f)

    /** As partes de um item que mudam com a escolha. */
    private inner class ItemView(val raiz: LinearLayout, val icone: ImageView, val nome: TextView, val sub: TextView, val traco: View)

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        // Véu escuro atrás da coluna (contraste com qualquer tema ou foto): da esquerda até depois do texto, some antes
        // das categorias da direita. Uma camada só, parada.
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xCC0E1020.toInt(), 0x990E1020.toInt(), 0x000E1020))
        }, LayoutParams(dpi(600), LayoutParams.MATCH_PARENT))
        rotulo = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_ROTULO)
            setTextColor(Estilo.ACENTO)
            typeface = fonteForte
            letterSpacing = 0.16f
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        addView(rotulo, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        rotulo.translationX = dp(COLUNA - ICONE_L / 2f)
        rotulo.translationY = dp(ROTULO_Y)
        setOnFocusChangeListener { _, _ -> posicionar(animar = false); anunciar() }
    }

    /**
     * Troca o conteúdo. Mesmos ids (só textos/ícones novos): atualiza no lugar. Ids diferentes: refaz as views uma vez e
     * mantém o lugar ([CruzLogica.reajustar]).
     */
    fun definir(novas: List<Categoria>) {
        val sig = novas.flatMap { c -> listOf("#" + c.cat.id) + c.itens.map { it.id } }
        val novoMapa = CruzLogica.Mapa(novas.map { it.cat }, novas.map { c -> c.itens.map { it.id } })
        if (sig == assinatura) {
            cats = novas
            for ((j, c) in novas.withIndex()) for ((i, it) in c.itens.withIndex()) preencher(viewsItem[j][i], it)
            return
        }
        estado = if (assinatura == null) CruzLogica.inicial(novoMapa, lembrados) else CruzLogica.reajustar(mapa, estado, novoMapa)
        assinatura = sig
        cats = novas
        mapa = novoMapa
        for (v in viewsCat) removeView(v)
        for (l in viewsItem) for (v in l) removeView(v.raiz)
        viewsCat.clear(); viewsItem.clear()
        for (c in novas) {
            viewsCat += criarCategoria(c).also { addView(it, LayoutParams(dpi(150), LayoutParams.WRAP_CONTENT)) }
            viewsItem += c.itens.map { item -> criarItem().also { preencher(it, item); addView(it.raiz, LayoutParams(dpi(LARGURA_ITEM), dpi(ICONE_A))) } }
        }
        posicionar(animar = false)
        avisarMudanca()
    }

    /** HOME: Assistir; HOME de novo: a TV. */
    fun home() = aplicar(CruzLogica.Tecla.HOME)

    /** Volta à abertura (Assistir › 1º item); as outras categorias ficam onde estão. */
    fun voltarAoInicio() {
        val a = mapa.indice(CruzLogica.Categoria.ASSISTIR).takeIf { it >= 0 } ?: return
        estado = CruzLogica.Estado(a, estado.sel.toMutableList().also { it[a] = 0 })
        posicionar(animar = false); avisarMudanca()
    }

    fun itemAtual(): Item? = cats.getOrNull(estado.cat)?.itens?.getOrNull(estado.sel.getOrElse(estado.cat) { 0 })
    fun categoriaAtual(): Categoria? = cats.getOrNull(estado.cat)

    // ------------------------------------------------------------------ teclas

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val t = when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> CruzLogica.Tecla.ESQUERDA
            KeyEvent.KEYCODE_DPAD_RIGHT -> CruzLogica.Tecla.DIREITA
            KeyEvent.KEYCODE_DPAD_UP -> CruzLogica.Tecla.CIMA
            KeyEvent.KEYCODE_DPAD_DOWN -> CruzLogica.Tecla.BAIXO
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                event.startTracking()   // OK abre no SOLTAR; segurado vira opções (onKeyLongPress)
                return true
            }
            else -> return super.onKeyDown(keyCode, event)
        }
        if (t == CruzLogica.Tecla.CIMA && CruzLogica.passo(mapa, estado, t) is CruzLogica.Resultado.SubirTopo) {
            aoSubirTopo?.invoke()
            return false   // o Android procura o foco acima (a barra de cima)
        }
        aplicar(t)
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
        if (event.isTracking && !event.isCanceled) aplicar(CruzLogica.Tecla.OK)
        return true
    }

    private fun aplicar(t: CruzLogica.Tecla) {
        val r = CruzLogica.passo(mapa, estado, t)
        if (r is CruzLogica.Resultado.Abrir) {
            aoAbrir?.invoke(r.categoria, r.item)
            itemAtual()?.abrir?.invoke()
            return
        }
        if (r.estado == estado) return
        estado = r.estado
        posicionar(animar = true)
        avisarMudanca()
    }

    private fun avisarMudanca() {
        val c = categoriaAtual() ?: return
        val i = itemAtual() ?: return
        rotulo.text = c.nome.uppercase()
        i.aoEscolher?.invoke()
        aoMudar?.invoke(c, i)
        anunciar()
    }

    // ------------------------------------------------------------------ desenho

    private fun criarCategoria(c: Categoria): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        addView(ImageView(context).apply { setImageDrawable(c.icone) }, LinearLayout.LayoutParams(dpi(CAT_L), dpi(CAT_A)))
        addView(TextView(context).apply {
            text = c.nome
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_CATEGORIA)
            setTextColor(COR_CATEGORIA)
            typeface = fonteForte
            maxLines = 1
            includeFontPadding = false
            gravity = Gravity.CENTER_HORIZONTAL
            setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
        }, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6) })
        // a altura vem do conteúdo; o centro do ícone é que fica na linha
        pivotX = dp(75f); pivotY = dp(CAT_A / 2f)
    }

    private fun criarItem(): ItemView {
        val icone = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val nome = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_ESCOLHIDO)
            setTextColor(Estilo.TEXTO)
            typeface = fonteForte
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            maxWidth = dpi(TEXTO_MAX)
            setShadowLayer(dp(8f), 0f, 0f, 0x99000000.toInt())
        }
        val traco = View(context).apply { background = GradientDrawable().apply { setColor(Estilo.ACENTO); cornerRadius = dp(2f) } }
        val sub = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_SUB)
            setTextColor(0xFFE2D8CF.toInt())   // creme claro (contraste > 4,5:1 sobre o véu)
            typeface = fonte
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            maxWidth = dpi(TEXTO_MAX)
            setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
        }
        val textos = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            addView(nome, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(traco, LinearLayout.LayoutParams(dpi(48), dpi(4)).apply { topMargin = dpi(6) })
            addView(sub, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(6) })
        }
        val raiz = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(icone, LinearLayout.LayoutParams(dpi(ICONE_L), dpi(ICONE_A)))
            addView(textos, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dpi(20) })
            pivotX = 0f; pivotY = dp(ICONE_A / 2f)
            visibility = View.INVISIBLE
            alpha = 0f
        }
        return ItemView(raiz, icone, nome, sub, traco)
    }

    private fun preencher(v: ItemView, item: Item) {
        if (v.icone.drawable !== item.icone) v.icone.setImageDrawable(item.icone)
        if (v.nome.text.toString() != item.nome) v.nome.text = item.nome
        if (v.sub.text.toString() != item.sub) v.sub.text = item.sub
        v.sub.visibility = if (item.sub.isEmpty()) View.GONE else View.VISIBLE
    }

    /** Põe cada view no lugar do estado atual (animado ou de uma vez). */
    private fun posicionar(animar: Boolean) {
        val f = estado.cat
        val focada = hasFocus() || isFocused
        for ((j, v) in viewsCat.withIndex()) {
            val k = j - f
            val cx = when {
                k == 0 -> COLUNA
                k < 0 -> CAT_ESQUERDA + (k + 1) * PASSO_CATS
                else -> CAT_DIREITA + (k - 1) * PASSO_CATS
            }
            val visivel = k != 0 && cx > 30f && cx < TELA_L - 40f
            mover(v, dp(cx - 75f), dp(LINHA - CAT_A / 2f), if (visivel) 1f else 0f, 1f, animar)
        }
        for ((j, lista) in viewsItem.withIndex()) {
            val k = j - f
            val sel = estado.sel.getOrElse(j) { 0 }
            for ((i, iv) in lista.withIndex()) {
                val rel = i - sel
                if (k != 0) {
                    // categoria fora de foco: os itens se recolhem no ícone dela e somem
                    val cx = if (k < 0) CAT_ESQUERDA + (k + 1) * PASSO_CATS else CAT_DIREITA + (k - 1) * PASSO_CATS
                    mover(iv.raiz, dp(cx - ICONE_L * ESCALA_PEQUENO / 2f), dp(LINHA - ICONE_A / 2f), 0f, ESCALA_PEQUENO, animar)
                    continue
                }
                val cy = when {
                    rel == 0 -> LINHA
                    rel < 0 -> LINHA - VAO_CIMA + (rel + 1) * PASSO_COLUNA
                    else -> LINHA + VAO_ESCOLHIDO + (rel - 1) * PASSO_COLUNA
                }
                val alfa = if (rel == 0) (if (focada) 1f else 0.75f) else ALFA_COLUNA[rel] ?: 0f
                val escala = if (rel == 0) 1f else ESCALA_PEQUENO
                val x = if (rel == 0) COLUNA - ICONE_L / 2f else COLUNA - ICONE_L * ESCALA_PEQUENO / 2f
                mover(iv.raiz, dp(x), dp(cy - ICONE_A / 2f), alfa, escala, animar)
                iv.traco.alpha = if (rel == 0 && focada) 1f else 0f
                iv.sub.alpha = if (rel == 0) 1f else 0f
            }
        }
    }

    private fun mover(v: View, x: Float, y: Float, alfa: Float, escala: Float, animar: Boolean) {
        v.animate().cancel()
        if (alfa > 0f) v.visibility = View.VISIBLE
        if (!animar || v.visibility != View.VISIBLE) {
            v.translationX = x; v.translationY = y; v.alpha = alfa; v.scaleX = escala; v.scaleY = escala
            if (alfa == 0f) v.visibility = View.INVISIBLE
            return
        }
        v.animate().translationX(x).translationY(y).alpha(alfa).scaleX(escala).scaleY(escala)
            .setDuration(ANIMA_MS).setInterpolator(interp)
            .withEndAction { if (alfa == 0f) v.visibility = View.INVISIBLE }
            .start()
    }

    // ------------------------------------------------------------------ TalkBack

    /** O leitor de tela ouve "Assistir, Séries, T1 E4", e a dica de teclas na 1ª vez que a cruz ganha o foco. */
    private fun anunciar() {
        val c = categoriaAtual() ?: return
        val i = itemAtual() ?: return
        contentDescription = listOf(c.nome, i.nome, i.sub).filter { it.isNotEmpty() }.joinToString(", ")
        if (isFocused) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
        info.isClickable = true
    }

    override fun performClick(): Boolean { super.performClick(); aplicar(CruzLogica.Tecla.OK); return true }
}
