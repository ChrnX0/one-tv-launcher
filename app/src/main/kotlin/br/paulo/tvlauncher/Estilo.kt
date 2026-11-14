package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView

/**
 * O sistema de design do launcher (10/11/2026, dono: "todo o launcher precisa ser conciso e coerente").
 * Proposta aprovada: https://claude.ai/artifact/3a6coAJtQT1s9E37Gb3JVw
 *
 * Regra de uso: tela nova (ou tela mexida) NÃO escreve cor, tamanho de letra, raio nem tempo de animação na mão.
 * Pede aqui: `Estilo.texto(ctx, Papel.TITULO, "Configurações")`, `Estilo.linhaOpcao(...)`, `Estilo.pilula(...)` etc.
 * Assim, mudar o visual do app inteiro é mudar este arquivo.
 *
 * Medidas em dp/sp da TV: a Sala tem 1920x1080 px com densidade 320, ou seja, 960 x 540 dp.
 */
object Estilo {

    // ------------------------------------------------------------------ cores
    // Formato 0xAARRGGBB: os dois primeiros dígitos são a opacidade (FF = sólido, 00 = invisível).

    /** Fundo de toda tela sem imagem atrás (era o fundo mais usado no código antigo). */
    const val FUNDO = 0xFF0B0F17.toInt()
    /** Superfície de card/linha em tela lisa: branco a 6%. */
    const val SUPERFICIE = 0x0FFFFFFF
    /** Superfície um nível acima (linha focada sem virar pílula, chip ativo): branco a 12%. */
    const val SUPERFICIE_ALTA = 0x1FFFFFFF
    /** Fio de borda das superfícies: branco a 8%. */
    const val BORDA = 0x14FFFFFF
    /** Vidro (sobre imagem): tinta escura translúcida + fio claro. Igual à pílula da HOME que o dono aprovou. */
    const val VIDRO_TINTA = 0x8C0B0F17.toInt()
    const val VIDRO_BORDA = 0x38FFFFFF

    /** Texto: principal, explicação (70%) e rótulo apagado (48%). Nunca branco puro em bloco grande: cansa a vista. */
    const val TEXTO = 0xFFF2F5F3.toInt()
    const val TEXTO_2 = 0xB3F2F5F3.toInt()
    const val TEXTO_3 = 0x7AF2F5F3.toInt()
    /** Texto em cima do foco branco (linha de lista focada). */
    const val TEXTO_NO_FOCO = 0xFF0B0F17.toInt()
    const val TEXTO_NO_FOCO_2 = 0xA60B0F17.toInt()

    /** O ÚNICO acento: "ligado", valor escolhido, progresso. Foco NÃO usa acento: foco é branco. */
    const val ACENTO = 0xFF7FD6C2.toInt()
    /** Acento legível sobre o foco branco. */
    const val ACENTO_NO_FOCO = 0xFF1F6F5D.toInt()
    /** Semânticas: atenção (chuva, vencimento) e erro/ao vivo. Não contam como acento. */
    const val ATENCAO = 0xFFF5B544.toInt()
    const val ERRO = 0xFFFF6B5E.toInt()
    /** Foco: branco. A TV é vista a 3 m; o foco tem que ser a coisa mais óbvia da tela. */
    const val FOCO = 0xFFFFFFFF.toInt()

    // ------------------------------------------------------------------ tipografia (5 papéis, Manrope)

    /** Papel do texto: tamanho em sp, peso da Manrope e cor. Caixa alta + espaçamento só na SECAO. */
    enum class Papel(val sp: Float, val peso: Int, val cor: Int, val caixaAlta: Boolean = false, val espaco: Float = 0f) {
        /** Hora, temperatura grande. */
        DISPLAY(56f, 200, TEXTO),
        /** Título de tela, nome do item em destaque. */
        TITULO(28f, 600, TEXTO),
        /** Linhas, cards, botões. */
        CORPO(16f, 500, TEXTO),
        /** Explicação, metadado. */
        LEGENDA(13f, 400, TEXTO_2),
        /** Nome de grupo ("PRÓXIMOS 9 DIAS"). */
        SECAO(12f, 600, TEXTO_3, caixaAlta = true, espaco = 0.14f),
    }

