package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextClock
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val TOKEN_OCULTOS = "ocultos"   // identifica o card "Ocultos" (nenhum pacote tem esse nome)
private const val TOKEN_TV = "player:tv"      // o card "TV ao vivo" do player universal (06/11/2026; ":" não existe em pacote)

/** Partes da HOME que o adb pode esconder para medir a GPU (ver HOME_TESTE no ControleShizukuReceiver). Volta tudo ao reiniciar. */
object TesteHome {
    @Volatile var capa = true
    @Volatile var destaque = true
    @Volatile var faixa = true
    @Volatile var fundo = true
    @Volatile var capaAnima = true    // false: a capa troca sem o esmaecer de 260 ms
    @Volatile var capaCamada = true   // false: a capa sem camada de hardware
    @Volatile var aplicar: (() -> Unit)? = null
}
private const val ID_PREVIA = "previa"       // id da pilha provisoria mostrada enquanto se arrasta um app para cima de outro
private const val ICONE_ECLIPSE = "eclipse"  // no lugar de um emoji: sol coberto pela lua (IconeEclipse). So aparece durante um eclipse solar REAL (ver Eclipse.kt e o Config.kt)

/**
 * A tela inicial inteira. A interface é montada por código (sem arquivos XML de layout):
 * menos arquivos, e fica fácil de ler de cima para baixo.
 */
class MainActivity : Activity() {

    /** Um app na grade: pacote (identificador), nome e o ícone redondo da linha principal. */
    private class Item(val pacote: String, val nome: String, val icone: Drawable)

    /**
     * Um card da linha principal: um app, uma pilha de apps, ou o card "Ocultos" (sempre o ultimo).
     * [token] identifica o card: o pacote (app), "pilha:<id>" (pilha) ou TOKEN_OCULTOS.
     * Na pilha, [frente] e a posicao (em [itens]) do app que aparece na frente e que o OK abre.
     */
    private sealed class Entrada(val token: String, val nome: String) {
        class App(val item: Item) : Entrada(item.pacote, item.nome)
        class Pilha(val id: String, val itens: List<Item>, val frente: Int = 0) :
            Entrada(Organizacao.tokenDaPilha(id), itens[frente].nome) {
            val daFrente: Item get() = itens[frente]
            val titulo: String get() = itens.joinToString(" + ") { it.nome }
        }
        class Ocultos(val itens: List<Item>, nome: String) : Entrada(TOKEN_OCULTOS, nome)
        /** "TV ao vivo" (player universal): sempre o 1º card, só quando há conta de canais cadastrada. Não se move nem empilha. */
        class Tv(nome: String) : Entrada(TOKEN_TV, nome)
    }

    private val ui = Handler(Looper.getMainLooper())          // roda código na thread da tela
    private val rede = Executors.newSingleThreadExecutor()    // roda chamadas de internet fora da thread da tela

    // Paleta: tons frios e quase neutros, para a tela não competir com os banners dos apps.
    private val textoClaro = 0xFFE8EEEA.toInt()
    // Nos temas com fundo desenhado (ceu, pintura, mostrador) o cinza escuro sumia: la o "suave" e quase tao claro quanto o claro.
    private val textoSuave: Int get() = if (tema == Tema.ESCURO) 0xFF93A19A.toInt() else 0xFFCFD9D4.toInt()
    // O visual escolhido no menu do perfil (Tema.kt). Lido no onCreate; trocar de tema recria a tela.
    private var tema = Tema.ESCURO
    private var desviadaParaSegura = false   // modo de segurança: o onCreate parou cedo (nada foi montado)
    private var assinaturaFundo = ""   // "Meu fundo": arquivo|brilho|contraste quando a tela foi montada
    private var idiomaMontado = ""     // idioma em que a tela foi montada (locale está em configChanges: não recria sozinha)
    private var assinaturaLayout = ""  // layout flexível (LayoutHome): tamanho|transparente quando a tela foi montada
    private val fonteLeve by lazy { Fontes.display(this, tema) }   // hora e temperatura
    private val fonteUi by lazy { Fontes.ui(this, tema) }

    private var fundo: View? = null                 // o fundo vivo do tema (céu, pintura, mostrador)
    @Volatile private var pontoAtual: Localizacao.Ponto? = null
    private var ultimoCodigo = -1
    private var ultimoChovendo = false

