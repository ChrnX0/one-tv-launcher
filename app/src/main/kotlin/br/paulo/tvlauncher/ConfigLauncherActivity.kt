package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors

private const val PEDIDO_RECURSOS = 41   // código do requestPermissions dos recursos (Recursos.kt)

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/**
 * "Configurações do launcher": tudo que é nosso (não do sistema). Aberta pelo tile "Launcher" do painel rápido (ver
 * PainelRapidoActivity.kt).
 *
 * 07/11/2026 (pedido do dono: "aquela área é bem feia e desorganizada"): refeita em ABAS, no jeito das Configurações do
 * próprio Google TV. À esquerda, o menu das abas; à direita, as opções da aba em foco. Opção de liga/desliga tem uma
 * chave (bolinha que corre para a direita quando liga); opção de escolher mostra o valor à direita; opção que abre outra
 * tela tem uma seta. Embaixo, a dica fixa das teclas, no mesmo estilo das dicas do player.
 *
 * Abas novas: "Conta" (os dados da conta de IPTV, como a área de usuário do 1-Stream, ver ContaIptv.kt) e
 * "Controle parental" (canais bloqueados com o PIN, ver ControleParental.kt). O resto são as opções que já existiam,
 * só agrupadas.
 */
class ConfigLauncherActivity : Activity() {
    companion object {
        const val EXTRA_ABA = "aba"   // índice da aba para abrir (ex.: o atalho "Conta" de outra tela)
        private const val PIN_VALE_MS = 2 * 60_000L   // depois de acertar o PIN aqui, 2 min sem pedir de novo
    }

    private val trabalho = Executors.newSingleThreadExecutor()   // rede, PBKDF2 e PackageManager fora da thread da tela
    /** Leituras locais rápidas (contas de TV no Keystore): fila separada, para não esperar uma consulta de rede da `trabalho`. */
    private val leitura = Executors.newSingleThreadExecutor()
    @Volatile private var medindoVelocidade = false
    private val ui = Handler(Looper.getMainLooper())

    private val textoClaro = Estilo.TEXTO     // cores do sistema de design
    private val textoSuave = Estilo.TEXTO_2
    private val apagado = Estilo.TEXTO_3
    private val destaque = Estilo.ACENTO
    private val ambar = Estilo.ATENCAO
    private val coral = Estilo.ERRO
    private val fonteLeve = Fontes.manrope(200)
    private val fonteMedia = Fontes.manrope(500)

    /** Uma aba do menu: título, explicação curta (aparece no alto do conteúdo) e quem monta o conteúdo. */
    private class Aba(val titulo: String, val explica: String, val montar: (LinearLayout) -> Unit)

    private val abas: List<Aba> by lazy {
        listOf(
            Aba("Conta", "A conta de IPTV cadastrada na TV: situação, validade e conexões", this::abaConta),
            Aba("Controle parental", "Bloqueie canais com o PIN: eles somem da lista e não tocam", this::abaParental),
            Aba("Aparência", "Tema, pilhas de apps, clima e avisos na tela", this::abaAparencia),
            Aba("Tela e energia", "Protetor de tela e desligar sozinha", this::abaEnergia),
            Aba("Visitas e rotinas", "Modo visita e ações por horário", this::abaVisitas),
            Aba("Rede", "DNS, VPN e velocidade da internet", this::abaRede),
            Aba("Acesso remoto", "Painel no navegador, PIN e o Claude pela internet", this::abaRemoto),
            Aba("Sistema", "Diagnóstico, atualização, apps e permissões", this::abaSistema),
        )
    }

