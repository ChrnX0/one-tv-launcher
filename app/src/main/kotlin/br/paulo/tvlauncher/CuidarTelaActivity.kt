package br.paulo.tvlauncher

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.ColorDrawable
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.alarm.AcenderTela
import br.paulo.tvlauncher.family.CuidarDeLonge
import br.paulo.tvlauncher.input.SegurarOk
import br.paulo.tvlauncher.panel.confirmar

/**
 * A pergunta grande do "Cuidar de longe" (ideias 77, 78, 79), por cima de qualquer tela. Letra de Estilo.Leitura
 * (PERGUNTA*): é para ler a 3 m, às vezes com pressa ou sem óculos. Três modos:
 *
 *  - CONSENTIMENTO: "Você aceita que Ana receba avisos desta TV?", com a lista do que sai. O foco começa em
 *    "Não aceito" (o seguro, como todo diálogo do sistema de design). "Aceito" SÓ vale com o controle de verdade:
 *    tecla de aparelho físico (deviceId real). Tecla do painel, do Claude (adb `input`) ou clique de acessibilidade não
 *    aceita por ninguém: aqui a pessoa que mora na casa tem de apertar.
 *  - AJUDA: "Mandar pedido de ajuda para Ana?". Foco em "Mandar" (quem segurou 3 s quer ajuda; OK manda, VOLTAR
 *    desiste). Depois mostra "Ana foi avisada. Pode esperar." (ou o erro) e fecha sozinha.
 *  - LEMBRETE: o texto do lembrete enorme, "Feito" com o foco. Acende a tela se estiver apagada (como o despertador).
 */
class CuidarTelaActivity : Activity() {
    companion object {
        const val EXTRA_MODO = "modo"
        const val EXTRA_CHAVE = "chave"
        const val EXTRA_TEXTO = "texto"
        const val EXTRA_MOMENTO = "momento"
        const val MODO_CONSENTIMENTO = "consentimento"
        const val MODO_AJUDA = "ajuda"
        const val MODO_LEMBRETE = "lembrete"
        const val MODO_ESTOU_BEM = "estou_bem"
        private const val FECHAR_MS = 30_000L
        /** Lembretes / "Estou bem?" que chegaram com outro na tela (CuidarDeLonge.esperaNaFila). Só na thread da tela. */
        private val pendentes = ArrayDeque<Intent>()
    }

