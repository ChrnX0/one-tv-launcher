package br.paulo.tvlauncher

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.hardware.input.InputManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.text.format.DateUtils
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView

/**
 * Tela do card Jogos, no desenho "Sofá" (aprovado pelo dono em 17/11/2026, telas-v5 "Sofá v2"):
 *  - à esquerda, um painel com quem está no sofá (os controles ligados e a bateria) e a turma do Discord do servidor
 *    da pessoa (quem está na voz, quem está jogando o quê; [TurmaDiscord]). Com o foco no painel ele abre: aparece o QR
 *    que leva o celular para a sala de voz (a conversa vai pelo celular) e a fileira de jogos escurece;
 *  - à direita, "Continuar": os jogos em cards altos, o jogado por último primeiro, com Jogar, Ligar o PC e jogar e
 *    Chamar a turma embaixo; e "Por tipo", que filtra a fileira.
 * Segurar OK (ou Y no controle) num jogo troca o grupo dele ou tira ele do card. Letras grandes (idosos usam a TV).
 * A lista de apps, o uso, os banners e a turma são lidos fora da thread da tela.
 */
class JogosActivity : Activity() {
    private lateinit var painel: LinearLayout
    private lateinit var direita: LinearLayout
    private lateinit var controlesBox: LinearLayout
    private lateinit var sofaTitulo: TextView
    private lateinit var turmaNome: TextView
    private lateinit var turmaBox: LinearLayout
    private lateinit var chamarPainel: TextView
    private lateinit var chipRede: TextView
    private lateinit var brilho: View
    private lateinit var fila: LinearLayout
    private lateinit var botaoJogar: TextView
    private lateinit var botaoPc: TextView
    private lateinit var botaoChamar: TextView
    private lateinit var frase: TextView
    private lateinit var chips: LinearLayout
    private val ui = Handler(Looper.getMainLooper())
    private var rede: JogosTv.Medida? = null
    private var jogos: List<JogosTv.App> = emptyList()   // já na ordem "jogado por último primeiro"
    private var uso: Map<String, Long> = emptyMap()
    private var atual: JogosTv.App? = null               // o jogo com foco (ou o último que teve)
    private var filtro: Jogos.Grupo? = null              // "Por tipo": null = todos
    private var turma: TurmaDiscordTv.Estado? = null
    private var aberto = false                           // painel aberto (foco nele)
    private var esperandoPc = false
    private var montado = false
    private var abrirNoSofa = false   // veio do item "Sofá" da tela em cruz: 1º foco no painel
    private var focoPacote: String? = null   // ao redesenhar (mudou de grupo, voltou de um jogo), o foco volta no mesmo jogo
    private var qr: ImageView? = null        // QR da sala de voz (só aparece com o painel aberto)
    private var qrDica: TextView? = null

    /** O que a tela desenha de cada app: o banner oficial (logo) ou, sem banner, o ícone sobre a cor dele. */
    private class Visual(val banner: Bitmap?, val icone: Drawable?, val cores: IntArray)
    private val visuais = HashMap<String, Visual>()   // só na thread da tela