    fun fonte(peso: Int): Typeface = Fontes.manrope(peso)

    /**
     * Filmes, Séries e TV ao vivo (13/11/2026: letra maior para ler a 3 m). São telas de LER, a 3 m do sofá, então
     * ficam um degrau acima de [Papel.CORPO]/[Papel.LEGENDA]: ~+15% nos títulos e +20-30% no texto corrido e nas listas.
     * As três telas usam estes mesmos números para continuarem iguais entre si.
     */
    object Leitura {
        // Ficha de filme/série
        const val TITULO = 32f          // era 28 (filme) / 26 (série)
        const val DADOS = 17f           // ano · duração · nota · gênero; era 14 / 13,5
        const val SINOPSE = 18f         // era 15 / 13,5
        const val CREDITOS = 16f        // Direção, Elenco; era 13
        const val AVISO = 15f           // "carregando…", "sem episódios"; era 13 / 14
        const val BOTAO = 18f           // Assistir, Trailer…; era 16
        const val BOTAO_PEQUENO = 16f   // temporadas; era 13,5
        // Linhas de lista: episódio, canal
        const val ITEM = 17f            // número e nome; era 14 / 14,5
        const val ITEM_DETALHE = 15f    // sinopse do episódio, programa do canal; era 12
        // Coluna de categorias e cabeçalhos
        const val CATEGORIA = 16f       // era 14,5
        const val CONTAGEM = 13.5f      // número ao lado da categoria; era 12
        const val ROTULO = 13f          // "CATEGORIAS", "A SEGUIR"; era 11,5
        const val DICA = 12.5f          // linha de teclas no rodapé; era 11
        // Grade de pôsteres e painel do canal
        const val CARD = 14.5f          // nome embaixo do pôster; era 12
        const val GRADE_COLUNAS = 5     // pôsteres por linha em Filmes e Séries; eram 6 (pôster ~20% maior)
        const val POSTER_ALTURA = 180   // dp; era 150 (mantém a proporção 2:3 com 5 colunas)
        /**
         * Nome EM CIMA do pôster (13/11/2026): na fileira cortada embaixo da tela o nome continua aparecendo. Altura fixa
         * de 2 linhas, texto alinhado embaixo (encostado no pôster dele), para os pôsteres ficarem alinhados.
         */
        const val NOME_CARD_ALTURA = 48 // dp: 2 linhas de [CARD] + 6 de folga até o pôster
        const val SUB_CARD_ALTURA = 18  // dp: a linha "T2E5" da Série, embaixo do nome
        const val GRADE_VAO = 20        // dp entre fileiras (era 14): separa o nome do pôster de cima
        const val FOCADO = 16f          // linha do item em foco acima da grade; era 14
        const val PROGRAMA = 22f        // título do programa no detalhe do canal; era 19
    }

    // ------------------------------------------------------------------ forma, espaço, movimento

    /** Só 3 raios: chip pequeno, card/painel e pílula (botão, foco de lista, avisos). */
    const val RAIO_PEQUENO = 8
    const val RAIO = 16
    const val RAIO_PILULA = 999

    /** Grade de 8 dp. */
    const val E1 = 8
    const val E2 = 16
    const val E3 = 24
    const val E6 = 48
    /** Margem segura da TV (algumas cortam a borda da imagem). */
    const val MARGEM_LATERAL = 48
    const val MARGEM_VERTICAL = 27

    /** Só 2 tempos: foco (140 ms) e entrar/sair (220 ms), sempre desacelerando. */
    const val FOCO_MS = 140L
    const val TRANSICAO_MS = 220L
    private val desacelera = DecelerateInterpolator()

    /** Quanto cresce o que ganha foco: card 8%, linha de lista 2%. */
    const val ESCALA_CARD = 1.08f
    const val ESCALA_LINHA = 1.02f

    /**
     * Matiz escuro de uma cor (dono, 10/11/2026, decisão 3): mistura [cor] com o [FUNDO], ficando só [quanto] da cor.
     * Os cards coloridos da HOME mantêm a identidade (TV coral, Séries roxo…) sem brigar com banners e com a pintura.
     */
    fun matiz(cor: Int, quanto: Float = 0.42f): Int {
        fun canal(desloc: Int) = ((cor shr desloc and 0xFF) * quanto + (FUNDO shr desloc and 0xFF) * (1 - quanto)).toInt()
        return (0xFF shl 24) or (canal(16) shl 16) or (canal(8) shl 8) or canal(0)
    }

