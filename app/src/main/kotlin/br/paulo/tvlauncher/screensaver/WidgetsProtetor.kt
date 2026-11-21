package br.paulo.tvlauncher.screensaver

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.FonteVidro
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.Vidro
import br.paulo.tvlauncher.home.IconeLinha
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.weather.IconesClima
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Os widgets de baixo do protetor, nos três estilos que a pessoa escolhe nas Configurações (15/11/2026,
 * [Protetor.CHAVE_WIDGET]; esboços em fundo-tempo/widgets/ na pasta do projeto). Os três mostram o tempo de agora, as
 * próximas horas e amanhã; nenhuma letra abaixo de 12 sp (idosos usam a TV, de longe).
 *  - FAIXA: uma faixa de vidro em três partes; as próximas horas numa curva da temperatura com a chuva em barrinhas e
 *    o nascer/pôr do sol riscado na hora certa ([CurvaHoras]).
 *  - CARTOES: quatro cartões de vidro: agora, próximas horas, hoje (sol, lua, umidade, chuva) e amanhã.
 *  - SOLTO: sem caixa; a temperatura grande, o tempo dito numa frase ("Chuva fraca até umas 3h") e as horas à direita.
 *  - LINHA e GRANDE (17/11/2026): tudo numa linha; ou a temperatura enorme e fina à direita.
 * Quem chama é o [ProtetorGaleria]; os dados vêm prontos em [Dados] (a lógica pura está em [WidgetsTempo]).
 */
class WidgetsProtetor(private val ctx: Context, private val vidro: FonteVidro) {
    private val m400: Typeface = Fontes.nunito(ctx, 400)
    private val m500: Typeface = Fontes.nunito(ctx, 500)
    private val m600: Typeface = Fontes.nunito(ctx, 600)
    private val branco = 0xFFFFFFFF.toInt()
    private val azulChuva = 0xFF9CC2FF.toInt()
    private val amarelo = 0xFFFFD66B.toInt()
    private val hh = SimpleDateFormat("H'h'", Locale.getDefault())
    private val hm = SimpleDateFormat("HH:mm", Locale.getDefault())

    class Amanha(val max: Int, val min: Int, val chuva: Int?, val codigo: Int)

    /** Tudo o que os três estilos mostram. [temClima] falso: sem medida recente, só sol e lua (melhor calar que mentir). */
    class Dados(
        val agora: Long, val temClima: Boolean, val temp: Int, val codigo: Int, val chovendo: Boolean, val eDia: Boolean,
        val max: Int?, val min: Int?,
        /** Todas as horas gravadas (hora a hora), para a frase; [marcas] são as 5 de 2 em 2 que aparecem. */
        val horas: List<WidgetsTempo.Marca>, val marcas: List<WidgetsTempo.Marca>, val eDiaEm: (Long) -> Boolean,
        val nascerPor: Pair<Long, Long>?, val solNaCurva: Pair<Long, Boolean>?,
        val luaFase: String?, val luaFracao: Double, val sul: Boolean,
        val umidade: Int?, val chuvaHoje: Int?, val amanha: Amanha?,
    )

    fun montar(estilo: String, d: Dados): View = when {
        !d.temClima -> semClima(d)
        estilo == Protetor.WIDGET_CARTOES -> cartoes(d)
        estilo == Protetor.WIDGET_SOLTO -> solto(d)
        estilo == Protetor.WIDGET_LINHA -> umaLinha(d)
        estilo == Protetor.WIDGET_GRANDE -> grande(d)
        else -> faixa(d)
    }

