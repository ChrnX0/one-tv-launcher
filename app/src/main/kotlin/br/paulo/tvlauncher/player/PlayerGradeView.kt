package br.paulo.tvlauncher.player

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.Fundo
import br.paulo.tvlauncher.Gravacoes
import br.paulo.tvlauncher.LembretesTv
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.Rtl
import br.paulo.tvlauncher.alarm.Lembretes
import br.paulo.tvlauncher.settings.Fontes
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Player universal, guia em grade (06/11/2026): canais nas linhas, horários nas colunas, como na TV a cabo.
 *
 * É UMA View que desenha tudo no Canvas, em vez de uma View por programa: com 8 linhas × ~6 programas seriam ~50 Views
 * refeitas a cada ◀ ▶, e esta TV (S905D3) já está no limite da GPU na HOME. Aqui cada passo é um único desenho.
 * A matemática (cursor, janela, rolagem) é a PlayerGrade, testada fora do Android.
 *
 * Teclas: ▲ ▼ canal, ◀ ▶ programa, CH+ CH- página, OK assiste o canal da linha, VOLTAR fecha.
 * OK num programa que ainda vai começar abre: Me avise, Troque sozinho na hora (ideia 85), Gravar e Gravar toda semana.
 * O ponto âmbar no canto do programa mostra os marcados (LembretesTv).
 */
