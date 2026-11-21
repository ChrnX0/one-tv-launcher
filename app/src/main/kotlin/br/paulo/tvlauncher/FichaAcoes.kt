package br.paulo.tvlauncher

import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import br.paulo.tvlauncher.home.IconeFicha
import br.paulo.tvlauncher.player.ServicosContas
import br.paulo.tvlauncher.player.ServicosFilmes
import br.paulo.tvlauncher.player.ServicosSync
import br.paulo.tvlauncher.player.Vod
import br.paulo.tvlauncher.search.BuscaOutrosApps
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.weather.DataSources

/**
 * Fichas de filme e série no estilo C "Botões grandes" (15/11/2026, maquete escolhida para quem vê de longe): à esquerda
 * uma lista de ações em letra grande, uma embaixo da outra (▲▼ anda, OK faz), nada cortado nem empurrado para o lado;
 * à direita as informações. Peças comuns às duas telas.
 */
object FichaAcoes {
    // Coluna fina com ícones (16/11/2026, pedido do dono: "mais bonitinha e ocupando menos espaço"): 330 -> 240 dp, e o
    // que sobra vai para as informações. Só a ação com foco tem fundo (faixa branca, a coisa mais óbvia da tela); as
    // outras são ícone + texto, sem caixa. Texto longo (alemão, "Continuar T1E1") quebra em 2 linhas em vez de cortar.
    /** Largura da coluna de ações (dp). */
    const val LARGURA_DP = 240
    const val ALTURA_DP = 48
    const val TEXTO_SP = 19f
    const val DETALHE_SP = 14f
    const val ICONE_DP = 22

    private fun dp(ctx: Context, v: Int) = Estilo.dp(ctx, v)

