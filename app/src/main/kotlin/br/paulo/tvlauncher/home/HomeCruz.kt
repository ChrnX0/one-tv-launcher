package br.paulo.tvlauncher.home

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Choreographer
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
import br.paulo.tvlauncher.player.item

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

    /**
     * Um item da coluna. [abrir] roda no OK; [opcoes] no OK segurado (null = segurar é igual a apertar).
     * [progresso] (0..1): quanto já foi visto (filme, série, programa da TV ao vivo); vira uma barra embaixo do ícone.
     * [situacao]: o que está acontecendo agora com o item (no ar, parou em…): 1ª linha do canto de baixo à direita.
     */
    class Item(
        val id: String, val nome: String, val sub: String, val icone: Drawable,
        val abrir: () -> Unit, val opcoes: (() -> Unit)? = null, val aoEscolher: (() -> Unit)? = null,
        val progresso: Float? = null, val situacao: CruzLogica.Situacao? = null,
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
        // Até 4 linhas por item nas telas de Ouvir (CruzOuvir); o item tem SUB_SOBRA dp a mais embaixo do ícone para o texto.
        const val SUB_LINHAS = 4
        const val SUB_SOBRA = 76
        // Na HOME, embaixo do nome fica UMA linha (Paulo, 18/11/2026 12:15 local); o resto vai para o canto de baixo à
        // direita, onde a tela está vazia ([CruzLogica.partes]). Aparece quando o foco para, para não piscar navegando.
        const val DETALHE_L = 330         // largura do bloco de detalhes
        const val DETALHE_MARGEM = 48     // da borda direita
        const val DETALHE_BAIXO = 44      // da borda de baixo
        const val DETALHE_LINHAS = 5
        const val DETALHE_ESPERA_MS = 300L
        // A informação de baixo é UMA, mas pode quebrar em 2 linhas na palavra (cortar "Vídeos, músicas e fotos da rede
        // da …" a 3 letras do fim e repetir inteiro no canto ficava pior); passou de 2, termina em "…" e vai inteira para o canto.
        const val ITEM_LINHAS = 2
        const val TEXTO_TOPO = 9        // do topo do ícone ao topo do nome (o nome fica na metade de cima do ícone)
        const val PONTO = 10            // ponto laranja à esquerda do nome do escolhido (era uma barra embaixo dele)
        const val CAT_ESQUERDA = 97f    // centro da categoria logo à esquerda da coluna
        const val CAT_DIREITA = 670f    // centro da 1ª categoria à direita (depois do texto mais longo)
        const val PASSO_CATS = 104f     // entre categorias à direita
        const val ROTULO_Y = 96f        // nome da categoria em foco, acima da linha à esquerda
        const val LARGURA_ITEM = 470    // ícone + texto do item
        const val TEXTO_MAX = 300       // largura máxima do nome/linha de baixo (termina antes das categorias da direita)
        const val TELA_L = 960f
        // ---- fontes (sp): tabela do estudo 32 / 20 / 16 / 18
        const val FONTE_ESCOLHIDO = 32f // nome do item escolhido (fora da escolha fica 0,6 x = ~19)
        const val FONTE_ROTULO = 22f    // nome da categoria em foco (estudo da HOME, 18/11/2026: 20 -> 22)
        const val FONTE_SUB = 18f       // linha de baixo do item escolhido (18 sp = texto padrão do Google para TV; era 16)
        const val FONTE_CATEGORIA = 18f // nome das categorias na linha
        // ---- transparência dos vizinhos na coluna (posição relativa -> alfa)
        // Estudo da HOME (18/11/2026): nenhum nome visível abaixo de 0,6, que a 3 m some (era 0,5 em cima e 0,35 no 3º).
        private val ALFA_NORMAL = mapOf(-1 to 0.6f, 1 to 0.9f, 2 to 0.72f, 3 to 0.6f)
        // Alto contraste (crítica de design 19/11/2026): nada apagado demais; a distância do foco fica só na escala.
        private val ALFA_CONTRASTE = mapOf(-1 to 0.9f, 1 to 1f, 2 to 0.95f, 3 to 0.9f)
        val ALFA_COLUNA: Map<Int, Float> get() = if (Estilo.altoContraste) ALFA_CONTRASTE else ALFA_NORMAL
        /** Item escolhido de outra coluna (fora do foco): 75% normal, inteiro no alto contraste. */
        val ALFA_ESCOLHIDO_FORA: Float get() = if (Estilo.altoContraste) 1f else 0.75f
        // ---- movimento (Paulo 18/11/2026: "mais suave e orgânico"): mola em vez de tempo fixo. A mola guarda a velocidade
        // quando a tecla vem no meio do caminho (nada "freia e recomeça"), e os vizinhos saem um pouco depois (onda).
        const val MOLA_W = 17f          // rigidez (rad/s): ~0,3 s até assentar, perto dos 220 ms de antes
        const val MOLA_AMORTECE = 0.86f // < 1 passa um tiquinho do alvo e volta (1 % na posição); 1 = sem passar
        const val ONDA_MS = 16L         // atraso por passo de distância do item escolhido
        const val ONDA_MAX_MS = 64L
        // Trocar de categoria é um salto lateral grande: sem passar do alvo (amortecimento 1), para não "balançar".
        const val MOLA_AMORTECE_CATEGORIA = 1f
        // Segurando a seta (a partir da 3ª repetição): mola 40% mais rígida e sem onda, para atravessar a lista sem fila.
        const val REPETICAO_RAPIDA = 2
        const val RAPIDA_W = 1.4f
        // O som, segurando a seta, toca 1 vez a cada 3 itens (não metralha).
        const val SOM_A_CADA = 3
        const val APERTO = 0.92f        // "aperto" do ícone no OK: resposta no mesmo quadro do toque
        const val APERTO_MS = 70L
        const val NAO_ABRIU_MS = 4000L  // "Não deu para abrir" no lugar da linha de baixo
        const val FONTE_POSICAO = 18f   // "3 de 9"
        const val BARRA_A = 5f          // altura da barra de progresso (dp)
        const val BARRA_L = 0.72f       // largura da barra em fração do ícone
        // Seta ▼ "tem mais embaixo": abaixo do 3º vizinho (o último visível), no centro dos ícones pequenos.
        const val SETA_L = 28
        const val SETA_A = 12
        const val SETA_Y = 526f
        const val SETA_DX = 64f        // a seta sai do eixo dos ícones e fica sob os nomes
        const val DICA_MS = 1500L       // cruz parada por este tempo: a seta aparece (some na próxima tecla)
        // O ícone escolhido começa a animação dele depois que a coluna assenta (~0,3 s de mola): durante o movimento a view
        // tem camada de GPU, e um desenho que muda a cada quadro obrigaria a refazer a camada inteira a cada 33 ms.
        const val ICONE_ESPERA_MS = 450L
        const val COR_CATEGORIA = 0xFFE8E2DA.toInt()
        const val COR_SUB = 0xFFE2D8CF.toInt()
    }

    /** Quanto os ícones se mexem (MovimentoAdaptativo); quem usa decide pelo aparelho e pelos quadros medidos. */
    var nivel: MovimentoAdaptativo.Nivel = MovimentoAdaptativo.Nivel.COMPLETO
        set(v) { if (field != v) { field = v; posicionar(animar = false) } }
    /** ▲ no 1º item saiu da cruz; o número é o repeatCount da tecla (0 = toque novo, >0 = vinha segurando). */
    var aoSubirTopo: ((Int) -> Unit)? = null
    /** Andou com uma seta (para a arte deslizar ao contrário: paralaxe, onda 3). Não vem na HOME nem na borda. */
    var aoPasso: ((CruzLogica.Tecla) -> Unit)? = null
    /** Som de cada passo (SonsCruz): quem usa decide se toca. */
    var aoSom: ((SonsCruz.Som, CruzLogica.Categoria?) -> Unit)? = null
    /** Mudou o item em foco (para o destaque/capa de quem usa) */
    var aoMudar: ((Categoria, Item) -> Unit)? = null
    /** OK num item (antes de [Item.abrir]): quem usa grava o "lembrado" da categoria. */
    var aoAbrir: ((CruzLogica.Categoria, String) -> Unit)? = null
    /** Barra "Relógio" ocupa o canto de baixo com a hora grande: os detalhes vão para o canto de cima, à direita. */
    var detalheEmCima = false
        set(v) {
            if (field == v) return
            field = v
            (detalhe.layoutParams as LayoutParams).apply {
                gravity = (if (v) Gravity.TOP else Gravity.BOTTOM) or Gravity.END
                topMargin = if (v) dpi(DETALHE_BAIXO) else 0
                bottomMargin = if (v) 0 else dpi(DETALHE_BAIXO)
            }
            detalhe.gravity = (if (v) Gravity.TOP else Gravity.BOTTOM) or Gravity.END
            detalhe.requestLayout()
        }
    /** Itens lembrados (id da categoria -> id do item), lidos por quem usa; valem só na 1ª montagem. */
    var lembrados: Map<String, String> = emptyMap()
    /** O último item aberto ("categoria|item"); quem usa grava e devolve aqui na 1ª montagem (HOME refeita). */
    var ultimoAberto: String? = null

    private var cats: List<Categoria> = emptyList()
    private var mapa = CruzLogica.Mapa(emptyList(), emptyList())
    private var estado = CruzLogica.Estado(0, emptyList())
    private var assinatura: List<String>? = null

    private val viewsCat = ArrayList<View>()
    private val viewsItem = ArrayList<List<ItemView>>()
    private val rotulo: TextView
    /** "3 de 9" ao lado do nome da categoria: quanto falta na coluna (estudo da HOME, onda 2). */
    private val posicao: TextView
    /** Seta ▼ embaixo da coluna quando há itens escondidos lá embaixo; aparece com a cruz parada ([DICA_MS]). */
    private val maisAbaixo: View
    /** Os detalhes do item em foco, no canto de baixo à direita. */
    private val detalhe: TextView
    private val dm = resources.displayMetrics
    private fun dp(v: Float) = v * dm.density
    private fun dpi(v: Int) = (v * dm.density + 0.5f).toInt()

    /** As partes de um item que mudam com a escolha. */
    private inner class ItemView(val raiz: LinearLayout, val icone: ImageView, val nome: TextView, val sub: TextView, val traco: View) {
        /** Linhas da linha de baixo na última arrumação (1 ou 2; com 2, os vizinhos de baixo descem uma linha). */
        var linhas = 1
        var voltarSub: Runnable? = null
        /** A linha de baixo (só a principal: [CruzLogica.partes]), para voltar depois do "Não deu para abrir". */
        var principal: String = ""
        /** Barra de "quanto já foi visto", criada só no item que tem progresso. */
        var barra: BarraProgresso? = null
    }

    /** Barra fina e arredondada no pé do ícone (como nas miniaturas de vídeo): trilho claro, parte vista na cor de destaque. */
    private inner class BarraProgresso : Drawable() {
        var valor = 0f
            set(v) { field = v.coerceIn(0f, 1f); invalidateSelf() }
        private val trilho = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0x59FFFFFF }
        private val cheio = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.ACENTO }
        private val r = android.graphics.RectF()
        override fun draw(c: android.graphics.Canvas) {
            val b = bounds
            val a = dp(BARRA_A); val l = b.width() * BARRA_L
            r.set(b.exactCenterX() - l / 2f, b.bottom - a - dp(2f), b.exactCenterX() + l / 2f, b.bottom - dp(2f))
            c.drawRoundRect(r, a / 2f, a / 2f, trilho)
            if (valor > 0f) { r.right = r.left + maxOf(a, l * valor); c.drawRoundRect(r, a / 2f, a / 2f, cheio) }
        }
        override fun setAlpha(alpha: Int) {}
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java")
        override fun getOpacity() = android.graphics.PixelFormat.TRANSLUCENT
    }

    init {
        isFocusable = true
        isFocusableInTouchMode = false
        // A cruz é desenhada por posição absoluta (translationX a partir da esquerda). Em árabe a direção da view virava
        // da direita para a esquerda e todos os filhos começavam na borda direita: a coluna saía da tela (telas ar,
        // 18/11/2026). Fica sempre da esquerda para a direita; o texto em árabe continua escrito na direção dele.
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        clipChildren = false
        clipToPadding = false
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        // Véu escuro atrás da coluna (contraste com qualquer tema ou foto): da esquerda até depois do texto, some antes
        // das categorias da direita. Uma camada só, parada.
        addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0xCC121528.toInt(), 0x99121528.toInt(), 0x00121528))
        }, LayoutParams(dpi(600), LayoutParams.MATCH_PARENT))
        rotulo = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_ROTULO)
            setTextColor(Estilo.ACENTO)
            typeface = fonteForte
            letterSpacing = 0.16f
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        posicao = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_POSICAO)
            setTextColor(COR_SUB)
            typeface = fonte
            includeFontPadding = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
        }
        val cabecalho = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(rotulo, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            addView(posicao, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply { marginStart = dpi(14) })
        }
        addView(cabecalho, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        cabecalho.translationX = dp(COLUNA - ICONE_L / 2f)
        cabecalho.translationY = dp(ROTULO_Y)
        maisAbaixo = Seta(ctx).apply { alpha = 0f; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        addView(maisAbaixo, LayoutParams(dpi(SETA_L), dpi(SETA_A)))
        // Ao lado dos ícones, embaixo do nome (crítica de design 19/11/2026): no eixo, a seta caía em cima do logo do 4º app.
        maisAbaixo.translationX = dp(COLUNA + SETA_DX - SETA_L / 2f)
        maisAbaixo.translationY = dp(SETA_Y)
        detalhe = TextView(ctx).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_SUB)
            setTextColor(COR_SUB)
            typeface = fonte
            // à direita, colado no canto (a cruz é sempre da esquerda para a direita: END = direita)
            gravity = Gravity.END or Gravity.BOTTOM
            maxLines = DETALHE_LINHAS
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dp(6f), 1f)
            setShadowLayer(dp(8f), 0f, 0f, 0xCC000000.toInt())
            alpha = 0f
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO   // o TalkBack já ouve tudo no item
        }
        addView(detalhe, LayoutParams(dpi(DETALHE_L), LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.END).apply {
            marginEnd = dpi(DETALHE_MARGEM); bottomMargin = dpi(DETALHE_BAIXO)
        })
        setOnFocusChangeListener { _, tem -> posicionar(animar = false); anunciar(); atualizarDetalhe(); if (tem) dicaDepois() else esconderDica() }
    }

    /**
     * Troca o conteúdo. Mesmos ids (só textos/ícones novos): atualiza no lugar. Ids diferentes: refaz as views uma vez e
     * mantém o lugar ([CruzLogica.reajustar]).
     */
    fun definir(novas: List<Categoria>) {
        val sig = novas.flatMap { c -> listOf("#" + c.cat.id) + c.itens.map { it.id } }
        val novoMapa = CruzLogica.Mapa(novas.map { it.cat }, novas.map { c -> c.itens.map { it.id } })
        if (sig == assinatura) {
            val antes = itemAtual()
            cats = novas
            for ((j, c) in novas.withIndex()) for ((i, it) in c.itens.withIndex()) preencher(viewsItem[j][i], it)
            // O item em foco mudou de texto ou ícone (canal novo, episódio novo): a arte e o TalkBack acompanham.
            val agora = itemAtual()
            if (agora != null && (antes?.nome != agora.nome || antes.sub != agora.sub || antes.icone !== agora.icone || antes.situacao != agora.situacao)) avisarMudanca()
            return
        }
        estado = if (assinatura == null) CruzLogica.inicial(novoMapa, lembrados, ultimoAberto?.substringAfter('|'))
            else CruzLogica.reajustar(mapa, estado, novoMapa)
        assinatura = sig
        cats = novas
        mapa = novoMapa
        for (v in viewsCat) removeView(v)
        for (l in viewsItem) for (v in l) removeView(v.raiz)
        viewsCat.clear(); viewsItem.clear()
        for (c in novas) {
            viewsCat += criarCategoria(c).also { addView(it, LayoutParams(dpi(150), LayoutParams.WRAP_CONTENT)) }
            viewsItem += c.itens.map { item -> criarItem().also { preencher(it, item); addView(it.raiz, LayoutParams(dpi(LARGURA_ITEM), dpi(ICONE_A + SUB_SOBRA))) } }
        }
        posicionar(animar = false)
        avisarMudanca()
    }

    /**
     * Voltando de um app (HOME ou VOLTAR): o foco vai para o item que abriu o último app, na categoria e posição dele
     * (dono, 18/11/2026; antes ia sempre para Assistir, quase sempre na TV). Se o item não está na cruz, fica onde estava.
     */
    fun voltarDoApp() {
        val u = ultimoAberto ?: return
        val cat = CruzLogica.Categoria.entries.firstOrNull { it.id == u.substringBefore('|') }
        val e = CruzLogica.focar(mapa, estado, u.substringAfter('|'), cat) ?: return
        if (e == estado) return
        estado = e
        posicionar(animar = false); avisarMudanca()
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

    /** Quantas vezes a tecla de agora já se repetiu (segurada): muda a mola e o som (ver [REPETICAO_RAPIDA]). */
    private var repeticao = 0

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        repeticao = event.repeatCount
        esconderDica()
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
            aoSubirTopo?.invoke(repeticao)
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
            aoSom?.invoke(SonsCruz.Som.ABRIR, categoriaAtual()?.cat)
            ultimoAberto = r.categoria.id + "|" + r.item
            aoAbrir?.invoke(r.categoria, r.item)
            apertar()
            // Um app que não abre (desinstalado no meio, sem tela de TV, recusado pelo Android) não falha em silêncio.
            try { itemAtual()?.abrir?.invoke() } catch (e: Exception) { naoAbriu() }
            return
        }
        val rapida = t != CruzLogica.Tecla.HOME && repeticao >= REPETICAO_RAPIDA
        val soar = !rapida || repeticao % SOM_A_CADA == 0
        if (r.estado == estado) {
            if (t != CruzLogica.Tecla.HOME && repeticao == 0) aoSom?.invoke(SonsCruz.Som.BORDA, categoriaAtual()?.cat)   // fim da coluna/linha
            return
        }
        val trocouCategoria = r.estado.cat != estado.cat
        if (soar) aoSom?.invoke(if (trocouCategoria) SonsCruz.Som.GRUPO else SonsCruz.Som.ANDAR, cats.getOrNull(r.estado.cat)?.cat)
        estado = r.estado
        amortecer = if (trocouCategoria) MOLA_AMORTECE_CATEGORIA else MOLA_AMORTECE
        rigidez = if (rapida) MOLA_W * RAPIDA_W else MOLA_W
        semOnda = rapida
        posicionar(animar = true)
        avisarMudanca()
        if (t != CruzLogica.Tecla.HOME && !rapida) aoPasso?.invoke(t)
    }

    // Parâmetros da mola para o passo de agora (lidos por [mover]).
    private var amortecer = MOLA_AMORTECE
    private var rigidez = MOLA_W
    private var semOnda = false

    /** O ícone do item escolhido "aperta" e volta: o toque foi entendido, no mesmo quadro. */
    private fun apertar() {
        val iv = viewAtual() ?: return
        if (semAnimacao()) return
        iv.icone.animate().cancel()
        iv.icone.pivotX = iv.icone.width / 2f; iv.icone.pivotY = iv.icone.height / 2f
        iv.icone.animate().scaleX(APERTO).scaleY(APERTO).setDuration(APERTO_MS).withEndAction {
            iv.icone.animate().scaleX(1f).scaleY(1f).setDuration(APERTO_MS * 3)
                .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
        }.start()
    }

    /**
     * Quem usa avisa que o item não abriu (ou a abertura deu erro): a linha de baixo vira "Não deu para abrir" na cor de
     * destaque por [NAO_ABRIU_MS], e o leitor de tela fala. Depois volta ao texto de antes.
     */
    fun naoAbriu() {
        val iv = viewAtual() ?: return
        val aviso = context.getString(R.string.cruz_nao_abriu)
        iv.sub.text = aviso
        iv.sub.setTextColor(Estilo.ACENTO)
        iv.sub.visibility = View.VISIBLE
        announceForAccessibility(aviso)
        iv.sub.removeCallbacks(iv.voltarSub)
        iv.voltarSub = Runnable {
            // o texto de AGORA (o item pode ter mudado nesses 4 s: programa novo, minutos que passaram)
            val agora = iv.principal
            iv.sub.setTextColor(COR_SUB)
            iv.sub.text = agora
            iv.sub.visibility = if (agora.isEmpty()) View.GONE else View.VISIBLE
        }
        iv.sub.postDelayed(iv.voltarSub, NAO_ABRIU_MS)
    }

    // ---- dica de "tem mais embaixo" (idosos acham a coluna mais rápido com dica de seta; notas do estudo, IJHCI 2018)

    private val mostrarDica = Runnable {
        val n = categoriaAtual()?.itens?.size ?: 0
        val sel = estado.sel.getOrElse(estado.cat) { 0 }
        val tem = sel + ALFA_COLUNA.keys.max() < n - 1 && (hasFocus() || isFocused)
        maisAbaixo.animate().cancel()
        if (tem) maisAbaixo.animate().alpha(0.8f).setDuration(if (semAnimacao()) 0L else 300L).start() else maisAbaixo.alpha = 0f
    }

    private fun dicaDepois() { removeCallbacks(mostrarDica); postDelayed(mostrarDica, DICA_MS) }

    private fun esconderDica() { removeCallbacks(mostrarDica); maisAbaixo.animate().cancel(); maisAbaixo.alpha = 0f }

    /** Um "v" de traço arredondado, na cor da linha de baixo. Desenhado à mão: nenhuma fonte garante o glifo ▾. */
    private class Seta(ctx: Context) : View(ctx) {
        private val tinta = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = COR_SUB
            style = android.graphics.Paint.Style.STROKE
            strokeCap = android.graphics.Paint.Cap.ROUND
            strokeJoin = android.graphics.Paint.Join.ROUND
            setShadowLayer(4f, 0f, 0f, 0x99000000.toInt())
        }
        private val caminho = android.graphics.Path()
        override fun onDraw(c: android.graphics.Canvas) {
            val e = height * 0.28f
            tinta.strokeWidth = e
            caminho.reset()
            caminho.moveTo(e, e); caminho.lineTo(width / 2f, height - e); caminho.lineTo(width - e, e)
            c.drawPath(caminho, tinta)
        }
    }

    /** Para o teste de tela: o texto de baixo do item em foco, como está desenhado. */
    internal fun subAtual(): TextView? = viewAtual()?.sub

    private fun viewAtual(): ItemView? = viewsItem.getOrNull(estado.cat)?.getOrNull(estado.sel.getOrElse(estado.cat) { 0 })

    private fun avisarMudanca() {
        val c = categoriaAtual() ?: return
        val i = itemAtual() ?: return
        rotulo.text = c.nome.uppercase()
        val n = c.itens.size
        val sel = estado.sel.getOrElse(estado.cat) { 0 }
        posicao.text = if (n > 1) context.getString(R.string.cruz_posicao, sel + 1, n) else ""
        dicaDepois()
        i.aoEscolher?.invoke()
        aoMudar?.invoke(c, i)
        anunciar()
        atualizarDetalhe()
    }

    // ---- detalhes no canto de baixo

    private val mostrarDetalhe = Runnable {
        val i = itemAtual()
        val iv = viewAtual()
        val linhas = if (i == null || iv == null || !(hasFocus() || isFocused)) emptyList() else {
            val p = CruzLogica.partes(i.sub, CruzLogica.tirarDoItem(i.situacao))
            val cortada = p.principal.isNotEmpty() && android.text.StaticLayout.Builder
                .obtain(p.principal, 0, p.principal.length, iv.sub.paint, dpi(TEXTO_MAX)).setIncludePad(false).build().lineCount > ITEM_LINHAS
            CruzLogica.detalhes(p, principalCortada = cortada)
        }
        val s = i?.situacao?.takeIf { it.texto.isNotBlank() && iv != null && (hasFocus() || isFocused) }
        // Nome que não coube ("What universal fre…", visto na Sala 18/11/2026 no Continuar): vai inteiro no topo do canto.
        val nomeInteiro = i?.nome?.takeIf { n -> iv != null && (hasFocus() || isFocused) &&
            android.text.TextPaint(iv.nome.paint).apply {
                textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, FONTE_ESCOLHIDO, resources.displayMetrics)
            }.measureText(n) > dpi(TEXTO_MAX) }
        detalhe.animate().cancel()
        if (linhas.isEmpty() && s == null && nomeInteiro == null) { detalhe.alpha = 0f; detalhe.text = ""; return@Runnable }
        // 1ª linha: a situação, com o rótulo em negrito (laranja é só marca, foco e progresso; estudo global 20/11) ("Parou em  T1 E1 · 35% · faltam 43 min"); depois os detalhes.
        val texto = android.text.SpannableStringBuilder().apply {
            if (nomeInteiro != null) {
                append(nomeInteiro, android.text.style.ForegroundColorSpan(Estilo.TEXTO), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (s != null || linhas.isNotEmpty()) append("\n")
            }
            if (s != null) {
                val ini = length
                append(s.rotulo, android.text.style.ForegroundColorSpan(Estilo.TEXTO), android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), ini, length, android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                append("  ").append(s.texto)
                if (linhas.isNotEmpty()) append("\n")
            }
            append(linhas.joinToString("\n"))
        }
        if (detalhe.text.toString() != texto.toString()) detalhe.text = texto
        detalhe.animate().alpha(1f).setDuration(if (semAnimacao()) 0L else 220L).start()
    }

    /** O foco mudou (ou o texto do item): os detalhes somem na hora e voltam quando o foco para ([DETALHE_ESPERA_MS]). */
    private fun atualizarDetalhe() {
        removeCallbacks(mostrarDetalhe)
        detalhe.animate().cancel()
        detalhe.alpha = 0f
        postDelayed(mostrarDetalhe, if (semAnimacao()) 0L else DETALHE_ESPERA_MS)
    }

    /** Para o teste de tela: os detalhes do item em foco, como estão no canto de baixo. */
    internal fun detalheAtual(): TextView = detalhe

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
        // O ponto laranja do escolhido (Paulo, 18/11/2026: a barra embaixo do nome virou um ponto à esquerda dele).
        val traco = View(context).apply { background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Estilo.ACENTO) } }
        val sub = TextView(context).apply {
            setTextSize(TypedValue.COMPLEX_UNIT_SP, FONTE_SUB)
            setTextColor(COR_SUB)   // creme claro (contraste > 4,5:1 sobre o véu)
            typeface = fonte
            // Uma informação, até 2 linhas (o resto vai para o canto de baixo: [detalhe]).
            maxLines = ITEM_LINHAS
            setLineSpacing(dp(3f), 1f)
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            setLineSpacing(dp(3f), 1f)
            setShadowLayer(dp(6f), 0f, 0f, 0x99000000.toInt())
        }
        val linhaNome = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(traco, LinearLayout.LayoutParams(dpi(PONTO), dpi(PONTO)).apply { marginEnd = dpi(10) })
            addView(nome, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
        }
        val textos = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.TOP
            setPaddingRelative(0, dpi(TEXTO_TOPO), 0, 0)
            addView(linhaNome, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT))
            // o texto de baixo começa embaixo do nome (alinhado com ele, não com o ponto)
            addView(sub, LinearLayout.LayoutParams(dpi(TEXTO_MAX), LayoutParams.WRAP_CONTENT).apply { topMargin = dpi(8); marginStart = dpi(PONTO + 10) })
        }
        // Sem "sobreposição" (20/11/2026): com transparência < 1 (os vizinhos apagados da coluna, 0,6 a 0,9) o Android
        // desenhava o item inteiro numa camada à parte e depois a colava, a CADA quadro em que algo se mexia na cruz
        // (medido na Sala: ~1,5 camadas de 940 x 340 px por quadro, "alpha caused saveLayer"). O ícone e os textos ficam
        // lado a lado, então aplicar a transparência em cada desenho dá o mesmo resultado; a única diferença é a sombra
        // do texto aparecer sob a letra transparente, sobre o véu escuro (~1% de brilho, invisível).
        val raiz = object : LinearLayout(context) {
            override fun hasOverlappingRendering() = false
        }.apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.TOP
            clipChildren = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            addView(icone, LinearLayout.LayoutParams(dpi(ICONE_L), dpi(ICONE_A)))
            addView(textos, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT).apply { marginStart = dpi(20) })
            pivotX = 0f; pivotY = dp(ICONE_A / 2f)
            visibility = View.INVISIBLE
            alpha = 0f
        }
        return ItemView(raiz, icone, nome, sub, traco).also { iv ->
            // A informação quebrou em 2 linhas (ou voltou a 1): a coluna se rearruma.
            sub.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                val n = sub.lineCount.coerceIn(1, ITEM_LINHAS)
                if (n != iv.linhas) { iv.linhas = n; post { posicionar(animar = true) } }
            }
        }
    }

    private fun preencher(v: ItemView, item: Item) {
        if (v.icone.drawable !== item.icone) v.icone.setImageDrawable(item.icone)
        if (v.nome.text.toString() != item.nome) v.nome.text = item.nome
        val principal = CruzLogica.partes(item.sub, CruzLogica.tirarDoItem(item.situacao)).principal
        if (v.principal != principal || v.sub.text.toString() != principal) { v.principal = principal; v.sub.text = principal }
        v.sub.visibility = if (principal.isEmpty()) View.GONE else View.VISIBLE
        val p = item.progresso
        if (p == null) { if (v.icone.foreground != null) v.icone.foreground = null }
        else {
            val b = v.barra ?: BarraProgresso().also { v.barra = it }
            b.valor = p
            if (v.icone.foreground !== b) v.icone.foreground = b
        }
    }

    private fun onda(distancia: Int) = if (semOnda) 0L else minOf(distancia * ONDA_MS, ONDA_MAX_MS)

    override fun onWindowFocusChanged(temFoco: Boolean) {
        super.onWindowFocusChanged(temFoco)
        posicionar(animar = false)   // o ponto do escolhido some com um menu por cima e volta quando ele fecha
    }

    /** Põe cada view no lugar do estado atual (animado ou de uma vez). */
    private fun posicionar(animar: Boolean) {
        val f = estado.cat
        val focada = hasFocus() || isFocused
        val semMovimento = semAnimacao()
        semMov = semMovimento   // lido por [mover] em cada view: uma leitura do ajuste por arrumação, não ~40
        val nivelAgora = if (semMovimento) MovimentoAdaptativo.Nivel.PARADO else nivel
        val descEsc = MovimentoAdaptativo.descansoEscolhido(nivelAgora)
        for ((j, v) in viewsCat.withIndex()) {
            val k = j - f
            val cx = when {
                k == 0 -> COLUNA
                k < 0 -> CAT_ESQUERDA + (k + 1) * PASSO_CATS
                else -> CAT_DIREITA + (k - 1) * PASSO_CATS
            }
            val visivel = k != 0 && cx > 30f && cx < TELA_L - 40f
            mover(v, dp(cx - 75f), dp(LINHA - CAT_A / 2f), if (visivel) 1f else 0f, 1f, animar, onda(kotlin.math.abs(k)))
            // As categorias dos lados tocam a animação delas de vez em quando, uma de cada vez (atraso pela posição).
            // Quanto: MovimentoAdaptativo (em ciclo, uma vez ao chegar, ou nada), conforme o aparelho aguenta.
            val descCat = MovimentoAdaptativo.descansoCategoria(nivelAgora)
            ((v as? ViewGroup)?.getChildAt(0) as? ImageView)?.drawable.let { it as? IconeCruz }
                ?.animar(visivel && focada && descCat != null, descCat ?: IconeCruz.DESCANSO_CATEGORIA_MS, MovimentoAdaptativo.atrasoCategoria(nivelAgora, j))
        }
        for ((j, lista) in viewsItem.withIndex()) {
            val k = j - f
            val sel = estado.sel.getOrElse(j) { 0 }
            // A informação do escolhido em 2 linhas: os vizinhos de baixo descem a linha a mais.
            val extra = lista.getOrNull(sel)?.takeIf { k == 0 && it.sub.visibility == View.VISIBLE }
                ?.let { (it.linhas - 1).coerceAtLeast(0) * it.sub.lineHeight / dm.density } ?: 0f
            for ((i, iv) in lista.withIndex()) {
                val rel = i - sel
                if (k != 0) {
                    // categoria fora de foco: os itens se recolhem no ícone dela e somem
                    val cx = if (k < 0) CAT_ESQUERDA + (k + 1) * PASSO_CATS else CAT_DIREITA + (k - 1) * PASSO_CATS
                    mover(iv.raiz, dp(cx - ICONE_L * ESCALA_PEQUENO / 2f), dp(LINHA - ICONE_A / 2f), 0f, ESCALA_PEQUENO, animar, onda(kotlin.math.abs(rel)))
                    if (iv.icone.isActivated) iv.icone.isActivated = false
                    (iv.icone.drawable as? IconeCruz)?.animar(false)
                    continue
                }
                val cy = when {
                    rel == 0 -> LINHA
                    rel < 0 -> LINHA - VAO_CIMA + (rel + 1) * PASSO_COLUNA
                    else -> LINHA + VAO_ESCOLHIDO + extra + (rel - 1) * PASSO_COLUNA
                }
                val alfa = if (rel == 0) (if (focada) 1f else ALFA_ESCOLHIDO_FORA) else ALFA_COLUNA[rel] ?: 0f
                val escala = if (rel == 0) 1f else ESCALA_PEQUENO
                val x = if (rel == 0) COLUNA - ICONE_L / 2f else COLUNA - ICONE_L * ESCALA_PEQUENO / 2f
                mover(iv.raiz, dp(x), dp(cy - ICONE_A / 2f), alfa, escala, animar, onda(kotlin.math.abs(rel)))
                // Sem o ponto quando outra janela (menu do app, lista) está por cima: um foco só na tela (crítica 19/11/2026).
                iv.traco.alpha = if (rel == 0 && focada && hasWindowFocus()) 1f else 0f
                // Ícones "Linha" (IconeCruz): o escolhido toca a animação dele (a cor é de cada ícone, sempre).
                val aceso = rel == 0 && focada
                if (iv.icone.isActivated != aceso) iv.icone.isActivated = aceso
                (iv.icone.drawable as? IconeCruz)?.animar(aceso && descEsc != null, descEsc ?: IconeCruz.DESCANSO_ESCOLHIDO_MS, if (animar) ICONE_ESPERA_MS else 0L)
                iv.sub.alpha = if (rel == 0) 1f else 0f
            }
        }
    }

    private fun mover(v: View, x: Float, y: Float, alfa: Float, escala: Float, animar: Boolean, atrasoMs: Long = 0L) {
        val m = molas.getOrPut(v) { Mola(v) }
        if (alfa > 0f) v.visibility = View.VISIBLE
        if (!animar || v.visibility != View.VISIBLE || semMov) {
            m.parar(x, y, alfa, escala)
            return
        }
        m.mirar(floatArrayOf(x, y, alfa, escala), android.os.SystemClock.uptimeMillis() + atrasoMs, rigidez, amortecer)
        if (m !in ativas) ativas += m
        if (!quadroPedido) { quadroPedido = true; ultimoQuadro = 0L; Choreographer.getInstance().postFrameCallback(quadro) }
    }

    private var semMov = false

    /** "Remover animações" (acessibilidade) ou escala de animação 0 nas opções do desenvolvedor: tudo vai direto. */
    private fun semAnimacao() =
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)

    // ------------------------------------------------------------------ mola

    /**
     * Uma view com quatro propriedades em mola (x, y, transparência, escala), cada uma com posição e velocidade. A conta é a
     * de um amortecedor: aceleração = -w²·(posição - alvo) - 2·ζ·w·velocidade. Um quadro do Choreographer anda todas.
     */
    private inner class Mola(val v: View) {
        val pos = FloatArray(4); val vel = FloatArray(4); val alvo = FloatArray(4)
        private var proximo: FloatArray? = null
        private var trocaEm = 0L
        private var camada = false

        fun ler() { pos[0] = v.translationX; pos[1] = v.translationY; pos[2] = v.alpha; pos[3] = v.scaleX }

        fun parar(x: Float, y: Float, alfa: Float, escala: Float) {
            ativas.remove(this); proximo = null
            vel.fill(0f); alvo[0] = x; alvo[1] = y; alvo[2] = alfa; alvo[3] = escala
            pos.indices.forEach { pos[it] = alvo[it] }
            aplicar(); semCamada()
            if (alfa == 0f) v.visibility = View.INVISIBLE
        }

        private var w = MOLA_W
        private var zeta = MOLA_AMORTECE

        fun mirar(novo: FloatArray, quando: Long, rigidez: Float, amortece: Float) {
            if (this !in ativas) { ler(); vel.fill(0f); pos.indices.forEach { alvo[it] = pos[it] } }
            proximo = novo; trocaEm = quando; w = rigidez; zeta = amortece
        }

        /** Anda dt segundos; devolve true se ainda está em movimento. */
        fun andar(agora: Long, dt: Float): Boolean {
            proximo?.let { if (agora >= trocaEm) { it.copyInto(alvo); proximo = null } }
            val zw2 = 2f * zeta * w; val ww = w * w
            var t = dt
            while (t > 0f) {   // passos de até 4 ms: estável mesmo se um quadro atrasar
                val h = minOf(t, 0.004f); t -= h
                for (i in 0..3) {
                    // transparência sem passar do alvo (amortecimento crítico): piscar de alfa chama atenção
                    val z = if (i == 2) 2f * w else zw2
                    vel[i] += (-ww * (pos[i] - alvo[i]) - z * vel[i]) * h
                    pos[i] += vel[i] * h
                }
            }
            pos[2] = pos[2].coerceIn(0f, 1f)
            val parado = proximo == null &&
                kotlin.math.abs(pos[0] - alvo[0]) < 0.5f && kotlin.math.abs(pos[1] - alvo[1]) < 0.5f &&
                kotlin.math.abs(pos[2] - alvo[2]) < 0.004f && kotlin.math.abs(pos[3] - alvo[3]) < 0.002f &&
                kotlin.math.abs(vel[0]) < 8f && kotlin.math.abs(vel[1]) < 8f
            if (parado) { pos.indices.forEach { pos[it] = alvo[it] }; vel.fill(0f) }
            // Escala ou transparência mudando: camada de GPU (texto e vetor rasterizados UMA vez, não a cada quadro; foi a
            // cauda lenta medida na Sala, p95 42 ms). Só translação não precisa.
            if (!parado && (kotlin.math.abs(pos[2] - alvo[2]) > 0.004f || kotlin.math.abs(pos[3] - alvo[3]) > 0.002f)) comCamada()
            aplicar()
            if (parado) { semCamada(); if (alvo[2] == 0f) v.visibility = View.INVISIBLE }
            return !parado
        }

        private fun aplicar() {
            v.translationX = pos[0]; v.translationY = pos[1]; v.alpha = pos[2]; v.scaleX = pos[3]; v.scaleY = pos[3]
        }
        private fun comCamada() { if (!camada && v.isAttachedToWindow) { camada = true; v.setLayerType(View.LAYER_TYPE_HARDWARE, null) } }
        private fun semCamada() { if (camada) { camada = false; v.setLayerType(View.LAYER_TYPE_NONE, null) } }
    }

    private val molas = HashMap<View, Mola>()
    private val ativas = ArrayList<Mola>()
    private var quadroPedido = false
    private var ultimoQuadro = 0L
    private val quadro: Choreographer.FrameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(t: Long) {
            val dt = if (ultimoQuadro == 0L) 1f / 60f else ((t - ultimoQuadro) / 1e9f).coerceIn(0f, 1f / 20f)
            ultimoQuadro = t
            val agora = android.os.SystemClock.uptimeMillis()
            ativas.removeAll { !it.andar(agora, dt) }
            if (ativas.isNotEmpty() && isAttachedToWindow) Choreographer.getInstance().postFrameCallback(this)
            else quadroPedido = false
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        Choreographer.getInstance().removeFrameCallback(quadro); quadroPedido = false
        removeCallbacks(mostrarDica)
        // fora da tela: tudo direto no lugar (nada fica no meio do caminho)
        ativas.toList().forEach { m -> m.andar(Long.MAX_VALUE, 5f) }
        ativas.clear()
    }

    // ------------------------------------------------------------------ TalkBack

    /** O leitor de tela ouve "Assistir, Séries, T1 E4", e a dica de teclas na 1ª vez que a cruz ganha o foco. */
    private fun anunciar() {
        val c = categoriaAtual() ?: return
        val i = itemAtual() ?: return
        contentDescription = listOf(c.nome, i.nome, i.situacao?.let { it.rotulo + " " + it.texto }.orEmpty(), i.sub, posicao.text.toString()).filter { it.isNotEmpty() }.joinToString(", ")
        if (isFocused) sendAccessibilityEvent(AccessibilityEvent.TYPE_VIEW_FOCUSED)
    }

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = android.widget.Button::class.java.name
        info.isClickable = true
    }

    override fun performClick(): Boolean { super.performClick(); aplicar(CruzLogica.Tecla.OK); return true }
}
