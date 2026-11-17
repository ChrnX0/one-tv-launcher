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
        /** Índices das abas que outras telas abrem direto (a ordem mudou em 14/11/2026: use estes, não números). */
        const val ABA_CONTA = 1
        const val ABA_AVANCADO = 8
        private const val PIN_VALE_MS = 2 * 60_000L   // depois de acertar o PIN aqui, 2 min sem pedir de novo
        private const val ESTADO_LIBERADO = "hospede_liberado"
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
    private val fonteLeve = Fontes.nunito(200)
    private val fonteMedia = Fontes.nunito(500)

    /** Uma aba do menu: título, explicação curta (aparece no alto do conteúdo) e quem monta o conteúdo. */
    private class Aba(val titulo: String, val explica: String, val montar: (LinearLayout) -> Unit)

    private val abas: List<Aba> by lazy {
        // 14/11/2026 (pedido do dono: "complexo demais"): abas por assunto, na ordem do uso do dia a dia; o técnico
        // (rede, permissões, shell, limpeza de fábrica, confirmações) foi para "Avançado", a última. Antes: 8 abas
        // por origem do código (Conta, Controle parental, Aparência, Tela e energia, Visitas, Rede, Remoto, Sistema).
        listOf(
            Aba(getString(R.string.cfg2_aba_inicio), getString(R.string.cfg2_aba_inicio_det), this::abaAparencia),
            Aba(getString(R.string.cl_aba_conta), getString(R.string.cl_aba_conta_det), this::abaConta),   // ABA_CONTA
            Aba(getString(R.string.cfg2_aba_familia), getString(R.string.cfg2_aba_familia_det), this::abaFamilia),
            Aba(getString(R.string.cl_aba_energia), getString(R.string.cl_aba_energia_det), this::abaEnergia),
            Aba(getString(R.string.cfg2_aba_casa), getString(R.string.cfg2_aba_casa_det), this::abaCasa),
            Aba(getString(R.string.cl_aba_remoto), getString(R.string.cl_aba_remoto_det), this::abaRemoto),
            Aba(getString(R.string.cl_aba_sistema), getString(R.string.cfg2_aba_sistema_det), this::abaSistema),
            // Ajuda (14/11/2026, pedido do dono): o manual de tudo o que o launcher faz, antes do Avançado.
            Aba(getString(R.string.aj_aba), getString(R.string.aj_aba_det), this::abaAjuda),
            Aba(getString(R.string.cfg2_aba_avancado), getString(R.string.cfg2_aba_avancado_det), this::abaAvancado),   // ABA_AVANCADO
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
    private var liberadoHospede = false   // modo hóspede: o PIN já foi acertado nesta visita às Configurações
    // Erros do PIN guardados (TentativasPin, como no player): fechar e abrir as Configurações não dá mais 5 tentativas.
    // Preferência própria do processo da HOME ("config_pin"); a do player ("player_pin") é do processo ":player".
    private val prefsPin by lazy { getSharedPreferences("config_pin", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        abaAtual = (savedInstanceState?.getInt(EXTRA_ABA) ?: intent.getIntExtra(EXTRA_ABA, 0)).coerceIn(0, abas.size - 1)
        // Modo hóspede (degrau 2): as Configurações só abrem com o PIN (o hóspede não desliga a limpeza nem mexe na TV).
        // "liberado" vem do estado salvo só quando a tela foi recriada com o PIN já certo (controle/teclado ligado, giro);
        // antes valia "savedInstanceState == null", e qualquer recriação abria sem PIN (achado de 17/11/2026).
        liberadoHospede = savedInstanceState?.getBoolean(ESTADO_LIBERADO, false) ?: false
        if (ModoHospede.ativo(this) && !liberadoHospede) { pedirPinHospede(); return }
        abrirTela()
    }

    /** Tela vazia + PIN; acertou = libera e desenha; errou ou cancelou = fecha. */
    private fun pedirPinHospede() {
        setContentView(View(this).apply { background = Estilo.fundoTela(context) })
        PinNaTela.pedir(this, getString(R.string.hos_pin_config), aoCancelar = { finish() }) {
            liberadoHospede = true
            pinValidoAte = System.currentTimeMillis() + PIN_VALE_MS
            abrirTela()
        }
    }

    private fun abrirTela() {
        setContentView(montarTela())
        mostrarAba(abaAtual)
        itensMenu[abaAtual].post { itensMenu[abaAtual].requestFocus() }
    }

    override fun onSaveInstanceState(estado: Bundle) {
        super.onSaveInstanceState(estado)
        estado.putInt(EXTRA_ABA, abaAtual)
        estado.putBoolean(ESTADO_LIBERADO, liberadoHospede)
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
        // Saiu de verdade (HOME, outro app; não é só a tela sendo recriada): no modo hóspede, voltar pede o PIN de novo.
        if (!isChangingConfigurations) liberadoHospede = false
    }

    override fun onRestart() {
        super.onRestart()
        // Volta pelos recentes ou pelo botão voltar de outra tela: com o hóspede ligado, o PIN de novo.
        if (ModoHospede.ativo(this) && !liberadoHospede) { pedirPinHospede(); return }
        if (reconstruirAoVoltar && ::conteudo.isInitialized) { reconstruirAoVoltar = false; recarregarAba() }
    }

    @Deprecated("Activity simples, sem AndroidX")
    override fun onRequestPermissionsResult(codigo: Int, permissoes: Array<out String>, resultados: IntArray) {
        if (codigo == PEDIDO_RECURSOS) recarregarAba()
    }

    // ================================================================ esqueleto: menu à esquerda, conteúdo à direita

    private fun montarTela(): View {
        itensMenu.clear()   // a tela pode ser montada de novo (PIN do hóspede ao voltar): o menu antigo sai da lista
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
        mostrarAbasDoUso()
        colunas.addView(ScrollView(this).apply { isVerticalScrollBarEnabled = false; clipChildren = false; addView(menu)
            // Igual ao conteúdo: corta embaixo (a última aba não passa por cima da barra de dicas; achado do teste de tela,
            // ideia 96) e deixa 16 dp dos lados para a pílula com foco crescer.
            addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.clipBounds = android.graphics.Rect(-dp(16), 0, v.width + dp(16), v.height) } },
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

    /** Uso "Simples"/"Para quem cuida" (UsoTv, degrau 2): as abas técnicas somem do menu (a aberta agora fica). */
    private fun mostrarAbasDoUso() {
        val uso = UsoTv.atual(this)
        itensMenu.forEachIndexed { i, t -> t.visibility = if (UsoTv.mostraAba(uso, i) || i == abaAtual) View.VISIBLE else View.GONE }
    }

    private fun nomeUso(u: UsoTv) = getString(when (u) {
        UsoTv.SIMPLES -> R.string.uso_simples
        UsoTv.COMPLETO -> R.string.uso_completo
        UsoTv.CUIDA -> R.string.uso_cuida
    })

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
        // Ajuda por QR (degrau 2): no fim de cada aba, o assunto do manual dela, com o QR para ler no celular.
        AjudaQr.topicoDaAba(i)?.let { id ->
            val n = Manual.TOPICOS.indexOf(id)
            if (n >= 0) conteudo.addView(linha(getString(R.string.ajq_linha), getString(R.string.ajq_linha_det,
                AjudaActivity.texto(this, id, Manual.Parte.TITULO)), seta = true) {
                startActivity(Intent(this, AjudaActivity::class.java).putExtra(AjudaActivity.EXTRA_TOPICO, n))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16); bottomMargin = dp(8) })
        }
    }

    /** Redesenha a aba atual no lugar (depois de voltar de outra tela ou de mudar algo que mexe em várias linhas). */
    private fun recarregarAba() {
        // 15/11/2026 (visto na Sala): tirar as linhas com o foco numa delas deixava o Android escolher outro foco, às
        // vezes outra aba do menu, que então abria (Avançado > ligar um empréstimo > caía em "TV e filmes" com o foco
        // numa linha de lá, e o próximo OK mudava uma opção que ninguém escolheu). Agora o foco espera na aba atual e
        // volta para a MESMA linha (pela posição) depois de redesenhar.
        val antes = focaveis(conteudo)
        val focado = conteudo.findFocus()
        val indice = antes.indexOfFirst { it === focado || (focado != null && ehAncestral(it, focado)) }
        val tinhaFocoNoConteudo = focado != null
        if (tinhaFocoNoConteudo) itensMenu[abaAtual].requestFocus()
        mostrarAba(abaAtual)
        if (tinhaFocoNoConteudo) conteudo.post {
            val depois = focaveis(conteudo)
            (depois.getOrNull(indice) ?: depois.firstOrNull())?.requestFocus()
        }
    }

    private fun focaveis(v: View, r: MutableList<View> = mutableListOf()): List<View> {
        if (v.visibility != View.VISIBLE) return r
        if (v.isFocusable) { r += v; return r }
        if (v is ViewGroup) for (k in 0 until v.childCount) focaveis(v.getChildAt(k), r)
        return r
    }

    private fun ehAncestral(pai: View, filho: View): Boolean {
        var p = filho.parent
        while (p != null) { if (p === pai) return true; p = p.parent }
        return false
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
        dica.addView(if (noMenu) Estilo.barraDicas(this, "▲▼" to getString(R.string.cl_dica_aba), "▶ OK" to getString(R.string.cl_dica_entrar), getString(R.string.cl_tecla_voltar) to getString(R.string.cl_dica_sair))
            else Estilo.barraDicas(this, "▲▼" to getString(R.string.cl_dica_escolher), "OK" to getString(R.string.cl_dica_mudar), "◀ " + getString(R.string.cl_tecla_voltar) to getString(R.string.cl_dica_voltar)))
    }

    private var engoliuVoltar = false   // o VOLTAR que só levou o foco ao menu não pode fechar a tela ao soltar

    /** ◀ nas opções volta ao menu; ▶ no menu entra nas opções; ▲▼ não escapam de um lado para o outro. */
    override fun dispatchKeyEvent(e: KeyEvent): Boolean {
        if (!::conteudo.isInitialized) return super.dispatchKeyEvent(e)   // modo hóspede: ainda esperando o PIN
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
        secao(c, getString(R.string.cl_gerenciar_contas))
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
        // Serviços de filmes e séries (14/11/2026): Trakt, Simkl, MDBList, TMDB e OpenSubtitles numa tela só. A chave do
        // OpenSubtitles agora é a do app; a linha antiga (chave do dono) só aparece para quem já tinha cadastrado uma,
        // enquanto o build não tiver a chave do app.
        c.addView(linha(getString(R.string.sv_linha), getString(R.string.sv_linha_det), seta = true) {
            startActivity(Intent(this, ServicosFilmesActivity::class.java))
        }, lp())
        lateinit var linhaLeg: LinhaCfg
        linhaLeg = linha(getString(R.string.legenda_conta_titulo), getString(R.string.legenda_conta_det), valor = "…") {
            pedirContaLegenda { tem -> linhaLeg.valor(getString(if (tem) R.string.legenda_conta_sim else R.string.legenda_conta_nao)) }
        }
        if (!ServicosContas.disponivel(ServicosFilmes.Servico.OPENSUBTITLES)) c.addView(linhaLeg)
        // Som e legenda para quem enxerga ou ouve pior (14/11/2026). As mesmas chaves do menu ▼ do player.
        secao(c, getString(R.string.acess_config_titulo))
        val chavesAcess = listOf(PlayerAcessivel.LEGENDA_FUNDO to R.string.acess_legenda_fundo, PlayerAcessivel.LEGENDA_AMARELA to R.string.acess_legenda_amarela) +
            (if (EfeitosSom.disponivel()) listOf(PlayerAcessivel.VOLUME_IGUAL to R.string.acess_volume, PlayerAcessivel.VOZ_CLARA to R.string.acess_voz) else emptyList())
        chavesAcess.forEachIndexed { i, (k, rot) ->
            lateinit var l: LinhaCfg
            l = linha(getString(rot), if (i == 0) getString(R.string.acess_config_texto) else "", chave = EfeitosSom.ligado(this, k)) {
                l.ligado(EfeitosSom.alternar(this, k))
            }
            c.addView(l)
        }
        // Ideia 86: filme na taxa de quadros dele (24 Hz), a mesma chave do menu ▼ do player e do painel.
        lateinit var linhaQuadros: LinhaCfg
        linhaQuadros = linha(getString(R.string.quadros_opcao), getString(R.string.quadros_det), chave = TaxaQuadros.ligada(this)) {
            linhaQuadros.ligado(TaxaQuadros.alternar(this))
        }
        c.addView(linhaQuadros)
        // Confortos do estudo do Stremio (16/11/2026; Conforto.kt): esconder spoiler, segundo idioma e a contagem do próximo.
        lateinit var linhaSpoiler: LinhaCfg
        linhaSpoiler = linha(getString(R.string.cf_spoiler), getString(R.string.cf_spoiler_det), chave = Conforto.semSpoiler(this)) {
            linhaSpoiler.ligado(Conforto.alternarSpoiler(this))
        }
        c.addView(linhaSpoiler)
        fun rotuloSegundo(cod: String?) = cod?.let { Conforto.nomeIdioma(it) } ?: getString(R.string.cf_nenhum)
        lateinit var linhaSegundo: LinhaCfg
        linhaSegundo = linha(getString(R.string.cf_segundo), getString(R.string.cf_segundo_det), valor = rotuloSegundo(Conforto.segundoIdioma(this))) {
            val opcoes = listOf<String?>(null) + ConfortoRegras.SEGUNDOS
            val atualS = Conforto.segundoIdioma(this)
            Estilo.lista(this, getString(R.string.cf_segundo), opcoes.map { rotuloSegundo(it) }, listOf(opcoes.indexOf(atualS).coerceAtLeast(0))) { i ->
                Conforto.definirSegundo(this, opcoes[i]); linhaSegundo.valor(rotuloSegundo(opcoes[i]))
            }
        }
        c.addView(linhaSegundo)
        fun rotuloContagem(s: Int) = if (s <= 0) getString(R.string.cf_proximo_nao) else getString(R.string.cf_segundos, s)
        lateinit var linhaContagem: LinhaCfg
        linhaContagem = linha(getString(R.string.cf_proximo), getString(R.string.cf_proximo_det), valor = rotuloContagem(Conforto.contagem(this))) {
            val v = ConfortoRegras.CONTAGENS
            Estilo.lista(this, getString(R.string.cf_proximo), v.map { rotuloContagem(it) }, listOf(v.indexOf(Conforto.contagem(this)).coerceAtLeast(0))) { i ->
                Conforto.definirContagem(this, v[i]); linhaContagem.valor(rotuloContagem(v[i]))
            }
        }
        c.addView(linhaContagem)
        // Addons (16/11/2026): ver e remover aqui; instalar é pelo painel web (colar endereço é penoso no controle).
        fun rotuloAddons() = AddonsTv.instalados(this).size.let { if (it == 0) getString(R.string.cf_nenhum) else it.toString() }
        lateinit var linhaAddons: LinhaCfg
        linhaAddons = linha(getString(R.string.ad_titulo), getString(R.string.ad_cfg_det), valor = rotuloAddons()) {
            val todos = AddonsTv.instalados(this)
            if (todos.isEmpty()) {
                CaixaTv(this).setTitle(getString(R.string.ad_titulo)).setMessage(getString(R.string.ad_cfg_como))
                    .setPositiveButton(getString(android.R.string.ok), null).show()
                return@linha
            }
            Estilo.lista(this, getString(R.string.ad_cfg_remover_qual), todos.map { it.m.nome }, emptyList()) { i ->
                val a = todos[i]
                CaixaTv(this).setTitle(a.m.nome).setMessage(getString(R.string.ad_cfg_remover, a.m.nome))
                    .setPositiveButton(getString(R.string.ad_cfg_remover_sim)) { _, _ -> AddonsTv.remover(this, a.base); linhaAddons.valor(rotuloAddons()) }
                    .setNegativeButton(getString(R.string.sv_cancelar), null).show()
            }
        }
        c.addView(linhaAddons)
        // Foto da conta Google na bolinha do perfil (FotoPerfil, 13/11/2026): abre a tela do código para o celular.
        secao(c, getString(R.string.fp_secao))
        c.addView(linha(getString(R.string.fp_linha), getString(R.string.fp_linha_det), seta = true) {
            startActivity(Intent(this, FotoPerfilActivity::class.java))
        }, lp())
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
        val chave = campo(getString(R.string.cl_os_chave), T or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val usuario = campo(getString(R.string.cl_os_usuario), T or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS)
        val senha = campo(getString(R.string.cl_os_senha), T or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(48, 16, 48, 0)
            if (erro != null) addView(Estilo.texto(this@ConfigLauncherActivity, Estilo.Papel.LEGENDA, erro).apply { setTextColor(coral) })
            addView(chave); addView(usuario); addView(senha)
        }
        CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(R.string.legenda_conta_titulo))
            .setView(caixa)
            .setPositiveButton(getString(R.string.cl_gravar)) { _, _ ->
                val conta = LegendaBaixar.Conta(chave.text.toString(), usuario.text.toString(), senha.text.toString())
                leitura.execute {
                    val r = LegendaBaixar.definirConta(this, conta)
                    val tem = LegendaBaixar.temConta(this)
                    ui.post {
                        if (isDestroyed) return@post
                        r.onSuccess { aoGravar(tem) }.onFailure { pedirContaLegenda(getString(R.string.cl_nao_gravei, it.message.toString()), aoGravar) }
                    }
                }
            }
            .setNeutralButton(getString(R.string.cl_apagar)) { _, _ ->
                leitura.execute { LegendaBaixar.definirConta(this, null); ui.post { if (!isDestroyed) aoGravar(false) } }
            }
            .setNegativeButton(getString(R.string.cl_cancelar), null)
            .show()
    }

    private fun contasNoTopo(c: LinearLayout, fontes: List<FonteTv>) {
        if (fontes.isEmpty()) {
            c.addView(cartao().apply {
                addView(valor(getString(R.string.cl_nenhuma_conta), getString(R.string.cl_nenhuma_conta_det)))
            }, lp())
            // Sem lista ainda (11/11/2026, visto no 1 Play): um vídeo livre para ver o player funcionando antes de cadastrar.
            c.addView(linha(getString(R.string.cl_demo), getString(R.string.cl_demo_det), seta = true) {
                VodActivity.abrirUrl(this, Config.VIDEO_DEMONSTRACAO, "Sintel · Blender Foundation · CC BY 3.0")
            })
        }
        for (f in fontes) {
            secao(c, f.nome.ifEmpty { getString(R.string.cl_aba_conta) })
            val cartao = cartao().apply {
                // Focável (sem clique) só para o ▲ conseguir voltar até ele: senão o quadro sumia acima da tela.
                isFocusable = true
                setOnFocusChangeListener { v, temFoco -> v.background = vidro(temFoco); if (temFoco) mostrarDica(noMenu = false) }
            }
            c.addView(cartao, lp())
            fun preencher(s: Xtream.Situacao?, consultou: Boolean) {
                cartao.removeAllViews()
                for (l in ContaIptv.linhas(f, s, consultou, System.currentTimeMillis(), t = textosConta))
                    cartao.addView(valor(l.rotulo, l.valor, if (l.alerta) coral else null))
            }
            preencher(null, consultou = false)
            val podeConsultar = ContaIptv.contaConsultavel(f) != null
            if (podeConsultar) {
                // Primeiro mostra o que já se sabe (nome, usuário, tipo); a situação chega quando o servidor responder.
                (cartao.getChildAt(cartao.childCount - 1) as? LinearLayout)?.let { (it.getChildAt(1) as? TextView)?.text = getString(R.string.cl_consultando) }
                lateinit var linhaConsultar: LinhaCfg
                fun consultar() {
                    linhaConsultar.detalhe(getString(R.string.cl_consultando))
                    trabalho.execute {
                        val s = PlayerCarga.situacao(f)
                        val hora = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
                        ui.post {
                            if (isDestroyed) return@post
                            preencher(s, consultou = true)
                            linhaConsultar.detalhe(if (s != null) getString(R.string.cl_atualizado, hora) else getString(R.string.cl_sem_resposta, hora))
                        }
                    }
                }
                linhaConsultar = linha(getString(R.string.cl_atualizar_conta), getString(R.string.cl_atualizar_conta_det), seta = true) { consultar() }
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
            adicionar(linha(f.nome, "${ContaIptv.tipoLogin(f, textosConta)} · ${f.servidorVisivel}", valor = getString(R.string.cl_apagar)) {
                // Com o controle parental ligado, apagar e cadastrar pedem o PIN: cadastrar de novo muda o id dos canais e
                // os bloqueados por canal deixariam de valer.
                exigirPinSeParental {
                    CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(getString(R.string.cl_apagar_conta_titulo, f.nome))
                        .setMessage(getString(R.string.cl_apagar_conta_msg))
                        .setPositiveButton(getString(R.string.cl_apagar)) { _, _ ->
                            PlayerArmazem.apagar(this, f.id)
                            recarregarAba()
                        }
                        .setNegativeButton(getString(R.string.cl_cancelar), null)
                        .show()
                }
            })
        }
        if (contas.size < 8) {
            adicionar(linha(getString(R.string.cl_cad_xtream), getString(R.string.cl_cad_xtream_det), seta = true) {
                exigirPinSeParental { pedirContaTv(FonteTv.TIPO_XTREAM) }
            })
            adicionar(linha(getString(R.string.cl_cad_m3u), getString(R.string.cl_cad_m3u_det), seta = true) {
                exigirPinSeParental { pedirContaTv(FonteTv.TIPO_M3U) }
            })
        }
        if (contas.isNotEmpty()) {
            lateinit var linhaTestar: LinhaCfg
            linhaTestar = linha(getString(R.string.cl_testar), getString(R.string.cl_testar_det), seta = true) {
                linhaTestar.detalhe(getString(R.string.cl_baixando))
                trabalho.execute {
                    val agora = System.currentTimeMillis()
                    val semCanal = PlayerArmazem.fontes(this).count { PlayerCarga.canaisDaFonte(this, it, forcar = true).isEmpty() }
                    val canais = PlayerCarga.canais(this)
                    val guia = PlayerCarga.guia(this, canais, forcar = true)
                    val comGuia = guia.comProgramaAgora(agora).let { no -> canais.count { it.guiaId in no } }.also { guia.fechar() }   // 1 consulta; solta o banco
                    val texto = getString(R.string.cl_teste_resultado, canais.size, comGuia) +
                        (if (semCanal > 0) getString(R.string.cl_teste_sem_canal, semCanal) else "")
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
        val nome = campo(getString(R.string.cl_campo_nome), 40, T, antes?.getOrNull(0))
        val endereco = campo(if (xtream) getString(R.string.cl_campo_servidor) else getString(R.string.cl_campo_link), 2000,
            T or android.text.InputType.TYPE_TEXT_VARIATION_URI, antes?.getOrNull(1))
        val usuario = campo(getString(R.string.cl_campo_usuario), 200, T or android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, antes?.getOrNull(2))
        // A senha não volta preenchida num erro: digitar de novo é mais seguro que deixá-la na memória da caixa.
        val senha = campo(getString(R.string.cl_campo_senha), 200, T or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD, null)
        // Outros endereços do mesmo painel (o vendedor costuma mandar 2 ou 3): usados se o principal cair.
        val reservas = campo(getString(R.string.cl_campo_reservas), 600,
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
            .setTitle(if (xtream) getString(R.string.cl_conta_xtream) else getString(R.string.cl_lista_canais))
            .setView(caixa)
            .setPositiveButton(getString(R.string.cl_cadastrar)) { _, _ ->
                val r = PlayerArmazem.adicionar(this, nome.text.toString(), tipo, endereco.text.toString(),
                    usuario.text.toString(), senha.text.toString(), reservas = reservas.text.toString())
                r.onSuccess { recarregarAba() }.onFailure {
                    pedirContaTv(tipo, listOf(nome.text.toString(), endereco.text.toString(), usuario.text.toString(), reservas.text.toString()),
                        getString(R.string.cl_nao_cadastrei, it.message.toString()))
                }
            }
            .setNegativeButton(getString(R.string.cl_cancelar), null)
            .show()
        endereco.requestFocus()
    }

    // ================================================================ aba: Controle parental

    private fun abaParental(c: LinearLayout) {
        if (!PainelArmazem.temPin(this)) {
            c.addView(cartao().apply {
                addView(valor(getString(R.string.cl_precisa_pin), getString(R.string.cl_precisa_pin_det)))
            }, lp())
            c.addView(linha(getString(R.string.cl_definir_pin), getString(R.string.cl_definir_pin_det, PinSeguro.MIN_DIGITOS, PinSeguro.MAX_DIGITOS), seta = true) {
                definirNovoPin(null) { recarregarAba() }
            }, lp())
            extrasDaAba(c, ExtrasAbas.Aba.PARENTAL)
            return
        }
        val r = PlayerArmazem.parental(this)
        fun resumo(r: ControleParental.Regras) = when {
            r.vazio -> getString(R.string.cl_nenhum_canal)
            else -> getString(R.string.cl_resumo_bloqueio, r.canais.size, r.grupos.size)
        }
        lateinit var chave: LinhaCfg
        chave = linha(getString(R.string.cl_aba_parental), if (r.ligado) getString(R.string.cl_ligado_resumo, resumo(r)) else getString(R.string.cl_desligado_resumo, resumo(r)), chave = r.ligado) {
            pedirPinParental {
                val atual = PlayerArmazem.parental(this)
                val nova = atual.copy(ligado = !atual.ligado)
                if (PlayerArmazem.definirParental(this, nova)) {
                    chave.ligado(nova.ligado)
                    chave.detalhe(if (nova.ligado) getString(R.string.cl_ligado_resumo, resumo(nova)) else getString(R.string.cl_desligado_resumo, resumo(nova)))
                } else chave.detalhe(getString(R.string.cl_nao_gravei_tente))
            }
        }
        c.addView(chave, lp())
        c.addView(linha(getString(R.string.cl_escolher_bloqueados), getString(R.string.cl_escolher_bloqueados_det), seta = true) {
            pedirPinParental {
                reconstruirAoVoltar = true
                startActivity(Intent(this, BloqueioCanaisActivity::class.java))
            }
        }, lp())
        if (!r.vazio) {
            c.addView(linha(getString(R.string.cl_liberar_todos), getString(R.string.cl_liberar_todos_det), seta = true) {
                pedirPinParental {
                    CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
                        .setTitle(getString(R.string.cl_liberar_todos_q))
                        .setMessage(getString(R.string.cl_liberar_todos_msg))
                        .setPositiveButton(getString(R.string.cl_liberar)) { _, _ ->
                            // copy: o modo infantil e as categorias infantis ficam como estão
                            PlayerArmazem.definirParental(this, PlayerArmazem.parental(this).copy(canais = emptySet(), grupos = emptySet()))
                            recarregarAba()
                        }
                        .setNegativeButton(getString(R.string.cl_cancelar), null)
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
                else chaveInf.detalhe(getString(R.string.cl_nao_gravei_tente))
            }
        }
        c.addView(chaveInf, lp())
        c.addView(linha(getString(R.string.cfg_infantil_escolher), getString(R.string.cfg_infantil_escolher_det), seta = true) {
            pedirPinParental {
                reconstruirAoVoltar = true
                startActivity(Intent(this, BloqueioCanaisActivity::class.java).putExtra(BloqueioCanaisActivity.EXTRA_INFANTIS, true))
            }
        }, lp())
        // Ideia 91: idade máxima no modo infantil (classificação indicativa do TMDB). Escolher pede o PIN.
        fun nomeIdade(i: Int) = when (i) { Classificacao.DESLIGADA -> getString(R.string.idade_so_categorias); 0 -> getString(R.string.idade_livre); else -> getString(R.string.idade_anos, i) }
        lateinit var linhaIdade: LinhaCfg
        linhaIdade = linha(getString(R.string.idade_titulo), getString(R.string.idade_det), valor = nomeIdade(r.idadeMax)) {
            pedirPinParental {
                val atual = PlayerArmazem.parental(this)
                Estilo.lista(this, getString(R.string.idade_titulo), Classificacao.OPCOES.map { nomeIdade(it) },
                    listOf(Classificacao.OPCOES.indexOf(atual.idadeMax))) { i ->
                    val nova = PlayerArmazem.parental(this).copy(idadeMax = Classificacao.OPCOES[i])
                    if (PlayerArmazem.definirParental(this, nova)) linhaIdade.valor(nomeIdade(nova.idadeMax))
                    else linhaIdade.detalhe(getString(R.string.cl_nao_gravei_tente))
                }
            }
        }
        c.addView(linhaIdade, lp())
        // PIN das crianças (ideia 34): destrava na frente da criança sem entregar o PIN que administra a TV.
        c.addView(linha(getString(R.string.fam_pin), getString(R.string.fam_pin_det),
            valor = getString(if (PainelArmazem.temPinFamilia(this)) R.string.cl_definido else R.string.cl_nao_definido)) {
            pedirPinParental(getString(R.string.cl_pin_painel)) { pinFamilia() }
        }, lp())
        extrasDaAba(c, ExtrasAbas.Aba.PARENTAL)
        secao(c, getString(R.string.cl_como_funciona))
        c.addView(cartao().apply {
            addView(valor(getString(R.string.cl_cf_bloqueado), getString(R.string.cl_cf_bloqueado_det)))
            addView(valor(getString(R.string.cl_cf_assistir), getString(R.string.cl_cf_assistir_det)))
            addView(valor(getString(R.string.cl_cf_tranca), getString(R.string.cl_cf_tranca_det)))
            addView(valor(getString(R.string.cl_cf_pin), getString(R.string.cl_cf_pin_det)))
        }, lp())
    }

    /** Definir, trocar ou apagar o PIN das crianças (ideia 34). Quem chama já conferiu o PIN do painel. */
    private fun pinFamilia() {
        val definir = {
            pedirPin(getString(R.string.cl_novo_pin, PinSeguro.MIN_DIGITOS, PinSeguro.MAX_DIGITOS)) { pin ->
                pedirPin(getString(R.string.cl_pin_de_novo)) { pin2 ->
                    if (pin2 != pin) { aviso(getString(R.string.cl_pin_nao_bateu)); return@pedirPin }
                    aviso(getString(R.string.cl_gravando))
                    trabalho.execute {
                        // Igual ao do painel não vale (PinFamilia.conferirNovo). PBKDF2: fora da thread da tela.
                        val r = PinFamilia.conferirNovo(pin, PinSeguro.valido(pin) && PainelArmazem.conferirPin(this, pin))
                        if (r == PinFamilia.Novo.OK) PainelArmazem.definirPinFamilia(this, pin)
                        ui.post {
                            if (isDestroyed) return@post
                            aviso(when (r) {
                                PinFamilia.Novo.OK -> getString(R.string.fam_pin_ok)
                                PinFamilia.Novo.IGUAL_AO_PAINEL -> getString(R.string.fam_pin_igual)
                                PinFamilia.Novo.INVALIDO -> getString(R.string.cl_pin_invalido, PinSeguro.MIN_DIGITOS, PinSeguro.MAX_DIGITOS)
                            })
                            recarregarAba()
                        }
                    }
                }
            }
        }
        if (!PainelArmazem.temPinFamilia(this)) { definir(); return }
        Estilo.lista(this, getString(R.string.fam_pin), listOf(getString(R.string.fam_trocar), getString(R.string.fam_apagar))) { i ->
            if (i == 0) definir()
            else trabalho.execute { PainelArmazem.apagarPinFamilia(this); ui.post { if (!isDestroyed) { aviso(getString(R.string.fam_apagado)); recarregarAba() } } }
        }
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

    private fun pedirPinParental(titulo: String = getString(R.string.cl_pin_parental), acao: () -> Unit) {
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
        secao(c, getString(R.string.cl_sec_confirmacoes))
        val r = ConfirmacoesTv.regras(this)
        fun mudar(novas: Confirmacoes.Regras) {
            val gravar = { ConfirmacoesTv.definir(this, novas); recarregarAba() }
            if (Confirmacoes.pedePin(ConfirmacoesTv.regras(this), novas)) pedirPinParental(getString(R.string.cl_pin_liberar), gravar) else gravar()
        }
        c.addView(linha(getString(R.string.cl_liberar_tudo),
            getString(R.string.cl_liberar_tudo_det),
            chave = r.liberarTudo) { mudar(r.copy(liberarTudo = !r.liberarTudo)) }, lp())
        for (a in Confirmacoes.Acao.entries) {
            val atual = Confirmacoes.modo(r, a)
            c.addView(linha(tituloAcao(a), explicaAcao(a), valor = if (r.liberarTudo) getString(R.string.cl_liberado) else rotuloModo(atual)) {
                if (r.liberarTudo) aviso(getString(R.string.cl_desligue_liberar))
                else mudar(r.copy(modos = r.modos + (a to Confirmacoes.proximo(a, atual))))
            }, lp())
        }
        c.addView(info(getString(R.string.cl_perguntar_tv), getString(R.string.cl_perguntar_tv_det)), lp())
    }

    /**
     * Emprestar o shell a outros apps (15/11/2026, "poder máximo"; ver ShizukuEmbutido). Desligado de fábrica. Ligar pede
     * o PIN (liberar mais); desligar não. Cada app ainda pede na TV, com PIN, na primeira vez.
     */
    private fun emprestimoShell(c: LinearLayout) {
        secao(c, getString(R.string.emp_secao))
        val modo = ShizukuEmbutido.modo(this)
        val rotulo = when (modo) {
            RegrasEmprestimo.Modo.DESLIGADO -> getString(R.string.emp_modo_desligado)
            RegrasEmprestimo.Modo.SOB_DEMANDA -> getString(R.string.emp_modo_sob_demanda)
            RegrasEmprestimo.Modo.SEMPRE -> getString(R.string.emp_modo_sempre)
        }
        c.addView(linha(getString(R.string.emp_titulo), getString(R.string.emp_titulo_det), valor = rotulo) {
            val proximo = RegrasEmprestimo.Modo.values()[(modo.ordinal + 1) % RegrasEmprestimo.Modo.values().size]
            val gravar = { ShizukuEmbutido.definirModo(this, proximo); recarregarAba() }
            if (modo == RegrasEmprestimo.Modo.DESLIGADO) pedirPinParental(getString(R.string.cl_pin_liberar), gravar) else gravar()
        }, lp())
        when (ShizukuEmbutido.parado(this)) {
            RegrasEmprestimo.Parado.SHIZUKU_OFICIAL -> c.addView(info(getString(R.string.emp_titulo), getString(R.string.emp_oficial)), lp())
            RegrasEmprestimo.Parado.PLANO -> c.addView(info(getString(R.string.emp_titulo), getString(R.string.emp_plano)), lp())
            else -> {}
        }
        // Device Owner emprestado (Dhizuku, DonoEmprestado.kt). Ligar pede o PIN; desligar vale na hora para todos os apps.
        val donoLigado = DonoEmprestado.ligado(this)
        c.addView(linha(getString(R.string.emd_titulo), getString(R.string.emd_titulo_det),
            valor = getString(if (donoLigado) R.string.emd_ligado else R.string.emp_modo_desligado)) {
            val gravar = { DonoEmprestado.definirLigado(this, !donoLigado); recarregarAba() }
            if (!donoLigado) pedirPinParental(getString(R.string.cl_pin_liberar), gravar) else gravar()
        }, lp())
        if (donoLigado) when (DonoEmprestado.parado(this)) {
            RegrasDono.Parado.SEM_DONO -> c.addView(info(getString(R.string.emd_titulo), getString(R.string.emd_sem_dono)), lp())
            RegrasDono.Parado.PLANO -> c.addView(info(getString(R.string.emd_titulo), getString(R.string.emp_plano)), lp())
            else -> {}
        }
    }

    // ================================================================ aba: Aparência

    private fun abaAparencia(c: LinearLayout) {
        if (VindoDoProjectivyActivity.instalado(this)) c.addView(linha(getString(R.string.vp_titulo), getString(R.string.vp_cfg_det), seta = true) {
            reconstruirAoVoltar = true
            startActivity(Intent(this, VindoDoProjectivyActivity::class.java))
        }, lp())
        // Plugins (degrau 3, PluginsTv): cartões de outros apps na linha Assistir. Ligar um plugin pede o PIN parental.
        c.addView(linha(getString(R.string.plg_titulo), getString(R.string.plg_cfg_det), seta = true) {
            // Título padrão ("PIN do controle parental"): só "Plugins" sobre um campo vazio não dizia o que digitar (Sala, 17/11).
            pedirPinParental {
                reconstruirAoVoltar = true
                startActivity(Intent(this, PluginsActivity::class.java))
            }
        }, lp())
        lateinit var linhaTema: LinhaCfg
        linhaTema = linha(getString(R.string.cl_tema), getString(R.string.cfg_tema_det), valor = Tema.atual(this).nome) {
            Tema.proximo(this)
            linhaTema.valor(Tema.atual(this).nome)
            // A MainActivity só relê o tema no onCreate: ela mesma percebe a troca e se recria ao voltar (ver onResume lá).
        }
        c.addView(linhaTema, lp())
        // "Meu fundo" (degrau 1, 15/11/2026; FundoMeu.kt): escolher o arquivo já põe o tema; brilho e contraste em degraus.
        fun rotuloMeu() = FundoMeuDados.arquivo(this)?.name ?: getString(R.string.pi_nenhum)
        lateinit var linhaMeu: LinhaCfg
        linhaMeu = linha(getString(R.string.fm_titulo), getString(R.string.fm_det), valor = rotuloMeu()) {
            trabalho.execute {
                val arquivos = FundoMeuDados.candidatos()
                ui.post {
                    if (isDestroyed) return@post
                    if (arquivos.isEmpty()) { aviso(getString(R.string.fm_vazio)); return@post }
                    val atual = FundoMeuDados.arquivo(this)
                    // Mesmo nome em pastas diferentes (ex.: Download/v1.png e Pictures/v1.png): mostra a pasta junto,
                    // senão a lista tem duas linhas iguais e não dá para saber qual é qual (visto na Sala, 17/11/2026).
                    val repetidos = arquivos.groupingBy { it.name }.eachCount().filterValues { it > 1 }.keys
                    val nomes = arquivos.map { f -> if (f.name in repetidos) "${f.parentFile?.name ?: "?"}/${f.name}" else f.name }
                    Estilo.lista(this, getString(R.string.fm_titulo), listOf(getString(R.string.fm_nenhum)) + nomes,
                        marcadas = listOf(arquivos.indexOfFirst { it == atual } + 1)) { i ->
                        FundoMeuDados.definirArquivo(this, arquivos.getOrNull(i - 1))
                        Tema.definir(this, if (i == 0) Tema.ESCURO else Tema.MEU)
                        recarregarAba()   // mostra (ou esconde) brilho e contraste
                    }
                }
            }
        }
        c.addView(linhaMeu, lp())
        fun pct(v: Int) = if (v > 0) "+$v%" else "$v%"
        // Brilho e contraste só aparecem com um arquivo escolhido (sem "Meu fundo" não valem nada: conciso).
        if (FundoMeuDados.arquivo(this) != null) {
        lateinit var linhaBrilho: LinhaCfg
        linhaBrilho = linha(getString(R.string.fm_brilho), getString(R.string.fm_ajuste_det), valor = pct(FundoMeuDados.brilho(this))) {
            FundoMeuDados.definirBrilho(this, FundoMeuRegras.proximoDegrau(FundoMeuDados.brilho(this)))
            linhaBrilho.valor(pct(FundoMeuDados.brilho(this)))
        }
        c.addView(linhaBrilho, lp())
        lateinit var linhaContraste: LinhaCfg
        linhaContraste = linha(getString(R.string.fm_contraste), getString(R.string.fm_ajuste_det), valor = pct(FundoMeuDados.contraste(this))) {
            FundoMeuDados.definirContraste(this, FundoMeuRegras.proximoDegrau(FundoMeuDados.contraste(this)))
            linhaContraste.valor(pct(FundoMeuDados.contraste(this)))
        }
        c.addView(linhaContraste, lp())
        }
        // Tamanho do texto (ideia 47, TamanhoTexto): vale em todas as telas do launcher; esta tela se refaz para mostrar.
        fun rotuloTamanho(v: Int) = getString(when (v) { 110 -> R.string.tt_grande; 120 -> R.string.tt_maior; else -> R.string.tt_normal })
        c.addView(linha(getString(R.string.tt_titulo), getString(R.string.tt_det), valor = rotuloTamanho(TamanhoTexto.atual(this))) {
            TamanhoTexto.definir(this, TamanhoTexto.proximo(TamanhoTexto.atual(this)))
            recreate()
        }, lp())
        // Fileira ou grade (layout flexível, LayoutHome): a HOME se refaz ao voltar.
        c.addView(linha(getString(R.string.lh_arranjo), getString(R.string.lh_arranjo_det),
            valor = getString(if (LayoutHome.grade(this)) R.string.lh_grade else R.string.lh_fileira)) {
            LayoutHome.definirGrade(this, !LayoutHome.grade(this))
            recarregarAba()
        }, lp())
        // Tamanho dos cartões (layout flexível, LayoutHome): a HOME se refaz ao voltar.
        c.addView(linha(getString(R.string.lh_titulo), getString(R.string.lh_det),
            valor = getString(if (LayoutHome.tamanho(this) == 100) R.string.lh_normal else R.string.lh_grande)) {
            LayoutHome.definirTamanho(this, LayoutHome.proximo(LayoutHome.tamanho(this)))
            reconstruirAoVoltar = true
            recarregarAba()
        }, lp())
        // Pacote de ícones (degrau 1, 15/11/2026): desligado de fábrica; cada OK passa ao próximo instalado e volta a "Nenhum".
        val pacotes = PacoteIcones.instalados(this)
        fun rotuloPacote() = PacoteIcones.escolhido(this).let { if (it.isEmpty()) getString(R.string.pi_nenhum) else PacoteIcones.nome(this, it) }
        lateinit var linhaPacote: LinhaCfg
        linhaPacote = linha(getString(R.string.pi_titulo), getString(if (pacotes.isEmpty()) R.string.pi_det_vazio else R.string.pi_det),
            valor = rotuloPacote()) {
            PacoteIcones.escolher(this, PacoteIconesRegras.proximo(PacoteIcones.escolhido(this), pacotes.map { it.first }))
            linhaPacote.valor(rotuloPacote())   // a HOME redesenha sozinha ao voltar: o pacote entra na chave do cache
        }
        c.addView(linhaPacote, lp())
        // Visuais prontos (revisão dos degraus, 1b, 17/11/2026): cada OK aplica o próximo da lista (VisualCodigo.PRONTOS).
        fun rotuloPronto() = VisualCodigo.prontoAtual(VisualCodigoTv.atual(this))?.let { nomePronto(it.id) } ?: getString(R.string.vpr_meu)
        c.addView(linha(getString(R.string.vpr_titulo), getString(R.string.vpr_det), valor = rotuloPronto()) {
            val p = VisualCodigo.proximoPronto(VisualCodigoTv.atual(this))
            VisualCodigoTv.aplicar(this, VisualCodigo.codificar(p.visual))
            recreate()   // tema e tamanho do texto mudam a própria tela de Configurações
        }, lp())
        // Tema por QR (degrau 1, 15/11/2026): mostra o código do visual desta TV num QR; o celular abre a página pública.
        c.addView(linha(getString(R.string.vq_titulo), getString(R.string.vq_det), seta = true) { mostrarQrVisual() }, lp())
        // Apps ocultos (14/11/2026): o card "Ocultos (N)" saiu da HOME; mostrar de volta é aqui.
        val nOcultos = Organizacao.ocultos(this).size
        c.addView(linha(getString(R.string.oc_titulo), if (nOcultos == 0) getString(R.string.oc_nenhum) else
            getString(R.string.oc_quantos, nOcultos), seta = true) {
            reconstruirAoVoltar = true
            startActivity(Intent(this, AppsEscolhaActivity::class.java).putExtra(AppsEscolhaActivity.EXTRA_MODO, AppsEscolhaActivity.MODO_OCULTOS))
        }, lp())
        // Pilhas automáticas (05/11/2026, Organizacao.agruparPorCategoria): a HOME relê ao voltar (preencherApps no onResume).
        lateinit var linhaPilhas: LinhaCfg
        linhaPilhas = linha(getString(R.string.cfg_pilhas), getString(R.string.cl_pilhas_det),
            chave = Organizacao.pilhasAutomaticas(this)) {
            Organizacao.definirPilhasAutomaticas(this, !Organizacao.pilhasAutomaticas(this))
            linhaPilhas.ligado(Organizacao.pilhasAutomaticas(this))
        }
        c.addView(linhaPilhas, lp())
        // Ideia 42: quais blocos o painel rápido (HOME segurado) mostra, e em que ordem.
        c.addView(linha(getString(R.string.pb_titulo), getString(R.string.pb_cfg_det), seta = true) {
            startActivity(Intent(this, PainelBlocosActivity::class.java))
        }, lp())

        secao(c, getString(R.string.cl_clima))
        lateinit var linhaEndereco: LinhaCfg
        fun detalheEndereco() = LocalClima.ler(this)?.let { LocalClima.descrever(it) }
            ?: getString(R.string.cl_end_nenhum)
        linhaEndereco = linha(getString(R.string.cl_end_clima), detalheEndereco(), seta = true) {
            pedirEndereco { cep, numero ->
                linhaEndereco.detalhe(getString(R.string.cl_procurando_end))
                trabalho.execute {
                    val r = LocalClima.definirPorCep(this, cep, numero)
                    ui.post { if (!isDestroyed) linhaEndereco.detalhe(r.fold({ detalheEndereco() }, { getString(R.string.cl_nao_deu, it.message.toString()) })) }
                }
            }
        }
        c.addView(linhaEndereco, lp())
        // Aviso de chuva por cima de qualquer app (AvisoChuva.kt). O recado vem pelo painel e não tem chave aqui.
        lateinit var linhaChuva: LinhaCfg
        linhaChuva = linha(getString(R.string.cl_aviso_chuva),
            getString(R.string.cl_aviso_chuva_det, AvisoChuva.LIMIAR, AvisoChuva.HORAS_A_FRENTE),
            chave = AvisoChuva.ligado(this)) {
            AvisoChuva.definirLigado(this, !AvisoChuva.ligado(this))
            linhaChuva.ligado(AvisoChuva.ligado(this))
        }
        c.addView(linhaChuva, lp())
        // Ideia 94: calor ou frio forte ("Beba água"), no mesmo cartão do aviso de chuva.
        lateinit var linhaTemp: LinhaCfg
        linhaTemp = linha(getString(R.string.tp_cfg), getString(R.string.tp_cfg_det, AvisoTemperatura.CALOR.toInt(), AvisoTemperatura.FRIO.toInt()),
            chave = AvisoTemperatura.ligado(this)) {
            AvisoTemperatura.definirLigado(this, !AvisoTemperatura.ligado(this))
            linhaTemp.ligado(AvisoTemperatura.ligado(this))
        }
        c.addView(linhaTemp, lp())
        extrasDaAba(c, ExtrasAbas.Aba.APARENCIA)
    }

    // ================================================================ aba: Tela e energia

    /**
     * Protetor v2 (17/11/2026, protótipo "Protetor Vivo 2"): fundo, relógio (estilo, lugar, tamanho), quando o resto
     * aparece e o liga/desliga de cada elemento. OK passa para a próxima opção; vale no próximo protetor ([Protetor]).
     */
    private fun protetorV2(c: LinearLayout) {
        fun escolha(titulo: Int, detalhe: Int, e: Protetor.Escolha, rotulos: Map<String, Int>) {
            lateinit var l: LinhaCfg
            l = linha(getString(titulo), getString(detalhe), valor = getString(rotulos.getValue(e.ler(this)))) {
                val novo = e.proximo(e.ler(this)); e.gravar(this, novo); l.valor(getString(rotulos.getValue(novo)))
            }
            c.addView(l, lp())
        }
        escolha(R.string.pv2_fundo, R.string.pv2_fundo_det, Protetor.FUNDO,
            mapOf(Protetor.FUNDO_TEMA to R.string.pv2_fundo_tema, Protetor.FUNDO_TERRA to R.string.pv2_fundo_terra))
        // Relógio: a lista depende da língua da TV ("por extenso" só em português).
        val lingua = java.util.Locale.getDefault().language
        val nomesRelogio = mapOf(Protetor.REL_FINO to R.string.pv2_rel_fino, Protetor.REL_EDITORIAL to R.string.pv2_rel_editorial,
            Protetor.REL_MONUMENTAL to R.string.pv2_rel_monumental, Protetor.REL_PONTEIROS to R.string.pv2_rel_ponteiros,
            Protetor.REL_EXTENSO to R.string.pv2_rel_extenso, Protetor.REL_EMPILHADO to R.string.pv2_rel_empilhado)
        lateinit var linhaRel: LinhaCfg
        linhaRel = linha(getString(R.string.pv2_relogio), getString(R.string.pv2_relogio_det),
            valor = getString(nomesRelogio.getValue(Protetor.relogio(this, lingua)))) {
            val novo = Protetor.proximoRelogio(Protetor.relogio(this, lingua), lingua)
            Protetor.RELOGIO.gravar(this, novo); linhaRel.valor(getString(nomesRelogio.getValue(novo)))
        }
        c.addView(linhaRel, lp())
        escolha(R.string.pv2_posicao, R.string.pv2_posicao_det, Protetor.POSICAO,
            mapOf(Protetor.POS_ALTO to R.string.pv2_pos_alto, Protetor.POS_BAIXO to R.string.pv2_pos_baixo, Protetor.POS_CENTRO to R.string.pv2_pos_centro))
        escolha(R.string.pv2_tamanho, R.string.pv2_tamanho_det, Protetor.TAMANHO,
            mapOf("normal" to R.string.pv2_tam_normal, "grande" to R.string.pv2_tam_grande))
        escolha(R.string.pv2_mostrar, R.string.pv2_mostrar_det, Protetor.MOSTRAR,
            mapOf(Protetor.MOSTRAR_SO_HORA to R.string.pv2_mostrar_so, Protetor.MOSTRAR_DE_VEZ to R.string.pv2_mostrar_vez,
                Protetor.MOSTRAR_SEMPRE to R.string.pv2_mostrar_sempre))
        // Variações de cada elemento (17/11/2026, Paulo: "variações também para o clima e todos os outros").
        escolha(R.string.pv2_est_avisos, R.string.pv2_est_avisos_det, Protetor.AVISOS,
            mapOf(Protetor.AVISOS_CARTOES to R.string.pv2_av_cartoes, Protetor.AVISOS_LISTA to R.string.pv2_av_lista,
                Protetor.AVISOS_UM to R.string.pv2_av_um))
        escolha(R.string.pv2_est_textos, R.string.pv2_est_textos_det, Protetor.TEXTOS,
            mapOf(Protetor.TEXTOS_MODERNO to R.string.pv2_tx_moderno, Protetor.TEXTOS_SERIFA to R.string.pv2_tx_serifa,
                Protetor.TEXTOS_MAIUSCULAS to R.string.pv2_tx_maiusculas))
        escolha(R.string.pv2_est_linhas, R.string.pv2_est_linhas_det, Protetor.LINHAS,
            mapOf(Protetor.LINHAS_ICONES to R.string.pv2_ln_icones, Protetor.LINHAS_VIDRO to R.string.pv2_ln_vidro,
                Protetor.LINHAS_TEXTO to R.string.pv2_ln_texto))
        escolha(R.string.pv2_est_credito, R.string.pv2_est_credito_det, Protetor.CREDITO,
            mapOf(Protetor.CREDITO_ETIQUETA to R.string.pv2_cr_etiqueta, Protetor.CREDITO_DISCRETO to R.string.pv2_cr_discreto))
    }

    /** Liga/desliga de cada elemento do protetor (a hora não desliga), numa seção no fim da aba. */
    private fun protetorElementos(c: LinearLayout) {
        secao(c, getString(R.string.pv2_elementos))
        val nomes = mapOf(Protetor.Elemento.SAUDACAO to R.string.pv2_el_saudacao, Protetor.Elemento.DATA to R.string.pv2_el_data,
            Protetor.Elemento.LINHA_DIA to R.string.pv2_el_linha_dia, Protetor.Elemento.AGENDA to R.string.pv2_el_agenda,
            Protetor.Elemento.FRASE to R.string.pv2_el_frase, Protetor.Elemento.CURIOSIDADES to R.string.pv2_el_curiosidades,
            Protetor.Elemento.AVISOS to R.string.pv2_el_avisos, Protetor.Elemento.WIDGET to R.string.pv2_el_widget,
            Protetor.Elemento.CREDITO to R.string.pv2_el_credito)
        Protetor.Elemento.entries.forEach { e ->
            lateinit var l: LinhaCfg
            l = linha(getString(nomes.getValue(e)), getString(R.string.pv2_el_det), chave = Protetor.ligado(this, e)) {
                Protetor.alternar(this, e); l.ligado(Protetor.ligado(this, e))
            }
            c.addView(l, lp())
        }
    }

    private fun abaEnergia(c: LinearLayout) {
        // OK passa para a próxima opção da lista e grava na hora.
        val opcoes = listOf(60_000, 5 * 60_000, 10 * 60_000, 15 * 60_000, 30 * 60_000, Int.MAX_VALUE)
        fun rotuloTempo(ms: Int) = if (ms == Int.MAX_VALUE) getString(R.string.cfg_nunca) else getString(R.string.cfg_min, ms / 60_000)
        lateinit var linhaDescanso: LinhaCfg
        linhaDescanso = linha(getString(R.string.cl_protetor), getString(R.string.cl_protetor_det),
            valor = rotuloTempo(PrivilegioDO.tempoDescanso(this))) {
            val atual = PrivilegioDO.tempoDescanso(this)
            // Próxima opção depois da atual; se o valor atual não estiver na lista (mudado por fora), começa da primeira.
            val proximo = opcoes[(opcoes.indexOf(atual) + 1) % opcoes.size]
            if (PrivilegioDO.definirTempoDescanso(this, proximo)) linhaDescanso.valor(rotuloTempo(proximo))
            else linhaDescanso.detalhe(getString(R.string.cfg_nao_gravou_do))
        }
        c.addView(linhaDescanso, lp())
        // Estilo dos widgets de baixo do protetor (Protetor.widget, 15/11/2026). OK passa para o próximo; vale no próximo protetor.
        fun rotuloBarra(v: String) = getString(when (v) {
            Protetor.WIDGET_CARTOES -> R.string.cl_widget_cartoes
            Protetor.WIDGET_SOLTO -> R.string.cl_widget_solto
            Protetor.WIDGET_LINHA -> R.string.cl_widget_linha
            Protetor.WIDGET_GRANDE -> R.string.cl_widget_grande
            else -> R.string.cl_widget_faixa
        })
        lateinit var linhaBarra: LinhaCfg
        linhaBarra = linha(getString(R.string.cl_linha_baixo), getString(R.string.pv2_widget_det),
            valor = rotuloBarra(Protetor.widget(this))) {
            val novo = Protetor.proximoWidget(Protetor.widget(this))
            Protetor.definirWidget(this, novo)
            linhaBarra.valor(rotuloBarra(novo))
        }
        c.addView(linhaBarra, lp())
        protetorV2(c)
        // (11/11/2026) Saiu a linha "Estilo do protetor": o protetor é um só (o da Galeria) e o TEMA decide o fundo
        // (pintura na Galeria, wallpaper escuro no Escuro). O "clássico" escolhido por engano deixava o Escuro diferente.
        // Desligar sozinha (AutoDesligar): depois de X min sem ninguém apertar o controle, avisa por 60 s e apaga a TV.
        fun rotuloDesligar(m: Int) = if (m == 0) getString(R.string.cfg_nunca) else if (m % 60 == 0) "${m / 60} h" else "$m min"
        lateinit var linhaDesligar: LinhaCfg
        linhaDesligar = linha(getString(R.string.cl_desligar), getString(R.string.cl_desligar_det),
            valor = rotuloDesligar(AutoDesligar.minutos(this))) {
            val o = AutoDesligar.OPCOES
            val proximo = o[(o.indexOf(AutoDesligar.minutos(this)) + 1) % o.size]
            AutoDesligar.definir(this, proximo)
            linhaDesligar.valor(rotuloDesligar(proximo))
        }
        c.addView(linhaDesligar, lp())
        linhaQr(c, DestinoPainel.FOTOS, R.string.qrd_fotos, R.string.qrd_fotos_det)
        linhaQr(c, DestinoPainel.MURAL, R.string.qrd_mural, R.string.qrd_mural_det)
        protetorElementos(c)
        extrasDaAba(c, ExtrasAbas.Aba.ENERGIA)
    }

    // ================================================================ aba: Visitas e rotinas

    /** Família (14/11/2026): o controle parental e, embaixo, as visitas (modo visita e os apps que ele esconde). */
    private fun abaFamilia(c: LinearLayout) {
        abaParental(c)
        // Agenda da família (degrau 2, AgendaPerfisTv): o perfil troca sozinho no horário. Atrás do PIN dos pais.
        c.addView(linha(getString(R.string.agf_titulo), getString(R.string.agf_cfg_det, AgendaPerfisTv.lista(this).size), seta = true) {
            pedirPinParental {
                reconstruirAoVoltar = true
                startActivity(Intent(this, AgendaFamiliaActivity::class.java))
            }
        }, lp())
        // Família conectada (degrau 3, FamiliaConectada.kt): filhos e netos mandam foto e recado por um link. Atrás do PIN
        // dos pais (o link abre a TV para quem o tiver). Abrir a tela liga o link; "Desligar" lá dentro desliga.
        c.addView(linha(getString(R.string.famc_titulo), getString(if (FamiliaConectada.ligado(this)) R.string.famc_ligado else R.string.famc_det), seta = true) {
            // Plano (17/11/2026): o link de fora de casa é Pro; quem já ligou antes ainda entra para DESLIGAR.
            if (!FamiliaConectada.ligado(this) && !PlanosTv.pedir(this, Planos.Recurso.FAMILIA_LINK)) return@linha
            pedirPinParental {
                reconstruirAoVoltar = true
                startActivity(Intent(this, FamiliaConectadaActivity::class.java))
            }
        }, lp())
        // Cuidar de longe (ideias 77-79, 15/11/2026): pedido de ajuda, lembrete com aviso, TV que não ligou. Desligado de fábrica.
        secao(c, getString(R.string.cuidar_titulo))
        c.addView(linha(getString(R.string.cuidar_titulo), getString(
            if (CuidarDeLonge.consentido(CuidarDeLongeTv.config(this))) R.string.cuidar_cfg_ligado else R.string.cuidar_cfg_det), seta = true) {
            reconstruirAoVoltar = true
            startActivity(Intent(this, CuidarDeLongeActivity::class.java))
        }, lp())
        secao(c, getString(R.string.cfg2_sec_visitas))
        // Modo visita (ModoVisita.kt): esconde perfil, agenda e os apps marcados, até desligar. Na HOME, com o modo
        // ligado, o OK na bolinha do perfil oferece "Sair" (o popup não abre).
        lateinit var linhaVisita: LinhaCfg
        linhaVisita = linha(getString(R.string.cfg_visita), getString(R.string.cl_visita_det),
            chave = ModoVisita.ativo(this)) {
            ModoVisita.definir(this, !ModoVisita.ativo(this))
            linhaVisita.ligado(ModoVisita.ativo(this))
        }
        c.addView(linhaVisita, lp())
        // Wi-Fi das visitas em QR (ideia 54): OK configura; com a rede gravada, uma 2ª linha mostra o QR.
        c.addView(linha(getString(R.string.l51_wifi_titulo),
            WifiVisita.rede(this).ifEmpty { getString(R.string.l51_wifi_nao) }, seta = true) {
            WifiVisita.pedir(this) { if (it) recarregarAba() }
        }, lp())
        if (WifiVisita.configurado(this)) c.addView(linha(getString(R.string.l51_wifi_qr_titulo), "", seta = true) {
            WifiVisita.mostrarQr(this)
        }, lp())
        // A lista de apps do modo visita era longa e ficava aberta aqui; agora é uma tela própria (AppsEscolhaActivity).
        c.addView(linha(getString(R.string.cl_apps_visita), getString(R.string.cfg2_apps_visita_det), seta = true) {
            startActivity(Intent(this, AppsEscolhaActivity::class.java).putExtra(AppsEscolhaActivity.EXTRA_MODO, AppsEscolhaActivity.MODO_VISITA))
        }, lp())
        secaoHospede(c)
    }

    /**
     * Modo hóspede (degrau 2, 16/11/2026, casa de aluguel): ModoHospede.kt. Ligar é livre; desligar pede o PIN. A saída
     * e a hora mudam com OK (ciclos curtos, sem teclado); as regras da casa abrem um campo de texto.
     */
    private fun secaoHospede(c: LinearLayout) {
        secao(c, getString(R.string.hos_titulo))
        lateinit var chave: LinhaCfg
        chave = linha(getString(R.string.hos_titulo), getString(R.string.hos_det), chave = ModoHospede.ativo(this)) {
            if (ModoHospede.ativo(this)) ModoHospede.desligarComPin(this) { recarregarAba() }
            else if (ModoHospede.ligar(this)) recarregarAba()
            else android.widget.Toast.makeText(this, getString(R.string.emp_sem_pin), android.widget.Toast.LENGTH_LONG).show()   // sem PIN o modo trancaria a TV
        }
        c.addView(chave, lp())
        lateinit var linhaSaida: LinhaCfg
        linhaSaida = linha(getString(R.string.hos_saida), getString(R.string.hos_saida_det), valor = ModoHospede.textoSaida(this)) {
            ModoHospede.proximaSaida(this); linhaSaida.valor(ModoHospede.textoSaida(this))
        }
        c.addView(linhaSaida, lp())
        lateinit var linhaHora: LinhaCfg
        linhaHora = linha(getString(R.string.hos_hora), getString(R.string.hos_hora_det), valor = "%02d:00".format(ModoHospede.hora(this))) {
            ModoHospede.proximaHora(this)
            linhaHora.valor("%02d:00".format(ModoHospede.hora(this))); linhaSaida.valor(ModoHospede.textoSaida(this))
        }
        c.addView(linhaHora, lp())
        c.addView(linha(getString(R.string.hos_regras), ModoHospede.regras(this).ifBlank { getString(R.string.hos_regras_vazio) }, seta = true) {
            pedirRegrasHospede()
        }, lp())
        // Degrau 3: limpeza total = todos os apps da TV (menos os que mantêm a TV funcionando). Ligar desfaz a lista escolhida.
        lateinit var linhaTotal: LinhaCfg
        linhaTotal = linha(getString(R.string.hos_total), getString(R.string.hos_total_det), chave = ModoHospede.total(this)) {
            val novo = !ModoHospede.total(this)
            ModoHospede.definirTotal(this, novo)
            linhaTotal.ligado(novo)
        }
        c.addView(linhaTotal, lp())
        c.addView(linha(getString(R.string.hos_apps), getString(R.string.hos_apps_det), seta = true) {
            reconstruirAoVoltar = true
            startActivity(Intent(this, AppsEscolhaActivity::class.java).putExtra(AppsEscolhaActivity.EXTRA_MODO, AppsEscolhaActivity.MODO_HOSPEDE))
        }, lp())
        lateinit var linhaEncerrar: LinhaCfg
        linhaEncerrar = linha(getString(R.string.hos_encerrar), ModoHospede.ultimaLimpeza(this)?.let { getString(R.string.hos_ultima, it) }
            ?: getString(R.string.hos_encerrar_det), seta = true) {
            CaixaTv(this).setTitle(getString(R.string.hos_encerrar)).setMessage(getString(R.string.hos_encerrar_pergunta))
                .setNegativeButton(getString(R.string.cl_cancelar), null)
                .setPositiveButton(getString(R.string.hos_encerrar_sim)) { _, _ ->
                    linhaEncerrar.detalhe(getString(R.string.hos_limpando))
                    val app = applicationContext
                    Fundo.rodar("hospede-encerrar") {
                        val r = ModoHospede.encerrarEstadia(app)
                        runOnUiThread { if (!isDestroyed) { linhaEncerrar.detalhe(r); linhaSaida.valor(ModoHospede.textoSaida(this)) } }
                    }
                }.show().getButton(android.content.DialogInterface.BUTTON_NEGATIVE)?.requestFocus()
        }
        c.addView(linhaEncerrar, lp())
    }

    /** As regras da casa (até 600 letras), mostradas nas boas-vindas do hóspede. */
    private fun pedirRegrasHospede() {
        val campo = android.widget.EditText(this).apply {
            setText(ModoHospede.regras(this@ConfigLauncherActivity)); hint = getString(R.string.hos_regras_dica)
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 3; maxLines = 6
            filters = arrayOf(android.text.InputFilter.LengthFilter(600))
        }
        CaixaTv(this).setTitle(getString(R.string.hos_regras)).setView(FrameLayout(this).apply { setPadding(dp(20), dp(8), dp(20), 0); addView(campo) })
            .setNegativeButton(getString(R.string.cl_cancelar), null)
            .setPositiveButton("OK") { _, _ -> ModoHospede.definirRegras(this, campo.text.toString()); recarregarAba() }
            .show()
    }

    /** Casa e automações (14/11/2026): automações, a tela Casa e o que se configura pelo celular. */
    private fun abaCasa(c: LinearLayout) {
        // Automações (14/11/2026, ideia 1): o editor único de horários (as antigas Rotinas), botões, eventos e links.
        c.addView(linha(getString(R.string.aut_titulo), getString(R.string.aut_cfg_det), seta = true) {
            startActivity(Intent(this, AutomacoesActivity::class.java))
        }, lp())
        if (CasaHA.configurado(this)) c.addView(linha(getString(R.string.casa_titulo), getString(R.string.cfg2_casa_det), seta = true) {
            startActivity(Intent(this, CasaActivity::class.java))
        }, lp())
        // Ideia 43: o que só se configura pelo painel web, com o QR que abre a página certa no celular.
        secao(c, getString(R.string.cfg2_sec_celular))
        linhaQr(c, DestinoPainel.CENAS, R.string.qrd_cenas, R.string.qrd_cenas_det)
        linhaQr(c, DestinoPainel.CASA, R.string.qrd_casa, R.string.qrd_casa_det)
        linhaQr(c, DestinoPainel.TIME, R.string.qrd_time, R.string.qrd_time_det)
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
        secao(c, getString(R.string.cl_aba_rede))
        // Se o DNS fixo caiu e o launcher voltou para o automático (Dns.voltarAoAutomaticoSeQuebrado), avisa aqui.
        val caiu = Dns.servidorQueCaiu(this)
        c.addView(linha(getString(R.string.cfg_dns), Dns.rotulo(this) + (if (caiu != null) getString(R.string.cfg_dns_caiu, caiu) else ""), seta = true) {
            startActivity(Intent(this, DnsActivity::class.java))
        }, lp())
        val vpnDetalhe = if (Config.VPN_FIXA.isEmpty()) getString(R.string.cl_vpn_nao_fixa) else getString(R.string.cl_vpn_adguard)
        c.addView(linha("VPN", vpnDetalhe, valor = if (vpnAtiva()) getString(R.string.cfg_vpn_conectada) else getString(R.string.cfg_vpn_desconectada)) {
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
        linhaQr(c, DestinoPainel.ROTEADOR, R.string.qrd_roteador, R.string.qrd_roteador_det)
        extrasDaAba(c, ExtrasAbas.Aba.REDE, comSecao = false)
    }

    // ================================================================ aba: Acesso remoto

    private fun abaRemoto(c: LinearLayout) {
        // Painel no navegador (PainelWeb.kt): administrar a TV pelo celular/computador da casa, com PIN.
        secao(c, getString(R.string.cl_painel_navegador))
        lateinit var linhaPainel: LinhaCfg
        lateinit var linhaPin: LinhaCfg
        fun detalhePainel(): String = when {
            !PainelArmazem.temPin(this) -> getString(R.string.cl_painel_defina)
            PainelArmazem.desligadoPorErros(this) -> getString(R.string.cl_painel_desligou)
            PainelArmazem.ligado(this) && PainelWeb.ligado() -> getString(R.string.cl_painel_abra, PainelWeb.endereco() ?: getString(R.string.cl_sem_rede))
            PainelArmazem.ligado(this) -> getString(R.string.cl_painel_porta, Config.PAINEL_PORTA)
            else -> getString(R.string.cl_painel_det)
        }
        fun ligadoPainel() = PainelArmazem.ligado(this) && PainelArmazem.temPin(this) && !PainelArmazem.desligadoPorErros(this)
        linhaPainel = linha(getString(R.string.cl_painel), detalhePainel(), chave = ligadoPainel()) {
            when {
                !PainelArmazem.temPin(this) -> linhaPainel.detalhe(getString(R.string.cl_painel_pin_primeiro))
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
        linhaPin = linha(getString(R.string.cl_cf_pin), getString(R.string.cl_pin_det),
            valor = if (PainelArmazem.temPin(this)) getString(R.string.cl_definido) else getString(R.string.cl_nao_definido)) {
            // Trocar um PIN que já existe pede o PIN atual: senão qualquer um no controle trocava e desligava o parental.
            val trocar = { definirNovoPin(linhaPin) {
                linhaPin.valor(getString(R.string.cl_definido))
                linhaPainel.ligado(ligadoPainel()); linhaPainel.detalhe(detalhePainel())
                ui.postDelayed({ if (!isDestroyed) linhaPainel.detalhe(detalhePainel()) }, 800)
            } }
            if (PainelArmazem.temPin(this)) pedirPinParental(getString(R.string.cl_pin_atual), trocar) else trocar()
        }
        c.addView(linhaPin, lp())
        // Entrar pelo celular sem digitar o PIN (EntrarPeloQrActivity). Com alguma trava para criança ligada (controle
        // parental, hora de dormir, PIN para mexer na HOME), quem está na TV pode ser uma criança: pede o PIN antes.
        c.addView(linha(getString(R.string.cl_entrar_celular), getString(R.string.cl_entrar_celular_det), seta = true) {
            abrirQrPainel(null, null)
        }, lp())
        val acessos = PainelArmazem.resumoRegistro(this, 3)
        c.addView(info(getString(R.string.cl_ultimos), if (acessos.isEmpty()) getString(R.string.cl_nenhum_ainda) else acessos.joinToString("\n"), maxLinhas = 4), lp())

        // A TV como unidade de rede (PainelDav, 14/11/2026): WebDAV na porta https do painel, senha = PIN. Desligada
        // por padrão; a mesma chave existe na aba Configurações do painel (PainelConfig "unidadeRede").
        secao(c, getString(R.string.ur_secao))
        lateinit var linhaUnidade: LinhaCfg
        fun detalheUnidade(): String = when {
            !PainelArmazem.unidadeRede(this) -> getString(R.string.ur_det)
            !PainelArmazem.podeLigar(this) -> getString(R.string.ur_painel)
            else -> PainelWeb.enderecoUnidade(this)?.let { getString(R.string.ur_abra, it) } ?: getString(R.string.ur_https)
        }
        linhaUnidade = linha(getString(R.string.ur_titulo), detalheUnidade(), chave = PainelArmazem.unidadeRede(this)) {
            PainelArmazem.definirUnidadeRede(this, !PainelArmazem.unidadeRede(this))
            PainelWeb.unidadeRedeMudou(this)
            linhaUnidade.ligado(PainelArmazem.unidadeRede(this)); linhaUnidade.detalhe(detalheUnidade())
        }
        c.addView(linhaUnidade, lp())
        // Quem já foi aceito na TV para o MTP pela rede (MtpNaRede). OK esquece todos: cada computador pergunta de novo.
        lateinit var linhaPcs: LinhaCfg
        fun detalhePcs(): String = MtpNaRede.permitidos(this).let {
            if (it.isEmpty()) getString(R.string.mtp_pcs_nenhum) else getString(R.string.mtp_pcs_lista, it.joinToString(", "))
        }
        linhaPcs = linha(getString(R.string.mtp_pcs), detalhePcs()) {
            if (MtpNaRede.permitidos(this).isNotEmpty()) {
                MtpNaRede.esquecerTodos(this)
                android.widget.Toast.makeText(this, R.string.mtp_pcs_esquecidos, android.widget.Toast.LENGTH_SHORT).show()
                linhaPcs.detalhe(detalhePcs())
            }
        }
        c.addView(linhaPcs, lp())

        // Claude pela internet (McpServidor + TunelTailscale, 05/11/2026). A chave do Tailscale se cola no painel (PIN).
        secao(c, getString(R.string.cl_claude_internet))
        lateinit var linhaMcp: LinhaCfg
        fun detalheMcp(): String {
            val nome = TunelTailscale.nome(this)
            return when {
                !PainelArmazem.temPin(this) -> getString(R.string.cl_mcp_pin)
                !McpServidor.ligado(this) -> getString(R.string.cl_mcp_off)
                TunelTailscale.urlLogin != null || TunelTailscale.urlAtivar != null -> getString(R.string.cl_mcp_tailscale)
                TunelTailscale.rodando() && nome != null -> "https://$nome/mcp"
                else -> getString(R.string.cl_mcp_subindo, TunelTailscale.ultimoErro(this)?.let { getString(R.string.cl_ultimo_erro, it) } ?: "")
            }
        }
        linhaMcp = linha(getString(R.string.cl_acesso_claude), detalheMcp(), chave = McpServidor.ligado(this)) {
            if (!PainelArmazem.temPin(this)) { linhaMcp.detalhe(detalheMcp()); return@linha }
            McpServidor.definirLigado(this, !McpServidor.ligado(this))
            linhaMcp.ligado(McpServidor.ligado(this)); linhaMcp.detalhe(detalheMcp())
            ui.postDelayed({ if (!isDestroyed) linhaMcp.detalhe(detalheMcp()) }, 3000)
        }
        c.addView(linhaMcp, lp())
        c.addView(linha(getString(R.string.cl_conectar_qr), getString(R.string.cl_conectar_qr_det), seta = true) {
            startActivity(Intent(this, ConectarClaudeActivity::class.java))
        }, lp())
        lateinit var linhaRede: LinhaCfg
        linhaRede = linha(getString(R.string.cl_rede_tailscale), getString(R.string.cl_rede_tailscale_det),
            chave = TunelTailscale.redeLigada(this)) {
            TunelTailscale.definirRede(this, !TunelTailscale.redeLigada(this)); linhaRede.ligado(TunelTailscale.redeLigada(this))
        }
        c.addView(linhaRede, lp())
        lateinit var linhaShell: LinhaCfg
        linhaShell = linha(getString(R.string.cl_shell_claude), getString(R.string.cl_shell_claude_det),
            chave = McpServidor.shellLigado(this)) {
            McpServidor.definirShell(this, !McpServidor.shellLigado(this)); linhaShell.ligado(McpServidor.shellLigado(this))
        }
        c.addView(linhaShell, lp())
        lateinit var linhaLogins: LinhaCfg
        linhaLogins = linha(getString(R.string.cl_logins), getString(R.string.cl_logins_det), valor = getString(R.string.cl_ativos, McpServidor.logins(this))) {
            McpServidor.revogar(this)
            linhaLogins.valor(getString(R.string.cl_ativos, McpServidor.logins(this))); linhaLogins.detalhe(getString(R.string.cl_revogados))
        }
        c.addView(linhaLogins, lp())
        extrasDaAba(c, ExtrasAbas.Aba.REMOTO)
    }

    // ================================================================ QR que abre o painel na página certa (ideia 43)

    /**
     * Abre o QR de entrada no painel ([EntrarPeloQrActivity]); com [destino], o celular entra já naquela página.
     * Com alguma trava para criança ligada (controle parental, hora de dormir, PIN para mexer na HOME), quem está na
     * TV pode ser uma criança: pede o PIN antes.
     */
    private fun abrirQrPainel(destino: DestinoPainel?, titulo: String?) {
        val abrir = {
            startActivity(Intent(this, EntrarPeloQrActivity::class.java)
                .putExtra(EntrarPeloQrActivity.EXTRA_DESTINO, destino?.name).putExtra(EntrarPeloQrActivity.EXTRA_TITULO, titulo))
        }
        when {
            !PainelArmazem.podeLigar(this) || !PainelWeb.ligado() -> aviso(getString(R.string.cl_ligue_painel))
            PlayerArmazem.parental(this).ligado || Extras.ligado(this, Extras.Chave.HORA_DORMIR) ||
                Extras.ligado(this, Extras.Chave.PIN_HOME) -> pedirPinParental(getString(R.string.cl_pin_painel), abrir)
            else -> abrir()
        }
    }

    /** Linha "<algo> pelo celular": mostra o QR que entra no painel já em [destino]. */
    private fun linhaQr(c: LinearLayout, destino: DestinoPainel, titulo: Int, detalhe: Int) {
        val t = getString(titulo)
        c.addView(linha(t, getString(detalhe), seta = true) { abrirQrPainel(destino, t) }, lp())
    }

    // ================================================================ chaves de Extras dentro das abas (ideia 46)

    /**
     * As chaves de [Extras] que moram em [aba] ([ExtrasAbas]), numa seção "Mais opções" no fim dela. As que o painel
     * protege pedem o PIN aqui também (o mesmo PIN, com as mesmas tentativas, do controle parental).
     */
    private fun extrasDaAba(c: LinearLayout, aba: ExtrasAbas.Aba, comSecao: Boolean = true) {
        val porNome = Extras.Chave.values().associateBy { it.name }
        val chaves = ExtrasAbas.daAba(aba, porNome.keys).mapNotNull { porNome[it] }
            // "Entradas HDMI" num Chromecast (sem entrada) era uma chave que não faz nada (visto na Sala, 17/11/2026).
            .filter { it != Extras.Chave.ENTRADAS_TV || EntradasTv.existe(this) }
        if (chaves.isEmpty()) return
        if (comSecao) secao(c, getString(R.string.cfg_extras_secao))
        for (k in chaves) {
            lateinit var l: LinhaCfg
            l = linha(ExtrasTv.rotulo(this, k), "", chave = Extras.ligado(this, k)) {
                val mudar = { ExtrasTv.alternar(this, k); l.ligado(Extras.ligado(this, k)) }
                val titulo = getString(if (k == Extras.Chave.HORA_DORMIR) R.string.dormir_pin else R.string.player_pin_titulo)
                when {
                    !Extras.precisaPinNaTv(k, Extras.ligado(this, k), PainelArmazem.temPin(this)) -> mudar()
                    // Chaves de família (ideia 34): o PIN das crianças também vale, como na tela Extras antes.
                    k in Extras.CHAVES_FAMILIA -> PinTv.pedir(this, titulo, familia = true) { certo -> if (certo) mudar() }
                    else -> pedirPinParental(titulo, mudar)
                }
            }
            c.addView(l, lp())
        }
    }

    // ================================================================ aba: Sistema

    private fun abaSistema(c: LinearLayout) {
        // Degrau 2 (UsoTv): Simples / Completo / Para quem cuida. OK troca; as abas técnicas aparecem ou somem na hora.
        lateinit var linhaUso: LinhaCfg
        linhaUso = linha(getString(R.string.uso_titulo), getString(R.string.uso_det), valor = nomeUso(UsoTv.atual(this))) {
            val novo = UsoTv.proximo(UsoTv.atual(this))
            UsoTv.escolher(this, novo)
            linhaUso.valor(nomeUso(novo))
            mostrarAbasDoUso()
        }
        c.addView(linhaUso, lp())
        // Degrau 4: receitas prontas ("Para quem é esta TV?"), com Desfazer (ReceitasTv.kt).
        c.addView(linha(getString(R.string.rec_titulo), getString(R.string.rec_subtitulo), seta = true) {
            startActivity(Intent(this, ReceitasActivity::class.java))
        }, lp())
        // Diagnóstico de 1 botão (04/11/2026): o 1º da aba, para ser o OK mais rápido quando "o app parou".
        c.addView(linha(getString(R.string.cfg_diag), getString(R.string.cfg_diag_det), seta = true) {
            startActivity(Intent(this, DiagnosticoActivity::class.java))
        }, lp())
        // Degrau 3: "Nada sai da sua casa", o compromisso de privacidade em uma tela (o mesmo texto do site).
        c.addView(linha(getString(R.string.nsc_titulo), getString(R.string.nsc_det), seta = true) {
            CaixaTv(this).setTitle(getString(R.string.nsc_titulo)).setMessage(getString(R.string.nsc_texto))
                .setPositiveButton("OK", null).show()
        }, lp())
        // Degrau 3: relatar um problema pelo celular (QR com os dados técnicos, RelatarProblema.kt).
        c.addView(linha(getString(R.string.rel_titulo), getString(R.string.rel_linha_det), seta = true) {
            startActivity(Intent(this, RelatarProblemaActivity::class.java))
        }, lp())
        // Backup noturno cifrado (BackupNoturno.kt): as opções ficam numa tela própria; ao voltar, o resumo se atualiza.
        c.addView(linha(getString(R.string.bkn_titulo), BackupNoturno.resumo(this), seta = true) {
            reconstruirAoVoltar = true
            startActivity(Intent(this, BackupNoturnoActivity::class.java))
        }, lp())

        // Uso local das funções (ideia 67): o que é usado e o que ninguém abre. Fica só na TV.
        c.addView(linha(getString(R.string.l51_uso_titulo), getString(R.string.l51_uso_det), seta = true) {
            UsoLocal.mostrar(this)
        }, lp())

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
        // Esta versão (14/11/2026, ideias 45 e 63): o que mudou e o roteiro de conferência com o controle.
        val novidades = Novidades.itens(this)
        if (novidades.isNotEmpty()) c.addView(linha(getString(R.string.ver_novidades),
            getString(R.string.ver_novidades_det, novidades.size, versaoInstalada().toString()), seta = true) { Novidades.mostrar(this) }, lp())
        val idsRoteiro = ConferirTvActivity.ids(this)
        if (idsRoteiro.isNotEmpty()) c.addView(linha(getString(R.string.ver_conferir), getString(R.string.ver_conferir_det,
            RoteiroConferencia.conferidos(ConferirTvActivity.estado(this), idsRoteiro), idsRoteiro.size), seta = true) {
            reconstruirAoVoltar = true
            startActivity(Intent(this, ConferirTvActivity::class.java))
        }, lp())
        // Atualização vigiada (VigiaAtualizacao.kt): só aparece se uma versão caiu logo depois de instalar.
        VigiaAtualizacao.textoPausa(this)?.let { pausa ->
            lateinit var linhaSeguro: LinhaCfg
            linhaSeguro = linha(getString(R.string.cfg_vig_liberar), pausa + getString(R.string.cfg_vig_liberar_det), seta = true) {
                VigiaAtualizacao.liberar(this)
                linhaSeguro.visibility = View.GONE
            }
            c.addView(linhaSeguro, lp())
        }

        // Sobre: números úteis para saber se algo por trás dos panos está ok, sem precisar do logcat.
        secao(c, getString(R.string.cl_sobre))
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

    /** Ajuda (14/11/2026): um assunto do manual por linha; OK abre o texto inteiro (AjudaActivity), com ◀ ▶ entre eles. */
    private fun abaAjuda(c: LinearLayout) {
        Manual.TOPICOS.forEachIndexed { i, id ->
            c.addView(linha(AjudaActivity.texto(this, id, Manual.Parte.TITULO), AjudaActivity.texto(this, id, Manual.Parte.RESUMO), seta = true) {
                startActivity(Intent(this, AjudaActivity::class.java).putExtra(AjudaActivity.EXTRA_TOPICO, i))
            }, lp())
        }
    }

    /**
     * Avançado (14/11/2026): o que quase nunca se mexe e exige saber o que é (rede, ferramentas, confirmações do Claude,
     * apps de fábrica, permissões e o shell). Fica na última aba, fora do caminho do dia a dia.
     */
    private fun abaAvancado(c: LinearLayout) {
        abaRede(c)
        secao(c, getString(R.string.cfg2_sec_ferramentas))
        // Extras (10/11/2026): as chaves das ideias da pesquisa ficam numa tela própria (ExtrasActivity).
        c.addView(linha(getString(R.string.extras_titulo), getString(R.string.extras_cfg_det), seta = true) {
            startActivity(Intent(this, ExtrasActivity::class.java))
        }, lp())
        extrasDaAba(c, ExtrasAbas.Aba.SISTEMA, comSecao = false)

        confirmacoes(c)
        emprestimoShell(c)

        // Apps ocultos pelo Device Owner (Config.APPS_OCULTAR/PrivilegioDO.ocultar): libera a memória sem desinstalar.
        // Chave ligada = app visível.
        if (Config.APPS_OCULTAR.isNotEmpty()) {
            secao(c, getString(R.string.cfg2_sec_ocultos_sistema))
            Config.APPS_OCULTAR.forEach { pacote ->
                val rotulo = try {
                    // MATCH_UNINSTALLED_PACKAGES: oculto pelo dono, o app some do getApplicationInfo comum e a linha mostrava
                    // "com.google.android.apps.tv.dreamx" em vez do nome (Sala, 17/11/2026).
                    packageManager.getApplicationLabel(packageManager.getApplicationInfo(pacote,
                        android.content.pm.PackageManager.MATCH_UNINSTALLED_PACKAGES)).toString()
                } catch (e: Exception) { pacote }
                lateinit var linhaApp: LinhaCfg
                // O detalhe acompanha a chave: dizia "visível na TV" também com o app escondido (Sala, 17/11/2026).
                fun detalheApp(visivel: Boolean) = getString(if (visivel) R.string.cl_visivel else R.string.cl_escondido)
                val visivelAgora = !PrivilegioDO.estaOculto(this, pacote)
                linhaApp = linha(rotulo, detalheApp(visivelAgora), chave = visivelAgora) {
                    // Fora da thread da tela: sem Device Owner o ocultar passa pelo shell e pode demorar (Congelar.kt).
                    val app = applicationContext
                    Fundo.rodar {
                        val ocultoAntes = PrivilegioDO.estaOculto(app, pacote)
                        val mudou = PrivilegioDO.ocultar(app, pacote, !ocultoAntes)
                        val ocultoAgora = PrivilegioDO.estaOculto(app, pacote)
                        runOnUiThread {
                            if (isDestroyed) return@runOnUiThread
                            if (mudou) { linhaApp.ligado(!ocultoAgora); linhaApp.detalhe(detalheApp(!ocultoAgora)) } else linhaApp.detalhe(getString(R.string.cfg_nao_mudou_do))
                        }
                    }
                }
                c.addView(linhaApp, lp())
            }
        }

        // Limpeza de apps de fábrica (Debloat.kt, 05/11/2026): só os apps já testados nesta TV, com desfazer.
        // Chave ligada = app visível (como na seção de cima).
        secao(c, getString(R.string.cl_limpeza))
        val limpaveis = Debloat.disponiveis(this)
        if (limpaveis.isEmpty()) c.addView(info(getString(R.string.cl_nenhum_liberado), getString(R.string.cl_nenhum_liberado_det)), lp())
        limpaveis.forEach { item ->
            lateinit var linhaApp: LinhaCfg
            linhaApp = linha(nomeDebloat(item), getString(R.string.cl_ocultar_perde, perdeDebloat(item)), chave = !PrivilegioDO.estaOculto(this, item.pacote)) {
                val app = applicationContext
                Fundo.rodar {   // fora da thread da tela (sem Device Owner vai pelo shell, ver Congelar.kt)
                    val ocultar = !PrivilegioDO.estaOculto(app, item.pacote)
                    val mudou = Debloat.definir(app, item.pacote, ocultar)
                    val ocultoAgora = PrivilegioDO.estaOculto(app, item.pacote)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        if (mudou) linhaApp.ligado(!ocultoAgora)
                        else linhaApp.detalhe(getString(R.string.cl_nao_mudou))
                    }
                }
            }
            c.addView(linhaApp, lp())
        }
        if (Debloat.ocultosPorAqui(this).isNotEmpty()) {
            lateinit var linhaDesfazer: LinhaCfg
            linhaDesfazer = linha(getString(R.string.cl_mostrar_todos), getString(R.string.cl_mostrar_todos_det), seta = true) {
                val app = applicationContext
                Fundo.rodar {
                    val n = Debloat.desfazerTudo(app)
                    runOnUiThread {
                        if (isDestroyed) return@runOnUiThread
                        linhaDesfazer.detalhe(getString(R.string.cl_apps_de_volta, n))
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
        pedirPin(getString(R.string.cl_novo_pin, PinSeguro.MIN_DIGITOS, PinSeguro.MAX_DIGITOS)) { pin ->
            if (!PinSeguro.valido(pin)) { avisar(getString(R.string.cl_pin_invalido, PinSeguro.MIN_DIGITOS, PinSeguro.MAX_DIGITOS)); return@pedirPin }
            pedirPin(getString(R.string.cl_pin_de_novo)) { pin2 ->
                if (pin2 != pin) { avisar(getString(R.string.cl_pin_nao_bateu)); return@pedirPin }
                avisar(getString(R.string.cl_gravando))
                // PBKDF2 com 100 mil voltas leva um tempo num ARMv7: fora da thread da tela.
                trabalho.execute {
                    PainelArmazem.definirPin(this, pin)
                    ui.post {
                        if (isDestroyed) return@post
                        PainelArmazem.definirLigado(this, true)
                        PainelWeb.recomecar(this)
                        pinValidoAte = System.currentTimeMillis() + PIN_VALE_MS
                        avisar(getString(R.string.cl_pin_definido))
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
            .setNegativeButton(getString(R.string.cl_cancelar), null)
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
        val cep = campo(getString(R.string.cl_cep), 8, atual?.cep.orEmpty())
        val numero = campo(getString(R.string.cl_numero_casa), 6, atual?.numero.orEmpty())
        val caixa = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(cep); addView(numero)
        }
        CaixaTv(this, android.R.style.Theme_Material_Dialog_Alert)
            .setTitle(getString(R.string.cl_end_clima))
            .setView(caixa)
            .setPositiveButton("OK") { _, _ ->
                if (cep.text.isBlank()) { LocalClima.limpar(this); recarregarAba() }
                else aoConfirmar(cep.text.toString(), numero.text.toString())
            }
            .setNegativeButton(getString(R.string.cl_cancelar), null)
            .show()
        cep.requestFocus()
    }

    /** Recado curto: o aviso padrão do app (pílula no canto de cima), não mais um texto trocado no rodapé. */
    private fun aviso(t: String) = AvisoTv.rapido(this, getString(R.string.cfg_titulo), t, 4)

    // ================================================================ textos de objetos puros (idioma da TV)

    /** Rótulos da área Conta no idioma da TV (ContaIptv é puro e testado na JVM; o padrão dele é o português). */
    private val textosConta by lazy {
        ContaIptv.Textos(
            lista = getString(R.string.cl_ci_lista), semNome = getString(R.string.cl_ci_sem_nome),
            usuario = getString(R.string.cl_ci_usuario), tipoLogin = getString(R.string.cl_ci_tipo),
            servidor = getString(R.string.cl_ci_servidor), situacao = getString(R.string.cl_ci_situacao),
            naoRespondeu = getString(R.string.cl_ci_nao_respondeu), m3uComum = getString(R.string.cl_ci_m3u_comum),
            criada = getString(R.string.cl_ci_criada), naoInformado = getString(R.string.cl_ci_nao_informado),
            vence = getString(R.string.cl_ci_vence), semValidade = getString(R.string.cl_ci_sem_validade),
            conexoes = getString(R.string.cl_ci_conexoes), emUso = { max, uso -> getString(R.string.cl_ci_em_uso, max, uso) },
            recado = getString(R.string.cl_ci_recado), m3uXtream = getString(R.string.cl_ci_m3u_xtream),
            ativa = getString(R.string.cl_ci_ativa), ativaTeste = getString(R.string.cl_ci_ativa_teste),
            desativada = { motivo -> getString(R.string.cl_ci_desativada, motivo) },
            vencida = getString(R.string.cl_ci_vencida), banida = getString(R.string.cl_ci_banida),
            desligada = getString(R.string.cl_ci_desligada), recusado = getString(R.string.cl_ci_recusado),
            faltam = { d -> getString(R.string.cl_ci_faltam, d) }, falta1 = getString(R.string.cl_ci_falta1),
            venceHoje = getString(R.string.cl_ci_vence_hoje), venceuOntem = getString(R.string.cl_ci_venceu_ontem),
            venceuHa = { d -> getString(R.string.cl_ci_venceu_ha, d) },
            formatoData = getString(R.string.cl_ci_formato_data), local = resources.configuration.locales[0],
        )
    }

    /** Confirmacoes é puro (testado na JVM, também usado pelo painel): o texto da tela vem daqui, no idioma da TV. */
    private fun tituloAcao(a: Confirmacoes.Acao): String = getString(ConfirmacoesTv.tituloRes(a))
    private fun explicaAcao(a: Confirmacoes.Acao): String = getString(ConfirmacoesTv.explicacaoRes(a))
    private fun rotuloModo(m: Confirmacoes.Modo): String = getString(ConfirmacoesTv.modoRes(m))

    /** Nome e "o que se perde" da limpeza (Debloat.LISTA guarda o português; nome de marca fica como está). */
    private fun nomeDebloat(i: Debloat.Item): String = when (i.pacote) {
        "com.google.android.videos" -> getString(R.string.cl_db_videos)
        "com.google.android.tvrecommendations" -> getString(R.string.cl_db_recom)
        else -> i.nome
    }

    private fun perdeDebloat(i: Debloat.Item): String = when (i.pacote) {
        "com.google.android.marvin.talkback" -> getString(R.string.cl_db_talkback_perde)
        "com.google.android.videos" -> getString(R.string.cl_db_videos_perde)
        "com.google.android.youtube.tvkids" -> getString(R.string.cl_db_kids_perde)
        "com.google.android.play.games" -> getString(R.string.cl_db_games_perde)
        "com.google.android.tvrecommendations" -> getString(R.string.cl_db_recom_perde)
        // Ideia 70: item da comunidade traz o português e, se houver, o inglês.
        else -> if (resources.configuration.locales[0].language == "pt") i.perde else i.perdeEn ?: i.perde
    }

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
            setOnClickListener { UsoLocal.contar(this@ConfigLauncherActivity, "opcao:$titulo"); aoClicar() }   // ideia 67
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
            contentDescription = "${tTitulo.text}: ${if (sim) getString(R.string.cl_ligado) else getString(R.string.cl_desligado)}"
        }

        /** Interruptor: ligado = acento (no foco, acento escuro); desligado = trilho apagado. */
        private fun pintarChave(sim: Boolean) {
            trilho?.background = Estilo.fundo(context,
                when { sim && focada -> Estilo.ACENTO_NO_FOCO; sim -> Estilo.ACENTO; focada -> 0x330E1020; else -> 0x33FFFFFF },
                Estilo.RAIO_PILULA)
            bolinha?.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (sim) (if (focada) Estilo.FOCO else Estilo.FUNDO) else (if (focada) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO))
            }
        }

        /** Mostra o valor atual à direita (ex.: "5 min", "Escuro"). */
        fun valor(v: String) {
            // Uma linha só e largura com teto: um valor longo (ex.: nome de arquivo do "Meu fundo") espremia o título
            // em "Meu…" (visto na Sala, 17/11/2026). MIDDLE guarda o começo, a extensão e o "›".
            val t = tValor ?: Estilo.texto(context, Estilo.Papel.CORPO).apply {
                setSingleLine(true); ellipsize = TextUtils.TruncateAt.MIDDLE; maxWidth = dp(360)
            }.also { direita.removeAllViews(); direita.addView(it); tValor = it }
            t.text = "$v  ›"
            pintarTextos()
        }

        fun seta() {
            direita.removeAllViews()
            direita.addView(Estilo.texto(context, Estilo.Papel.TITULO, "›").apply { typeface = Estilo.fonte(200) }.also { tSeta = it })
            pintarTextos()
        }
    }

    private fun nomePronto(id: String) = getString(when (id) {
        "leitura" -> R.string.vpr_leitura; "galeria" -> R.string.vpr_galeria; "galeria_grande" -> R.string.vpr_galeria_grande
        "grade" -> R.string.vpr_grade; "grade_grande" -> R.string.vpr_grade_grande; "cores" -> R.string.vpr_cores
        "cinema" -> R.string.vpr_cinema; else -> R.string.vpr_classico
    })

    /** O QR do "Compartilhar visual": cartão por cima, com o link curto embaixo para quem não tem leitor de QR. */
    private fun mostrarQrVisual() {
        val link = VisualCodigoTv.link(this)
        val d = android.app.Dialog(this)
        val fechar = Estilo.botao(this, getString(R.string.vq_fechar)).apply { setOnClickListener { d.dismiss() } }
        val qr = android.widget.ImageView(this).apply {
            setImageBitmap(runCatching { QrCodigo.bitmap(link, dp(280)) }.getOrNull())
        }
        val tela = Estilo.dialogo(this, null, getString(R.string.vq_titulo), getString(R.string.vq_texto), listOf(fechar))
        // o QR entra no cartão antes do título (o cartão é o 1º filho do véu)
        (tela.getChildAt(0) as android.widget.LinearLayout).addView(qr, 0,
            android.widget.LinearLayout.LayoutParams(dp(280), dp(280)).apply { bottomMargin = dp(16) })
        d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        d.setContentView(tela)
        d.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            setLayout(-1, -1)
        }
        d.show()
        fechar.requestFocus()
    }
}
