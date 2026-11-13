package br.paulo.tvlauncher

import android.content.Context
import android.graphics.Matrix
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.ForegroundColorSpan
import android.text.style.TypefaceSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService

/**
 * O protetor "Galeria", direção B aprovada pelo dono em 10/11/2026 (modelos em protetor-versoes/B2 e B3):
 * - fundo: a pintura do dia do The Met em tela cheia ([FundoGaleria]), com movimento lento de câmera (Ken Burns);
 * - em cima, à esquerda: saudação, a hora grande em Manrope (hora cheia, minuto fino), a data e uma linha fina que mostra
 *   quanto do dia claro já passou (do nascer ao pôr do sol; some à noite);
 * - em cima, à direita: as notificações da TV em cartões de vidro fosco ([NotificacoesTv]), mais o próximo despertador;
 * - embaixo: uma linha solta sobre a pintura (12/11/2026): o tempo de agora, as próximas horas de 2 em 2 e, à direita,
 *   sol, lua e amanhã; acima dela, a obra do dia.
 * O vidro é a própria pintura desfocada atrás de cada painel ([Vidro]).
 *
 * Quem cria e destrói é o [ProtetorTela]. [montar] monta a tela; [atualizar] relê clima, avisos e a obra do dia.
 * Agenda, desfoque e decodificação da pintura saem da thread da tela pelo [carregador].
 */
