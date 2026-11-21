package br.paulo.tvlauncher

import android.app.Activity
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
import br.paulo.tvlauncher.alarm.AutoDesligar
import br.paulo.tvlauncher.family.CuidarDeLonge
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.family.PinNaTela
import br.paulo.tvlauncher.family.PinTv
import br.paulo.tvlauncher.family.UsoLocal
import br.paulo.tvlauncher.family.UsoTv
import br.paulo.tvlauncher.home.SofalyWordmark
import br.paulo.tvlauncher.home.WrapRow
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.mcp.McpServidor
import br.paulo.tvlauncher.mcp.TunelTailscale
import br.paulo.tvlauncher.network.CasaHA
import br.paulo.tvlauncher.network.Dns
import br.paulo.tvlauncher.network.Velocidade
import br.paulo.tvlauncher.network.WifiVisita
import br.paulo.tvlauncher.panel.DestinoPainel
import br.paulo.tvlauncher.panel.MtpNaRede
import br.paulo.tvlauncher.panel.PainelArmazem
import br.paulo.tvlauncher.panel.PainelWeb
import br.paulo.tvlauncher.panel.PinSeguro
import br.paulo.tvlauncher.panel.QrCodigo
import br.paulo.tvlauncher.player.ContaIptv
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.avisar
import br.paulo.tvlauncher.privilege.Confirmacoes
import br.paulo.tvlauncher.privilege.Debloat
import br.paulo.tvlauncher.privilege.PrivilegioDO
import br.paulo.tvlauncher.screensaver.Protetor
import br.paulo.tvlauncher.settings.AjudaQr
import br.paulo.tvlauncher.settings.BuscaAjustes
import br.paulo.tvlauncher.settings.Extras
import br.paulo.tvlauncher.settings.ExtrasAbas
import br.paulo.tvlauncher.settings.ExtrasTv
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.settings.Manual
import br.paulo.tvlauncher.settings.VisualCodigoTv
import br.paulo.tvlauncher.settings.abaAjuda
import br.paulo.tvlauncher.settings.abaAparencia
import br.paulo.tvlauncher.settings.abaAvancado
import br.paulo.tvlauncher.settings.abaConta
import br.paulo.tvlauncher.settings.abaParental
import br.paulo.tvlauncher.settings.abaSistema
import br.paulo.tvlauncher.settings.itemDaBusca
import br.paulo.tvlauncher.settings.lembrarRecente
import br.paulo.tvlauncher.settings.mostrarBusca
import br.paulo.tvlauncher.settings.pedirPinParental
import br.paulo.tvlauncher.settings.voltarAoMenu
import br.paulo.tvlauncher.weather.LocalClima
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
        const val ABA_REMOTO = 5
        const val ABA_AVANCADO = 8
        internal const val PIN_VALE_MS = 2 * 60_000L   // depois de acertar o PIN aqui, 2 min sem pedir de novo
        private const val ESTADO_LIBERADO = "hospede_liberado"
        private const val ESTADO_LINHA = "linha_foco"
        internal const val TAG = "TvLauncher.Config"
        /** [abaMontada] quando o conteúdo mostra a busca nos ajustes (12.2), não uma aba. */
        internal const val BUSCA = -2
    }

    internal val trabalho = Executors.newSingleThreadExecutor()   // rede, PBKDF2 e PackageManager fora da thread da tela
    /** Leituras locais rápidas (contas de TV no Keystore): fila separada, para não esperar uma consulta de rede da `trabalho`. */
    internal val leitura = Executors.newSingleThreadExecutor()
    @Volatile private var medindoVelocidade = false
    internal val ui = Handler(Looper.getMainLooper())

    private val textoClaro = Estilo.TEXTO     // cores do sistema de design
    private val textoSuave = Estilo.TEXTO_2
    internal val coral = Estilo.ERRO
    private val fonteMedia = Fontes.nunito(500)

    /** Uma aba do menu: título, explicação curta (aparece no alto do conteúdo) e quem monta o conteúdo. */
    /** [area] = cor da área (estudo do painel 20/11/2026, regra "cor quer dizer área"): a mesma do ícone da página no
     *  painel web, num ponto antes do nome. null = neutra de propósito (Sistema, Ajuda, Avançado: técnico). */
    internal class Aba(val titulo: String, val explica: String, val area: Int?, val montar: (LinearLayout) -> Unit)

    internal val abas: List<Aba> by lazy {
        // 14/11/2026 (pedido do dono: "complexo demais"): abas por assunto, na ordem do uso do dia a dia; o técnico
        // (rede, permissões, shell, limpeza de fábrica, confirmações) foi para "Avançado", a última. Antes: 8 abas
        // por origem do código (Conta, Controle parental, Aparência, Tela e energia, Visitas, Rede, Remoto, Sistema).
        listOf(
            Aba(getString(R.string.cfg2_aba_inicio), getString(R.string.cfg2_aba_inicio_det), DesignTokens.WORLD_APPS, this::abaAparencia),
            Aba(getString(R.string.cl_aba_conta), getString(R.string.cl_aba_conta_det), DesignTokens.WORLD_WATCH, this::abaConta),   // ABA_CONTA
            Aba(getString(R.string.cfg2_aba_familia), getString(R.string.cfg2_aba_familia_det), DesignTokens.AREA_HOME, this::abaFamilia),
            Aba(getString(R.string.cl_aba_energia), getString(R.string.cl_aba_energia_det), DesignTokens.AREA_HOME, this::abaEnergia),
            Aba(getString(R.string.cfg2_aba_casa), getString(R.string.cfg2_aba_casa_det), DesignTokens.WORLD_NETWORK, this::abaCasa),
            Aba(getString(R.string.cl_aba_remoto), getString(R.string.cl_aba_remoto_det), DesignTokens.WORLD_NETWORK, this::abaRemoto),
            Aba(getString(R.string.cl_aba_sistema), getString(R.string.cfg2_aba_sistema_det), null, this::abaSistema),
            // Ajuda (14/11/2026, pedido do dono): o manual de tudo o que o launcher faz, antes do Avançado.
            Aba(getString(R.string.aj_aba), getString(R.string.aj_aba_det), null, this::abaAjuda),
            Aba(getString(R.string.cfg2_aba_avancado), getString(R.string.cfg2_aba_avancado_det), null, this::abaAvancado),   // ABA_AVANCADO
        )
    }

    internal var abaAtual = 0
    internal var abaMontada = -1   // qual aba está desenhada no conteúdo agora
    // Elevação 12.2: busca nos ajustes. [indexando] = a aba sendo montada só para o índice (-1 = montagem de verdade).
    internal var indexando = -1
    internal var indice: List<BuscaAjustes.Entrada>? = null
    internal var itemBusca: TextView? = null
    internal var campoBusca: android.widget.EditText? = null
    internal val prefsBusca by lazy { getSharedPreferences("config_busca", MODE_PRIVATE) }
    private lateinit var menu: LinearLayout
    internal lateinit var conteudo: LinearLayout
    internal lateinit var rolagem: ScrollView
    private lateinit var dica: FrameLayout
    internal val itensMenu = ArrayList<TextView>()
    internal var reconstruirAoVoltar = false   // voltou de outra tela (Android, canais bloqueados): redesenha a aba
    internal var pinValidoAte = 0L
    private var liberadoHospede = false   // modo hóspede: o PIN já foi acertado nesta visita às Configurações
    // Erros do PIN guardados (TentativasPin, como no player): fechar e abrir as Configurações não dá mais 5 tentativas.
    // Preferência própria do processo da HOME ("config_pin"); a do player ("player_pin") é do processo ":player".
    internal val prefsPin by lazy { getSharedPreferences(Prefs.CONFIG_PIN, MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Elevação 2.11: toda mudança feita aqui vira um passo com "Desfazer" por 10 s (Desfazer.kt).
        VigiaDesfazer.ligar(this, getString(R.string.desf_rot_config), mostrarBarra = false) { if (::conteudo.isInitialized) recarregarAba() }
        abaAtual = (savedInstanceState?.getInt(EXTRA_ABA) ?: intent.getIntExtra(EXTRA_ABA, 0)).coerceIn(0, abas.size - 1)
        linhaSalva = savedInstanceState?.getInt(ESTADO_LINHA, -1) ?: -1
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

    /** Linha das opções que tinha o foco quando a tela se refez (alto contraste, tamanho do texto, visual pronto). */
    private var linhaSalva = -1

    private fun abrirTela() {
        setContentView(montarTela())
        mostrarAba(abaAtual)
        // Mesmo padrão de [recarregarAba]: quem mudou uma opção que refaz a tela continua na MESMA linha (pela posição),
        // não volta para a aba (visto na Sala 20/11/2026 ao ligar o alto contraste).
        val linha = linhaSalva.also { linhaSalva = -1 }
        itensMenu[abaAtual].post {
            val alvo = if (linha >= 0) focaveis(conteudo).getOrNull(linha) else null
            (alvo ?: itensMenu[abaAtual]).requestFocus()
        }
    }

    override fun onSaveInstanceState(estado: Bundle) {
        super.onSaveInstanceState(estado)
        estado.putInt(EXTRA_ABA, abaAtual)
        if (::conteudo.isInitialized) conteudo.findFocus()?.let { f ->
            estado.putInt(ESTADO_LINHA, focaveis(conteudo).indexOfFirst { it === f || ehAncestral(it, f) })
        }
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
            setPaddingRelative(dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL + 9), dp(Estilo.MARGEM_LATERAL), dp(Estilo.MARGEM_VERTICAL - 7))   // a mesma margem da HOME
            // A pílula com foco cresce 2% e passa um pouco da margem: sem isto a borda esquerda dela saía cortada (TV, 10/11/2026).
            clipChildren = false; clipToPadding = false
        }
        raiz.addView(Estilo.tituloTela(this, getString(R.string.cfg_titulo)))

        val colunas = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        menu = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; clipChildren = false; clipToPadding = false; setPaddingRelative(0, dp(4), 0, dp(4)) }
        menu.addView(itemDaBusca(), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
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
            setPaddingRelative(dp(12), dp(4), dp(24), dp(24))
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
        dica = FrameLayout(this).apply { setPaddingRelative(0, dp(12), 0, 0) }
        // Logo Sofaly nas Configurações (18/11/2026, pedido do Paulo): no canto de baixo, na linha das dicas. O canto de
        // cima é do relógio; aqui ela fica à vista sem disputar com o título.
        val rodape = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        rodape.addView(dica, LinearLayout.LayoutParams(0, WRAP, 1f))
        rodape.addView(SofalyWordmark(this, dp(26).toFloat()).apply { setPaddingRelative(0, dp(12), 0, 0) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(Estilo.E3) })
        raiz.addView(rodape, LinearLayout.LayoutParams(MATCH, WRAP))
        mostrarDica(noMenu = true)
        return raiz
    }

    /** Uso "Simples"/"Para quem cuida" (UsoTv, degrau 2): as abas técnicas somem do menu (a aberta agora fica). */
    internal fun mostrarAbasDoUso() {
        val uso = UsoTv.atual(this)
        itensMenu.forEachIndexed { i, t -> t.visibility = if (UsoTv.mostraAba(uso, i) || i == abaAtual) View.VISIBLE else View.GONE }
    }

    internal fun nomeUso(u: UsoTv) = getString(when (u) {
        UsoTv.SIMPLES -> R.string.uso_simples
        UsoTv.COMPLETO -> R.string.uso_completo
        UsoTv.CUIDA -> R.string.uso_cuida
    })

    private fun itemMenu(i: Int, aba: Aba): TextView = TextView(this).apply {
        text = aba.titulo
        Estilo.aplicar(this, Estilo.Papel.CORPO)
        setPaddingRelative(dp(20), dp(11), dp(20), dp(11))
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        isFocusable = true; isClickable = true
        // Ponto da cor da área (o mesmo do ícone no painel web). Aba neutra leva um ponto transparente do mesmo
        // tamanho, para todos os nomes começarem na mesma coluna.
        val ponto = dp(10)
        setCompoundDrawablesRelative(GradientDrawable().apply {
            shape = GradientDrawable.OVAL; setColor(aba.area ?: android.graphics.Color.TRANSPARENT); setSize(ponto, ponto)
            setBounds(0, 0, ponto, ponto)
        }, null, null, null)
        compoundDrawablePadding = dp(12)
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
    internal fun pintarItemMenu(t: TextView, foco: Boolean, escolhido: Boolean) {
        t.setTextColor(when { foco -> Estilo.TEXTO_NO_FOCO; escolhido -> Estilo.TEXTO; else -> Estilo.TEXTO_2 })
        t.typeface = Estilo.fonte(if (foco || escolhido) 600 else 500)
        t.background = when {
            foco -> Estilo.fundo(this, Estilo.FOCO, Estilo.RAIO_PILULA)
            escolhido -> Estilo.fundo(this, Estilo.SUPERFICIE_ALTA, Estilo.RAIO_PILULA)
            else -> null
        }
    }

    internal val trocarAba = Runnable { mostrarAba(abaAtual) }

    internal fun mostrarAba(i: Int) {
        abaMontada = i
        conteudo.removeAllViews()
        rolagem.scrollTo(0, 0)
        val aba = abas[i]
        conteudo.addView(Estilo.texto(this, Estilo.Papel.TITULO, aba.titulo).apply { textSize = 24f; setPaddingRelative(dp(8), dp(4), 0, 0) })   // menor que o título da tela (crítica 19/11/2026)
        conteudo.addView(Estilo.texto(this, Estilo.Papel.LEGENDA, aba.explica).apply { setPaddingRelative(dp(8), dp(6), 0, dp(12)) })
        secoesDaAba.clear()
        aba.montar(conteudo)
        montarConversas(i)
        montarIndice()
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
    internal fun recarregarAba() {
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

    internal fun focaveis(v: View, r: MutableList<View> = mutableListOf()): List<View> {
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

    internal fun primeiroFocavel(v: View): View? {
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

    internal fun mostrarDica(noMenu: Boolean) {
        dica.removeAllViews()
        dica.addView(if (noMenu) Estilo.barraDicas(this, "▲▼" to getString(R.string.cl_dica_aba), "▶ OK" to getString(R.string.cl_dica_entrar), getString(R.string.cl_tecla_voltar) to getString(R.string.cl_dica_sair))
            else Estilo.barraDicas(this, "▲▼" to getString(R.string.cl_dica_escolher), "OK" to getString(R.string.cl_dica_mudar), "◀ " + getString(R.string.cl_tecla_voltar) to getString(R.string.cl_dica_voltar)))
    }

    /** ◀ numa pílula do índice/"Conversa com" que tem outra pílula daquele lado: anda entre elas (não volta ao menu). */
    private fun irmaNaLinha(foco: View, tecla: Int): Boolean {
        val linha = foco.parent as? WrapRow ?: return false
        val prox = foco.focusSearch(if (tecla == KeyEvent.KEYCODE_DPAD_LEFT) View.FOCUS_LEFT else View.FOCUS_RIGHT)
        return prox != null && prox.parent === linha
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
            when (Rtl.tecla(e.keyCode, window.decorView)) {   // 16.8: no árabe o menu fica à direita
                KeyEvent.KEYCODE_DPAD_LEFT -> if (noConteudo && foco !is android.widget.EditText && !irmaNaLinha(foco!!, e.keyCode)) { voltarAoMenu(); return true }
                KeyEvent.KEYCODE_BACK -> if (noConteudo) { engoliuVoltar = true; voltarAoMenu(); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> if (noMenu) {
                    if (foco === itemBusca) { if (abaMontada != BUSCA) mostrarBusca(); campoBusca?.requestFocus() } else entrarNoConteudo()
                    return true
                }
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









    // ================================================================ aba: Controle parental








    // ================================================================ aba: Aparência


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
        CaixaTv(this).setTitle(getString(R.string.hos_regras)).setView(FrameLayout(this).apply { setPaddingRelative(dp(20), dp(8), dp(20), 0); addView(campo) })
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

    internal fun abaRede(c: LinearLayout) {
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
    internal fun extrasDaAba(c: LinearLayout, aba: ExtrasAbas.Aba, comSecao: Boolean = true) {
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






    // ================================================================ caixas de texto (PIN, endereço)

    /**
     * Novo PIN, digitado duas vezes. [linhaAviso] recebe os erros (null = aviso na tela). [aoGravar] roda depois de gravar.
     * Definir o PIN já liga o painel (é para isso que se define) e derruba as sessões antigas.
     */
    internal fun definirNovoPin(linhaAviso: LinhaCfg?, aoGravar: () -> Unit) {
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
    internal fun pedirPin(titulo: String, aoConfirmar: (String) -> Unit) {
        val campo = android.widget.EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            filters = arrayOf(android.text.InputFilter.LengthFilter(PinSeguro.MAX_DIGITOS))
            setSingleLine()
        }
        val moldura = FrameLayout(this).apply { setPaddingRelative(dp(20), dp(8), dp(20), 0); addView(campo) }
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
    internal fun pedirEndereco(aoConfirmar: (String, String) -> Unit) {
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
            setPaddingRelative(48, 16, 48, 0)
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
    internal fun aviso(t: String) = AvisoTv.rapido(this, getString(R.string.cfg_titulo), t, 4)

    // ================================================================ textos de objetos puros (idioma da TV)

    /** Rótulos da área Conta no idioma da TV (ContaIptv é puro e testado na JVM; o padrão dele é o português). */
    internal val textosConta by lazy {
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
    internal fun tituloAcao(a: Confirmacoes.Acao): String = getString(ConfirmacoesTv.tituloRes(a))
    internal fun explicaAcao(a: Confirmacoes.Acao): String = getString(ConfirmacoesTv.explicacaoRes(a))
    internal fun rotuloModo(m: Confirmacoes.Modo): String = getString(ConfirmacoesTv.modoRes(m))

    /** Nome e "o que se perde" da limpeza (Debloat.LISTA guarda o português; nome de marca fica como está). */
    internal fun nomeDebloat(i: Debloat.Item): String = when (i.pacote) {
        "com.google.android.videos" -> getString(R.string.cl_db_videos)
        "com.google.android.tvrecommendations" -> getString(R.string.cl_db_recom)
        else -> i.nome
    }

    internal fun perdeDebloat(i: Debloat.Item): String = when (i.pacote) {
        "com.google.android.marvin.talkback" -> getString(R.string.cl_db_talkback_perde)
        "com.google.android.videos" -> getString(R.string.cl_db_videos_perde)
        "com.google.android.youtube.tvkids" -> getString(R.string.cl_db_kids_perde)
        "com.google.android.play.games" -> getString(R.string.cl_db_games_perde)
        "com.google.android.tvrecommendations" -> getString(R.string.cl_db_recom_perde)
        // Ideia 70: item da comunidade traz o português e, se houver, o inglês.
        else -> if (resources.configuration.locales[0].language == "pt") i.perde else i.perdeEn ?: i.perde
    }

    // ================================================================ busca nos ajustes (elevação 12.2)

    internal val indiceMontando = ArrayList<BuscaAjustes.Entrada>()








    // ================================================================ peças

    @Suppress("DEPRECATION")
    internal fun versaoInstalada(): Long = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
    } catch (e: Exception) { 0L }

    /**
     * Uma linha de opção: título e explicação à esquerda; à direita, conforme o tipo:
     *  - [chave] não nulo: a chave de liga/desliga (ligada = verde à direita);
     *  - [valor] não nulo: o valor atual em destaque (ex.: "5 min");
     *  - [seta]: "›", para o que abre outra tela ou faz uma ação.
     */
    internal fun linha(titulo: String, detalhe: String, chave: Boolean? = null, valor: String? = null, seta: Boolean = false,
                      aoClicar: () -> Unit): LinhaCfg =
        LinhaCfg(this, focavel = true).apply {
            if (indexando >= 0) indiceMontando += BuscaAjustes.Entrada(indexando, titulo, detalhe)
            montarLinha(titulo, detalhe)
            if (chave != null) ligado(chave)
            if (valor != null) this.valor(valor)
            if (seta && chave == null && valor == null) this.seta()
            setOnClickListener {
                UsoLocal.contar(this@ConfigLauncherActivity, "opcao:$titulo")   // ideia 67
                if (abaMontada >= 0) lembrarRecente(abaMontada, titulo)          // elevação 12.2: "mexidos há pouco"
                aoClicar()
            }
        }

    /** Uma linha só informativa, sem foco nem clique (para ler, não para apertar OK). */
    internal fun info(titulo: String, detalhe: String, maxLinhas: Int = 3): LinhaCfg =
        LinhaCfg(this, focavel = false).apply { montarLinha(titulo, detalhe, maxLinhas) }

    /** Cartão de vidro que agrupa linhas de "rótulo: valor" (dados da conta, sobre). */
    internal fun cartao() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = vidro(false)
        setPaddingRelative(dp(24), dp(12), dp(24), dp(12))
    }

    /** "Rótulo ........ valor" numa linha do cartão. [cor] pinta o valor (alerta). */
    internal fun valor(rotulo: String, valor: String, cor: Int? = null) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPaddingRelative(0, dp(6), 0, dp(6))
        addView(texto(16f, textoSuave, fonteMedia).apply { text = rotulo }, LinearLayout.LayoutParams(dp(230), WRAP))
        addView(texto(16f, cor ?: textoClaro, fonteMedia).apply { text = valor; maxLines = 3; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    internal fun secao(c: LinearLayout, titulo: String) {
        val v = Estilo.secao(this, titulo).apply { setPaddingRelative(dp(8), dp(Estilo.E3), 0, dp(Estilo.E1)) }
        c.addView(v)
        if (c === conteudo && indexando < 0) secoesDaAba += titulo to v   // para o índice e o "Conversa com"
    }

    // ================================================================ índice e "Conversa com" (iguais ao painel web)
    // PR 6 do estudo "painel com a identidade Sofaly" (20/11/2026): as Configurações da TV com a mesma leitura do painel.
    // O ponto colorido da área já está no menu (PR 1); aqui entram o índice ("Nesta página") no alto das abas longas e,
    // no fim de um grupo, as pílulas "Conversa com" que levam ao grupo que mexe na mesma coisa (regra 3: porta, não sala).

    /** Os grupos (cabeçalhos de seção) da aba montada agora, na ordem da tela. */
    private val secoesDaAba = ArrayList<Pair<String, TextView>>()

    /**
     * Grupos que conversam (aba, título da seção). O primeiro de cada lista aponta para os outros, e cada outro aponta de
     * volta para o primeiro, como a tabela RELACOES do painel (shortcuts.js). Só pares conferidos no código:
     * o painel abre pelo QR do "Pelo celular"; as Confirmações valem para o Claude pela internet e para o empréstimo.
     */
    private val relacoes: List<List<Pair<Int, Int>>> = listOf(
        listOf(ABA_REMOTO to R.string.cl_painel_navegador, 4 to R.string.cfg2_sec_celular),
        listOf(2 to R.string.cfg2_sec_visitas, 2 to R.string.hos_titulo),
        listOf(ABA_AVANCADO to R.string.cl_sec_confirmacoes, ABA_AVANCADO to R.string.emp_secao, ABA_REMOTO to R.string.cl_claude_internet),
    )

    private fun relacionados(aba: Int, titulo: String): List<Pair<Int, String>> {
        val r = LinkedHashSet<Pair<Int, String>>()
        for (g in relacoes) {
            val l = g.map { (a, res) -> a to getString(res) }
            val k = l.indexOf(aba to titulo)
            if (k == 0) r += l.drop(1) else if (k > 0) r += l[0]
        }
        return r.toList()
    }

    /** No fim de cada grupo com parentes: "Conversa com" e uma pílula por grupo (com o nome da aba, se for outra). */
    private fun montarConversas(aba: Int) {
        for (n in secoesDaAba.indices.reversed()) {
            val (titulo, cabeca) = secoesDaAba[n]
            val alvos = relacionados(aba, titulo)
            if (alvos.isEmpty()) continue
            val fim = secoesDaAba.getOrNull(n + 1)?.let { conteudo.indexOfChild(it.second) } ?: conteudo.childCount
            if (conteudo.indexOfChild(cabeca) < 0 || fim < 0) continue
            val bloco = blocoDePilulas(getString(R.string.cfg_related_label), alvos.map { (a, t) ->
                (if (a == aba) t else t + " · " + abas[a].titulo) to { irParaSecao(a, t) }
            })
            conteudo.addView(bloco, fim, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4); bottomMargin = dp(8) })
        }
    }

    /** Aba com 3 grupos ou mais: o índice "Nesta página" logo abaixo da explicação. OK leva ao grupo. */
    private fun montarIndice() {
        if (secoesDaAba.size < 3) return
        val bloco = blocoDePilulas(getString(R.string.cfg_index_label), secoesDaAba.map { (t, cabeca) -> t to { focarSecao(cabeca) } })
        conteudo.addView(bloco, 2, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) })
    }

    private fun blocoDePilulas(rotulo: String, itens: List<Pair<String, () -> Unit>>): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        clipChildren = false; clipToPadding = false
        addView(Estilo.texto(context, Estilo.Papel.LEGENDA, rotulo).apply { setPaddingRelative(dp(8), 0, 0, dp(6)) })
        val linha = WrapRow(context, dp(10), dp(10)).apply { clipChildren = false; clipToPadding = false; setPaddingRelative(dp(4), 0, dp(4), 0) }
        for ((texto, ir) in itens) linha.addView(Estilo.pilula(context).apply {
            addView(Estilo.texto(context, Estilo.Papel.CORPO, texto).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
            setOnClickListener { ir() }
            val foco = onFocusChangeListener
            setOnFocusChangeListener { v, f -> foco?.onFocusChange(v, f); if (f) mostrarDica(noMenu = false) }
        })
        addView(linha, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /** Foco na primeira opção depois do cabeçalho [cabeca], rolando até o grupo aparecer inteiro no alto. */
    private fun focarSecao(cabeca: View) {
        val k = conteudo.indexOfChild(cabeca)
        if (k < 0) return
        val alvo = (k + 1 until conteudo.childCount).firstNotNullOfOrNull { primeiroFocavel(conteudo.getChildAt(it)) }
        alvo?.requestFocus()
        rolagem.post { rolagem.smoothScrollTo(0, (cabeca.top - dp(8)).coerceAtLeast(0)) }
    }

    /** Abre a aba [aba] (se for outra) e leva ao grupo [titulo]; sem o grupo (escondido nesta versão), fica no alto da aba. */
    private fun irParaSecao(aba: Int, titulo: String) {
        if (aba != abaMontada) {
            abaAtual = aba
            mostrarAba(aba)
            mostrarAbasDoUso()
            itensMenu.forEachIndexed { j, t -> pintarItemMenu(t, foco = false, escolhido = j == abaAtual) }
        }
        conteudo.post {
            val cabeca = secoesDaAba.firstOrNull { it.first == titulo }?.second
            if (cabeca != null) focarSecao(cabeca) else primeiroFocavel(conteudo)?.requestFocus()
        }
    }

    internal fun lp() = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(8) }

    internal fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        if (fonte != null) typeface = fonte
    }

    /** Superfície das linhas e cartões (sistema de design): repouso = branco 6% com fio; foco = branco sólido. */
    internal fun vidro(focado: Boolean): GradientDrawable =
        if (focado) Estilo.fundo(this, Estilo.FOCO, Estilo.RAIO) else Estilo.fundo(this, Estilo.SUPERFICIE, Estilo.RAIO, Estilo.BORDA)

    /**
     * A linha das Configurações: [título / detalhe] à esquerda e um acessório à direita (chave, valor ou seta).
     * Fica clara e cresce um pouco com o foco, como as linhas do popup e da tela de DNS.
     */
    internal inner class LinhaCfg(ctx: Context, focavel: Boolean) : LinearLayout(ctx) {
        private val tTitulo = Estilo.texto(ctx, Estilo.Papel.CORPO).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        private val tDetalhe = Estilo.texto(ctx, Estilo.Papel.LEGENDA).apply {
            maxLines = 3; ellipsize = TextUtils.TruncateAt.END; setPaddingRelative(0, dp(4), 0, 0)
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
            setPaddingRelative(dp(Estilo.E3), dp(14), dp(Estilo.E3), dp(14))
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

        // 3 linhas: com a letra maior (18/11/2026) a explicação não pode cortar
        fun montarLinha(titulo: String, detalhe: String, maxLinhas: Int = 3) {
            tTitulo.text = titulo
            tDetalhe.text = detalhe
            tDetalhe.maxLines = maxLinhas
            tDetalhe.visibility = if (detalhe.isEmpty()) View.GONE else View.VISIBLE
        }

        fun detalhe(novo: String) = montarLinha(tTitulo.text.toString(), novo, tDetalhe.maxLines)

        /** Foco = linha branca: os textos escurecem (o valor também; laranja ficou só para marca, foco e progresso). */
        private fun pintarTextos() {
            tTitulo.setTextColor(if (focada) Estilo.TEXTO_NO_FOCO else Estilo.TEXTO)
            tDetalhe.setTextColor(if (focada) Estilo.TEXTO_NO_FOCO_2 else Estilo.TEXTO_2)
            tValor?.setTextColor(if (focada) Estilo.TEXTO_NO_FOCO_2 else Estilo.TEXTO_2)   // valor sem laranja (crítica de design 19/11/2026)
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
                when { sim && focada -> Estilo.ACENTO_NO_FOCO; sim -> Estilo.ACENTO; focada -> 0x33121528; else -> 0x33FFFFFF },
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
            }.also {
                direita.removeAllViews()
                // WRAP explícito: o padrão do FrameLayout é MATCH_PARENT, e aí o TextView refaz o texto novo na largura
                // do ANTERIOR sem pedir layout ("Duotom" -> "Nen…m", visto na Sala, 18/11/2026).
                direita.addView(it, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.CENTER_VERTICAL or Gravity.END))
                tValor = it
            }
            t.text = "$v  ›"
            pintarTextos()
        }

        fun seta() {
            direita.removeAllViews()
            // A mesma seta do valor ("Escuro  ›"): antes era uma seta fina no tamanho de título, de outro desenho.
            direita.addView(Estilo.texto(context, Estilo.Papel.CORPO, "›").also { tSeta = it })
            pintarTextos()
        }
    }

    internal fun nomePronto(id: String) = getString(when (id) {
        "leitura" -> R.string.vpr_leitura; "galeria" -> R.string.vpr_galeria; "galeria_grande" -> R.string.vpr_galeria_grande
        "cores" -> R.string.vpr_cores; else -> R.string.vpr_classico
    })

    /** O QR do "Compartilhar visual": cartão por cima, com o link curto embaixo para quem não tem leitor de QR. */
    internal fun mostrarQrVisual() {
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
        Estilo.vestir(d, tela)
        d.show()
        fechar.requestFocus()
    }
}