class PlayerGradeView(
    ctx: Context,
    private val aoAssistir: (Canal) -> Unit,
    private val aoFechar: () -> Unit,
) : View(ctx) {
    private var canais: List<Canal> = emptyList()
    private var guia = Guia(emptyList())
    private var titulo = ""
    private var atualId: String? = null
    private var linha = 0
    private var topo = 0
    private var foco = 0L          // instante do cursor
    private var janela = 0L        // começo da janela de 3 h

    private val d = resources.displayMetrics.density
    private val hora = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val papel = Estilo.TEXTO       // cores do sistema de design (Estilo), como na lista de canais (ideia 17)
    private val suave = Estilo.TEXTO_2
    private val ambar = Estilo.ATENCAO
    private val agoraCor = Estilo.ERRO     // a linha do "agora" no vermelho do "ao vivo"

    private val pTexto = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = papel; textSize = 16f * d; typeface = Fontes.nunito(500) }   // 14 -> 16 (13/11/2026: letra maior em Filmes, Séries e TV)
    private val pPequeno = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = suave; textSize = Estilo.Leitura.CONTAGEM * d; typeface = Fontes.nunito(400) }   // pente fino 19/11/2026: sem isto saía em Roboto (horários, "Sem guia")
    private val pTitulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = papel; textSize = Estilo.Leitura.PROGRAMA * d; typeface = Fontes.nunito(500) }
    private val pRotulo = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.TEXTO_3; textSize = Estilo.Leitura.ROTULO * d; typeface = Fontes.nunito(600); letterSpacing = 0.06f }
    private val pBloco = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pBorda = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Estilo.FOCO }
    private val pLinha = Paint().apply { color = agoraCor; strokeWidth = 2f * d }
    private val r = RectF()
    private val pMarca = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Estilo.ATENCAO }
    /** Lembretes marcados ("canalId|início"), lidos do disco ao abrir a grade. */
    @Volatile private var marcados: Set<String> = emptySet()

    // Medidas (em px)
    private val margem = 28f * d
    private val alturaCabecalho = 38f * d   // medidas acompanham a letra maior: 5 canais visíveis (eram 6)
    private val alturaRegua = 28f * d
    private val alturaLinha = 58f * d
    private val alturaDetalhe = 120f * d
    private val larguraCanal = 220f * d

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(Estilo.FUNDO)
        visibility = GONE
    }

    val aberta: Boolean get() = visibility == VISIBLE

    fun abrir(canais: List<Canal>, guia: Guia, titulo: String, atual: Canal?) {
        this.canais = canais; this.guia = guia; this.titulo = titulo; atualId = atual?.id
        val agora = System.currentTimeMillis()
        linha = canais.indexOfFirst { it.id == atualId }.coerceAtLeast(0)
        topo = PlayerGrade.ajustarTopo(linha - visiveis() / 2, linha, visiveis(), canais.size)
        foco = agora
        janela = PlayerGrade.inicioFaixa(agora)
        visibility = VISIBLE
        requestFocus()
        post { anunciar() }
        invalidate()
        removeCallbacks(relogio); postDelayed(relogio, 30_000)
        val app = context.applicationContext
        Fundo.rodar("lembretes") {
            val m = runCatching { Lembretes.marcados(LembretesTv.listar(app)) }.getOrDefault(emptySet())
            post { marcados = m; invalidate() }
        }
    }

    /** Me avise / Troque sozinho / nada (disco fora da thread da tela) e confirma com um aviso curto. */
    private fun definirLembrete(canal: Canal, p: Programa, modo: Int) {
        val app = context.applicationContext
        Fundo.rodar("lembrete") {
            runCatching { LembretesTv.definir(app, canal, p, modo) }.getOrNull() ?: return@rodar
            post {
                val chave = "${canal.id}|${p.inicio}"
                marcados = if (modo == Lembretes.MODO_NADA) marcados - chave else marcados + chave
                invalidate()
                val (titulo, texto) = when (modo) {
                    Lembretes.MODO_AVISAR -> app.getString(R.string.bu_lembrete_sim) to
                        app.getString(R.string.pg_aviso_as, LembretesTv.hora(p.inicio - Lembretes.ANTECEDENCIA_MS), p.titulo)
                    Lembretes.MODO_TROCAR -> app.getString(R.string.lb_troca_marcada) to
                        app.getString(R.string.lb_troca_marcada_det, canal.nome, LembretesTv.hora(p.inicio))
                    else -> app.getString(R.string.bu_lembrete_nao) to p.titulo
                }
                AvisoTv.rapido(app, titulo, texto)
            }
        }
    }

    /**
     * OK num programa futuro (14/11/2026): Lembrar, Gravar ou Gravar toda semana (GravacaoServico). Já agendada: Cancelar
     * a gravação. Disco fora da thread da tela.
     */
    private fun futuro(canal: Canal, p: Programa) {
        val app = context.applicationContext
        Fundo.rodar("gravacao") {
            val ag = runCatching { Gravacoes.agendada(app, canal.id, p.inicio) }.getOrNull()
            val modo = runCatching { Lembretes.modo(LembretesTv.listar(app), "${canal.id}|${p.inicio}") }.getOrDefault(Lembretes.MODO_NADA)
            post {
                if (!aberta) return@post
                val itens = ArrayList<Pair<String, () -> Unit>>()
                // Ideia 85: "Me avise" (2 min antes, com Assistir) ou "Troque sozinho na hora". OK no marcado desmarca.
                itens += app.getString(R.string.lb_me_avise) to { definirLembrete(canal, p, if (modo == Lembretes.MODO_AVISAR) Lembretes.MODO_NADA else Lembretes.MODO_AVISAR) }
                itens += app.getString(R.string.lb_trocar_sozinho) to { definirLembrete(canal, p, if (modo == Lembretes.MODO_TROCAR) Lembretes.MODO_NADA else Lembretes.MODO_TROCAR) }
                val marcadas = when (modo) { Lembretes.MODO_AVISAR -> listOf(0); Lembretes.MODO_TROCAR -> listOf(1); else -> emptyList() }
                if (ag != null) itens += app.getString(R.string.grv_cancelar_agendada) to { cancelarGravacao(ag) }
                else {
                    itens += app.getString(R.string.grv_agendar, Gravacoes.hora(p.inicio - GravacaoRegras.FOLGA_ANTES_MS),
                        Gravacoes.hora(p.fim + GravacaoRegras.FOLGA_DEPOIS_MS)) to { agendarGravacao(canal, p, false) }
                    itens += app.getString(R.string.grv_agendar_semana) to { agendarGravacao(canal, p, true) }
                }
                Estilo.lista(context, "${p.titulo} · ${canal.nome} ${Gravacoes.hora(p.inicio)}", itens.map { it.first }, marcadas) { i -> itens[i].second() }
            }
        }
    }

    private fun agendarGravacao(canal: Canal, p: Programa, semanal: Boolean) =
        GravacaoDestino.perguntar(context) { escolha -> agendarGravacao(canal, p, semanal, escolha) }

    private fun agendarGravacao(canal: Canal, p: Programa, semanal: Boolean, escolha: GravacaoDestino.Opcao?) {
        val app = context.applicationContext
        val onde = escolha?.nome
        Fundo.rodar("gravacao") {
            val r = runCatching { Gravacoes.agendar(app, canal, p, semanal, escolha?.id.orEmpty()) }.getOrNull()
            post {
                val (titulo, texto) = when {
                    r == null -> app.getString(R.string.grv_nao_agendou) to app.getString(R.string.grv_lista_cheia)
                    r.isNotEmpty() -> app.getString(R.string.grv_nao_agendou) to app.getString(R.string.grv_conflito_agenda,
                        r.first().programa.ifEmpty { r.first().canalNome }, r.first().canalNome, Gravacoes.hora(r.first().inicio))
                    else -> app.getString(R.string.grv_agendada) to (p.titulo + " · " +
                        (onde?.let { app.getString(R.string.grv_em, it) } ?: app.getString(R.string.grv_sem_destino_ainda)))
                }
                AvisoTv.rapido(app, titulo, texto, 8)
            }
        }
    }

    private fun cancelarGravacao(a: GravacaoRegras.Agendada) {
        val app = context.applicationContext
        Fundo.rodar("gravacao") {
            runCatching { Gravacoes.cancelar(app, a.id) }
            post { AvisoTv.rapido(app, app.getString(R.string.grv_cancelada), a.programa) }
        }
    }

    fun fechar() { removeCallbacks(relogio); visibility = GONE }

    fun atualizarGuia(g: Guia) { guia = g; if (aberta) invalidate() }

    /** A linha vermelha do "agora" anda sozinha; e o cursor não pode ficar no passado. */
    private val relogio: Runnable = object : Runnable {
        override fun run() {
            if (!aberta) return
            val agora = System.currentTimeMillis()
            if (foco < agora) foco = agora
            janela = PlayerGrade.ajustarJanela(janela, foco, agora)
            invalidate()
            postDelayed(this, 30_000)
        }
    }

    private fun visiveis(): Int {
        val h = if (height > 0) height.toFloat() else resources.displayMetrics.heightPixels.toFloat()
        return ((h - margem - alturaCabecalho - alturaRegua - alturaDetalhe - margem / 2) / alturaLinha).toInt().coerceAtLeast(1)
    }

    private fun programas(c: Canal): List<Programa> = if (c.guiaId.isEmpty()) emptyList() else guia.doCanal(c.guiaId)

    // ---------------------------------------------------------------- teclas

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!aberta || canais.isEmpty()) return super.onKeyDown(keyCode, event)
        val agora = System.currentTimeMillis()
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> trocarLinha(-1)
            KeyEvent.KEYCODE_DPAD_DOWN -> trocarLinha(+1)
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_PAGE_UP -> trocarLinha(-visiveis())
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> trocarLinha(+visiveis())
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val passo = PlayerGrade.passoDaSeta(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT, Rtl.ehRtl(this))
                foco = PlayerGrade.mover(programas(canais[linha]), foco, passo, agora)
                janela = PlayerGrade.ajustarJanela(janela, foco, agora)
            }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (event.repeatCount == 0) {
                    val p = programaFocado()
                    if (p != null && p.inicio > agora) futuro(canais[linha], p) else aoAssistir(canais[linha])
                }
                return true
            }
            KeyEvent.KEYCODE_BACK -> { aoFechar(); return true }
            else -> return super.onKeyDown(keyCode, event)   // volume e o resto seguem para o Android
        }
        invalidate()
        anunciar()
        return true
    }

    /** 16.1: diz ao leitor de tela o canal e o programa sob o cursor (e deixa como descrição da grade). */
    private fun anunciar() {
        val c = canais.getOrNull(linha) ?: return
        val p = programaFocado()
        val fala = PlayerGrade.falaLeitor(c.numero, c.nome, p?.titulo, p?.let { hora.format(Date(it.inicio)) },
            p?.let { hora.format(Date(it.fim)) }, context.getString(R.string.player_sem_guia_curto))
        if (fala == contentDescription) return
        contentDescription = fala
        val am = context.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE) as? android.view.accessibility.AccessibilityManager
        @Suppress("DEPRECATION")
        if (am?.isEnabled == true) announceForAccessibility(fala)
    }

    private fun trocarLinha(passo: Int) {
        linha = PlayerGrade.moverLinha(linha, passo, canais.size)
        topo = PlayerGrade.ajustarTopo(topo, linha, visiveis(), canais.size)
    }

    // ---------------------------------------------------------------- desenho

    // 16.8: no árabe o guia anda da direita para a esquerda (canais à direita, o tempo cresce para a esquerda).
    private var rtl = false

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        if (canais.isEmpty()) return
        rtl = Rtl.ehRtl(this)
        Rtl.espelhar(this, c) { desenhar(c) }
    }

    private fun desenhar(c: Canvas) {
        val agora = System.currentTimeMillis()
        val esquerda = margem
        val direita = width - margem
        val xGrade = esquerda + larguraCanal
        val larguraGrade = direita - xGrade
        val pxPorMs = larguraGrade / PlayerGrade.JANELA
        fun x(t: Long) = xGrade + (t - janela) * pxPorMs

        // Cabeçalho: título e hora.
        var y = margem
        Rtl.texto(c, rtl, titulo.uppercase(Locale.getDefault()), esquerda, y + 16f * d, pRotulo)
        val relogio = hora.format(Date(agora))
        Rtl.texto(c, rtl, relogio, direita - pTexto.measureText(relogio), y + 16f * d, pTexto)
        y += alturaCabecalho

        // Régua de 30 em 30 min.
        var t = janela
        while (t < janela + PlayerGrade.JANELA) {
            Rtl.texto(c, rtl, hora.format(Date(t)), x(t) + 6f * d, y + 18f * d, pPequeno)
            t += PlayerGrade.FAIXA
        }
        y += alturaRegua
        val yLinhas = y

        // Linhas.
        val n = visiveis()
        val focado = programaFocado()
        for (k in 0 until n) {
            val i = topo + k
            if (i >= canais.size) break
            val canal = canais[i]
            val y0 = yLinhas + k * alturaLinha
            val y1 = y0 + alturaLinha - 4f * d
            val meio = (y0 + y1) / 2

            // Coluna do canal: número, logo e nome.
            pTexto.color = if (canal.id == atualId) ambar else papel
            Rtl.texto(c, rtl, canal.numero.toString(), esquerda + 4f * d, meio + 6f * d, pTexto)
            val logo = PlayerLogos.emMemoria(context, canal.logo) { invalidate() }
            val xLogo = esquerda + 46f * d
            if (logo != null) {
                val esc = minOf(52f * d / logo.width, 34f * d / logo.height)
                r.set(xLogo, meio - logo.height * esc / 2, xLogo + logo.width * esc, meio + logo.height * esc / 2)
                c.drawBitmap(logo, null, r, null)
            }
            pTexto.color = papel
            val nome = TextUtils.ellipsize(canal.nome, pTexto, larguraCanal - 120f * d, TextUtils.TruncateAt.END)
            Rtl.texto(c, rtl, nome, 0, nome.length, esquerda + 106f * d, meio + 6f * d, pTexto)

            // Programas.
            val ps = PlayerGrade.naJanela(programas(canal), janela)
            if (ps.isEmpty()) {
                r.set(xGrade, y0, direita, y1)
                pBloco.color = Estilo.SUPERFICIE
                c.drawRoundRect(r, 8f * d, 8f * d, pBloco)
                Rtl.texto(c, rtl, context.getString(R.string.player_sem_guia_curto), xGrade + 12f * d, meio + 5f * d, pPequeno)
                if (i == linha) c.drawRoundRect(r, 8f * d, 8f * d, pBorda)
                continue
            }
            for (p in ps) {
                val xa = maxOf(x(p.inicio), xGrade) + 2f * d
                val xb = minOf(x(p.fim), direita) - 2f * d
                if (xb - xa < 4f * d) continue
                r.set(xa, y0, xb, y1)
                val noAr = agora >= p.inicio && agora < p.fim
                pBloco.color = when {
                    i == linha && p === focado -> Estilo.alfa(Estilo.TEXTO, 0x33)
                    noAr -> Estilo.SUPERFICIE_ALTA
                    else -> Estilo.SUPERFICIE
                }
                c.drawRoundRect(r, 8f * d, 8f * d, pBloco)
                if (i == linha && p === focado) c.drawRoundRect(r, 8f * d, 8f * d, pBorda)
                if ("${canal.id}|${p.inicio}" in marcados) c.drawCircle(xb - 9f * d, y0 + 9f * d, 4f * d, pMarca)
                val larguraTexto = xb - xa - 20f * d
                if (larguraTexto > 12f * d) {
                    pTexto.color = papel
                    val tt = TextUtils.ellipsize(p.titulo, pTexto, larguraTexto, TextUtils.TruncateAt.END)
                    Rtl.texto(c, rtl, tt, 0, tt.length, xa + 10f * d, meio - 2f * d, pTexto)
                    val hh = TextUtils.ellipsize("${hora.format(Date(p.inicio))} – ${hora.format(Date(p.fim))}", pPequeno, larguraTexto, TextUtils.TruncateAt.END)
                    Rtl.texto(c, rtl, hh, 0, hh.length, xa + 10f * d, meio + 17f * d, pPequeno)
                }
            }
        }

        // Linha do "agora".
        val xAgora = x(agora)
        if (xAgora in xGrade..direita) c.drawLine(xAgora, yLinhas - 6f * d, xAgora, yLinhas + n * alturaLinha, pLinha)

        // Detalhe do programa em foco (embaixo).
        val yD = height - margem / 2 - alturaDetalhe
        r.set(esquerda, yD, direita, height - margem / 2)
        pBloco.color = Estilo.alfa(Estilo.FUNDO, 0xCC)
        c.drawRoundRect(r, 14f * d, 14f * d, pBloco)
        val canal = canais.getOrNull(linha) ?: return
        val xt = esquerda + 18f * d
        val larg = direita - esquerda - 36f * d
        var yt = yD + 34f * d
        val tituloDet = TextUtils.ellipsize(focado?.titulo ?: canal.nome, pTitulo, larg, TextUtils.TruncateAt.END)
        Rtl.texto(c, rtl, tituloDet, 0, tituloDet.length, xt, yt, pTitulo)
        yt += 23f * d
        val info = buildList {
            add("${canal.numero}  ${canal.nome}")
            focado?.let { add("${hora.format(Date(it.inicio))} – ${hora.format(Date(it.fim))}"); if (it.categoria.isNotEmpty()) add(it.categoria) }
            add(context.getString(R.string.player_grade_teclas))
        }.joinToString("   ·   ")
        val infoCurta = TextUtils.ellipsize(info, pPequeno, larg, TextUtils.TruncateAt.END)
        Rtl.texto(c, rtl, infoCurta, 0, infoCurta.length, xt, yt, pPequeno)
        val desc = focado?.descricao.orEmpty()
        if (desc.isNotEmpty()) {
            // Descrição em até 2 linhas (corte simples por largura; o resto vira "…").
            pPequeno.color = papel
            var resto = desc
            for (k in 0 until 2) {
                if (resto.isEmpty()) break
                yt += 21f * d
                val cabe = pPequeno.breakText(resto, true, larg, null)
                val ultima = k == 1 || cabe >= resto.length
                val trecho = if (ultima) TextUtils.ellipsize(resto, pPequeno, larg, TextUtils.TruncateAt.END).toString()
                    else resto.substring(0, resto.lastIndexOf(' ', cabe).takeIf { it > 0 } ?: cabe)
                Rtl.texto(c, rtl, trecho, xt, yt, pPequeno)
                resto = resto.substring(minOf(resto.length, trecho.length)).trimStart()
                if (ultima) break
            }
            pPequeno.color = suave
        }
    }

    private fun programaFocado(): Programa? = canais.getOrNull(linha)?.let { PlayerGrade.programaEm(programas(it), foco) }
}