    private val ouvinteControles = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(id: Int) = desenharControles()
        override fun onInputDeviceRemoved(id: Int) = desenharControles()
        override fun onInputDeviceChanged(id: Int) = desenharControles()
    }

    // O painel abre quando o foco entra nele e fecha quando sai (sem ouvinte por view: um só para a tela toda).
    private val ouvinteFoco = ViewTreeObserver.OnGlobalFocusChangeListener { _, novo -> abrirPainel(novo != null && dentro(novo, painel)) }

    private val repetirTurma = object : Runnable {
        override fun run() { buscarTurma(); ui.postDelayed(this, 60_000) }
    }

    private fun dp(v: Int) = Estilo.dp(this, v)

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (VigiaAtualizacao.fechada(this)) return   // modo seguro (VigiaAtualizacao)
        abrirNoSofa = intent?.getBooleanExtra(EXTRA_SOFA, false) == true
        val raiz = FrameLayout(this).apply { background = Estilo.fundoTela(this@JogosActivity) }
        // Luz da cor do jogo em foco atrás da fileira (a tela "veste" o jogo) e um calor da marca no canto do painel.
        brilho = View(this)
        raiz.addView(brilho, FrameLayout.LayoutParams(-1, -1))
        raiz.addView(View(this).apply {
            background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(420).toFloat()
                setGradientCenter(0f, 0f)
                colors = intArrayOf(Estilo.alfa(MARCA, 0x30), Estilo.alfa(MARCA, 0x0C), 0x00000000)
            }
        }, FrameLayout.LayoutParams(-1, -1))

        val linha = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        painel = montarPainel()
        linha.addView(painel, LinearLayout.LayoutParams(dp(LARGURA_PAINEL), -1))
        direita = montarDireita()
        linha.addView(direita, LinearLayout.LayoutParams(0, -1, 1f))
        raiz.addView(linha, FrameLayout.LayoutParams(-1, -1))
        setContentView(raiz)
        raiz.viewTreeObserver.addOnGlobalFocusChangeListener(ouvinteFoco)
        getSystemService(InputManager::class.java)?.registerInputDeviceListener(ouvinteControles, ui)
        turma = TurmaDiscordTv.guardado(this)
        desenharControles()
        desenharTurma()
        testarRede()
    }

    override fun onResume() {
        super.onResume()
        carregar()   // ao voltar de um jogo ou da Play Store: a ordem mudou ou um app foi instalado
        ui.removeCallbacks(repetirTurma)
        repetirTurma.run()
    }

    override fun onPause() {
        ui.removeCallbacks(repetirTurma)   // tela fechada não fala com o Discord
        super.onPause()
    }

    override fun onDestroy() {
        getSystemService(InputManager::class.java)?.unregisterInputDeviceListener(ouvinteControles)
        if (::painel.isInitialized) painel.rootView.viewTreeObserver.takeIf { it.isAlive }?.removeOnGlobalFocusChangeListener(ouvinteFoco)
        esperandoPc = false
        super.onDestroy()
    }

    private fun carregar() {
        val ja = visuais.keys.toSet()
        Fundo.rodar("jogos") {
            val ctx = applicationContext
            val lista = JogosTv.listar(ctx)
            val u = Uso.ultimoUso(ctx)
            val porPacote = lista.associateBy { it.pacote }
            val ordenada = Jogos.ordenarPorUso(lista.map { it.pacote }, u).mapNotNull { porPacote[it] }
            // Banners e ícones só dos que ainda não foram lidos nesta tela (voltar de um jogo não relê nada).
            val novos = HashMap<String, Visual>()
            for (j in lista) if (j.pacote !in ja) novos[j.pacote] = lerVisual(j.pacote)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                visuais.putAll(novos); uso = u; jogos = ordenada
                if (focoPacote == null) focoPacote = atual?.pacote
                montar()
            }
        }
    }

    private fun lerVisual(pacote: String): Visual {
        val banner = CapaFoco.banner(packageManager, pacote)?.let { runCatching { CapaFoco.bitmapReduzido(it) }.getOrNull() }
        val icone = runCatching { packageManager.getApplicationIcon(pacote) }.getOrNull()
        val cores = icone?.let { runCatching { CapaFoco.corDoIcone(it) }.getOrNull() } ?: intArrayOf(0xFFF5A524.toInt(), 0xFF7A4A0E.toInt())
        return Visual(banner, icone, cores)
    }

    // ================================================================ painel da esquerda: sofá + turma

    private fun montarPainel(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(0x52000000, 0x38000000))
        setPadding(dp(30), dp(Estilo.MARGEM_VERTICAL), dp(20), dp(16))

        // "Jogos" com o ponto da marca.
        addView(LinearLayout(this@JogosActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.TITULO, getString(R.string.jogos_titulo)).apply {
                textSize = 30f; typeface = Estilo.fonte(800)
            })
            addView(View(this@JogosActivity).apply { background = Estilo.fundo(this@JogosActivity, MARCA, Estilo.RAIO_PILULA) },
                LinearLayout.LayoutParams(dp(9), dp(9)).apply { marginStart = dp(4); bottomMargin = dp(6) })
        })

        sofaTitulo = Estilo.secao(this@JogosActivity, getString(R.string.jogos_no_sofa))
        addView(sofaTitulo)
        controlesBox = LinearLayout(this@JogosActivity).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        addView(controlesBox)

        // "SUA TURMA · Os Parças · 9 online": o nome do servidor sem caixa alta.
        val cab = LinearLayout(this@JogosActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        cab.addView(Estilo.secao(this@JogosActivity, getString(R.string.turma_titulo)))
        turmaNome = Estilo.texto(this@JogosActivity, Estilo.Papel.LEGENDA).apply {
            textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(6), 0, 0, dp(Estilo.E1 - 1))
        }
        cab.addView(turmaNome, LinearLayout.LayoutParams(0, -2, 1f))
        addView(cab, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
        turmaBox = LinearLayout(this@JogosActivity).apply {
            orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false
            // Turma grande não passa por cima do botão de baixo: corta embaixo (dos lados sobra folga para o foco crescer).
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.clipBounds = android.graphics.Rect(-dp(40), -dp(40), v.width + dp(40), v.height) }
        }
        addView(turmaBox, LinearLayout.LayoutParams(-1, 0, 1f))

        chamarPainel = botaoMarca(getString(R.string.turma_chamar)).apply { setOnClickListener { chamarTurma(null) } }
        addView(chamarPainel, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E1) })
    }

    /** Uma linha por controle ligado: número do jogador, nome e se a bateria aguenta. Sem controle: "Parear um controle". */
    private fun desenharControles() {
        if (!::controlesBox.isInitialized) return
        controlesBox.removeAllViews()
        val c = JogosTv.controlesComBateria().take(4)
        if (c.isEmpty()) {
            controlesBox.addView(linhaCartao(getString(R.string.jogos_chip_sem_controle), getString(R.string.jogos_parear_um), null).apply {
                isFocusable = true; isClickable = true
                Estilo.focoCard(this)
                setOnClickListener {
                    if (!JogosTv.abrirPareamento(this@JogosActivity)) AvisoTv.rapido(this@JogosActivity, getString(R.string.jogos_parear), getString(R.string.jogos_parear_falhou))
                }
            }, LinearLayout.LayoutParams(-1, -2))
            return
        }
        for ((i, k) in c.withIndex()) {
            val fraca = k.bateria != null && k.bateria < 20
            val sub = getString(if (fraca) R.string.jogos_controle_carregar else R.string.jogos_controle_pronto) + (k.bateria?.let { " · $it%" } ?: "")
            val numero = Estilo.texto(this, Estilo.Papel.CORPO, "${i + 1}").apply {
                textSize = 17f; typeface = Estilo.fonte(800); gravity = Gravity.CENTER; setTextColor(Estilo.TEXTO)
                background = Estilo.fundo(this@JogosActivity, CORES_JOGADOR[i % CORES_JOGADOR.size], 10)
            }
            controlesBox.addView(linhaCartao(k.nome, sub, numero).apply {
                if (fraca) (getChildAt(1) as? LinearLayout)?.getChildAt(1)?.let { (it as TextView).setTextColor(Estilo.ATENCAO) }
            }, LinearLayout.LayoutParams(-1, -2).apply { if (i > 0) topMargin = dp(Estilo.E1) })
        }
    }

    /** Cartão baixo do painel: [esquerda] (número ou avatar), título e linha de baixo. */
    private fun linhaCartao(titulo: String, sub: String, esquerda: View?): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        background = Estilo.fundo(this@JogosActivity, Estilo.SUPERFICIE_ALTA, Estilo.RAIO)
        setPadding(dp(12), dp(10), dp(12), dp(10))
        if (esquerda != null) addView(esquerda, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(12) })
        else addView(View(this@JogosActivity), LinearLayout.LayoutParams(0, 0))
        addView(LinearLayout(this@JogosActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO, titulo).apply {
                textSize = 17f; typeface = Estilo.fonte(700); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.LEGENDA, sub).apply { textSize = 14f; maxLines = 1 },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(3) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun buscarTurma() {
        if (TurmaDiscordTv.servidor(this).isEmpty()) { turma = TurmaDiscordTv.Estado.SemServidor; desenharTurma(); return }
        val app = applicationContext
        Fundo.rodar("jogos-turma") {
            val e = TurmaDiscordTv.buscar(app)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                // A rede caiu por um instante: fica a turma de antes em vez de piscar "sem conexão".
                if (e is TurmaDiscordTv.Estado.SemRede && turma is TurmaDiscordTv.Estado.Pronta) return@runOnUiThread
                turma = e; desenharTurma(); if (montado) desenharFila()
            }
        }
    }

    private fun desenharTurma() {
        val tinhaFoco = currentFocus?.let { dentro(it, turmaBox) } == true
        turmaBox.removeAllViews()
        qr = null; qrDica = null
        turmaNome.text = ""
        var focavel: View? = null
        when (val e = turma) {
            null -> turmaBox.addView(legenda(getString(R.string.turma_procurando)))
            TurmaDiscordTv.Estado.SemServidor -> focavel = cartaoAcao(getString(R.string.turma_mostrar_titulo), getString(R.string.turma_mostrar_texto)) { configurarTurma() }
            TurmaDiscordTv.Estado.Desligado -> focavel = cartaoAcao(getString(R.string.turma_desligado_titulo), getString(R.string.turma_desligado_texto)) { configurarTurma() }
            TurmaDiscordTv.Estado.SemRede -> focavel = cartaoAcao(getString(R.string.turma_sem_rede), getString(R.string.turma_config_titulo)) { configurarTurma() }
            is TurmaDiscordTv.Estado.Pronta -> focavel = desenharTurmaPronta(e.turma)
        }
        aplicarAberto()
        if (tinhaFoco) (focavel ?: chamarPainel.takeIf { it.visibility == View.VISIBLE })?.requestFocus()
        else if (abrirNoSofa && montado && focavel != null) { abrirNoSofa = false; focavel.requestFocus() }
        atualizarAcoes()
    }

    /** A sala de voz mais cheia num cartão (com o QR quando o painel abre) e os outros online embaixo. */
    private fun desenharTurmaPronta(t: TurmaDiscord.Turma): View? {
        turmaNome.text = listOf(t.servidor, getString(R.string.turma_online, t.online)).filter { it.isNotEmpty() }.joinToString(" · ", prefix = "· ")
        var mostrados = 0
        var focavel: View? = null
        val sala = t.salas.firstOrNull()
        val servidor = TurmaDiscordTv.servidor(this)
        if (sala != null) {
            val cartao = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                isFocusable = true; isClickable = true
                background = Estilo.fundo(this@JogosActivity, 0x2234C77B, Estilo.RAIO, 0x4434C77B)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                Estilo.focoCard(this)
                setOnClickListener { mostrarQrGrande(sala, servidor) }
            }
            cartao.addView(Estilo.texto(this, Estilo.Papel.SECAO, "●  " + getString(R.string.turma_na_voz) + " · " + sala.nome).apply {
                setTextColor(VERDE); textSize = 13f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            val meio = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            val nomes = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            for (m in sala.membros.take(3)) { nomes.addView(linhaMembro(m), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }); mostrados++ }
            if (sala.membros.size > 3) nomes.addView(legenda("+ " + (sala.membros.size - 3)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(6); marginStart = dp(44) })
            mostrados += (sala.membros.size - 3).coerceAtLeast(0)
            meio.addView(nomes, LinearLayout.LayoutParams(0, -2, 1f))
            qr = ImageView(this).apply {
                setImageBitmap(QrCodigo.bitmap(TurmaDiscord.linkSala(servidor, sala.id), dp(QR)))
                background = Estilo.fundo(this@JogosActivity, 0xFFFFFFFF.toInt(), 10)
                setPadding(dp(5), dp(5), dp(5), dp(5))
                contentDescription = getString(R.string.turma_qr_dica)
            }
            meio.addView(qr, LinearLayout.LayoutParams(dp(QR), dp(QR)).apply { marginStart = dp(10) })
            cartao.addView(meio)
            qrDica = Estilo.texto(this, Estilo.Papel.LEGENDA, getString(R.string.turma_qr_dica)).apply { textSize = 14f; setTextColor(Estilo.TEXTO) }
            cartao.addView(qrDica, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
            turmaBox.addView(cartao, LinearLayout.LayoutParams(-1, -2))
            focavel = cartao
        }
        // Os outros online: menos linhas quando há sala de voz (o cartão já ocupa o meio do painel).
        val cabem = if (sala != null) 2 else 5
        for ((i, m) in t.outros.take(cabem).withIndex()) {
            turmaBox.addView(linhaMembro(m), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(if (i == 0 && sala == null) 4 else 12); marginStart = dp(14) })
            mostrados++
        }
        val resto = t.online - mostrados
        when {
            mostrados == 0 -> turmaBox.addView(legenda(getString(R.string.turma_ninguem)))
            resto > 0 -> turmaBox.addView(legenda(getString(R.string.turma_mais_online, resto)), LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(10); marginStart = dp(14) })
        }
        return focavel
    }

    /** Avatar (inicial na cor do nome, bolinha do status), nome e o jogo (ou o status). */
    private fun linhaMembro(m: TurmaDiscord.Membro): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val avatar = FrameLayout(this@JogosActivity).apply {
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO, m.nome.take(1).uppercase()).apply {
                textSize = 16f; typeface = Estilo.fonte(800); gravity = Gravity.CENTER; setTextColor(Estilo.FUNDO)
                background = Estilo.fundo(this@JogosActivity, CORES_AVATAR[Math.floorMod(m.nome.hashCode(), CORES_AVATAR.size)], Estilo.RAIO_PILULA)
            }, FrameLayout.LayoutParams(dp(32), dp(32)))
            addView(View(this@JogosActivity).apply {
                background = Estilo.fundo(this@JogosActivity, when (m.status) {
                    TurmaDiscord.Status.ONLINE -> VERDE
                    TurmaDiscord.Status.AUSENTE -> Estilo.ATENCAO
                    TurmaDiscord.Status.OCUPADO -> Estilo.ERRO
                }, Estilo.RAIO_PILULA, Estilo.FUNDO, 2)
            }, FrameLayout.LayoutParams(dp(12), dp(12), Gravity.BOTTOM or Gravity.END))
        }
        addView(avatar, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        addView(LinearLayout(this@JogosActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO, m.nome).apply {
                textSize = 17f; typeface = Estilo.fonte(700); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            })
            val sub = m.jogo ?: getString(when (m.status) {
                TurmaDiscord.Status.ONLINE -> R.string.turma_status_online
                TurmaDiscord.Status.AUSENTE -> R.string.turma_ausente
                TurmaDiscord.Status.OCUPADO -> R.string.turma_ocupado
            })
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.LEGENDA, sub).apply {
                textSize = 14f; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
                if (m.jogo != null) setTextColor(Estilo.TEXTO)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(2) })
        }, LinearLayout.LayoutParams(0, -2, 1f))
    }

    private fun legenda(t: String) = Estilo.texto(this, Estilo.Papel.LEGENDA, t).apply { textSize = 14f; setPadding(dp(4), dp(6), 0, 0) }

    /** Cartão focável com título e explicação (configurar a turma, widget desligado, sem rede). */
    private fun cartaoAcao(titulo: String, texto: String, ao: () -> Unit): View {
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            isFocusable = true; isClickable = true
            background = Estilo.vidro(this@JogosActivity)
            setPadding(dp(14), dp(12), dp(14), dp(14))
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO, titulo).apply { textSize = 17f; typeface = Estilo.fonte(700) })
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.LEGENDA, texto).apply { textSize = 14f; setLineSpacing(0f, 1.15f) },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })
            Estilo.focoCard(this)
            setOnClickListener { ao() }
        }
        turmaBox.addView(v, LinearLayout.LayoutParams(-1, -2))
        return v
    }

    /** Painel aberto: mais largo, com QR e dica; a direita escurece. Fechado: estreito, sem QR. */
    private fun abrirPainel(sim: Boolean) {
        if (sim == aberto) return
        aberto = sim
        val de = painel.layoutParams.width
        val para = dp(if (sim) LARGURA_PAINEL_ABERTO else LARGURA_PAINEL)
        ValueAnimator.ofInt(de, para).apply {
            duration = Estilo.TRANSICAO_MS
            addUpdateListener { a -> painel.layoutParams = painel.layoutParams.apply { width = a.animatedValue as Int } }
            start()
        }
        direita.animate().alpha(if (sim) 0.35f else 1f).setDuration(Estilo.TRANSICAO_MS).start()
        aplicarAberto()
    }

    private fun aplicarAberto() {
        val v = if (aberto) View.VISIBLE else View.GONE
        qr?.visibility = v; qrDica?.visibility = v
        // Aberto, o painel é da turma (desenho Sofá v2): os controles saem e o espaço vai para a sala de voz.
        val sofa = if (aberto && qr != null) View.GONE else View.VISIBLE
        sofaTitulo.visibility = sofa; controlesBox.visibility = sofa
        // "Chamar a turma" do painel só com ele aberto; fechado, o mesmo botão está ao lado do Jogar.
        chamarPainel.visibility = if (aberto && turma is TurmaDiscordTv.Estado.Pronta && TurmaDiscordTv.temWebhook(this)) View.VISIBLE else View.GONE
    }

    /** O 1º focável dentro de [g] (ordem da tela). */
    private fun achar(g: ViewGroup): View? {
        for (i in 0 until g.childCount) {
            val v = g.getChildAt(i)
            if (v.visibility != View.VISIBLE) continue
            if (v.isFocusable) return v
            if (v is ViewGroup) achar(v)?.let { return it }
        }
        return null
    }

    private fun dentro(v: View, pai: ViewGroup): Boolean {
        var p: Any? = v
        while (p is View) { if (p === pai) return true; p = p.parent }
        return false
    }

    /** OK no cartão da voz: o QR grande, para o celular ler de longe. */
    private fun mostrarQrGrande(sala: TurmaDiscord.Sala, servidor: String) {
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(ImageView(this@JogosActivity).apply {
                setImageBitmap(QrCodigo.bitmap(TurmaDiscord.linkSala(servidor, sala.id), dp(240)))
                background = Estilo.fundo(this@JogosActivity, 0xFFFFFFFF.toInt(), 12)
                setPadding(dp(10), dp(10), dp(10), dp(10))
            }, LinearLayout.LayoutParams(dp(240), dp(240)))
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO, getString(R.string.turma_qr_dica)).apply { textSize = 18f; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
        }
        CaixaTv(this).setTitle(getString(R.string.turma_na_voz) + " · " + sala.nome).setView(corpo)
            .setPositiveButton(android.R.string.ok, null).show()
    }

    // ================================================================ direita: continuar + por tipo

    private fun montarDireita(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        setPadding(dp(28), dp(Estilo.MARGEM_VERTICAL + 6), dp(Estilo.MARGEM_LATERAL), dp(14))

        // Topo: CONTINUAR · rede · hora.
        addView(LinearLayout(this@JogosActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(Estilo.secao(this@JogosActivity, getString(R.string.jogos_continuar)).apply { setPadding(dp(4), 0, 0, 0) },
                LinearLayout.LayoutParams(0, -2, 1f))
            chipRede = Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO).apply { textSize = 16f }
            addView(chipRede)
            addView(TextClock(this@JogosActivity).apply {
                Estilo.aplicar(this, Estilo.Papel.CORPO); textSize = 18f; typeface = Estilo.fonte(700)
            }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E3) })
        })

        // A fileira de jogos.
        fila = LinearLayout(this@JogosActivity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false; gravity = Gravity.CENTER_VERTICAL }
        addView(rolagem(fila).apply { setPadding(dp(8), dp(16), dp(8), dp(16)) }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })

        // Ações do jogo em foco + quem da turma está jogando.
        val acoes = LinearLayout(this@JogosActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; clipChildren = false }
        botaoJogar = botaoMarca("▶  " + getString(R.string.jogos_jogar)).apply {
            id = View.generateViewId()
            setOnClickListener { atual?.let { abrirJogo(it) } }
        }
        botaoPc = Estilo.botao(this@JogosActivity, "⏻  " + getString(R.string.jogos_ligar_pc_jogar), primario = false).apply {
            textSize = 18f; id = View.generateViewId(); setPadding(dp(22), dp(11), dp(22), dp(11))
            setOnClickListener { jogarNoPc(atual?.pacote) }
        }
        botaoChamar = Estilo.botao(this@JogosActivity, getString(R.string.turma_chamar), primario = false).apply {
            textSize = 18f; id = View.generateViewId(); setPadding(dp(22), dp(11), dp(22), dp(11))
            setOnClickListener { chamarTurma(atual?.nome) }
        }
        frase = Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO).apply { textSize = 16f; setTextColor(Estilo.TEXTO_2); maxLines = 2 }
        acoes.addView(botaoJogar)
        acoes.addView(botaoPc, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
        acoes.addView(botaoChamar, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(Estilo.E2) })
        acoes.addView(frase, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(Estilo.E3) })
        addView(acoes, LinearLayout.LayoutParams(-1, -2).apply { leftMargin = dp(8) })

        addView(View(this@JogosActivity), LinearLayout.LayoutParams(-1, 0, 1f))

        // Por tipo (filtra a fileira) + Mais opções.
        addView(LinearLayout(this@JogosActivity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            addView(Estilo.secao(this@JogosActivity, getString(R.string.jogos_por_tipo)).apply { setPadding(dp(4), 0, dp(Estilo.E2), 0) })
            chips = LinearLayout(this@JogosActivity).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            addView(rolagem(chips).apply { setPadding(dp(6), dp(8), dp(6), dp(8)) }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        addView(Estilo.barraDicas(this@JogosActivity,
            "◀ ▶" to getString(R.string.jogos_tecla_escolher),
            "OK" to getString(R.string.jogos_tecla_jogar),
            getString(R.string.jogos_tecla_segurar) to getString(R.string.jogos_tecla_opcoes)),
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E1); leftMargin = dp(4) })
    }

    private fun atualizarRede() {
        val m = rede
        chipRede.text = "●  " + (if (m == null) getString(R.string.jogos_chip_medindo) else getString(when (m.rede) {
            Jogos.Rede.BOA -> R.string.jogos_chip_boa
            Jogos.Rede.OK -> R.string.jogos_chip_ok
            Jogos.Rede.FRACA -> R.string.jogos_chip_fraca
            Jogos.Rede.SEM -> R.string.jogos_chip_sem
        }) + (m.ms?.let { " · $it ms" } ?: ""))
        chipRede.setTextColor(when (m?.rede) {
            Jogos.Rede.BOA -> 0xFF7EE2A8.toInt()
            Jogos.Rede.OK -> 0xFFFFD27A.toInt()
            Jogos.Rede.FRACA, Jogos.Rede.SEM -> 0xFFFF8A80.toInt()
            null -> Estilo.TEXTO_2
        })
    }

    private fun textoRede(m: JogosTv.Medida): String = getString(when (m.rede) {
        Jogos.Rede.BOA -> R.string.jogos_rede_boa
        Jogos.Rede.OK -> R.string.jogos_rede_ok
        Jogos.Rede.FRACA -> R.string.jogos_rede_fraca
        Jogos.Rede.SEM -> R.string.jogos_rede_sem
    }) + (m.ms?.let { " ($it ms)" } ?: "")

    private fun testarRede(avisar: Boolean = false) {
        rede = null
        atualizarRede()
        Fundo.rodar("jogos-rede") {
            val m = JogosTv.medirRede(applicationContext)
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                rede = m; atualizarRede()
                if (avisar) AvisoTv.rapido(this, getString(R.string.jogos_testar_rede), textoRede(m))
            }
        }
    }

    private fun montar() {
        desenharFila()
        desenharChips()
        montado = true
    }

    /**
     * A fileira: os jogos do filtro (o jogado por último primeiro) e "+ Mais jogos"; sem jogos, as sugestões.
     * O foco só vai para a fileira na 1ª vez, quando já estava nela ou quando um jogo pediu ([focoPacote]): a turma
     * que se atualiza com o foco no painel não rouba o foco.
     */
    private fun desenharFila() {
        if (!::fila.isInitialized) return
        val focoNaFila = currentFocus?.let { dentro(it, fila) } == true
        val tag = focoPacote ?: (currentFocus?.tag as? String)?.takeIf { focoNaFila }
        val alvo = redesenharFila(tag)
        if (!montado && abrirNoSofa) {
            // Item "Sofá" da cruz: o foco começa no painel (sala de voz, configurar a turma ou parear controle).
            // A turma ainda pode estar chegando do Discord: sem nada focável no painel, o foco fica no jogo e passa
            // para a turma quando ela aparecer (desenharTurma).
            val noPainel = achar(turmaBox) ?: achar(controlesBox)
            if (noPainel != null) {
                abrirNoSofa = false
                alvo?.isFocusedByDefault = false
                noPainel.isFocusedByDefault = true; noPainel.requestFocus()
            } else alvo?.requestFocus()
        } else if (!montado || focoNaFila || focoPacote != null) alvo?.requestFocus()
        focoPacote = null
    }

    /** Refaz os cards e devolve o que deve receber o foco ([tag] dele, senão o primeiro). */
    private fun redesenharFila(tag: String?): View? {
        fila.removeAllViews()
        val t = (turma as? TurmaDiscordTv.Estado.Pronta)?.turma
        val mostrar = jogos.filter { filtro == null || it.grupo == filtro }
        var primeiro: View? = null
        var alvo: View? = null
        if (jogos.isEmpty()) {
            for (s in Jogos.SUGESTOES) fila.addView(cardSugestao(s).also { if (primeiro == null) primeiro = it }, lpCard())
        } else for (j in mostrar) {
            val card = cardJogo(j, t?.let { TurmaDiscord.noApp(it, j.nome) } ?: emptyList())
            fila.addView(card, lpCard())
            if (primeiro == null) primeiro = card
            if (j.pacote == tag) alvo = card
        }
        val mais = cardMaisJogos()
        fila.addView(mais, lpCard())
        if (tag == "botao:mais") alvo = mais
        val escolhido = alvo ?: primeiro ?: mais
        // A janela, ao ganhar foco, escolhe sozinha o 1º focável (o painel): o foco de entrada é sempre um jogo.
        escolhido.isFocusedByDefault = true
        if (atual == null || mostrar.none { it.pacote == atual?.pacote }) mudarAtual(mostrar.firstOrNull { it.pacote == tag } ?: mostrar.firstOrNull())
        else atualizarAcoes()
        return escolhido
    }

    private fun lpCard() = LinearLayout.LayoutParams(dp(CARD_L), dp(CARD_A)).apply { marginEnd = dp(Estilo.E2 + 4) }

    private fun rolagem(v: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false; clipChildren = false; clipToPadding = false
        addView(v)
    }

    private fun nomeGrupo(g: Jogos.Grupo) = getString(when (g) {
        Jogos.Grupo.NUVEM -> R.string.jogos_grupo_nuvem
        Jogos.Grupo.PC -> R.string.jogos_grupo_pc
        Jogos.Grupo.RETRO -> R.string.jogos_grupo_retro
        Jogos.Grupo.TV -> R.string.jogos_grupo_tv
    })

    /**
     * Card alto de um jogo: o banner oficial do app em cima (já traz o logo; nada de logo copiado para o repositório),
     * sobre a cor do ícone; embaixo o nome grande, o tipo e quando foi jogado. Selo "2 da turma aqui" quando o Discord
     * diz que alguém da turma joga um jogo com esse mesmo nome.
     */
    private fun cardJogo(j: JogosTv.App, daTurma: List<String>): View = FrameLayout(this).apply {
        tag = j.pacote
        isFocusable = true; isClickable = true
        id = View.generateViewId()
        contentDescription = j.nome
        val v = visuais[j.pacote]
        val c = v?.cores ?: intArrayOf(0xFF2B2F3A.toInt(), Estilo.FUNDO)
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Estilo.matiz(c[0], 0.85f), Estilo.matiz(c[c.size - 1], 0.55f))).apply {
            cornerRadius = dp(CARD_RAIO).toFloat()
        }
        clipToOutline = true
        outlineProvider = object : android.view.ViewOutlineProvider() {
            override fun getOutline(vw: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, vw.width, vw.height, dp(CARD_RAIO).toFloat())
        }
        // Em cima: o banner 16:9 (ou o ícone do app).
        val topo = FrameLayout(this@JogosActivity).apply {
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(vw: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, vw.width, vw.height, dp(12).toFloat())
            }
        }
        if (v?.banner != null) topo.addView(ImageView(this@JogosActivity).apply { setImageBitmap(v.banner); scaleType = ImageView.ScaleType.CENTER_CROP }, FrameLayout.LayoutParams(-1, -1))
        else topo.addView(ImageView(this@JogosActivity).apply { setImageDrawable(v?.icone ?: IconeApp(IconeApp.Tipo.JOGOS, Estilo.TEXTO)) },
            FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
        val larguraTopo = CARD_L - 20
        addView(topo, FrameLayout.LayoutParams(dp(larguraTopo), dp(larguraTopo * 9 / 16), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(10) })
        // Embaixo: selo da turma, nome e "tipo · quando".
        val baixo = LinearLayout(this@JogosActivity).apply { orientation = LinearLayout.VERTICAL }
        if (daTurma.isNotEmpty()) baixo.addView(Estilo.texto(this@JogosActivity, Estilo.Papel.LEGENDA, getString(R.string.turma_aqui, daTurma.size)).apply {
            textSize = 13f; typeface = Estilo.fonte(700); setTextColor(Estilo.TEXTO)
            background = Estilo.fundo(this@JogosActivity, 0x66000000, Estilo.RAIO_PILULA)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }, LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(8) })
        baixo.addView(Estilo.texto(this@JogosActivity, Estilo.Papel.TITULO, j.nome).apply {
            textSize = 19f; typeface = Estilo.fonte(800); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.05f)
            setShadowLayer(dp(4).toFloat(), 0f, dp(1).toFloat(), 0x99000000.toInt())
        })
        baixo.addView(Estilo.texto(this@JogosActivity, Estilo.Papel.LEGENDA, subtitulo(j)).apply {
            textSize = 13f; setTextColor(Estilo.TEXTO_2); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        addView(baixo, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        Estilo.focoCard(this)
        val base = onFocusChangeListener
        setOnFocusChangeListener { vw, foco ->
            base?.onFocusChange(vw, foco)
            if (foco) {
                mudarAtual(j)
                // ▼ vai aos botões; ▲ nos botões volta para ESTE card.
                botaoJogar.nextFocusUpId = vw.id; botaoPc.nextFocusUpId = vw.id; botaoChamar.nextFocusUpId = vw.id
            }
        }
        setOnClickListener { abrirJogo(j) }
        setOnLongClickListener { escolherGrupo(j); true }
    }

    /** "Na nuvem · hoje". */
    private fun subtitulo(j: JogosTv.App): String {
        val quando = uso[j.pacote]?.takeIf { it > 0 }?.let {
            DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.DAY_IN_MILLIS).toString().lowercase()
        }
        return listOfNotNull(nomeGrupo(j.grupo), quando).joinToString(" · ")
    }

    /** O jogo em foco: a luz da cor dele e os botões de baixo. */
    private fun mudarAtual(j: JogosTv.App?) {
        val mudou = atual?.pacote != j?.pacote
        atual = j
        if (mudou) {
            val c = j?.let { visuais[it.pacote]?.cores } ?: intArrayOf(MARCA)
            brilho.background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = dp(560).toFloat()
                setGradientCenter(0.6f, 0.35f)
                colors = intArrayOf(Estilo.alfa(c[0], 0x44), Estilo.alfa(c[0], 0x12), 0x00000000)
            }
        }
        atualizarAcoes()
    }

    private fun atualizarAcoes() {
        if (!::botaoJogar.isInitialized) return
        val j = atual
        botaoJogar.visibility = if (j != null) View.VISIBLE else View.GONE
        botaoPc.visibility = if (j?.grupo == Jogos.Grupo.PC) View.VISIBLE else View.GONE
        val pronta = (turma as? TurmaDiscordTv.Estado.Pronta)?.turma
        botaoChamar.visibility = if (j != null && pronta != null && TurmaDiscordTv.temWebhook(this)) View.VISIBLE else View.GONE
        // "Lucas e Mari jogando Fortnite": quem joga o jogo deste card, senão o jogo com mais gente da turma.
        val jogando = pronta?.let { TurmaDiscord.jogandoAgora(it) } ?: emptyList()
        val par = j?.let { a -> jogando.firstOrNull { it.first.equals(a.nome, ignoreCase = true) } } ?: jogando.firstOrNull()
        frase.text = par?.let { (jogo, nomes) -> getString(R.string.turma_jogando, juntar(nomes), jogo) } ?: ""
        if (j == null && jogos.isEmpty()) frase.text = getString(R.string.jogos_vazio_dica)
    }

    private fun juntar(nomes: List<String>): String = when (nomes.size) {
        0 -> ""
        1 -> nomes[0]
        2 -> getString(R.string.turma_dois, nomes[0], nomes[1])
        else -> getString(R.string.turma_e_mais, nomes[0], nomes.size - 1)
    }

    /** "Todos · 9", um chip por tipo com jogos e "Mais opções". O tipo escolhido fica marcado. */
    private fun desenharChips() {
        chips.removeAllViews()
        val grupos = jogos.groupBy { it.grupo }
        fun chip(rotulo: String, marcado: Boolean, ao: () -> Unit) = Estilo.botao(this, (if (marcado) "✓ " else "") + rotulo, primario = marcado).apply {
            textSize = 16f; setPadding(dp(18), dp(9), dp(18), dp(9))
            setOnClickListener { ao() }
            chips.addView(this, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(Estilo.E1 + 4) })
        }
        if (grupos.size > 1) {
            chip(getString(R.string.jogos_todos) + " · " + jogos.size, filtro == null) { filtrar(null) }
            for (g in Jogos.Grupo.values()) {
                val n = grupos[g]?.size ?: continue
                chip(nomeGrupo(g) + " · " + n, filtro == g) { filtrar(g) }
            }
        }
        chip("⋯  " + getString(R.string.jogos_mais_opcoes), false) { maisOpcoes() }
    }

    private fun filtrar(g: Jogos.Grupo?) {
        filtro = g
        val foco = currentFocus
        val pos = (0 until chips.childCount).firstOrNull { chips.getChildAt(it) === foco }
        atual = null
        redesenharFila(null)
        desenharChips()
        pos?.let { chips.getChildAt(it)?.requestFocus() }
    }

    /** "+ Mais jogos" no fim da fileira: as sugestões da Play Store. */
    private fun cardMaisJogos(): View = FrameLayout(this).apply {
        isFocusable = true; isClickable = true
        tag = "botao:mais"
        background = GradientDrawable().apply {
            cornerRadius = dp(CARD_RAIO).toFloat()
            setStroke(dp(2), 0x55FFFFFF, dp(8).toFloat(), dp(6).toFloat())
        }
        addView(Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO, "+\n" + getString(R.string.jogos_mais_jogos)).apply {
            textSize = 18f; gravity = Gravity.CENTER; typeface = Estilo.fonte(700); setTextColor(Estilo.TEXTO_2)
        }, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        Estilo.focoCard(this)
        setOnClickListener { mostrarLoja() }
    }

    private fun cardSugestao(s: Jogos.Sugestao): View = FrameLayout(this).apply {
        isFocusable = true; isClickable = true
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(Estilo.matiz(0xFFF5A524.toInt(), 0.5f), Estilo.matiz(0xFF7A4A0E.toInt(), 0.4f))).apply { cornerRadius = dp(CARD_RAIO).toFloat() }
        contentDescription = s.nome
        addView(ImageView(this@JogosActivity).apply { setImageDrawable(IconeApp(IconeApp.Tipo.JOGOS, Estilo.alfa(Estilo.TEXTO, 0xB0))) },
            FrameLayout.LayoutParams(dp(52), dp(52), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(36) })
        addView(LinearLayout(this@JogosActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.TITULO, s.nome).apply { textSize = 19f; typeface = Estilo.fonte(800); maxLines = 2 })
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.LEGENDA, nomeGrupo(s.grupo)).apply { textSize = 13f },
                LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(4) })
        }, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })
        Estilo.focoCard(this)
        setOnClickListener { abrirNaLoja(s.pacote) }
    }

    /** Pílula na cor da marca (Jogar, Chamar a turma no painel). Foco = branco, como todo botão do app. */
    private fun botaoMarca(rotulo: String): TextView = Estilo.botao(this, rotulo).apply {
        textSize = 18f; typeface = Estilo.fonte(800)
        setPadding(dp(26), dp(11), dp(26), dp(11))
        val normal = Estilo.fundo(this@JogosActivity, MARCA, Estilo.RAIO_PILULA)
        background = normal
        val base = onFocusChangeListener
        setOnFocusChangeListener { v, foco ->
            base?.onFocusChange(v, foco)
            if (!foco) { v.background = normal; (v as TextView).setTextColor(0xFFFFFFFF.toInt()) }
        }
        setTextColor(0xFFFFFFFF.toInt())
    }

    // ================================================================ ações

    private fun abrir(pacote: String): Boolean {
        val i = packageManager.getLeanbackLaunchIntentForPackage(pacote) ?: packageManager.getLaunchIntentForPackage(pacote) ?: return false
        return try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } catch (_: Exception) { false }
    }

    /** Jogo na nuvem com a rede fraca: avisa antes (ideia 4 da especificação). Os outros abrem direto. */
    private fun abrirJogo(j: JogosTv.App) {
        val r = rede?.rede
        if (j.grupo == Jogos.Grupo.NUVEM && (r == Jogos.Rede.FRACA || r == Jogos.Rede.SEM)) {
            CaixaTv(this).setTitle(if (r == Jogos.Rede.SEM) R.string.jogos_rede_sem else R.string.jogos_aviso_fraca_titulo)
                .setMessage(R.string.jogos_aviso_fraca)
                .setPositiveButton(R.string.jogos_jogar_assim) { _, _ -> abrirOuAvisar(j.pacote) }
                .setNegativeButton(android.R.string.cancel, null).show()
            return
        }
        abrirOuAvisar(j.pacote)
    }

    private fun abrirOuAvisar(pacote: String) {
        if (!abrir(pacote)) AvisoTv.rapido(this, getString(R.string.jogos_titulo), getString(R.string.jogos_nao_abriu))
    }

    /** Segurar OK num jogo: em que grupo ele fica, tirar do card ou voltar ao automático. */
    private fun escolherGrupo(j: JogosTv.App) {
        lista(j.nome, Jogos.Grupo.values().map { g -> ((if (g == j.grupo) "✓ " else "") + nomeGrupo(g)) to { escolherValor(j, g.name) } } +
            listOf(getString(R.string.jogos_tirar) to { escolherValor(j, Jogos.FORA) }, getString(R.string.jogos_automatico) to { escolherValor(j, null) }))
    }

    private fun escolherValor(j: JogosTv.App, valor: String?) {
        JogosTv.escolher(this, j.pacote, valor)
        focoPacote = j.pacote
        carregar()
    }

    /** Lista de opções numa caixa: OK numa linha fecha a caixa e faz a ação. */
    private fun lista(titulo: String, itens: List<Pair<String, () -> Unit>>, mensagem: String? = null) {
        val corpo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        var janela: CaixaTv.Janela? = null
        for ((rotulo, ao) in itens) corpo.addView(Estilo.linhaTexto(this, rotulo).apply {
            setOnClickListener { janela?.dismiss(); ao() }
        }, Estilo.lpLinha(this))
        janela = CaixaTv(this).setTitle(titulo).apply { if (mensagem != null) setMessage(mensagem) }.setView(corpo)
            .setNegativeButton(android.R.string.cancel, null).show()
        corpo.getChildAt(0)?.requestFocus()
    }

    /** "⋯ Mais opções": o que antes era uma fileira de botões. */
    private fun maisOpcoes() = lista(getString(R.string.jogos_mais_opcoes), listOf(
        getString(R.string.jogos_testar_rede) to { testarRede(avisar = true) },
        getString(R.string.jogos_parear) to {
            if (!JogosTv.abrirPareamento(this)) AvisoTv.rapido(this, getString(R.string.jogos_parear), getString(R.string.jogos_parear_falhou))
        },
        getString(R.string.jogos_jogar_pc) to { jogarNoPc(null) },
        getString(R.string.jogos_baixar) to { mostrarLoja() },
        getString(R.string.jogos_escolher) to {
            startActivity(Intent(this, AppsEscolhaActivity::class.java).putExtra(AppsEscolhaActivity.EXTRA_MODO, AppsEscolhaActivity.MODO_JOGOS))
        },
        getString(R.string.turma_config_titulo) to { configurarTurma() },
    ))

    /** Sugestões que ainda não estão na TV. A Play Store decide se roda neste aparelho (dono: "problema dele"). */
    private fun mostrarLoja() {
        val faltam = Jogos.SUGESTOES.filter { !JogosTv.instalado(this, it.pacote) }
        if (faltam.isEmpty()) { AvisoTv.rapido(this, getString(R.string.jogos_baixar), getString(R.string.jogos_loja_tudo)); return }
        lista(getString(R.string.jogos_baixar), faltam.map { s -> (s.nome + "  ·  " + nomeGrupo(s.grupo)) to { abrirNaLoja(s.pacote) } },
            getString(R.string.jogos_loja_explica))
    }

    private fun abrirNaLoja(pacote: String) {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$pacote")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try { startActivity(i) } catch (_: Exception) {
            AvisoTv.rapido(this, getString(R.string.jogos_baixar), getString(R.string.jogos_sem_loja))
        }
    }

    // ================================================================ turma: configurar e chamar

    /**
     * O ID do servidor se digita aqui (só números; o teclado da TV abre no modo numérico). O webhook do "Chamar a
     * turma" é comprido demais para o controle: vai pelo painel web ou pelo adb (`EXTRAS --es o turma_webhook`).
     */
    private fun configurarTurma() {
        val campo = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine()
            hint = getString(R.string.turma_config_dica)
            setText(TurmaDiscordTv.servidor(this@JogosActivity))
        }
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(Estilo.texto(this@JogosActivity, Estilo.Papel.CORPO, getString(R.string.turma_config_texto)).apply { textSize = 17f; setLineSpacing(0f, 1.2f) })
            addView(campo, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
        }
        val caixa = CaixaTv(this).setTitle(R.string.turma_config_titulo).setView(corpo)
            .setPositiveButton(R.string.turma_config_guardar) { _, _ -> guardarServidor(campo.text.toString()) }
            .setNegativeButton(android.R.string.cancel, null)
        if (TurmaDiscordTv.servidor(this).isNotEmpty()) caixa.setNeutralButton(getString(R.string.turma_config_tirar)) { _, _ -> guardarServidor("") }
        caixa.show()
        campo.requestFocus()
    }

    private fun guardarServidor(id: String) {
        if (!TurmaDiscordTv.guardarServidor(this, id)) {
            AvisoTv.rapido(this, getString(R.string.turma_config_titulo), getString(R.string.turma_id_invalido)); return
        }
        turma = if (id.isBlank()) TurmaDiscordTv.Estado.SemServidor else null
        desenharTurma()
        buscarTurma()
    }

    /** Manda "Bora jogar <jogo>?" no canal do webhook, depois de mostrar o texto e perguntar. */
    private fun chamarTurma(jogo: String?) {
        val texto = if (jogo.isNullOrBlank()) getString(R.string.turma_chamado) else getString(R.string.turma_chamado_jogo, jogo)
        CaixaTv(this).setTitle(R.string.turma_chamar_pergunta).setMessage(texto)
            .setPositiveButton(R.string.turma_mandar) { _, _ ->
                val app = applicationContext
                Fundo.rodar("jogos-chamar") {
                    val r = TurmaDiscordTv.chamar(app, texto)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        AvisoTv.rapido(this, getString(R.string.turma_chamar), getString(when (r) {
                            TurmaDiscordTv.Chamado.ENVIADO -> R.string.turma_enviado
                            TurmaDiscordTv.Chamado.ESPERE -> R.string.turma_espere
                            TurmaDiscordTv.Chamado.SEM_WEBHOOK, TurmaDiscordTv.Chamado.FALHOU -> R.string.turma_falhou
                        }))
                    }
                }
            }
            .setNegativeButton(android.R.string.cancel, null).show()
    }

    // ================================================================ jogar no PC (ideia 3)

    /** [pacote] = o app de PC em foco ("Ligar o PC e jogar"); null = o botão geral, que escolhe o app. */
    private fun jogarNoPc(pacote: String?) {
        if (AcordarPc.macGuardado(this).isEmpty()) {
            CaixaTv(this).setTitle(R.string.jogos_jogar_pc).setMessage(R.string.jogos_pc_sem_mac)
                .setPositiveButton(android.R.string.ok, null).show(); return
        }
        if (pacote != null) { acordarEAbrir(pacote); return }
        val instalados = Jogos.APPS_PC.filter { JogosTv.instalado(this, it) }.toSet()
        val app = Jogos.appDoPc(instalados, JogosTv.appPcEscolhido(this))
        if (app == null) {
            CaixaTv(this).setTitle(R.string.jogos_jogar_pc).setMessage(R.string.jogos_pc_sem_app)
                .setPositiveButton(R.string.jogos_baixar) { _, _ -> mostrarLoja() }
                .setNegativeButton(android.R.string.cancel, null).show(); return
        }
        if (instalados.size > 1 && JogosTv.appPcEscolhido(this) == null) { escolherAppPc(instalados.toList()); return }
        acordarEAbrir(app)
    }

    /** Mais de um app de PC instalado: pergunta uma vez qual usar (a escolha fica guardada). */
    private fun escolherAppPc(apps: List<String>) = lista(getString(R.string.jogos_pc_qual_app), apps.map { p ->
        val nome = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
        nome to { JogosTv.escolherAppPc(this, p); acordarEAbrir(p) }
    })

    private fun acordarEAbrir(app: String) {
        if (esperandoPc) return
        esperandoPc = true
        val texto = Estilo.texto(this, Estilo.Papel.CORPO, getString(R.string.jogos_pc_acordando, 0)).apply { textSize = 19f }
        val janela = CaixaTv(this).setTitle(R.string.jogos_jogar_pc).setView(texto)
            .setNegativeButton(android.R.string.cancel) { _, _ -> esperandoPc = false }
            .setOnCancelListener { esperandoPc = false }.show()
        val app0 = applicationContext
        Fundo.rodar("jogos-pc") {
            val ok = JogosTv.acordarEEsperar(app0, { s -> runOnUiThread { texto.text = getString(R.string.jogos_pc_acordando, s) } }, { !esperandoPc })
            runOnUiThread {
                if (isDestroyed || !esperandoPc) return@runOnUiThread
                esperandoPc = false
                runCatching { janela.dismiss() }
                if (!ok) AvisoTv.rapido(this, getString(R.string.jogos_jogar_pc), getString(R.string.jogos_pc_nao_respondeu))
                abrirOuAvisar(app)   // abre mesmo sem resposta: o app de PC mostra o próprio erro e deixa tentar de novo
            }
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Botão Y/△ do controle de videogame em cima de um jogo = o mesmo que segurar OK (escolher o grupo).
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_Y && event.action == KeyEvent.ACTION_UP) {
            val p = currentFocus?.tag as? String
            jogos.firstOrNull { it.pacote == p }?.let { escolherGrupo(it); return true }
        }
        return super.dispatchKeyEvent(event)
    }

    companion object {
        /** Abrir já no painel do sofá (item "Sofá" da coluna Jogar da tela em cruz): o foco começa na turma. */
        const val EXTRA_SOFA = "br.paulo.tvlauncher.jogos.SOFA"

        /** Cor da marca Sofaly no fundo escuro (17/11/2026). Vira Estilo.ACENTO quando a marca chegar ao Estilo. */
        private const val MARCA = 0xFFFF6A2B.toInt()
        private const val VERDE = 0xFF57D98A.toInt()
        private const val LARGURA_PAINEL = 236
        private const val LARGURA_PAINEL_ABERTO = 312
        private const val CARD_L = 158
        private const val CARD_A = 232
        private const val CARD_RAIO = 18
        private const val QR = 92
        private val CORES_JOGADOR = intArrayOf(MARCA, 0xFF3D7BFF.toInt(), 0xFF2FA866.toInt(), 0xFFC98A12.toInt())
        // Avatares: tons claros, nenhum rosado (o dono detesta rosa).
        private val CORES_AVATAR = intArrayOf(0xFFFFB37A.toInt(), 0xFF9CC8FF.toInt(), 0xFFA8E6B8.toInt(), 0xFFF5DFA0.toInt(), 0xFF8FE0D0.toInt(), 0xFFC7D3E0.toInt())
    }
}