    // ------------------------------------------------------------------ 4) uma linha só (17/11/2026)
    /**
     * Tudo numa linha, sem caixa: céu e temperatura de agora com o tempo dito numa frase à esquerda; amanhã e o sol à
     * direita. O mais discreto dos cinco: pouco mais de 40 dp de altura.
     */
    private fun umaLinha(d: Dados): View {
        val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        linha.addView(iconeClima(d.codigo, d.eDia, d.chovendo), LinearLayout.LayoutParams(dp(38), dp(38)))
        linha.addView(texto("${d.temp}°", 32f, m500).apply { letterSpacing = -0.03f; sombrear() }, lp(10))
        linha.addView(texto(fraseDoTempo(d), 16f, m500).apply { linhaUnica(); maxWidth = dp(360); alpha = 0.92f; sombrear() }, lp(14))
        linha.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        d.amanha?.let { a ->
            linha.addView(texto(ctx.getString(R.string.pg_amanha), 13f, m600).apply { isAllCaps = true; letterSpacing = 0.14f; alpha = 0.7f; sombrear() })
            linha.addView(iconeClima(a.codigo, true, false), LinearLayout.LayoutParams(dp(26), dp(26)).apply { marginStart = dp(10) })
            linha.addView(texto("${a.max}° / ${a.min}°", 17f, m600).apply { sombrear() }, lp(8))
        }
        d.nascerPor?.let { (n, p) ->
            linha.addView(View(ctx).apply { setBackgroundColor(0x40FFFFFF) }, LinearLayout.LayoutParams(dp(1), dp(22)).apply { marginStart = dp(18); marginEnd = dp(18) })
            linha.addView(ImageView(ctx).apply { setImageDrawable(IconeLinha(IconeLinha.Tipo.HORIZONTE, cor = amarelo)) }, LinearLayout.LayoutParams(dp(20), dp(20)))
            linha.addView(texto("${hm.format(Date(n))} – ${hm.format(Date(p))}", 16f, m500).apply { sombrear() }, lp(8))
        }
        return linha
    }