    private var abaAtual = 0
    private var abaMontada = -1   // qual aba está desenhada no conteúdo agora
    private lateinit var menu: LinearLayout
    private lateinit var conteudo: LinearLayout
    private lateinit var rolagem: ScrollView
    private lateinit var dica: FrameLayout
    private val itensMenu = ArrayList<TextView>()
    private var reconstruirAoVoltar = false   // voltou de outra tela (Android, canais bloqueados): redesenha a aba
    private var pinValidoAte = 0L
    // Erros do PIN guardados (TentativasPin, como no player): fechar e abrir as Configurações não dá mais 5 tentativas.
    // Preferência própria do processo da HOME ("config_pin"); a do player ("player_pin") é do processo ":player".
    private val prefsPin by lazy { getSharedPreferences("config_pin", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        abaAtual = (savedInstanceState?.getInt(EXTRA_ABA) ?: intent.getIntExtra(EXTRA_ABA, 0)).coerceIn(0, abas.size - 1)
        setContentView(montarTela())
        mostrarAba(abaAtual)
        itensMenu[abaAtual].post { itensMenu[abaAtual].requestFocus() }
    }

    override fun onSaveInstanceState(estado: Bundle) {
        super.onSaveInstanceState(estado)
        estado.putInt(EXTRA_ABA, abaAtual)
    }

    override fun onDestroy() {
        super.onDestroy()
        ui.removeCallbacksAndMessages(null)
        trabalho.shutdownNow()
        leitura.shutdownNow()
    }

    override fun onStop() {
        super.onStop()
        pinValidoAte = 0L   // saiu da tela: o PIN vale de novo só depois de digitar
    }

    override fun onRestart() {
        super.onRestart()
        if (reconstruirAoVoltar) { reconstruirAoVoltar = false; recarregarAba() }
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onRequestPermissionsResult(codigo: Int, permissoes: Array<out String>, resultados: IntArray) {
        if (codigo == PEDIDO_RECURSOS) recarregarAba()
    }

    // ================================================================ esqueleto: menu à esquerda, conteúdo à direita

    private fun montarTela(): View {
        val raiz = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = Estilo.fundoTela(context)   // o grafite da HOME (sistema de design)
            setPadding(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL - 7))   // a mesma margem da HOME
            // A pílula com foco cresce 2% e passa um pouco da margem: sem isto a borda esquerda dela saía cortada (TV, 10/11/2026).
            clipChildren = false; clipToPadding = false
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.cfg_titulo)))

        val colunas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPadding(0, dp(4), 0, dp(4)) }
        abas.forEachIndexed { i, aba -> menu.addView(itemMenu(i, aba), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }) }
        colunas.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(menu) },
            LinearLayout.LayoutParams(dp(270), MATCH))
        // Sem linha separando menu e conteúdo (nenhuma outra tela tem): o espaço já separa as duas colunas.
        colunas.addView(View(this), LinearLayout.LayoutParams(dp(Estilo.E3 + 8), MATCH))
        conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false; clipToPadding = false
            setPadding(dp(12), dp(4), dp(24), dp(24))
        }
        rolagem = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false; clipChildren = false; isSmoothScrollingEnabled = true; addView(conteudo)
            // Corta em cima e embaixo (o conteúdo não passa por cima do título nem da barra de dicas), mas deixa 16 dp de folga
            // dos lados para a linha com foco crescer sem ser cortada. A raiz não corta mais na margem (ver acima).
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.clipBounds = android.graphics.Rect(-dp(16), 0, v.width + dp(16), v.height) }
        }
        colunas.addView(rolagem, LinearLayout.LayoutParams(0, MATCH, 1f))
        raiz.addView(colunas, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(18) })

        // Dica fixa das teclas (mesmo jeito das dicas do player: 11sp, cor apagada). Muda com o lado em foco.
        // Barra de dicas do controle (componente padrão do sistema de design). Muda com o lado em foco.
        dica = FrameLayout(this).apply { setPadding(0, dp(12), 0, 0) }
        raiz.addView(dica)
        mostrarDica(noMenu = true)
        return raiz
    }

    private fun itemMenu(i: Int, aba: Aba): TextView = TextView(this).apply {
        text = aba.titulo
        Estilo.aplicar(this, Estilo.Papel.CORPO)
        setPadding(dp(20), dp(11), dp(20), dp(11))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        isFocusable = true; isClickable = true
        pintarItemMenu(this, foco = false, escolhido = i == abaAtual)
        setOnFocusChangeListener { v, temFoco ->
            if (temFoco && abaAtual != i) {
                abaAtual = i
                // Espera um instante: descer o menu rápido não monta todas as abas no caminho.
                ui.removeCallbacks(trocarAba)
                ui.postDelayed(trocarAba, 140)
            }
            itensMenu.forEachIndexed { j, t -> pintarItemMenu(t, foco = t.hasFocus(), escolhido = j == abaAtual) }
            if (temFoco) mostrarDica(noMenu = true)
            v.animate().scaleX(if (temFoco) Estilo.ESCALA_LINHA else 1f).scaleY(if (temFoco) Estilo.ESCALA_LINHA else 1f)
                .setDuration(Estilo.FOCO_MS).start()
        }
        setOnClickListener { entrarNoConteudo() }
        itensMenu += this
    }

    /** Menu das abas no sistema de design: foco = pílula branca com texto escuro; aba aberta = pílula clara. */
    private fun pintarItemMenu(t: TextView, foco: Boolean, escolhido: Boolean) {
        t.setTextColor(when { foco -> Estilo.TEXTO_NO_FOCO; escolhido -> Estilo.TEXTO; else -> Estilo.TEXTO_2 })
        t.typeface = Estilo.fonte(if (foco || escolhido) 600 else 500)
        t.background = when {
            foco -> Estilo.fundo(this, Estilo.FOCO, Estilo.RAIO_PILULA)
            escolhido -> Estilo.fundo(this, Estilo.SUPERFICIE_ALTA, Estilo.RAIO_PILULA)
            else -> null
        }
    }

    private val trocarAba = Runnable { mostrarAba(abaAtual) }

    private fun mostrarAba(i: Int) {
        abaMontada = i
        conteudo.removeAllViews()
        rolagem.scrollTo(0, 0)
        val aba = abas[i]
        conteudo.addView(Estilo.texto(this, Estilo.Papel.TITULO, aba.titulo).apply { setPadding(dp(8), dp(4), 0, 0) })
        conteudo.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, aba.explica).apply { setPadding(dp(8), dp(6), 0, dp(12)) })
        aba.montar(conteudo)
    }

    /** Redesenha a aba atual no lugar (depois de voltar de outra tela ou de mudar algo que mexe em várias linhas). */
    private fun recarregarAba() {
        val tinhaFocoNoConteudo = conteudo.hasFocus()
        mostrarAba(abaAtual)
        if (tinhaFocoNoConteudo) conteudo.post { entrarNoConteudo() }
    }

    private fun entrarNoConteudo() {
        ui.removeCallbacks(trocarAba)
        if (abaMontada != abaAtual) mostrarAba(abaAtual)
        primeiroFocavel(conteudo)?.requestFocus()
    }

    private fun primeiroFocavel(v: View): View? {
        if (v.visibility != View.VISIBLE) return null
        if (v.isFocusable) return v
        if (v is ViewGroup) for (k in 0 until v.childCount) primeiroFocavel(v.getChildAt(k))?.let { return it }
        return null
    }

    private fun View.dentroDe(pai: View): Boolean {
        var v: View? = this
        while (v != null) { if (v === pai) return true; v = v.parent as? View }
        return false
    }

    private fun mostrarDica(noMenu: Boolean) {
        dica.removeAllViews()
        dica.addView(if (noMenu) Estilo.barraDicas(this, "▲▼" to "escolher a aba", "▶ OK" to "entrar nas opções", "VOLTAR" to "sair")
            else Estilo.barraDicas(this, "▲▼" to "escolher", "OK" to "mudar ou abrir", "◀ VOLTAR" to "voltar às abas"))
    }

    private var engoliuVoltar = false   // o VOLTAR que só levou o foco ao menu não pode fechar a tela ao soltar

    /** ◀ nas opções volta ao menu; ▶ no menu entra nas opções; ▲▼ não escapam de um lado para o outro. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (e.keyCode == KeyEvent.KEYCODE_BACK && e.action == KeyEvent.ACTION_UP && engoliuVoltar) { engoliuVoltar = false; return true }
        if (e.action == KeyEvent.ACTION_DOWN) {
            val foco = currentFocus
            val noConteudo = foco != null && foco.dentroDe(conteudo)
            val noMenu = foco != null && foco.dentroDe(menu)
            when (e.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> if (noConteudo) { itensMenu[abaAtual].requestFocus(); return true }
                KeyEvent.KEYCODE_BACK -> if (noConteudo) { engoliuVoltar = true; itensMenu[abaAtual].requestFocus(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (noMenu) { entrarNoConteudo(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (foco != null && (noConteudo || noMenu)) {
                    val dir = if (e.keyCode == KeyEvent.KEYCODE_DPAD_UP) View.FOCUS_UP else View.FOCUS_DOWN
                    val prox = foco.focusSearch(dir)
                    if (prox == null || !prox.dentroDe(if (noConteudo) conteudo else menu)) {
                        // Sem mais opção para aquele lado: no conteúdo, rola para mostrar o texto que sobra (ex.: "Como funciona").
                        if (noConteudo) rolagem.smoothScrollBy(0, if (dir == View.FOCUS_UP) -dp(240) else dp(240))
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(e)
    }

    // ================================================================ aba: Conta

    private fun abaConta(c: LinearLayout) {
        // As contas são decifradas pelo Keystore (IPC): leitura fora da thread da tela (auditoria 10/11). A seção
        // "Gerenciar contas" já entra embaixo; as contas aparecem no lugar reservado quando a leitura terminar.
        val topo = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        c.addView(topo, LinearLayout.LayoutParams(MATCH, WRAP))
        leitura.execute {
            val fontes = PlayerArmazem.fontes(this)
            ui.post { if (!isDestroyed) contasNoTopo(topo, fontes) }
        }
        // Gerenciar: cadastrar, apagar, testar (o que antes era a seção "TV ao vivo").
        secao(c, "Gerenciar contas")
        val caixaContas = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        c.addView(caixaContas, LinearLayout.LayoutParams(MATCH, WRAP))
        montarContasTv(caixaContas)

        // Áudio de filmes e séries (Idioma, 11/11/2026). OK alterna. Cada filme ou série lembra a troca feita no player.
        secao(c, getString(R.string.idioma_secao))
        var atual = Idioma.Pref.DUBLADO
        val linhaAudio = linha(getString(R.string.idioma_titulo), getString(R.string.idioma_det), valor = "…") {}
        linhaAudio.setOnClickListener {
            atual = if (atual == Idioma.Pref.ORIGINAL) Idioma.Pref.DUBLADO else Idioma.Pref.ORIGINAL
            linhaAudio.valor(IdiomaPlayer.rotulo(this, atual))
            val nova = atual
            leitura.execute { IdiomaPlayer.definirPadrao(this, nova) }
        }
        c.addView(linhaAudio)
        // Legenda da internet: a chave de API e a conta do OpenSubtitles são do dono (o launcher é público, não leva chave).
        lateinit var linhaLeg: LinhaCfg
        linhaLeg = linha(getString(R.string.legenda_conta_titulo), getString(R.string.legenda_conta_det), valor = "…") {
            pedirContaLegenda { tem -> linhaLeg.valor(getString(if (tem) R.string.legenda_conta_sim else R.string.legenda_conta_nao)) }
        }
        c.addView(linhaLeg)
        leitura.execute {
            val p = IdiomaPlayer.prefs(this).padrao
            val tem = LegendaBaixar.temConta(this)
            ui.post {
                if (isDestroyed) return@post
                atual = p; linhaAudio.valor(IdiomaPlayer.rotulo(this, p))
                linhaLeg.valor(getString(if (tem) R.string.legenda_conta_sim else R.string.legenda_conta_nao))
            }
        }
    }

    /**
     * Chave de API (obrigatória) e conta (opcional: com ela o limite diário de downloads sobe) do OpenSubtitles. A chave
     * não volta preenchida: fica cifrada e só o player a lê. Gravar com a chave vazia apaga.
     */
    private fun pedirContaLegenda(erro: String? = null, aoGravar: (Boolean) -> Unit) {
        val T = android.text.InputType.TYPE_CLASS_TEXT
        fun campo(dica: String, tipo: Int) = android.widget.EditText(this).apply {
            inputType = tipo; hint = dica; setSingleLine(); filters = arrayOf(android.text.InputFilter.LengthFilter(100))
        }
        val chave = campo("Chave da API (opensubtitles.com/consumers)", T or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val usuario = campo("Usuário do OpenSubtitles (opcional)", T or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val senha = campo("Senha (opcional)", T or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
            if (erro != null) addView(Estilo.texto(this@ConfigLauncherActivity, Estilo.Papel.LEGENDA, erro).apply { setTextColor(coral) })
            addView(chave); addView(usuario); addView(senha)
        }
        CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(R.string.legenda_conta_titulo))
            .setView(caixa)
            .setPositiveButton("Gravar") { _, _ ->
                val conta = LegendaBaixar.Conta(chave.text.toString(), usuario.text.toString(), senha.text.toString())
                leitura.execute {
                    val r = LegendaBaixar.definirConta(this, conta)
                    val tem = LegendaBaixar.temConta(this)
                    ui.post {
                        if (isDestroyed) return@post
                        r.onSuccess { aoGravar(tem) }.onFailure { pedirContaLegenda("Não gravei: ${it.message}", aoGravar) }
                    }
                }
            }
            .setNeutralButton("Apagar") { _, _ ->
                leitura.execute { LegendaBaixar.definirConta(this, null); ui.post { if (!isDestroyed) aoGravar(false) } }
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun contasNoTopo(c: LinearLayout, fontes: List<FonteTv>) {
        if (fontes.isEmpty()) {
            c.addView(cartao().apply {
                addView(valor("Nenhuma conta cadastrada", "cadastre abaixo a conta que o vendedor do IPTV mandou"))
            }, lp())
            // Sem lista ainda (11/11/2026, visto no 1 Play): um vídeo livre para ver o player funcionando antes de cadastrar.
            c.addView(linha("Ver um vídeo de demonstração", "Sintel, da Blender Foundation (licença CC BY 3.0). Precisa de internet", seta = true) {
                VodActivity.abrirUrl(this, Config.VIDEO_DEMONSTRACAO, "Sintel · Blender Foundation · CC BY 3.0")
            })
        }
        for (f in fontes) {
            secao(c, f.nome.ifEmpty { "Conta" })
            val cartao = cartao().apply {
                // Focável (sem clique) só para o ▲ conseguir voltar até ele: senão o quadro sumia acima da tela.
                isFocusable = true
                setOnFocusChangeListener { v, temFoco -> v.background = vidro(temFoco); if (temFoco) mostrarDica(noMenu = false) }
            }
            c.addView(cartao, lp())
            fun preencher(s: Xtream.Situacao?, consultou: Boolean) {
                cartao.removeAllViews()
                for (l in ContaIptv.linhas(f, s, consultou, System.currentTimeMillis()))
                    cartao.addView(valor(l.rotulo, l.valor, if (l.alerta) coral else null))
            }
            preencher(null, consultou = false)
            val podeConsultar = ContaIptv.contaConsultavel(f) != null
            if (podeConsultar) {
                // Primeiro mostra o que já se sabe (nome, usuário, tipo); a situação chega quando o servidor responder.
                (cartao.getChildAt(cartao.childCount - 1) as? LinearLayout)?.let { (it.getChildAt(1) as? TextView)?.text = "consultando o servidor…" }
                lateinit var linhaConsultar: LinhaCfg
                fun consultar() {
                    linhaConsultar.detalhe("consultando o servidor…")
                    trabalho.execute {
                        val s = PlayerCarga.situacao(f)
                        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        ui.post {
                            if (isDestroyed) return@post
                            preencher(s, consultou = true)
                            linhaConsultar.detalhe(if (s != null) "atualizado às $hora · OK consulta de novo" else "sem resposta às $hora · OK tenta de novo")
                        }
                    }
                }
                linhaConsultar = linha("Atualizar dados da conta", "consulta o servidor de novo", seta = true) { consultar() }
                c.addView(linhaConsultar, lp())
                consultar()
            }
        }
    }

    /** Lista de contas com apagar, cadastrar e testar. Refeita no lugar depois de mudar, sem perder a tela. */
    private fun montarContasTv(caixa: LinearLayout) {
        leitura.execute {   // Keystore fora da thread da tela (auditoria 10/11)
            val contas = PlayerArmazem.fontes(this)
            ui.post { if (!isDestroyed) montarContasTv(caixa, contas) }
        }
    }

    private fun montarContasTv(caixa: LinearLayout, contas: List<FonteTv>) {
        caixa.removeAllViews()
        fun adicionar(v: View) = caixa.addView(v, lp())
        for (f in contas) {
            adicionar(linha(f.nome, "${ContaIptv.tipoLogin(f)} · ${f.servidorVisivel}", valor = "Apagar") {
                // Com o controle parental ligado, apagar e cadastrar pedem o PIN: cadastrar de novo muda o id dos canais e
                // os bloqueados por canal deixariam de valer.
                exigirPinSeParental {
                    CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Apagar a conta \"${f.nome}\"?")
                        .setMessage("Os canais dela somem da TV ao vivo. Para voltar, é preciso cadastrar de novo.")
                        .setPositiveButton("Apagar") { _, _ ->
                            PlayerArmazem.apagar(this, f.id)
                            recarregarAba()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            })
        }
        if (contas.size < 8) {
            adicionar(linha("Cadastrar conta Xtream", "servidor, usuário e senha (o que o vendedor do IPTV manda)", seta = true) {
                exigirPinSeParental { pedirContaTv(FonteTv.TIPO_XTREAM) }
            })
            adicionar(linha("Cadastrar lista de canais (M3U)", "um link .m3u (http:// ou https://)", seta = true) {
                exigirPinSeParental { pedirContaTv(FonteTv.TIPO_M3U) }
            })
        }
        if (contas.isNotEmpty()) {
            lateinit var linhaTestar: LinhaCfg
            linhaTestar = linha("Testar e recarregar canais", "baixa a lista e o guia de novo e conta os canais", seta = true) {
                linhaTestar.detalhe("baixando… (pode levar até um minuto)")
                trabalho.execute {
                    val agora = System.currentTimeMillis()
                    val semCanal = PlayerArmazem.fontes(this).count { PlayerCarga.canaisDaFonte(this, it, forcar = true).isEmpty() }
                    val canais = PlayerCarga.canais(this)
                    val guia = PlayerCarga.guia(this, canais, forcar = true)
                    val comGuia = guia.comProgramaAgora(agora).let { no -> canais.count { it.guiaId in no } }.also { guia.fechar() }   // 1 consulta; solta o banco
                    val texto = "${canais.size} canais, $comGuia com programação agora" +
                        (if (semCanal > 0) " · $semCanal conta(s) sem canal: confira servidor, usuário e senha" else "")
                    ui.post { if (!isDestroyed) linhaTestar.detalhe(texto) }
                }
            }
            adicionar(linhaTestar)
        }
    }

    /**
     * Caixa para cadastrar uma conta com o teclado da TV (AlertDialog: OK confirma, Voltar cancela). Xtream: servidor,
     * usuário e senha; lista: o link. O nome é opcional. Erro de validação reabre a caixa com o que já foi digitado.
     */
    private fun pedirContaTv(tipo: String, antes: List<String>? = null, erro: String? = null) {
        val xtream = tipo == FonteTv.TIPO_XTREAM
        fun campo(dica: String, max: Int, tipoTexto: Int, valor: String?) = android.widget.EditText(this).apply {
            inputType = tipoTexto
            filters = arrayOf(android.text.InputFilter.LengthFilter(max))
            hint = dica; setText(valor.orEmpty()); setSingleLine()
        }
        val T = android.text.InputType.TYPE_CLASS_TEXT
        val nome = campo("Nome da lista (opcional, ex.: Playon)", 40, T, antes?.getOrNull(0))
        val endereco = campo(if (xtream) "Servidor (ex.: http://servidor:8080)" else "Link da lista (http://…)", 2000,
            T or android.text.InputType.TYPE_TEXT_VARIATION_URI, antes?.getOrNull(1))
        val usuario = campo("Usuário", 200, T or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, antes?.getOrNull(2))
        // A senha não volta preenchida num erro: digitar de novo é mais seguro que deixá-la na memória da caixa.
        val senha = campo("Senha", 200, T or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, null)
        // Outros endereços do mesmo painel (o vendedor costuma mandar 2 ou 3): usados se o principal cair.
        val reservas = campo("Servidores reserva (opcional, separados por vírgula)", 600,
            T or android.text.InputType.TYPE_TEXT_VARIATION_URI, antes?.getOrNull(3))
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            if (erro != null) addView(Estilo.texto(this@ConfigLauncherActivity, Estilo.Papel.LEGENDA, erro).apply { setTextColor(coral) })
            addView(endereco)
            if (xtream) { addView(usuario); addView(senha); addView(reservas) }
            addView(nome)
        }
        CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(if (xtream) "Conta Xtream" else "Lista de canais")
            .setView(caixa)
            .setPositiveButton("Cadastrar") { _, _ ->
                val r = PlayerArmazem.adicionar(this, nome.text.toString(), tipo, endereco.text.toString(),
                    usuario.text.toString(), senha.text.toString(), reservas = reservas.text.toString())
                r.onSuccess { recarregarAba() }.onFailure {
                    pedirContaTv(tipo, listOf(nome.text.toString(), endereco.text.toString(), usuario.text.toString(), reservas.text.toString()),
                        "Não cadastrei: ${it.message}")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
        endereco.requestFocus()
    }

    // ================================================================ aba: Controle parental

    private fun abaParental(c: LinearLayout) {
        if (!PainelArmazem.temPin(this)) {
            c.addView(cartao().apply {
                addView(valor("Precisa de um PIN", "o controle parental usa o mesmo PIN do painel no navegador; defina um primeiro"))
            }, lp())
            c.addView(linha("Definir o PIN", "${PinSeguro.MIN_DIGITOS} a ${PinSeguro.MAX_DIGITOS} números, digitados duas vezes", seta = true) {
                definirNovoPin(null) { recarregarAba() }
            }, lp())
            return
        }
        val r = PlayerArmazem.parental(this)
        fun resumo(r: ControleParental.Regras) = when {
            r.vazio -> "nenhum canal escolhido ainda"
            else -> "${r.canais.size} canal(is) e ${r.grupos.size} categoria(s) inteira(s) escolhidos"
        }
        lateinit var chave: LinhaCfg
        chave = linha("Controle parental", if (r.ligado) "ligado · ${resumo(r)}" else "desligado · ${resumo(r)}", chave = r.ligado) {
            pedirPinParental {
                val atual = PlayerArmazem.parental(this)
                val nova = atual.copy(ligado = !atual.ligado)
                if (PlayerArmazem.definirParental(this, nova)) {
                    chave.ligado(nova.ligado)
                    chave.detalhe(if (nova.ligado) "ligado · ${resumo(nova)}" else "desligado · ${resumo(nova)}")
                } else chave.detalhe("não consegui gravar · tente de novo")
            }
        }
        c.addView(chave, lp())
        c.addView(linha("Escolher canais bloqueados", "por canal ou por categoria inteira", seta = true) {
            pedirPinParental {
                reconstruirAoVoltar = true
                startActivity(Intent(this, BloqueioCanaisActivity::class.java))
            }
        }, lp())
        if (!r.vazio) {
            c.addView(linha("Liberar todos os canais", "apaga a lista de bloqueados (o controle continua como está)", seta = true) {
                pedirPinParental {
                    CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle("Liberar todos os canais?")
                        .setMessage("A lista de canais e categorias bloqueados fica vazia.")
                        .setPositiveButton("Liberar") { _, _ ->
                            // copy: o modo infantil e as categorias infantis ficam como estão
                            PlayerArmazem.definirParental(this, PlayerArmazem.parental(this).copy(canais = emptySet(), grupos = emptySet()))
                            recarregarAba()
                        }
                        .setNegativeButton("Cancelar", null)
                        .show()
                }
            }, lp())
        }
        // Modo infantil (10/11/2026): só as categorias marcadas como infantis aparecem na TV ao vivo, Filmes e Séries.
        // Ligar e desligar pedem o PIN; o resto, com o modo ligado, só aparece com o PIN no player (como os bloqueados).
        lateinit var chaveInf: LinhaCfg
        fun detalheInf(r: ControleParental.Regras) = getString(if (r.infantil) R.string.cfg_infantil_ligado else R.string.cfg_infantil_desligado, r.infantis.size)
        chaveInf = linha(getString(R.string.cfg_infantil), detalheInf(r), chave = r.infantil) {
            pedirPinParental {
                val atual = PlayerArmazem.parental(this)
                val nova = atual.copy(infantil = !atual.infantil)
                if (PlayerArmazem.definirParental(this, nova)) { chaveInf.ligado(nova.infantil); chaveInf.detalhe(detalheInf(nova)) }
                else chaveInf.detalhe("não consegui gravar · tente de novo")
            }
        }
        c.addView(chaveInf, lp())
        c.addView(linha(getString(R.string.cfg_infantil_escolher), getString(R.string.cfg_infantil_escolher_det), seta = true) {
            pedirPinParental {
                reconstruirAoVoltar = true
                startActivity(Intent(this, BloqueioCanaisActivity::class.java).putExtra(BloqueioCanaisActivity.EXTRA_INFANTIS, true))
            }
        }, lp())
        secao(c, "Como funciona")
        c.addView(cartao().apply {
            addView(valor("Bloqueado", "some da lista de canais, da grade, da busca, do ▲▼ e do número digitado, e não toca"))
            addView(valor("Para assistir", "no player: OK > Filtros > \"Mostrar categorias ocultas e canais bloqueados\" e o PIN"))
            addView(valor("Tranca de novo", "sozinho, quando o player sai da tela (HOME, outro app ou TV apagada)"))
            addView(valor("PIN", "o mesmo do painel no navegador; trocar o PIN pede o PIN atual (aba Acesso remoto)"))
        }, lp())
    }

    /** Ação que exige o PIN quando o controle parental está valendo (ex.: apagar ou cadastrar conta). */
    private fun exigirPinSeParental(acao: () -> Unit) {
        if (PlayerArmazem.parental(this).ativo) pedirPinParental(acao = acao) else acao()
    }

    /**
     * Pede o PIN do painel e roda [acao] se acertar. Acertou: vale 2 min nesta tela (não pede a cada OK). Do 5º erro
     * em diante, espera crescente (TentativasPin), guardada mesmo saindo da tela. A conferência (PBKDF2) roda fora da thread da tela.
     */
    private fun lerTentativas() = TentativasPin.Estado(prefsPin.getInt("erros", 0), prefsPin.getLong("ate", 0), prefsPin.getLong("ultimo", 0))
    private fun gravarTentativas(e: TentativasPin.Estado) =
        prefsPin.edit().putInt("erros", e.erros).putLong("ate", e.bloqueadoAte).putLong("ultimo", e.ultimoErro).apply()

    private fun pedirPinParental(titulo: String = "PIN do controle parental", acao: () -> Unit) {
        if (!PainelArmazem.temPin(this)) { acao(); return }
        if (System.currentTimeMillis() < pinValidoAte) { acao(); return }
        val espera = TentativasPin.espera(lerTentativas(), System.currentTimeMillis())
        if (espera > 0) { aviso(getString(R.string.player_pin_espere, TentativasPin.minutos(espera))); return }
        pedirPin(titulo) { pin ->
            trabalho.execute {
                val certo = PainelArmazem.conferirPin(this, pin)
                ui.post {
                    if (isDestroyed) return@post
                    if (certo) { gravarTentativas(TentativasPin.acertou()); pinValidoAte = System.currentTimeMillis() + PIN_VALE_MS; acao() }
                    else {
                        val e = TentativasPin.errou(lerTentativas(), System.currentTimeMillis())
                        gravarTentativas(e)
                        val falta = TentativasPin.espera(e, System.currentTimeMillis())
                        aviso(if (falta > 0) getString(R.string.player_pin_espere, TentativasPin.minutos(falta)) else getString(R.string.player_pin_errado))
                    }
                }
            }
        }
    }

    /**
     * Confirmações (Confirmacoes.kt / ConfirmacoesTv.kt, 09/11/2026): o que pergunta na TV antes de agir. "Liberar tudo" é
     * para quem usa de longe; quem não sabe que isto existe fica com os padrões protegidos. Liberar mais pede o PIN;
     * ficar mais seguro, não.
     */
    private fun confirmacoes(c: LinearLayout) {
        secao(c, "Confirmações")
        val r = ConfirmacoesTv.regras(this)
        fun mudar(novas: Confirmacoes.Regras) {
            val gravar = { ConfirmacoesTv.definir(this, novas); recarregarAba() }
            if (Confirmacoes.pedePin(ConfirmacoesTv.regras(this), novas)) pedirPinParental("PIN para liberar", gravar) else gravar()
        }
        c.addView(linha("Liberar tudo (uso avançado)",
            "nada pergunta na TV: para quem usa de longe, pelo painel ou pelo Claude · ligar pede o PIN",
            chave = r.liberarTudo) { mudar(r.copy(liberarTudo = !r.liberarTudo)) }, lp())
        for (a in Confirmacoes.Acao.entries) {
            val atual = Confirmacoes.modo(r, a)
            c.addView(linha(a.titulo, a.explicacao, valor = if (r.liberarTudo) "Liberado" else atual.rotulo) {
                if (r.liberarTudo) aviso("desligue \"Liberar tudo\" para escolher linha por linha")
                else mudar(r.copy(modos = r.modos + (a to Confirmacoes.proximo(a, atual))))
            }, lp())
        }
        c.addView(info("Perguntar na TV", "a TV mostra o pedido com o foco em Negar; sem resposta em 60 s, nega."), lp())
    }

    // ================================================================ aba: Aparência

    private fun abaAparencia(c: LinearLayout) {
        lateinit var linhaTema: LinhaCfg
        linhaTema = linha("Tema", getString(R.string.cfg_tema_det), valor = Tema.atual(this).nome) {
            Tema.proximo(this)
            linhaTema.valor(Tema.atual(this).nome)
            // A MainActivity só relê o tema no onCreate: ela mesma percebe a troca e se recria ao voltar (ver onResume lá).
        }
        c.addView(linhaTema, lp())
        // Pilhas automáticas (05/11/2026, Organizacao.agruparPorCategoria): a HOME relê ao voltar (preencherApps no onResume).
        lateinit var linhaPilhas: LinhaCfg
        linhaPilhas = linha(getString(R.string.cfg_pilhas), "junta os apps soltos por categoria; as suas pilhas continuam",
            chave = Organizacao.pilhasAutomaticas(this)) {
            Organizacao.definirPilhasAutomaticas(this, !Organizacao.pilhasAutomaticas(this))
            linhaPilhas.ligado(Organizacao.pilhasAutomaticas(this))
        }
        c.addView(linhaPilhas, lp())

        secao(c, "Clima")
        lateinit var linhaEndereco: LinhaCfg
        fun detalheEndereco() = LocalClima.ler(this)?.let { LocalClima.descrever(it) }
            ?: "nenhum · a TV se acha pelo Wi-Fi; o endereço vale quando o Wi-Fi não acha"
        linhaEndereco = linha("Endereço do clima", detalheEndereco(), seta = true) {
            pedirEndereco { cep, numero ->
                linhaEndereco.detalhe("procurando o endereço…")
                trabalho.execute {
                    val r = LocalClima.definirPorCep(this, cep, numero)
                    ui.post { if (!isDestroyed) linhaEndereco.detalhe(r.fold({ detalheEndereco() }, { "não deu: ${it.message} · OK tenta de novo" })) }
                }
            }
        }
        c.addView(linhaEndereco, lp())
        // Aviso de chuva por cima de qualquer app (AvisoChuva.kt). O recado vem pelo painel e não tem chave aqui.
        lateinit var linhaChuva: LinhaCfg
        linhaChuva = linha("Aviso de chuva",
            "avisa por cima do app quando a chance passa de ${AvisoChuva.LIMIAR}% nas próximas ${AvisoChuva.HORAS_A_FRENTE} h",
            chave = AvisoChuva.ligado(this)) {
            AvisoChuva.definirLigado(this, !AvisoChuva.ligado(this))
            linhaChuva.ligado(AvisoChuva.ligado(this))
        }
        c.addView(linhaChuva, lp())
    }

    // ================================================================ aba: Tela e energia

    private fun abaEnergia(c: LinearLayout) {
        // OK passa para a próxima opção da lista e grava na hora.
        val opcoes = listOf(60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000, Int.MAX_VALUE)
        fun rotuloTempo(ms: Int) = if (ms == Int.MAX_VALUE) getString(R.string.cfg_nunca) else getString(R.string.cfg_min, ms / 60_000)
        lateinit var linhaDescanso: LinhaCfg
        linhaDescanso = linha("Protetor de tela", "tempo parado até o protetor aparecer · OK muda",
            valor = rotuloTempo(PrivilegioDO.tempoDescanso(this))) {
            val atual = PrivilegioDO.tempoDescanso(this)
            // Próxima opção depois da atual; se o valor atual não estiver na lista (mudado por fora), começa da primeira.
            val proximo = opcoes[(opcoes.indexOf(atual) + 1) % opcoes.size]
            if (PrivilegioDO.definirTempoDescanso(this, proximo)) linhaDescanso.valor(rotuloTempo(proximo))
            else linhaDescanso.detalhe(getString(R.string.cfg_nao_gravou_do))
        }
        c.addView(linhaDescanso, lp())
        // O que a linha de baixo do protetor mostra (Protetor.barra). OK alterna; vale no próximo protetor.
        fun rotuloBarra(v: String) = if (v == Protetor.BARRA_RESUMO) "Resumo do dia" else "Próximas horas"
        lateinit var linhaBarra: LinhaCfg
        linhaBarra = linha("Linha de baixo do protetor", "próximas horas, ou sol, lua, umidade e chuva do dia · OK muda",
            valor = rotuloBarra(Protetor.barra(this))) {
            val novo = if (Protetor.barra(this) == Protetor.BARRA_RESUMO) Protetor.BARRA_HORAS else Protetor.BARRA_RESUMO
            Protetor.definirBarra(this, novo)
            linhaBarra.valor(rotuloBarra(novo))
        }
        c.addView(linhaBarra, lp())
        // (11/11/2026) Saiu a linha "Estilo do protetor": o protetor é um só (o da Galeria) e o TEMA decide o fundo
        // (pintura na Galeria, wallpaper escuro no Escuro). O "clássico" escolhido por engano deixava o Escuro diferente.
        // Desligar sozinha (AutoDesligar): depois de X min sem ninguém apertar o controle, avisa por 60 s e apaga a TV.
        fun rotuloDesligar(m: Int) = if (m == 0) "Nunca" else if (m % 60 == 0) "${m / 60} h" else "$m min"
        lateinit var linhaDesligar: LinhaCfg
        linhaDesligar = linha("Desligar a TV sem uso", "avisa 60 s antes; qualquer botão cancela · OK muda",
            valor = rotuloDesligar(AutoDesligar.minutos(this))) {
            val o = AutoDesligar.OPCOES
            val proximo = o[(o.indexOf(AutoDesligar.minutos(this)) + 1) % o.size]
            AutoDesligar.definir(this, proximo)
            linhaDesligar.valor(rotuloDesligar(proximo))
        }
        c.addView(linhaDesligar, lp())
    }

    // ================================================================ aba: Visitas e rotinas

    private fun abaVisitas(c: LinearLayout) {
        // Modo visita (ModoVisita.kt): esconde perfil, agenda e os apps marcados abaixo, até desligar. Na HOME, com o modo
        // ligado, o OK na bolinha do perfil oferece "Sair" (o popup não abre).
        lateinit var linhaVisita: LinhaCfg
        linhaVisita = linha(getString(R.string.cfg_visita), "esconde o perfil, a agenda e os apps marcados abaixo",
            chave = ModoVisita.ativo(this)) {
            ModoVisita.definir(this, !ModoVisita.ativo(this))
            linhaVisita.ligado(ModoVisita.ativo(this))
            // A HOME relê o modo no onResume (bolinha e apps), então não precisa avisar nada a ela.
        }
        c.addView(linhaVisita, lp())
        // Rotinas por horário (05/11/2026): ligar, abrir um app ou desligar sozinha (ver Rotinas.kt).
        c.addView(linha(getString(R.string.cfg_rotinas), getString(R.string.cfg_rotinas_det), seta = true) {
            startActivity(Intent(this, RotinasActivity::class.java))
        }, lp())
        secao(c, "Apps escondidos no modo visita")
        val listaVisita = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
        c.addView(listaVisita, LinearLayout.LayoutParams(MATCH, WRAP))
        listaVisita.addView(info("Carregando os apps…", ""))
        carregarAppsDaVisita(listaVisita)
    }

    /**
     * Uma linha por app de TV instalado, para marcar quais somem no modo visita (chave ligada = some). A lista
     * (PackageManager) é lida fora da thread da tela; só o nome importa aqui, sem ícone, para ficar leve.
     */
    private fun carregarAppsDaVisita(destino: LinearLayout) {
        trabalho.execute {
            val pm = packageManager
            val consulta = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
            val apps = try {
                pm.queryIntentActivities(consulta, 0)
                    // Os mesmos apps que a HOME mostra (MainActivity.carregarApps): os instalados pelo usuário e os de
                    // sistema que estão em SEMPRE_MOSTRAR.
                    .filter {
                        val pkg = it.activityInfo.packageName
                        pkg != packageName && pkg != Config.PACOTE_CONFIG &&
                            ((it.activityInfo.applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                                pkg in Config.SEMPRE_MOSTRAR)
                    }
                    .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                    .distinctBy { it.first }
                    .sortedBy { it.second.lowercase() }
            } catch (e: Exception) { emptyList() }
            ui.post {
                if (isDestroyed || !destino.isAttachedToWindow) return@post
                destino.removeAllViews()
                for ((pacote, rotulo) in apps) {
                    lateinit var linhaApp: LinhaCfg
                    linhaApp = linha(rotulo, "some no modo visita quando ligado", chave = ModoVisita.escondeNaVisita(this, pacote)) {
                        ModoVisita.alternar(this, pacote)
                        linhaApp.ligado(ModoVisita.escondeNaVisita(this, pacote))
                    }
                    destino.addView(linhaApp, lp())
                }
            }
        }
    }

    // ================================================================ aba: Rede

    // Desde 04/11/2026 a VPN é o app AdGuard VPN (decisão do dono); o launcher só o fixa como "VPN sempre ativa" pelo
    // Device Owner (Config.VPN_FIXA, PrivilegioDO.aplicar) e mostra o estado aqui.
    private fun vpnAtiva(): Boolean = try {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        @Suppress("DEPRECATION")
        cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN) == true }
    } catch (e: Exception) { false }

    private fun abaRede(c: LinearLayout) {
        // Se o DNS fixo caiu e o launcher voltou para o automático (Dns.voltarAoAutomaticoSeQuebrado), avisa aqui.
        val caiu = Dns.servidorQueCaiu(this)
        c.addView(linha(getString(R.string.cfg_dns), Dns.rotulo(this) + (if (caiu != null) getString(R.string.cfg_dns_caiu, caiu) else ""), seta = true) {
            startActivity(Intent(this, DnsActivity::class.java))
        }, lp())
        val vpnDetalhe = if (Config.VPN_FIXA.isEmpty()) "o launcher não fixa VPN" else "app AdGuard VPN, sempre ativa · OK abre o app"
        c.addView(linha("VPN", vpnDetalhe, valor = if (vpnAtiva()) "conectada" else "desconectada") {
            if (Config.VPN_FIXA.isEmpty()) return@linha
            val abrir = packageManager.getLeanbackLaunchIntentForPackage(Config.VPN_FIXA)
                ?: packageManager.getLaunchIntentForPackage(Config.VPN_FIXA)
            if (abrir != null) startActivity(abrir.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }, lp())
        lateinit var linhaVelocidade: LinhaCfg
        linhaVelocidade = linha(getString(R.string.cfg_velocidade), getString(R.string.cfg_velocidade_det), seta = true) {
            // Linha própria, fora da fila `trabalho` (auditoria 10/11): na fila única, o PIN esperava o download de 25 MB.
            // Um teste por vez: OK repetido não empilha downloads.
            if (!medindoVelocidade) {
                medindoVelocidade = true
                linhaVelocidade.detalhe(getString(R.string.cfg_testando))
                Fundo.rodar("velocidade") {
                    val r = Velocidade.medir()
                    medindoVelocidade = false
                    ui.post { if (!isDestroyed) linhaVelocidade.detalhe(r) }
                }
            }
        }
        c.addView(linhaVelocidade, lp())
    }

    // ================================================================ aba: Acesso remoto

    private fun abaRemoto(c: LinearLayout) {
        // Painel no navegador (PainelWeb.kt): administrar a TV pelo celular/computador da casa, com PIN.
        secao(c, "Painel no navegador")
        lateinit var linhaPainel: LinhaCfg
        lateinit var linhaPin: LinhaCfg
        fun detalhePainel(): String = when {
            !PainelArmazem.temPin(this) -> "defina um PIN abaixo para ligar"
            PainelArmazem.desligadoPorErros(this) -> "desligou sozinho: PIN errado muitas vezes · OK religa"
            PainelArmazem.ligado(this) && PainelWeb.ligado() -> "abra ${PainelWeb.endereco() ?: "(sem rede)"} no navegador"
            PainelArmazem.ligado(this) -> "ligado, mas a porta ${Config.PAINEL_PORTA} não abriu"
            else -> "administre a TV pelo celular ou computador da casa"
        }
        fun ligadoPainel() = PainelArmazem.ligado(this) && PainelArmazem.temPin(this) && !PainelArmazem.desligadoPorErros(this)
        linhaPainel = linha("Painel", detalhePainel(), chave = ligadoPainel()) {
            when {
                !PainelArmazem.temPin(this) -> linhaPainel.detalhe("defina um PIN primeiro (linha de baixo)")
                PainelArmazem.desligadoPorErros(this) -> {
                    PainelArmazem.definirDesligadoPorErros(this, false)
                    PainelArmazem.definirLigado(this, true)
                    PainelWeb.recomecar(this)
                }
                else -> {
                    PainelArmazem.definirLigado(this, !PainelArmazem.ligado(this))
                    PainelWeb.ajustar(this)
                }
            }
            if (PainelArmazem.temPin(this)) {
                linhaPainel.ligado(ligadoPainel()); linhaPainel.detalhe(detalhePainel())
                // A porta abre numa thread à parte: confere de novo daqui a pouco.
                ui.postDelayed({ if (!isDestroyed) linhaPainel.detalhe(detalhePainel()) }, 800)
            }
        }
        c.addView(linhaPainel, lp())
        linhaPin = linha("PIN", "o mesmo PIN vale para o painel, o controle parental e o Claude",
            valor = if (PainelArmazem.temPin(this)) "definido" else "não definido") {
            // Trocar um PIN que já existe pede o PIN atual: senão qualquer um no controle trocava e desligava o parental.
            val trocar = { definirNovoPin(linhaPin) {
                linhaPin.valor("definido")
                linhaPainel.ligado(ligadoPainel()); linhaPainel.detalhe(detalhePainel())
                ui.postDelayed({ if (!isDestroyed) linhaPainel.detalhe(detalhePainel()) }, 800)
            } }
            if (PainelArmazem.temPin(this)) pedirPinParental("PIN atual", trocar) else trocar()
        }
        c.addView(linhaPin, lp())
        // Entrar pelo celular sem digitar o PIN (EntrarPeloQrActivity). Com alguma trava para criança ligada (controle
        // parental, hora de dormir, PIN para mexer na HOME), quem está na TV pode ser uma criança: pede o PIN antes.
        c.addView(linha("Entrar pelo celular", "mostra um QR: o celular lê e entra no painel sem digitar o PIN", seta = true) {
            val abrir = { startActivity(android.content.Intent(this, EntrarPeloQrActivity::class.java)) }
            when {
                !PainelArmazem.podeLigar(this) || !PainelWeb.ligado() -> aviso("ligue o painel primeiro (linha Painel, acima)")
                PlayerArmazem.parental(this).ligado || Extras.ligado(this, Extras.Chave.HORA_DORMIR) ||
                    Extras.ligado(this, Extras.Chave.PIN_HOME) -> pedirPinParental("PIN do painel", abrir)
                else -> abrir()
            }
        }, lp())
        val acessos = PainelArmazem.resumoRegistro(this, 3)
        c.addView(info("Últimos acessos", if (acessos.isEmpty()) "nenhum ainda" else acessos.joinToString("\n"), maxLinhas = 4), lp())

        // Claude pela internet (McpServidor + TunelTailscale, 05/11/2026). A chave do Tailscale se cola no painel (PIN).
        secao(c, "Claude pela internet (MCP)")
        lateinit var linhaMcp: LinhaCfg
        fun detalheMcp(): String {
            val nome = TunelTailscale.nome(this)
            return when {
                !PainelArmazem.temPin(this) -> "defina o PIN do painel primeiro (é o login)"
                !McpServidor.ligado(this) -> "o Claude, o ChatGPT e outros controlam a TV pela internet"
                TunelTailscale.urlLogin != null || TunelTailscale.urlAtivar != null -> "falta entrar no Tailscale: abra \"Conectar\" abaixo"
                TunelTailscale.rodando() && nome != null -> "https://$nome/mcp"
                else -> "túnel subindo${TunelTailscale.ultimoErro(this)?.let { " (último erro: $it)" } ?: ""}"
            }
        }
        linhaMcp = linha("Acesso pelo Claude", detalheMcp(), chave = McpServidor.ligado(this)) {
            if (!PainelArmazem.temPin(this)) { linhaMcp.detalhe(detalheMcp()); return@linha }
            McpServidor.definirLigado(this, !McpServidor.ligado(this))
            linhaMcp.ligado(McpServidor.ligado(this)); linhaMcp.detalhe(detalheMcp())
            ui.postDelayed({ if (!isDestroyed) linhaMcp.detalhe(detalheMcp()) }, 3000)
        }
        c.addView(linhaMcp, lp())
        c.addView(linha("Conectar (QR code)", "passo a passo: login no Tailscale e endereço para o Claude, ChatGPT, Codex…", seta = true) {
            startActivity(Intent(this, ConectarClaudeActivity::class.java))
        }, lp())
        lateinit var linhaRede: LinhaCfg
        linhaRede = linha("Rede Tailscale", "painel pelos aparelhos da conta Tailscale e lista deles para o Claude",
            chave = TunelTailscale.redeLigada(this)) {
            TunelTailscale.definirRede(this, !TunelTailscale.redeLigada(this)); linhaRede.ligado(TunelTailscale.redeLigada(this))
        }
        c.addView(linhaRede, lp())
        lateinit var linhaShell: LinhaCfg
        linhaShell = linha("Shell pelo Claude", "deixa o Claude rodar qualquer comando na TV (como adb shell); desligue se não usar",
            chave = McpServidor.shellLigado(this)) {
            McpServidor.definirShell(this, !McpServidor.shellLigado(this)); linhaShell.ligado(McpServidor.shellLigado(this))
        }
        c.addView(linhaShell, lp())
        lateinit var linhaLogins: LinhaCfg
        linhaLogins = linha("Logins do Claude", "OK revoga todos", valor = "${McpServidor.logins(this)} ativo(s)") {
            McpServidor.revogar(this)
            linhaLogins.valor("${McpServidor.logins(this)} ativo(s)"); linhaLogins.detalhe("revogados")
        }
        c.addView(linhaLogins, lp())
    }

    // ================================================================ aba: Sistema

    private fun abaSistema(c: LinearLayout) {
        // Diagnóstico de 1 botão (04/11/2026): o 1º da aba, para ser o OK mais rápido quando "o app parou".
        c.addView(linha(getString(R.string.cfg_diag), getString(R.string.cfg_diag_det), seta = true) {
            startActivity(Intent(this, DiagnosticoActivity::class.java))
        }, lp())
        // Extras (10/11/2026): as chaves das ideias da pesquisa ficam numa tela própria (ExtrasActivity).
        c.addView(linha(getString(R.string.extras_titulo), getString(R.string.extras_cfg_det), seta = true) {
            startActivity(Intent(this, ExtrasActivity::class.java))
        }, lp())
        // Backup noturno cifrado (BackupNoturno.kt): as opções ficam numa tela própria; ao voltar, o resumo se atualiza.
        c.addView(linha(getString(R.string.bkn_titulo), BackupNoturno.resumo(this), seta = true) {
            reconstruirAoVoltar = true
            startActivity(Intent(this, BackupNoturnoActivity::class.java))
        }, lp())

        confirmacoes(c)

        // Atualização (Atualizador.kt): o launcher confere o GitHub a cada 6 h e instala sozinho com a tela apagada.
        // "Procurar" só confere; "Instalar a versão N" só aparece quando há um Release que ESTA TV aceita (mesma chave).
        secao(c, getString(R.string.cfg_sec_atualizacao))
        lateinit var linhaAtualiza: LinhaCfg
        lateinit var linhaInstalar: LinhaCfg
        fun mostrarInstalar() {
            val n = Atualizador.pendente(this)
            linhaInstalar.visibility = if (n == null) View.GONE else View.VISIBLE
            if (n != null) linhaInstalar.montarLinha(getString(R.string.cfg_instalar, n), getString(R.string.cfg_instalar_det))
        }
        linhaAtualiza = linha(getString(R.string.cfg_atualizar), getString(R.string.cfg_atualizar_det, versaoInstalada().toString(), Atualizador.estado(this).substringBefore(" · OK")), seta = true) {
            linhaAtualiza.detalhe(getString(R.string.cfg_procurando))
            Atualizador.verificar(this, instalar = false) { r -> runOnUiThread { if (!isDestroyed) { linhaAtualiza.detalhe(r); mostrarInstalar() } } }
        }
        linhaInstalar = linha(getString(R.string.cfg_instalar, 0L), getString(R.string.cfg_instalar_det), seta = true) {
            linhaInstalar.detalhe(getString(R.string.cfg_instalando))
            // Baixa e confere de novo antes de instalar (o arquivo da procura não fica guardado). Device Owner: sem pergunta.
            Atualizador.verificar(this, instalar = true) { r -> runOnUiThread { if (!isDestroyed) linhaInstalar.detalhe(r) } }
        }
        c.addView(linhaAtualiza, lp())
        c.addView(linhaInstalar, lp())
        mostrarInstalar()
        // Atualização vigiada (VigiaAtualizacao.kt): só aparece se uma versão caiu logo depois de instalar.
        VigiaAtualizacao.textoPausa(this)?.let { pausa ->
            lateinit var linhaSeguro: LinhaCfg
            linhaSeguro = linha(getString(R.string.cfg_vig_liberar), pausa + getString(R.string.cfg_vig_liberar_det), seta = true) {
                VigiaAtualizacao.liberar(this)
                linhaSeguro.visibility = View.GONE
            }
            c.addView(linhaSeguro, lp())
        }

        // Apps ocultos pelo Device Owner (Config.APPS_OCULTAR/PrivilegioDO.ocultar): libera a memória sem desinstalar.
        // Chave ligada = app visível.
        if (Config.APPS_OCULTAR.isNotEmpty()) {
            secao(c, getString(R.string.cfg_sec_ocultos))
            Config.APPS_OCULTAR.forEach { pacote ->
                val rotulo = try {
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pacote, 0)).toString()
                } catch (e: Exception) { pacote }
                lateinit var linhaApp: LinhaCfg
                linhaApp = linha(rotulo, "visível na TV", chave = !PrivilegioDO.estaOculto(this, pacote)) {
                    // Fora da thread da tela: sem Device Owner o ocultar passa pelo shell e pode demorar (Congelar.kt).
                    val app = applicationContext
                    Fundo.rodar {
                        val ocultoAntes = PrivilegioDO.estaOculto(app, pacote)
                        val mudou = PrivilegioDO.ocultar(app, pacote, !ocultoAntes)
                        val ocultoAgora = PrivilegioDO.estaOculto(app, pacote)
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            if (mudou) linhaApp.ligado(!ocultoAgora) else linhaApp.detalhe(getString(R.string.cfg_nao_mudou_do))
                        }
                    }
                }
                c.addView(linhaApp, lp())
            }
        }

        // Limpeza de apps de fábrica (Debloat.kt, 05/11/2026): só os apps já testados nesta TV, com desfazer.
        // Chave ligada = app visível (como na seção de cima).
        secao(c, "Limpeza de apps de fábrica")
        val limpaveis = Debloat.disponiveis(this)
        if (limpaveis.isEmpty()) c.addView(info("Nenhum app liberado ainda", "só entram apps testados um a um nesta TV (ver Debloat.kt)"), lp())
        limpaveis.forEach { item ->
            lateinit var linhaApp: LinhaCfg
            linhaApp = linha(item.nome, "ocultar perde: ${item.perde}", chave = !PrivilegioDO.estaOculto(this, item.pacote)) {
                val app = applicationContext
                Fundo.rodar {   // fora da thread da tela (sem Device Owner vai pelo shell, ver Congelar.kt)
                    val ocultar = !PrivilegioDO.estaOculto(app, item.pacote)
                    val mudou = Debloat.definir(app, item.pacote, ocultar)
                    val ocultoAgora = PrivilegioDO.estaOculto(app, item.pacote)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (mudou) linhaApp.ligado(!ocultoAgora)
                        else linhaApp.detalhe("não consegui mudar (sem Device Owner e sem o shell próprio pareado?)")
                    }
                }
            }
            c.addView(linhaApp, lp())
        }
        if (Debloat.ocultosPorAqui(this).isNotEmpty()) {
            lateinit var linhaDesfazer: LinhaCfg
            linhaDesfazer = linha("Mostrar todos de volta", "desfaz a limpeza (o protetor do Google continua oculto)", seta = true) {
                val app = applicationContext
                Fundo.rodar {
                    val n = Debloat.desfazerTudo(app)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        linhaDesfazer.detalhe("$n app(s) de volta")
                        ui.postDelayed({ if (!isDestroyed) recarregarAba() }, 1200)
                    }
                }
            }
            c.addView(linhaDesfazer, lp())
        }

        // Permissões: o que o launcher usa para funcionar sem o computador por perto. OK num que falta tenta ligar sem PC.
        secao(c, getString(R.string.cfg_sec_ligado, Recursos.resumo(this)))
        for (r in Recursos.listar(this)) {
            val detalhe = if (r.ativo) getString(R.string.cfg_ligado) else r.sem + when (r.pedir) {
                null -> getString(R.string.cfg_so_pc)
                else -> getString(R.string.cfg_tenta_ligar)
            }
            c.addView(if (r.ativo || r.pedir == null) info((if (r.ativo) "✓ " else "✗ ") + r.nome, detalhe)
                else linha("✗ " + r.nome, detalhe, seta = true) { ligarRecurso(r) }, lp())
        }
        if (PrivilegioDO.somosDonos(this))
            c.addView(info(getString(R.string.cfg_perm_fixas), Config.PERMISSOES_FIXAS.joinToString { it.substringAfterLast('.') }), lp())

        // Shell próprio (cliente ADB Wi-Fi embutido). Até 12/11/2026 esta seção era o "Shizuku do launcher" (servidor do
        // Shizuku embutido para outros apps); o servidor saiu e ficou só o shell que o próprio launcher usa.
        secao(c, getString(R.string.cfg_sec_shizuku))
        c.addView(info(getString(R.string.cfg_shell_estado), getString(
            if (SaudeAcesso.shellEsperado(this)) R.string.cfg_shell_pareado else R.string.cfg_shell_nunca)), lp())
        // Parear a depuração sem fio sem PC (ver PareamentoNaTv).
        lateinit var linhaParear: LinhaCfg
        linhaParear = linha(getString(R.string.cfg_parear), if (PareamentoNaTv.podeMostrar(this))
            getString(R.string.cfg_parear_det) else getString(R.string.cfg_acess_off), seta = true) {
            if (!PareamentoNaTv.comecar(this)) linhaParear.detalhe(getString(R.string.cfg_acess_off))
        }
        c.addView(linhaParear, lp())

        // Sobre: números úteis para saber se algo por trás dos panos está ok, sem precisar do logcat.
        secao(c, "Sobre")
        val versao = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { null }
        val cartao = cartao()
        c.addView(cartao, lp())
        cartao.addView(valor(getString(R.string.cfg_versao), "${versao ?: getString(R.string.cfg_desconhecida)} (${versaoInstalada()})"))
        cartao.addView(valor(getString(R.string.cfg_depuracao), if (Config.DEPURACAO_SEM_FIO_SEMPRE_LIGADA) getString(R.string.cfg_depuracao_on) else getString(R.string.cfg_depuracao_android)))
        // Fora da thread da tela (revisão 04/11): na 1ª vez isto GERA a chave RSA-2048 (segundos num ARMv7).
        val linhaChave = valor(getString(R.string.cfg_chave), getString(R.string.cfg_calculando))
        cartao.addView(linhaChave)
        trabalho.execute {
            val fp = try { ShellProprio.impressaoDigital(this) } catch (e: Exception) { getString(R.string.cfg_erro, e.javaClass.simpleName) }
            ui.post { if (!isDestroyed) (linhaChave.getChildAt(1) as? TextView)?.text = fp }
        }
        // Licenças (05/11/2026): o texto fica em assets/licencas.txt, o mesmo que o repositório mostra.
        c.addView(linha(getString(R.string.cfg_licencas), getString(R.string.cfg_licencas_det), seta = true) {
            startActivity(Intent(this, LicencasActivity::class.java))
        }, lp())
    }

    private fun ligarRecurso(r: Recursos.Recurso) {
        val p = r.pedir
        if (p == Recursos.VIRAR_DONO) {
            virarDono()
        } else if (p == Recursos.PEDIR_PERMISSAO) {
            val faltam = Recursos.perigosasFaltando(this)
            if (faltam.isNotEmpty()) requestPermissions(faltam, PEDIDO_RECURSOS)
        } else if (p is Intent) {
            // Algumas TVs não têm a tela de uso/sobreposição: cai nas Configurações gerais em vez de travar.
            reconstruirAoVoltar = true
            try { startActivity(p) } catch (e: Exception) {
                try { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) } catch (_: Exception) { reconstruirAoVoltar = false }
            }
        }
    }

    /**
     * Virar Device Owner pela TV (VirarDono.kt). Primeiro descobre se há usuários extras (sairiam, com os dados deles) e
     * pede confirmação dizendo exatamente o que vai acontecer. Tudo fora da thread da tela.
     */
    private fun virarDono() = VirarDono.perguntar(this, ::aviso) { if (it.ok) recarregarAba() }

    // ================================================================ caixas de texto (PIN, endereço)

    /**
     * Novo PIN, digitado duas vezes. [linhaAviso] recebe os erros (null = aviso na tela). [aoGravar] roda depois de gravar.
     * Definir o PIN já liga o painel (é para isso que se define) e derruba as sessões antigas.
     */
    private fun definirNovoPin(linhaAviso: LinhaCfg?, aoGravar: () -> Unit) {
        fun avisar(t: String) { if (linhaAviso != null) linhaAviso.detalhe(t) else aviso(t) }
        pedirPin("Novo PIN (${PinSeguro.MIN_DIGITOS} a ${PinSeguro.MAX_DIGITOS} dígitos)") { pin ->
            if (!PinSeguro.valido(pin)) { avisar("PIN precisa ter de ${PinSeguro.MIN_DIGITOS} a ${PinSeguro.MAX_DIGITOS} números"); return@pedirPin }
            pedirPin("Digite o PIN de novo") { pin2 ->
                if (pin2 != pin) { avisar("os dois PINs não bateram · OK tenta de novo"); return@pedirPin }
                avisar("gravando…")
                // PBKDF2 com 100 mil voltas leva um tempo num ARMv7: fora da thread da tela.
                trabalho.execute {
                    PainelArmazem.definirPin(this, pin)
                    ui.post {
                        if (isDestroyed) return@post
                        PainelArmazem.definirLigado(this, true)
                        PainelWeb.recomecar(this)
                        pinValidoAte = System.currentTimeMillis() + PIN_VALE_MS
                        avisar("PIN definido")
                        aoGravar()
                    }
                }
            }
        }
    }

    /**
     * Caixa para digitar o PIN com o teclado da TV (só números, escondido). [aoConfirmar] recebe o texto digitado.
     * AlertDialog do próprio Android: o controle remoto já sabe navegar nele (OK confirma, Voltar cancela).
     */
    private fun pedirPin(titulo: String, aoConfirmar: (String) -> Unit) {
        val campo = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(PinSeguro.MAX_DIGITOS))
            setSingleLine()
        }
        val moldura = FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(campo) }
        val d = CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(titulo)
            .setView(moldura)
            .setPositiveButton("OK") { _, _ -> aoConfirmar(campo.text.toString()) }
            .setNegativeButton("Cancelar", null)
            .create()
        // "Concluído" do teclado da TV já confirma, sem ter de descer até o botão.
        campo.setOnEditorActionListener { _, acao, _ ->
            if (acao == android.view.inputmethod.EditorInfo.IME_ACTION_DONE || acao == android.view.inputmethod.EditorInfo.IME_ACTION_GO) {
                d.dismiss(); aoConfirmar(campo.text.toString()); true
            } else false
        }
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }

    /** CEP e número com o teclado numérico da TV (AlertDialog: OK confirma, Voltar cancela). CEP vazio apaga o endereço. */
    private fun pedirEndereco(aoConfirmar: (String, String) -> Unit) {
        val atual = LocalClima.ler(this)
        fun campo(dica: String, max: Int, valor: String) = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(android.text.InputFilter.LengthFilter(max))
            hint = dica; setText(valor); setSingleLine()
        }
        val cep = campo("CEP (8 números)", 8, atual?.cep.orEmpty())
        val numero = campo("Número da casa (opcional)", 6, atual?.numero.orEmpty())
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(cep); addView(numero)
        }
        CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle("Endereço do clima")
            .setView(caixa)
            .setPositiveButton("OK") { _, _ ->
                if (cep.text.isBlank()) { LocalClima.limpar(this); recarregarAba() }
                else aoConfirmar(cep.text.toString(), numero.text.toString())
            }
            .setNegativeButton("Cancelar", null)
            .show()
        cep.requestFocus()
    }

    /** Recado curto: o aviso padrão do app (pílula no canto de cima), não mais um texto trocado no rodapé. */
    private fun aviso(t: String) = AvisoTv.rapido(this, getString(R.string.cfg_titulo), t, 4)

    // ================================================================ peças

    @Suppress("DEPRECATION")
    private fun versaoInstalada(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    } catch (e: Exception) { 0L }

    /**
     * Uma linha de opção: título e explicação à esquerda; à direita, conforme o tipo:
     *  - [chave] não nulo: a chave de liga/desliga (ligada = verde à direita);
     *  - [valor] não nulo: o valor atual em destaque (ex.: "5 min");
     *  - [seta]: "›", para o que abre outra tela ou faz uma ação.
     */
    private fun linha(titulo: String, detalhe: String, chave: Boolean? = null, valor: String? = null, seta: Boolean = false,
                      aoClicar: () -> Unit): LinhaCfg =
        LinhaCfg(this, focavel = true).apply {
            montarLinha(titulo, detalhe)
            if (chave != null) ligado(chave)
            if (valor != null) this.valor(valor)
            if (seta && chave == null && valor == null) this.seta()
            setOnClickListener { aoClicar() }
        }

    /** Uma linha só informativa, sem foco nem clique (para ler, não para apertar OK). */
    private fun info(titulo: String, detalhe: String, maxLinhas: Int = 3): LinhaCfg =
        LinhaCfg(this, focavel = false).apply { montarLinha(titulo, detalhe, maxLinhas) }

    /** Cartão de vidro que agrupa linhas de "rótulo: valor" (dados da conta, sobre). */
    private fun cartao() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = vidro(false)
        setPadding(dp(24), dp(12), dp(24), dp(12))
    }

    /** "Rótulo ........ valor" numa linha do cartão. [cor] pinta o valor (alerta). */
    private fun valor(rotulo: String, valor: String, cor: Int? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(6), 0, dp(6))
        addView(texto(16f, textoSuave, fonteMedia).apply { text = rotulo }, LinearLayout.LayoutParams(dp(230), WRAP))
        addView(texto(16f, cor ?: textoClaro, fonteMedia).apply { text = valor; maxLines = 3; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun secao(c: LinearLayout, titulo: String) = c.addView(Estilo.secao(this, titulo).apply { setPadding(dp(8), dp(Estilo.E3), 0, dp(Estilo.E1)) })

    private fun lp() = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    /** Superfície das linhas e cartões (sistema de design): repouso = branco 6% com fio; foco = branco sólido. */
    private fun vidro(focado: Boolean): GradientDrawable =
        if (focado) Estilo.fundo(this, Estilo.FOCO, Estilo.RAIO) else Estilo.fundo(this, Estilo.SUPERFICIE, Estilo.RAIO, Estilo.BORDA)

    /**
     * A linha das Configurações: [título / detalhe] à esquerda e um acessório à direita (chave, valor ou seta).
     * Fica clara e cresce um pouco com o foco, como as linhas do popup e da tela de DNS.
     */
    private inner class LinhaCfg(ctx: Context, focavel: Boolean) : LinearLayout(ctx) {
        private val tTitulo = Estilo.texto(ctx, Estilo.Papel.CORPO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val tDetalhe = Estilo.texto(ctx, Estilo.Papel.LEGENDA).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0)
        }
        private var tSeta: TextView? = null
        private var focada = false
        private var ligadoAgora: Boolean? = null
        private val direita = FrameLayout(ctx)
        private var trilho: View? = null
        private var bolinha: View? = null
        private var tValor: TextView? = null

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(Estilo.E3), dp(14), dp(Estilo.E3), dp(14))
            val textos = LinearLayout(ctx).apply { orientation = VERTICAL; addView(tTitulo); addView(tDetalhe) }
            addView(textos, LayoutParams(0, WRAP, 1f))
            addView(direita, LayoutParams(WRAP, WRAP).apply { marginStart = dp(16) })
            if (focavel) {
                isFocusable = true
                isClickable = true
                background = vidro(false)
                setOnFocusChangeListener { v, temFoco ->
                    focada = temFoco
                    v.background = vidro(temFoco)
                    pintarTextos()
                    v.animate().scaleX(if (temFoco) Estilo.ESCALA_LINHA else 1f).scaleY(if (temFoco) Estilo.ESCALA_LINHA else 1f)
                        .setDuration(Estilo.FOCO_MS).start()
                    if (temFoco) mostrarDica(noMenu = false)
                }
            }
        }

        fun montarLinha(titulo: String, detalhe: String, maxLinhas: Int = 2) {
            tTitulo.text = titulo
            tDetalhe.text = detalhe
            tDetalhe.maxLines = maxLinhas
            tDetalhe.visibility = if (detalhe.isEmpty()) View.GONE else View.VISIBLE
        }

        fun detalhe(novo: String) = montarLinha(tTitulo.text.toString(), novo, tDetalhe.maxLines)

        /** Foco = linha branca: os textos escurecem e o valor passa para o acento escuro (legível no branco). */
        private fun pintarTextos() {
            tTitulo.setTextColor(if (focada) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO)
            tDetalhe.setTextColor(if (focada) Estilo.TEXTO_NO_FOCO_2 else Estilo.TEXTO_2)
            tValor?.setTextColor(if (focada) Estilo.ACENTO_NO_FOCO else Estilo.ACENTO)
            tSeta?.setTextColor(if (focada) Estilo.TEXTO_NO_FOCO_2 else Estilo.TEXTO_3)
            ligadoAgora?.let { pintarChave(it) }
        }

        /** Mostra a chave de liga/desliga no estado [sim]. */
        fun ligado(sim: Boolean) {
            if (trilho == null) {
                direita.removeAllViews()
                trilho = View(context)
                bolinha = View(context)
                direita.addView(trilho, FrameLayout.LayoutParams(dp(50), dp(28), Gravity.CENTER_VERTICAL))
                direita.addView(bolinha, FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER_VERTICAL))
            }
            ligadoAgora = sim
            pintarChave(sim)
            (bolinha!!.layoutParams as FrameLayout.LayoutParams).apply {
                gravity = Gravity.CENTER_VERTICAL or if (sim) Gravity.END else Gravity.START
                marginStart = if (sim) 0 else dp(3); marginEnd = if (sim) dp(3) else 0
            }
            bolinha!!.requestLayout()
            contentDescription = "${tTitulo.text}: ${if (sim) "ligado" else "desligado"}"
        }

        /** Interruptor: ligado = acento (no foco, acento escuro); desligado = trilho apagado. */
        private fun pintarChave(sim: Boolean) {
            trilho?.background = Estilo.fundo(context,
                when { sim && focada -> Estilo.ACENTO_NO_FOCO; sim -> Estilo.ACENTO; focada -> 0x330B0F17; else -> 0x33FFFFFF },
                Estilo.RAIO_PILULA)
            bolinha?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (sim) (if (focada) Estilo.FOCO else Estilo.FUNDO) else (if (focada) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO))
            }
        }

        /** Mostra o valor atual à direita (ex.: "5 min", "Escuro"). */
        fun valor(v: String) {
            val t = tValor ?: Estilo.texto(context, Estilo.Papel.CORPO).also { direita.removeAllViews(); direita.addView(it); tValor = it }
            t.text = "$v  ›"
            pintarTextos()
        }

        fun seta() {
            direita.removeAllViews()
            direita.addView(Estilo.texto(context, Estilo.Papel.TITULO, "›").apply { typeface = Estilo.fonte(200) }.also { tSeta = it })
            pintarTextos()
        }
    }
}