    /** A mesma cor com outra opacidade ([a] de 0 a 255): o véu escuro do player é o [FUNDO] a 80%, por exemplo. */
    fun alfa(cor: Int, a: Int): Int = (a.coerceIn(0, 255) shl 24) or (cor and 0xFFFFFF)

    fun dp(ctx: Context, v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), ctx.resources.displayMetrics).toInt()

    // ------------------------------------------------------------------ peças básicas

    /** Um TextView já no papel certo (tamanho, peso, cor). [conteudo] pode ficar vazio e ser trocado depois. */
    fun texto(ctx: Context, papel: Papel, conteudo: CharSequence = ""): TextView = TextView(ctx).apply {
        aplicar(this, papel)
        text = conteudo
    }

    /** Põe um TextView que já existe no papel (para telas antigas migrarem aos poucos). */
    fun aplicar(t: TextView, papel: Papel) {
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, papel.sp)
        t.typeface = fonte(papel.peso)
        t.setTextColor(papel.cor)
        t.isAllCaps = papel.caixaAlta
        t.letterSpacing = papel.espaco
        t.includeFontPadding = false   // sem o "colchão" de cima e de baixo da fonte: o espaço fica por conta da grade
    }

    /** Fundo arredondado. [borda] = cor do fio (0 = sem fio). */
    fun fundo(ctx: Context, cor: Int, raioDp: Int = RAIO, borda: Int = 0, espessuraDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(cor)
            cornerRadius = dp(ctx, raioDp).toFloat()
            if (borda != 0) setStroke(dp(ctx, espessuraDp), borda)
        }

    /** Vidro sem desfoque (o desfoque de verdade só existe em janela; ver [AvisoTv]): tinta escura + fio claro. */
    fun vidro(ctx: Context, raioDp: Int = RAIO): GradientDrawable = fundo(ctx, VIDRO_TINTA, raioDp, VIDRO_BORDA)

    // ------------------------------------------------------------------ foco (UMA regra no app inteiro)

    /**
     * Foco de CARD: cresce 8%, ganha anel branco de 3 dp e sobe (sombra). [anel] desenha por cima do card, então serve
     * para card com imagem (banner de app). Chame uma vez por card.
     */
    fun focoCard(v: View) {
        val ctx = v.context
        val anel = fundo(ctx, 0, RAIO + 3, FOCO, 3)
        v.setOnFocusChangeListener { view, foco ->
            view.foreground = if (foco) anel else null
            view.animate().scaleX(if (foco) ESCALA_CARD else 1f).scaleY(if (foco) ESCALA_CARD else 1f)
                .translationZ(if (foco) dp(ctx, 8).toFloat() else 0f)
                .setDuration(FOCO_MS).setInterpolator(desacelera).start()
        }
    }

    /**
     * Foco de LINHA de lista: a linha vira pílula branca e os textos dela escurecem. [textos] = os TextViews da linha
     * com o papel de cada um, para a cor voltar certa quando o foco sai.
     */
    fun focoLinha(v: View, vararg textos: Pair<TextView, Papel>) {
        val ctx = v.context
        val normal = fundo(ctx, SUPERFICIE, RAIO, BORDA)
        val focado = fundo(ctx, FOCO, RAIO)
        v.background = normal
        v.setOnFocusChangeListener { view, foco ->
            view.background = if (foco) focado else normal
            for ((t, papel) in textos) t.setTextColor(
                when {
                    !foco -> t.getTag(R.id.estilo_cor_normal) as? Int ?: papel.cor
                    papel == Papel.LEGENDA || papel == Papel.SECAO -> TEXTO_NO_FOCO_2
                    t.getTag(R.id.estilo_cor_normal) == ACENTO -> ACENTO_NO_FOCO
                    else -> TEXTO_NO_FOCO
                }
            )
            view.animate().scaleX(if (foco) ESCALA_LINHA else 1f).scaleY(if (foco) ESCALA_LINHA else 1f)
                .setDuration(FOCO_MS).setInterpolator(desacelera).start()
        }
    }

    // ------------------------------------------------------------------ componentes

    /** Nome de grupo, em caixa alta e apagado ("CLIMA", "PRÓXIMOS 9 DIAS"). */
    fun secao(ctx: Context, nome: String): TextView = texto(ctx, Papel.SECAO, nome).apply {
        setPadding(dp(ctx, 4), dp(ctx, E3), 0, dp(ctx, E1))
    }

    /**
     * Linha de opção, a peça de toda lista (Configurações, Extras, Rotinas…): título, explicação embaixo e, à direita,
     * [direita] (valor "Escuro ›", um interruptor ou nada). Já vem focável, com o foco padrão.
     */
    fun linhaOpcao(ctx: Context, titulo: String, explicacao: String? = null, direita: View? = null): LinearLayout {
        val t = texto(ctx, Papel.CORPO, titulo)
        val e = explicacao?.let { texto(ctx, Papel.LEGENDA, it).apply { setPadding(0, dp(ctx, 4), 0, 0) } }
        val coluna = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(t)
            if (e != null) addView(e)
        }
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, 56)
            setPadding(dp(ctx, E2 + 4), dp(ctx, 12), dp(ctx, E2 + 4), dp(ctx, 12))
            isFocusable = true
            isClickable = true
            addView(coluna, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (direita != null) addView(direita, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, E2) })
            val pares = mutableListOf(t to Papel.CORPO)
            if (e != null) pares += e to Papel.LEGENDA
            if (direita is TextView) pares += direita to Papel.CORPO
            focoLinha(this, *pares.toTypedArray())
        }
    }

    /** O valor à direita de uma linha ("Escuro ›"), no acento. */
    fun valor(ctx: Context, texto: String, seta: Boolean = true): TextView =
        texto(ctx, Papel.CORPO, if (seta) "$texto  ›" else texto).apply {
            setTextColor(ACENTO); setTag(R.id.estilo_cor_normal, ACENTO)
        }

    /**
     * Pílula: o chip do topo da HOME (clima, despertador, timer, "diga o que quer ver"), que o dono aprovou
     * (10/11/2026: "gosto dela"). Vidro escuro com fio claro; com foco, fica branca e o texto escurece.
     * [icone] opcional, 20 dp. Os textos vão em [addView] depois, como num LinearLayout comum.
     */
    /** [vidro] = false: sem fundo próprio (pílula dentro de uma cápsula de vidro, como no topo da HOME); o foco é o mesmo. */
    fun pilula(ctx: Context, icone: Drawable? = null, focavel: Boolean = true, vidro: Boolean = true): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 12), 0, dp(ctx, 14), 0)
        minimumHeight = dp(ctx, 32)
        if (icone != null) addView(ImageView(ctx).apply { setImageDrawable(icone) },
            LinearLayout.LayoutParams(dp(ctx, 20), dp(ctx, 20)).apply { marginEnd = dp(ctx, 8) })
        val normal = if (vidro) vidro(ctx, RAIO_PILULA) else null
        background = normal
        if (focavel) {
            isFocusable = true
            isClickable = true
            val focado = fundo(ctx, FOCO, RAIO_PILULA)
            setOnFocusChangeListener { v, foco ->
                v.background = if (foco) focado else normal
                tingir(v as ViewGroup, if (foco) TEXTO_NO_FOCO else null)
                v.animate().scaleX(if (foco) ESCALA_LINHA else 1f).scaleY(if (foco) ESCALA_LINHA else 1f)
                    .setDuration(FOCO_MS).setInterpolator(desacelera).start()
            }
        }
    }

    /** Cor de texto que escurece sozinha com o foco (para botões com fundo StateListDrawable: foco = branco). */
    fun textoFoco(): android.content.res.ColorStateList = android.content.res.ColorStateList(
        arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(TEXTO_NO_FOCO, TEXTO))

    /** Superfície de linha/botão de lista: repouso = branco 6% com fio; foco = branco sólido (use com [focarLinha]). */
    fun superficie(ctx: Context, focado: Boolean): GradientDrawable =
        if (focado) fundo(ctx, FOCO, RAIO) else fundo(ctx, SUPERFICIE, RAIO, BORDA)

    /**
     * Foco de linha para telas que montam a linha do seu jeito: troca o fundo ([superficie]) e escurece TODOS os textos
     * e ícones de linha de dentro dela (guardando a cor de cada um para devolver). Chame no setOnFocusChangeListener.
     */
    fun focarLinha(v: View, foco: Boolean) {
        v.background = superficie(v.context, foco)
        if (v is ViewGroup) tingir(v, if (foco) TEXTO_NO_FOCO else null)
        else if (v is TextView) {
            if (v.getTag(R.id.estilo_cor_normal) == null) v.setTag(R.id.estilo_cor_normal, v.currentTextColor)
            v.setTextColor(if (foco) TEXTO_NO_FOCO else v.getTag(R.id.estilo_cor_normal) as Int)
        }
    }

    /** Pinta textos e ícones de dentro de [g] com [cor]; null = volta cada um à cor que tinha. */
    private fun tingir(g: ViewGroup, cor: Int?) {
        for (i in 0 until g.childCount) when (val f = g.getChildAt(i)) {
            is TextView -> {
                if (f.getTag(R.id.estilo_cor_normal) == null) f.setTag(R.id.estilo_cor_normal, f.currentTextColor)
                f.setTextColor(cor ?: f.getTag(R.id.estilo_cor_normal) as Int)
            }
            // Só ícone de linha muda de cor; ilustração (o sol 3D do clima) fica como está.
            is ImageView -> if (f.drawable is IconeLinha || f.drawable is IconeApp) {
                if (cor != null) f.setColorFilter(cor) else f.clearColorFilter()
            }
            is ViewGroup -> tingir(f, cor)
        }
    }

    /** Botão: pílula sólida (primário) ou translúcida (secundário). Foco = branco + cresce. */
    fun botao(ctx: Context, rotulo: String, primario: Boolean = true): TextView = texto(ctx, Papel.CORPO, rotulo).apply {
        gravity = Gravity.CENTER
        setPadding(dp(ctx, E3), dp(ctx, 10), dp(ctx, E3), dp(ctx, 10))
        isFocusable = true
        isClickable = true
        val normal = fundo(ctx, if (primario) SUPERFICIE_ALTA else SUPERFICIE, RAIO_PILULA, BORDA)
        val focado = fundo(ctx, FOCO, RAIO_PILULA)
        background = normal
        setOnFocusChangeListener { v, foco ->
            v.background = if (foco) focado else normal
            (v as TextView).setTextColor(if (foco) TEXTO_NO_FOCO else TEXTO)
            v.animate().scaleX(if (foco) ESCALA_CARD else 1f).scaleY(if (foco) ESCALA_CARD else 1f)
                .setDuration(FOCO_MS).setInterpolator(desacelera).start()
        }
    }

    /**
     * Barra de dicas do controle, embaixo, em toda tela que não é a HOME: pares tecla → ação.
     * Ex.: `barraDicas(ctx, "▲▼" to "escolher", "OK" to "mudar", "VOLTAR" to "sair")`.
     */
    fun barraDicas(ctx: Context, vararg dicas: Pair<String, String>): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        for ((tecla, acao) in dicas) {
            addView(texto(ctx, Papel.LEGENDA, tecla).apply {
                textSize = 11f; typeface = fonte(600); setTextColor(TEXTO_2)
                background = fundo(ctx, 0, 6, 0x40FFFFFF)
                setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
            })
            addView(texto(ctx, Papel.LEGENDA, acao).apply { setTextColor(TEXTO_3) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    marginStart = dp(ctx, 6); marginEnd = dp(ctx, E3)
                })
        }
    }

    /**
     * Estado vazio ou de erro: ícone, o que aconteceu ([titulo]), o que a TV já está fazendo ou o que fazer ([texto]) e
     * até dois botões. O foco cai no primeiro botão (quem chama faz requestFocus nele se quiser). Nunca "Erro" sozinho.
     */
    fun estadoVazio(ctx: Context, icone: Drawable?, titulo: String, texto: String, vararg botoes: TextView): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(ctx, E6), dp(ctx, E6), dp(ctx, E6), dp(ctx, E6))
            if (icone != null) addView(ImageView(ctx).apply { setImageDrawable(icone); alpha = 0.6f },
                LinearLayout.LayoutParams(dp(ctx, 48), dp(ctx, 48)).apply { bottomMargin = dp(ctx, E2) })
            addView(texto(ctx, Papel.TITULO, titulo).apply { textSize = 22f; gravity = Gravity.CENTER })
            addView(texto(ctx, Papel.LEGENDA, texto).apply {
                gravity = Gravity.CENTER; textSize = 15f; maxWidth = dp(ctx, 440)
                setLineSpacing(0f, 1.25f); setPadding(0, dp(ctx, E1 + 2), 0, dp(ctx, E3))
            })
            if (botoes.isNotEmpty()) addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                botoes.forEachIndexed { i, b ->
                    addView(b, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { if (i > 0) marginStart = dp(ctx, E2) })
                }
            })
        }

    /**
     * Título de tela, com a margem segura da TV. Uma só altura e um só tamanho em todas as telas
     * (antes: Roboto Light 34 sp nas Configurações, nada na Previsão, 24 sp em outras).
     */
    fun tituloTela(ctx: Context, titulo: String): TextView = texto(ctx, Papel.TITULO, titulo).apply {
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
    }

    /** Entrada suave de uma tela/painel: some → aparece em [TRANSICAO_MS], subindo 12 dp. */
    fun entrar(v: View) {
        v.alpha = 0f; v.translationY = dp(v.context, 12).toFloat()
        v.animate().alpha(1f).translationY(0f).setDuration(TRANSICAO_MS).setInterpolator(desacelera).start()
    }

    /** Fundo padrão de tela lisa: [FUNDO] com um brilho azulado bem suave no alto à esquerda (como a HOME Escuro). */
    /**
     * Fundo de TODA tela que não é a HOME (10/11/2026, dono: "tem menu fora de padrão"): o MESMO grafite da HOME no tema
     * Escuro (Fundos.kt, desenharFundoEscuro), em vez da textura pontilhada antiga (drawable fundo.jpg) e dos cinzas
     * soltos de cada tela. Telas de lista ficam em fundo liso mesmo no tema Galeria (decisão 1: vidro só com imagem atrás).
     */
    fun fundoTela(@Suppress("UNUSED_PARAMETER") ctx: Context): Drawable = FundoGrafite()

    /**
     * Tela cuja raiz já pinta o grafite na tela toda: a cor de fundo da janela (tema) por baixo é uma camada de tela
     * cheia a mais em cada quadro, que ninguém vê (13/11/2026, medido na Sala com "mostrar sobreposição"). Tira essa cor.
     * Chamado para toda tela pelo LauncherApp (onActivityStarted); só age quando a raiz é o grafite e ocupa a janela.
     */
    fun tirarCorDaJanelaSeCoberta(a: android.app.Activity) {
        val conteudo = a.findViewById<android.view.ViewGroup>(android.R.id.content) ?: return
        val raiz = conteudo.getChildAt(0) ?: return
        val lp = raiz.layoutParams ?: return
        if (raiz.background !is FundoGrafite || lp.width != android.view.ViewGroup.LayoutParams.MATCH_PARENT ||
            lp.height != android.view.ViewGroup.LayoutParams.MATCH_PARENT) return
        a.window.setBackgroundDrawable(null)
    }

    private class FundoGrafite : Drawable() {
        override fun draw(c: android.graphics.Canvas) {
            c.save(); c.translate(bounds.left.toFloat(), bounds.top.toFloat())
            FundoEscuroPronto.desenhar(c, bounds.width().toFloat(), bounds.height().toFloat())   // uma imagem pronta (Fundos.kt)
            c.restore()
        }
        override fun setAlpha(alpha: Int) {}   // opaco sempre (ninguém usa alfa neste fundo)
        override fun setColorFilter(cf: android.graphics.ColorFilter?) {}
        @Deprecated("Deprecated in Java") override fun getOpacity() = android.graphics.PixelFormat.OPAQUE
    }

    /**
     * Molde de tela de lista (um só para o app inteiro): fundo da HOME, margem da HOME, título no tamanho de título e a
     * coluna onde a tela põe as linhas. Devolve (raiz para o setContentView, coluna).
     */
    fun telaLista(ctx: Context, titulo: String, subtitulo: String? = null): Pair<View, LinearLayout> {
        // Título e subtítulo fixos; só as linhas rolam (12/11/2026, moldura da HOME): rolando tudo, as linhas passavam por
        // baixo da hora da moldura (Moldura.kt), que fica parada no alto à direita.
        val raiz = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = fundoTela(ctx)
            clipChildren = false; clipToPadding = false
            setPadding(dp(ctx, MARGEM_LATERAL), dp(ctx, MARGEM_VERTICAL + 9), dp(ctx, MARGEM_LATERAL), 0)
            addView(tituloTela(ctx, titulo))
            if (subtitulo != null) addView(texto(ctx, Papel.LEGENDA, subtitulo), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(ctx, 4) })
        }
        val coluna = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            // Folga de cima (E2) dentro da rolagem: a linha focada cresce 2% e não pode ser cortada pela borda dela.
            setPadding(0, dp(ctx, E2), 0, dp(ctx, MARGEM_VERTICAL + 16))
        }
        raiz.addView(android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false; isFillViewport = true
            addView(coluna)
        }, LinearLayout.LayoutParams(-1, 0, 1f))
        return raiz to coluna
    }

    /**
     * Linha de texto focável, a mesma das Configurações: superfície clara, foco = pílula branca com texto escuro e um
     * leve aumento. Para telas que só têm uma frase por linha (Extras, Casa, Calibração...).
     */
    fun linhaTexto(ctx: Context, conteudo: CharSequence = ""): TextView = texto(ctx, Papel.CORPO, conteudo).apply {
        textSize = 17f
        isFocusable = true; isClickable = true
        setPadding(dp(ctx, 20), dp(ctx, 14), dp(ctx, 20), dp(ctx, 14))
        background = superficie(ctx, false)
        setOnFocusChangeListener { v, foco ->
            focarLinha(v, foco)
            v.animate().scaleX(if (foco) ESCALA_LINHA else 1f).scaleY(if (foco) ESCALA_LINHA else 1f).setDuration(FOCO_MS).start()
        }
    }

    /**
     * Informação do item em foco num catálogo (Filmes, Séries; coerência visual parte 2, 12/11/2026): EM CIMA da grade,
     * como o destaque da HOME. Nome em 24 sp e, na mesma linha, a estrela da nota em âmbar e o resto em LEGENDA:
     * "Resident Evil   ★ 7,3  ·  2026  ·  Prime Video  ·  você parou em 20%".
     */
    fun infoFoco(nome: String, nota: Float?, resto: List<String>): CharSequence {
        val sb = android.text.SpannableStringBuilder(nome)
        sb.setSpan(android.text.style.AbsoluteSizeSpan(24, true), 0, sb.length, 0)
        sb.setSpan(android.text.style.ForegroundColorSpan(TEXTO), 0, sb.length, 0)
        sb.setSpan(android.text.style.TypefaceSpan(fonte(600)), 0, sb.length, 0)
        val ini = sb.length
        sb.append("   ")
        if (nota != null && nota > 0f) {
            val i = sb.length
            sb.append("★ " + String.format(java.util.Locale.getDefault(), "%.1f", nota))
            sb.setSpan(android.text.style.ForegroundColorSpan(ATENCAO), i, sb.length, 0)
            if (resto.isNotEmpty()) sb.append("  ·  ")
        }
        sb.append(resto.joinToString("  ·  "))
        sb.setSpan(android.text.style.AbsoluteSizeSpan(14, true), ini, sb.length, 0)
        return sb
    }

    /** LayoutParams de uma linha dentro da coluna do [telaLista]: largura toda, 8 dp entre linhas. */
    fun lpLinha(ctx: Context) = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, E1) }

    /** Para quem precisa de um container com o fundo padrão e a margem segura já aplicados. */
    fun telaBase(ctx: Context): FrameLayout = FrameLayout(ctx).apply {
        background = fundoTela(ctx)
        setPadding(dp(ctx, MARGEM_LATERAL), dp(ctx, MARGEM_VERTICAL), dp(ctx, MARGEM_LATERAL), dp(ctx, MARGEM_VERTICAL))
    }

    /** Escurecido atrás de um diálogo: a tela de trás fica visível mas não compete. */
    const val VEU = 0xB3000000.toInt()
    /** Cartão de diálogo: quase opaco (lê-se por cima de qualquer coisa), com o fio do vidro. */
    const val CARTAO = 0xF5141A23.toInt()

    /**
     * Diálogo único (perguntas por cima de qualquer tela: confirmar, permitir, aviso parado). Cartão opaco no padrão
     * dos painéis (raio 16, fio claro), rótulo SECAO em cima, TITULO, texto em LEGENDA e os botões em pílula
     * ([botao]) lado a lado. Devolve a tela inteira, já com o véu, para o setContentView.
     * O primeiro botão da lista é o seguro (Cancelar/Negar) e fica à esquerda; quem chama dá o foco nele.
     */
    fun dialogo(ctx: Context, sobre: String?, titulo: CharSequence, mensagem: CharSequence?, botoes: List<TextView>,
                icone: Drawable? = null, larguraDp: Int = 640): FrameLayout {
        val cartao = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            clipToPadding = false
            setPadding(dp(ctx, E6 - 8), dp(ctx, E3 + 8), dp(ctx, E6 - 8), dp(ctx, E3 + 8))
            background = fundo(ctx, CARTAO, RAIO, VIDRO_BORDA)
            if (icone != null) addView(android.widget.ImageView(ctx).apply { setImageDrawable(icone) },
                LinearLayout.LayoutParams(dp(ctx, 56), dp(ctx, 56)).apply { bottomMargin = dp(ctx, E2) })
            if (sobre != null) addView(texto(ctx, Papel.SECAO, sobre).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(ctx, E1) })
            addView(texto(ctx, Papel.TITULO, titulo).apply { textSize = 24f; gravity = Gravity.CENTER })
            if (!mensagem.isNullOrEmpty()) addView(texto(ctx, Papel.LEGENDA, mensagem).apply {
                textSize = 16f; gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(ctx, E2) })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                clipChildren = false
                botoes.forEachIndexed { i, b ->
                    addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(ctx, E2) })
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(ctx, E3 + 8) })
        }
        return FrameLayout(ctx).apply {
            setBackgroundColor(VEU)
            addView(cartao, FrameLayout.LayoutParams(dp(ctx, larguraDp), -2, Gravity.CENTER))
        }
    }

    /**
     * Lista de escolha por cima da tela (11/11/2026, pente fino): o mesmo cartão do [dialogo], com as linhas de opção
     * das Configurações ([linhaOpcao]). Substitui as caixas cinzas do Android (AlertDialog setItems/setSingleChoiceItems)
     * no player, em filmes e séries e no menu da HOME. As [marcadas] ganham um "✓" e o foco começa na primeira.
     * OK numa linha fecha e chama [aoEscolher]; VOLTAR fecha sem escolher.
     */
    fun lista(ctx: Context, titulo: CharSequence, itens: List<CharSequence>, marcadas: Collection<Int> = emptyList(),
              aoFechar: (() -> Unit)? = null, aoEscolher: (Int) -> Unit): android.app.Dialog {
        val d = android.app.Dialog(ctx)
        val linhas = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(ctx, E1), dp(ctx, E1), dp(ctx, E1), dp(ctx, E1))
        }
        val vistas = itens.mapIndexed { i, rotulo ->
            linhaOpcao(ctx, rotulo.toString(), direita = if (i in marcadas) valor(ctx, "✓", seta = false) else null).apply {
                setOnClickListener { d.dismiss(); aoEscolher(i) }
            }.also { linhas.addView(it, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(ctx, E1) }) }
        }
        val rolagem = android.widget.ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
            addView(linhas)
        }
        val cartao = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(ctx, E3), dp(ctx, E3), dp(ctx, E3), dp(ctx, E3))
            background = fundo(ctx, CARTAO, RAIO, VIDRO_BORDA)
            addView(texto(ctx, Papel.TITULO, titulo).apply { textSize = 22f; maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
                LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(ctx, E2) })
            addView(rolagem, LinearLayout.LayoutParams(-1, -2))
        }
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(cartao)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            addFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.6f)
            setLayout(dp(ctx, 600), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        d.setOnShowListener { (marcadas.firstOrNull { it in vistas.indices }?.let { vistas[it] } ?: vistas.firstOrNull())?.requestFocus() }
        if (aoFechar != null) d.setOnDismissListener { aoFechar() }
        d.show()
        return d
    }
}