    // ------------------------------------------------------------------ 5) temperatura grande (17/11/2026)
    /**
     * Sem caixa, à direita: a temperatura enorme e fina (Manrope 200), como um segundo relógio, e embaixo o céu de agora
     * e a máxima/mínima. Combina com o relógio no canto de cima ou no centro.
     */
    private fun grande(d: Dados): View {
        val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        linha.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        col.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(iconeClima(d.codigo, d.eDia, d.chovendo), LinearLayout.LayoutParams(dp(54), dp(54)))
            addView(texto("${d.temp}°", 84f, Fontes.nunito(ctx, 200)).apply { letterSpacing = -0.05f; sombrear() }, lp(10))
        }, LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
        col.addView(texto(fraseDoTempo(d), 16f, m600).apply { linhaUnica(); maxWidth = dp(420); gravity = Gravity.END; sombrear() },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(2) })
        d.amanha?.let { a ->
            col.addView(texto(ctx.getString(R.string.pw_amanha_frase, a.max, IconesClima.texto(a.codigo).replaceFirstChar { it.lowercase(Locale.getDefault()) }),
                14f, m500).apply { linhaUnica(); maxWidth = dp(420); alpha = 0.85f; gravity = Gravity.END; sombrear() },
                LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END; topMargin = dp(4) })
        }
        linha.addView(col)
        return linha
    }

    // ------------------------------------------------------------------ 1) faixa de vidro
    private fun faixa(d: Dados): View {
        val caixa = Vidro(ctx, vidro, 18f)
        val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPaddingRelative(dp(22), dp(14), dp(22), dp(14)) }
        // Sem a curva das próximas horas, nenhuma zona dava a altura (todas "-1" numa linha "-2"): a faixa virava um traço
        // vazio de 28 dp (visto no teste de telas do protetor v2, 17/11/2026). Aí as zonas medem a si mesmas.
        val alturaZona = if (d.marcas.size >= 2) -1 else -2
        // Agora
        linha.addView(zona(R.string.pw_agora, LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(iconeClima(d.codigo, d.eDia, d.chovendo), LinearLayout.LayoutParams(dp(46), dp(46)))
            addView(texto("${d.temp}°", 46f, m500).apply { letterSpacing = -0.03f }, lp(10))
            addView(duasLinhas(IconesClima.texto(d.codigo, d.chovendo), maxMin(d), 14f, 12f, 150), lp(12))
        }), LinearLayout.LayoutParams(-2, alturaZona))
        linha.addView(divisoria(), LinearLayout.LayoutParams(dp(1), -1).apply { marginStart = dp(22); marginEnd = dp(22) })
        // Próximas horas (curva)
        if (d.marcas.size >= 2) {
            linha.addView(zona(R.string.pw_proximas_horas, CurvaHoras(d.marcas, d.solNaCurva), alturaConteudo = dp(100)),
                LinearLayout.LayoutParams(0, -2, 1f))
        } else linha.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        // Amanhã
        d.amanha?.let { a ->
            linha.addView(divisoria(), LinearLayout.LayoutParams(dp(1), -1).apply { marginStart = dp(22); marginEnd = dp(22) })
            linha.addView(zona(R.string.pg_amanha, LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(iconeClima(a.codigo, true, false), LinearLayout.LayoutParams(dp(36), dp(36)))
                addView(duasLinhas("${a.max}° / ${a.min}°", a.chuva?.let { ctx.getString(R.string.pw_chuva_pct, it) }, 20f, 12f, 190), lp(10))
            }), LinearLayout.LayoutParams(-2, alturaZona))
        }
        caixa.addView(linha, FrameLayout.LayoutParams(-1, -2))
        return caixa
    }

    // ------------------------------------------------------------------ 2) quatro cartões
    private fun cartoes(d: Dados): View {
        val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        // Larguras fixas nos cartões dos lados: com wrap_content o LinearLayout espremia o "Agora" e o 22° quebrava em
        // duas linhas (teste de tela, 15/11). O cartão das horas fica com o resto.
        fun cartao(titulo: Int, conteudo: View, peso: Float = 0f, largura: Int = 0) {
            val c = Vidro(ctx, vidro, 16f)
            c.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPaddingRelative(dp(16), dp(12), dp(16), dp(13))
                addView(rotulo(titulo))
                addView(conteudo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            }, FrameLayout.LayoutParams(-1, -1))
            val lp = if (peso > 0f) LinearLayout.LayoutParams(0, -1, peso) else LinearLayout.LayoutParams(dp(largura), -1)
            if (linha.childCount > 0) lp.marginStart = dp(12)
            linha.addView(c, lp)
        }
        // Agora
        cartao(R.string.pw_agora, LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(iconeClima(d.codigo, d.eDia, d.chovendo), LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(texto("${d.temp}°", 42f, m500).apply { letterSpacing = -0.03f }, lp(10))
            })
            addView(duasLinhas(IconesClima.texto(d.codigo, d.chovendo), maxMin(d), 13f, 12f, 150), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        }, largura = 172)
        // Próximas horas
        if (d.marcas.isNotEmpty()) cartao(R.string.pw_proximas_horas, colunasHoras(d, iconeDp = 30, tempSp = 16f, juntas = false), 1f)
        else linha.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        // Hoje: sol, lua, umidade e chuva (o antigo "resumo do dia")
        val hoje = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        d.nascerPor?.let { (n, p) -> hoje.addView(itemHoje(IconeLinha(IconeLinha.Tipo.HORIZONTE, cor = amarelo), "${hm.format(Date(n))} – ${hm.format(Date(p))}")) }
        d.luaFase?.let { hoje.addView(itemHoje(IconeLinha(IconeLinha.Tipo.LUA, d.luaFracao, sul = d.sul), it)) }
        d.umidade?.let { hoje.addView(itemHoje(IconeLinha(IconeLinha.Tipo.GOTA), ctx.getString(R.string.pw_umidade_pct, it))) }
        d.chuvaHoje?.let { hoje.addView(itemHoje(IconeLinha(IconeLinha.Tipo.CHUVA, cor = azulChuva), ctx.getString(R.string.pw_chuva_pct, it))) }
        if (hoje.childCount > 0) cartao(R.string.pw_hoje, hoje, largura = 222)
        // Amanhã
        d.amanha?.let { a ->
            cartao(R.string.pg_amanha, LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                addView(iconeClima(a.codigo, true, false), LinearLayout.LayoutParams(dp(38), dp(38)))
                addView(duasLinhas("${a.max}° / ${a.min}°", a.chuva?.let { ctx.getString(R.string.pw_chuva_pct, it) }, 19f, 12f, 150),
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
            }, largura = 160)
        }
        return linha
    }

    // ------------------------------------------------------------------ 3) sem caixa
    private fun solto(d: Dados): View {
        val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        linha.addView(iconeClima(d.codigo, d.eDia, d.chovendo), LinearLayout.LayoutParams(dp(56), dp(56)).apply { bottomMargin = dp(8) })
        linha.addView(texto("${d.temp}°", 66f, m400).apply { letterSpacing = -0.04f; includeFontPadding = false; sombrear() }, lp(14))
        val frase = fraseDoTempo(d)
        val amanha = d.amanha?.let { a ->
            ctx.getString(R.string.pw_amanha_frase, a.max, IconesClima.texto(a.codigo).replaceFirstChar { it.lowercase(Locale.getDefault()) })
        }
        linha.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(texto(frase, 17f, m600).apply { linhaUnica(); maxWidth = dp(400); sombrear() }, LinearLayout.LayoutParams(-2, -2))
            if (amanha != null) addView(texto(amanha, 14f, m500).apply { linhaUnica(); maxWidth = dp(400); alpha = 0.85f; sombrear() },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(16); bottomMargin = dp(10) })
        linha.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        if (d.marcas.isNotEmpty()) linha.addView(colunasHoras(d, iconeDp = 30, tempSp = 17f, juntas = true), LinearLayout.LayoutParams(-2, -2))
        return linha
    }

    /** "Chuva fraca até umas 3h", "Nublado, chuva lá pelas 18h", "Chuva o resto da noite" ou só o céu de agora. */
    private fun fraseDoTempo(d: Dados): String {
        val ceu = IconesClima.texto(d.codigo, d.chovendo)
        return when (val f = WidgetsTempo.frase(d.chovendo, d.horas, d.agora)) {
            is WidgetsTempo.Frase.ParaPorVolta -> ctx.getString(R.string.pw_ate_umas, ceu, hh.format(Date(f.ms)))
            is WidgetsTempo.Frase.ChegaPorVolta -> ctx.getString(R.string.pw_chuva_pelas, ceu, hh.format(Date(f.ms)))
            WidgetsTempo.Frase.ChuvaSegue -> ctx.getString(R.string.pw_chuva_segue, ceu)
            null -> maxMin(d)?.let { "$ceu · $it" } ?: ceu
        }
    }

    // ------------------------------------------------------------------ sem medida do clima: só sol e lua
    private fun semClima(d: Dados): View {
        val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        linha.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        val col = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        d.nascerPor?.let { (n, p) -> col.addView(itemHoje(IconeLinha(IconeLinha.Tipo.HORIZONTE), "${hm.format(Date(n))} – ${hm.format(Date(p))}")) }
        d.luaFase?.let { col.addView(itemHoje(IconeLinha(IconeLinha.Tipo.LUA, d.luaFracao, sul = d.sul), it)) }
        linha.addView(col)
        return linha
    }

    // ------------------------------------------------------------------ peças
    /** As marcas lado a lado: hora, céu, temperatura e chance de chuva (some abaixo de 10%, que é ruído). */
    private fun colunasHoras(d: Dados, iconeDp: Int, tempSp: Float, juntas: Boolean) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM
        d.marcas.forEachIndexed { i, m ->
            if (i > 0) addView(View(ctx), if (juntas) LinearLayout.LayoutParams(dp(18), 1) else LinearLayout.LayoutParams(0, 1, 1f))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; minimumWidth = dp(44)
                addView(texto(hh.format(Date(m.ms)), 12f, m500).apply { alpha = 0.8f; sombrear() })
                addView(iconeClima(m.codigo, d.eDiaEm(m.ms), false), LinearLayout.LayoutParams(dp(iconeDp), dp(iconeDp)).apply { topMargin = dp(4) })
                addView(texto("${m.temp}°", tempSp, m600).apply { sombrear() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                addView(texto(if (m.chuva >= 10) "${m.chuva}%" else " ", 12f, m600).apply { setTextColor(azulChuva); sombrear() },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            })
        }
    }

    /** Uma parte da faixa: o título sempre no alto (alinhado com os das outras partes) e o conteúdo no meio do que sobra. */
    private fun zona(titulo: Int, conteudo: View, alturaConteudo: Int = -2) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(rotulo(titulo))
        addView(FrameLayout(ctx).apply {
            addView(conteudo, FrameLayout.LayoutParams(-1, alturaConteudo, Gravity.CENTER_VERTICAL))
        }, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(8) })
    }

    /** Título pequeno em maiúsculas espaçadas ("AGORA"): o mínimo de 12 sp vale para ele também. */
    private fun rotulo(t: Int) = texto(ctx.getString(t), 12f, m600).apply { isAllCaps = true; letterSpacing = 0.14f; alpha = 0.7f; linhaUnica() }

    private fun divisoria() = View(ctx).apply { setBackgroundColor(0x24FFFFFF) }

    private fun itemHoje(icone: Drawable, t: String) = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPaddingRelative(0, dp(3), 0, dp(3))
        addView(ImageView(ctx).apply { setImageDrawable(icone) }, LinearLayout.LayoutParams(dp(18), dp(18)))
        addView(texto(t, 13f, m500).apply { linhaUnica(); maxWidth = dp(164); sombrear() }, lp(8))
    }

    private fun duasLinhas(cima: String, baixo: String?, spCima: Float, spBaixo: Float, larguraMax: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        // LayoutParams explícitos: o padrão do LinearLayout vertical é MATCH_PARENT, e a linha de cima ficava da largura da
        // de baixo, cortada quando era mais comprida (teste de tela, 15/11).
        addView(texto(cima, spCima, m600).apply { linhaUnica(); maxWidth = dp(larguraMax); sombrear() }, LinearLayout.LayoutParams(-2, -2))
        if (baixo != null) addView(texto(baixo, spBaixo, m400).apply { linhaUnica(); maxWidth = dp(larguraMax); alpha = 0.85f; sombrear() },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
    }

    private fun maxMin(d: Dados): String? =
        if (d.max != null && d.min != null) ctx.getString(R.string.pg_max_min, d.max, d.min) else null

    private fun iconeClima(codigo: Int, dia: Boolean, chovendo: Boolean): ImageView =
        IconesClima.imagem(ctx, IconesClima.chave(codigo, dia, chovendo))

    private fun texto(t: String, sp: Float, face: Typeface) = TextView(ctx).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = face; setTextColor(branco); includeFontPadding = false
    }

    private fun TextView.linhaUnica() { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private fun TextView.sombrear() = setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), 0xB3000000.toInt())
    private fun lp(margem: Int) = LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(margem) }
    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()
    private fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, ctx.resources.displayMetrics)

    /**
     * A curva das próximas horas (estilo FAIXA): hora em cima, temperatura sobre a linha, chance de chuva em barrinha azul
     * com o número embaixo, e o nascer (ou pôr) do sol riscado em amarelo na hora certa. Desenhada à mão num só View:
     * uma linha com 5 TextViews soltas não dá para ligar com a curva.
     */
    private inner class CurvaHoras(private val marcas: List<WidgetsTempo.Marca>, private val sol: Pair<Long, Boolean>?) : View(ctx) {
        private val pHora = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = m500; textSize = sp(12f); color = 0xCCFFFFFF.toInt(); textAlign = Paint.Align.CENTER }
        private val pTemp = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = m600; textSize = sp(15f); color = branco; textAlign = Paint.Align.CENTER }
        private val pChuva = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = m600; textSize = sp(12f); color = azulChuva; textAlign = Paint.Align.CENTER }
        private val pSol = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = m600; textSize = sp(12f); color = amarelo; textAlign = Paint.Align.LEFT }
        private val pLinha = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2).toFloat() * 0.8f; color = 0x99FFFFFF.toInt(); strokeJoin = Paint.Join.ROUND }
        private val pBarra = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC6EA0FF.toInt() }
        private val pRisco = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = dp(1).toFloat(); color = 0x99FFD66B.toInt(); pathEffect = DashPathEffect(floatArrayOf(dp(2).toFloat(), dp(3).toFloat()), 0f)
        }
        private val caminho = Path()
        private val r = RectF()

        override fun onDraw(c: Canvas) {
            if (marcas.size < 2) return
            val w = width.toFloat(); val h = height.toFloat()
            val margem = dp(20).toFloat()
            val xs = marcas.indices.map { margem + it * (w - 2 * margem) / (marcas.size - 1) }
            val alt = WidgetsTempo.alturas(marcas.map { it.temp })
            // Faixas na vertical: hora | temperatura + curva | barra de chuva | número da chuva.
            val yHora = sp(12f)
            val topoCurva = yHora + dp(8) + sp(15f) + dp(6)
            val fundoCurva = topoCurva + dp(18)
            val yChuvaTxt = h - dp(1)
            val fundoBarra = yChuvaTxt - sp(12f) - dp(4)
            val maxBarra = dp(18).toFloat()
            val ys = alt.map { fundoCurva - it * (fundoCurva - topoCurva) }
            caminho.reset()
            xs.forEachIndexed { i, x -> if (i == 0) caminho.moveTo(x, ys[i]) else caminho.lineTo(x, ys[i]) }
            c.drawPath(caminho, pLinha)
            marcas.forEachIndexed { i, m ->
                c.drawText(hh.format(Date(m.ms)), xs[i], yHora, pHora)
                c.drawText("${m.temp}°", xs[i], ys[i] - dp(6), pTemp)
                if (m.chuva >= 10) {
                    val bh = maxBarra * (m.chuva / 100f).coerceIn(0.12f, 1f)
                    r.set(xs[i] - dp(3), fundoBarra - bh, xs[i] + dp(3), fundoBarra)
                    c.drawRoundRect(r, dp(3).toFloat(), dp(3).toFloat(), pBarra)
                    c.drawText("${m.chuva}%", xs[i], yChuvaTxt, pChuva)
                }
            }
            // Nascer/pôr do sol: risco tracejado e a hora ao lado (à esquerda do risco quando ele está no fim da curva).
            val (ms, nasce) = sol ?: return
            val pos = WidgetsTempo.posicaoNaCurva(marcas, ms) ?: return
            val x = xs.first() + pos * (xs.last() - xs.first())
            val rotulo = ctx.getString(if (nasce) R.string.pw_nasce else R.string.pw_poe, hm.format(Date(ms)))
            val largura = pSol.measureText(rotulo)
            // O rótulo não pode cobrir uma barra de chuva (foto da Sala, 15/11): WidgetsTempo escolhe o lado ou o vão.
            val barras = marcas.indices.filter { marcas[it].chuva >= 10 }.map { xs[it] }
            val tx = WidgetsTempo.xRotuloSol(x, largura, barras, dp(3) + dp(4).toFloat(), dp(6).toFloat(), w)
            val yTexto = fundoBarra - dp(2)
            // Se o texto ficou em cima do risco (centralizado no vão), o risco para logo acima dele.
            val fimRisco = if (x >= tx - dp(2) && x <= tx + largura + dp(2)) yTexto - pSol.textSize - dp(2) else fundoBarra
            c.drawLine(x, topoCurva - dp(4), x, fimRisco, pRisco)
            c.drawText(rotulo, tx, yTexto, pSol)
        }
    }
}