class ProtetorGaleria(
    private val ctx: Context, private val carregador: ExecutorService, private val comPintura: Boolean,
    /** Tema Escuro (dono, 11/11/2026): o MESMO protetor, só que com o wallpaper escuro da HOME no lugar da pintura. */
    private val wallpaperEscuro: Boolean = false,
) {
    private val m200: Typeface = Fontes.manrope(ctx, 200)
    private val m400: Typeface = Fontes.manrope(ctx, 400)
    private val m500: Typeface = Fontes.manrope(ctx, 500)
    private val m600: Typeface = Fontes.manrope(ctx, 600)
    private val fraunces: Typeface = Fontes.fraunces(ctx)
    private val branco = 0xFFFFFFFF.toInt()
    private val sombra = 0x59000000

    /** Camada que se move (anti-burn-in): o ProtetorTela desloca ela em "oito". */
    lateinit var camadaInfo: FrameLayout; private set
    private var fundo: FundoGaleria? = null
    private val vidro = FonteVidro()
    private lateinit var raiz: FrameLayout
    private lateinit var saudacao: TextView
    private lateinit var hora: TextView
    private lateinit var data: TextView
    private lateinit var agenda: LinearLayout
    private lateinit var progresso: FrameLayout
    private lateinit var progressoCheio: View
    private lateinit var colunaAvisos: LinearLayout
    private lateinit var barra: LinearLayout
    private lateinit var etiquetaObra: LinearLayout

    // Avisos que vêm da thread de fundo (despertador e agenda).
    private var despertador: Long? = null
    private var eventos: List<Pair<String, String>> = emptyList()
    /** Ideias de 12/11/2026: cartões do mural da família e da retrospectiva da semana (título, linha, linha 2). */
    private var extras12: List<Triple<String, String, String>> = emptyList()
    private val icones = HashMap<String, Drawable?>()

    fun montar(raiz: FrameLayout) {
        this.raiz = raiz
        fundo = if (comPintura) FundoGaleria(ctx, { t -> if (!carregador.isShutdown) carregador.execute(t) }, comEtiqueta = false, veus = false).also { f ->
            raiz.addView(f, FrameLayout.LayoutParams(-1, -1))
            vidro.matrizNaTela = { matrizPintura(f) }
            // Pintura nova -> gera o vidro desfocado dela fora da thread da tela e redesenha os painéis.
            f.aoTrocarImagem = { bmp -> if (!carregador.isShutdown) carregador.execute { vidro.preparar(bmp) { raiz.post { vidro.redesenhar() } } } }
        } else null
        if (!comPintura && wallpaperEscuro) montarWallpaperEscuro(raiz)
        // Véu leve só no canto da hora: relógio branco continua legível sobre um céu claro de pintura.
        // Radial a partir do canto: o degradê linear numa caixa deixava uma emenda visível na borda dela (foto de 10/11).
        raiz.addView(View(ctx).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT; setGradientCenter(0f, 0f); gradientRadius = dp(620).toFloat()
                colors = intArrayOf(0x66000000, 0x26000000, 0x00000000)
            }
        }, FrameLayout.LayoutParams(-1, -1))

        camadaInfo = FrameLayout(ctx)
        montarTopo()
        colunaAvisos = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        camadaInfo.addView(colunaAvisos, FrameLayout.LayoutParams(dp(250), -2, Gravity.TOP or Gravity.END).apply {
            topMargin = dp(32); rightMargin = dp(32)
        })
        // Sem caixa (12/11/2026, dono: "a B na proposta da C"): a linha de baixo fica solta sobre a pintura, legível por
        // um véu escuro que sobe do pé da tela (fica na raiz, parado: não anda com o anti-burn-in) e uma sombra nas letras.
        raiz.addView(View(ctx).apply {
            background = GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP, intArrayOf(0x9E000000.toInt(), 0x4D000000, 0x00000000))
        }, raiz.childCount - 1, FrameLayout.LayoutParams(-1, dp(190), Gravity.BOTTOM))
        barra = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM; clipChildren = false }
        camadaInfo.addView(barra, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
            leftMargin = dp(Estilo.MARGEM_LATERAL); rightMargin = dp(Estilo.MARGEM_LATERAL); bottomMargin = dp(Estilo.MARGEM_VERTICAL)
        })
        etiquetaObra = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        camadaInfo.addView(etiquetaObra, FrameLayout.LayoutParams(dp(420), -2, Gravity.TOP or Gravity.END).apply {
            rightMargin = dp(Estilo.MARGEM_LATERAL)
        })
        raiz.addView(camadaInfo, FrameLayout.LayoutParams(-1, -1))

        NotificacoesTv.ouvir(aoMudarNotificacoes)
        garantirOuvinte()
        atualizar()
        tique.run()
        // Entrada suave: as informações aparecem em 1,5 s depois da pintura.
        camadaInfo.alpha = 0f
        camadaInfo.animate().alpha(1f).setStartDelay(600).setDuration(1500).withLayer().start()
        fundo?.let { kenBurns(it) }
    }

    /**
     * O wallpaper escuro da HOME (desenharFundoEscuro, em Fundos.kt) desenhado numa imagem e posto onde a pintura ficaria.
     * O vidro dos painéis desfoca ESSA imagem, como desfocaria a pintura: o resto do protetor fica igual ao da Galeria.
     * Imagem em meia resolução (960x540, ~2 MB): é um degradê suave, ampliar não perde nada.
     */
    private fun montarWallpaperEscuro(raiz: FrameLayout) {
        val bmp = android.graphics.Bitmap.createBitmap(960, 540, android.graphics.Bitmap.Config.ARGB_8888)
        desenharFundoEscuro(android.graphics.Canvas(bmp), 960f, 540f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG))
        val img = android.widget.ImageView(ctx).apply { scaleType = android.widget.ImageView.ScaleType.FIT_XY; setImageBitmap(bmp) }
        raiz.addView(img, FrameLayout.LayoutParams(-1, -1))
        // Imagem -> tela: só a ampliação para o tamanho da raiz (o wallpaper fica parado, sem Ken Burns).
        vidro.matrizNaTela = {
            if (img.width == 0) null else Matrix().apply {
                setScale(img.width / 960f, img.height / 540f)
                val loc = IntArray(2); img.getLocationOnScreen(loc); postTranslate(loc[0].toFloat(), loc[1].toFloat())
            }
        }
        if (!carregador.isShutdown) carregador.execute { vidro.preparar(bmp) { raiz.post { vidro.redesenhar() } } }
    }

    // ------------------------------------------------------------------ topo: saudação, hora, data, linha do dia
    private fun montarTopo() {
        val topo = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        saudacao = texto("", 9f, m500).apply { letterSpacing = 0.24f; isAllCaps = true; alpha = 0.85f; setPadding(dp(6), 0, 0, 0) }
        topo.addView(saudacao, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(2) })
        hora = texto("", 125f, m200).apply { letterSpacing = -0.045f; setShadowLayer(dp(15).toFloat(), 0f, dp(1).toFloat(), sombra) }
        topo.addView(hora, LinearLayout.LayoutParams(-2, -2))
        data = texto("", 15f, m500).apply { setPadding(dp(6), 0, 0, 0) }
        topo.addView(data, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6) })
        // Compromissos de hoje logo abaixo da data (dono, 10/11: "o calendário poderia ficar junto da data").
        agenda = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(6), 0, 0, 0) }
        topo.addView(agenda, LinearLayout.LayoutParams(-2, -2))
        progresso = FrameLayout(ctx).apply { background = arredondado(0x40FFFFFF, 2f); clipToOutline = true }
        progressoCheio = View(ctx).apply { background = arredondado(branco, 2f) }
        progresso.addView(progressoCheio, FrameLayout.LayoutParams(0, -1))
        topo.addView(progresso, LinearLayout.LayoutParams(dp(180), dp(2)).apply { topMargin = dp(11); leftMargin = dp(6) })
        camadaInfo.addView(topo, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
            leftMargin = dp(36); topMargin = dp(26)
        })
    }

    /** A cada virada de minuto: hora, saudação, data e a linha do dia. */
    private val tique = object : Runnable {
        override fun run() {
            val agora = System.currentTimeMillis()
            val c = Calendar.getInstance().apply { timeInMillis = agora }
            val h = SimpleDateFormat("HH", Locale.getDefault()).format(Date(agora))
            val m = SimpleDateFormat("mm", Locale.getDefault()).format(Date(agora))
            // Hora cheia (Manrope 600) e minuto fino (200), com os dois-pontos apagados: o contraste é o desenho.
            hora.text = SpannableStringBuilder().apply {
                append(h); if (android.os.Build.VERSION.SDK_INT >= 28) setSpan(TypefaceSpan(m600), 0, length, 0)
                // Espaço finíssimo (U+200A) só antes: com as letras apertadas, o ":" encostava no "6" (e o espaço fino
                // U+2009 dos dois lados ficou largo demais na foto de 10/11).
                val i = length; append("\u200A:"); setSpan(ForegroundColorSpan(0xB3FFFFFF.toInt()), i, length, 0)
                append(m)
            }
            saudacao.text = ctx.getString(when (c.get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> R.string.pg_bom_dia
                in 12..17 -> R.string.pg_boa_tarde
                else -> R.string.pg_boa_noite
            })
            data.text = SimpleDateFormat(ctx.getString(R.string.fmt_data_longa), Locale.getDefault()).format(Date(agora))
                .replaceFirstChar { it.titlecase(Locale.getDefault()) }
            val (lat, lon) = lugar()
            val frac = Astronomia.nascerEPor(agora, lat, lon)?.let { (nasce, poe) ->
                if (agora in nasce..poe) (agora - nasce).toFloat() / (poe - nasce) else null
            }
            progresso.visibility = if (frac == null) View.GONE else View.VISIBLE
            if (frac != null) progressoCheio.layoutParams = (progressoCheio.layoutParams as FrameLayout.LayoutParams).apply { width = (dp(180) * frac).toInt() }
            hora.postDelayed(this, 60_000 - agora % 60_000 + 50)
        }
    }

    // ------------------------------------------------------------------ atualizar tudo (a cada 15 min, pelo ProtetorTela)
    fun atualizar() {
        val agora = System.currentTimeMillis()
        val (lat, lon) = lugar()
        val clima = ctx.getSharedPreferences("clima", Context.MODE_PRIVATE)
        fundo?.atualizar(EstadoCeu(agora, lat, lon, clima.getInt("codigo", 0), clima.getBoolean("chovendo", false)))
        montarBarra(agora, lat, lon)
        montarAvisos()
        carregarAvisos(agora)
    }

    private fun lugar(): Pair<Double, Double> {
        val clima = ctx.getSharedPreferences("clima", Context.MODE_PRIVATE)
        return clima.getFloat("lat", Config.LATITUDE.toFloat()).toDouble() to clima.getFloat("lon", Config.LONGITUDE.toFloat()).toDouble()
    }

    // ------------------------------------------------------------------ linha de baixo (sem caixa)
    private fun montarBarra(agora: Long, lat: Double, lon: Double) {
        barra.removeAllViews()
        val clima = ctx.getSharedPreferences("clima", Context.MODE_PRIVATE)
        // Só com medida de até 3 h, como no protetor clássico: melhor calar que mostrar um número velho.
        val temClima = clima.contains("temp") && agora - clima.getLong("em", 0) < 3 * 60 * 60_000L
        val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
        // 1) O tempo de agora, o maior da linha.
        if (temClima) {
            val codigo = clima.getInt("codigo", 0)
            val chovendo = clima.getBoolean("chovendo", false)
            val seg = segmento()
            // Dia/noite recalculado agora, não o gravado pela HOME (que pode ter até 3 h; auditoria 10/11).
            val eDia = Astronomia.eDia(agora, lat, lon, clima.getBoolean("eDia", true))
            seg.addView(IconesClima.imagem(ctx, IconesClima.chave(codigo, eDia, chovendo)), LinearLayout.LayoutParams(dp(34), dp(34)))
            seg.addView(texto("${clima.getInt("temp", 0)}°", 38f, m200).apply { letterSpacing = -0.03f; sombrear() }, lpSeg(10))
            val maxMin = if (clima.contains("max")) "máx ${clima.getInt("max", 0)}° · mín ${clima.getInt("min", 0)}°" else null
            seg.addView(par(IconesClima.texto(codigo, chovendo), maxMin, 170, 13f, 11f), lpSeg(12))
            barra.addView(seg)
        }
        // 2) As próximas horas, de 2 em 2 (5 marcas): hora, céu, temperatura e chance de chuva. Sem as horas gravadas
        //    (HOME antiga, ou a Open-Meteo não mandou), cai na chance de chuva do dia, como era.
        //    A pessoa pode trocar pelo "resumo do dia" nas Configurações (Protetor.barra).
        val resumo = Protetor.barra(ctx) == Protetor.BARRA_RESUMO
        val horas = if (temClima && !resumo) proximasHoras(clima.getString("horas", "").orEmpty(), agora, lat, lon) else emptyList()
        if (horas.isNotEmpty()) {
            // As marcas se espalham pelo vão entre o "agora" e a coluna da direita (vãos iguais de peso 1 entre elas).
            val grupo = segmento().apply { gravity = Gravity.BOTTOM }
            horas.forEachIndexed { i, v ->
                if (i > 0) grupo.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                grupo.addView(v, LinearLayout.LayoutParams(-2, -2))
            }
            barra.addView(grupo, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(44); rightMargin = dp(44) })
        } else if (temClima) {
            // Resumo do dia (ou sem as horas gravadas): sol, lua, umidade e chuva de hoje espalhados; amanhã fica à direita.
            val grupo = segmento()
            val itens = mutableListOf<View>()
            Astronomia.nascerEPor(agora, lat, lon)?.let { (nasce, poe) ->
                itens += segmentoSimples(icone(IconeLinha.Tipo.HORIZONTE), hm.format(Date(nasce)), "pôr ${hm.format(Date(poe))}")
            }
            val luaR = Lua.resumoCurto(agora, lat).split("  ·  ")
            luaR[0].substringAfter("  ").trim().takeIf { it.isNotEmpty() }?.let { f ->
                itens += segmentoSimples(IconeLinha(IconeLinha.Tipo.LUA, Lua.idade(agora) / 29.530588853, sul = lat < 0), f, luaR.getOrNull(1))
            }
            clima.getInt("umidade", -1).takeIf { it >= 0 }?.let { itens += segmentoSimples(icone(IconeLinha.Tipo.GOTA), "$it%", ctx.getString(R.string.pg_umidade)) }
            clima.getInt("chuva", -1).takeIf { it >= 0 }?.let { itens += segmentoSimples(icone(IconeLinha.Tipo.CHUVA), "$it%", ctx.getString(R.string.pg_chuva)) }
            itens.forEachIndexed { i, v ->
                if (i > 0) grupo.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                grupo.addView(v)
            }
            barra.addView(grupo, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(48); rightMargin = dp(36) })
        } else barra.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        // 3) À direita, três linhas menores: sol, lua e amanhã.
        val direita = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.END }
        if (!resumo || !temClima) Astronomia.nascerEPor(agora, lat, lon)?.let { (nasce, poe) ->
            direita.addView(linhaDireita(icone(IconeLinha.Tipo.HORIZONTE), "${hm.format(Date(nasce))} – ${hm.format(Date(poe))}"))
        }
        val lua = Lua.resumoCurto(agora, lat).split("  ·  ")
        val fase = lua[0].substringAfter("  ").trim()
        if (fase.isNotEmpty() && (!resumo || !temClima)) direita.addView(linhaDireita(
            IconeLinha(IconeLinha.Tipo.LUA, Lua.idade(agora) / 29.530588853, sul = lat < 0), fase))
        if (temClima && clima.contains("amanha_max")) {
            val chuvaAmanha = clima.getInt("amanha_chuva", -1).takeIf { it >= 0 }?.let { " · $it%" } ?: ""
            direita.addView(linhaDireita(null, "${ctx.getString(R.string.pg_amanha)}  ${clima.getInt("amanha_max", 0)}° / ${clima.getInt("amanha_min", 0)}°$chuvaAmanha",
                IconesClima.imagem(ctx, IconesClima.chave(clima.getInt("amanha_codigo", 0), true, false))))
        }
        barra.addView(direita)
        // A obra do dia: etiqueta de museu logo acima da linha, à direita.
        etiquetaObra.removeAllViews()
        fundo?.legenda(agora)?.let { (titulo, autor) ->
            etiquetaObra.addView(texto(titulo, 11.5f, fraunces).apply { linhaUnica(); gravity = Gravity.END; setShadowLayer(dp(10).toFloat(), 0f, 0f, 0x99000000.toInt()) },
                LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.END })
            etiquetaObra.addView(texto(autor, 8.5f, m400).apply { linhaUnica(); alpha = 0.85f; gravity = Gravity.END; setShadowLayer(dp(10).toFloat(), 0f, 0f, 0x99000000.toInt()) },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2); gravity = Gravity.END })
        }
        // A etiqueta fica logo acima da linha de baixo, seja qual for a altura dela.
        barra.post {
            (etiquetaObra.layoutParams as? FrameLayout.LayoutParams)?.let { lp ->
                lp.gravity = Gravity.BOTTOM or Gravity.END
                lp.bottomMargin = dp(Estilo.MARGEM_VERTICAL) + barra.height + dp(14)
                etiquetaObra.layoutParams = lp
            }
        }
    }

    /**
     * As marcas das próximas horas: a partir da próxima hora cheia, de 2 em 2, até 5. Lê "hora|temp|código|chuva;…" que a
     * HOME grava (ClimaFontes.proximasHoras). A hora vem no fuso do lugar, que é o da TV.
     */
    private fun proximasHoras(texto: String, agora: Long, lat: Double, lon: Double): List<View> {
        if (texto.isBlank()) return emptyList()
        val leitor = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US)
        val marcas = texto.split(';').mapNotNull { linha ->
            val p = linha.split('|')
            if (p.size < 4) return@mapNotNull null
            val ms = try { leitor.parse(p[0])?.time } catch (e: Exception) { null } ?: return@mapNotNull null
            if (ms <= agora) null else Triple(ms, p[1].toIntOrNull() ?: return@mapNotNull null, p[2].toIntOrNull() ?: 0) to (p[3].toIntOrNull() ?: -1)
        }.filterIndexed { i, _ -> i % 2 == 0 }.take(8)
        val hh = SimpleDateFormat("H'h'", Locale.getDefault())
        return marcas.map { (t, chuva) ->
            val (ms, temp, cod) = t
            LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
                addView(texto(hh.format(Date(ms)), 11f, m500).apply { alpha = 0.75f; sombrear() })
                val eDia = Astronomia.eDia(ms, lat, lon, true)
                addView(IconesClima.imagem(ctx, IconesClima.chave(cod, eDia, false)), LinearLayout.LayoutParams(dp(28), dp(28)).apply { topMargin = dp(4) })
                addView(texto("$temp°", 15f, m600).apply { sombrear() }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
                // Chance de chuva em azul claro (a cor do gráfico da Previsão); some abaixo de 10%, que é ruído.
                addView(texto(if (chuva >= 10) "$chuva%" else " ", 11f, m500).apply { setTextColor(0xFF9CC2FF.toInt()); sombrear() },
                    LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
            }
        }
    }

    private fun linhaDireita(icone: Drawable?, t: String, ilustracao: View? = null) = segmento().apply {
        gravity = Gravity.CENTER_VERTICAL or Gravity.END
        if (icone != null) addView(ImageView(ctx).apply { setImageDrawable(icone) }, LinearLayout.LayoutParams(dp(16), dp(16)))
        ilustracao?.let { addView(it, LinearLayout.LayoutParams(dp(18), dp(18))) }
        addView(texto(t, 11f, m500).apply { linhaUnica(); alpha = 0.9f; sombrear() }, lpSeg(8))
        setPadding(0, dp(3), 0, dp(3))
    }

    /** Sombra suave nas letras: a linha não tem caixa, então precisa se destacar da pintura sozinha. */
    private fun TextView.sombrear() = setShadowLayer(dp(8).toFloat(), 0f, dp(1).toFloat(), 0xB3000000.toInt())

    private fun segmento() = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun lpSeg(margem: Int = 8) = LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(margem) }

    private fun icone(t: IconeLinha.Tipo) = IconeLinha(t)

    private fun segmentoSimples(icone: Drawable, cima: String, baixo: String?, larguraMax: Int = 120) = segmento().apply {
        addView(ImageView(ctx).apply { setImageDrawable(icone) }, LinearLayout.LayoutParams(dp(20), dp(20)))
        addView(par(cima, baixo, larguraMax), lpSeg())
    }

    /** Duas linhas: a de cima forte, a de baixo apagada (como "Nublado" / "máx 29° · mín 19°"). */
    private fun par(cima: String, baixo: String?, larguraMax: Int, spCima: Float = 12f, spBaixo: Float = 10.5f) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        addView(texto(cima, spCima, m600).apply { linhaUnica(); maxWidth = dp(larguraMax); sombrear() }, LinearLayout.LayoutParams(-2, -2))
        if (baixo != null) addView(texto(baixo, spBaixo, m400).apply { linhaUnica(); maxWidth = dp(larguraMax); alpha = 0.8f; sombrear() },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
    }

    // ------------------------------------------------------------------ notificações (canto de cima, à direita)
    private val aoMudarNotificacoes: () -> Unit = { if (::colunaAvisos.isInitialized) montarAvisos() }
    private val tirarAvisoVencido = Runnable { if (::colunaAvisos.isInitialized) montarAvisos() }

    /** Liga o ouvinte de notificações pelo shell próprio se ainda não estiver ligado (só o shell consegue). */
    private fun garantirOuvinte() {
        if (OuvinteNotificacoes.conectado || carregador.isShutdown) return
        carregador.execute { try { TocandoAgoraTv.ajustar(ctx, true) } catch (e: Exception) { android.util.Log.w(TAG, "ouvinte: $e") } }
    }

    private class Cartao(val icone: Drawable?, val emoji: String?, val app: String, val tempo: String, val titulo: String, val texto: String)

    private fun montarAvisos() {
        val agora = System.currentTimeMillis()
        val cartoes = mutableListOf<Cartao>()
        // Último aviso da TV: recado, campainha e o que pede atenção ficam 6 h; chuva 2 h; confirmação (ex.: "O Claude
        // olhou a tela") 2 min (AvisoRegras, 12/11/2026). Antes tudo ficava 6 h. Ao vencer, o cartão sai sozinho.
        val ultimo = ctx.getSharedPreferences(AvisoTv.PREFS_ULTIMO, Context.MODE_PRIVATE)
        val emRecado = ultimo.getLong("em", 0)
        val restante = AvisoRegras.restanteMs(emRecado, ultimo.getString("fica", null), agora)
        colunaAvisos.removeCallbacks(tirarAvisoVencido)
        if (restante > 0) {
            colunaAvisos.postDelayed(tirarAvisoVencido, restante + 500)
            val linhas = ultimo.getString("texto", "").orEmpty().split('\n')
            cartoes.add(Cartao(IconeLinha(IconeLinha.Tipo.RECADO), null, ultimo.getString("titulo", null) ?: ctx.getString(R.string.pg_recado),
                NotificacoesTv.haQuanto(emRecado, agora, ctx.getString(R.string.pg_ontem), ctx.getString(R.string.pg_agora)),
                linhas.firstOrNull().orEmpty(), linhas.drop(1).joinToString(" ")))
        }
        // Retrospectiva e mural (12/11/2026): depois do recado, antes do despertador e das notificações.
        extras12.forEach { (titulo, linha1, linha2) ->
            cartoes.add(Cartao(IconeLinha(IconeLinha.Tipo.RECADO), null, titulo, "", linha1, linha2))
        }
        despertador?.takeIf { it > agora }?.let {
            cartoes.add(Cartao(IconeLinha(IconeLinha.Tipo.DESPERTADOR), null, ctx.getString(R.string.pg_app_despertador), "", quandoTexto(it, agora), ""))
        }
        NotificacoesTv.atuais.forEach { a ->
            val ic = icones.getOrPut(a.pacote) { try { ctx.packageManager.getApplicationIcon(a.pacote) } catch (e: Exception) { null } }
            cartoes.add(Cartao(ic, if (ic == null) "🔔" else null, a.app,
                NotificacoesTv.haQuanto(a.quando, agora, ctx.getString(R.string.pg_ontem), ctx.getString(R.string.pg_agora)), a.titulo, a.texto))
        }
        colunaAvisos.removeAllViews()
        cartoes.take(3).forEach { colunaAvisos.addView(cartao(it), LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(7) }) }
        if (cartoes.size > 3) colunaAvisos.addView(texto(ctx.getString(R.string.pg_mais_notif, cartoes.size - 3), 8f, m500).apply {
            alpha = 0.8f; gravity = Gravity.END; setPadding(0, 0, dp(4), 0)
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun cartao(c: Cartao): View = Vidro(ctx, vidro, 13f).apply {
        val linha = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(dp(12), dp(10), dp(12), dp(10)) }
        val caixaIcone = FrameLayout(ctx).apply { background = arredondado(0x2EFFFFFF, 7f) }
        if (c.icone != null) caixaIcone.addView(ImageView(ctx).apply { setImageDrawable(c.icone) }, FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
        else caixaIcone.addView(texto(c.emoji ?: "", 14f, m400).apply { gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1))
        linha.addView(caixaIcone, LinearLayout.LayoutParams(dp(26), dp(26)))
        val tx = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cabeca = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        cabeca.addView(texto(c.app, 7.5f, m600).apply { letterSpacing = 0.12f; isAllCaps = true; alpha = 0.75f; linhaUnica() },
            LinearLayout.LayoutParams(0, -2, 1f))
        if (c.tempo.isNotEmpty()) cabeca.addView(texto(c.tempo, 7.5f, m600).apply { alpha = 0.75f }, LinearLayout.LayoutParams(-2, -2))
        tx.addView(cabeca, LinearLayout.LayoutParams(-1, -2))
        tx.addView(texto(c.titulo, 11f, m600).apply { linhaUnica() }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(2) })
        if (c.texto.isNotEmpty()) tx.addView(texto(c.texto, 9.5f, m400).apply { linhaUnica(); alpha = 0.85f },
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(1) })
        linha.addView(tx, LinearLayout.LayoutParams(0, -2, 1f).apply { leftMargin = dp(9) })
        addView(linha, FrameLayout.LayoutParams(-1, -2))
    }

    /** Próximo despertador e o próximo compromisso de hoje: lidos fora da thread da tela (a agenda é uma consulta). */
    private fun carregarAvisos(agora: Long) {
        if (carregador.isShutdown) return
        carregador.execute {
            var alarme: Long? = null
            var lista: List<Pair<String, String>> = emptyList()
            try {
                // Só o que toca nas próximas 24 h: um alarme de daqui a 5 dias não ajuda na tela parada.
                Despertador.proximo(ctx)?.let { (_, quando) -> if (quando - agora < 24 * 60 * 60_000L) alarme = quando }
            } catch (e: Exception) { android.util.Log.w(TAG, "despertador: $e") }
            try {
                if (!ModoVisita.ativo(ctx)) {
                    val fimDoDia = Calendar.getInstance().apply {
                        timeInMillis = agora; set(Calendar.HOUR_OF_DAY, 23); set(Calendar.MINUTE, 59); set(Calendar.SECOND, 59)
                    }.timeInMillis
                    val hm = SimpleDateFormat("HH:mm", Locale.getDefault())
                    lista = Agenda.eventos(ctx, Perfil.ativa(ctx), agora, fimDoDia).filter { it.fim > agora }.take(2).map { e ->
                        e.titulo to (if (e.diaInteiro) ctx.getString(R.string.pg_dia_todo) else hm.format(Date(e.inicio)))
                    }
                }
            } catch (e: Exception) { android.util.Log.w(TAG, "agenda: $e") }
            val cartoes12 = ArrayList<Triple<String, String, String>>()
            try {
                if (!ModoVisita.ativo(ctx)) {
                    Retrospectiva.cartao(ctx, agora)?.let { cartoes12 += it }
                    if (Extras.ligado(ctx, Extras.Chave.MURAL)) {
                        val m = Mural.ler(ctx)
                        // Aniversários de hoje (calendário de aniversários do Google, que vem dos contatos).
                        val inicioDia = Calendar.getInstance().apply {
                            timeInMillis = agora; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
                        }.timeInMillis
                        Agenda.eventos(ctx, Perfil.ativa(ctx), inicioDia, inicioDia + 24 * 3_600_000L - 1)
                            .filter { it.diaInteiro && Mural.eAniversario(it.titulo) }.take(2)
                            .forEach { cartoes12 += Triple("Hoje", it.titulo, "") }
                        m.recados.forEach { cartoes12 += Triple("Recado", it, "") }
                        if (m.compras.isNotEmpty()) cartoes12 += Triple("Lista de compras", m.compras.joinToString(", "), "")
                    }
                }
            } catch (e: Exception) { android.util.Log.w(TAG, "mural: $e") }
            raiz.post {
                extras12 = cartoes12
                despertador = alarme; eventos = lista
                montarAvisos()
                montarAgenda()
            }
        }
    }

    /** Uma linha por compromisso, embaixo da data: ícone de calendário, título e "hoje" ou a hora. */
    private fun montarAgenda() {
        agenda.removeAllViews()
        eventos.forEachIndexed { i, (titulo, quando) ->
            agenda.addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(ImageView(ctx).apply { setImageDrawable(IconeLinha(IconeLinha.Tipo.CALENDARIO)); alpha = 0.9f },
                    LinearLayout.LayoutParams(dp(15), dp(15)))
                addView(texto(titulo, 12f, m600).apply { linhaUnica(); maxWidth = dp(300); setShadowLayer(dp(8).toFloat(), 0f, 0f, sombra) },
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(8) })
                addView(texto("·  $quando", 12f, m400).apply { alpha = 0.8f; setShadowLayer(dp(8).toFloat(), 0f, 0f, sombra) },
                    LinearLayout.LayoutParams(-2, -2).apply { leftMargin = dp(6) })
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(if (i == 0) 10 else 5) })
        }
    }

    private fun quandoTexto(quando: Long, agora: Long): String {
        val h = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(quando))
        val hoje = Calendar.getInstance().apply { timeInMillis = agora }.get(Calendar.DAY_OF_YEAR)
        val dia = Calendar.getInstance().apply { timeInMillis = quando }.get(Calendar.DAY_OF_YEAR)
        return if (dia == hoje) ctx.getString(R.string.pg_hoje_as, h) else ctx.getString(R.string.pg_amanha_as, h)
    }

    // ------------------------------------------------------------------ movimento e vidro
    /**
     * Movimento lento de câmera sobre a pintura ("efeito Ken Burns"): aproxima de 6 % a 12 % e desliza de um lado ao outro
     * em 75 s, e volta. Dá vida ao quadro e nenhum pixel fica parado (anti-burn-in). Camada de hardware: a GPU só move a
     * textura. Os painéis de vidro redesenham junto, para o desfoque atrás deles acompanhar a pintura.
     */
    private fun kenBurns(v: View) {
        v.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        // O zoom mínimo tem que cobrir o deslize dos dois lados (1 + 2·deslize/largura), senão aparece a borda da
        // pintura (aconteceu na 1ª foto da TV: 1.04 com 36 dp mostrava uma faixa escura na direita).
        val desliza = dp(24).toFloat()
        v.scaleX = 1.06f; v.scaleY = 1.06f; v.translationX = -desliza
        animador = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 75_000; repeatCount = android.animation.ValueAnimator.INFINITE; repeatMode = android.animation.ValueAnimator.REVERSE
            interpolator = android.view.animation.AccelerateDecelerateInterpolator()
            addUpdateListener { a ->
                val f = a.animatedValue as Float
                val esc = 1.06f + 0.06f * f
                v.scaleX = esc; v.scaleY = esc; v.translationX = -desliza + 2 * desliza * f
                vidro.redesenhar()
            }
            start()
        }
    }
    private var animador: android.animation.ValueAnimator? = null

    /** Pixels da pintura -> tela: recorte do ImageView, depois o zoom/deslize do Ken Burns, depois onde a raiz está. */
    private fun matrizPintura(f: FundoGaleria): Matrix? {
        val m = f.matrizImagem() ?: return null
        m.postConcat(f.matrix)
        m.postTranslate(f.left.toFloat(), f.top.toFloat())
        val loc = IntArray(2); raiz.getLocationOnScreen(loc)
        m.postTranslate(loc[0].toFloat(), loc[1].toFloat())
        return m
    }

    // ------------------------------------------------------------------ utilidades
    private fun texto(t: String, sp: Float, face: Typeface) = TextView(ctx).apply {
        text = t; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); typeface = face; setTextColor(branco); includeFontPadding = false
    }

    private fun TextView.linhaUnica() { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }

    private fun arredondado(cor: Int, raioDp: Float) = GradientDrawable().apply { setColor(cor); cornerRadius = raioDp * ctx.resources.displayMetrics.density }

    fun soltar() {
        animador?.cancel(); animador = null
        if (::hora.isInitialized) hora.removeCallbacks(tique)
        NotificacoesTv.parar(aoMudarNotificacoes)
        if (::colunaAvisos.isInitialized) colunaAvisos.removeCallbacks(tirarAvisoVencido)
        vidro.soltar()
        fundo?.let { f -> (0 until f.childCount).map { f.getChildAt(it) }.filterIsInstance<ImageView>().forEach { it.setImageDrawable(null) } }
    }

    private fun dp(v: Int) = (v * ctx.resources.displayMetrics.density + 0.5f).toInt()

    private companion object { const val TAG = "TvLauncher.Protetor" }
}