    private lateinit var txtIcone: TextView   // so para o texto de reserva
    private lateinit var imgIcone: ImageView   // o icone do clima (imagem de assets/clima/)
    private lateinit var txtTemp: TextView
    private lateinit var txtChuva: TextView   // a linha informativa embaixo da temperatura: chuva prevista ou a condicao de agora
    private lateinit var linhaChuva: LinearLayout
    private lateinit var txtMax: TextView   // "↑ 32°" ao lado da temperatura
    private lateinit var txtMin: TextView   // "↓ 18°"
    private lateinit var iconeEclipse: IconeEclipse   // sol parcialmente coberto pela lua, desenhado em codigo
    // Lugar do clima trocado no painel (LocalClima.kt; o painel roda neste mesmo processo): busca o clima na hora.
    // Campo (e não lambda solta): o SharedPreferences guarda o ouvinte por referência fraca.
    private val ouvinteLocal = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, chave ->
        if (chave == LocalClima.CHAVE_LAT || chave == LocalClima.CHAVE_LON) { ui.removeCallbacks(tarefaClima); ui.postDelayed(tarefaClima, 300) }
    }
    /**
     * Despertador ou timer criado ou apagado de fora (MCP, painel, celular) com a HOME na tela: a pílula só mudava na
     * próxima volta à HOME (teste na Sala, 15/11/2026: alarme apagado e timer criado pelo MCP não apareciam).
     */
    private val ouvinteAlarmes = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.post { runCatching { atualizarPilulaAlarme() } }
    }
    private val ouvinteTimer = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        ui.post { ui.removeCallbacks(tarefaTimer); ui.post(tarefaTimer) }
    }
    private lateinit var txtDica: TextView            // guia de teclas do modo mover (some fora dele)
    private lateinit var fotoPerfil: ImageView        // a bolinha do perfil, no canto superior direito
    private var bolinhaPerfil: View? = null            // o botão em volta da foto (o que recebe o foco)
    // Abriu o popup do perfil: ao voltar de uma tela aberta por ele (Configurações, Wi-Fi...), o foco volta para a bolinha,
    // e não para o 1º app (visto na Sala em 07/11/2026). Apaga quando outra coisa da HOME ganha o foco.
    private var voltarAoPerfil = false
    // Passada na TV (12/11/2026): voltar da previsão (pílula do clima) ou do calendário (data) jogava o foco no 1º app ou no
    // último. Quem abriu uma tela pelo topo recebe o foco de volta, como a bolinha do perfil já fazia.
    private var topoRaiz: View? = null
    private var focoTopo: View? = null
    private lateinit var linhaApps: ViewGroup   // LinearLayout (fileira) ou GradeApps (grade, LayoutHome)
    // HOME recomendada (06/11/2026): capa de quem está em foco e a linha "Continuar" embaixo dos apps.
    private lateinit var capa: CapaFoco
    private lateinit var conteudo: FrameLayout          // tudo menos o fundo e a capa: sobe quando o foco vai para o Continuar
    private lateinit var caixaContinuar: LinearLayout
    private lateinit var linhaContinuar: LinearLayout
    // Banner (ou cor) por pacote, lido uma vez e FORA da thread da tela (06/11: ler na hora do foco engasgava a HOME).
    private val capas = java.util.concurrent.ConcurrentHashMap<String, Pair<Bitmap?, IntArray>>()
    // Capa montada de cada card de Assistir (CapaAtalho), com o endereço da arte usada ("" = ícone grande). Só refaz quando
    // a arte muda (outro canal, outro filme): baixar e montar a cada volta à HOME seria trabalho jogado fora.
    private val capasAtalho = java.util.concurrent.ConcurrentHashMap<IconeApp.Tipo, Pair<String, List<Bitmap>>>()
    @Volatile private var montandoCapas = false
    // Arte do último filme e da última série no fundo (12/11/2026): endereço -> imagem, para não baixar/decodificar de novo
    // a cada volta à HOME. E a ficha curta deles, para o destaque (lidas em preencherContinuar, fora da tela).
    private val arteAtalho = java.util.concurrent.ConcurrentHashMap<IconeApp.Tipo, Pair<String, Bitmap>>()
    @Volatile private var fichaFilme: PlayerArmazem.FichaHome? = null
    @Volatile private var fichaSerie: PlayerArmazem.FichaHome? = null
    @Volatile private var posicoesHome: Map<String, Posicao> = emptyMap()
    private var capaPedida: String? = null   // o pacote cuja capa a tela espera (se o foco andar, a que chegar atrasada não aparece)
    private lateinit var rolagem: FrameLayout   // HorizontalScrollView (fileira) ou ScrollView (grade)
    private var instalados: List<Item> = emptyList()          // todos os apps de TV instalados (inclui os ocultos)
    private var lista: MutableList<Entrada> = mutableListOf()   // cards da linha principal, na ordem da tela
    private var modoMover = false
    private var iconeClima: String? = null        // icone que o CLIMA mandou na ultima busca (o eclipse so o substitui enquanto dura)
    private var ceuCoberto = false                // nuvens ou chuva (codigo > 2): o eclipse nao seria visto, entao fica o icone do clima
    private var eclipseAgora: Eclipse.Estado? = null   // null = nao ha eclipse neste minuto
    private var tokenMovendo: String? = null   // card que destaca em verde-agua no modo mover (o arrastado, ou a pre-pilha)
    private var arrastado: Entrada? = null     // card que esta sendo arrastado (a lista [lista] nao muda ate confirmar)
    private var posMover = 0                   // posicao de teste do arrastado: par = ao lado, impar = em cima de um card
    private var ignorarProximoUp = false       // true logo apos segurar OK: o "soltar" dessa pressao nao confirma
    private var ultimoToken: String? = null   // card com foco: a ordem pode mudar, então guardamos o token, não a posição
    private var indiceFoco = 0                // posição do card com foco: usada quando o token some (ex.: app ocultado)

    // Tarefas que se repetem enquanto a tela está visível (e param em onPause, para não gastar rede à toa).
    private val tarefaClima = object : Runnable {
        override fun run() {
            // Clima instantâneo (auditoria 10/11/2026): o salvo já está na tela (mostrarClimaSalvo, no onResume). Só busca
            // se ele tem mais de CLIMA_A_CADA_MIN; senão espera o que falta. Antes buscava a cada volta à HOME.
            val ciclo = Config.CLIMA_A_CADA_MIN * 60_000L
            val prefs = getSharedPreferences("clima", MODE_PRIVATE)
            val idade = System.currentTimeMillis() - prefs.getLong("em", 0)
            // A linha salva vem pronta no idioma da busca: trocou o idioma, busca já (senão ficava no antigo até o ciclo).
            if (idade in 0 until ciclo && prefs.getString("idioma", null) == idiomaAtual()) { ui.postDelayed(this, ciclo - idade); return }
            buscarClima()
            ui.postDelayed(this, ciclo)
        }
    }
    private val tarefaEclipse = object : Runnable {
        override fun run() {
            recalcularDiaNoite()
            atualizarEclipse()
            atualizarFundo()   // o céu e o mostrador andam com o relógio
            ui.postDelayed(this, Config.ECLIPSE_A_CADA_SEG * 1000L)
        }
    }

    // Portao de rede: no boot, a TV demora a conectar e o primeiro buscarClima() falha calado -> o widget ficaria
    // ate 5 min mostrando nada/velho. Este observador dispara uma busca ASSIM QUE a rede sobe, se o clima estiver velho.
    private var vigiaRede: android.net.ConnectivityManager.NetworkCallback? = null

    private fun vigiarRedeParaClima() {
        if (vigiaRede != null) return
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return
        vigiaRede = object : android.net.ConnectivityManager.NetworkCallback() {
            override fun onAvailable(rede: android.net.Network) {
                // Clima fresco (< 2 min)? Entao nao precisa; senao, busca agora, fora da fila dos 5 min.
                val em = getSharedPreferences("clima", MODE_PRIVATE).getLong("em", 0)
                if (System.currentTimeMillis() - em > 2 * 60_000L) ui.post { buscarClima() }
                ui.post { avaliarSemInternet() }
            }
            // Ideia 88: a internet caiu ou voltou (validação do Android) -> a pílula "Sem internet" aparece ou some.
            override fun onLost(rede: android.net.Network) { ui.post { avaliarSemInternet() } }
            override fun onCapabilitiesChanged(rede: android.net.Network, caps: android.net.NetworkCapabilities) { ui.post { avaliarSemInternet() } }
        }
        try { cm.registerDefaultNetworkCallback(vigiaRede!!) } catch (e: Exception) { vigiaRede = null }
    }

    // ---- Ideia 88 (14/11/2026): sem internet, a HOME diz em uma linha o que continua funcionando (SemInternet.kt).
    private lateinit var pilulaSemInternet: LinearLayout
    private lateinit var txtSemInternet: TextView
    private var semInternetDesde = 0L
    /** Sem internet confirmada (a pílula está à vista): na cruz, o que depende dela diz "Precisa de internet". */
    private var semInternetCruz = false
    private val tarefaSemInternet = Runnable { avaliarSemInternet() }

    /**
     * (internet, rede local). Olha TODAS as redes, não só a padrão: com a VPN sempre ligada a padrão é o túnel, e a
     * validação dele nem sempre reflete a internet de baixo. Internet = alguma rede validada pelo Android.
     */
    private fun estadoInternet(): Pair<Boolean, Boolean> {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true to true
        val caps = runCatching { @Suppress("DEPRECATION") cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) } }.getOrNull() ?: return true to true
        val internet = caps.any { it.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) }
        val local = caps.any { it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) }
        return internet to local
    }

    private fun avaliarSemInternet() {
        ui.removeCallbacks(tarefaSemInternet)
        if (isDestroyed || !::pilulaSemInternet.isInitialized) return
        val (internet, local) = estadoInternet()
        if (internet) {
            semInternetDesde = 0L
            if (semInternetCruz) { semInternetCruz = false; atualizarCruz() }
            if (pilulaSemInternet.visibility != View.GONE) {
                if (pilulaSemInternet.hasFocus()) linhaApps.requestFocus()
                pilulaSemInternet.visibility = View.GONE
                atualizarAvisos()
            }
            return
        }
        // Espera um pouco: no boot e na troca de Wi-Fi o Android leva alguns segundos para validar a internet.
        val agora = android.os.SystemClock.elapsedRealtime()
        if (semInternetDesde == 0L) semInternetDesde = agora
        val falta = semInternetDesde + SemInternet.ESPERA_MS - agora
        if (falta > 0) { ui.postDelayed(tarefaSemInternet, falta); return }
        val app = applicationContext
        Fundo.rodar {
            // Fora da thread da tela: as contas de Na rede são decifradas do disco.
            val pendrive = runCatching { GravacaoDestino.pendrive(app) != null }.getOrDefault(false)
            val servidores = runCatching { MidiaArmazem.contas(app).map { it.servidor } }.getOrDefault(emptyList())
            val destino = when {
                pendrive -> "pendrive"
                runCatching { GravacaoDestino.pastaRede(app) }.getOrDefault("").isNotEmpty() -> "rede"
                else -> null
            }
            val itens = SemInternet.itens(SemInternet.Estado(local, pendrive, servidores, destino))
            val frase = SemInternet.frase(itens, getString(R.string.si_sem_internet), { getString(R.string.si_funcionam, it) },
                mapOf(SemInternet.Item.PENDRIVE to getString(R.string.si_pendrive),
                    SemInternet.Item.NA_REDE to getString(R.string.home_card_rede),
                    SemInternet.Item.GRAVACOES to getString(R.string.grv_tela)))
            ui.post {
                if (isDestroyed || estadoInternet().first) return@post
                txtSemInternet.text = frase
                pilulaSemInternet.visibility = View.VISIBLE
                if (!semInternetCruz) { semInternetCruz = true; atualizarCruz() }
                atualizarAvisos()
            }
        }
        ui.postDelayed(tarefaSemInternet, 60_000L)   // pendrive ligado ou tirado muda a frase
    }

    private fun pararVigiaRede() {
        val cm = getSystemService(android.net.ConnectivityManager::class.java)
        vigiaRede?.let { try { cm?.unregisterNetworkCallback(it) } catch (e: Exception) {} }
        vigiaRede = null
    }

    // ---------------------------------------------------------------- ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Modo de segurança (degrau 2): a HOME caiu 3 vezes seguidas nesta versão -> HOME mínima no lugar desta.
        if (ModoSeguro.desviar(this)) { desviadaParaSegura = true; return }
        DesempenhoTv.criou()   // guarda de desempenho (12/11/2026): o 1º quadro depois disto dá o tempo de abertura
        tema = Tema.atual(this)
        assinaturaFundo = FundoMeuDados.assinatura(this)
        idiomaMontado = idiomaAtual()
        assinaturaLayout = "${LayoutHome.tamanho(this)}|${LayoutHome.transparente(this)}|${LayoutHome.grade(this)}|${LayoutHome.cruz(this)}|${LayoutHome.iconesCruz(this)}|${LayoutHome.barraCruz(this)}|${LayoutHome.arteCruz(this)}"
        setContentView(montarTela())
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, novo ->
            if (novo != null && novo !== bolinhaPerfil) voltarAoPerfil = false
        }
        TesteHome.aplicar = { ui.post { if (!isDestroyed) aplicarTeste() } }
        aplicarTeste()
        // Foco no topo (hora, pílulas, perfil): a capa fica com o último card em foco, para o meio da tela
        // não ficar vazio (visto na Sala, 06/11); a legenda apaga (ver posicionarDestaque). Só o card "Ocultos" os apaga (ver mostrarCapa).
        window.setBackgroundDrawable(null)   // a janela não pinta o fundo padrão do tema por baixo de tudo (ver montarTela)
        Privilegio.religarAcessibilidade(this)   // religa o serviço de teclas do controle se o Android o desligou
        Privilegio.vigiarDepuracaoSemFio(this)   // religa a Depuração sem fio sempre que o Android a desligar
        PrivilegioDO.aplicar(this)   // Device Owner: fixa o nosso protetor e oculta apps substituidos (ver PrivilegioDO.kt)
        PainelWeb.ajustar(this)   // painel no navegador: só abre a porta se estiver ligado e com PIN (ver PainelWeb.kt)
        HistoricoRede.iniciar(this)   // histórico de rede (ver HistoricoRede.kt); idempotente
        AutoDesligar.iniciar(this)   // desligar sozinha depois de X min sem controle (ver AutoDesligar.kt)
        AcordarComLg.iniciar(this)   // LG ligou pelo controle dela na entrada do Chromecast: acorda o Chromecast
        if (Config.SHELL_PROPRIO_DIAG) ShellProprio.diagnosticar(this)   // MARCO 1 do shell proprio: loga chave + porta adb (ver ShellProprio.kt)
        // Shell próprio: liga o vigia (Wi-Fi, 10 min) e roda a autocura se faltar alguma permissão (GuardiaoShizuku).
        GuardiaoShizuku.vigiar(this)
        GuardiaoShizuku.garantir(this, "launcher abriu")
        BoasVindasActivity.talvezMostrar(this)   // 1ª abertura sem Device Owner: assistente (nunca na TV com DO)
        VindoDoProjectivyActivity.talvezMostrar(this)   // Projectivy no aparelho: migração guiada, uma vez
    }

    override fun onConfigurationChanged(novo: android.content.res.Configuration) {
        super.onConfigurationChanged(novo)
        // Idioma trocado com a HOME na frente: remonta já, sem esperar a próxima volta.
        if (idiomaMontado.isNotEmpty() && idiomaAtual() != idiomaMontado) recreate()
    }

    override fun onResume() {
        super.onResume()
        homeNaFrente = true
        SonsCruz.naFrente = true
        if (desviadaParaSegura) return
        ModoSeguro.aoRetomarHome(this)   // 30 s de pé = a HOME firmou (zera a conta de quedas seguidas)
        ModoHospede.talvezBoasVindas(this)   // modo hóspede: boas-vindas uma vez por estadia
        ModoHospede.conferirSaida(this)      // e a saída que passou com a TV fora da tomada
        // Agenda da família (degrau 2): a troca de perfil que passou com a TV desligada (o alarme não veio).
        Fundo.rodar("agenda-perfis") {
            if (AgendaPerfisTv.conferir(applicationContext)) runOnUiThread { if (!isDestroyed) { atualizarFotoPerfil(); reorganizar() } }
        }
        // O tema agora só troca na tela separada de Configurações do launcher (ConfigLauncherActivity), que não
        // tem como chamar recreate() nesta Activity diretamente — então aqui a gente mesmo percebe a troca ao voltar.
        if (Tema.atual(this) != tema) { recreate(); return }
        // Trocou o idioma (do sistema ou só do launcher): textos já montados (data, destaque) ficavam no antigo.
        if (idiomaAtual() != idiomaMontado) { recreate(); return }
        if (tema == Tema.MEU && FundoMeuDados.assinatura(this) != assinaturaFundo) { recreate(); return }   // outro arquivo/brilho
        if ("${LayoutHome.tamanho(this)}|${LayoutHome.transparente(this)}|${LayoutHome.grade(this)}|${LayoutHome.cruz(this)}|${LayoutHome.iconesCruz(this)}|${LayoutHome.barraCruz(this)}|${LayoutHome.arteCruz(this)}" != assinaturaLayout) { recreate(); return }   // cartões mudaram
        // Modo simples (12/11/2026, ideia 10; desligado por padrão): a HOME vira a tela de cards grandes.
        if (ModoSimples.ligado(this)) {
            try { startActivity(Intent(this, ModoSimplesActivity::class.java)); return } catch (_: Exception) {}
        }
        DesempenhoTv.ligar(this)   // conta o tempo de cada quadro da HOME, numa thread própria (DesempenhoTv)
        preencherApps()
        preencherContinuar()
        reagendarAmbiente()
        talvezPerguntarQuemVe()
        if (voltarAoPerfil) bolinhaPerfil?.requestFocus()
        else if (focoTopo?.isShown == true) focoTopo?.requestFocus()
        else if (atalhoAberto < 0 && ::linhaContinuar.isInitialized && linhaContinuar.hasFocus()) linhaApps.getChildAt(indiceFoco.coerceAtLeast(0))?.requestFocus()
        mostrarClimaSalvo()   // na hora, antes de qualquer rede
        ui.post(tarefaClima)
        ui.post(tarefaEclipse)
        ui.post(tarefaTimer)
        vigiaTocando.ligar()   // ideia 75: pílula Tocando agora, por aviso das sessões de mídia
        atualizarPilulaAlarme()   // o alarme pode ter mudado na tela do despertador
        atualizarPilulaVencimento()
        atualizarPilulaJogo()
        atualizarPilulaAniversario()
        getSharedPreferences("launcher", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(ouvinteLocal)
        getSharedPreferences("despertador", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(ouvinteAlarmes)
        getSharedPreferences("timer", MODE_PRIVATE).registerOnSharedPreferenceChangeListener(ouvinteTimer)
        vigiarRedeParaClima()   // busca o clima assim que a rede subir no boot, sem esperar o ciclo de 5 min
        avaliarSemInternet()    // ideia 88
        Privilegio.religarAcessibilidade(this)
        Privilegio.religarDepuracaoSemFio(this)   // o Android pode ter desligado a Depuração sem fio no reinício
        // Fora da thread da tela (13/11/2026): a foto da conta (AccountManager + arquivos) e os agendamentos do despertador
        // e das rotinas (AlarmManager, PendingIntent) eram 5 a 7 chamadas ao sistema a cada volta à HOME, na mesma thread
        // que atende o controle. A filaApps é uma thread só, então os agendamentos continuam um depois do outro.
        filaApps.execute {
            val foto = runCatching { avatarDaBolinha() }.getOrNull()   // a conta ativa ou uma foto pode ter mudado
            if (foto != null) ui.post { if (!isDestroyed) fotoPerfil.setImageBitmap(foto) }
            runCatching { Despertador.agendar(this) }   // garante o proximo alarme agendado
            runCatching { Rotinas.agendar(this) }       // idem para as rotinas por horário
            if (fichasHome == null) fichasHome = runCatching {   // frase do destaque dos apps (ideia 13): fora da tela
                FrasesApps.ler(ComunidadeApps.fichasJuntas(this))   // ideia 70: APK + comunidade
            }.getOrDefault(emptyMap())
            ComunidadeApps.talvezAtualizar(this)   // ideia 70: no máximo 1x por semana, numa thread
        }
        BackupAutomatico.talvezGravar(this)   // cópia diária das configurações (numa thread; no máximo 1x por dia)
        // DNS fixo que parou de responder deixa a TV "sem internet": confere ao voltar à HOME (fora da thread; só testa
        // a rede de verdade se o modo é fixo E a rede perdeu a validação — no caso normal custa quase nada).
        rede.execute { Dns.voltarAoAutomaticoSeQuebrado(this) }
    }

    override fun onPause() {
        super.onPause()
        homeNaFrente = false
        SonsCruz.naFrente = false
        ModoSeguro.aoPausarHome()
        ui.removeCallbacks(tarefaAmbiente); ui.removeCallbacks(tarefaQuemVe)
        sairAmbiente(animar = false)
        if (desviadaParaSegura) return
        focoTopo = currentFocus?.takeIf { f -> generateSequence(f.parent) { it.parent }.any { it === topoRaiz } }
        DesempenhoTv.desligar(this)
        if (::capa.isInitialized) capa.parar()   // zoom da cena: não fica pedindo quadro com a HOME por trás de um app
        ui.removeCallbacks(tarefaClima)
        ui.removeCallbacks(tarefaEclipse)
        ui.removeCallbacks(tarefaTimer)
        ui.removeCallbacks(tarefaTocando)
        vigiaTocando.desligar()
        getSharedPreferences("launcher", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(ouvinteLocal)
        getSharedPreferences("despertador", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(ouvinteAlarmes)
        getSharedPreferences("timer", MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(ouvinteTimer)
        pararVigiaRede()
        ui.removeCallbacks(tarefaSemInternet)
        menuAberto?.dismiss()
        fecharPasta()
        if (modoMover) cancelarMover()
    }

    /** HOME escondida e memória apertada: solta as capas dos apps (são refeitas na volta, fora da thread da tela). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (Memoria.soltarCapasDaHome(level, hasWindowFocus())) { capas.clear(); capasAtalho.clear(); arteAtalho.clear() }
    }

    override fun onDestroy() {
        vigiaTela?.let { runCatching { unregisterReceiver(it) } }; vigiaTela = null
        rede.shutdownNow()
        // Cada recreate() (troca de tema) cria uma Activity nova com a sua própria fila: sem isto a thread "Apps"
        // da Activity velha ficava viva para sempre. shutdown (não Now): deixa terminar uma carga em curso.
        filaApps.shutdown()
        super.onDestroy()
    }

    // Apertar HOME de novo volta o foco para o primeiro app.
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.hasCategory(Intent.CATEGORY_HOME) == true) {
            sairAmbiente(animar = false)
            ultimoToken = null
            indiceFoco = 0
            focoTopo = null
            voltarAoPerfil = false   // HOME de novo depois de bolinha > Configurações: o foco vai ao 1º app (home-12)
            // Cruz: HOME vai para Assistir; HOME de novo, para a TV (CruzLogica). Fecha o "Todos os apps".
            // Só o 2º passo (Assistir -> TV) com a HOME já na frente. Voltando de um app, o foco vai ao item que o abriu
            // (HomeCruz.voltarDoApp; dono, 18/11/2026: "deveria voltar no último app aberto").
            cruz?.let { c -> if (todosAbertos) fecharTodos(); if (homeNaFrente) c.home() else c.voltarDoApp(); c.requestFocus() }
        }
    }

    // Uma tela inicial não "fecha" com o botão Voltar.
    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {}

    // ---------------------------------------------------------------- montagem da tela

    private fun montarTela(): View {
        // Laterais em Grade.MARGEM; alturas fixas: topo em TOPO_PALCO, destaque em TOPO_DESTAQUE, apps em LINHA_APPS_HOME.
        val raiz = FrameLayout(this).apply {
            clipChildren = false   // a bolinha e o card focado crescem um pouco alem da propria caixa
            clipToPadding = false
        }

        val topo = FrameLayout(this).apply {
            clipChildren = false
            clipToPadding = false
        }

        // ---- Topo no estilo da barra do Google TV (12/11/2026, dono: "apenas o estilo, E NÃO sumir com as nossas coisas").
        // Esquerda: a bolinha do perfil solta; uma cápsula de vidro com a lupa (a busca única, que era o card Buscar), o
        // microfone (Gemini) e o clima; e uma 2ª cápsula pequena só quando há despertador, timer ou lista vencendo. Direita:
        // a hora solta, sem fundo, com a data em duas linhas ao lado. Tudo continua botão: OK na hora abre o despertador; na
        // data, a agenda; no clima, a previsão; na bolinha, o painel rápido. Foco = pílula branca (Estilo.pilula).
        val blocoHora = pilula(vidro = false).apply {
            addView(RelogioTela(this@MainActivity, data = false).apply {
                format12Hour = "HH:mm"
                format24Hour = "HH:mm"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                setTextColor(Color.WHITE)
                typeface = fonteUi
                letterSpacing = -0.01f
                includeFontPadding = false
            })
            setOnClickListener { startActivity(Intent(this@MainActivity, AlarmesActivity::class.java)) }
        }
        // Data em duas linhas pequenas ao lado da hora ("Quinta-feira" / "12 de novembro"), no lugar do "| Google TV" do
        // launcher do Google (dono, 12/11/2026: a barra no estilo da do Google, sem sumir com nada nosso).
        val blocoData = pilula(vidro = false).apply {
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                for (fmt in listOf(R.string.fmt_data_dia, R.string.fmt_data_mes)) addView(RelogioTela(this@MainActivity, data = true).apply {
                    format12Hour = getString(fmt)
                    format24Hour = getString(fmt)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    maxLines = 1
                    setTextColor(textoClaro)
                    typeface = fonteUi
                    includeFontPadding = false
                    setLineSpacing(0f, 1.1f)
                })
            })
            // No modo visita a agenda (do perfil ativo) não abre.
            setOnClickListener { if (!ModoVisita.ativo(this@MainActivity)) startActivity(Intent(this@MainActivity, CalendarioActivity::class.java)) }
        }

        // Pílula do clima: [ícone] 24° · chuva 20% (ou a condição de agora). O ícone pode ser imagem, texto ou o eclipse.
        txtIcone = texto(16f, Color.WHITE).apply { gravity = Gravity.CENTER; includeFontPadding = false }
        imgIcone = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER; visibility = View.GONE }
        iconeEclipse = IconeEclipse(this).apply { visibility = View.GONE }
        val caixaIcone = FrameLayout(this).apply {
            addView(txtIcone, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
            addView(imgIcone, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(iconeEclipse, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        txtTemp = texto(15f, Color.WHITE, fonteUi).apply { includeFontPadding = false; text = "…" }
        // Máxima e mínima não aparecem mais na HOME (estão na tela do clima); os campos ficam para o código que os preenche.
        txtMax = texto(12f, textoClaro).apply { visibility = View.GONE }
        txtMin = texto(12f, textoClaro).apply { visibility = View.GONE }
        txtChuva = texto(12.5f, textoClaro).apply {
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(320)   // 200 cortava "Poucas nuvens - Chuva 51% em 3 h" (Sala, 07/11); encolhe se faltar espaço (abaixo)
        }
        linhaChuva = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texto(12.5f, textoClaro).apply { text = " · "; includeFontPadding = false })
            addView(txtChuva)
        }
        val blocoClima = pilula(vidro = false).apply {
            addView(caixaIcone, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) })
            addView(txtTemp)
            addView(linhaChuva)
            setOnClickListener { abrirPrevisao() }
        }

        // Pílula do despertador: o próximo toque ("⏰ amanhã 6:30"). Some se não houver alarme (ver atualizarPilulaAlarme).
        txtAlarme = texto(12.5f, textoClaro).apply { includeFontPadding = false }
        pilulaAlarme = pilula(IconeLinha(IconeLinha.Tipo.DESPERTADOR), vidro = false).apply {
            addView(txtAlarme)
            setOnClickListener { startActivity(Intent(this@MainActivity, AlarmesActivity::class.java)) }
        }

        // Pílulas de 32 dp dentro de cápsulas de 48 (8 de folga em cima e embaixo: com menos a pílula focada encostava na
        // borda, visto na Sala, 12/11).
        val alturaPilula = dp(ALTURA_TOPO - 16)
        fun capsula() = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            background = Estilo.vidro(this@MainActivity, Estilo.RAIO_PILULA)
            setPadding(dp(8), 0, dp(8), 0)
        }
        fun lpPilula() = LinearLayout.LayoutParams(WRAP, alturaPilula).apply { marginEnd = dp(2) }
        // Pílula só de ícone (lupa, microfone): redonda, sem o espaço que a pílula deixa para um texto depois do ícone.
        fun pilulaIcone(icone: android.graphics.drawable.Drawable, descricao: String, acao: () -> Unit) = pilula(icone, vidro = false).apply {
            (getChildAt(0).layoutParams as LinearLayout.LayoutParams).marginEnd = 0
            setPadding(dp(8), 0, dp(8), 0)
            contentDescription = descricao   // o MCP e a acessibilidade acham o botão pelo nome
            setOnClickListener { acao() }
        }
        val lupa = pilulaIcone(IconeApp(IconeApp.Tipo.BUSCA), getString(R.string.home_busca)) { abrirBusca() }
        val voz = pilulaIcone(IconeApp(IconeApp.Tipo.MICROFONE), getString(R.string.home_falar)) { abrirBuscaPorVoz() }

        val bolinha = botaoPerfil().also { bolinhaPerfil = it }
        topo.addView(bolinha, FrameLayout.LayoutParams(dp(ALTURA_TOPO - 4), dp(ALTURA_TOPO - 4), Gravity.CENTER_VERTICAL).apply {
            leftMargin = dp(Grade.MARGEM)
        })
        // Duas cápsulas (13/11/2026, pedido do dono): uma só com a busca e a voz, outra só com o clima. Juntas numa cápsula
        // só, a busca parecia parte do clima.
        val busca = capsula().apply {
            addView(lupa, LinearLayout.LayoutParams(alturaPilula, alturaPilula).apply { marginEnd = dp(2) })
            addView(voz, LinearLayout.LayoutParams(alturaPilula, alturaPilula))
        }
        val capsulaClima = capsula().apply { addView(blocoClima, LinearLayout.LayoutParams(WRAP, alturaPilula)) }
        // Ideia 75 (14/11/2026): "Tocando agora", cápsula própria depois do clima (chave Tocando agora, em Extras). Capa do
        // que toca (ou o ícone do app), barrinhas que mexem só tocando, título e artista/app. OK pausa ou continua; segurar
        // OK abre o app. Some sem nada tocando e 10 min depois de pausar (ver mostrarTocando).
        capaTocando = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(v: View, o: android.graphics.Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(5).toFloat())
            }
            clipToOutline = true
        }
        barrasTocando = BarrasSom(this)
        val corSub = 0xB3F4F1EC.toInt()
        txtTocandoTitulo = texto(12.5f, Estilo.TEXTO, Typeface.create(fonteUi, Typeface.BOLD)).apply {
            includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(220)
        }
        txtTocandoSub = texto(12.5f, corSub).apply {
            includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(130)
        }
        pilulaTocando = pilula(vidro = false).apply {
            setPadding(dp(6), 0, dp(14), 0)   // a capa encosta mais na borda que um ícone
            addView(capaTocando, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
            addView(barrasTocando, LinearLayout.LayoutParams(dp(11), dp(12)).apply { marginEnd = dp(8) })
            addView(txtTocandoTitulo)
            addView(texto(12.5f, corSub).apply { text = " · "; includeFontPadding = false })
            addView(txtTocandoSub)
            setOnClickListener {
                barrasTocando.tocando = !barrasTocando.tocando   // resposta na hora; o aviso da sessão confirma
                filaApps.execute { runCatching { TocandoAgoraTv.controlar(this@MainActivity, "alternar") } }
            }
            setOnLongClickListener {
                // A nossa rádio (Ouvir, 17/11/2026): abrir a tela dela ou parar. Outro app: abre o app, como antes.
                if (tocandoNosso) Estilo.lista(this@MainActivity, txtTocandoTitulo.text, listOf(getString(if (tipoNosso == "podcast") R.string.pod_abrir_tela else R.string.ouv_abrir), getString(R.string.ra_parar))) { i ->
                    if (i == 0) runCatching { startActivity(Intent(this@MainActivity, if (tipoNosso == "podcast") PodcastsActivity::class.java else RadioActivity::class.java)) }
                    else { OuvirEstado.comandar(this@MainActivity, OuvirEstado.PARAR); mostrarTocando(null, null) }
                } else pacoteTocando?.let { abrir(it) }
                true
            }
        }
        capsulaTocando = capsula().apply {
            visibility = View.GONE
            addView(pilulaTocando, LinearLayout.LayoutParams(WRAP, alturaPilula))
        }
        // Avisos: despertador, timer e lista vencendo. A cápsula some quando os três somem (ver atualizarAvisos).
        avisos = capsula().apply { addView(pilulaAlarme, lpPilula()) }
        val esquerda = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(busca, LinearLayout.LayoutParams(WRAP, dp(ALTURA_TOPO)))
            addView(capsulaClima, LinearLayout.LayoutParams(WRAP, dp(ALTURA_TOPO)).apply { marginStart = dp(8) })
            addView(capsulaTocando, LinearLayout.LayoutParams(WRAP, dp(ALTURA_TOPO)).apply { marginStart = dp(8) })
            addView(avisos, LinearLayout.LayoutParams(WRAP, dp(ALTURA_TOPO)).apply { marginStart = dp(8) })
        }
        topo.addView(esquerda, FrameLayout.LayoutParams(WRAP, dp(ALTURA_TOPO)).apply {
            leftMargin = dp(Grade.MARGEM + ALTURA_TOPO - 4 + 12)
        })

        // Direita: a hora e a data soltas, sem vidro. A pílula tem 14 dp de respiro no fim: a margem desconta, para o texto
        // terminar na mesma linha das laterais (Grade.MARGEM).
        val direita = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            clipChildren = false
            clipToPadding = false
            addView(blocoHora, LinearLayout.LayoutParams(WRAP, dp(ALTURA_TOPO - 8)))
            addView(blocoData, LinearLayout.LayoutParams(WRAP, dp(ALTURA_TOPO - 8)))
        }
        topo.addView(direita, FrameLayout.LayoutParams(WRAP, dp(ALTURA_TOPO), Gravity.END).apply { rightMargin = dp(Grade.MARGEM - 14) })
        // O texto do clima usa só o espaço que sobra até a hora (com despertador e timer na linha ele encolhe e ganha "…";
        // sem eles, cabe inteiro). Esquerda e direita são independentes no FrameLayout e podiam se sobrepor.
        esquerda.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (direita.width > 0) {
                val resto = v.width - txtChuva.width                       // tudo da esquerda menos o texto do clima
                val livre = direita.left - dp(24) - v.left - resto
                val alvo = livre.coerceIn(dp(80), dp(320))
                if (txtChuva.maxWidth != alvo) txtChuva.post { txtChuva.maxWidth = alvo }
            }
        }

        // Timer (05/11/2026): mais uma pílula na mesma linha, depois do despertador. OK nela cancela. Some sem timer
        // (ver tarefaTimer).
        txtTimer = texto(12.5f, Estilo.TEXTO, fonteUi).apply { includeFontPadding = false }
        pilulaTimer = pilula(IconeApp(IconeApp.Tipo.TIMER), vidro = false).apply {
            visibility = View.GONE
            addView(txtTimer)
            setOnClickListener {
                // Ideia 31: OK cancela o timer que acaba primeiro; sem timer, para o cronômetro e mostra quanto marcou.
                if (Timer.cancelarProximo(this@MainActivity)) AvisoTv.rapido(this@MainActivity, "Timer", getString(R.string.tm_timer_cancelado), 4)
                else Timer.pararCronometro(this@MainActivity)?.let { AvisoTv.rapido(this@MainActivity, getString(R.string.tm_crono), getString(R.string.tm_crono_marcou, it), 4) }
                ui.removeCallbacks(tarefaTimer); ui.post(tarefaTimer)
            }
        }
        avisos.addView(pilulaTimer, lpPilula())

        // Lista de TV perto de vencer (11/11/2026, visto no 1 Play): "Playon vence em 3 dias". OK abre a área Conta.
        // Some quando nenhuma lista vence nos próximos 7 dias (ver atualizarPilulaVencimento).
        txtVencimento = texto(12.5f, Estilo.ATENCAO, fonteUi).apply { includeFontPadding = false }
        pilulaVencimento = pilula(vidro = false).apply {
            visibility = View.GONE
            addView(txtVencimento)
            setOnClickListener { startActivity(Intent(this@MainActivity, ConfigLauncherActivity::class.java).putExtra(ConfigLauncherActivity.EXTRA_ABA, ConfigLauncherActivity.ABA_CONTA)) }
        }
        avisos.addView(pilulaVencimento, lpPilula())
        // Ideia 12: o próximo jogo do "Meu time" (24 h) na mesma cápsula, sem fileira nova. OK abre a busca ("Hoje na TV").
        txtJogo = texto(12.5f, Estilo.TEXTO, fonteUi).apply { includeFontPadding = false }
        pilulaJogo = pilula(IconeApp(IconeApp.Tipo.TV), vidro = false).apply {
            visibility = View.GONE
            addView(txtJogo)
            setOnClickListener { abrirBusca() }
        }
        avisos.addView(pilulaJogo, lpPilula())
        // Ideia 93: "🎂 Hoje é aniversário de tia Rosa · 80 anos" (véspera e dia, lista do painel). OK mostra todos num aviso.
        txtAniversario = texto(12.5f, Estilo.TEXTO, fonteUi).apply { includeFontPadding = false }
        pilulaAniversario = pilula(vidro = false).apply {
            visibility = View.GONE
            addView(txtAniversario)
            setOnClickListener {
                val todos = Aniversarios.textos(this@MainActivity)
                if (todos.isNotEmpty()) AvisoTv.rapido(this@MainActivity, getString(R.string.an_rotulo), todos.joinToString("\n"), 8)
            }
        }
        avisos.addView(pilulaAniversario, lpPilula())
        // Ideia 88: "Sem internet · Funcionam: pendrive, Na rede, Gravações". Primeira da cápsula (é o que mais importa
        // quando aparece). OK abre o Diagnóstico, que explica a queda em uma frase. Ver avaliarSemInternet.
        txtSemInternet = texto(12.5f, Estilo.ATENCAO, fonteUi).apply { includeFontPadding = false }
        pilulaSemInternet = pilula(IconeApp(IconeApp.Tipo.SEM_REDE, Estilo.ATENCAO), vidro = false).apply {
            visibility = View.GONE
            addView(txtSemInternet)
            setOnClickListener { startActivity(Intent(this@MainActivity, DiagnosticoActivity::class.java)) }
        }
        avisos.addView(pilulaSemInternet, 0, lpPilula())
        atualizarAvisos()

        raiz.addView(topo, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(TOPO_PALCO) })
        topoRaiz = topo

        // ---- Destaque: o que está em foco, logo acima de "ASSISTIR". Desde 12/11/2026 (dono: "por que não aparecem as
        // informações como no launcher do Google?") é uma ficha que cresce PARA CIMA: linha 1 = título grande e o que é
        // ("Filmes · você parou em 34%"), linha 2 = nota, gênero, ano, duração, quanto falta (ou "● AO VIVO 13:30 – 14:15"),
        // e 2 linhas de sinopse. Apps e Na rede: só a linha 1, como antes. Ancorado por baixo (Gravity.BOTTOM numa caixa
        // que termina em TOPO_DESTAQUE): com ou sem sinopse, ele nunca encosta em "ASSISTIR".
        txtDestaqueRotulo = texto(11f, CORAL, fonteUi).apply {
            letterSpacing = 0.08f
            typeface = Typeface.create(fonteUi, Typeface.BOLD)
            includeFontPadding = false
            maxLines = 1
        }
        txtDestaqueTitulo = texto(36f, Color.WHITE, fonteUi).apply {
            typeface = Typeface.create(fonteUi, Typeface.BOLD)
            includeFontPadding = false
            letterSpacing = -0.02f
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        txtDestaqueSub = texto(14f, textoClaro, fonteUi).apply { includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        txtDestaqueMeta = texto(14f, Color.WHITE, fonteUi).apply { includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
        txtDestaqueSinopse = texto(14f, textoClaro, fonteUi).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            setLineSpacing(0f, 1.2f)
            maxWidth = dp(560)   // linha longa demais cansa a leitura; 560 dp ~ 70 letras
        }
        // Nome grande e a descrição na MESMA linha, alinhados pela base do texto (dono, 12/11 tarde).
        val linha1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            isBaselineAligned = true
            for (t in listOf(txtDestaqueRotulo, txtDestaqueTitulo, txtDestaqueSub))
                addView(t, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(16) })
            // A descrição é o que encolhe (com "…") quando a linha não cabe.
            txtDestaqueSub.layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        destaque = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.INVISIBLE
            addView(linha1, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(txtDestaqueMeta, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(10) })
            addView(txtDestaqueSinopse, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
        }
        if (tema != Tema.ESCURO) for (t in listOf(txtDestaqueRotulo, txtDestaqueTitulo, txtDestaqueSub, txtDestaqueMeta, txtDestaqueSinopse))
            t.setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x99000000.toInt())
        val topoCaixa = TOPO_PALCO + ALTURA_TOPO + 12
        raiz.addView(FrameLayout(this).apply {
            clipChildren = false
            if (emGrade || emCruz) visibility = View.GONE   // grade e cruz: a ficha sai e o Continuar sobe para o lugar dela (LayoutHome)
            addView(destaque, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM))
        }, FrameLayout.LayoutParams(dp(Grade.largura(10)), dp(TOPO_DESTAQUE - topoCaixa)).apply {
            leftMargin = dp(Grade.MARGEM)
            topMargin = dp(topoCaixa)
        })
        // Pela troca de foco (e não por quem chama mostrarDestaque): apaga com o foco no topo, volta ao descer.
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, novo -> if (novo != null) posicionarDestaque(novo) }

        // ---- Linha de apps (rola na horizontal). O padding de cima/baixo e a folga para o card focado crescer;
        // por isso a rolagem comeca FOLGA acima da linha da grade.
        val folga = 24
        // Grade (LayoutHome): o Continuar sobe para logo abaixo do topo e a grade começa embaixo dele, até o fim da tela.
        val topoContinuar = if (emGrade) LayoutHome.TOPO_CONTINUAR_GRADE else TOPO_CONTINUAR
        val linhaDosApps = when {
            emCruz -> TOPO_PALCO + ALTURA_TOPO + 60   // "Todos os apps" da cruz: a grade logo abaixo da barra de cima
            emGrade -> topoContinuar + ALTURA_CONTINUAR + descer + 28
            else -> LINHA_APPS_HOME + descer
        }
        if (gradeDosApps) {
            linhaApps = GradeApps(this, LayoutHome.colunas(passoCartao, Grade.MARGEM), dp(12))
            rolagem = ScrollView(this).apply {
                clipChildren = false
                clipToPadding = false
                isVerticalScrollBarEnabled = false
                isSmoothScrollingEnabled = true
                setPadding(dp(Grade.MARGEM - ANEL), dp(folga) - dp(ANEL), dp(Grade.MARGEM), dp(folga))
                addView(linhaApps)
            }
            raiz.addView(rolagem, FrameLayout.LayoutParams(MATCH, dp(LayoutHome.TELA_A - linhaDosApps + folga)).apply {
                topMargin = dp(linhaDosApps - folga)
            })
        } else {
            rolagem = HorizontalScrollView(this).apply {
                clipChildren = false    // senão a rolagem corta o card focado quando ele cresce
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                setPadding(dp(Grade.MARGEM - ANEL), dp(folga) - dp(ANEL), dp(Grade.MARGEM), dp(folga))
            }
            linhaApps = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                clipChildren = false
            }
            rolagem.addView(linhaApps)
            raiz.addView(rolagem, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(linhaDosApps - folga) })
        }
        // Título "APPS" em cima da linha, como o "CONTINUAR" (esboço do dono, 06/11 noite).
        raiz.addView(texto(if (emCruz) 20f else 12f, if (emCruz) Estilo.ACENTO else textoSuave, fonteUi).apply {
            rotuloApps = this
            text = if (emCruz) getString(R.string.cruz_todos_apps).uppercase() else getString(R.string.home_linha_apps)
            letterSpacing = 0.1f
            if (tema != Tema.ESCURO) setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x99000000.toInt())
        }, FrameLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(Grade.MARGEM); topMargin = dp(linhaDosApps - 32) })

        // ---- Continuar (HOME recomendada): último canal e apps usados há pouco, em cards 16:9 com o banner do app. Fica
        // meio escondida na borda de baixo; com o foco nela (▼ num card solto), a tela inteira sobe para mostrá-la.
        linhaContinuar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
        caixaContinuar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            visibility = View.GONE
            addView(texto(12f, textoSuave, fonteUi).apply {
                rotuloContinuar = this
                text = getString(R.string.home_linha_continuar)
                letterSpacing = 0.1f
                if (tema != Tema.ESCURO) setShadowLayer(dp(6).toFloat(), 0f, 0f, 0x99000000.toInt())
            })
            addView(HorizontalScrollView(this@MainActivity).apply {
                clipChildren = false
                clipToPadding = false
                isHorizontalScrollBarEnabled = false
                setPadding(0, dp(10), dp(Grade.MARGEM), dp(16))
                addView(linhaContinuar)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        // Altura fixa (passa da borda de baixo de propósito): com WRAP o FrameLayout cortava a linha no fim da tela, e ao
        // subir aparecia só o topo dos cards e uma faixa vazia embaixo (visto na Sala, 06/11).
        raiz.addView(caixaContinuar, FrameLayout.LayoutParams(MATCH, dp(ALTURA_CONTINUAR + descer)).apply {
            leftMargin = dp(Grade.MARGEM)
            topMargin = dp(topoContinuar)
        })

        // ---- Guia de teclas do modo mover: uma faixa na parte de baixo, por cima de tudo. Some fora do modo mover.
        txtDica = texto(15f, corMover).apply {
            visibility = View.GONE
            gravity = Gravity.CENTER
            maxLines = 2
            maxWidth = dp(1000)
            setPadding(dp(22), dp(10), dp(22), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(22).toFloat()
                setColor(0xE60E1311.toInt())
                setStroke(dp(1), 0x66FF6A2B)
            }
        }

        // Sem decoração (arco do sol/lua, etiqueta da obra) na HOME v3: o lugar dela agora é do destaque e dos apps.
        val vivo = criarFundo(this, tema, decoracao = false) { bloco -> rede.execute { bloco() } }
        fundo = vivo
        // Sem fundo preto aqui (nem na janela, ver onCreate): o fundo vivo já cobre a tela inteira e é opaco. Cada camada
        // de tela cheia a mais é a GPU pintando 1920x1080 de novo A CADA QUADRO (medido: 3 camadas, 22 ms por quadro).
        capa = CapaFoco(this).apply { desligada = tema == Tema.GALERIA || tema == Tema.MEU || (emCruz && !LayoutHome.arteCruz(this@MainActivity)) }   // Galeria: a obra é o fundo, sem brilho por cima
        if (emCruz) montarCruz(raiz)
        conteudo = raiz
        return FrameLayout(this).apply {
            clipChildren = false   // a tela que sobe (Continuar) desenha o que estava abaixo da borda
            addView(vivo, FrameLayout.LayoutParams(MATCH, MATCH))
            // A capa do foco: alto à direita, do tamanho do esboço (680 x 330 dp), atrás do topo e dos apps. Desenhada em
            // METADE da resolução e ampliada 2x (06/11: a capa cheia custava ~5 ms de GPU por quadro na Sala; é uma imagem
            // de fundo escurecida, a perda de nitidez não aparece). A ampliação parte do canto de cima à direita.
            // Esboço do dono (06/11 noite): de volta ao alto à direita, atrás do topo e do destaque, sumindo antes dos apps.
            // 12/11/2026: 600 x 400 dp (era 680 x 330), o tamanho da arte de filme/série aprovada no teste do dono.
            addView(capa, FrameLayout.LayoutParams(dp(300), dp(200), Gravity.TOP or Gravity.END))
            capa.escalaBase = 2f
            capa.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = v.width.toFloat(); v.pivotY = 0f }
            addView(raiz, FrameLayout.LayoutParams(MATCH, MATCH))
            addView(txtDica, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = dp(32)
            })
        }
    }

    /** Passa ao fundo vivo a hora, o lugar e o clima de agora (ver Fundos.kt). */
    private fun atualizarFundo() {
        val p = pontoAtual
        (fundo as? FundoVivo)?.atualizar(EstadoCeu(System.currentTimeMillis(), p?.lat ?: Config.LATITUDE,
            p?.lon ?: Config.LONGITUDE, ultimoCodigo, ultimoChovendo))
    }

    /** A bolinha do perfil, no canto superior direito: mostra a foto/inicial da conta ativa e abre o popup (OK). */
    private fun botaoPerfil(): View {
        fotoPerfil = ImageView(this).apply {
            setImageBitmap(avatarDaBolinha())
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(view: View, outline: Outline) {
                    outline.setOval(0, 0, view.width, view.height)   // recorte redondo
                }
            }
        }
        return FrameLayout(this).apply {
            contentDescription = getString(R.string.home_perfil)
            isFocusable = true
            isClickable = true
            setPadding(dp(3), dp(3), dp(3), dp(3))
            background = anel(false)
            addView(fotoPerfil, FrameLayout.LayoutParams(MATCH, MATCH))   // ocupa a bolinha toda (40 dp no topo Palco)
            setOnClickListener { abrirPopup() }
            setOnFocusChangeListener { v, temFoco ->
                v.background = anel(temFoco)
                val escala = if (temFoco) 1.12f else 1f
                v.animate().scaleX(escala).scaleY(escala).setDuration(140).start()
            }
        }
    }

    private lateinit var txtAlarme: TextView          // "⏰ amanhã 6:30" na pílula do despertador
    private lateinit var pilulaAlarme: LinearLayout
    private lateinit var destaque: LinearLayout      // o bloco grande do que está em foco (ver mostrarDestaque)
    private lateinit var txtDestaqueRotulo: TextView
    private lateinit var txtDestaqueTitulo: TextView
    private lateinit var txtDestaqueSub: TextView
    private lateinit var txtDestaqueMeta: TextView      // ★ 6,6  Thriller · 2023 · 1 h 59 min · faltam 1 h 18 min
    private lateinit var txtDestaqueSinopse: TextView
    @Volatile private var gravandoTv: String? = null   // "Globo até 22:05" enquanto grava (Gravacoes.resumoParaHome)
    private var ultimoTv: PlayerArmazem.Ultimo? = null   // último canal + programas (lido no onResume, em preencherContinuar)
    private lateinit var txtTimer: TextView   // "4:32" na pílula do timer enquanto um timer corre (ver Timer.kt)
    private lateinit var pilulaTimer: LinearLayout

    /** Atualiza o "⏱ 4:32" a cada segundo enquanto a HOME está na frente e há timer; some quando não há. */
    private val tarefaTimer = object : Runnable {
        override fun run() {
            val t = Timer.pilula(this@MainActivity)   // o próximo timer, "+N" e o cronômetro (ideia 31)
            if (t == null) {
                if (pilulaTimer.hasFocus()) linhaApps.requestFocus()   // o foco não pode ficar num texto invisível
                pilulaTimer.visibility = View.GONE
                atualizarAvisos()
                return
            }
            txtTimer.text = t
            pilulaTimer.visibility = View.VISIBLE
            atualizarAvisos()
            // Na volta do segundo: o texto anda junto com o relógio, sem pular números.
            ui.postDelayed(this, 1000 - (System.currentTimeMillis() % 1000) + 20)
        }
    }


    /** Redesenha a bolinha: a conta ativa mudou, ou chegou uma foto nova. */
    private fun atualizarFotoPerfil() {
        fotoPerfil.setImageBitmap(avatarDaBolinha())
    }

    /** A foto da conta ativa; no modo visita, o boneco genérico (conta = null), para não mostrar quem é o dono. */
    private fun avatarDaBolinha() =
        if (ModoVisita.ativo(this)) Perfil.avatar(this, dp(44), conta = null) else Perfil.avatar(this, dp(44))


    /** Contorno branco dos blocos do topo (hora, clima) quando estao com foco. */
    private fun realceFoco() = GradientDrawable().apply {
        cornerRadius = dp(16).toFloat()
        setColor(Estilo.BORDA)
        setStroke(dp(2), 0xD9FFFFFF.toInt())
    }
    private var ultimaTemp: Int? = null   // a temperatura mostrada no topo (para a linha "Hoje" da previsao bater com ela)

    /** Abre a tela da previsao (ver PrevisaoActivity.kt); leva a temperatura do topo para as duas telas baterem. */
    private fun abrirPrevisao() {
        startActivity(Intent(this, PrevisaoActivity::class.java)
            .putExtra(PrevisaoActivity.EXTRA_TEMP, ultimaTemp ?: Int.MIN_VALUE)
            .putExtra(PrevisaoActivity.EXTRA_CHOVENDO, ultimoChovendo)
            .putExtra(PrevisaoActivity.EXTRA_CODIGO, ultimoCodigo)
            .putExtra(PrevisaoActivity.EXTRA_UMIDADE, getSharedPreferences("clima", MODE_PRIVATE).getInt("umidade", -1)))
    }

    /** Abre o painel rápido (perfis, Wi-Fi, timer, notificações...): ver PainelRapidoActivity.kt. */
    private fun abrirPopup() {
        // No modo visita o painel (perfis, Configurações, Wi-Fi) não abre: só a pergunta para sair do modo.
        if (ModoVisita.ativo(this)) { perguntarSairDaVisita(); return }
        voltarAoPerfil = true
        PainelRapidoActivity.abrir(this)
    }

    /** Modo visita: o OK na bolinha pergunta se é para sair. O foco começa em "Continuar", para não sair sem querer. */
    private fun perguntarSairDaVisita() {
        val dialogo = CaixaTv(this)
            .setTitle(getString(R.string.home_visita_titulo))
            .setMessage(getString(R.string.home_visita_msg))
            .setNegativeButton(getString(R.string.home_continuar), null)
            .setPositiveButton(getString(R.string.home_sair)) { _, _ ->
                val voltar = { atualizarFotoPerfil(); reorganizar() }   // os apps já estão carregados: só refaz a linha
                // Modo hóspede (degrau 2): sair pede o PIN do painel.
                if (ModoHospede.ativo(this)) ModoHospede.desligarComPin(this) { voltar() }
                else { ModoVisita.definir(this, false); voltar() }
            }
            .show()
        dialogo.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        menuAberto = dialogo
    }

    /** Anel ao redor da foto: quase invisível normalmente, branco quando o botão está focado. */
    private fun anel(focado: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(Color.TRANSPARENT)
        setStroke(dp(3), if (focado) 0xD9FFFFFF.toInt() else 0x33FFFFFF)
    }

    // ---------------------------------------------------------------- apps, pilhas e ocultos

    /** pacote -> (APK|activity, Item). Vive enquanto o processo da HOME viver (sobrevive à troca de tema). */
    private companion object {
        val cacheApps = HashMap<String, Pair<String, Item>>()
        // Topo C3 (12/11/2026): cápsulas de 52 dp em 24 (até 76). Ordem pedida pelo dono (12/11 tarde): nome do card em foco
        // (36 sp) e a descrição na mesma linha em 222 (até ~266), "ASSISTIR" em 290 e os cards (do tamanho dos apps) em 316
        // (até 379), "APPS" em 397 e os apps em 429 (até 492): embaixo sobra a mesma margem das laterais (48 dp).
        const val TOPO_PALCO = 28          // onde começa a barra do topo (estilo Google TV, 12/11/2026)
        const val ALTURA_TOPO = 48         // altura das cápsulas (pílulas de 32 dp com 8 de folga em cima e embaixo)
        const val TOPO_DESTAQUE = 278      // onde TERMINA a ficha do card em foco (ela cresce para cima), 12 dp acima de "ASSISTIR"
        const val CORAL = 0xFFFF8A80.toInt()   // a cor do "ao vivo"
        const val AMARELO_NOTA = 0xFFF5C518.toInt()   // a estrela da nota (o amarelo que todo mundo associa a nota de filme)
        const val ALTURA_LINHA_APPS = 136   // da linha da grade dos apps até o fim do nome embaixo do disco (com folga)
        // Linha de apps logo abaixo do destaque (118 + ~90 + folga) e o Continuar embaixo dela: tudo numa tela só.
        // A faixa de 9 dias saiu em 06/11 (dono: "abrindo o widget do clima essas informações já tem lá dentro").
        // Arranjo 2 do Palco (escolha do dono, 06/11/2026), com respiro entre os blocos (ele achou o 1º teste colado):
        // topo 24, apps (discos de 56 dp) em 116, destaque em 232, Continuar em 352 (cards até ~488, sobra ~50 embaixo).
        // Esboço do dono (06/11 noite, "a home já é parte do player"): topo 24, destaque 100, Continuar 214 (começa com os
        // cards TV, Séries e Filmes), apps em retângulos 16:9 embaixo, em 404 (até ~467; sobram ~70 dp).
        const val TOPO_CONTINUAR = 290
        const val LINHA_APPS_HOME = 429     // o título "APPS" fica 32 dp acima; com 10 dp a menos ele encostava no foco
        val CORES_SERIES = intArrayOf(0xFF9B7BFF.toInt(), 0xFF4A2C9A.toInt())   // roxo
        val CORES_FILMES = intArrayOf(0xFF4C9BFF.toInt(), 0xFF1A3F8F.toInt())   // azul
        val CORES_REDE = intArrayOf(0xFF2FBF8F.toInt(), 0xFF0E5A45.toInt())     // verde
        val CORES_JOGOS = intArrayOf(0xFFF5A524.toInt(), 0xFF7A4A0E.toInt())    // âmbar (card Jogos, 14/11/2026)
        val CORES_OUVIR = intArrayOf(0xFF4A8BFF.toInt(), 0xFF123A7A.toInt())    // azul (card Ouvir, 17/11/2026)
        const val PREFS_CRUZ_APPS = "cruz_apps"   // aberturas por app (meia-vida 7 dias) e fixados no topo de Aplicativos
        const val PREFS_CRUZ = "cruz"   // cruz: o último item escolhido em cada categoria (id da categoria -> id do item)
        const val CONTINUAR_MAX = 9          // itens do Continuar da cruz (o 10º seria o "Dormir em 30 min")
        /** Prefs "cruz": o último item aberto na cruz ("categoria|item") e quando; vale [ULTIMO_ABERTO_VALE_MS] na HOME refeita. */
        const val ULTIMO_ABERTO = "ultimo_aberto"
        const val ULTIMO_ABERTO_EM = "ultimo_aberto_em"
        const val ULTIMO_ABERTO_VALE_MS = 6L * 3600 * 1000
        const val MIDIA_NO_CONTINUAR = 4     // apps de mídia (MidiaRecente) no Continuar
        const val JOGOS_NO_CONTINUAR = 3     // jogos recentes no Continuar
        const val LARGURA_ARTE = 640   // px da arte de filme/série no fundo (ver carregarArte)
        /** Frase curta embaixo do nome de cada app da HOME. Pacote novo na TV: acrescentar aqui (e nas strings). */
        val DESCRICAO_APPS = mapOf(
            "com.google.android.youtube.tv" to R.string.app_sub_youtube,
            "com.netflix.ninja" to R.string.app_sub_netflix,
            "com.amazon.amazonvideo.livingroom" to R.string.app_sub_prime,
            "com.wbd.stream" to R.string.app_sub_max,
            "com.android.vending" to R.string.app_sub_play_store,
            "com.google.android.play.games" to R.string.app_sub_play_games,
            "com.google.android.videos" to R.string.app_sub_google_tv,
            "com.android.tv.settings" to R.string.app_sub_config,
        )
        const val ALTURA_CONTINUAR = 112    // título + cards do tamanho dos apps (63 dp) + folga do card focado
        /** As fichas de fichas-apps.json para a frase do destaque (ideia 13). Lidas uma vez, na filaApps; null até lá. */
        @Volatile var fichasHome: Map<String, FrasesApps.Ficha>? = null
    }

    /** Todos os apps de TV instalados, sem ordem e sem tirar os ocultos (quem organiza é montarEntradas). */
    private fun carregarApps(): List<Item> {
        val pm = packageManager
        // "Quais apps aparecem no menu de uma TV?" = os que declaram a categoria LEANBACK_LAUNCHER.
        val consulta = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val itens = LinkedHashMap<String, Item>()
        val jogos = HashSet<String>()   // pacotes de jogo com ícone na TV, de sistema também (a conta da tela Jogos)
        val pacoteIcones = PacoteIcones.versao(this)
        for (ri in pm.queryIntentActivities(consulta, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || pkg == Config.PACOTE_CONFIG || pkg in itens) continue
            // Jogos contam ANTES do filtro de sistema (17/11/2026): a tela Jogos (JogosTv.listar) mostra também os de
            // sistema, como o Play Games; contar só os instalados por você dava "5 jogos" na cruz e "Todos · 6" no hub.
            JogosTv.primar(this, pkg, ri.activityInfo.applicationInfo.category)
            if (JogosTv.ehJogo(this, pkg)) jogos += pkg
            // FLAG_SYSTEM == 0 significa "instalado por você". Os de sistema só entram se estiverem em SEMPRE_MOSTRAR.
            val instaladoPorVoce = (ri.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) == 0
            if (!instaladoPorVoce && pkg !in Config.SEMPRE_MOSTRAR) continue
            // Cache por APK (04/11/2026): isto roda na thread da tela a cada volta à HOME, e o serviço de teclas do
            // controle divide essa thread — medido 107-260 ms só aqui. O caminho do APK muda quando o app é
            // atualizado, então serve de chave: app igual = reaproveita nome e ícone já carregados.
            // (Antes também carregava o banner, que nada usava, e o ícone duas vezes.)
            // Pacote de ícones (PacoteIcones.kt, desligado de fábrica) entra na chave: trocar de pacote redesenha.
            val chave = ri.activityInfo.applicationInfo.sourceDir + "|" + ri.activityInfo.name + "|" + pacoteIcones
            // A categoria já vem no ResolveInfo: sem IPC na thread da tela depois (home-9).
            categorias[pkg] = ri.activityInfo.applicationInfo.category
            // APK novo (atualizado): a capa guardada pode ser a arte antiga; lê de novo (home-6).
            if (cacheApps[pkg]?.first != null && cacheApps[pkg]?.first != chave) capas.remove(pkg)
            itens[pkg] = cacheApps[pkg]?.takeIf { it.first == chave }?.second ?: run {
                // Só o ícone redondo fica guardado: o original (loadIcon) vira lixo logo depois de desenhado.
                val original = PacoteIcones.icone(this, pkg, ri.activityInfo.name) ?: ri.loadIcon(pm)
                // Pacote embutido (Símbolo/Duotom, 18/11/2026): o traço do app no estilo da cruz; sem traço legível, o de sempre.
                val icone = PacoteIcones.embutido(this)?.let { DesenhoHome.iconeTraco(resources, original, it == PacoteIcones.Embutido.DUOTOM) }
                    ?: DesenhoHome.iconeRedondo(resources, original)
                Item(pkg, ri.loadLabel(pm).toString(), icone).also { cacheApps[pkg] = chave to it }
            }
        }
        // Entradas HDMI/AV (EntradasTv, degrau 1): só em TV com entrada; chave "entrada:<id>", ícone redondo como os apps.
        for (e in EntradasTv.listar(this)) itens[e.chave] = cacheApps[e.chave]?.second ?: Item(e.chave, e.nome,
            DesenhoHome.iconeRedondo(resources, e.icone ?: IconeApp(IconeApp.Tipo.TV))).also { cacheApps[e.chave] = "" to it }
        cacheApps.keys.retainAll(itens.keys)   // app desinstalado sai do cache
        capas.keys.retainAll(itens.keys)       // e a capa dele também (home-6)
        jogosNaTv = jogos.size   // a frase do card Jogos (resumoJogos) e da coluna Jogar
        return itens.values.toList()
    }

    /**
     * Transforma os apps instalados nos cards da linha: ordem (manual primeiro, depois automática),
     * pilhas (vários apps num card só) e, no fim, o card "Ocultos" se houver algum app oculto.
     */
    /** Categoria que o app declara (ApplicationInfo.category; -1 = nenhuma). Guardada: não muda enquanto o app existir. */
    private val categorias = java.util.concurrent.ConcurrentHashMap<String, Int>()   // preenchida em carregarApps (home-9)
    private fun categoria(pacote: String): Int = categorias.getOrPut(pacote) {
        try { packageManager.getApplicationInfo(pacote, 0).category } catch (e: Exception) { -1 }
    }

    private fun montarEntradas(todos: List<Item>): MutableList<Entrada> {
        val ocultos = Organizacao.ocultos(this)
        // Modo visita (ModoVisita.kt): os apps escolhidos somem também, e o card "Apps ocultos" não aparece.
        val naVisita = ModoVisita.ativo(this)
        val daVisita = if (naVisita) ModoVisita.apps(this) else emptySet()
        // Card Jogos (14/11/2026): com ele ligado, os apps de jogo moram só nele e saem desta linha.
        val semJogos = JogosTv.cardLigado(this)
        val visiveis = todos.filter { it.pacote !in ocultos && it.pacote !in daVisita && !(semJogos && JogosTv.ehJogo(this, it.pacote)) }
        val porPacote = visiveis.associateBy { it.pacote }

        // Pilhas: só valem com 2+ apps instalados e visíveis. Um app nunca fica em duas pilhas.
        val pilhaDe = HashMap<String, Organizacao.Pilha>()   // pacote -> pilha
        val membros = HashMap<String, List<Item>>()          // id da pilha -> apps, na ordem da pilha
        for (pilha in Organizacao.pilhas(this)) {
            val itens = pilha.apps.mapNotNull { porPacote[it] }.filter { it.pacote !in pilhaDe }
            if (itens.size < 2) continue
            membros[pilha.id] = itens
            for (item in itens) pilhaDe[item.pacote] = pilha
        }

        // Pilhas AUTOMÁTICAS por categoria (Organizacao.agruparPorCategoria), se ligadas: só os apps que sobraram
        // soltos. Os FIXOS ficam de fora (têm lugar próprio no começo). A frente é a escolhida com ▲ ou, sem escolha,
        // o app usado mais recentemente.
        if (Organizacao.pilhasAutomaticas(this)) {
            val soltos = visiveis.filter { it.pacote !in pilhaDe && it.pacote !in Config.FIXOS }
            val grupos = Organizacao.agruparPorCategoria(soltos.map { it.pacote to categoria(it.pacote) }, Organizacao.foraDaAuto(this))
            val frentes = Organizacao.frentesAuto(this)
            for ((id, pacotes) in grupos) {
                val itens = pacotes.mapNotNull { porPacote[it] }
                val frente = frentes[id]?.takeIf { it in pacotes } ?: pacotes.maxByOrNull { ultimoUso[it] ?: 0L }
                val pilha = Organizacao.Pilha(id, pacotes.toMutableList(), frente)
                membros[id] = itens
                for (item in itens) pilhaDe[item.pacote] = pilha
            }
        }

        // Lido fora da tela por preencherApps (queryAndAggregateUsageStats de 14 dias é caro); aqui só se usa o mapa.
        val usados = ultimoUso
        val manual = Organizacao.ordem(this).withIndex().associate { it.value to it.index }
        // Os apps de uma pilha usam o token da pilha na ordem manual; a pilha aparece onde está o seu 1º app.
        fun tokenDe(i: Item): String = pilhaDe[i.pacote]?.token ?: i.pacote
        val ordenados = visiveis.sortedWith(
            compareBy<Item>({ manual[tokenDe(it)] ?: Int.MAX_VALUE }, { fixo(it.pacote) }, { -(usados[it.pacote] ?: 0L) }, { posicao(it.pacote) }, { it.nome.lowercase() })
        )

        val entradas = mutableListOf<Entrada>()
        val pilhasJaVistas = HashSet<String>()
        for (item in ordenados) {
            val pilha = pilhaDe[item.pacote]
            if (pilha == null) {
                entradas.add(Entrada.App(item))
            } else if (pilhasJaVistas.add(pilha.id)) {
                val itens = membros.getValue(pilha.id)
                val frente = itens.indexOfFirst { it.pacote == pilha.frente }.coerceAtLeast(0)
                entradas.add(Entrada.Pilha(pilha.id, itens, frente))
            }
        }
        // 14/11/2026 (pedido do dono): o card "Ocultos (N)" saiu da HOME. Mostrar de volta: Configurações > Tela inicial
        // > Apps ocultos (AppsEscolhaActivity).
        // A TV saiu da linha de apps (06/11 noite): agora é o 1º card do Continuar, ao lado de Séries e Filmes.
        return entradas
    }

    private fun fixo(pacote: String): Int {
        val i = Config.FIXOS.indexOf(pacote)
        return if (i >= 0) i else Int.MAX_VALUE
    }

    private fun posicao(pacote: String): Int {
        val i = Config.ORDEM.indexOf(pacote)
        return if (i >= 0) i else Int.MAX_VALUE
    }

    private fun preencherApps() {
        // Carregar os apps (PackageManager + ícone redondo de cada um) roda FORA da thread da tela (04/11/2026: um ANR de
        // 5 s tinha iconeRedondo na pilha da thread principal, no 1º onResume do processo). Só o desenho volta para ela.
        val t0 = android.os.SystemClock.elapsedRealtime()
        filaApps.execute {
            val todos = carregarApps()   // cacheApps só é tocado nesta fila (uma thread)
            val doJogos = todos.filter { JogosTv.ehJogo(this, it.pacote) }
            // Uso recente também aqui fora (04/11/2026): antes rodava dentro de montarEntradas, na thread da tela.
            val uso = if (Config.ORDENAR_POR_USO_RECENTE) Uso.ultimoUso(this) else emptyMap()
            // Card Jogos em foco (esboço A, 14/11/2026): o fundo é o banner oficial do jogo usado por último.
            ultimoJogo = Jogos.ultimoUsado(doJogos.map { it.pacote }, uso)?.let { p -> p to doJogos.first { it.pacote == p }.nome }
            // Os retângulos mostram o banner de cada app: lê os que faltam ANTES de desenhar (só na 1ª vez do processo).
            for (app in todos) if (!capas.containsKey(app.pacote)) runCatching { lerCapa(app.pacote, app.icone) }
            // Ícones da cruz no estilo escolhido (banner, redondo, solto): feitos aqui, fora da tela, uma vez por app.
            for (app in todos) prepararIconeCruz(app)
            iconeCruzApp.keys.retainAll(todos.map { it.pacote }.toSet())
            val t1 = android.os.SystemClock.elapsedRealtime()
            ui.post {
                if (isDestroyed) return@post
                instalados = todos
                ultimoUso = uso   // reorganizar() reaproveita este mapa até a próxima volta à HOME
                lista = montarEntradas(instalados)
                desenhar()
                // Medir para não deixar crescer: a thread da tela também atende as teclas do controle.
                val desenhou = android.os.SystemClock.elapsedRealtime() - t1
                android.util.Log.i("TvLauncher.Apps", "preencherApps: carregar=${t1 - t0} ms (fora da tela), desenhar=$desenhou ms")
                LevezaTv.anotarHome(t1 - t0, desenhou)   // números de leveza (degrau 2): `EXTRAS --es o leveza`
            }
        }
    }
    private val filaApps = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "Apps") }
    /** pacote -> último uso (ms), lido por preencherApps na filaApps. Só lido/escrito na thread da tela. */
    private var ultimoUso: Map<String, Long> = emptyMap()

    /** Só reorganiza (pilhas, ordem, ocultos) com os apps já carregados: síncrono, quem chama lê a [lista] em seguida. */
    private fun reorganizar() {
        lista = montarEntradas(instalados)
        desenhar()
    }

    /** Os cards que aparecem agora: a lista normal, ou (no modo mover) a lista com o card arrastado na posição de teste. */
    private fun listaDaTela(): List<Entrada> = if (modoMover) listaMovendo() else lista

    /** Desenha os cards e devolve o foco ao último card usado. */
    private fun desenhar() {
        val cards = listaDaTela()
        // Reaproveitar os cards (04/11/2026, item 2b). Medido na TV: o desenho refazia todos os cards a cada volta à HOME,
        // 63-89 ms na thread da tela, mesmo sem nada ter mudado. Se a assinatura (token, nome, ícone e app da frente de
        // cada card) é a mesma da última vez, a linha fica como está e só o foco volta. No modo mover sempre redesenha
        // (a ordem de teste muda a cada tecla), e ao sair dele a assinatura zerada força o redesenho.
        val assinatura = if (modoMover) null else cards.map { assinaturaDe(it) }
        if (assinatura == null || assinatura != ultimaAssinatura || linhaApps.childCount != cards.size) {
            linhaApps.removeAllViews()
            cards.forEach { linhaApps.addView(criarCartao(it)) }
            prenderBordas()
            ultimaAssinatura = assinatura
        }
        if (emCruz) { atualizarCruz(); if (!todosAbertos) return }
        if (cards.isEmpty()) return
        if (atalhoAberto >= 0) return   // voltando de TV/Séries/Filmes: o foco volta para aquele card (desenharContinuar)
        linhaApps.post {
            // Foco no Continuar (o TV na abertura): não roubar. A volta de um app já o trouxe para os apps no onResume.
            if (::linhaContinuar.isInitialized && linhaContinuar.hasFocus()) return@post
            if (voltarAoPerfil) { bolinhaPerfil?.requestFocus(); return@post }
            focoTopo?.takeIf { it.isShown }?.let { focoTopo = null; it.requestFocus(); return@post }
            val achado = cards.indexOfFirst { it.token == ultimoToken }
            // Se o card sumiu (ex.: app ocultado), o foco fica na mesma posição, que agora é o vizinho.
            val alvo = if (achado >= 0) achado else indiceFoco.coerceIn(0, cards.lastIndex)
            linhaApps.getChildAt(alvo)?.requestFocus()
        }
    }

    /** O último desenho da linha (ver [desenhar]); null = redesenhar na próxima vez. */
    private var ultimaAssinatura: List<String>? = null

    /**
     * O que define a aparência e o comportamento de um card. Os ícones entram pela identidade do objeto: um Item só é
     * recriado (cacheApps) quando o APK ou a activity do app mudam, e aí o ícone é outro objeto.
     */
    private fun assinaturaDe(e: Entrada): String {
        fun ic(i: Item) = "${i.pacote}@${System.identityHashCode(i)}"
        return when (e) {
            is Entrada.App -> "A|${e.nome}|${ic(e.item)}"
            is Entrada.Pilha -> "P|${e.id}|${e.nome}|${e.frente}|" + e.itens.joinToString(",") { ic(it) }
            is Entrada.Ocultos -> "O|${e.nome}|" + e.itens.joinToString(",") { ic(it) }
            is Entrada.Tv -> "T|${e.nome}"
        }
    }

    private fun criarCartao(entrada: Entrada): View {
        val aoFocar = { v: View ->
            ultimoToken = entrada.token
            indiceFoco = linhaApps.indexOfChild(v)
            (rolagem as? HorizontalScrollView)?.let { centralizar(v, it) }   // a grade (ScrollView) rola sozinha até o foco
            mostrarCapa(entrada)
        }
        return when (entrada) {
            is Entrada.App -> montarCartao(entrada.token, entrada.nome, miniaturaApp(entrada.item), { abrir(entrada.item.pacote) }, aoFocar)
            // Pilha: o OK abre o app da frente (▲▼ trocam qual é).
            is Entrada.Pilha -> montarCartao(entrada.token, entrada.nome, miniaturaPilha(entrada.itens, entrada.frente), { abrir(entrada.daFrente.pacote) }, aoFocar)
            is Entrada.Ocultos -> montarCartao(entrada.token, entrada.nome, miniaturaOcultos(entrada.nome), { abrirPasta(entrada) }, aoFocar)
            // O último canal fica no card "AO VIVO" da linha Continuar (no disco o nome não cabia: "Continuar: 4 ·…").
            is Entrada.Tv -> montarCartao(entrada.token, entrada.nome, miniaturaTv(), { abrirTv() }, aoFocar)
        }
    }

    /**
     * Um card: um disco de [Grade.DISCO] dp (o ícone do app, recortado em círculo) e o nome embaixo, centralizado.
     * O foco aparece como um anel branco no disco (e o disco cresce 12%); o nome fica branco. Serve para a linha principal e
     * para dentro das pastas. [token] identifica o card (modo de mover e menu), [aoAbrir] roda ao apertar OK.
     * O card ocupa DISCO + VAO_DISCO de largura: o disco fica no meio e o nome tem espaço para respirar.
     */
    private fun montarCartao(token: String, nome: String, miniatura: View, aoAbrir: () -> Unit, aoFocar: (View) -> Unit,
                             aoDesfocar: ((View) -> Unit)? = null): View {
        val cartao = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            clipChildren = false
            isFocusable = true
            isClickable = true
            tag = token
            contentDescription = nome   // a acessibilidade (e o tocar_elemento do MCP) acha o card pelo nome do app
            setOnClickListener { aoAbrir() }
            setOnFocusChangeListener { v, temFoco ->
                estilizar(v, token, temFoco)
                if (temFoco) aoFocar(v) else aoDesfocar?.invoke(v)
            }
        }
        // filho 0: o disco (o anel de foco é desenhado POR CIMA, no foreground). filho 1: o nome.
        val moldura = FrameLayout(this).apply {
            clipChildren = false
            // o disco fica 6 dp para dentro: o anel de foco é desenhado na borda da moldura, com uma folga, como no Google TV
            addView(miniatura, FrameLayout.LayoutParams(MATCH, MATCH).apply { setMargins(dp(ANEL), dp(ANEL), dp(ANEL), dp(ANEL)) })
            foreground = fioFino()
        }
        cartao.addView(moldura, LinearLayout.LayoutParams(dp(RET_L + 2 * ANEL), dp(RET_A + 2 * ANEL)))
        // O nome fica guardado (estilizar e o modo mover usam o filho 1), mas não aparece: o retângulo já mostra a arte do
        // app, e quem não tem banner mostra o nome dentro (ver retanguloDoApp).
        cartao.addView(texto(11.5f, textoSuave).apply { text = nome; visibility = View.GONE })
        cartao.layoutParams = LinearLayout.LayoutParams(dp(RET_L + 2 * ANEL), WRAP).apply { marginEnd = dp(4) }
        return cartao
    }

    // ---------------------------------------------------------------- HOME recomendada: capa do foco e Continuar

    private fun dentro(v: View, pai: View): Boolean {
        var p: Any? = v
        while (p is View) { if (p === pai) return true; p = p.parent }
        return false
    }

    /** A capa de quem está em foco: banner do app (ou a cor do ícone); a TV ao vivo, coral; o card "Ocultos", nada. */
    private fun mostrarCapa(entrada: Entrada) {
        val item = when (entrada) {
            is Entrada.App -> { mostrarDestaque(null, entrada.nome, subApp(entrada.item.pacote, entrada.nome)); entrada.item }
            is Entrada.Pilha -> {
                mostrarDestaque(null, entrada.daFrente.nome, getString(R.string.home_destaque_pilha, entrada.itens.size))
                entrada.daFrente
            }
            is Entrada.Tv -> { destaqueTv(); mostrarCapaAtalho(IconeApp.Tipo.TV); return }
            is Entrada.Ocultos -> { capaPedida = null; esconderDestaque(); capa.esconder(); return }
        }
        mostrarCapaDoApp(item.pacote, item.icone)
    }

    /** Pílula de vidro do topo: o componente padrão do sistema de design (Estilo.pilula), com o foco padrão. */
    private fun pilula(icone: android.graphics.drawable.Drawable? = null, vidro: Boolean = true): LinearLayout =
        Estilo.pilula(this, icone, vidro = vidro)

    private lateinit var pilulaVencimento: LinearLayout
    private lateinit var avisos: LinearLayout   // 2ª cápsula do topo: despertador, timer e lista vencendo

    /** A cápsula dos avisos só aparece com pelo menos um deles (vazia, sobrava uma bolha de vidro sem nada). */
    private fun atualizarAvisos() {
        val algum = (0 until avisos.childCount).any { avisos.getChildAt(it).visibility == View.VISIBLE }
        avisos.visibility = if (algum) View.VISIBLE else View.GONE
    }
    private lateinit var txtVencimento: TextView

    /** Lê o vencimento das listas fora da tela (decifra as contas) e mostra/esconde a pílula. */
    private fun atualizarPilulaVencimento() {
        if (!PlayerArmazem.temContas(this)) { pilulaVencimento.visibility = View.GONE; atualizarAvisos(); return }
        Fundo.rodar {
            val texto = runCatching { ContaIptv.avisoVencimento(PlayerArmazem.vencimentos(this), System.currentTimeMillis(),
                t = ContaIptv.Textos(venceHoje = getString(R.string.cl_ci_vence_hoje), venceuCurto = getString(R.string.ci_venceu),
                    venceAmanha = getString(R.string.ci_vence_amanha), venceEmDias = { d -> getString(R.string.ci_vence_em, d) })) }.getOrNull()
            ui.post {
                if (isDestroyed) return@post
                if (texto == null) { if (pilulaVencimento.hasFocus()) linhaApps.requestFocus(); pilulaVencimento.visibility = View.GONE }
                else { txtVencimento.text = texto; pilulaVencimento.visibility = View.VISIBLE }
                atualizarAvisos()
            }
        }
    }

    private lateinit var txtJogo: TextView
    private lateinit var pilulaAniversario: LinearLayout
    private lateinit var txtAniversario: TextView
    private lateinit var pilulaJogo: LinearLayout

    // ---- Tocando agora (ideia 75)
    private lateinit var capsulaTocando: LinearLayout
    private lateinit var pilulaTocando: LinearLayout
    private lateinit var capaTocando: ImageView
    private lateinit var barrasTocando: BarrasSom
    private lateinit var txtTocandoTitulo: TextView
    private lateinit var txtTocandoSub: TextView
    private var pacoteTocando: String? = null
    private var tocandoNosso = false   // a pílula é a nossa rádio (OuvirServico), não outro app
    private var tipoNosso = ""          // "radio" ou "podcast"
    private var capaTocandoDe: String? = null   // pacote + título da capa na tela (não redesenha a mesma)
    private val vigiaTocando by lazy { TocandoAgoraTv.Vigia(this) { ui.removeCallbacks(tarefaTocando); ui.post(tarefaTocando) } }

    /** Lê o que toca fora da thread da tela e mostra ou esconde a pílula. Pausada e visível: confere de novo em 1 min. */
    private val tarefaTocando = Runnable {
        filaApps.execute {
            val p = runCatching { TocandoAgoraTv.paraHome(this) }.getOrNull()
            val icone = if (p != null && p.capa == null) runCatching { packageManager.getApplicationIcon(p.pacote) }.getOrNull() else null
            ui.post { if (!isDestroyed) mostrarTocando(p, icone) }
        }
    }

    /** O que o NOSSO som toca agora (tipo "radio"/"podcast" -> título): a linha de baixo de Rádio/Podcasts na cruz diz. */
    private var ouvindoCruz: Pair<String, String>? = null

    private fun mostrarTocando(p: TocandoAgoraTv.Pilula?, icone: android.graphics.drawable.Drawable?) {
        ui.removeCallbacks(tarefaTocando)
        val ouvindo = p?.takeIf { it.nosso && it.tocando }?.let { it.tipoNosso.ifEmpty { "radio" } to it.titulo }
        if (ouvindo != ouvindoCruz) { ouvindoCruz = ouvindo; atualizarCruz() }
        if (p == null) {
            if (pilulaTocando.hasFocus()) linhaApps.requestFocus()   // o foco não pode ficar numa pílula invisível
            capsulaTocando.visibility = View.GONE
            barrasTocando.tocando = false
            pacoteTocando = null; capaTocandoDe = null; capaTocando.setImageDrawable(null); tocandoNosso = false
            return
        }
        pacoteTocando = p.pacote
        tocandoNosso = p.nosso; tipoNosso = p.tipoNosso
        txtTocandoTitulo.text = p.titulo
        txtTocandoSub.text = p.subtitulo
        barrasTocando.tocando = p.tocando
        val de = p.pacote + "\u0000" + p.titulo + (if (p.capa != null) "" else "\u0000icone")
        if (de != capaTocandoDe) {
            capaTocandoDe = de
            if (p.capa != null) capaTocando.setImageBitmap(p.capa) else capaTocando.setImageDrawable(icone)
        }
        pilulaTocando.contentDescription = getString(R.string.l75_pilula, p.titulo, p.subtitulo)
        capsulaTocando.visibility = View.VISIBLE
        // Pausada: some sozinha depois do prazo. A nossa rádio: confere também tocando (se o ":player" cair, não há aviso).
        if (!p.tocando || p.nosso) ui.postDelayed(tarefaTocando, 60_000)
    }

    /** Ideia 93: aniversário hoje ou amanhã (lista da TV). Mais de um: o primeiro e "+N". */
    private fun atualizarPilulaAniversario() {
        Fundo.rodar {
            val textos = runCatching { Aniversarios.textos(this) }.getOrDefault(emptyList())
            ui.post {
                if (isDestroyed) return@post
                if (textos.isEmpty()) { if (pilulaAniversario.hasFocus()) linhaApps.requestFocus(); pilulaAniversario.visibility = View.GONE }
                else {
                    txtAniversario.text = "\uD83C\uDF82 " + textos.first() + if (textos.size > 1) "  +${textos.size - 1}" else ""
                    pilulaAniversario.visibility = View.VISIBLE
                }
                atualizarAvisos()
            }
        }
    }

    /** Ideia 12: lê os lembretes fora da tela e mostra/esconde a pílula do próximo jogo do "Meu time" (LembretesTv). */
    private fun atualizarPilulaJogo() {
        Fundo.rodar {
            val texto = runCatching { LembretesTv.proximoJogo(this) }.getOrNull()
            ui.post {
                if (isDestroyed) return@post
                if (texto == null) { if (pilulaJogo.hasFocus()) linhaApps.requestFocus(); pilulaJogo.visibility = View.GONE }
                else { txtJogo.text = texto; pilulaJogo.visibility = View.VISIBLE }
                atualizarAvisos()
            }
        }
    }

    /** O próximo despertador na pílula: "hoje 18:30", "amanhã 6:30" ou "sáb 7:00". Sem alarme, a pílula some. */
    private fun atualizarPilulaAlarme() {
        val prox = runCatching { Despertador.proximo(this) }.getOrNull()
        if (prox == null) { if (pilulaAlarme.hasFocus()) linhaApps.requestFocus(); pilulaAlarme.visibility = View.GONE; atualizarAvisos(); return }
        val quando = java.util.Calendar.getInstance().apply { timeInMillis = prox.second }
        val hoje = java.util.Calendar.getInstance()
        val dias = ((java.time.LocalDate.of(quando.get(java.util.Calendar.YEAR), quando.get(java.util.Calendar.MONTH) + 1, quando.get(java.util.Calendar.DAY_OF_MONTH)).toEpochDay()) -
            java.time.LocalDate.of(hoje.get(java.util.Calendar.YEAR), hoje.get(java.util.Calendar.MONTH) + 1, hoje.get(java.util.Calendar.DAY_OF_MONTH)).toEpochDay())
        val hora = java.text.SimpleDateFormat("H:mm", java.util.Locale.getDefault()).format(java.util.Date(prox.second))
        val dia = when (dias) {
            0L -> getString(R.string.home_alarme_hoje)
            1L -> getString(R.string.home_alarme_amanha)
            else -> java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(java.util.Date(prox.second)).trimEnd('.')
        }
        txtAlarme.text = "$dia $hora"
        pilulaAlarme.visibility = View.VISIBLE
        atualizarAvisos()
    }

    /**
     * "Diga o que quer ver": abre o Gemini, igual ao botão do microfone do controle.
     *
     * Por que pelo shell (medido na Sala, 10/11/2026): o microfone do controle manda a tecla ASSIST, e o Android abre a
     * sessão do serviço de voz (katniss = Gemini). Já a Intent ACTION_ASSIST que um app consegue mandar cai em outra porta
     * do mesmo app, a SearchActivityTrampoline, que é a tela do assistente ANTIGO. App comum não tem como abrir a sessão
     * do serviço de voz; o shell (uid 2000) tem: injeta a mesma tecla do controle. Sem shell, cai no caminho antigo.
     */
    private fun abrirBuscaPorVoz() {
        Fundo.rodar {
            // A fila do shell pode estar ocupada; 5 s bastam para uma tecla.
            val ok = GuardiaoShizuku.comando(this, "input keyevent KEYCODE_ASSIST", 5_000) != null
            if (!ok) runOnUiThread { abrirAssistenteAntigo() }
        }
    }

    /** Plano B sem shell: o assistente pela Intent pública (abre a tela antiga do Google, mas abre). */
    private fun abrirAssistenteAntigo() {
        val tentativas = listOf(
            Intent(Intent.ACTION_ASSIST),
            Intent("android.intent.action.VOICE_ASSIST"),
            Intent("android.search.action.GLOBAL_SEARCH"),
        )
        for (i in tentativas) {
            try { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return } catch (e: Exception) { }
        }
        AvisoTv.rapido(this, getString(R.string.home_busca_voz), getString(R.string.home_voz_indisponivel), 4)
    }

    private fun aplicarTeste() {
        capa.visibility = if (TesteHome.capa) View.VISIBLE else View.GONE
        destaque.visibility = if (TesteHome.destaque) destaque.visibility.takeIf { it != View.GONE } ?: View.INVISIBLE else View.GONE
        fundo?.visibility = if (TesteHome.fundo) View.VISIBLE else View.INVISIBLE
        capa.setLayerType(if (TesteHome.capaCamada) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null)
    }

    /**
     * O destaque: [rotulo] (pequeno coral, some se null), [titulo] grande e [sub] na mesma linha (some se null); embaixo,
     * [meta] (nota, gênero, ano...) e [sinopse], que somem se vazios.
     */
    private fun mostrarDestaque(rotulo: String?, titulo: String, sub: String?, meta: CharSequence? = null, sinopse: String? = null) {
        if (!TesteHome.destaque) return
        txtDestaqueRotulo.text = rotulo
        txtDestaqueRotulo.visibility = if (rotulo == null) View.GONE else View.VISIBLE
        txtDestaqueTitulo.text = titulo
        txtDestaqueSub.text = sub
        txtDestaqueSub.visibility = if (sub == null) View.GONE else View.VISIBLE
        txtDestaqueMeta.text = meta
        txtDestaqueMeta.visibility = if (meta.isNullOrEmpty()) View.GONE else View.VISIBLE
        txtDestaqueSinopse.text = sinopse
        txtDestaqueSinopse.visibility = if (sinopse.isNullOrBlank()) View.GONE else View.VISIBLE
        if (destaque.visibility != View.VISIBLE) {
            destaque.alpha = 0f
            destaque.visibility = View.VISIBLE
        }
        destaque.animate().cancel()
        destaque.animate().alpha(1f).setDuration(200).start()
    }

    /** Apaga a informação do card com o foco no topo e a traz de volta quando o foco desce. */
    private fun posicionarDestaque(foco: View) {
        val pais = generateSequence(foco.parent) { it.parent }
        // Foco no topo: a legenda apaga (ela fala de um card, e nenhum card está em foco; visto na Sala, 12/11). Não usa
        // esconderDestaque: o texto continua lá e volta ao descer, mesmo que o card não chame mostrarDestaque de novo.
        if (foco === topoRaiz || pais.any { it === topoRaiz }) {
            destaque.animate().cancel()
            destaque.animate().alpha(0f).setDuration(150).start()
            return
        }
        // Fora do topo: a informação volta (lugar fixo, em cima de "ASSISTIR"; ver TOPO_DESTAQUE).
        if (destaque.visibility == View.VISIBLE && destaque.alpha < 1f) {
            destaque.animate().cancel()
            destaque.animate().alpha(1f).setDuration(200).start()
        }
    }

    private fun esconderDestaque() {
        if (destaque.visibility != View.VISIBLE) return
        destaque.animate().cancel()
        destaque.animate().alpha(0f).setDuration(150).withEndAction { destaque.visibility = View.INVISIBLE }.start()
    }

    /**
     * Destaque do card TV (12/11/2026, ficha como a do launcher do Google): o programa que passa agora no último canal
     * (o player abre nele) em letra grande, "TV · canal N", "● AO VIVO 13:30 – 14:15 · faltam 12 min" e a descrição do
     * guia, se ele trouxer. Sem guia: "TV" e o canal, como antes.
     */
    private fun destaqueTv() {
        val u = ultimoTv
        // Gravando (14/11/2026): o rótulo em cima do título diz o quê e até quando, seja qual for o último canal.
        val rotulo = gravandoTv?.let { "● " + getString(R.string.grv_selo_home, it) }
        if (u == null) { mostrarDestaque(rotulo, getString(R.string.home_card_tv), getString(R.string.home_destaque_tv_sub)); return }
        val agora = System.currentTimeMillis()
        val p = u.programas.firstOrNull { it.inicio <= agora && agora < it.fim && it.titulo.isNotBlank() }
        if (p == null) {
            mostrarDestaque(rotulo, getString(R.string.home_card_tv), getString(R.string.home_destaque_tv_sub_canal, u.numero)); return
        }
        val fmt = java.text.SimpleDateFormat("HH:mm", Locale.getDefault())
        val meta = android.text.SpannableStringBuilder()
        meta.append("● " + getString(R.string.player_ao_vivo), android.text.style.ForegroundColorSpan(Estilo.ERRO), 0)
        meta.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, meta.length, 0)
        meta.append("   ${fmt.format(java.util.Date(p.inicio))} – ${fmt.format(java.util.Date(p.fim))}")
        meta.append("  ·  " + getString(R.string.home_faltam, duracaoTexto(((p.fim - agora + 59_999) / 60_000).toInt())))
        mostrarDestaque(rotulo, p.titulo, getString(R.string.home_dest_tv, u.numero), meta, p.descricao.ifBlank { null })
    }

    /**
     * Destaque de Séries e Filmes: o último título aberto (ficha gravada ao tocar), com nota, gênero, ano, duração,
     * quanto falta e a sinopse. Sem nada aberto ainda: o nome do card e a frase de sempre.
     */
    private fun destaqueVod(serie: Boolean) {
        val f = if (serie) fichaSerie else fichaFilme
        if (f == null) {
            mostrarDestaque(null, getString(if (serie) R.string.home_card_series else R.string.home_card_filmes),
                getString(if (serie) R.string.home_destaque_series else R.string.home_destaque_filmes))
            return
        }
        // Só "continue de onde parou" quando a ficha de Filmes também oferece Continuar (Vod.deveRetomar: 1 min ou mais):
        // com 20 s vistos a HOME dizia "continue" e a ficha "Assistir" do começo (visto na Sala, 17/11/2026).
        val pos = posicoesHome[f.chave]?.takeIf { it.duracao > 0 && (serie || Vod.deveRetomar(it)) }
        val sub = when {
            serie -> getString(R.string.home_dest_serie, f.episodio)
            pos != null -> getString(R.string.home_dest_filme)   // o "faltam" já vai na linha de baixo
            else -> getString(R.string.home_dest_filme_ultimo)
        }
        val meta = android.text.SpannableStringBuilder()
        f.nota?.takeIf { it > 0f }?.let {
            meta.append("★ " + String.format(Locale.getDefault(), "%.1f", it), android.text.style.ForegroundColorSpan(AMARELO_NOTA), 0)
            meta.setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, meta.length, 0)
        }
        val partes = listOfNotNull(
            f.genero.split(',', '/', '|').firstOrNull()?.trim()?.takeIf { it.isNotEmpty() },
            f.ano?.toString(),
            f.duracaoMin?.let { duracaoTexto(it) },
            pos?.let { ((it.duracao - it.ms) / 60_000).toInt() }?.takeIf { it > 0 }?.let { getString(R.string.home_faltam, duracaoTexto(it)) },
        )
        if (partes.isNotEmpty()) { if (meta.isNotEmpty()) meta.append("   "); meta.append(partes.joinToString("  ·  ")) }
        mostrarDestaque(null, f.titulo, sub, meta, f.sinopse)
    }

    /** "1 h 59 min", "45 min", "2 h". */
    private fun duracaoTexto(min: Int): String {
        val h = min / 60; val m = min % 60
        return when { h == 0 -> "$m min"; m == 0 -> "$h h"; else -> "$h h $m min" }
    }

    /**
     * Linha de baixo de um app (dono, 10/11/2026, opção A): uma frase curta escrita à mão para os apps desta TV
     * ([DESCRICAO_APPS]); para app novo, a categoria que ele declara no idioma da TV (ex.: "Vídeo"); sem nada, "App".
     * (Medido: YouTube, Netflix e cia. não declaram categoria, por isso a tabela.)
     */
    private fun subApp(pacote: String, nome: String): String {
        // Ideia 13 (14/11/2026): entre a frase à mão e a categoria, a ficha do painel (FrasesApps decide).
        val c = categoria(pacote)
        return FrasesApps.escolher(pacote, nome, DESCRICAO_APPS[pacote]?.let { getString(it) }, fichasHome,
            resources.configuration.locales[0].language == "pt",
            if (c >= 0) ApplicationInfo.getCategoryTitle(this, c)?.toString() else null) ?: getString(R.string.home_destaque_app)
    }

    /** Lê o banner (ou a cor do ícone) de um app. Roda em qualquer thread; o resultado vai para [capas]. */
    private fun lerCapa(pacote: String, icone: Drawable?): Pair<Bitmap?, IntArray> {
        val b = CapaFoco.banner(packageManager, pacote)?.let { runCatching { CapaFoco.bitmapReduzido(it) }.getOrNull() }
        val ic = icone ?: runCatching { packageManager.getApplicationIcon(pacote) }.getOrNull()
        val cores = ic?.let { runCatching { CapaFoco.corDoIcone(it) }.getOrNull() } ?: intArrayOf(0xFF2B2F3A.toInt(), Estilo.FUNDO)
        return (b to cores).also { capas[pacote] = it }
    }

    private fun mostrarCapaDoApp(pacote: String, icone: Drawable?) {
        capaPedida = pacote
        val pronta = capas[pacote]
        if (pronta != null) { capa.mostrar(pronta.first, if (pronta.first == null) pronta.second else null); return }
        // Ainda não lida (app novo, ou a leitura antecipada não chegou nele): lê fora e mostra se o foco ainda for dele.
        filaApps.execute {
            val (img, cores) = lerCapa(pacote, icone)
            ui.post { if (!isDestroyed && capaPedida == pacote) capa.mostrar(img, if (img == null) cores else null) }
        }
    }

    private fun preencherContinuar() {
        filaApps.execute {
            val temTv = PlayerArmazem.temContas(this)
            val ultimo = if (temTv) PlayerArmazem.ultimo(this)?.takeIf { ultimoLiberado(it) }?.let { programasDeAgora(it) } else null
            // Séries e Filmes: conta Xtream, ou lista M3U que traz filmes/episódios (ideia 26). fontes() decifra: fora da tela.
            val temVod = temTv && PlayerCarga.temVod(this)
            gravandoTv = if (temTv) Gravacoes.resumoParaHome(this) else null
            // Os "apps recentes" saíram desta linha (10/11/2026, dono: "por que esses cards repetem os apps de baixo?").
            // A linha de apps já vem ordenada por uso recente, então os 4 recentes eram sempre os 4 primeiros apps de
            // baixo, em tamanho grande. E o "continuar assistindo" de verdade de Netflix/YouTube não é alcançável: a
            // tabela Watch Next do Android está vazia nesta TV (medido: content query watch_next_program = vazio).
            // Ficha e arte do último filme/série (gravadas por FilmesActivity/SeriesActivity ao tocar). Só com catálogo.
            val filme = if (temVod) PlayerArmazem.capaHome(this, serie = false) else null
            val serie = if (temVod) PlayerArmazem.capaHome(this, serie = true) else null
            fichaFilme = filme?.ficha
            fichaSerie = serie?.ficha
            posicoesHome = if (temVod) PlayerArmazem.posicoes(this) else emptyMap()
            if (cruz != null) lerContinuarCruz()
            ui.post { if (!isDestroyed) desenharContinuar(temTv, temVod, ultimo) }
            // Plugins (degrau 3, PluginsTv): desenha já com os cartões da última leitura e só redesenha se mudaram. Sem
            // plugin permitido, cartoes() volta na hora; com plugin, cada um tem 2 s de prazo (aqui, fora da tela).
            val novos = PluginsTv.cartoes(this)
            if (novos != cartoesPlugin) { cartoesPlugin = novos; ui.post { if (!isDestroyed) desenharContinuar(temTv, temVod, ultimo) } }
            montarCapasAtalho()
            carregarArte(IconeApp.Tipo.FILMES, filme)
            carregarArte(IconeApp.Tipo.SERIES, serie)
        }
    }

    /**
     * O card TV com o programa de AGORA (12/11/2026, dono: "TV não mostra nada diferente"): os programas que o player
     * gravou no ultimo.json envelhecem (o player foi aberto horas atrás). Aqui lê o guia em cache (guia.db, só leitura,
     * nunca baixa) para o canal e troca pelos próximos de agora. Sem guia, ou canal sem guia: fica o que veio.
     * Roda na fila dos apps. Canal de ultimo.json antigo (sem guiaId): procura na lista em cache, uma vez por canal.
     */
    private fun programasDeAgora(u: PlayerArmazem.Ultimo): PlayerArmazem.Ultimo {
        val agora = System.currentTimeMillis()
        if (u.programas.any { it.inicio <= agora && agora < it.fim }) return u   // o gravado ainda vale
        val guiaId = u.guiaId.ifEmpty {
            guiaIdAchado?.takeIf { it.first == u.id }?.second ?: runCatching {
                PlayerCarga.canaisDoCache(this).firstOrNull { it.id == u.id }?.guiaId.orEmpty()
            }.getOrDefault("").also { guiaIdAchado = u.id to it }
        }
        if (guiaId.isEmpty()) return u
        val novos = runCatching { PlayerCarga.guiaDoCache(this, setOf(guiaId)).proximos(guiaId, agora, 4) }.getOrNull()
        return if (novos.isNullOrEmpty()) u else u.copy(programas = novos)
    }
    @Volatile private var guiaIdAchado: Pair<String, String>? = null   // canal -> guiaId (só para ultimo.json antigo)

    /**
     * Baixa (ou lê do cache de disco do player) a arte de um card: a 1ª cena (backdrop, larga) ou o pôster. Roda na fila
     * dos apps. Só refaz quando o endereço muda (outro filme/série). Pronta, troca o fundo se o card estiver em foco.
     */
    private fun carregarArte(tipo: IconeApp.Tipo, c: PlayerArmazem.CapaHome?) {
        val url = c?.cenas?.firstOrNull() ?: c?.poster.orEmpty()
        if (url.isEmpty()) { arteAtalho.remove(tipo); return }
        if (arteAtalho[tipo]?.first == url) return
        // 640 px: a capa é desenhada em 300 dp (metade, ampliada 2x) e escurecida; mais que isso só gasta memória.
        val img = PlayerLogos.bitmapAgora(this, url, LARGURA_ARTE, opaco = true) ?: return
        arteAtalho[tipo] = url to img
        ui.post { if (!isDestroyed && currentFocus?.getTag(R.id.tag_capa_atalho) == tipo) mostrarCapaAtalho(tipo) }
    }

    /**
     * Monta (fora da tela) o fundo dos quatro cards de Assistir: o ícone grande sobre o degradê da cor do card.
     * 12/11/2026, dono: "Quero o filmes e séries sem esse negócio da capa e zoom. Deixa como os outros cards mesmo." Saiu a
     * cena (backdrop) do último título com zoom lento e troca a cada 7 s; Séries e Filmes ficam iguais a TV e Na rede.
     */
    private fun montarCapasAtalho() {
        if (capasAtalho.size == 5) return   // não depende de nada que mude: monta uma vez
        if (montandoCapas) return
        montandoCapas = true
        Fundo.rodar("CapasAtalho") {
            try {
                fun montar(tipo: IconeApp.Tipo, cores: IntArray) {
                    capasAtalho[tipo] = "" to listOf(CapaAtalho.montar(cores, tipo, null))
                }
                montar(IconeApp.Tipo.TV, CapaFoco.CORES_TV)
                montar(IconeApp.Tipo.SERIES, CORES_SERIES)
                montar(IconeApp.Tipo.FILMES, CORES_FILMES)
                montar(IconeApp.Tipo.REDE, CORES_REDE)
                montar(IconeApp.Tipo.JOGOS, CORES_JOGOS)
                montar(IconeApp.Tipo.OUVIR, CORES_OUVIR)
                // O card já em foco quando a capa ficou pronta: troca o degradê pelo ícone na hora.
                ui.post { if (!isDestroyed) (currentFocus?.getTag(R.id.tag_capa_atalho) as? IconeApp.Tipo)?.let { mostrarCapaAtalho(it) } }
            } finally { montandoCapas = false }
        }
    }

    /** Fundo de um card de Assistir: o ícone grande, ou o degradê da cor enquanto ele não fica pronto. Parado, sem zoom. */
    private fun mostrarCapaAtalho(tipo: IconeApp.Tipo) {
        capaPedida = null
        // Jogos: o banner (logo oficial) do jogo usado por último, como o de um app em foco. Sem jogo usado, o controle.
        if (tipo == IconeApp.Tipo.JOGOS) ultimoJogo?.first?.let { p -> capas[p]?.first?.let { capa.mostrar(it, null); return } }
        // Séries e Filmes: a arte do último título, grande à direita (12/11/2026). Sem arte, o ícone como os outros.
        arteAtalho[tipo]?.second?.let { capa.mostrar(it, null, arte = true); return }
        val img = capasAtalho[tipo]?.second?.firstOrNull()
        val cores = when (tipo) {
            IconeApp.Tipo.SERIES -> CORES_SERIES
            IconeApp.Tipo.FILMES -> CORES_FILMES
            IconeApp.Tipo.REDE -> CORES_REDE
            IconeApp.Tipo.JOGOS -> CORES_JOGOS
            IconeApp.Tipo.OUVIR -> CORES_OUVIR
            else -> CapaFoco.CORES_TV
        }
        capa.mostrar(img, if (img == null) cores else null)
    }

    /**
     * O último canal ainda pode aparecer no "Continuar"? A mesma regra do player (PlayerFiltro.podeIrParaHome): nada de
     * canal bloqueado pelo controle parental nem fora do modo infantil, que podem ter sido ligados DEPOIS de o canal
     * ser gravado. Sem parental valendo, não lê a lista (o player já conferiu ao gravar, e a HOME precisa abrir rápido).
     * Roda fora da thread da tela (filaApps): a lista de canais pode ter milhares de linhas.
     */
    private fun ultimoLiberado(u: PlayerArmazem.Ultimo): Boolean {
        val parental = PlayerArmazem.parental(this)
        if (!parental.ativo) return true
        val canais = runCatching { PlayerCarga.canaisDoCache(this) }.getOrDefault(emptyList())
        val ocultas = PlayerFiltro.ocultasEfetivas(canais, PlayerArmazem.gruposEscondidos(this), PlayerArmazem.gruposLiberados(this))
        return PlayerFiltro.ultimoPodeAparecer(u.id, canais.firstOrNull { it.id == u.id }, ocultas, parental)
    }

    private fun desenharContinuar(temTv: Boolean, temVod: Boolean, ultimo: PlayerArmazem.Ultimo?) {
        if (emCruz) { ultimoTv = ultimo; cruzTemTv = temTv; cruzTemVod = temVod; cruzContinuarLido = true; atualizarCruz(); return }
        val focoAqui = linhaContinuar.hasFocus()
        linhaContinuar.removeAllViews()
        // quadrado = true: os atalhos (TV, Séries, Filmes, Na rede). Desde 12/11/2026 (dono) eles têm o tamanho e o
        // passo dos apps da linha de baixo (RET_L x RET_A), e cada card fica em cima de um app. O nome ficou para não mexer
        // em quem chama.
        fun adicionar(v: View, quadrado: Boolean = false) = linhaContinuar.addView(v,
            if (quadrado) LinearLayout.LayoutParams(dp(RET_L), dp(RET_A)).apply { marginEnd = dp(2 * ANEL + 4) }
            else LinearLayout.LayoutParams(dp(Grade.CARD_LARGURA), dp(Grade.CARD_ALTURA)).apply { marginEnd = dp(Grade.VAO) })
        ultimoTv = ultimo
        if (currentFocus?.tag == TOKEN_TV) destaqueTv()   // o card da TV já em foco: agora com o programa de agora
        // Esboço do dono (06/11 noite): "a home já é parte do player". Os 3 primeiros lugares são TV (os canais), Séries e
        // Filmes (cada um abre só o seu catálogo); depois vem o Continuar de sempre.
        if (temTv) adicionar(cartaoAtalho(getString(R.string.home_card_tv), IconeApp.Tipo.TV, CapaFoco.CORES_TV,
            { destaqueTv(); mostrarCapaAtalho(IconeApp.Tipo.TV) }) { abrirTv() }.apply { tag = TOKEN_TV }, quadrado = true)
        if (temVod) {
            adicionar(cartaoAtalho(getString(R.string.home_card_series), IconeApp.Tipo.SERIES, CORES_SERIES, {
                destaqueVod(serie = true)
                mostrarCapaAtalho(IconeApp.Tipo.SERIES)
            }) { abrirCatalogo(SeriesActivity::class.java) }, quadrado = true)
            adicionar(cartaoAtalho(getString(R.string.home_card_filmes), IconeApp.Tipo.FILMES, CORES_FILMES, {
                destaqueVod(serie = false)
                mostrarCapaAtalho(IconeApp.Tipo.FILMES)
            }) { abrirCatalogo(FilmesActivity::class.java) }, quadrado = true)
        }
        // Mídias da rede (07/11): sempre presente, logo depois de Filmes. Não depende de conta de TV.
        adicionar(cartaoAtalho(getString(R.string.home_card_rede), IconeApp.Tipo.REDE, CORES_REDE, {
            mostrarDestaque(null, getString(R.string.home_card_rede), getString(R.string.home_destaque_rede))
            mostrarCapaAtalho(IconeApp.Tipo.REDE)
        }) { abrirCatalogo(MidiaRedeActivity::class.java) }, quadrado = true)   // quadrado também (dono, 07/11)
        // Ouvir (17/11/2026, Paulo escolheu "cartão próprio"): rádio + podcasts. Com um episódio tocando, abre os podcasts.
        if (Extras.ligado(this, Extras.Chave.CARD_OUVIR)) adicionar(cartaoAtalho(getString(R.string.home_card_ouvir), IconeApp.Tipo.OUVIR, CORES_OUVIR, {
            mostrarDestaque(null, getString(R.string.home_card_ouvir), getString(R.string.home_destaque_ouvir))
            mostrarCapaAtalho(IconeApp.Tipo.OUVIR)
        }) { abrirCatalogo(if (tocandoNosso && tipoNosso == "podcast") PodcastsActivity::class.java else RadioActivity::class.java) }, quadrado = true)
        // Jogos (14/11/2026, dono): sempre presente, depois de Na rede; os apps de jogo saem da linha de apps.
        if (JogosTv.cardLigado(this)) adicionar(cartaoAtalho(getString(R.string.home_card_jogos), IconeApp.Tipo.JOGOS, CORES_JOGOS, {
            mostrarDestaque(null, getString(R.string.home_card_jogos), resumoJogos())
            mostrarCapaAtalho(IconeApp.Tipo.JOGOS)
        }) { abrirCatalogo(JogosActivity::class.java) }, quadrado = true)
        // Addons (16/11/2026, estudo do Stremio): só aparece com pelo menos um addon instalado (painel web ou Configurações).
        if (AddonsTv.instalados(this).isNotEmpty()) adicionar(cartaoAtalho(getString(R.string.ad_titulo), IconeApp.Tipo.FILMES, CORES_REDE, {
            mostrarDestaque(null, getString(R.string.ad_titulo), getString(R.string.ad_destaque))
        }) { abrirCatalogo(AddonsActivity::class.java) }, quadrado = true)
        // Plugins de terceiros (degrau 3): no fim da linha, no máximo 3, desenhados por NÓS com os dados que o plugin mandou.
        for (c in cartoesPlugin) adicionar(cartaoPlugin(c), quadrado = true)
        rotuloContinuar?.text = getString(if (JogosTv.cardLigado(this)) R.string.home_linha_continuar_jogos else R.string.home_linha_continuar)
        // O card Buscar saiu (12/11/2026, dono): a lupa do topo abre a mesma busca única, e a linha ganha espaço.
        // O card "AO VIVO · Canal N" saiu (10/11/2026): abria o mesmo player, no mesmo último canal, que o card TV.
        caixaContinuar.visibility = if (linhaContinuar.childCount > 0) View.VISIBLE else View.GONE
        prenderEsquerda(linhaContinuar.getChildAt(0))
        prenderDireita(linhaContinuar.getChildAt(linhaContinuar.childCount - 1))
        // A HOME abre com o foco no card TV (dono, 06/11 noite: "a home já é parte do player"). Só na abertura: voltando de
        // um app, o foco fica onde estava.
        if (focoInicialNaTv) {
            focoInicialNaTv = false
            // return: o else-if lá embaixo ("foco no Continuar -> volta aos apps") tirava o foco do TV na hora (visto na Sala).
            if (temTv && atalhoAberto < 0) { linhaContinuar.getChildAt(0)?.requestFocus(); return }
        }
        if (atalhoAberto >= 0) {
            (linhaContinuar.getChildAt(atalhoAberto) ?: linhaApps.getChildAt(indiceFoco.coerceAtLeast(0)))?.requestFocus()
            atalhoAberto = -1
        } else if (focoAqui) linhaContinuar.getChildAt(0)?.requestFocus() ?: linhaApps.requestFocus()
        // Ao voltar de um app, o foco é da linha de apps (visto na Sala: voltando do player, ele caía no Continuar).
        else if (linhaContinuar.hasFocus()) linhaApps.getChildAt(indiceFoco.coerceAtLeast(0))?.requestFocus()
    }

    /** Card 16:9 do Continuar: o banner (ou o ícone no meio, ou o coral da TV), com o nome num selo embaixo. */
    private fun cartaoContinuar(nome: String, banner: Bitmap?, icone: Drawable?, aoVivo: Boolean, aoFocar: () -> Unit, aoAbrir: () -> Unit): View =
        FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(10).toFloat())
            }
            background = if (aoVivo) GradientDrawable(GradientDrawable.Orientation.TL_BR, CapaFoco.CORES_TV)
                else ColorDrawable(0xFF1B2029.toInt())
            when {
                banner != null -> addView(ImageView(this@MainActivity).apply {
                    setImageBitmap(banner); scaleType = ImageView.ScaleType.CENTER_CROP
                }, FrameLayout.LayoutParams(MATCH, MATCH))
                icone != null -> addView(ImageView(this@MainActivity).apply { setImageDrawable(icone) },
                    FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER))
            }
            addView(TextView(this@MainActivity).apply {
                text = if (aoVivo) "● " + getString(R.string.player_ao_vivo) + "\n" + nome else nome
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, if (aoVivo) 14f else 11f)
                typeface = fonteUi
                maxLines = if (aoVivo) 3 else 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), dp(3), dp(8), dp(3))
                if (!aoVivo) background = GradientDrawable().apply { cornerRadius = dp(5).toFloat(); setColor(0xBF0E1020.toInt()) }
            }, FrameLayout.LayoutParams(WRAP, WRAP, if (aoVivo) Gravity.CENTER_VERTICAL or Gravity.START else Gravity.BOTTOM or Gravity.START).apply {
                setMargins(dp(8), dp(8), dp(8), dp(8))
            })
            contentDescription = nome   // idem: o MCP acha o card pelo nome
            setOnClickListener { aoAbrir() }
            setOnFocusChangeListener { v, temFoco ->
                v.foreground = if (temFoco) GradientDrawable().apply {
                    cornerRadius = dp(10).toFloat(); setStroke(dp(3), CorDoFundo.foco(context, Color.WHITE))   // cor do fundo, se ligada
                } else null
                val e = if (temFoco) 1.06f else 1f
                v.animate().scaleX(e).scaleY(e).setDuration(120).start()
                if (temFoco) aoFocar()
            }
        }

    /** Card fixo do começo do Continuar (TV, Séries, Filmes): degradê da cor dele, símbolo e nome grandes embaixo. */
    /**
     * Card de atalho do Continuar (TV, Séries, Filmes, Na rede) no sistema de design (10/11/2026): a cor de cada um vira
     * um matiz escuro (Estilo.matiz), o emoji vira ícone de linha, cantos de 16 dp e o foco padrão dos cards (cresce 8%,
     * anel de 3 dp; a cor do anel segue a opção "cor do fundo", se ligada).
     */
    private fun cartaoAtalho(nome: String, icone: IconeApp.Tipo, cores: IntArray, aoFocar: () -> Unit, aoAbrir: () -> Unit): View =
        FrameLayout(this).apply {
            isFocusable = true
            isClickable = true
            clipToOutline = true
            outlineProvider = object : ViewOutlineProvider() {
                override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(Estilo.RAIO).toFloat())
            }
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Estilo.matiz(cores[0], 0.55f), Estilo.matiz(cores[1], 0.45f)))
            foregroundGravity = Gravity.FILL
            addView(ImageView(this@MainActivity).apply { setImageDrawable(IconeApp(icone, 0xD9FFFFFF.toInt())) },
                FrameLayout.LayoutParams(dp(16), dp(16), Gravity.TOP or Gravity.END).apply { setMargins(dp(8), dp(8), dp(10), dp(8)) })
            addView(TextView(this@MainActivity).apply {
                text = nome
                setTextColor(Estilo.TEXTO)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)   // 15: cabe no card de 112 x 63 dp (o tamanho dos apps)
                typeface = Estilo.fonte(600)
                includeFontPadding = false
                maxLines = 1
            }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), dp(8), dp(8), dp(10)) })
            setTag(R.id.tag_capa_atalho, icone)   // montarCapasAtalho: qual capa trocar se este card já estiver em foco
            setOnClickListener { aoAbrir() }
            setOnFocusChangeListener { v, temFoco ->
                v.foreground = if (temFoco) GradientDrawable().apply {
                    cornerRadius = dp(Estilo.RAIO).toFloat(); setStroke(dp(3), CorDoFundo.foco(context, Estilo.FOCO))   // cor do fundo, se ligada
                } else null
                val e = if (temFoco) Estilo.ESCALA_CARD else 1f
                v.animate().scaleX(e).scaleY(e).translationZ(if (temFoco) dp(8).toFloat() else 0f).setDuration(Estilo.FOCO_MS).start()
                if (temFoco) aoFocar()
            }
        }

    /** Cartões dos plugins permitidos (PluginsTv), da última leitura na filaApps. */
    @Volatile private var cartoesPlugin: List<PluginCartao.Cartao> = emptyList()

    /**
     * O cartão de um plugin: do tamanho de um app, na cor que o plugin pediu (sempre opaca), com o VALOR grande no meio
     * (letra grande: "2 x 1", "18°") e o título embaixo. O detalhe vai para o destaque quando o cartão ganha o foco.
     */
    private fun cartaoPlugin(c: PluginCartao.Cartao): View = FrameLayout(this).apply {
        isFocusable = true
        isClickable = true
        contentDescription = listOf(c.titulo, c.valor).filter { it.isNotEmpty() }.joinToString(": ")
        clipToOutline = true
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(v: View, o: Outline) = o.setRoundRect(0, 0, v.width, v.height, dp(Estilo.RAIO).toFloat())
        }
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Estilo.matiz(c.cor, 0.6f), Estilo.matiz(c.cor, 0.4f)))
        if (c.valor.isNotEmpty()) addView(TextView(this@MainActivity).apply {
            text = c.valor
            setTextColor(Estilo.TEXTO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Estilo.fonte(700)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(10), dp(8), dp(8)) })
        addView(TextView(this@MainActivity).apply {
            text = c.titulo
            setTextColor(Estilo.TEXTO)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            typeface = Estilo.fonte(600)
            includeFontPadding = false
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.START).apply { setMargins(dp(12), dp(8), dp(8), dp(10)) })
        setOnClickListener { PluginsTv.abrir(this@MainActivity, c) }
        setOnFocusChangeListener { v, temFoco ->
            v.foreground = if (temFoco) GradientDrawable().apply {
                cornerRadius = dp(Estilo.RAIO).toFloat(); setStroke(dp(3), CorDoFundo.foco(context, Estilo.FOCO))
            } else null
            val e = if (temFoco) Estilo.ESCALA_CARD else 1f
            v.animate().scaleX(e).scaleY(e).translationZ(if (temFoco) dp(8).toFloat() else 0f).setDuration(Estilo.FOCO_MS).start()
            if (temFoco) {
                mostrarDestaque(null, c.titulo.ifEmpty { c.valor }, c.detalhe.ifEmpty { null })
                capaPedida = null
                capa.mostrar(null, intArrayOf(Estilo.matiz(c.cor, 0.6f), Estilo.matiz(c.cor, 0.35f)))
            }
        }
    }

    /** "ASSISTIR" ou "ASSISTIR E JOGAR" (com o card Jogos), em cima da linha dos atalhos. */
    private var rotuloContinuar: TextView? = null

    /** Quantos apps de jogo a TV tem (lido em preencherApps, fora da tela). */
    @Volatile private var jogosNaTv = 0
    /** pacote e nome do jogo usado por último (UsageStats), ou null. Lido em preencherApps. */
    @Volatile private var ultimoJogo: Pair<String, String>? = null

    /** A frase do card Jogos em foco: "3 jogos · 1 controle ligado" ou, sem jogos, o que o card oferece. */
    /** Linha de baixo do item "Sofá": a turma do Discord (última vista na tela Jogos) ou os controles ligados. */
    private fun resumoSofa(): String {
        val t = (TurmaDiscordTv.guardado(this) as? TurmaDiscordTv.Estado.Pronta)?.turma
        if (t != null) {
            val sala = t.salas.firstOrNull()
            return if (sala != null) getString(R.string.cruz_sofa_voz, sala.membros.size, sala.nome)
                else listOf(t.servidor, getString(R.string.turma_online, t.online)).filter { it.isNotEmpty() }.joinToString(" · ")
        }
        val controles = runCatching { JogosTv.controles().size }.getOrDefault(0)
        return if (controles > 0) resources.getQuantityString(R.plurals.jogos_controles, controles, controles) else getString(R.string.cruz_sofa_sub)
    }

    private fun resumoJogos(): String {
        val controles = runCatching { JogosTv.controles().size }.getOrDefault(0)
        if (jogosNaTv == 0 && controles == 0) return getString(R.string.home_destaque_jogos_vazio)
        val partes = mutableListOf(resources.getQuantityString(R.plurals.jogos_qtd, jogosNaTv, jogosNaTv))
        ultimoJogo?.second?.let { partes.add(0, getString(R.string.home_jogos_ultimo, it)) }
        if (controles > 0) partes += resources.getQuantityString(R.plurals.jogos_controles, controles, controles)
        return partes.joinToString(" · ")
    }

    /** true até o 1º desenho do Continuar nesta abertura da HOME (ver desenharContinuar). */
    private var focoInicialNaTv = true

    /** ◀ e ▶ presos nas bordas: na fileira, o 1º e o último card; na grade, o 1º e o último de cada fileira. */
    private fun prenderBordas() {
        val n = linhaApps.childCount
        if (n == 0) return
        val cols = (linhaApps as? GradeApps)?.colunas ?: n
        for (i in 0 until n) {
            if (i % cols == 0) prenderEsquerda(linhaApps.getChildAt(i))
            if (i % cols == cols - 1 || i == n - 1) prenderDireita(linhaApps.getChildAt(i))
        }
    }

    /** ◀ no 1º card de uma linha não sai dela (antes o foco fugia para o relógio, lá em cima). */
    private fun prenderEsquerda(v: View?) {
        if (v == null) return
        if (v.id == View.NO_ID) v.id = View.generateViewId()
        v.nextFocusLeftId = v.id
    }

    /** ▶ no último card de uma linha não sai dela (teste de 15/11/2026: o foco pulava para o relógio, lá em cima). */
    private fun prenderDireita(v: View?) {
        if (v == null) return
        if (v.id == View.NO_ID) v.id = View.generateViewId()
        v.nextFocusRightId = v.id
    }

    /** Qual card fixo do Continuar (0 TV, 1 Séries, 2 Filmes) foi aberto: ao voltar, o foco volta nele. -1 = nenhum. */
    private var atalhoAberto = -1

    /** A busca única roda no processo da HOME (só lê os caches). Ao voltar, o foco volta no card Buscar. */
    private fun abrirBusca() {
        atalhoAberto = linhaContinuar.indexOfChild(currentFocus)
        try { startActivity(Intent(this, BuscaActivity::class.java)) }
        catch (e: Exception) { android.util.Log.w("TvLauncher.Busca", "não abriu: ${e.javaClass.simpleName}") }
    }

    /** Abre só um catálogo do player (Séries ou Filmes), no processo dele; o VOLTAR de lá cai de novo na HOME. */
    private fun abrirCatalogo(tela: Class<out Activity>) {
        atalhoAberto = linhaContinuar.indexOfChild(currentFocus)
        try { startActivity(Intent(this, tela)) }
        catch (e: Exception) { android.util.Log.w("TvLauncher.Player", "não abriu ${tela.simpleName}: ${e.javaClass.simpleName}") }
    }

    /** O nome embaixo do disco (filho 1 do card, ver [montarCartao]). */
    private fun nomeDoCartao(card: View): TextView? = (card as? ViewGroup)?.getChildAt(1) as? TextView

    /** O disco da TV ao vivo: coral (a cor do "ao vivo" nos esboços) com "TV" no meio. */
    private fun miniaturaTv(): View = TextView(this).apply {
        text = "TV"
        textSize = 19f   // disco de 56 dp (layout Palco)
        typeface = Fontes.nunito(600)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(0xFFFF7A6E.toInt(), 0xFFC0392B.toInt()))
        DesenhoHome.circular(this)
    }

    /** Abre o player (processo próprio, ver PlayerActivity) no último canal assistido. */
    private fun abrirTv() {
        atalhoAberto = linhaContinuar.indexOfChild(currentFocus)
        try { startActivity(Intent(this, PlayerActivity::class.java)) }
        catch (e: Exception) { android.util.Log.w("TvLauncher.Player", "não abriu o player: ${e.javaClass.simpleName}") }
    }

    /** O app num retângulo 16:9 (esboço do dono, 06/11 noite). */
    private fun miniaturaApp(app: Item): View = retanguloDoApp(app)

    /**
     * Retângulo do app: a arte de banner dele (a mesma da capa, já lida fora da tela em preencherApps). Sem banner, a cor
     * do ícone com o ícone e o nome dentro.
     */
    private fun retanguloDoApp(app: Item): View = FrameLayout(this).apply {
        val (banner, cores) = capas[app.pacote] ?: (null to intArrayOf(0xFF2B2F3A.toInt(), Estilo.FUNDO))
        DesenhoHome.arredondar(this, dp(10).toFloat())
        if (banner != null) {
            addView(ImageView(this@MainActivity).apply {
                setImageBitmap(banner); scaleType = ImageView.ScaleType.CENTER_CROP
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        } else {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, cores)
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), 0, dp(6), 0)
                addView(ImageView(this@MainActivity).apply { setImageDrawable(DesenhoHome.copia(resources, app.icone)) }, LinearLayout.LayoutParams(dp(30), dp(30)))
                addView(texto(12f, Color.WHITE, fonteUi).apply {
                    text = app.nome
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    typeface = Typeface.create(fonteUi, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(6) })
            }, FrameLayout.LayoutParams(MATCH, MATCH))
        }
    }

    private fun discoDoApp(app: Item): ImageView = ImageView(this).apply {
        setImageDrawable(DesenhoHome.copia(resources, app.icone))
        scaleType = ImageView.ScaleType.CENTER_CROP
        DesenhoHome.circular(this)
    }

    /**
     * Pilha: o disco do app da [frente] na frente e até 2 atrás, deslocados para cima e para a direita (como cartas).
     * Embaixo, o selo "▲▼ 1/3": a pilha tem vários apps e as setas trocam qual fica na frente. O nome é o do app da frente.
     */
    private fun miniaturaPilha(itens: List<Item>, frente: Int): View {
        val camadas = (listOf(itens[frente]) + itens.filterIndexed { i, _ -> i != frente }).take(3)
        val caixa = FrameLayout(this).apply { clipChildren = false }
        for (i in camadas.indices.reversed()) {   // de trás para a frente: o último adicionado fica por cima
            val ret = retanguloDoApp(camadas[i]).apply {
                alpha = if (i == 0) 1f else 0.7f - 0.25f * (i - 1)   // as de trás mais apagadas
            }
            caixa.addView(ret, FrameLayout.LayoutParams(MATCH, MATCH).apply {
                leftMargin = dp(6) * i
                topMargin = -dp(4) * i
                rightMargin = -dp(6) * i
                bottomMargin = dp(4) * i
            })
        }
        caixa.addView(IndicadorPilha(this),
            FrameLayout.LayoutParams(dp(IndicadorPilha.LARGURA_DP), dp(IndicadorPilha.ALTURA_DP), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
                bottomMargin = -dp(8)
            })
        return caixa
    }

    private fun miniaturaOcultos(nome: String): View = FrameLayout(this).apply {
        background = GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0x26FFFFFF) }
        addView(texto(12f, Color.WHITE, fonteUi).apply {
            text = "🙈  $nome"
            gravity = Gravity.CENTER
            maxLines = 2
        }, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    /**
     * Rola [barra] so o necessario para o card focado caber entre as margens da grade. Assim os cards ficam sempre
     * nas colunas: com 4 ou menos nada se mexe; com mais, a fila anda um card por vez.
     */
    private fun centralizar(card: View, barra: HorizontalScrollView) {
        val esquerda = card.left - barra.scrollX
        val direita = card.right - barra.scrollX
        val minimo = barra.paddingLeft
        val maximo = barra.width - barra.paddingRight
        when {
            esquerda < minimo -> barra.smoothScrollBy(esquerda - minimo, 0)
            direita > maximo -> barra.smoothScrollBy(direita - maximo, 0)
        }
    }

    // ---------------------------------------------------------------- ▼ numa pilha: trocar o app da frente

    /**
     * Num card de pilha, ▼ troca o app da frente para o próximo (dá a volta; segurar ▼ não gira a pilha inteira).
     * ▲ e o resto seguem o caminho normal do foco. Em card solto não faz nada.
     */
    private fun trocarDaFrente(event: KeyEvent): Boolean {
        // 15/11/2026 (teste de todas as funções): ▲ numa pilha trocava o app da frente em vez de subir de fileira; quem
        // queria ir para TV/Filmes ficava preso, e duas sessões de teste caíram nisso (uma abriu a Netflix sem querer).
        // Agora ▲ sobe como em qualquer card e só ▼ troca o app da frente: a fileira de apps é a última, ▼ não tinha uso.
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_DOWN) return false
        val atual = currentFocus ?: return false
        val token = atual.tag as? String ?: return false
        val pilha = lista.firstOrNull { it.token == token } as? Entrada.Pilha ?: return false
        // Grade (LayoutHome): com uma fileira embaixo, ▼ desce para ela; só troca o app da frente na última fileira.
        if (gradeDosApps && atual.focusSearch(View.FOCUS_DOWN)?.let { dentro(it, linhaApps) } == true) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) girarPilha(pilha, +1)
        return true
    }

    /** Troca o app da frente da [pilha] em [passo] (+1 proximo, -1 anterior) e redesenha so aquele card. */
    private fun girarPilha(pilha: Entrada.Pilha, passo: Int) {
        val total = pilha.itens.size
        val novo = (pilha.frente + passo + total) % total   // % da a volta; "+ total" evita numero negativo
        Organizacao.definirFrente(this, pilha.id, pilha.itens[novo].pacote)
        val posicao = lista.indexOf(pilha)
        val nova = Entrada.Pilha(pilha.id, pilha.itens, novo)
        lista[posicao] = nova
        // Poe o card novo ANTES de tirar o velho: tirar o card com foco faria o Android jogar o foco para o 1o card.
        val vista = criarCartao(nova)
        linhaApps.addView(vista, posicao)
        vista.requestFocus()
        linhaApps.removeViewAt(posicao + 1)
        prenderBordas()   // ◀ no 1º card (de cada fileira, na grade) e ▶ no último não escapam para o topo (home-7)
        ultimaAssinatura = null   // a linha mudou por fora do desenhar(): o próximo desenho refaz tudo
    }

    // ---------------------------------------------------------------- pastas (apps ocultos e "remover do grupo")

    private var pasta: Dialog? = null
    private var pastaToken: String? = null   // qual pasta está aberta: TOKEN_OCULTOS ou "pilha:<id>" (modo remover)

    /**
     * Abre uma janela por cima da tela inicial, como uma pasta. Voltar fecha.
     * Com [remover], é a lista dos apps de uma pilha: OK num app pede confirmação para tirá-lo do grupo.
     */
    private fun abrirPasta(entrada: Entrada, remover: Boolean = false) {
        val itens = when (entrada) {
            is Entrada.Pilha -> entrada.itens
            is Entrada.Ocultos -> entrada.itens
            is Entrada.App, is Entrada.Tv -> return
        }
        fecharPasta()
        val linha = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            clipChildren = false
        }
        val barra = HorizontalScrollView(this).apply {
            clipChildren = false
            clipToPadding = false
            isHorizontalScrollBarEnabled = false
            setPadding(dp(56), dp(24), dp(56), dp(24))
            addView(linha)
        }
        for (app in itens) {
            linha.addView(montarCartao(app.pacote, app.nome, miniaturaApp(app),
                {
                    if (remover && entrada is Entrada.Pilha) confirmarRemocao(app, entrada)
                    else { fecharPasta(); abrir(app.pacote) }
                },
                { v -> centralizar(v, barra) }))
        }
        val ehOcultos = entrada is Entrada.Ocultos
        val conteudo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(texto(24f, Color.WHITE, fonteLeve).apply {
                text = if (ehOcultos) getString(R.string.cfg_sec_ocultos) else getString(R.string.home_qual_sai)
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(56), 0, dp(56), 0)
            }, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(barra, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(texto(13f, textoSuave).apply {
                text = if (ehOcultos) getString(R.string.home_ocultos_dica)
                       else getString(R.string.home_remover_dica)
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        val janela = Dialog(this, android.R.style.Theme_Translucent_NoTitleBar)
        janela.setContentView(conteudo)
        janela.window?.apply {
            setBackgroundDrawable(ColorDrawable(0xF70E1311.toInt()))   // escurece a tela inicial por trás
            setLayout(MATCH, MATCH)
        }
        // A janela tem as próprias teclas: o "segurar OK" (nos ocultos) precisa ser detectado aqui também.
        janela.setOnKeyListener { _, _, ev -> tratarSegurar(ev, janela.currentFocus) }
        janela.setOnDismissListener {
            if (pasta === janela) { pasta = null; pastaToken = null }
        }
        pasta = janela
        pastaToken = entrada.token
        janela.show()
        barra.post { linha.getChildAt(0)?.requestFocus() }
    }

    private fun fecharPasta() {
        pasta?.dismiss()
        pasta = null
        pastaToken = null
    }

    /** Pergunta antes de tirar [app] da [pilha]. O foco começa em "Cancelar": confirmar exige escolher "Remover". */
    private fun confirmarRemocao(app: Item, pilha: Entrada.Pilha) {
        val dialogo = CaixaTv(this)
            .setTitle(getString(R.string.home_remover_titulo, app.nome))
            .setMessage(getString(R.string.home_remover_msg))
            .setNegativeButton(getString(R.string.comum_cancelar), null)
            .setPositiveButton(getString(R.string.home_remover)) { _, _ ->
                Organizacao.tirarDaPilha(this, app.pacote)
                fecharPasta()
                reorganizar()
                // Se a pilha ainda existe (ficou com 2+ apps), reabre a lista para tirar mais um; senão ela se desfez.
                val restante = lista.firstOrNull { it is Entrada.Pilha && it.id == pilha.id }
                if (restante is Entrada.Pilha) {
                    ultimoToken = restante.token
                    abrirPasta(restante, remover = true)
                } else {
                    ultimoToken = app.pacote
                }
            }
            .show()
        dialogo.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
        menuAberto = dialogo
    }

    // ---------------------------------------------------------------- menu (segure OK em uma pilha)

    private class Opcao(val rotulo: String, val acao: () -> Unit)

    private var menuAberto: android.app.Dialog? = null
    private var engolirUp = false   // true entre o "segurar OK" e o menu fechar: o "soltar" da mesma pressão não pode virar clique

    /**
     * Segurar OK (ou ENTER) num card. Detectamos o "segurar" pelo repeatCount: o Android repete a tecla
     * enquanto ela está pressionada. O que acontece depende de onde:
     *  - app solto: menu (Mover, Ocultar da tela inicial, Desinstalar; ver MenuApp);
     *  - pilha: abre o menu (Mover, Remover cards do grupo);
     *  - app dentro da pasta "Ocultos": menu com "Mostrar na tela inicial" (e Desinstalar).
     * Devolve true se o evento foi tratado aqui.
     */
    private fun tratarSegurar(event: KeyEvent, focado: View?): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_CENTER && event.keyCode != KeyEvent.KEYCODE_ENTER) return false
        if (engolirUp) {   // o menu está abrindo: ignora as repetições e o "soltar" da mesma pressão
            if (event.action == KeyEvent.ACTION_UP) engolirUp = false
            return true
        }
        val segurado = event.repeatCount >= 1 || (event.flags and KeyEvent.FLAG_LONG_PRESS) != 0
        if (event.action != KeyEvent.ACTION_DOWN || !segurado) return false
        val token = focado?.tag as? String ?: return false   // só os cards têm tag
        if (token == TOKEN_OCULTOS || token == TOKEN_TV) return false   // "Ocultos" e "TV ao vivo" não têm opções
        val dentroDe = pastaToken
        if (dentroDe == TOKEN_OCULTOS) {
            engolirUp = true
            abrirMenu(token)
            return true
        }
        if (dentroDe != null) return false                    // pasta de "remover do grupo": segurar OK = apertar OK
        return when (lista.firstOrNull { it.token == token }) {
            is Entrada.App, is Entrada.Pilha -> { engolirUp = true; abrirMenu(token); true }
            else -> false
        }
    }

    private fun abrirMenu(token: String) {
        // PIN para mexer na HOME (Extras > PIN_HOME; TravaHome.kt). Acertou: fica liberado por 10 min.
        if (TravaHome.precisaPin(this)) { TravaHome.comPin(this) { abrirMenu(token) }; return }
        val entrada = lista.firstOrNull { it.token == token }
        val titulo: String
        val opcoes = mutableListOf<Opcao>()
        if (pastaToken == TOKEN_OCULTOS || (pastaToken == null && entrada is Entrada.App)) {
            titulo = nomeDe(token) + if (PainelApps.eSistema(this, token)) getString(R.string.home_app_sistema) else ""
            val acoes = MenuApp.acoes(pastaToken == TOKEN_OCULTOS, PainelApps.podeDesinstalar(this, token))
            for (a in acoes) opcoes += when (a) {
                MenuApp.Acao.MOSTRAR -> Opcao(getString(R.string.home_mostrar)) { Organizacao.mostrar(this, token); atualizarAposMudanca(token) }
                MenuApp.Acao.MOVER -> Opcao(getString(R.string.home_mover)) { iniciarMover(token, porSegurar = false) }
                MenuApp.Acao.OCULTAR -> Opcao(getString(R.string.home_ocultar)) { Organizacao.ocultar(this, token); atualizarAposMudanca(null); avisarOculto() }
                MenuApp.Acao.DESINSTALAR -> Opcao(getString(R.string.home_desinstalar)) { confirmarDesinstalar(token) }
            }
        } else if (pastaToken == null && entrada is Entrada.Pilha) {
            titulo = entrada.titulo
            opcoes += Opcao(getString(R.string.home_mover)) { iniciarMover(token, porSegurar = false) }
            opcoes += Opcao(getString(R.string.home_remover_cards)) { abrirPasta(entrada, remover = true) }
            // Uma linha "Desinstalar <app>" por app da pilha que pode sair (o da frente primeiro). App de fábrica não entra.
            val pacotes = entrada.itens.map { it.pacote }
            for (p in MenuApp.desinstalaveisDaPilha(pacotes, entrada.frente) { PainelApps.podeDesinstalar(this, it) })
                opcoes += Opcao(getString(R.string.home_desinstalar_app, nomeDe(p))) { confirmarDesinstalar(p) }
        } else {
            return
        }
        menuAberto = Estilo.lista(this, titulo, opcoes.map { it.rotulo }, aoFechar = { engolirUp = false }) { i -> opcoes[i].acao() }
    }

    /**
     * Pergunta antes de desinstalar (não tem volta: os dados do app somem junto). O foco começa no "Cancelar", para um
     * OK distraído não apagar nada. Quem desinstala é o PainelApps.desinstalar: em silêncio, pelo Device Owner, e
     * esperando o resultado de verdade do Android (fora da thread da tela, porque espera até 60 s).
     */
    private fun confirmarDesinstalar(pacote: String) {
        val nome = nomeDe(pacote)
        val dialogo = CaixaTv(this)
            .setTitle(getString(R.string.home_desinstalar_titulo, nome))
            .setMessage(R.string.home_desinstalar_aviso)
            .setNegativeButton(R.string.home_cancelar, null)
            .setPositiveButton(R.string.home_desinstalar) { _, _ ->
                mostrarAviso(getString(R.string.home_desinstalando, nome))
                Fundo.rodar("Desinstalar") {
                    val motivo = PainelApps.desinstalar(this, pacote)
                    Log.i("TvLauncher.Apps", "desinstalar $pacote pelo menu => ${motivo ?: "ok"}")
                    runOnUiThread {
                        if (isFinishing || isDestroyed) return@runOnUiThread
                        if (motivo == null) {
                            Organizacao.mostrar(this, pacote)   // limpa a marca de oculto, se tinha (o app não existe mais)
                            mostrarAviso(getString(R.string.home_desinstalado, nome))
                            // Relê os apps do Android: o reorganizar() reusa a lista "instalados" da memória, e o card
                            // do app desinstalado ficava na tela até a próxima volta à HOME (visto na Sala, 05/11/2026).
                            fecharPasta()
                            preencherApps()
                        } else {
                            mostrarAviso(getString(R.string.home_desinstalar_falhou, nome, motivo))
                        }
                    }
                }
            }
            .show()
        dialogo.getButton(AlertDialog.BUTTON_NEGATIVE)?.requestFocus()
    }

    private fun mostrarAviso(texto: String) = AvisoTv.rapido(this, "Apps", texto)

    /** Depois de mostrar/ocultar: fecha a pasta aberta, relê tudo e leva o foco para [foco] (ou perto de onde estava). */
    private fun atualizarAposMudanca(foco: String?) {
        fecharPasta()
        if (foco != null) ultimoToken = foco
        reorganizar()
    }

    private fun nomeDe(pacote: String): String = instalados.firstOrNull { it.pacote == pacote }?.nome ?: pacote

    // ---------------------------------------------------------------- mover cards (segure OK em um app, ou menu da pilha > Mover)

    private val corMover = Estilo.ACENTO

    /**
     * Visual de um card: normal (sem contorno), focado (contorno branco no banner, nome branco) ou
     * "sendo movido" (contorno verde-agua e maior). Funciona com os cards de montarCartao (filho 0 = banner, 1 = nome).
     */
    private fun estilizar(card: View, token: String, focado: Boolean) {
        val movendo = modoMover && token == tokenMovendo
        val grupo = card as? ViewGroup
        // Sem anel no foco (o circulo branco ficava pesado sobre o icone): so o disco crescendo 12% e o nome
        // ficando branco ja mostram onde esta, como no Google TV de referencia. O fio fino continua sempre
        // (nao so no foco): e o que impede um icone escuro (ex.: Netflix) de sumir no fundo.
        (grupo?.getChildAt(0))?.foreground = when {
            movendo -> contorno(corMover, 0x2EFF6A2B, 3)
            // O anel fica FORA do retângulo, com 2 dp de folga escura: num banner branco (Netflix, YouTube) um anel colado
            // na borda sumia (visto na Sala, 06/11 noite).
            focado -> android.graphics.drawable.InsetDrawable(contorno(Color.WHITE, Color.TRANSPARENT, 3), dp(1))
            else -> fioFino()
        }
        (grupo?.getChildAt(1) as? TextView)?.setTextColor(if (focado || movendo) Color.WHITE else textoSuave)
        // Só o disco cresce (o nome não: crescendo junto, ele encostava no nome do vizinho).
        val escala = if (movendo) 1.12f else if (focado) 1.08f else 1f
        // Cartões transparentes (LayoutHome): fora do foco o fundo aparece pelo cartão; o focado fica inteiro.
        val alfa = if (transparente && !focado && !movendo) LayoutHome.ALFA_TRANSPARENTE else 1f
        (grupo?.getChildAt(0) ?: card).animate().scaleX(escala).scaleY(escala).alpha(alfa).setDuration(120).start()
    }

    private val ANEL = 6   // folga (dp) entre o disco e o anel de foco
    // Retângulo de um app na linha de baixo (16:9, a arte de banner do app): 112x63 de fábrica; LayoutHome muda (15/11/2026).
    private val tamanhoCartoes by lazy { LayoutHome.tamanho(this) }
    private val RET_L by lazy { LayoutHome.largura(tamanhoCartoes) }
    private val RET_A by lazy { LayoutHome.altura(tamanhoCartoes) }
    /** A linha de apps desce o quanto o cartão cresceu (LayoutHome); 0 no tamanho de fábrica. */
    private val descer by lazy { LayoutHome.acrescimo(tamanhoCartoes) }
    private val transparente by lazy { LayoutHome.transparente(this) }
    private val emGrade by lazy { LayoutHome.grade(this) }
    /** Tela inicial em cruz (17/11/2026): a única tela inicial; a antiga só volta por adb (LayoutHome.cruz). */
    private val emCruz by lazy { LayoutHome.cruz(this) }
    /** Os apps em grade: no modo grade da HOME antiga e no "Todos os apps" da cruz. */
    private val gradeDosApps get() = emGrade || emCruz
    /** Passo de um cartão na fila (largura + anel dos dois lados + margem): o mesmo na fileira e na grade. */
    private val passoCartao get() = RET_L + 2 * ANEL + 4

    /** Fio de 1 dp em volta do disco (não da moldura): ícones escuros (Netflix) não somem no fundo. */
    private fun fioFino() = android.graphics.drawable.InsetDrawable(contorno(0x33FFFFFF, Color.TRANSPARENT, 1), dp(ANEL))

    /** Anel circular de [espessura] dp (com [preenchimento] por dentro), desenhado por cima do disco. */
    private fun contorno(cor: Int, preenchimento: Int, espessura: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = dp(10).toFloat()   // retângulo de cantos redondos, como o card (era OVAL no tempo dos discos)
        setColor(preenchimento)
        setStroke(dp(espessura), cor)
    }

    // ---------------------------------------------------------------- tela inicial em cruz (17/11/2026)
    // A cruz (HomeCruz) é montada com os MESMOS dados da HOME antiga: preencherContinuar (TV, Séries, Filmes, plugins) e
    // preencherApps (apps em ordem, ocultos, modo visita, Jogos). A grade dos apps continua sendo montada, escondida: é a
    // tela "Todos os apps" (com mover, ocultar e pilhas de hoje).

    private var cruz: HomeCruz? = null
    private var rotuloApps: TextView? = null
    /** "Todos os apps" aberto: a cruz some e a grade dos apps aparece; Voltar ou HOME fecham. */
    private var todosAbertos = false
    /** Entre onResume e onPause: a HOME é o que está na tela (onNewIntent chega ANTES do onResume vindo de um app). */
    private var homeNaFrente = false
    private var cruzTemTv = false
    private var cruzTemVod = false
    /** A 1ª leitura do player chegou (sem ela a cruz montaria sem a TV e pularia de lugar logo depois). */
    private var cruzContinuarLido = false
    private val estiloCruz by lazy { LayoutHome.iconesCruz(this) }
    /** Um desenho por lugar (id): a mesma instância volta a cada atualização (HomeCruz só troca o que mudou). */
    private val iconesCruz = HashMap<String, android.graphics.drawable.Drawable>()
    /** Cópia do ícone de um app para a cruz: um Drawable não pode estar em duas views (o card da grade usa o original). */
    private val iconesAppCruz = java.util.WeakHashMap<Item, android.graphics.drawable.Drawable>()

    private fun icCruz(lugar: String, tipo: IconeCruz.Tipo) = iconesCruz.getOrPut(lugar) { IconeCruz(tipo, estiloCruz) }
    private fun icApp(item: Item) = iconesAppCruz.getOrPut(item) {
        iconeCruzApp[item.pacote]?.takeIf { it.first == estiloAppCruz }?.second?.let { android.graphics.drawable.BitmapDrawable(resources, it) }
            ?: item.icone.constantState?.newDrawable(resources) ?: item.icone
    }

    /** Como os apps aparecem na cruz (LayoutHome.IconeAppCruz), lido uma vez por abertura da HOME. */
    private val estiloAppCruz by lazy { LayoutHome.iconeAppCruz(this) }
    /** Ícone de cada app no estilo da cruz, feito na fila dos apps (fora da tela): pacote -> (estilo, bitmap). */
    private val iconeCruzApp = java.util.concurrent.ConcurrentHashMap<String, Pair<LayoutHome.IconeAppCruz, Bitmap>>()

    /**
     * Faz o ícone de [item] no estilo escolhido (roda na fila dos apps). Banner: a arte de TV em 16:9 com cantos
     * arredondados, ou o ícone sobre a cor dele quando o app não tem banner. Redondo: o ícone num círculo. Solto: o
     * traço do ícone sem fundo (sem traço legível, o redondo). Tamanho: o do ícone grande da cruz, em pixels da TV.
     */
    private fun prepararIconeCruz(item: Item) {
        if (cruz == null || iconeCruzApp[item.pacote]?.first == estiloAppCruz) return
        val d = resources.displayMetrics.density
        val l = (HomeCruz.ICONE_L * d).toInt()
        val bmp: Bitmap? = runCatching {
            when (estiloAppCruz) {
                LayoutHome.IconeAppCruz.BANNER -> {
                    val a = l * 9 / 16
                    val out = Bitmap.createBitmap(l, a, Bitmap.Config.ARGB_8888)
                    val c = android.graphics.Canvas(out)
                    val raio = 10f * d
                    val forma = android.graphics.Path().apply { addRoundRect(android.graphics.RectF(0f, 0f, l.toFloat(), a.toFloat()), raio, raio, android.graphics.Path.Direction.CW) }
                    c.clipPath(forma)
                    val capa = capas[item.pacote]
                    val banner = capa?.first
                    if (banner != null && !banner.isRecycled) {
                        // corte central, como a capa grande (CapaFoco): preenche o 16:9 sem deformar
                        val esc = maxOf(l.toFloat() / banner.width, a.toFloat() / banner.height)
                        val w = banner.width * esc; val h = banner.height * esc
                        c.drawBitmap(banner, null, android.graphics.RectF((l - w) / 2, (a - h) / 2, (l + w) / 2, (a + h) / 2),
                            android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG))
                    } else {
                        val cores = capa?.second ?: intArrayOf(0xFF2B2F3A.toInt(), Estilo.FUNDO)
                        c.drawColor(cores[0])
                        val t = (a * 0.72f).toInt()
                        val ic = redondo(item.icone, t)
                        c.drawBitmap(ic, (l - t) / 2f, (a - t) / 2f, null)
                        ic.recycle()
                    }
                    out
                }
                LayoutHome.IconeAppCruz.REDONDO -> redondo(item.icone, (HomeCruz.ICONE_A * d).toInt())
                LayoutHome.IconeAppCruz.SOLTO -> {
                    val traco = DesenhoHome.iconeTraco(resources, item.icone, duotom = false)
                    val t = (HomeCruz.ICONE_A * d).toInt()
                    if (traco == null) redondo(item.icone, t)
                    else Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888).also { traco.setBounds(0, 0, t, t); traco.draw(android.graphics.Canvas(it)) }
                }
            }
        }.getOrNull()
        if (bmp != null) iconeCruzApp[item.pacote] = estiloAppCruz to bmp
    }

    /** O ícone (já do tamanho cheio, DesenhoHome.iconeRedondo) recortado em círculo de [t] px. */
    private fun redondo(icone: Drawable, t: Int): Bitmap {
        val out = Bitmap.createBitmap(t, t, Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        c.clipPath(android.graphics.Path().apply { addCircle(t / 2f, t / 2f, t / 2f, android.graphics.Path.Direction.CW) })
        icone.constantState?.newDrawable(resources)?.let { it.setBounds(0, 0, t, t); it.draw(c) }
        return out
    }

    private fun montarCruz(raiz: FrameLayout) {
        rolagem.visibility = View.GONE
        rotuloApps?.visibility = View.GONE
        caixaContinuar.visibility = View.GONE
        val prefs = getSharedPreferences(PREFS_CRUZ, MODE_PRIVATE)
        val c = HomeCruz(this, fonteUi, Typeface.create(fonteUi, Typeface.BOLD))
        c.lembrados = CruzLogica.Categoria.entries.mapNotNull { k -> prefs.getString(k.id, null)?.let { k.id to it } }.toMap()
        // HOME refeita pelo Android (memória para um app grande) até 6 h depois de abrir algo: volta naquele item.
        c.ultimoAberto = prefs.getString(ULTIMO_ABERTO, null)
            ?.takeIf { System.currentTimeMillis() - prefs.getLong(ULTIMO_ABERTO_EM, 0L) in 0..ULTIMO_ABERTO_VALE_MS }
        c.aoAbrir = { cat, item ->   // cada categoria lembra o último escolhido; e a cruz, o último aberto
            prefs.edit().putString(cat.id, item).putString(ULTIMO_ABERTO, cat.id + "|" + item)
                .putLong(ULTIMO_ABERTO_EM, System.currentTimeMillis()).apply()
        }
        raiz.addView(c, 0, FrameLayout.LayoutParams(MATCH, MATCH))   // atrás da barra de cima (o véu não a escurece)
        cruz = c
        c.id = View.generateViewId()
        c.aoMudar = { _, item -> arteDoItem(item) }
        c.aoSom = { som, cat -> if (homeNaFrente) SonsCruz.tocar(this, som, cat) }   // voltando de um app (HOME), sem som
        c.aoSubirTopo = { ui.post { bolinhaPerfil?.requestFocus() } }   // ▲ no 1º item: o perfil, 1º da barra
        c.aoPasso = { t -> deslizarArte(t) }
        raizCruz = raiz
        vigiarTela()
        montarBarraCruz(raiz, c)
    }

    // ------------------------------------------------------------------ barra de cima e arte do item, na cruz

    private val barraCruz by lazy { LayoutHome.barraCruz(this) }
    private var relogioCruz: View? = null       // relógio grande (barra RELOGIO), no canto livre de baixo
    private var tempoCruz: TextView? = null     // "27°" ao lado da hora, com a barra recolhida
    private var barraRecolhida = false

    /**
     * Aplica o estilo da barra de cima escolhido em Configurações (LayoutHome.BarraCruz). Recua e Relógio: com a cruz em
     * foco, a barra some (fica só a hora, ou nada no Relógio); o foco subindo para ela a traz de volta e a cruz desce 34 dp.
     * Leve: a barra fica, sem as cápsulas de vidro. As views somem só por alpha (continuam focáveis: o ▲ precisa achá-las).
     */
    private fun montarBarraCruz(raiz: FrameLayout, c: HomeCruz) {
        val topo = topoRaiz as? ViewGroup ?: return
        // ▼ em qualquer item da barra volta para a cruz (ela ocupa a tela inteira: a busca de foco "para baixo" não a acha).
        fun descer(v: View) {
            if (v.isFocusable) v.nextFocusDownId = c.id
            if (v is ViewGroup) for (i in 0 until v.childCount) descer(v.getChildAt(i))
        }
        descer(topo)
        if (barraCruz == LayoutHome.BarraCruz.HOJE) return
        val esquerda = topo.getChildAt(1) as? ViewGroup
        val direita = topo.getChildAt(2) as? LinearLayout
        if (barraCruz == LayoutHome.BarraCruz.LEVE) {
            esquerda?.let { e -> for (i in 0 until e.childCount) e.getChildAt(i).background = null }
            return
        }
        // Vidro (onda 3, como a barra do tvOS 26): uma faixa só, translúcida e parada, atrás da barra inteira; as cápsulas
        // perdem o fundo próprio. Sem desfoque de verdade (custa caro no Chromecast): gradiente claro + fio de luz em cima.
        if (barraCruz == LayoutHome.BarraCruz.VIDRO) {
            esquerda?.let { e -> for (i in 0 until e.childCount) e.getChildAt(i).background = null }
            val vidro = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(0x33FFFFFF, 0x14FFFFFF)).apply {
                cornerRadius = dp(ALTURA_TOPO / 2).toFloat()
                setStroke(dp(1), 0x2EFFFFFF)
            }
            topo.background = android.graphics.drawable.InsetDrawable(vidro, dp(Grade.MARGEM - 20), 0, dp(Grade.MARGEM - 20), 0)
            return
        }
        // Recua: a temperatura entra ao lado da hora enquanto a barra está recolhida.
        if (barraCruz == LayoutHome.BarraCruz.RECUA && direita != null) {
            val t = texto(18f, textoClaro, fonteUi).apply { includeFontPadding = false; text = txtTemp.text; alpha = 0f }
            direita.addView(t, 0, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(4) })
            txtTemp.addTextChangedListener(espelho { t.text = "${txtTemp.text}  ·" })
            t.text = "${txtTemp.text}  ·"
            tempoCruz = t
        }
        // A pílula "Tocando agora" costuma aparecer DEPOIS que a barra já recolheu (estava GONE, sem posição): a cada novo
        // layout dela, com a barra recolhida, ela vai para o começo da linha (visto na TV, v462231: ficava sobre a temperatura).
        capsulaTocando.addOnLayoutChangeListener { v, l, _, _, _, _, _, _, _ ->
            if (barraRecolhida && v.translationX != -l.toFloat()) { v.animate().cancel(); v.alpha = 1f; v.translationY = 0f; v.translationX = -l.toFloat() }
        }
        if (barraCruz == LayoutHome.BarraCruz.RELOGIO) relogioCruz = montarRelogioCruz().also {
            raiz.addView(it, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(Grade.MARGEM); bottomMargin = dp(40)
            })
        }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { _, novo ->
            if (novo == null || todosAbertos) return@addOnGlobalFocusChangeListener
            recolherBarra(novo === c)
        }
        recolherBarra(true, animar = false)
    }

    private fun recolherBarra(recolher: Boolean, animar: Boolean = true) {
        val topo = topoRaiz as? ViewGroup ?: return
        if (recolher == barraRecolhida && animar) return
        barraRecolhida = recolher
        val d = if (animar) 220L else 0L
        fun mexer(v: View, fica: Boolean) =
            v.animate().alpha(if (!recolher || fica) 1f else 0f).translationY(if (recolher && !fica) -dp(24).toFloat() else 0f).setDuration(d).start()
        for (i in 0 until topo.childCount) {
            val v = topo.getChildAt(i)
            // Recua: a direita (hora, e a temperatura) fica; Relógio: some tudo (a hora está grande embaixo).
            val fica = recolher && i == 2 && barraCruz == LayoutHome.BarraCruz.RECUA
            // A pílula "Tocando agora" nunca some com a barra (visto na TV 17/11/2026: estava na árvore de acessibilidade,
            // mas invisível): o grupo dela fica, e só os vizinhos (busca, clima, avisos) recolhem.
            if (v is ViewGroup && capsulaTocando.parent === v) {
                v.animate().cancel(); v.alpha = 1f; v.translationY = 0f
                for (j in 0 until v.childCount) v.getChildAt(j).let {
                    // Recolhida, a pílula vai para o começo da linha (onde ficavam busca e clima, agora apagados): no lugar
                    // de sempre ela batia na temperatura que entra ao lado da hora (visto na TV, v462204).
                    if (it === capsulaTocando) it.animate().alpha(1f).translationY(0f)
                        .translationX(if (recolher) -it.left.toFloat() else 0f).setDuration(d).start()
                    else mexer(it, fica)
                }
            } else mexer(v, fica)
        }
        tempoCruz?.animate()?.alpha(if (recolher) 1f else 0f)?.setDuration(d)?.start()
        relogioCruz?.animate()?.alpha(if (recolher) 1f else 0.35f)?.setDuration(d)?.start()
        cruz?.animate()?.translationY(if (recolher) 0f else dp(34).toFloat())?.setDuration(d)?.start()
    }

    /** Relógio grande da barra RELOGIO: hora, data por extenso e o tempo de agora (espelha a pílula do clima). */
    private fun montarRelogioCruz(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        val sombra = 0x99000000.toInt()
        addView(RelogioTela(this@MainActivity, data = false).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 72f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create(fonteUi, Typeface.NORMAL)
            letterSpacing = -0.02f
            includeFontPadding = false
            setShadowLayer(dp(10).toFloat(), 0f, 0f, sombra)
        })
        val padrao = android.text.format.DateFormat.getBestDateTimePattern(resources.configuration.locales[0], "EEEEdMMMM")
        addView(RelogioTela(this@MainActivity, data = true).apply {
            format12Hour = padrao; format24Hour = padrao
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(Color.WHITE)
            typeface = Typeface.create(fonteUi, Typeface.BOLD)
            setShadowLayer(dp(8).toFloat(), 0f, 0f, sombra)
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
        val clima = texto(18f, textoClaro, fonteUi).apply { setShadowLayer(dp(8).toFloat(), 0f, 0f, sombra); maxLines = 1 }
        fun copiar() { clima.text = listOf(txtTemp.text, txtChuva.text).filter { it.isNotBlank() && it != "…" }.joinToString(" · ") }
        txtTemp.addTextChangedListener(espelho { copiar() })
        txtChuva.addTextChangedListener(espelho { copiar() })
        copiar()
        addView(clima, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(2) })
    }

    private fun espelho(acao: () -> Unit) = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) = acao()
    }

    /**
     * Arte do item em foco (LayoutHome.arteCruz): a mesma capa à direita da tela antiga (CapaFoco, meia resolução,
     * escurecida), agora seguindo o item da cruz. TV, Séries, Filmes, Na rede e Jogos usam a arte do card (Séries e
     * Filmes: a cena do último título); um app, o banner dele; o resto, o degradê da cor do grupo.
     */
    private fun arteDoItem(item: HomeCruz.Item) {
        if (capa.desligada) return
        val id = item.id
        when {
            id == "tv" || id == "c:tv" -> mostrarCapaAtalho(IconeApp.Tipo.TV)
            id == "series" || id == "c:serie" -> mostrarCapaAtalho(IconeApp.Tipo.SERIES)
            id == "filmes" || id == "c:filme" -> mostrarCapaAtalho(IconeApp.Tipo.FILMES)
            id == "rede" -> mostrarCapaAtalho(IconeApp.Tipo.REDE)
            id == "central" -> mostrarCapaAtalho(IconeApp.Tipo.JOGOS)
            id.startsWith("app:") -> mostrarCapaDoApp(id.removePrefix("app:"), null)
            id.startsWith("c:jogo:") -> mostrarCapaDoApp(id.removePrefix("c:jogo:"), null)
            id.startsWith("c:midia:") -> mostrarCapaDoApp(id.removePrefix("c:midia:"), null)
            else -> {
                capaPedida = null
                val cor = when (cruz?.categoriaAtual()?.cat) {
                    CruzLogica.Categoria.OUVIR -> 0xFF2C6E68.toInt()
                    CruzLogica.Categoria.JOGAR -> 0xFF3A3F9E.toInt()
                    CruzLogica.Categoria.APLICATIVOS -> 0xFF3A3640.toInt()
                    else -> 0xFF8A4A2A.toInt()
                }
                capa.mostrar(null, intArrayOf(Estilo.matiz(cor, 0.6f), Estilo.matiz(cor, 0.35f)))
            }
        }
    }

    /**
     * Linha de baixo do canal de TV na cruz (Paulo, 18/11/2026: "as informações ali são ótimas, usar da melhor maneira"),
     * uma informação por linha, na ordem em que se lê: o canal; o programa de agora e quanto falta; o próximo e a hora.
     * [comPrograma] = false quando o programa já é o título do item (Continuar). Sem guia, só o canal.
     */
    private fun linhasTvCruz(u: PlayerArmazem.Ultimo, agora: Programa?, ms: Long, comPrograma: Boolean): String {
        val hora = android.text.format.DateFormat.getTimeFormat(this)
        val canal = if (u.nome.isBlank()) getString(R.string.home_dest_tv, u.numero) else getString(R.string.cruz_tv_canal, u.numero, u.nome.trim())
        val falta = agora?.let { ((it.fim - ms) / 60_000).toInt() }?.takeIf { it > 0 }?.let { getString(R.string.home_faltam, duracaoTexto(it)) }
        val linhaAgora = listOfNotNull(agora?.titulo?.trim()?.takeIf { comPrograma }, falta).joinToString(" · ")
        val depois = u.programas.firstOrNull { it.inicio >= (agora?.fim ?: ms) && it.titulo.isNotBlank() }
            ?.let { getString(R.string.cruz_tv_depois, hora.format(java.util.Date(it.inicio)), it.titulo.trim()) }
        return CruzLogica.linhasTv(canal, linhaAgora, depois)
    }

    /** Refaz a lista da cruz com os dados de agora. Barato: a HomeCruz só recria views quando os ids mudam. */
    private fun atualizarCruz() {
        val c = cruz ?: return
        if (!cruzContinuarLido) return
        val agora = System.currentTimeMillis()
        val programa = ultimoTv?.programas?.firstOrNull { it.inicio <= agora && agora < it.fim && it.titulo.isNotBlank() }
        fun faltaFilme(f: PlayerArmazem.FichaHome): String? = posicoesHome[f.chave]?.takeIf { it.duracao > 0 && Vod.deveRetomar(it) }
            ?.let { ((it.duracao - it.ms) / 60_000).toInt() }?.takeIf { it > 0 }?.let { getString(R.string.home_faltam, duracaoTexto(it)) }
        // Quanto já foi visto (barra no pé do ícone em Continuar): filme/série pela posição salva; TV, o programa no ar.
        fun visto(f: PlayerArmazem.FichaHome, serie: Boolean): Float? = posicoesHome[f.chave]
            ?.takeIf { it.duracao > 0 && (serie || Vod.deveRetomar(it)) }?.let { (it.ms.toFloat() / it.duracao).coerceIn(0f, 1f) }
        val noAr = programa?.takeIf { it.fim > it.inicio }?.let { ((agora - it.inicio).toFloat() / (it.fim - it.inicio)).coerceIn(0f, 1f) }
        // Sem internet: Séries, Filmes, Rádio e Podcasts avisam antes do OK (Na rede, TV gravada e pendrive seguem normais).
        val precisa = if (semInternetCruz) getString(R.string.cruz_precisa_internet) else null
        val cats = ArrayList<HomeCruz.Categoria>()
        fun categoria(k: CruzLogica.Categoria, nome: Int, tipo: IconeCruz.Tipo, itens: List<HomeCruz.Item>) {
            if (itens.isNotEmpty()) cats += HomeCruz.Categoria(k, getString(nome), icCruz("cat:" + k.id, tipo), itens)
        }

        // Os apps visíveis (os mesmos da grade, pilhas abertas), cada um na categoria dele (CategoriaApps: escolha manual
        // "Mover para…" > tipo que o app declara > Aplicativos). Os jogos moram em Jogar (jogosNaCruz).
        val porPacote = LinkedHashMap<String, Item>()
        for (e in lista) when (e) {
            is Entrada.App -> porPacote[e.item.pacote] = e.item
            is Entrada.Pilha -> for (it in e.itens) porPacote[it.pacote] = it
            else -> {}
        }
        val escolhasCat = CategoriaApps.escolhas(this)
        val porDestino = porPacote.keys.groupBy { CategoriaApps.destino(it, this@MainActivity.categoria(it), false, escolhasCat[it]) }
        val frequencia = frequenciaApps()
        val fixados = fixadosCruz()
        fun appsDe(k: CruzLogica.Categoria): List<HomeCruz.Item> =
            CruzLogica.ordenarApps(porDestino[k].orEmpty(), ultimoUso, frequencia, fixados).map { id -> porPacote.getValue(id).let { app ->
                HomeCruz.Item("app:$id", app.nome, linhasApp(id, app.nome), icApp(app), { abrir(id) }, opcoes = { menuAppCruz(id) })
            } }

        // Continuar (18/11/2026, pedido do dono): o canal de TV primeiro (a TV da casa); depois, do mais recente para o mais
        // antigo, a série, o filme, a última rádio, o podcast pela metade, até 3 jogos e o último de cada app de mídia
        // (YouTube, Spotify, Netflix…; MidiaRecente). "Dormir em 30 min" no fim, à noite.
        val continuar = ArrayList<HomeCruz.Item>()
        ultimoTv?.let { u ->
            continuar += HomeCruz.Item("c:tv", programa?.titulo ?: u.nome.ifBlank { getString(R.string.home_card_tv) },
                linhasTvCruz(u, programa, agora, comPrograma = false), icCruz("c:tv", IconeCruz.Tipo.TV), { abrirTv() }, progresso = noAr)
        }
        val recentes = ArrayList<Pair<Long, HomeCruz.Item>>()
        if (cruzTemVod) {
            fichaSerie?.let { f -> recentes += (posicoesHome[f.chave]?.quando ?: 0L) to HomeCruz.Item("c:serie", f.titulo,
                CruzLogica.linhas(CruzLogica.juntar(f.episodio.ifEmpty { getString(R.string.home_card_series) }, faltaSerie(f)), metaFicha(f, serie = true)),
                icCruz("c:serie", IconeCruz.Tipo.SERIES), { abrirCatalogo(SeriesActivity::class.java) }, progresso = visto(f, serie = true)) }
            fichaFilme?.let { f -> recentes += (posicoesHome[f.chave]?.quando ?: 0L) to HomeCruz.Item("c:filme", f.titulo,
                CruzLogica.linhas(CruzLogica.juntar(getString(R.string.cruz_filme), faltaFilme(f)), metaFicha(f, serie = false)),
                icCruz("c:filme", IconeCruz.Tipo.FILMES), { abrirCatalogo(FilmesActivity::class.java) }, progresso = visto(f, serie = false)) }
        }
        radioCruz?.let { (r, ouvidaEm) -> recentes += ouvidaEm to HomeCruz.Item("c:radio", r.nome,
            CruzLogica.linhas(getString(R.string.cruz_radio), quando(ouvidaEm)?.let { getString(R.string.cruz_tocou, it) }),
            icCruz("c:radio", IconeCruz.Tipo.RADIO), { tocarRadioCruz(r) }) }
        podcastCruz?.let { p -> recentes += p.em to HomeCruz.Item("c:podcast", p.titulo.ifBlank { getString(R.string.pod_titulo) },
            CruzLogica.linhas(p.podcast, faltaPodcast(p), quando(p.em)?.let { getString(R.string.cruz_tocou, it) }),
            icCruz("c:podcast", IconeCruz.Tipo.PODCASTS), { retomarPodcastCruz(p) },
            progresso = if (p.durMs > 0) (p.ms.toFloat() / p.durMs).coerceIn(0f, 1f) else null) }
        for ((pacote, item) in jogosRecentes()) recentes += (ultimoUso[pacote] ?: 0L) to HomeCruz.Item("c:jogo:$pacote", item.nome,
            CruzLogica.linhas(getString(R.string.cruz_jogo), quando(ultimoUso[pacote])?.let { getString(R.string.cruz_jogado, it) }), icApp(item), { abrir(pacote) }, opcoes = { menuAppCruz(pacote) })
        for (m in midiaCruz) {
            val nomeApp = nomeDe(m.registro.pacote)
            recentes += m.registro.quando to HomeCruz.Item("c:midia:${m.registro.pacote}", m.registro.titulo,
                CruzLogica.linhas(nomeApp, m.registro.sub, faltaMidia(m.registro), quando(m.registro.quando)?.let { getString(R.string.cruz_aberto, it) }),
                m.capa ?: instalados.firstOrNull { it.pacote == m.registro.pacote }?.let { icApp(it) } ?: icCruz("c:midia", IconeCruz.Tipo.CONTINUAR),
                { abrirMidiaCruz(m.registro.pacote) }, progresso = MidiaRecente.progresso(m.registro),
                opcoes = { menuMidiaCruz(m.registro.pacote, nomeApp) })
        }
        continuar += CruzLogica.recentes(recentes, CONTINUAR_MAX - continuar.size)
        itemDormir()?.let { continuar += it }   // onda 3: à noite, "Dormir em 30 min" no fim do Continuar
        categoria(CruzLogica.Categoria.CONTINUAR, R.string.cruz_continuar, IconeCruz.Tipo.CONTINUAR, continuar)

        // Assistir: os cards de hoje (TV, Séries, Filmes, Na rede, Addons, plugins), cada um só se o recurso existe.
        val assistir = ArrayList<HomeCruz.Item>()
        if (cruzTemTv) assistir += HomeCruz.Item("tv", getString(R.string.home_card_tv),
            ultimoTv?.let { linhasTvCruz(it, programa, agora, comPrograma = true) } ?: getString(R.string.home_destaque_tv_sub),
            icCruz("tv", IconeCruz.Tipo.TV), { abrirTv() }, progresso = noAr)
        if (cruzTemVod) {
            assistir += HomeCruz.Item("series", getString(R.string.home_card_series),
                precisa ?: fichaSerie?.let { f -> CruzLogica.linhas(f.titulo, CruzLogica.juntar(f.episodio, faltaSerie(f)), metaFicha(f, serie = true), f.genero) }
                    ?: getString(R.string.home_destaque_series),
                icCruz("series", IconeCruz.Tipo.SERIES), { abrirCatalogo(SeriesActivity::class.java) })
            assistir += HomeCruz.Item("filmes", getString(R.string.home_card_filmes),
                precisa ?: fichaFilme?.let { f -> CruzLogica.linhas(f.titulo, metaFicha(f, serie = false), f.genero, faltaFilme(f)) }
                    ?: getString(R.string.home_destaque_filmes),
                icCruz("filmes", IconeCruz.Tipo.FILMES), { abrirCatalogo(FilmesActivity::class.java) })
        }
        assistir += HomeCruz.Item("rede", getString(R.string.home_card_rede),
            CruzLogica.linhas(getString(R.string.home_destaque_rede), *pendrivesCruz().map { getString(R.string.cruz_pendrive, it) }.toTypedArray()),
            icCruz("rede", IconeCruz.Tipo.REDE), { abrirCatalogo(MidiaRedeActivity::class.java) })
        if (AddonsTv.instalados(this).isNotEmpty()) assistir += HomeCruz.Item("addons", getString(R.string.ad_titulo),
            CruzLogica.linhas(getString(R.string.ad_destaque), getString(R.string.cruz_addons_n, AddonsTv.instalados(this).size)), icCruz("addons", IconeCruz.Tipo.ADDONS), { abrirCatalogo(AddonsActivity::class.java) })
        for ((i, p) in cartoesPlugin.withIndex()) assistir += HomeCruz.Item("plugin:${p.pacote}:$i", p.titulo.ifEmpty { p.valor },
            if (p.titulo.isEmpty()) p.detalhe else listOf(p.valor, p.detalhe).filter { it.isNotEmpty() }.joinToString(" · "),
            icCruz("plugin:${p.pacote}:$i", IconeCruz.Tipo.PLUGIN), { PluginsTv.abrir(this, p) })
        assistir += appsDe(CruzLogica.Categoria.ASSISTIR)   // YouTube, Netflix… (movidos ou pelo tipo do app)
        categoria(CruzLogica.Categoria.ASSISTIR, R.string.cruz_assistir, IconeCruz.Tipo.ASSISTIR, assistir)

        // Ouvir: as rádios (catálogo mundial, sem conta) e os podcasts (plano Básico, Paulo 17/11/2026).
        categoria(CruzLogica.Categoria.OUVIR, R.string.cruz_ouvir, IconeCruz.Tipo.OUVIR, listOf(
            HomeCruz.Item("radio", getString(R.string.cruz_radio),
                CruzLogica.linhas(ouvindoCruz?.takeIf { it.first == "radio" }?.let { getString(R.string.cruz_tocando, it.second) } ?: precisa ?: getString(R.string.cruz_radio_sub),
                    radioCruz?.let { (r, q) -> CruzLogica.juntar(getString(R.string.cruz_por_ultimo, r.nome), quando(q)) }),
                icCruz("radio", IconeCruz.Tipo.RADIO), {
                try { startActivity(Intent(this, RadioActivity::class.java)) }
                catch (e: Exception) { Log.w("TvLauncher.Cruz", "rádio não abriu: ${e.javaClass.simpleName}"); cruz?.naoAbriu() }
            }),
            HomeCruz.Item("podcasts", getString(R.string.pod_titulo),
                CruzLogica.linhas(ouvindoCruz?.takeIf { it.first == "podcast" }?.let { getString(R.string.cruz_tocando, it.second) } ?: precisa ?: getString(R.string.ra_podcasts_det),
                    podcastCruz?.let { p -> CruzLogica.juntar(p.titulo, faltaPodcast(p)) },
                    assinadosCruz.takeIf { it > 0 }?.let { getString(R.string.cruz_podcasts_n, it) }),
                icCruz("podcasts", IconeCruz.Tipo.PODCASTS), {
                try { startActivity(Intent(this, PodcastsActivity::class.java)) }
                catch (e: Exception) { Log.w("TvLauncher.Cruz", "podcasts não abriram: ${e.javaClass.simpleName}"); cruz?.naoAbriu() }
            })) + appsDe(CruzLogica.Categoria.OUVIR))

        // Jogar (18/11/2026, pedido do dono: "Jogos" e "Sofá" abriam a mesma tela): a Central de jogos no topo (o painel com
        // controles, turma do Discord, rede e recentes) e cada jogo instalado, do jogado por último ao mais antigo.
        if (JogosTv.cardLigado(this)) {
            val jogos = ArrayList<HomeCruz.Item>()
            jogos += HomeCruz.Item("central", getString(R.string.cruz_central), linhasCentral(), icCruz("central", IconeCruz.Tipo.CENTRAL),
                { abrirCatalogo(JogosActivity::class.java) })
            val doJogo = jogosNaCruz().associateBy { it.pacote }
            for (p in CruzLogica.ordenarApps(doJogo.keys.sorted(), ultimoUso, emptyMap(), fixadosCruz())) doJogo.getValue(p).let { app ->
                jogos += HomeCruz.Item("app:$p", app.nome,
                    CruzLogica.linhas(subApp(p, app.nome), quando(ultimoUso[p])?.let { getString(R.string.cruz_jogado, it) }),
                    icApp(app), { abrir(p) }, opcoes = { menuAppCruz(p) })
            }
            categoria(CruzLogica.Categoria.JOGAR, R.string.cruz_jogar, IconeCruz.Tipo.JOGAR, jogos)
        }

        // Aplicativos: os 8 mais usados dos que moram aqui (nem movidos nem automáticos de Assistir/Ouvir) e "Todos os apps".
        val apps = CruzLogica.appsDaColuna(porDestino[CruzLogica.Categoria.APLICATIVOS].orEmpty(), ultimoUso, frequencia, fixados).map { id ->
            if (id == CruzLogica.ID_TODOS_APPS) HomeCruz.Item(id, getString(R.string.cruz_todos_apps),
                CruzLogica.linhas(getString(R.string.cruz_todos_apps_sub), getString(R.string.cruz_apps_n, instalados.size)),
                icCruz(id, IconeCruz.Tipo.TODOS_APPS), { abrirTodos() })
            else porPacote.getValue(id).let { app ->
                HomeCruz.Item("app:$id", app.nome, linhasApp(id, app.nome), icApp(app), { abrir(id) }, opcoes = { menuAppCruz(id) })
            }
        }
        categoria(CruzLogica.Categoria.APLICATIVOS, R.string.cruz_aplicativos, IconeCruz.Tipo.APLICATIVOS, apps)

        c.definir(cats)
        if (focoInicialNaTv && !todosAbertos) { focoInicialNaTv = false; c.requestFocus() }
        else if (currentFocus == null && !todosAbertos) c.requestFocus()
    }

    // ------------------------------------------------------------------ Continuar e Jogar da cruz (18/11/2026)

    /** Uma entrada de app de mídia no Continuar, com a capa já pronta (lida fora da tela). */
    private class MidiaCruz(val registro: MidiaRecente.Registro, val capa: Drawable?)
    @Volatile private var radioCruz: Pair<Radios.Radio, Long>? = null
    @Volatile private var podcastCruz: Podcasts.Posicao? = null
    @Volatile private var midiaCruz: List<MidiaCruz> = emptyList()
    /** O último título de cada app de mídia (para a linha de baixo do app na cruz) e quantos podcasts você segue. */
    @Volatile private var midiaPorApp: Map<String, MidiaRecente.Registro> = emptyMap()
    @Volatile private var assinadosCruz = 0

    /** Lê, na fila dos apps, o que o Continuar da cruz mostra além de TV/série/filme. */
    private fun lerContinuarCruz() {
        radioCruz = runCatching { Radios.ultima(this) }.getOrNull()
        midiaPorApp = runCatching { MidiaRecente.validos(MidiaRecente.ler(this), System.currentTimeMillis()).associateBy { it.pacote } }.getOrDefault(emptyMap())
        assinadosCruz = runCatching { Podcasts.assinaturas(this).size }.getOrDefault(0)
        podcastCruz = runCatching {
            Podcasts.continuar(Podcasts.posicoes(this), Podcasts.assinaturas(this).map { it.url }.toSet(), max = 1).firstOrNull()
        }.getOrNull()
        val pm = packageManager
        midiaCruz = runCatching {
            MidiaRecente.validos(MidiaRecente.ler(this), System.currentTimeMillis())
                .filter { r -> pm.getLeanbackLaunchIntentForPackage(r.pacote) != null || pm.getLaunchIntentForPackage(r.pacote) != null }
                .take(MIDIA_NO_CONTINUAR)
                .map { r -> MidiaCruz(r, MidiaRecente.capa(this, r.pacote)?.let { capaArredondada(it) }) }
        }.getOrDefault(emptyList())
    }

    /** A capa da música/episódio com cantos arredondados (como os ícones), num bitmap nosso. */
    private fun capaArredondada(b: android.graphics.Bitmap): Drawable {
        val out = android.graphics.Bitmap.createBitmap(b.width, b.height, android.graphics.Bitmap.Config.ARGB_8888)
        val c = android.graphics.Canvas(out)
        val raio = minOf(b.width, b.height) * 0.12f
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.shader = android.graphics.BitmapShader(b, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        c.drawRoundRect(android.graphics.RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), raio, raio, p)
        b.recycle()
        return android.graphics.drawable.BitmapDrawable(resources, out)
    }

    /** Os jogos visíveis na TV (instalados por você ou sempre mostrados), sem os ocultos e os da visita. */
    private fun jogosNaCruz(): List<Item> {
        val ocultos = Organizacao.ocultos(this)
        val daVisita = if (ModoVisita.ativo(this)) ModoVisita.apps(this) else emptySet()
        return instalados.filter { JogosTv.ehJogo(this, it.pacote) && it.pacote !in ocultos && it.pacote !in daVisita }
    }

    /** Até [JOGOS_NO_CONTINUAR] jogos já abertos, do mais recente. */
    private fun jogosRecentes(): List<Pair<String, Item>> =
        jogosNaCruz().filter { (ultimoUso[it.pacote] ?: 0L) > 0L }.sortedByDescending { ultimoUso[it.pacote] }
            .take(JOGOS_NO_CONTINUAR).map { it.pacote to it }

    // ---- linhas de informação da cruz (dono, 18/11/2026: "a regra da TV em toda a HOME"; CruzLogica.linhas)

    /** "há 2 horas", "ontem"… no idioma da TV; null sem data ou no futuro. */
    private fun quando(ms: Long?): String? {
        val agora = System.currentTimeMillis()
        if (ms == null || ms <= 0L || ms > agora + 60_000) return null
        return android.text.format.DateUtils.getRelativeTimeSpanString(ms, agora, android.text.format.DateUtils.MINUTE_IN_MILLIS).toString()
    }

    /** Ano · duração (filme) · nota, numa linha. */
    private fun metaFicha(f: PlayerArmazem.FichaHome, serie: Boolean): String? = CruzLogica.juntar(
        f.ano?.takeIf { it > 1800 }?.toString(),
        if (serie) null else f.duracaoMin?.takeIf { it > 0 }?.let { duracaoTexto(it) },
        f.nota?.takeIf { it > 0f }?.let { "★ " + String.format(Locale.getDefault(), "%.1f", it) })

    /** Quanto falta do episódio parado no meio. */
    private fun faltaSerie(f: PlayerArmazem.FichaHome): String? = posicoesHome[f.chave]?.takeIf { it.duracao > 0 && it.ms > 0 && it.ms < it.duracao }
        ?.let { ((it.duracao - it.ms) / 60_000).toInt() }?.takeIf { it > 0 }?.let { getString(R.string.home_faltam, duracaoTexto(it)) }

    private fun faltaMidia(r: MidiaRecente.Registro): String? = r.takeIf { it.durMs > 0 && it.posMs in 1 until it.durMs }
        ?.let { ((it.durMs - it.posMs) / 60_000).toInt() }?.takeIf { it > 0 }?.let { getString(R.string.home_faltam, duracaoTexto(it)) }

    /** Um app na cruz: a frase dele, o que você viu/ouviu por último nele (sessão de mídia) e quando abriu. */
    private fun linhasApp(pacote: String, nome: String): String = CruzLogica.linhas(subApp(pacote, nome),
        midiaPorApp[pacote]?.let { r -> getString(R.string.cruz_por_ultimo, CruzLogica.juntar(r.titulo, r.sub) ?: r.titulo) },
        quando(ultimoUso[pacote])?.let { getString(R.string.cruz_aberto, it) })

    /** Central de jogos: a turma do Discord e os controles ligados, cada um na sua linha. */
    private fun linhasCentral(): String {
        val controles = runCatching { JogosTv.controles().size }.getOrDefault(0)
        return CruzLogica.linhas(resumoSofa(), controles.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.jogos_controles, it, it) },
            jogosRecentes().firstOrNull()?.let { (p, it) -> CruzLogica.juntar(getString(R.string.cruz_por_ultimo, it.nome), quando(ultimoUso[p])) })
    }

    /** Pendrives/HDs ligados agora (nome do volume). */
    private fun pendrivesCruz(): List<String> = runCatching {
        (getSystemService(android.content.Context.STORAGE_SERVICE) as android.os.storage.StorageManager).storageVolumes
            .filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }.map { it.getDescription(this) }
    }.getOrDefault(emptyList()).take(2)

    private fun faltaPodcast(p: Podcasts.Posicao): String =
        if (p.durMs > p.ms) ((p.durMs - p.ms) / 60_000).toInt().takeIf { it > 0 }?.let { getString(R.string.home_faltam, duracaoTexto(it)) } ?: ""
        else ""

    private fun tocarRadioCruz(r: Radios.Radio) {
        if (semInternetCruz) { cruz?.naoAbriu(); return }
        OuvirServico.tocarRadio(this, r)   // toca e fica na HOME; a pílula "Tocando agora" aparece
    }

    /** Retoma o episódio de onde parou (o feed já está no cache); sem o feed, abre a tela de Podcasts. */
    private fun retomarPodcastCruz(p: Podcasts.Posicao) {
        Fundo.rodar("podcast-continuar") {
            val ass = runCatching { Podcasts.assinaturas(this).firstOrNull { it.url == p.feed } }.getOrNull()
            val feed = runCatching { Podcasts.feeds(this)[p.feed] }.getOrNull()
            val ep = feed?.episodios?.firstOrNull { it.guid == p.guid }
            ui.post {
                if (isDestroyed) return@post
                if (ep != null && feed != null) OuvirServico.tocarEpisodio(this, OuvirServico.EpisodioTocando(p.feed,
                    p.podcast.ifEmpty { ass?.titulo ?: feed.titulo }, feed.capa, ep), OuvirServico.RETOMAR)
                else try { startActivity(Intent(this, PodcastsActivity::class.java)) } catch (e: Exception) { cruz?.naoAbriu() }
            }
        }
    }

    /**
     * App de mídia do Continuar: com a sessão ainda aberta, música volta a tocar sem sair da HOME e vídeo abre o player
     * do app (a "atividade da sessão"); sem sessão, abre o app (a maioria retoma sozinho de onde parou).
     */
    private fun abrirMidiaCruz(pacote: String) {
        val sessao = runCatching {
            getSystemService(android.media.session.MediaSessionManager::class.java)
                ?.getActiveSessions(android.content.ComponentName(this, OuvinteNotificacoes::class.java))
                ?.firstOrNull { it.packageName == pacote }
        }.getOrNull()
        val musica = CategoriaApps.destino(pacote, categoria(pacote), false, CategoriaApps.escolhas(this)[pacote]) == CruzLogica.Categoria.OUVIR
        if (sessao != null && musica) { runCatching { sessao.transportControls.play() }.onSuccess { return } }
        val pi = sessao?.sessionActivity
        if (pi != null) {
            val ok = runCatching {
                val op = android.app.ActivityOptions.makeBasic()
                if (android.os.Build.VERSION.SDK_INT >= 34)
                    op.setPendingIntentBackgroundActivityStartMode(android.app.ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(this, 0, null, null, null, null, op.toBundle())
            }.isSuccess
            if (ok) return
        }
        abrir(pacote)
    }

    private fun menuMidiaCruz(pacote: String, nomeApp: String) {
        val opcoes = listOf(
            Opcao(getString(R.string.cruz_abrir_app, nomeApp)) { abrir(pacote) },
            Opcao(getString(R.string.cruz_tirar_continuar)) {
                Fundo.rodar("midia-esquecer") { MidiaRecente.esquecer(this, pacote); lerContinuarCruz(); ui.post { if (!isDestroyed) atualizarCruz() } }
            })
        menuAberto = Estilo.lista(this, nomeApp, opcoes.map { it.rotulo }) { i -> opcoes[i].acao() }
    }

    /** "Mover para…": Assistir, Ouvir, Jogar (com a Central ligada) ou Aplicativos; a atual vem marcada. */
    private fun moverAppCruz(pacote: String) {
        val eraJogo = JogosTv.ehJogo(this, pacote)
        val atual = CategoriaApps.destino(pacote, categoria(pacote), eraJogo && JogosTv.cardLigado(this), CategoriaApps.escolhas(this)[pacote])
        val destinos = CategoriaApps.DESTINOS.filter { it != CruzLogica.Categoria.JOGAR || JogosTv.cardLigado(this) }
        val nomes = destinos.map { k -> getString(when (k) {
            CruzLogica.Categoria.ASSISTIR -> R.string.cruz_assistir
            CruzLogica.Categoria.OUVIR -> R.string.cruz_ouvir
            CruzLogica.Categoria.JOGAR -> R.string.cruz_jogar
            else -> R.string.cruz_aplicativos
        }) + if (k == atual) getString(R.string.pp_ativo) else "" }
        menuAberto = Estilo.lista(this, getString(R.string.cruz_mover_titulo, nomeDe(pacote)), nomes) { i ->
            val k = destinos[i]
            if (k == atual) return@lista
            CategoriaApps.escolher(this, pacote, k, categoria(pacote), eraJogo)
            getSharedPreferences(PREFS_CRUZ, MODE_PRIVATE).edit().putString(k.id, "app:$pacote").apply()   // a coluna nova abre nele
            atualizarAposMudanca(null)
        }
    }

    // ------------------------------------------------------------------ onda 3 (CruzOnda3): ambiente, paralaxe, quem vê

    private var raizCruz: FrameLayout? = null
    private var ambiente = false
    private var relogioAmbiente: View? = null
    /** Tecla que tirou do modo ambiente: o "soltar" dela também é engolido (senão o OK abriria o item). */
    private var engolirTecla = -1
    private val tarefaAmbiente = Runnable { entrarAmbiente() }

    private fun reagendarAmbiente() {
        ui.removeCallbacks(tarefaAmbiente)
        if (cruz != null && homeNaFrente && CruzOnda3.ligado(this, CruzOnda3.CHAVE_AMBIENTE)) ui.postDelayed(tarefaAmbiente, CruzOnda3.AMBIENTE_MS)
    }

    private fun tocandoAgora() = ::capsulaTocando.isInitialized && capsulaTocando.visibility == View.VISIBLE && barrasTocando.tocando

    private fun entrarAmbiente() {
        val c = cruz ?: return
        if (ambiente || !homeNaFrente) return
        if (!CruzOnda3.entraAmbiente(CruzOnda3.ligado(this, CruzOnda3.CHAVE_AMBIENTE), c.hasFocus() || c.isFocused, todosAbertos,
                menuAberto?.isShowing == true, tocandoAgora())) { reagendarAmbiente(); return }
        ambiente = true
        val d = CruzOnda3.AMBIENTE_FADE_MS
        c.animate().alpha(0f).setDuration(d).start()
        topoRaiz?.animate()?.alpha(0f)?.setDuration(d)?.start()
        relogioCruz?.animate()?.alpha(0f)?.setDuration(d)?.start()
        // A hora grande no canto livre de baixo (a mesma da barra "Relógio grande"), com a data e o tempo.
        val r = relogioAmbiente ?: montarRelogioCruz().also { v ->
            v.alpha = 0f
            raizCruz?.addView(v, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                rightMargin = dp(Grade.MARGEM); bottomMargin = dp(40)
            })
            relogioAmbiente = v
        }
        r.visibility = View.VISIBLE
        r.animate().alpha(1f).setDuration(d).start()
        c.announceForAccessibility(getString(R.string.cruz_ambiente))
    }

    /** Volta exatamente onde estava (a cruz não mudou de lugar; só a transparência). */
    private fun sairAmbiente(animar: Boolean = true) {
        if (!ambiente) return
        ambiente = false
        val d = if (animar) CruzOnda3.VOLTA_MS else 0L
        cruz?.animate()?.alpha(1f)?.setDuration(d)?.start()
        topoRaiz?.animate()?.alpha(1f)?.setDuration(d)?.start()
        relogioCruz?.animate()?.alpha(if (barraRecolhida) 1f else 0.35f)?.setDuration(d)?.start()
        relogioAmbiente?.animate()?.alpha(0f)?.setDuration(d)?.withEndAction { relogioAmbiente?.visibility = View.GONE }?.start()
        reagendarAmbiente()
    }

    /** Antes de tudo no dispatchKeyEvent: no modo ambiente, a 1ª tecla só acorda a cruz (volume passa direto). */
    private fun teclaDoAmbiente(e: KeyEvent): Boolean {
        val volume = e.keyCode == KeyEvent.KEYCODE_VOLUME_UP || e.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || e.keyCode == KeyEvent.KEYCODE_VOLUME_MUTE
        if (ambiente && !volume) {
            if (e.action == KeyEvent.ACTION_DOWN) { engolirTecla = e.keyCode; sairAmbiente() }
            return true
        }
        if (e.keyCode == engolirTecla && e.action == KeyEvent.ACTION_UP) { engolirTecla = -1; return true }
        if (e.keyCode == engolirTecla) return true   // repetição da mesma tecla segurada
        if (e.action == KeyEvent.ACTION_DOWN) reagendarAmbiente()
        return false
    }

    /** Paralaxe: a arte anda [CruzOnda3.PARALAXE_DP] para o lado oposto da tecla e volta devagar. */
    private fun deslizarArte(t: CruzLogica.Tecla) {
        if (!::capa.isInitialized || capa.desligada || !CruzOnda3.ligado(this, CruzOnda3.CHAVE_PARALAXE)) return
        if (android.provider.Settings.Global.getFloat(contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f) return
        val (x, y) = CruzOnda3.paralaxe(t)
        if (x == 0 && y == 0) return
        val passo = CruzOnda3.PARALAXE_DP * resources.displayMetrics.density
        capa.animate().cancel()
        capa.translationX = x * passo; capa.translationY = y * passo
        capa.animate().translationX(0f).translationY(0f).setDuration(CruzOnda3.PARALAXE_MS)
            .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
    }

    // ---- "Quem está vendo?" ao ligar a TV
    private var vigiaTela: android.content.BroadcastReceiver? = null
    private var apagouEm = 0L
    private var acendeuEm = 0L
    private var dormiuMs = 0L
    private var perguntouNestaVez = true   // começa true: abrir o launcher não é "ligar a TV"
    private val tarefaQuemVe = Runnable { talvezPerguntarQuemVe() }

    private fun vigiarTela() {
        if (vigiaTela != null) return
        val r = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, i: Intent) {
                val agora = android.os.SystemClock.elapsedRealtime()
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> { apagouEm = agora; sairAmbiente(animar = false) }
                    Intent.ACTION_SCREEN_ON -> {
                        acendeuEm = agora
                        dormiuMs = if (apagouEm > 0) agora - apagouEm else 0L
                        perguntouNestaVez = false
                        if (homeNaFrente) { ui.removeCallbacks(tarefaQuemVe); ui.postDelayed(tarefaQuemVe, 1500) }
                    }
                }
            }
        }
        val f = android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(r, f, android.content.Context.RECEIVER_NOT_EXPORTED) else registerReceiver(r, f)
            vigiaTela = r
        }
    }

    private fun talvezPerguntarQuemVe() {
        if (perguntouNestaVez || !homeNaFrente || isDestroyed) return
        val desde = android.os.SystemClock.elapsedRealtime() - acendeuEm
        val contas = Perfil.contas(this)
        if (!CruzOnda3.perguntaQuemVe(CruzOnda3.ligado(this, CruzOnda3.CHAVE_QUEM), contas.size, dormiuMs, desde, ModoVisita.ativo(this))) {
            if (desde > CruzOnda3.QUEM_JANELA_MS) perguntouNestaVez = true
            return
        }
        if (menuAberto?.isShowing == true) return
        perguntouNestaVez = true
        val ativa = Perfil.ativa(this, contas)
        val nomes = contas.map { Perfil.nome(this, it) + if (it == ativa) getString(R.string.pp_ativo) else "" }
        menuAberto = Estilo.lista(this, getString(R.string.cruz_quem_titulo), nomes) { i ->
            val conta = contas[i]
            if (conta != ativa) PerfilCriancaTv.trocar(this, ativa, conta) { trocou ->
                if (trocou && !isDestroyed) { atualizarFotoPerfil(); reorganizar(); preencherContinuar() }
            }
        }
    }

    // ---- "Dormir em 30 min" (Continuar, à noite)

    private fun itemDormir(): HomeCruz.Item? {
        val marcado = Desligamento.marcado(this)
        val hora = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        if (!CruzOnda3.mostraDormir(CruzOnda3.ligado(this, CruzOnda3.CHAVE_DORMIR), hora, marcado != null)) return null
        val ic = icCruz("c:dormir", IconeCruz.Tipo.DORMIR)
        return if (marcado != null) HomeCruz.Item("c:dormir",
            getString(R.string.cruz_desliga_as, android.text.format.DateFormat.getTimeFormat(this).format(java.util.Date(marcado))),
            getString(R.string.cruz_dormir_cancelar), ic, { Desligamento.cancelar(this); atualizarCruz() })
        else HomeCruz.Item("c:dormir", getString(R.string.cruz_dormir), getString(R.string.cruz_dormir_sub), ic, {
            Desligamento.agendar(this, CruzOnda3.DORMIR_MIN); atualizarCruz()
        })
    }

    private fun abrirTodos() {
        val c = cruz ?: return
        todosAbertos = true
        recolherBarra(false, animar = false)   // ▲ na 1ª fileira vai à barra: ela precisa estar visível (o ouvinte sai cedo aqui)
        c.visibility = View.GONE
        rotuloApps?.visibility = View.VISIBLE
        rolagem.visibility = View.VISIBLE
        rolagem.scrollTo(0, 0)
        // Sem nenhum app na grade, o foco não pode ficar sem dono: vai à bolinha do perfil, 1º da barra.
        linhaApps.post { linhaApps.getChildAt(0)?.requestFocus() ?: bolinhaPerfil?.requestFocus() }
    }

    private fun fecharTodos() {
        val c = cruz ?: return
        if (modoMover) cancelarMover()
        todosAbertos = false
        rolagem.visibility = View.GONE
        rotuloApps?.visibility = View.GONE
        c.visibility = View.VISIBLE
        c.requestFocus()
    }

    /** Segurar OK num app da cruz: as opções de hoje que fazem sentido fora da grade (ocultar, desinstalar). */
    private fun menuAppCruz(pacote: String) {
        if (TravaHome.precisaPin(this)) { TravaHome.comPin(this) { menuAppCruz(pacote) }; return }
        val fixados = fixadosCruz()
        val fixado = pacote in fixados
        val opcoes = mutableListOf(Opcao(getString(if (fixado) R.string.cruz_soltar else R.string.cruz_fixar)) {
            guardarFixadosCruz(if (fixado) fixados - pacote else fixados + pacote)
            atualizarAposMudanca(null)
        })
        opcoes += Opcao(getString(R.string.cruz_mover)) { moverAppCruz(pacote) }
        opcoes += Opcao(getString(R.string.home_ocultar)) { Organizacao.ocultar(this, pacote); atualizarAposMudanca(null); avisarOculto() }
        if (PainelApps.podeDesinstalar(this, pacote)) opcoes += Opcao(getString(R.string.home_desinstalar)) { confirmarDesinstalar(pacote) }
        val titulo = nomeDe(pacote) + if (PainelApps.eSistema(this, pacote)) getString(R.string.home_app_sistema) else ""
        menuAberto = Estilo.lista(this, titulo, opcoes.map { it.rotulo }) { i -> opcoes[i].acao() }
    }

    /**
     * No modo mover: ◀ ▶ andam uma posição, OK confirma, Voltar cancela,
     * ▲ oculta o app (só card solto) e ▼ volta à ordem automática.
     * Fora dele: segurar OK (ver tratarSegurar) e ▲▼ numa pilha (ver trocarDaFrente).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (teclaDoAmbiente(event)) return true
        val confirma = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER || event.keyCode == KeyEvent.KEYCODE_ENTER
        if (!modoMover) {
            // Cruz: Voltar no "Todos os apps" volta para a cruz (na cruz, Voltar não faz nada).
            if (todosAbertos && event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) fecharTodos(); return true }
            if (tratarSegurar(event, currentFocus)) return true
            if (trocarDaFrente(event)) return true
            return super.dispatchKeyEvent(event)
        }
        val pressionou = event.action == KeyEvent.ACTION_DOWN
        val soltou = event.action == KeyEvent.ACTION_UP
        return when {
            event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT -> { if (pressionou) moverCartao(-1); true }
            event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT -> { if (pressionou) moverCartao(1); true }
            event.keyCode == KeyEvent.KEYCODE_DPAD_UP -> {
                if (pressionou && event.repeatCount == 0) ocultarArrastado()
                true
            }
            event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (pressionou && event.repeatCount == 0) restaurarOrdemAutomatica()
                true
            }
            confirma -> {
                if (soltou) {
                    // O primeiro "soltar" é o fim do segurar que abriu o modo: ignora.
                    if (ignorarProximoUp) ignorarProximoUp = false else confirmarMover()
                }
                true
            }
            event.keyCode == KeyEvent.KEYCODE_BACK -> { if (soltou) cancelarMover(); true }
            else -> super.dispatchKeyEvent(event)
        }
    }

    /*
     * Como o "arrastar" funciona. Os outros cards (sem o arrastado e sem o "Ocultos") ficam parados: m cards.
     * O arrastado tem 2m+1 posições possíveis, numeradas em posMover:
     *   par   (2k)   = ao lado: entra na fila antes do card k (só muda a ordem);
     *   ímpar (2k+1) = em cima do card k: pré-pilha (os dois viram um card só na tela, mas só vira pilha de verdade ao confirmar).
     * Cada ◀ ▶ anda uma posição. Então para passar por um card são 2 toques: "em cima" e depois "do outro lado".
     * Se o usuário não confirmar, o card segue andando. Voltar cancela e nada foi gravado: [lista] nunca é alterada.
     * Uma pilha arrastada só usa as posições pares (não dá para empilhar uma pilha dentro de outra).
     */

    /** Os cards que ficam parados enquanto o [arrastado] anda. */
    private fun outrosCards(): List<Entrada> = lista.filter { it !is Entrada.Ocultos && it !is Entrada.Tv && it.token != arrastado?.token }

    /** O card em cima do qual o arrastado está (pré-pilha), ou null se está "ao lado". */
    private fun alvoDeEmpilhar(): Entrada? = if (posMover % 2 == 1) outrosCards()[posMover / 2] else null

    /** Une um app arrastado a [alvo] (um app solto ou uma pilha) só para mostrar na tela. O que chegou fica na frente. */
    private fun fundir(alvo: Entrada, app: Entrada.App): Entrada.Pilha = when (alvo) {
        is Entrada.Pilha -> Entrada.Pilha(alvo.id, alvo.itens + app.item, alvo.itens.size)
        else -> Entrada.Pilha(ID_PREVIA, listOf((alvo as Entrada.App).item, app.item), 1)
    }

    /** A fila como aparece agora, com o arrastado na posição [posMover]. */
    private fun listaMovendo(): List<Entrada> {
        val movido = arrastado ?: return lista
        val fila = outrosCards().toMutableList()
        val alvo = alvoDeEmpilhar()
        if (alvo == null) fila.add(posMover / 2, movido) else fila[posMover / 2] = fundir(alvo, movido as Entrada.App)
        lista.lastOrNull { it is Entrada.Ocultos }?.let { fila.add(it) }   // "Ocultos" fica sempre no fim
        lista.firstOrNull { it is Entrada.Tv }?.let { fila.add(0, it) }    // e a "TV ao vivo", sempre no começo
        return fila
    }

    private fun iniciarMover(token: String, porSegurar: Boolean) {
        val card = lista.firstOrNull { it.token == token } ?: return
        if (card is Entrada.Ocultos || card is Entrada.Tv) return
        modoMover = true
        arrastado = card
        posMover = 2 * lista.filter { it !is Entrada.Ocultos && it !is Entrada.Tv }.indexOfFirst { it.token == token }   // começa "ao lado", onde estava
        ignorarProximoUp = porSegurar   // segurando OK, o "soltar" ainda vai chegar e não pode confirmar
        atualizarMover()
    }

    private fun moverCartao(delta: Int) {
        val passo = if (arrastado is Entrada.App) 1 else 2
        val nova = (posMover + delta * passo).coerceIn(0, 2 * outrosCards().size)
        if (nova == posMover) return
        posMover = nova
        atualizarMover()
    }

    /** Redesenha com o arrastado na posição nova e leva o foco para o card que o representa. */
    private fun atualizarMover() {
        val alvo = alvoDeEmpilhar()
        tokenMovendo = if (alvo == null) arrastado?.token else fundir(alvo, arrastado as Entrada.App).token
        ultimoToken = tokenMovendo
        mostrarDica(true)
        desenhar()
    }

    private fun confirmarMover() {
        val card = arrastado ?: return
        val alvo = alvoDeEmpilhar()
        if (alvo != null && card is Entrada.App) {
            // Congela a ordem de agora (com o arrastado onde estava) e junta: a pilha ocupa o lugar do alvo.
            Organizacao.salvarOrdem(this, lista.filter { it !is Entrada.Ocultos && it !is Entrada.Tv }.map { it.token })
            ultimoToken = if (alvo is Entrada.Pilha && alvo.id.startsWith(Organizacao.PREFIXO_AUTO)) {
                // Soltar em cima de uma pilha automática: ela vira manual, já com o app novo na frente.
                val token = Organizacao.criarPilha(this, alvo.itens.map { it.pacote } + card.token, card.token)
                Organizacao.salvarOrdem(this, Organizacao.ordem(this).filter { it != card.token }.map { if (it == alvo.token) token else it })
                token
            } else Organizacao.empilhar(this, card.token, alvo.token)
        } else {
            Organizacao.salvarOrdem(this, listaMovendo().filter { it !is Entrada.Ocultos && it !is Entrada.Tv }.map { it.token })
        }
        terminarMover()
        reorganizar()
    }

    private fun cancelarMover() {
        ultimoToken = arrastado?.token   // o foco volta para o card que estava sendo movido, no lugar de origem
        terminarMover()
        desenhar()
    }

    private fun terminarMover() {
        modoMover = false
        arrastado = null
        tokenMovendo = null
        ignorarProximoUp = false
        mostrarDica(false)
    }

    /** ▲ no modo mover: oculta o app arrastado (some da fila e vai para o card "Ocultos"). */
    private fun ocultarArrastado() {
        val card = arrastado as? Entrada.App ?: return
        terminarMover()
        Organizacao.ocultar(this, card.token)
        atualizarAposMudanca(null)
        avisarOculto()
    }

    /** O card "Ocultos" saiu da HOME (14/11/2026): ao ocultar, diz onde mostrar de volta. */
    private fun avisarOculto() = AvisoTv.rapido(this, getString(R.string.oc_aviso_titulo), getString(R.string.oc_aviso))

    private fun restaurarOrdemAutomatica() {
        terminarMover()
        Organizacao.limparOrdem(this)
        reorganizar()
    }

    /** Durante o modo de mover, aparece um guia de teclas na parte de baixo da tela. */
    private fun mostrarDica(ligada: Boolean) {
        if (!ligada) {
            txtDica.visibility = View.GONE
            return
        }
        val alvo = alvoDeEmpilhar()
        txtDica.text = when {
            alvo is Entrada.Pilha -> getString(R.string.home_mover_pilha)
            alvo != null -> getString(R.string.home_mover_forma)
            arrastado is Entrada.App -> getString(R.string.home_mover_app)
            else -> getString(R.string.home_mover_dica)
        }
        txtDica.visibility = View.VISIBLE
    }

    private fun abrir(pacote: String) {
        if (EntradasTv.eh(pacote)) { EntradasTv.abrir(this, pacote); return }   // entrada HDMI/AV (EntradasTv)
        val intent = packageManager.getLeanbackLaunchIntentForPackage(pacote)
            ?: packageManager.getLaunchIntentForPackage(pacote)
            ?: run { if (!todosAbertos) cruz?.naoAbriu(); return }   // sem tela para abrir: a cruz avisa, não falha calada
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
        contarAbertura(pacote)
    }

    // ---- Aplicativos da cruz: quantas vezes cada app foi aberto pela HOME (meia-vida de 7 dias) e os fixados no topo.
    // Fica nas preferências "cruz_apps": "f:<pacote>" = "pontos;quando" e "fixados" = pacotes separados por vírgula.

    private fun prefsApps() = getSharedPreferences(PREFS_CRUZ_APPS, MODE_PRIVATE)

    private fun contarAbertura(pacote: String) {
        val p = prefsApps()
        val agora = System.currentTimeMillis()
        val (pontos, desde) = lerFrequencia(p.getString("f:$pacote", null))
        p.edit().putString("f:$pacote", "${CruzLogica.somarAbertura(pontos, desde, agora)};$agora").apply()
    }

    private fun lerFrequencia(v: String?): Pair<Double, Long> {
        val partes = v?.split(';') ?: return 0.0 to 0L
        return (partes.getOrNull(0)?.toDoubleOrNull() ?: 0.0) to (partes.getOrNull(1)?.toLongOrNull() ?: 0L)
    }

    private fun frequenciaApps(): Map<String, Double> {
        val agora = System.currentTimeMillis()
        return prefsApps().all.mapNotNull { (k, v) ->
            if (!k.startsWith("f:")) return@mapNotNull null
            val (pontos, desde) = lerFrequencia(v as? String)
            k.substring(2) to CruzLogica.frequenciaAgora(pontos, desde, agora)
        }.toMap()
    }

    private fun fixadosCruz(): List<String> = prefsApps().getString("fixados", null)?.split(',')?.filter { it.isNotBlank() } ?: emptyList()

    private fun guardarFixadosCruz(l: List<String>) { prefsApps().edit().putString("fixados", l.joinToString(",")).apply() }

    // ---------------------------------------------------------------- clima, lua e rede

    /** Quando a última busca começou (thread da tela). O onAvailable da rede chega logo depois do onResume. */
    private var ultimaBuscaClimaMs = 0L

    private fun buscarClima() {
        // Uma busca só por volta à HOME (auditoria 10/11/2026, home-2): o onResume e o onAvailable imediato da rede
        // pediam duas buscas completas seguidas.
        val agora = System.currentTimeMillis()
        if (agora - ultimaBuscaClimaMs < 2 * 60_000L) return
        ultimaBuscaClimaMs = agora
        rede.execute {
            try {
                val r = ClimaFontes.buscar(this) ?: return@execute   // a conta toda mora em ClimaFontes.kt
                ui.post {
                    if (isDestroyed) return@post   // busca que terminou depois de um recreate(): a Activity nova busca a sua
                    pontoAtual = r.ponto
                    ultimoCodigo = r.codigo
                    ultimoChovendo = r.chovendo
                    ultimoEDia = r.eDia
                    atualizarFundo()
                    iconeClima = r.icone
                    ceuCoberto = r.codigo > 2 || r.chovendo
                    txtTemp.text = "${r.temp}°"
                    ultimaTemp = r.temp
                    // Guarda o que o topo mostra: a previsão aberta por voz ("Boletim do Tempo") e o protetor de tela
                    // (ProtetorTela.kt, sem rede própria) não passam por aqui e leem isto.
                    getSharedPreferences("clima", MODE_PRIVATE).edit()
                        .putInt("temp", r.temp).putBoolean("chovendo", r.chovendo)
                        .putInt("codigo", r.codigo).putBoolean("eDia", r.eDia)
                        .putInt("umidade", r.umidade ?: -1).putInt("chuva", r.chuvaHoje)
                        .putInt("max", r.max).putInt("min", r.min)   // máxima e mínima de hoje: a barra do protetor mostra
                        .apply { r.amanha?.let { (aMax, aMin, aCod, aChuva) ->   // previsão de amanhã: a barra do protetor
                            putInt("amanha_max", aMax).putInt("amanha_min", aMin).putInt("amanha_codigo", aCod).putInt("amanha_chuva", aChuva) } }
                        .putFloat("lat", r.ponto.lat.toFloat()).putFloat("lon", r.ponto.lon.toFloat())
                        .putInt("max", r.max).putInt("min", r.min).putString("linha", r.linha)
                        .putString("horas", r.horas)   // próximas horas: a barra do protetor
                        // Protetor que reage ao tempo (FonteTempoGuardado): UV, chuva de 15 em 15 min e ar.
                        .putString("uv", r.uv).putString("chuva15", r.chuva15).putInt("aqi", r.aqi ?: -1)
                        .putString("idioma", idiomaAtual())   // a linha é texto pronto nesse idioma
                        .putLong("em", System.currentTimeMillis()).apply()
                    txtMax.text = "↑ ${r.max}°"
                    txtMin.text = "↓ ${r.min}°"
                    txtChuva.text = r.linha
                    atualizarEclipse()   // escolhe o icone: o do clima ou, num eclipse real, o sol coberto
                }
            } catch (e: Exception) {
                // Sem internet ou API fora do ar: mantem o ultimo valor mostrado. Libera a próxima tentativa (o onAvailable
                // da rede, quando ela voltar, não pode ficar barrado pela trava de 2 min).
                ui.post { ultimaBuscaClimaMs = 0L }
            }
        }
    }


    /**
     * Clima instantâneo (auditoria 10/11/2026): ao abrir a HOME, mostra na hora o último clima salvo (até 3 h), sem esperar
     * a rede. Dia/noite é recalculado para agora. A busca de verdade vem depois, pela tarefaClima.
     */
    private fun mostrarClimaSalvo() {
        if (ultimaTemp != null) return   // a tela já mostra um clima desta Activity
        val c = getSharedPreferences("clima", MODE_PRIVATE)
        val em = c.getLong("em", 0)
        if (!c.contains("linha") || System.currentTimeMillis() - em > 3 * 3600_000L) return
        val codigo = c.getInt("codigo", -1).takeIf { it >= 0 } ?: return
        val chovendo = c.getBoolean("chovendo", false)
        val lat = c.getFloat("lat", Config.LATITUDE.toFloat()).toDouble()
        val lon = c.getFloat("lon", Config.LONGITUDE.toFloat()).toDouble()
        val eDia = Astronomia.eDia(System.currentTimeMillis(), lat, lon, c.getBoolean("eDia", true))
        val temp = c.getInt("temp", 0)
        ultimoCodigo = codigo
        ultimoChovendo = chovendo
        ultimoEDia = eDia
        ceuCoberto = codigo > 2 || chovendo
        iconeClima = ClimaFontes.iconeDoClima(codigo, eDia, chovendo, lat)
        txtTemp.text = "$temp°"
        ultimaTemp = temp
        txtMax.text = "↑ ${c.getInt("max", temp)}°"
        txtMin.text = "↓ ${c.getInt("min", temp)}°"
        // Linha salva em outro idioma (a pessoa trocou): não mostra texto no idioma errado; a busca já vem.
        txtChuva.text = if (c.getString("idioma", null) == idiomaAtual()) c.getString("linha", "") else ""
        atualizarEclipse()
    }

    /** Idioma em que as strings desta Activity saem (o do app, ou o do sistema). */
    private fun idiomaAtual(): String = resources.configuration.locales[0].toLanguageTag()

    /**
     * Dia/noite andando com o relógio, sem rede (auditoria 10/11/2026, home-3): antes só mudava numa busca de clima que deu
     * certo, e sem internet o sol ficava depois do pôr. Chamado pela tarefaEclipse.
     */
    private var ultimoEDia: Boolean? = null
    private fun recalcularDiaNoite() {
        if (ultimoCodigo < 0) return
        val c = getSharedPreferences("clima", MODE_PRIVATE)
        val lat = pontoAtual?.lat ?: c.getFloat("lat", Config.LATITUDE.toFloat()).toDouble()
        val lon = pontoAtual?.lon ?: c.getFloat("lon", Config.LONGITUDE.toFloat()).toDouble()
        val eDia = Astronomia.eDia(System.currentTimeMillis(), lat, lon, ultimoEDia ?: true)
        if (eDia == ultimoEDia) return
        ultimoEDia = eDia
        iconeClima = ClimaFontes.iconeDoClima(ultimoCodigo, eDia, ultimoChovendo, lat)
        c.edit().putBoolean("eDia", eDia).apply()   // o protetor e a previsão leem daqui
    }

    /**
     * Durante um eclipse solar REAL (tabela do Config.kt) e com o ceu visivel, o icone do clima vira o sol coberto
     * pela lua, na cobertura do minuto. Com nuvem ou chuva fica o icone do clima (o eclipse nao seria visto).
     * Fora do eclipse, volta para o ultimo icone do clima.
     */
    private fun atualizarEclipse() {
        val e = Eclipse.estado(System.currentTimeMillis())
        eclipseAgora = e
        if (e != null) Log.i("TvLauncher.Eclipse", "lua x=${e.luaX} y=${e.luaY} coberto=${"%.1f".format(e.coberto * 100)}% ceuCoberto=$ceuCoberto")
        if (e != null && !ceuCoberto) {
            iconeEclipse.definir(e.luaX, e.luaY, e.raioLua)
            mostrarIcone(ICONE_ECLIPSE)
        } else {
            iconeClima?.let { mostrarIcone(it) }
        }
    }

    /** Troca o icone do clima: o eclipse e desenhado em codigo; os outros sao emojis. */
    private fun mostrarIcone(icone: String) {
        if (icone == ICONE_ECLIPSE) {
            iconeEclipse.visibility = View.VISIBLE
            txtIcone.visibility = View.GONE
            imgIcone.visibility = View.GONE
        } else if (IconesClima.ehIcone(icone) && IconesClima.bitmap(this, icone) != null) {
            imgIcone.setImageBitmap(IconesClima.bitmap(this, icone))
            imgIcone.visibility = View.VISIBLE
            txtIcone.visibility = View.GONE
            iconeEclipse.visibility = View.GONE
        } else {
            imgIcone.visibility = View.GONE
            txtIcone.text = icone
            txtIcone.visibility = View.VISIBLE
            iconeEclipse.visibility = View.GONE
        }
    }


    // ---------------------------------------------------------------- utilidades

    private fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()

    private fun texto(sp: Float, cor: Int, fonte: Typeface? = null): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        typeface = fonte ?: fonteUi
        // Sobre o céu ou a pintura, uma sombra suave mantém o texto legível em qualquer cor de fundo.
        if (tema != Tema.ESCURO) setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x99000000.toInt())
    }

}