    private val ui = Handler(Looper.getMainLooper())
    private var modo = ""
    private var aceito: TextView? = null
    // Aparelhos de entrada que existiam quando a pergunta do consentimento abriu (CuidarDeLonge.aparelhoDeAntes).
    // Lido UMA vez, ao montar: reler no onResume deixaria entrar um aparelho criado enquanto a tela estava pausada
    // (com a tela pausada o shell volta a rodar).
    private var aparelhosNaAbertura: Set<Int>? = null
    private var mandou = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        modo = intent.getStringExtra(EXTRA_MODO).orEmpty()
        window.setBackgroundDrawable(ColorDrawable(0))
        when (modo) {
            MODO_CONSENTIMENTO -> { aparelhosNaAbertura = runCatching { android.view.InputDevice.getDeviceIds().toSet() }.getOrNull(); consentimento() }
            MODO_AJUDA -> ajuda()
            MODO_LEMBRETE -> lembrete()
            MODO_ESTOU_BEM -> estouBem()
            else -> finish()
        }
    }

    /**
     * singleTop: com a tela já aberta, um 2º lembrete, o "Estou bem?" ou o pedido de ajuda chegavam aqui e eram
     * descartados (e o lembrete já estava anotado como mostrado: a família recebia "não confirmado"). Agora a tela se
     * refaz com o pedido novo, salvo um pedido de ajuda em andamento, que nunca é trocado.
     */
    override fun onNewIntent(novo: Intent) {
        super.onNewIntent(novo)
        if (modo == MODO_AJUDA && !mandou) return   // ajuda em andamento: fica (outra ajuda é a mesma)
        val novoModo = novo.getStringExtra(EXTRA_MODO).orEmpty()
        val chave = novo.getStringExtra(EXTRA_CHAVE).orEmpty()
        if (CuidarDeLonge.esperaNaFila(modo, novoModo)) {
            // O mesmo pedido repetido não entra duas vezes (nem o que já está na tela).
            if (chave != intent.getStringExtra(EXTRA_CHAVE) && pendentes.none { it.getStringExtra(EXTRA_CHAVE) == chave }) pendentes.addLast(novo)
            return
        }
        // Ajuda/consentimento passam na frente: o lembrete da tela volta depois, em vez de sumir.
        if (CuidarDeLonge.esperaNaFila(modo, modo)) pendentes.addFirst(intent)
        setIntent(novo)
        recreate()
    }

    // Com a pergunta do consentimento na tela, o shell do Claude não roda (não pode imitar o "Aceito" do morador).
    override fun onResume() { super.onResume(); if (modo == MODO_CONSENTIMENTO) CuidarDeLongeTv.consentimentoNaTela = true }
    override fun onPause() { super.onPause(); CuidarDeLongeTv.consentimentoNaTela = false }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        if (modo == MODO_AJUDA && !mandou) CuidarDeLongeTv.desistirAjuda()
        // Respondeu (ou fechou): o próximo da fila aparece. recreate() não conta (isFinishing = false).
        if (isFinishing) pendentes.removeFirstOrNull()?.let { prox ->
            runCatching { applicationContext.startActivity(Intent(prox).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }

    // ---------------------------------------------------------------- montagem

    private fun dp(v: Int) = Estilo.dp(this, v)

    private fun botao(rotulo: String, primario: Boolean, acao: (() -> Unit)?): TextView = Estilo.botao(this, rotulo, primario).apply {
        textSize = Estilo.Leitura.PERGUNTA_BOTAO
        minWidth = dp(220)
        setPaddingRelative(dp(Estilo.E3 + 8), dp(18), dp(Estilo.E3 + 8), dp(18))
        if (acao != null) setOnClickListener { acao() }
    }

    /**
     * Cartão no meio, véu escuro atrás. [sobre]: rótulo pequeno em cima ("LEMBRETE · 20:00"). Os botões ficam sempre
     * à vista embaixo; se o texto não couber (consentimento com 3 avisos numa TV 960x540 dp, visto no teste de tela),
     * só o texto rola. [lista] = texto em itens, alinhado à esquerda (centralizado, os "•" ficavam desencontrados).
     */
    private fun montar(sobre: String?, titulo: String, texto: String?, botoes: List<TextView>, foco: TextView?,
                       tituloSp: Float = Estilo.Leitura.PERGUNTA, lista: String? = null, rodape: String? = null) {
        val corpo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            if (sobre != null) addView(Estilo.texto(context, Estilo.Papel.SECAO, sobre).apply { textSize = 18f; gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(Estilo.E2) })
            addView(Estilo.texto(context, Estilo.Papel.TITULO, titulo).apply {
                textSize = tituloSp; gravity = Gravity.CENTER; setLineSpacing(0f, 1.1f)
            })
            if (!texto.isNullOrEmpty()) addView(Estilo.texto(context, Estilo.Papel.LEGENDA, texto).apply {
                textSize = Estilo.Leitura.PERGUNTA_TEXTO; gravity = Gravity.CENTER; setLineSpacing(0f, 1.25f)
                setTextColor(Estilo.TEXTO)
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E3) })
            if (!lista.isNullOrEmpty()) addView(Estilo.texto(context, Estilo.Papel.LEGENDA, lista).apply {
                textSize = Estilo.Leitura.PERGUNTA_ITEM; gravity = Gravity.START; setLineSpacing(0f, 1.2f)
                setTextColor(Estilo.TEXTO)
            }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(Estilo.E2) })
            if (!rodape.isNullOrEmpty()) addView(Estilo.texto(context, Estilo.Papel.LEGENDA, rodape).apply {
                textSize = Estilo.Leitura.PERGUNTA_ITEM; gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E2) })
        }
        val altura = resources.displayMetrics.heightPixels
        val rolagem = object : android.widget.ScrollView(this) {
            // O texto pode ocupar no máximo o que sobra da tela depois dos botões e das margens.
            override fun onMeasure(w: Int, h: Int) =
                super.onMeasure(w, MeasureSpec.makeMeasureSpec((altura - dp(if (botoes.isEmpty()) 120 else 230)).coerceAtLeast(dp(120)), MeasureSpec.AT_MOST))
        }.apply { isVerticalScrollBarEnabled = false; isFocusable = false; addView(corpo) }
        val cartao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false; clipToPadding = false
            setPaddingRelative(dp(Estilo.E6), dp(Estilo.E3 + 4), dp(Estilo.E6), dp(Estilo.E3 + 4))
            background = Estilo.fundo(context, Estilo.CARTAO, Estilo.RAIO, Estilo.VIDRO_BORDA)
            addView(rolagem, LinearLayout.LayoutParams(-1, -2))
            if (botoes.isNotEmpty()) addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; clipChildren = false
                botoes.forEachIndexed { i, b -> addView(b, LinearLayout.LayoutParams(-2, -2).apply { if (i > 0) marginStart = dp(Estilo.E3) }) }
            }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(Estilo.E3) })
        }
        setContentView(FrameLayout(this).apply {
            setBackgroundColor(Estilo.VEU)
            addView(cartao, FrameLayout.LayoutParams(dp(900), -2, Gravity.CENTER))
        })
        foco?.post { foco.requestFocus() }
    }

    /** Troca a tela por uma frase e um OK, e fecha sozinha depois de [FECHAR_MS]. */
    private fun resultado(titulo: String, texto: String?) {
        val ok = botao(getString(R.string.cuidar_ok), true) { finish() }
        montar(null, titulo, texto, listOf(ok), ok)
        ui.postDelayed({ finish() }, FECHAR_MS)
    }

    private fun bipe() = try {
        ToneGenerator(AudioManager.STREAM_NOTIFICATION, 70).apply { startTone(ToneGenerator.TONE_PROP_BEEP2, 400) }
            .also { t -> ui.postDelayed({ t.release() }, 800) }
        Unit
    } catch (_: Throwable) {}   // sem som (aparelho sem o gerador de tom): o lembrete aparece igual

    // ---------------------------------------------------------------- consentimento

    private fun consentimento() {
        val c = CuidarDeLongeTv.config(this)
        if (CuidarDeLonge.falta(c) != null) { finish(); return }
        val itens = c.avisos.sortedBy { it.ordinal }.joinToString("\n") { a ->
            "•  " + when (a) {
                CuidarDeLonge.Aviso.AJUDA -> getString(R.string.cuidar_item_ajuda, getString(if (c.tecla == CuidarDeLonge.Tecla.OK) R.string.cuidar_tecla_ok else R.string.cuidar_tecla_voltar))
                CuidarDeLonge.Aviso.LEMBRETE -> getString(R.string.cuidar_item_lembrete, c.prazoMin)
                CuidarDeLonge.Aviso.NAO_LIGOU -> getString(R.string.cuidar_item_nao_ligou)
                CuidarDeLonge.Aviso.ESTOU_BEM -> getString(R.string.cuidar_item_bem)
            }
        }
        val nao = botao(getString(R.string.cuidar_nao_aceito), false) { finish() }
        // Sem OnClickListener de propósito: só a tecla física do controle aceita (dispatchKeyEvent). Um clique de
        // acessibilidade (tocar_elemento) cai aqui e não faz nada.
        val sim = botao(getString(R.string.cuidar_aceito), true, null)
        aceito = sim
        montar(getString(R.string.cuidar_titulo).uppercase(), getString(R.string.cuidar_pergunta_consentimento, c.nome), null,
            listOf(nao, sim), nao, tituloSp = Estilo.Leitura.PERGUNTA_MENOR, lista = itens, rodape = getString(R.string.cuidar_pode_desligar))
    }

    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        val sim = aceito
        if (modo == MODO_CONSENTIMENTO && sim != null && sim.isFocused && SegurarOk.ehOk(e.keyCode)) {
            if (e.action == KeyEvent.ACTION_UP) {
                // Controle de verdade: aparelho com id próprio. -1 (VIRTUAL_KEYBOARD) é o `input keyevent` do adb/painel.
                val fisica = CuidarDeLonge.teclaDeGente(e.deviceId, e.source, KeyCharacterMap.VIRTUAL_KEYBOARD, android.view.InputDevice.SOURCE_HDMI) &&
                    CuidarDeLonge.aparelhoDeAntes(e.deviceId, aparelhosNaAbertura)   // teclado falso criado depois: não
                if (fisica) {
                    CuidarDeLongeTv.consentir(this)
                    aceito = null
                    resultado(getString(R.string.cuidar_ligado_titulo), getString(R.string.cuidar_ligado, CuidarDeLongeTv.config(this).nome))
                } else AvisoTv.rapido(this, getString(R.string.cuidar_titulo), getString(R.string.cuidar_so_controle))
            }
            return true
        }
        return super.dispatchKeyEvent(e)
    }

    // ---------------------------------------------------------------- 77: ajuda

    private fun ajuda() {
        val c = CuidarDeLongeTv.config(this)
        if (!CuidarDeLongeTv.ativo(this, CuidarDeLonge.Aviso.AJUDA)) { finish(); return }
        val desistir = botao(getString(R.string.cuidar_desistir), false) { finish() }
        val mandar = botao(getString(R.string.cuidar_mandar), true) { mandarAjuda(c.nome) }
        montar(null, getString(R.string.cuidar_pergunta_ajuda, c.nome), getString(R.string.cuidar_ajuda_explica, c.nome), listOf(desistir, mandar), mandar)
    }

    private fun mandarAjuda(nome: String) {
        mandou = true
        montar(null, getString(R.string.cuidar_mandando), null, emptyList(), null)
        val app = applicationContext
        Fundo.rodar("cuidar-ajuda") {
            val (aceitos, total) = try { CuidarDeLongeTv.mandarAjuda(app) } catch (e: Exception) { 0 to 0 }
            ui.post {
                if (isFinishing || isDestroyed) return@post
                if (aceitos > 0) resultado(getString(R.string.cuidar_avisado, nome), getString(R.string.cuidar_pode_esperar))
                else resultado(getString(R.string.cuidar_nao_avisado, nome),
                    getString(if (total == 0) R.string.cuidar_sem_aparelho else R.string.cuidar_sem_internet))
            }
        }
    }

    // ---------------------------------------------------------------- 79 da Play: estou bem

    /** Primeira vez que a TV acende no dia: "Avisar Ana que está tudo bem?". Foco em "Estou bem"; VOLTAR = agora não. */
    private fun estouBem() {
        val chave = intent.getStringExtra(EXTRA_CHAVE).orEmpty()
        val c = CuidarDeLongeTv.config(this)
        if (chave.isEmpty() || !CuidarDeLongeTv.ativo(this, CuidarDeLonge.Aviso.ESTOU_BEM)) { finish(); return }
        val agoraNao = botao(getString(R.string.cuidar_bem_nao), false) { finish() }
        val bem = botao(getString(R.string.cuidar_bem_sim), true) {
            mandou = true
            montar(null, getString(R.string.cuidar_mandando), null, emptyList(), null)
            val app = applicationContext
            Fundo.rodar("cuidar-bem") {
                val (aceitos, total) = try { CuidarDeLongeTv.mandarEstouBem(app, chave) } catch (e: Exception) { 0 to 0 }
                ui.post {
                    if (isFinishing || isDestroyed) return@post
                    if (aceitos > 0) resultado(getString(R.string.cuidar_avisado, c.nome), null)
                    else resultado(getString(R.string.cuidar_nao_avisado, c.nome),
                        getString(if (total == 0) R.string.cuidar_sem_aparelho else R.string.cuidar_sem_internet))
                }
            }
        }
        montar(null, getString(R.string.cuidar_bem_pergunta, c.nome), null, listOf(agoraNao, bem), bem)
    }

    // ---------------------------------------------------------------- 78: lembrete

    private fun lembrete() {
        val chave = intent.getStringExtra(EXTRA_CHAVE).orEmpty()
        val texto = intent.getStringExtra(EXTRA_TEXTO).orEmpty()
        if (chave.isEmpty() || texto.isEmpty()) { finish(); return }
        AcenderTela.em(this)
        val momento = intent.getLongExtra(EXTRA_MOMENTO, System.currentTimeMillis())
        val c = CuidarDeLongeTv.config(this)
        val depois = botao(getString(R.string.cuidar_depois), false) { finish() }
        val feito = botao(getString(R.string.cuidar_feito), true) {
            CuidarDeLongeTv.confirmar(this, chave)
            finish()
        }
        val explica = if (CuidarDeLongeTv.ativo(this, CuidarDeLonge.Aviso.LEMBRETE)) getString(R.string.cuidar_lembrete_explica_aviso, c.prazoMin, c.nome)
            else getString(R.string.cuidar_lembrete_explica)
        montar(getString(R.string.cuidar_lembrete_sobre, CuidarDeLonge.textoHora(momento, java.util.TimeZone.getDefault())).uppercase(),
            texto, explica, listOf(depois, feito), feito)
        bipe()
    }
}