    private val CORES_TEXTO = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Estilo.TEXTO_NO_FOCO, Estilo.TEXTO))
    private val CORES_ICONE = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Estilo.TEXTO_NO_FOCO, Estilo.TEXTO_2))

    /**
     * "Onde assistir" (16/11/2026, no lugar do "Também em" da JustWatch): abre a busca do Google TV com o nome e o ano,
     * que mostra em quais serviços o título está. Sem o Google TV, nada (a ação não aparece).
     */
    fun ondeAssistir(ctx: Context, acoes: LinearLayout, nome: String, ano: Int?, temGoogleTv: Boolean) {
        val p = BuscaOutrosApps.ondeAssistir(Vod.nomeLimpo(nome), ano, if (temGoogleTv) setOf(BuscaOutrosApps.PACOTE_GOOGLE_TV) else emptySet()) ?: return
        adicionar(acoes, acao(ctx, ctx.getString(R.string.onde_assistir_botao), marca = "onde") { BuscaActivity.procurarEm(ctx, p) })
    }

    /**
     * Convite para conectar a conta Simkl (17/11/2026), no lugar da nota do Simkl para quem não conectou: a Simkl só
     * libera a nota sem login com licença comercial e pediu que o app leve as pessoas a criar conta. Some com a conta
     * conectada, sem a chave do Simkl no build, com a licença no Pro ([DataSources.simklSemLogin]), em Modo visita e no perfil infantil.
     * OK abre Serviços de filmes e séries já no código do Simkl.
     */
    fun conviteSimkl(ctx: Context, acoes: LinearLayout) {
        val s = ServicosFilmes.Servico.SIMKL
        if (DataSources.simklSemLogin(ctx) || !ServicosContas.disponivel(s) || ServicosSync.bloqueado(ctx)) return
        if (ServicosContas.conta(ctx, s) != null) return
        // Estudo global 20/11: o convite aparecia em TODA ficha, para sempre (um passo de D-pad a mais entre "Assistir"
        // e o resto). Agora aparece nas 5 primeiras fichas abertas por dia; quem quer, acha na linha de serviços de filmes das Configurações.
        val p = ctx.getSharedPreferences("convite_simkl", Context.MODE_PRIVATE)
        val hoje = java.time.LocalDate.now().toString()
        val vezes = if (p.getString("dia", "") == hoje) p.getInt("vezes", 0) else 0
        if (vezes >= 5) return
        p.edit().putString("dia", hoje).putInt("vezes", vezes + 1).apply()
        adicionar(acoes, acao(ctx, ctx.getString(R.string.sv_simkl_convite), ctx.getString(R.string.sv_simkl_convite_det), marca = "simkl") {
            ServicosFilmesActivity.abrirEntrar(ctx, s)
        })
    }

    /** O ícone de cada ação, pela marca (a mesma usada para devolver o foco depois de redesenhar). */
    fun iconeDe(marca: String): IconeFicha.Tipo? = when (marca) {
        "continuar", "assistir" -> IconeFicha.Tipo.PLAY
        "comeco" -> IconeFicha.Tipo.COMECO
        "episodios" -> IconeFicha.Tipo.EPISODIOS
        "temporadas" -> IconeFicha.Tipo.TEMPORADAS
        "trailer" -> IconeFicha.Tipo.TRAILER
        "elenco" -> IconeFicha.Tipo.ELENCO
        "versao" -> IconeFicha.Tipo.VERSAO
        "colecao" -> IconeFicha.Tipo.COLECAO
        "sinopse" -> IconeFicha.Tipo.SINOPSE
        "naoeeste" -> IconeFicha.Tipo.NAO_E_ESTE
        "onde" -> IconeFicha.Tipo.ONDE
        else -> null
    }

    /**
     * Uma ação: ícone, texto e um detalhe curto à direita ("16:48", "Temporada 2"). Com foco: faixa branca e tudo
     * escuro; sem foco: só ícone e texto, sem caixa. O "▶" do começo do texto vira o ícone.
     */
    fun acao(ctx: Context, texto: String, detalhe: String? = null, marca: String, aoApertar: () -> Unit): LinearLayout =
        LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(ctx, ALTURA_DP)
            setPaddingRelative(dp(ctx, 16), dp(ctx, 6), dp(ctx, 16), dp(ctx, 6))
            isFocusable = true; isClickable = true; tag = marca
            background = StateListDrawable().apply {
                addState(intArrayOf(android.R.attr.state_focused), GradientDrawable().apply { cornerRadius = dp(ctx, 24).toFloat(); setColor(Estilo.TEXTO) })
                addState(intArrayOf(), ColorDrawable(Color.TRANSPARENT))
            }
            iconeDe(marca)?.let { tipo ->
                addView(android.widget.ImageView(ctx).apply {
                    setImageDrawable(IconeFicha(tipo).apply { setTintList(CORES_ICONE) })
                    isDuplicateParentStateEnabled = true
                }, LinearLayout.LayoutParams(dp(ctx, ICONE_DP), dp(ctx, ICONE_DP)).apply { marginEnd = dp(ctx, 12) })
            }
            val rotulo = TextView(ctx).apply {
                text = texto.removePrefix("▶").trim(); textSize = TEXTO_SP; typeface = Fontes.nunito(500); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
                setLineSpacing(0f, 0.95f)
                isDuplicateParentStateEnabled = true
                setTextColor(CORES_TEXTO)
            }
            val det = detalhe?.takeIf { it.isNotEmpty() }?.let { d -> TextView(ctx).apply {
                text = d; textSize = DETALHE_SP; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; isDuplicateParentStateEnabled = true
                setTextColor(ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused), intArrayOf()), intArrayOf(Estilo.TEXTO_NO_FOCO_2, Estilo.TEXTO_3)))
            } }
            if (det != null && detalheEmbaixo(detalhe)) {
                // Detalhe comprido ("Temporada 1") vai embaixo do texto: à direita ele apertava o texto e "Episódios" quebrava
                // no meio da palavra ("Episódio" / "s"), visto na Sala em 16/11/2026.
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; isDuplicateParentStateEnabled = true
                    addView(rotulo, LinearLayout.LayoutParams(-1, -2))
                    addView(det, LinearLayout.LayoutParams(-1, -2))
                }, LinearLayout.LayoutParams(0, -2, 1f))
            } else {
                addView(rotulo, LinearLayout.LayoutParams(0, -2, 1f))
                if (det != null) addView(det, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(ctx, 8) })
            }
            setOnClickListener { aoApertar() }
        }

    /** Detalhe que vai embaixo do texto, e não à direita: o que passa de 5 caracteres (um "16:48" ou "6" fica à direita). */
    fun detalheEmbaixo(detalhe: String?): Boolean = (detalhe?.length ?: 0) > 5

    /** A coluna das ações (rola sozinha com o foco se não couber). Devolve (o que vai na tela, onde pôr as ações). */
    fun coluna(ctx: Context): Pair<ScrollView, LinearLayout> {
        val lista = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val rolagem = ScrollView(ctx).apply { isVerticalScrollBarEnabled = false; isFocusable = false; clipToPadding = false; addView(lista) }
        return rolagem to lista
    }

    fun adicionar(lista: LinearLayout, v: LinearLayout) {
        val ctx = lista.context
        lista.addView(v, LinearLayout.LayoutParams(-1, -2).apply { if (lista.childCount > 0) topMargin = dp(ctx, 6) })
    }

    /** A sinopse cortada passou de [linhas]? Só se sabe depois de desenhada. */
    fun cortada(t: TextView): Boolean = t.layout?.let { l -> l.lineCount > 0 && l.getEllipsisCount(l.lineCount - 1) > 0 } == true

    /** "Sinopse inteira": o texto todo num cartão por cima, letra grande; OK ou VOLTAR fecha. */
    fun mostrarSinopse(ctx: Context, titulo: String, texto: String) {
        val d = Dialog(ctx)
        val fechar = Estilo.botao(ctx, ctx.getString(R.string.fechar))
        fechar.setOnClickListener { d.dismiss() }
        Estilo.vestir(d, Estilo.dialogo(ctx, null, titulo, null, listOf(fechar), larguraDp = 820).also { tela ->
            // O texto vai num ScrollView próprio (sinopse longa não passa da tela), alinhado à esquerda, letra de leitura.
            val cartao = tela.getChildAt(0) as LinearLayout
            cartao.addView(ScrollView(ctx).apply {
                isVerticalScrollBarEnabled = false
                addView(TextView(ctx).apply { text = texto; textSize = Estilo.Leitura.SINOPSE + 2; setTextColor(Estilo.TEXTO); setLineSpacing(0f, 1.3f) })
            }, 1, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(ctx, 16) })
            (cartao.getChildAt(1) as ScrollView).layoutParams = LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(ctx, 16) }
            cartao.layoutParams = (cartao.layoutParams as android.widget.FrameLayout.LayoutParams).apply { height = dp(ctx, 440) }
        })
        // ▲▼ rolam o texto (o botão Fechar é o único com foco; sem isto uma sinopse longa não teria como ser lida).
        d.setOnKeyListener { _, k, e ->
            val rol = ((d.window?.decorView as? ViewGroup)?.let { achar(it) })
            if (rol != null && (k == android.view.KeyEvent.KEYCODE_DPAD_DOWN || k == android.view.KeyEvent.KEYCODE_DPAD_UP)) {
                if (e.action == android.view.KeyEvent.ACTION_DOWN) rol.smoothScrollBy(0, if (k == android.view.KeyEvent.KEYCODE_DPAD_DOWN) dp(ctx, 90) else -dp(ctx, 90))
                true
            } else false
        }
        d.setOnShowListener { fechar.requestFocus() }
        d.show()
    }

    private fun achar(g: ViewGroup): ScrollView? {
        for (i in 0 until g.childCount) {
            val c = g.getChildAt(i)
            if (c is ScrollView) return c
            if (c is ViewGroup) achar(c)?.let { return it }
        }
        return null
    }
}
