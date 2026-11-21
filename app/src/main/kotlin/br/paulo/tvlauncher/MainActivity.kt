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
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.text.format.Formatter
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.alarm.Aniversarios
import br.paulo.tvlauncher.alarm.AutoDesligar
import br.paulo.tvlauncher.alarm.Despertador
import br.paulo.tvlauncher.backup.BackupAutomatico
import br.paulo.tvlauncher.backup.MudarDeTv
import br.paulo.tvlauncher.backup.StorageMode
import br.paulo.tvlauncher.family.ModoVisita
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.family.Uso
import br.paulo.tvlauncher.games.Jogos
import br.paulo.tvlauncher.games.JogosTv
import br.paulo.tvlauncher.games.TurmaDiscordTv
import br.paulo.tvlauncher.home.AllAppsScreen
import br.paulo.tvlauncher.home.AppShelf
import br.paulo.tvlauncher.home.AppStorage
import br.paulo.tvlauncher.home.BarraIlha
import br.paulo.tvlauncher.home.CapaFoco
import br.paulo.tvlauncher.home.CategoriaApps
import br.paulo.tvlauncher.home.CruzLogica
import br.paulo.tvlauncher.home.CustomIcons
import br.paulo.tvlauncher.home.DesenhoHome
import br.paulo.tvlauncher.home.EstadoCeu
import br.paulo.tvlauncher.home.FrasesApps
import br.paulo.tvlauncher.home.FundoMeuDados
import br.paulo.tvlauncher.home.FundoVivo
import br.paulo.tvlauncher.home.Grade
import br.paulo.tvlauncher.home.HomeCardsTv
import br.paulo.tvlauncher.home.HomeCruz
import br.paulo.tvlauncher.home.IconeApp
import br.paulo.tvlauncher.home.IconeCruz
import br.paulo.tvlauncher.home.IconeLinha
import br.paulo.tvlauncher.home.LayoutHome
import br.paulo.tvlauncher.home.MidiaRecente
import br.paulo.tvlauncher.home.Organizacao
import br.paulo.tvlauncher.home.PacoteIcones
import br.paulo.tvlauncher.home.SonsCruz
import br.paulo.tvlauncher.home.SplashOverlay
import br.paulo.tvlauncher.home.SplashRules
import br.paulo.tvlauncher.home.abrir
import br.paulo.tvlauncher.home.abrirCongelado
import br.paulo.tvlauncher.home.aplicarIlha
import br.paulo.tvlauncher.home.arteDoItem
import br.paulo.tvlauncher.home.atualizarCruz
import br.paulo.tvlauncher.home.atualizarIlha
import br.paulo.tvlauncher.home.bannerTodos
import br.paulo.tvlauncher.home.botaoDoEvento
import br.paulo.tvlauncher.home.crescerIlhaTexto
import br.paulo.tvlauncher.home.criarFundo
import br.paulo.tvlauncher.home.deslizarArte
import br.paulo.tvlauncher.home.entrarAmbiente
import br.paulo.tvlauncher.home.espelho
import br.paulo.tvlauncher.home.fecharTodos
import br.paulo.tvlauncher.home.infoTodos
import br.paulo.tvlauncher.home.lerFontesIlha
import br.paulo.tvlauncher.home.liberarEscondidos
import br.paulo.tvlauncher.home.ligarMovimento
import br.paulo.tvlauncher.home.menuTodos
import br.paulo.tvlauncher.home.montarIlha
import br.paulo.tvlauncher.home.prateleirasManuais
import br.paulo.tvlauncher.home.prefsApps
import br.paulo.tvlauncher.home.reagendarAmbiente
import br.paulo.tvlauncher.home.recongelarPendentes
import br.paulo.tvlauncher.home.sairAmbiente
import br.paulo.tvlauncher.home.talvezPerguntarQuemVe
import br.paulo.tvlauncher.home.teclaDoAmbiente
import br.paulo.tvlauncher.home.vigiarTela
import br.paulo.tvlauncher.listen.BarrasSom
import br.paulo.tvlauncher.listen.OuvirEstado
import br.paulo.tvlauncher.listen.Podcasts
import br.paulo.tvlauncher.listen.Radios
import br.paulo.tvlauncher.network.AcordarComLg
import br.paulo.tvlauncher.network.CasaHA
import br.paulo.tvlauncher.network.Dns
import br.paulo.tvlauncher.network.HistoricoRede
import br.paulo.tvlauncher.network.SemInternet
import br.paulo.tvlauncher.observer.DesempenhoTv
import br.paulo.tvlauncher.observer.LevezaTv
import br.paulo.tvlauncher.observer.MedidasTv
import br.paulo.tvlauncher.observer.Placar
import br.paulo.tvlauncher.panel.PainelPermissoes
import br.paulo.tvlauncher.panel.PainelWeb
import br.paulo.tvlauncher.player.BarraTempo
import br.paulo.tvlauncher.player.ContaIptv
import br.paulo.tvlauncher.player.GravacaoDestino
import br.paulo.tvlauncher.player.MidiaArmazem
import br.paulo.tvlauncher.player.OnDemandTv
import br.paulo.tvlauncher.player.PlayerArmazem
import br.paulo.tvlauncher.player.PlayerCarga
import br.paulo.tvlauncher.player.PlayerFiltro
import br.paulo.tvlauncher.player.PlayerLogos
import br.paulo.tvlauncher.player.Posicao
import br.paulo.tvlauncher.player.item
import br.paulo.tvlauncher.privilege.GuardiaoShizuku
import br.paulo.tvlauncher.privilege.Privilegio
import br.paulo.tvlauncher.privilege.PrivilegioDO
import br.paulo.tvlauncher.privilege.ShellProprio
import br.paulo.tvlauncher.screensaver.PluginCartao
import br.paulo.tvlauncher.screensaver.RelogioTela
import br.paulo.tvlauncher.screensaver.WallpaperPluginContract
import br.paulo.tvlauncher.screensaver.WallpaperPlugins
import br.paulo.tvlauncher.settings.AltoContraste
import br.paulo.tvlauncher.settings.Extras
import br.paulo.tvlauncher.settings.Fontes
import br.paulo.tvlauncher.settings.Planos
import br.paulo.tvlauncher.settings.TamanhoTexto
import br.paulo.tvlauncher.settings.Tema
import br.paulo.tvlauncher.weather.Astronomia
import br.paulo.tvlauncher.weather.ClimaFontes
import br.paulo.tvlauncher.weather.Eclipse
import br.paulo.tvlauncher.weather.IconeEclipse
import br.paulo.tvlauncher.weather.IconesClima
import br.paulo.tvlauncher.weather.LocalClima
import br.paulo.tvlauncher.weather.Localizacao
import java.util.Locale
import java.util.concurrent.Executors

private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

/** Partes da HOME que o adb pode esconder para medir a GPU (ver HOME_TESTE no ControleShizukuReceiver). Volta tudo ao reiniciar. */
object TesteHome {
    @Volatile var capa = true
    @Volatile var fundo = true
    @Volatile var capaAnima = true    // false: a capa troca sem o esmaecer de 260 ms
    @Volatile var capaCamada = true   // false: a capa sem camada de hardware
    @Volatile var aplicar: (() -> Unit)? = null
}
private const val ICONE_ECLIPSE = "eclipse"  // no lugar de um emoji: sol coberto pela lua (IconeEclipse). So aparece durante um eclipse solar REAL (ver Eclipse.kt e o Config.kt)

/**
 * A tela inicial inteira. A interface é montada por código (sem arquivos XML de layout):
 * menos arquivos, e fica fácil de ler de cima para baixo.
 */
class MainActivity : Activity() {

    /** Um app instalado: pacote (identificador), nome e o ícone redondo. */
    internal class Item(val pacote: String, val nome: String, val icone: Drawable)

    internal val ui = Handler(Looper.getMainLooper())          // roda código na thread da tela
    private val rede = Executors.newSingleThreadExecutor()    // roda chamadas de internet fora da thread da tela

    // Paleta: tons frios e quase neutros, para a tela não competir com os banners dos apps.
    internal val textoClaro = 0xFFE8EEEA.toInt()
    // O visual escolhido no menu do perfil (Tema.kt). Lido no onCreate; trocar de tema recria a tela.
    private var tema = Tema.ESCURO
    private var desviadaParaSegura = false   // modo de segurança: o onCreate parou cedo (nada foi montado)
    private var assinaturaFundo = ""   // "Meu fundo": arquivo|brilho|contraste quando a tela foi montada
    private var idiomaMontado = ""     // idioma em que a tela foi montada (locale está em configChanges: não recria sozinha)
    private var assinaturaLayout = ""  // ajustes da cruz, alto contraste e letra quando a tela foi montada (assinaturaDoLayout)
    internal val fonteUi by lazy { Fontes.ui(this, tema) }

    internal var fundo: View? = null                 // o fundo vivo do tema (céu, pintura, mostrador)
    @Volatile private var pontoAtual: Localizacao.Ponto? = null
    private var ultimoCodigo = -1
    private var ultimoChovendo = false

    private lateinit var txtIcone: TextView   // so para o texto de reserva
    private lateinit var imgIcone: ImageView   // o icone do clima (imagem de assets/clima/)
    internal lateinit var txtTemp: TextView
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
    private lateinit var fotoPerfil: ImageView        // a bolinha do perfil, no canto superior direito
    internal var bolinhaPerfil: View? = null            // o botão em volta da foto (o que recebe o foco)
    // Abriu o popup do perfil: ao voltar de uma tela aberta por ele (Configurações, Wi-Fi...), o foco volta para a bolinha,
    // e não para o 1º app (visto na Sala em 07/11/2026). Apaga quando outra coisa da HOME ganha o foco.
    private var voltarAoPerfil = false
    // Passada na TV (12/11/2026): voltar da previsão (pílula do clima) ou do calendário (data) jogava o foco no 1º app ou no
    // último. Quem abriu uma tela pelo topo recebe o foco de volta, como a bolinha do perfil já fazia.
    internal var topoRaiz: View? = null
    private var focoTopo: View? = null
    // HOME recomendada (06/11/2026): capa de quem está em foco e a linha "Continuar" embaixo dos apps.
    internal val capaPronta get() = ::capa.isInitialized
    internal lateinit var capa: CapaFoco
    private lateinit var conteudo: FrameLayout          // tudo menos o fundo e a capa: sobe quando o foco vai para o Continuar
    // Banner (ou cor) por pacote, lido uma vez e FORA da thread da tela (06/11: ler na hora do foco engasgava a HOME).
    private val capas = java.util.concurrent.ConcurrentHashMap<String, Pair<Bitmap?, IntArray>>()
    // Arte do último filme e da última série no fundo (12/11/2026): endereço -> imagem, para não baixar/decodificar de novo
    // a cada volta à HOME. E a ficha curta deles, para o destaque (lidas em preencherContinuar, fora da tela).
    private val arteAtalho = java.util.concurrent.ConcurrentHashMap<IconeApp.Tipo, Pair<String, Bitmap>>()
    @Volatile internal var fichaFilme: PlayerArmazem.FichaHome? = null
    @Volatile internal var fichaSerie: PlayerArmazem.FichaHome? = null
    @Volatile internal var posicoesHome: Map<String, Posicao> = emptyMap()
    internal var capaPedida: String? = null   // o pacote cuja capa a tela espera (se o foco andar, a que chegar atrasada não aparece)
    internal var instalados: List<Item> = emptyList()          // todos os apps de TV instalados (inclui os ocultos)
    internal var visiveis: List<Item> = emptyList()            // os que a cruz mostra, em ordem (appsVisiveis)
    private var iconeClima: String? = null        // icone que o CLIMA mandou na ultima busca (o eclipse so o substitui enquanto dura)
    private var ceuCoberto = false                // nuvens ou chuva (codigo > 2): o eclipse nao seria visto, entao fica o icone do clima
    private var eclipseAgora: Eclipse.Estado? = null   // null = nao ha eclipse neste minuto

    // Tarefas que se repetem enquanto a tela está visível (e param em onPause, para não gastar rede à toa).
    private val tarefaClima = object : Runnable {
        override fun run() {
            // Clima instantâneo (auditoria 10/11/2026): o salvo já está na tela (mostrarClimaSalvo, no onResume). Só busca
            // se ele tem mais de CLIMA_A_CADA_MIN; senão espera o que falta. Antes buscava a cada volta à HOME.
            val ciclo = Config.CLIMA_A_CADA_MIN * 60_000L
            val prefs = getSharedPreferences(Prefs.CLIMA, MODE_PRIVATE)
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
                val em = getSharedPreferences(Prefs.CLIMA, MODE_PRIVATE).getLong("em", 0)
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
    internal lateinit var pilulaSemInternet: LinearLayout
    internal lateinit var txtSemInternet: TextView
    private var semInternetDesde = 0L
    /** Sem internet confirmada (a pílula está à vista): na cruz, o que depende dela diz "Precisa de internet". */
    internal var semInternetCruz = false
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

    /** Uma pílula do topo vai sumir com o foco nela: o foco vai para a cruz (não pode ficar num texto invisível). */
    private fun focoPadrao() { cruz?.requestFocus() }

    private fun avaliarSemInternet() {
        ui.removeCallbacks(tarefaSemInternet)
        if (isDestroyed || !::pilulaSemInternet.isInitialized) return
        val (internet, local) = estadoInternet()
        if (internet) {
            semInternetDesde = 0L
            if (semInternetCruz) { semInternetCruz = false; atualizarCruz() }
            if (pilulaSemInternet.visibility != View.GONE) {
                if (pilulaSemInternet.hasFocus()) focoPadrao()
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
        // Elevação 2.11: só a organização (ordem, pilhas, ocultos); o resto que a HOME grava é estado da tela.
        VigiaDesfazer.ligar(this, getString(R.string.desf_rot_organizar), setOf(DesfazerRegras.Categoria.ORGANIZACAO))
        DesempenhoTv.criou()   // guarda de desempenho (12/11/2026): o 1º quadro depois disto dá o tempo de abertura
        tema = Tema.atual(this)
        assinaturaFundo = FundoMeuDados.assinatura(this) + "|" + WallpaperPlugins.escolhidoId(this)
        idiomaMontado = idiomaAtual()
        assinaturaLayout = assinaturaDoLayout()
        setContentView(montarTela())
        // Logo Sofaly ao ligar (18/11/2026): no boot, uma vez por processo, por cima da HOME já montada (SplashRules).
        if (!splashBootDecidido) {
            splashBootDecidido = true
            if (SplashRules.onBoot(SplashOverlay.enabled(this), android.os.SystemClock.elapsedRealtime())) SplashOverlay.show(this)
        }
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

    /** Quando a HOME saiu da frente (para a volta decidir onde pôr o foco). */
    private var pausadaEm = 0L

    /** O que, mudando em Configurações, faz a HOME se refazer ao voltar (cartões, cruz, alto contraste 16.3, letra). */
    private fun assinaturaDoLayout() = "${LayoutHome.iconesCruz(this)}|${LayoutHome.barraCruz(this)}|${LayoutHome.arteCruz(this)}|" +
        "${AltoContraste.ligado(this)}|${TamanhoTexto.atual(this)}|${PacoteIcones.versao(this)}|${LayoutHome.iconesTrocados(this)}"

    override fun onResume() {
        super.onResume()
        homeNaFrente = true
        recongelarPendentes()   // "congelar de novo ao voltar" (Todos os apps, 20/11/2026)
        // Pen drive com apps foi tirado (AppStorage): diz quais sumiram até ele voltar.
        AppStorage.ausentes(this).takeIf { it.isNotEmpty() }?.let { f ->
            mostrarAviso(getString(R.string.st_sumiram, f.joinToString(", ")))
        }
        // Memória quase cheia e pen drive como memória da TV: a Sofy sugere o maior app para mover (1x por dia).
        Fundo.rodar("StorageAdvice") {
            val s = runCatching { StorageMode.sugestao(applicationContext) }.getOrNull() ?: return@rodar
            runOnUiThread {
                if (!isDestroyed) mostrarAviso(getString(R.string.st_sugestao, s.first,
                    android.text.format.Formatter.formatShortFileSize(this, s.second)))
            }
        }
        SonsCruz.naFrente = true
        if (ilhaPilula != null) {
            ui.removeCallbacks(tarefaIlhaTique); ui.post(tarefaIlhaTique)
            CasaHA.aoTocarCampainha = { ui.post { atualizarIlha() } }   // a campainha entra na Ilha na hora
            ui.removeCallbacks(tarefaIlhaGira); if (ilhaFila.size > 1) ui.postDelayed(tarefaIlhaGira, BarraIlha.RODIZIO_MS)
        }
        // A tela acendeu com a HOME ainda pausada (o Android retoma a HOME logo depois do SCREEN_ON): a logo entra agora.
        if (splashPendenteAte > 0L) {
            if (android.os.SystemClock.elapsedRealtime() <= splashPendenteAte) SplashOverlay.show(this)
            splashPendenteAte = 0L
        }
        if (desviadaParaSegura) return
        ModoSeguro.aoRetomarHome(this)   // 30 s de pé = a HOME firmou (zera a conta de quedas seguidas)
        ModoHospede.talvezBoasVindas(this)   // modo hóspede: boas-vindas uma vez por estadia
        ModoHospede.conferirSaida(this)      // e a saída que passou com a TV fora da tomada
        MudarDeTv.abrirFilaSePendente(this)  // elevação 19.7: veio tudo de outra TV; a fila dos apps abre uma vez
        // Agenda da família (degrau 2): a troca de perfil que passou com a TV desligada (o alarme não veio).
        Fundo.rodar("agenda-perfis") {
            if (AgendaPerfisTv.conferir(applicationContext)) runOnUiThread { if (!isDestroyed) { atualizarFotoPerfil(); reorganizar() } }
        }
        // O tema agora só troca na tela separada de Configurações do launcher (ConfigLauncherActivity), que não
        // tem como chamar recreate() nesta Activity diretamente — então aqui a gente mesmo percebe a troca ao voltar.
        if (Tema.atual(this) != tema) { recreate(); return }
        // Trocou o idioma (do sistema ou só do launcher): textos já montados (data, destaque) ficavam no antigo.
        if (idiomaAtual() != idiomaMontado) { recreate(); return }
        if ((tema == Tema.MEU || tema == Tema.PLUGIN) && FundoMeuDados.assinatura(this) + "|" + WallpaperPlugins.escolhidoId(this) != assinaturaFundo) { recreate(); return }   // outro arquivo/brilho/plugin
        if (assinaturaDoLayout() != assinaturaLayout) { recreate(); return }   // cartões, cruz, alto contraste (16.3) ou letra mudaram
        // Modo simples (12/11/2026, ideia 10; desligado por padrão): a HOME vira a tela de cards grandes.
        if (ModoSimples.ligado(this)) {
            try { startActivity(Intent(this, ModoSimplesActivity::class.java)); return } catch (_: Exception) {}
        }
        DesempenhoTv.ligar(this)   // conta o tempo de cada quadro da HOME, numa thread própria (DesempenhoTv)
        ligarMovimento()
        preencherApps()
        preencherContinuar()
        reagendarAmbiente()
        talvezPerguntarQuemVe()
        // Foco na barra de cima ao sair: volta nela só se foi um passeio curto (Configurações, perfil). Depois de muito tempo
        // fora (descanso de tela, um filme inteiro) quem volta quer o conteúdo: a cruz (Sala, 18/11/2026: acordou do
        // descanso com o foco na bolinha do perfil, e a 1ª seta andava na barra).
        if (focoTopo != null && pausadaEm > 0L && android.os.SystemClock.elapsedRealtime() - pausadaEm > FOCO_TOPO_VALE_MS) {
            focoTopo = null
            cruz?.requestFocus()
        }
        if (voltarAoPerfil) bolinhaPerfil?.requestFocus()
        else if (focoTopo?.isShown == true) focoTopo?.requestFocus()
        mostrarClimaSalvo()   // na hora, antes de qualquer rede
        ui.post(tarefaClima)
        ui.post(tarefaEclipse)
        ui.post(tarefaTimer)
        vigiaTocando.ligar()   // ideia 75: pílula Tocando agora, por aviso das sessões de mídia
        atualizarPilulaAlarme()   // o alarme pode ter mudado na tela do despertador
        atualizarPilulaVencimento()
        atualizarPilulaJogo()
        atualizarPilulaAniversario()
        atualizarPilulasCartoes(buscar = true)
        getSharedPreferences(Prefs.LAUNCHER, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(ouvinteLocal)
        getSharedPreferences(Prefs.DESPERTADOR, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(ouvinteAlarmes)
        getSharedPreferences(Prefs.TIMER, MODE_PRIVATE).registerOnSharedPreferenceChangeListener(ouvinteTimer)
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
            if (fichasHome == null || catsFicha == null) {   // frase do destaque dos apps (ideia 13): fora da tela
                val json = runCatching { ComunidadeApps.fichasJuntas(this) }.getOrNull()   // ideia 70: APK + comunidade
                fichasHome = runCatching { FrasesApps.ler(json ?: "{}") }.getOrDefault(emptyMap())
                catsFicha = AppShelf.sheetCategories(json)   // AppShelf (20/11/2026): categoria da Play pela ficha
            }
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
        ui.removeCallbacks(tarefaIlhaTique); ui.removeCallbacks(tarefaIlhaGira)
        CasaHA.aoTocarCampainha = null
        ModoSeguro.aoPausarHome()
        ui.removeCallbacks(tarefaAmbiente); ui.removeCallbacks(tarefaQuemVe)
        sairAmbiente(animar = false)
        if (desviadaParaSegura) return
        focoTopo = currentFocus?.takeIf { f -> generateSequence(f.parent) { it.parent }.any { it === topoRaiz } }
        pausadaEm = android.os.SystemClock.elapsedRealtime()
        DesempenhoTv.aoPesado = null
        DesempenhoTv.desligar(this)
        if (::capa.isInitialized) capa.parar()   // zoom da cena: não fica pedindo quadro com a HOME por trás de um app
        ui.removeCallbacks(tarefaClima)
        ui.removeCallbacks(tarefaEclipse)
        ui.removeCallbacks(tarefaTimer)
        ui.removeCallbacks(tarefaTocando)
        vigiaTocando.desligar()
        getSharedPreferences(Prefs.LAUNCHER, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(ouvinteLocal)
        getSharedPreferences(Prefs.DESPERTADOR, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(ouvinteAlarmes)
        getSharedPreferences(Prefs.TIMER, MODE_PRIVATE).unregisterOnSharedPreferenceChangeListener(ouvinteTimer)
        pararVigiaRede()
        ui.removeCallbacks(tarefaSemInternet)
        menuAberto?.dismiss()
    }

    /** HOME escondida e memória apertada: solta as capas dos apps (são refeitas na volta, fora da thread da tela). */
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (Memoria.soltarCapasDaHome(level, hasWindowFocus())) { capas.clear(); arteAtalho.clear() }
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
        // Laterais em Grade.MARGEM; a barra de cima em TOPO_PALCO e a cruz (montarCruz) no resto da tela.
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
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, Estilo.Leitura.DICA)
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
        txtMax = texto(Estilo.Leitura.DICA, textoClaro).apply { visibility = View.GONE }
        txtMin = texto(Estilo.Leitura.DICA, textoClaro).apply { visibility = View.GONE }
        txtChuva = texto(Estilo.Leitura.DICA, textoClaro).apply {
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            maxWidth = dp(320)   // 200 cortava "Poucas nuvens - Chuva 51% em 3 h" (Sala, 07/11); encolhe se faltar espaço (abaixo)
        }
        linhaChuva = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(texto(Estilo.Leitura.DICA, textoClaro).apply { text = " · "; includeFontPadding = false })
            addView(txtChuva)
            // Sem texto de chuva, o " · " ficava solto ("… ·" na pílula do clima; crítica de design 19/11/2026).
            visibility = View.GONE
        }
        txtChuva.addTextChangedListener(espelho { linhaChuva.visibility = if (txtChuva.text.isNullOrBlank()) View.GONE else View.VISIBLE })
        val blocoClima = pilula(vidro = false).apply {
            addView(caixaIcone, LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(6) })
            addView(txtTemp)
            addView(linhaChuva)
            setOnClickListener { abrirPrevisao() }
        }

        // Pílula do despertador: o próximo toque ("⏰ amanhã 6:30"). Some se não houver alarme (ver atualizarPilulaAlarme).
        txtAlarme = texto(Estilo.Leitura.DICA, textoClaro).apply { includeFontPadding = false }
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
            setPaddingRelative(dp(8), 0, dp(8), 0)
        }
        fun lpPilula() = LinearLayout.LayoutParams(WRAP, alturaPilula).apply { marginEnd = dp(2) }
        // Pílula só de ícone (lupa, microfone): redonda, sem o espaço que a pílula deixa para um texto depois do ícone.
        fun pilulaIcone(icone: android.graphics.drawable.Drawable, descricao: String, acao: () -> Unit) = pilula(icone, vidro = false).apply {
            (getChildAt(0).layoutParams as LinearLayout.LayoutParams).marginEnd = 0
            setPaddingRelative(dp(8), 0, dp(8), 0)
            contentDescription = descricao   // o MCP e a acessibilidade acham o botão pelo nome
            setOnClickListener { acao() }
        }
        val lupa = pilulaIcone(IconeApp(IconeApp.Tipo.BUSCA), getString(R.string.home_busca)) { abrirBusca() }
        val voz = pilulaIcone(IconeApp(IconeApp.Tipo.MICROFONE), getString(R.string.home_falar)) { abrirBuscaPorVoz() }

        val bolinha = botaoPerfil().also { bolinhaPerfil = it }
        topo.addView(bolinha, FrameLayout.LayoutParams(dp(ALTURA_TOPO - 4), dp(ALTURA_TOPO - 4), Gravity.CENTER_VERTICAL or Gravity.START).apply {
            marginStart = dp(Grade.MARGEM)
        })
        // Duas cápsulas (13/11/2026, pedido do dono): uma só com a busca e a voz, outra só com o clima. Juntas numa cápsula
        // só, a busca parecia parte do clima.
        val busca = capsula().apply {
            addView(lupa, LinearLayout.LayoutParams(alturaPilula, alturaPilula).apply { marginEnd = dp(2) })
            addView(voz, LinearLayout.LayoutParams(alturaPilula, alturaPilula))
        }
        // Cotação do dia (HomeCards, 18/11/2026): na mesma cápsula do clima, depois dele. OK mostra todas as escolhidas.
        txtCotacao = texto(Estilo.Leitura.DICA, Estilo.TEXTO, fonteUi).apply { includeFontPadding = false; maxLines = 1 }
        pilulaCotacao = pilula(vidro = false).apply {
            visibility = View.GONE
            addView(txtCotacao)
            setOnClickListener {
                Fundo.rodar { val t = HomeCardsTv.textoCotacoesCompleto(this@MainActivity); ui.post { AvisoTv.rapido(this@MainActivity, getString(R.string.hc_cotacao_rotulo), t, 10) } }
            }
        }
        val capsulaClima = capsula().apply {
            addView(blocoClima, LinearLayout.LayoutParams(WRAP, alturaPilula))
            addView(pilulaCotacao, LinearLayout.LayoutParams(WRAP, alturaPilula))
        }
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
        val corSub = 0xB3FFF3E6.toInt()
        txtTocandoTitulo = texto(Estilo.Leitura.DICA, Estilo.TEXTO, Typeface.create(fonteUi, Typeface.BOLD)).apply {
            includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(220)
        }
        txtTocandoSub = texto(Estilo.Leitura.DICA, corSub).apply {
            includeFontPadding = false; maxLines = 1; ellipsize = TextUtils.TruncateAt.END; maxWidth = dp(130)
        }
        pilulaTocando = pilula(vidro = false).apply {
            setPaddingRelative(dp(6), 0, dp(14), 0)   // a capa encosta mais na borda que um ícone
            addView(capaTocando, LinearLayout.LayoutParams(dp(22), dp(22)).apply { marginEnd = dp(8) })
            addView(barrasTocando, LinearLayout.LayoutParams(dp(11), dp(12)).apply { marginEnd = dp(8) })
            addView(txtTocandoTitulo)
            addView(texto(Estilo.Leitura.DICA, corSub).apply { text = " · "; includeFontPadding = false })
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
            marginStart = dp(Grade.MARGEM + ALTURA_TOPO - 4 + 12)
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
        topo.addView(direita, FrameLayout.LayoutParams(WRAP, dp(ALTURA_TOPO), Gravity.END).apply { marginEnd = dp(Grade.MARGEM - 14) })
        // O texto do clima usa só o espaço que sobra até a hora (com despertador e timer na linha ele encolhe e ganha "…";
        // sem eles, cabe inteiro). Esquerda e direita são independentes no FrameLayout e podiam se sobrepor.
        esquerda.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ ->
            if (direita.width > 0) {
                val resto = v.width - txtChuva.width                       // tudo da esquerda menos o texto do clima
                val livre = direita.left - dp(24) - v.left - resto
                val alvo = livre.coerceIn(dp(80), dp(320))
                if (txtChuva.maxWidth != alvo) txtChuva.post { txtChuva.maxWidth = alvo }
                // Cartões da tela inicial (19/11/2026, visto na Sala: "Feriado em 1 dia" passava por baixo da hora). Se nem
                // com o clima no mínimo cabe, a barra deixa de mostrar um cartão por vez: primeiro os que a Ilha também
                // mostra (contagem, lembrete), por último a cotação, que só existe aqui (visto na Sala 19/11: com
                // despertador + feriado a cotação sumia). Cortado só daqui: a Ilha continua vendo o texto.
                if (livre < dp(80)) listOf(pilulaContagem, pilulaLembreteCasa, pilulaCotacao)
                    .firstOrNull { it.visibility == View.VISIBLE }
                    ?.let { p -> p.post { if (p.hasFocus()) focoPadrao(); cortadas += p; p.visibility = View.GONE } }
            }
        }

        // Timer (05/11/2026): mais uma pílula na mesma linha, depois do despertador. OK nela cancela. Some sem timer
        // (ver tarefaTimer).
        txtTimer = texto(Estilo.Leitura.DICA, Estilo.TEXTO, fonteUi).apply { includeFontPadding = false }
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
        txtVencimento = texto(Estilo.Leitura.DICA, Estilo.ATENCAO, fonteUi).apply { includeFontPadding = false }
        pilulaVencimento = pilula(vidro = false).apply {
            visibility = View.GONE
            addView(txtVencimento)
            setOnClickListener { startActivity(Intent(this@MainActivity, ConfigLauncherActivity::class.java).putExtra(ConfigLauncherActivity.EXTRA_ABA, ConfigLauncherActivity.ABA_CONTA)) }
        }
        avisos.addView(pilulaVencimento, lpPilula())
        // Ideia 12: o próximo jogo do "Meu time" (24 h) na mesma cápsula, sem fileira nova. OK abre a busca ("Hoje na TV").
        txtJogo = texto(Estilo.Leitura.DICA, Estilo.TEXTO, fonteUi).apply { includeFontPadding = false }
        pilulaJogo = pilula(IconeApp(IconeApp.Tipo.TV), vidro = false).apply {
            visibility = View.GONE
            addView(txtJogo)
            setOnClickListener { abrirBusca() }
        }
        avisos.addView(pilulaJogo, lpPilula())
        // Ideia 93: "🎂 Hoje é aniversário de tia Rosa · 80 anos" (véspera e dia, lista do painel). OK mostra todos num aviso.
        txtAniversario = texto(Estilo.Leitura.DICA, Estilo.TEXTO, fonteUi).apply { includeFontPadding = false }
        pilulaAniversario = pilula(vidro = false).apply {
            visibility = View.GONE
            addView(txtAniversario)
            setOnClickListener {
                val todos = Aniversarios.textos(this@MainActivity)
                if (todos.isNotEmpty()) AvisoTv.rapido(this@MainActivity, getString(R.string.an_rotulo), todos.joinToString("\n"), 8)
            }
        }
        avisos.addView(pilulaAniversario, lpPilula())
        // Cartões próprios (HomeCards, 18/11/2026): lembrete da casa no dia e contagem regressiva na última semana.
        txtLembreteCasa = texto(Estilo.Leitura.DICA, Estilo.TEXTO, fonteUi).apply { includeFontPadding = false }
        pilulaLembreteCasa = pilula(IconeLinha(IconeLinha.Tipo.RECADO), vidro = false).apply {
            visibility = View.GONE
            addView(txtLembreteCasa)
            setOnClickListener {
                Fundo.rodar { val t = HomeCardsTv.textosLembretes(this@MainActivity); ui.post { if (t.isNotEmpty()) AvisoTv.rapido(this@MainActivity, getString(R.string.hc_lembrete_rotulo), t.joinToString("\n"), 8) } }
            }
        }
        avisos.addView(pilulaLembreteCasa, lpPilula())
        txtContagem = texto(Estilo.Leitura.DICA, Estilo.TEXTO, fonteUi).apply { includeFontPadding = false }
        pilulaContagem = pilula(IconeLinha(IconeLinha.Tipo.CALENDARIO), vidro = false).apply {
            visibility = View.GONE
            addView(txtContagem)
        }
        avisos.addView(pilulaContagem, lpPilula())
        // Ideia 88: "Sem internet · Funcionam: pendrive, Na rede, Gravações". Primeira da cápsula (é o que mais importa
        // quando aparece). OK abre o Diagnóstico, que explica a queda em uma frase. Ver avaliarSemInternet.
        txtSemInternet = texto(Estilo.Leitura.DICA, Estilo.ATENCAO, fonteUi).apply { includeFontPadding = false }
        pilulaSemInternet = pilula(IconeApp(IconeApp.Tipo.SEM_REDE, Estilo.ATENCAO), vidro = false).apply {
            visibility = View.GONE
            addView(txtSemInternet)
            setOnClickListener { startActivity(Intent(this@MainActivity, DiagnosticoActivity::class.java)) }
        }
        avisos.addView(pilulaSemInternet, 0, lpPilula())
        // Status do seu serviço (HomeCards): só aparece quando um endereço cadastrado para de responder. OK mostra todos.
        txtServico = texto(Estilo.Leitura.DICA, Estilo.ATENCAO, fonteUi).apply { includeFontPadding = false }
        pilulaServico = pilula(IconeApp(IconeApp.Tipo.SEM_REDE, Estilo.ATENCAO), vidro = false).apply {
            visibility = View.GONE
            addView(txtServico)
            setOnClickListener {
                Fundo.rodar {
                    HomeCardsTv.testarServicos(this@MainActivity, forcar = true)
                    val fora = HomeCardsTv.foraDoArTodos(this@MainActivity)
                    ui.post { atualizarPilulasCartoes(); if (fora.isNotEmpty()) AvisoTv.rapido(this@MainActivity, getString(R.string.hc_servico_rotulo), fora.joinToString("\n") { getString(R.string.hc_servico_fora, it) }, 8) }
                }
            }
        }
        avisos.addView(pilulaServico, 1, lpPilula())
        atualizarAvisos()

        raiz.addView(topo, FrameLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(TOPO_PALCO) })
        topoRaiz = topo

        // Sem decoração (arco do sol/lua, etiqueta da obra): o lugar dela é da cruz.
        val vivo = criarFundo(this, tema, decoracao = false) { bloco -> rede.execute { bloco() } }
        fundo = vivo
        // Sem fundo preto aqui (nem na janela, ver onCreate): o fundo vivo já cobre a tela inteira e é opaco. Cada camada
        // de tela cheia a mais é a GPU pintando 1920x1080 de novo A CADA QUADRO (medido: 3 camadas, 22 ms por quadro).
        capa = CapaFoco(this).apply { desligada = tema == Tema.GALERIA || tema == Tema.MEU || tema == Tema.PLUGIN || !LayoutHome.arteCruz(this@MainActivity) }   // Galeria: a obra é o fundo, sem brilho por cima
        montarCruz(raiz)
        conteudo = raiz
        return FrameLayout(this).apply {
            clipChildren = false   // a tela que sobe (Continuar) desenha o que estava abaixo da borda
            addView(vivo, FrameLayout.LayoutParams(MATCH, MATCH))
            // Alto contraste (crítica de design 19/11/2026): véu quase preto sobre o fundo vivo e sem a capa do foco, para
            // o texto ficar sobre fundo chapado. Só existe com o tema ligado (é uma camada de tela cheia a mais).
            if (Estilo.altoContraste) { capa.desligada = true; addView(View(this@MainActivity).apply { setBackgroundColor(0xE6000000.toInt()) }, FrameLayout.LayoutParams(MATCH, MATCH)) }
            // A capa do foco: alto à direita, do tamanho do esboço (680 x 330 dp), atrás do topo e dos apps. Desenhada em
            // METADE da resolução e ampliada 2x (06/11: a capa cheia custava ~5 ms de GPU por quadro na Sala; é uma imagem
            // de fundo escurecida, a perda de nitidez não aparece). A ampliação parte do canto de cima à direita.
            // Esboço do dono (06/11 noite): de volta ao alto à direita, atrás do topo e do destaque, sumindo antes dos apps.
            // 12/11/2026: 600 x 400 dp (era 680 x 330), o tamanho da arte de filme/série aprovada no teste do dono.
            addView(capa, FrameLayout.LayoutParams(dp(300), dp(200), Gravity.TOP or Gravity.END))
            capa.escalaBase = 2f
            capa.addOnLayoutChangeListener { v, _, _, _, _, _, _, _, _ -> v.pivotX = v.width.toFloat(); v.pivotY = 0f }
            addView(raiz, FrameLayout.LayoutParams(MATCH, MATCH))
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
            setPaddingRelative(dp(3), dp(3), dp(3), dp(3))
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

    internal lateinit var txtAlarme: TextView          // "⏰ amanhã 6:30" na pílula do despertador
    internal lateinit var pilulaAlarme: LinearLayout
    @Volatile internal var gravandoTv: String? = null   // "Globo até 22:05" enquanto grava (Gravacoes.resumoParaHome)
    internal var ultimoTv: PlayerArmazem.Ultimo? = null   // último canal + programas (lido no onResume, em preencherContinuar)
    internal lateinit var txtTimer: TextView   // "4:32" na pílula do timer enquanto um timer corre (ver Timer.kt)
    internal lateinit var pilulaTimer: LinearLayout

    /** Atualiza o "⏱ 4:32" a cada segundo enquanto a HOME está na frente e há timer; some quando não há. */
    private val tarefaTimer = object : Runnable {
        override fun run() {
            val t = Timer.pilula(this@MainActivity)   // o próximo timer, "+N" e o cronômetro (ideia 31)
            if (t == null) {
                if (pilulaTimer.hasFocus()) focoPadrao()   // o foco não pode ficar num texto invisível
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
    internal fun atualizarFotoPerfil() {
        fotoPerfil.setImageBitmap(avatarDaBolinha())
    }

    /** A foto da conta ativa; no modo visita, o boneco genérico (conta = null), para não mostrar quem é o dono. */
    private fun avatarDaBolinha() =
        if (ModoVisita.ativo(this)) Perfil.avatar(this, dp(44), conta = null) else Perfil.avatar(this, dp(44))


    private var ultimaTemp: Int? = null   // a temperatura mostrada no topo (para a linha "Hoje" da previsao bater com ela)

    /** Abre a tela da previsao (ver PrevisaoActivity.kt); leva a temperatura do topo para as duas telas baterem. */
    private fun abrirPrevisao() {
        startActivity(Intent(this, PrevisaoActivity::class.java)
            .putExtra(PrevisaoActivity.EXTRA_TEMP, ultimaTemp ?: Int.MIN_VALUE)
            .putExtra(PrevisaoActivity.EXTRA_CHOVENDO, ultimoChovendo)
            .putExtra(PrevisaoActivity.EXTRA_CODIGO, ultimoCodigo)
            .putExtra(PrevisaoActivity.EXTRA_UMIDADE, getSharedPreferences(Prefs.CLIMA, MODE_PRIVATE).getInt("umidade", -1)))
    }

    /** ▲ que saiu da cruz para a barra (Ilha); se ele for SEGURADO, abre a Central em vez de ficar na barra. */
    private var ilhaCimaDaCruz = false
    private var ilhaCentralAberta = false

    /**
     * Central ao segurar ▲ (como a Central de Controle da Apple TV e o painel rápido da Samsung): o toque curto abre a barra,
     * como sempre; segurado, a primeira repetição da tecla abre o painel rápido. As repetições seguintes e o soltar são
     * engolidos, para o foco não continuar subindo.
     */
    private fun centralAoSegurarCima(event: KeyEvent): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_DPAD_UP) return false
        if (event.action == KeyEvent.ACTION_UP) {
            val engole = ilhaCentralAberta
            ilhaCimaDaCruz = false; ilhaCentralAberta = false
            return engole
        }
        if (event.repeatCount == 0) { ilhaCentralAberta = false; return false }
        if (!ilhaCimaDaCruz) return false
        if (!ilhaCentralAberta) { ilhaCentralAberta = true; abrirPopup() }
        return true
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
    companion object {
        @Volatile var homeFriaMedida = false   // placar (elevação 2.1): a HOME "fria" é medida uma vez por processo
        /** A abertura com a logo do boot já foi decidida neste processo (recriar a HOME não mostra de novo). */
        @Volatile var splashBootDecidido = false
        internal val cacheApps = HashMap<String, Pair<String, Item>>()
        /** Apps cujo ícone veio de um pacote de ícones (ou foi trocado à mão): no modo banner da cruz, o ícone vence o banner. */
        val iconeDePacote: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
        // Topo C3 (12/11/2026): cápsulas de 52 dp em 24 (até 76). Ordem pedida pelo dono (12/11 tarde): nome do card em foco
        // (36 sp) e a descrição na mesma linha em 222 (até ~266), "ASSISTIR" em 290 e os cards (do tamanho dos apps) em 316
        // (até 379), "APPS" em 397 e os apps em 429 (até 492): embaixo sobra a mesma margem das laterais (48 dp).
        const val TOPO_PALCO = 28          // onde começa a barra do topo (estilo Google TV, 12/11/2026)
        const val ILHA_VERMELHO = 0xFFFF5A4F.toInt()   // ponto e "gravando" da Ilha (alerta)
        const val ILHA_PONTO_CALMO = 0x99FFF3E6.toInt()   // ponto da Ilha sem nada a avisar: branco apagado (laranja = foco)
        const val ILHA_TEXTO_MAX = 240     // texto do evento na pílula da Ilha (a pílula não chega no canto)
        const val ALTURA_TOPO = 48         // altura das cápsulas (pílulas de 32 dp com 8 de folga em cima e embaixo)
        const val PREFS_CRUZ_APPS = "cruz_apps"   // aberturas por app (meia-vida 7 dias) e fixados no topo de Aplicativos
        const val PREFS_CRUZ = "cruz"   // cruz: o último item escolhido em cada categoria (id da categoria -> id do item)
        const val CONTINUAR_MAX = 9          // itens do Continuar da cruz (o 10º seria o "Dormir em 30 min")
        /** Foco na barra de cima vale por isto fora da HOME; depois, a volta cai na cruz. */
        const val FOCO_TOPO_VALE_MS = 5 * 60_000L
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
        /** As fichas de fichas-apps.json para a frase do destaque (ideia 13). Lidas uma vez, na filaApps; null até lá. */
        @Volatile var fichasHome: Map<String, FrasesApps.Ficha>? = null
        const val CARTAO_TRANCA = "aa:lock"            // o cartão "N escondidos · PIN" (nenhum pacote tem esse nome)
        const val COR_CONGELADOS = 0xFF9CC8E8.toInt()  // gelo
        const val COR_ESCONDIDOS = 0xFF9A9AA6.toInt()  // neutro: não é mundo nenhum
        const val DIAS_PARADO = 60                      // dica do Observador: sem abrir há 60 dias
    }

    /** Todos os apps de TV instalados, sem ordem e sem tirar os ocultos (quem organiza é appsVisiveis). */
    private fun carregarApps(): List<Item> {
        val pm = packageManager
        // "Quais apps aparecem no menu de uma TV?" = os que declaram a categoria LEANBACK_LAUNCHER.
        val consulta = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER)
        val itens = LinkedHashMap<String, Item>()
        val jogos = HashSet<String>()   // pacotes de jogo com ícone na TV, de sistema também (a conta da tela Jogos)
        val pacoteIcones = PacoteIcones.versao(this)
        // Plugin (fundo animado no formato do Projectivy) não é app de abrir: some da grade e da conta (Paulo, 19/11/2026).
        // Ele continua achado pelo próprio serviço em WallpaperPlugins.
        val plugins = runCatching { pm.queryIntentServices(Intent(WallpaperPluginContract.ACTION), 0).map { it.serviceInfo.packageName }.toSet() }
            .getOrDefault(emptySet())
        for (ri in pm.queryIntentActivities(consulta, 0)) {
            val pkg = ri.activityInfo.packageName
            if (pkg == packageName || pkg == Config.PACOTE_CONFIG || pkg in itens || pkg in plugins) continue
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
                // Ícone trocado à mão ou do pacote de ícones (CustomIcons; pacote de outro desenvolvedor = plano Pro).
                val doPacote = CustomIcons.icone(this, pkg, ri.activityInfo.name)
                if (doPacote != null) iconeDePacote += pkg else iconeDePacote -= pkg
                val original = doPacote ?: ri.loadIcon(pm)
                // Pacote embutido (Símbolo/Duotom, 18/11/2026): o traço do app no estilo da cruz; sem traço legível, o de sempre.
                val icone = PacoteIcones.embutido(this)?.let { DesenhoHome.iconeTraco(resources, original, it == PacoteIcones.Embutido.DUOTOM) }
                    ?: DesenhoHome.iconeRedondo(resources, original)
                Item(pkg, ri.loadLabel(pm).toString(), icone).also { cacheApps[pkg] = chave to it }
            }
        }
        // Congelados sob demanda (OnDemandTv): congelado some da consulta acima; entra com o nome e o ícone guardados.
        // App de sistema marcado (ficha "seguro") entra também, mesmo fora de SEMPRE_MOSTRAR: foi o dono que pediu.
        for (e in OnDemandTv.entries(this)) {
            if (e.pkg in itens || e.pkg == packageName || !OnDemandTv.installed(this, e.pkg)) continue
            val chave = "sob-demanda|" + pacoteIcones
            itens[e.pkg] = cacheApps[e.pkg]?.takeIf { it.first == chave }?.second ?: run {
                val original = OnDemandTv.icon(this, e.pkg) ?: pm.defaultActivityIcon
                Item(e.pkg, e.label, DesenhoHome.iconeRedondo(resources, original)).also { cacheApps[e.pkg] = chave to it }
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

    /** Categoria que o app declara (ApplicationInfo.category; -1 = nenhuma). Guardada: não muda enquanto o app existir. */
    private val categorias = java.util.concurrent.ConcurrentHashMap<String, Int>()   // preenchida em carregarApps (home-9)
    internal fun categoria(pacote: String): Int = categorias.getOrPut(pacote) {
        try { packageManager.getApplicationInfo(pacote, 0).category } catch (e: Exception) { -1 }
    }

    /**
     * Os apps que a cruz mostra, na ordem de sempre (manual, fixos, uso recente, Config.ORDEM, nome): sem os ocultos, sem
     * os do modo visita e, com o card Jogos ligado, sem os jogos (eles moram em Jogar). A cruz reordena por categoria
     * (CruzLogica.ordenarApps) e usa esta ordem para desempatar.
     */
    private fun appsVisiveis(todos: List<Item>): List<Item> {
        val ocultos = Organizacao.ocultos(this)
        val daVisita = if (ModoVisita.ativo(this)) ModoVisita.apps(this) else emptySet()
        val semJogos = JogosTv.cardLigado(this)
        val usados = ultimoUso   // lido fora da tela por preencherApps (o UsageStats de 14 dias é caro)
        val manual = Organizacao.ordem(this).withIndex().associate { it.value to it.index }
        return todos.filter { it.pacote !in ocultos && it.pacote !in daVisita && !(semJogos && JogosTv.ehJogo(this, it.pacote)) }
            .sortedWith(compareBy<Item>({ manual[it.pacote] ?: Int.MAX_VALUE }, { fixo(it.pacote) }, { -(usados[it.pacote] ?: 0L) },
                { posicao(it.pacote) }, { it.nome.lowercase() }))
    }

    private fun fixo(pacote: String): Int {
        val i = Config.FIXOS.indexOf(pacote)
        return if (i >= 0) i else Int.MAX_VALUE
    }

    private fun posicao(pacote: String): Int {
        val i = Config.ORDEM.indexOf(pacote)
        return if (i >= 0) i else Int.MAX_VALUE
    }

    internal fun preencherApps() {
        // Carregar os apps (PackageManager + ícone redondo de cada um) roda FORA da thread da tela (04/11/2026: um ANR de
        // 5 s tinha iconeRedondo na pilha da thread principal, no 1º onResume do processo). Só o desenho volta para ela.
        val t0 = android.os.SystemClock.elapsedRealtime()
        filaApps.execute {
            val todos = carregarApps()   // cacheApps só é tocado nesta fila (uma thread)
            val doJogos = todos.filter { JogosTv.ehJogo(this, it.pacote) }
            // Uso recente também aqui fora (04/11/2026): antes rodava na thread da tela.
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
                visiveis = appsVisiveis(instalados)
                atualizarCruz()
                // Medir para não deixar crescer: a thread da tela também atende as teclas do controle.
                val desenhou = android.os.SystemClock.elapsedRealtime() - t1
                android.util.Log.i("TvLauncher.Apps", "preencherApps: carregar=${t1 - t0} ms (fora da tela), desenhar=$desenhou ms")
                LevezaTv.anotarHome(t1 - t0, desenhou)   // números de leveza (degrau 2): `EXTRAS --es o leveza`
                // Placar (elevação 2.1, pilar 1): volta à HOME até os apps desenhados; e, na 1ª vez do processo, desde
                // o processo nascer (a TV ligou ou o launcher reiniciou).
                MedidasTv.anotar(this, Placar.Chave.HOME, t1 - t0 + desenhou)
                if (!homeFriaMedida) {
                    homeFriaMedida = true
                    MedidasTv.anotar(this, Placar.Chave.HOME_FRIA, android.os.SystemClock.uptimeMillis() - android.os.Process.getStartUptimeMillis())
                }
            }
        }
    }
    private val filaApps = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "Apps") }
    /** pacote -> último uso (ms), lido por preencherApps na filaApps. Só lido/escrito na thread da tela. */
    internal var ultimoUso: Map<String, Long> = emptyMap()

    /** Só reorganiza (ordem, ocultos) com os apps já carregados: síncrono, quem chama lê [visiveis] em seguida. */
    internal fun reorganizar() {
        visiveis = appsVisiveis(instalados)
        atualizarCruz()
    }

    // ---------------------------------------------------------------- HOME recomendada: capa do foco e Continuar

    /** Pílula de vidro do topo: o componente padrão do sistema de design (Estilo.pilula), com o foco padrão. */
    private fun pilula(icone: android.graphics.drawable.Drawable? = null, vidro: Boolean = true): LinearLayout =
        Estilo.pilula(this, icone, vidro = vidro)

    internal lateinit var pilulaVencimento: LinearLayout
    private lateinit var avisos: LinearLayout   // 2ª cápsula do topo: despertador, timer e lista vencendo

    /** A cápsula dos avisos só aparece com pelo menos um deles (vazia, sobrava uma bolha de vidro sem nada). */
    private fun atualizarAvisos() {
        val algum = (0 until avisos.childCount).any { avisos.getChildAt(it).visibility == View.VISIBLE }
        avisos.visibility = if (algum) View.VISIBLE else View.GONE
    }
    internal lateinit var txtVencimento: TextView

    /** Lê o vencimento das listas fora da tela (decifra as contas) e mostra/esconde a pílula. */
    private fun atualizarPilulaVencimento() {
        if (!PlayerArmazem.temContas(this)) { pilulaVencimento.visibility = View.GONE; atualizarAvisos(); return }
        Fundo.rodar {
            val texto = runCatching { ContaIptv.avisoVencimento(PlayerArmazem.vencimentos(this), System.currentTimeMillis(),
                t = ContaIptv.Textos(venceHoje = getString(R.string.cl_ci_vence_hoje), venceuCurto = getString(R.string.ci_venceu),
                    venceAmanha = getString(R.string.ci_vence_amanha), venceEmDias = { d -> getString(R.string.ci_vence_em, d) })) }.getOrNull()
            ui.post {
                if (isDestroyed) return@post
                if (texto == null) { if (pilulaVencimento.hasFocus()) focoPadrao(); pilulaVencimento.visibility = View.GONE }
                else { txtVencimento.text = texto; pilulaVencimento.visibility = View.VISIBLE }
                atualizarAvisos()
            }
        }
    }

    internal lateinit var txtJogo: TextView
    // Cartões próprios da tela inicial (HomeCards, 18/11/2026)
    private lateinit var pilulaCotacao: LinearLayout
    private lateinit var txtCotacao: TextView
    internal lateinit var pilulaLembreteCasa: LinearLayout
    internal lateinit var txtLembreteCasa: TextView
    internal lateinit var pilulaContagem: LinearLayout
    /** Cartões tirados da barra aberta por falta de espaço (ver o ajuste da esquerda); a Ilha ainda os mostra. */
    internal val cortadas = mutableSetOf<View>()
    internal lateinit var txtContagem: TextView
    internal lateinit var pilulaServico: LinearLayout
    internal lateinit var txtServico: TextView
    /** Próximo jogo do "Meu time" para a linha do item TV da cruz (HomeCards, chave CARTAO_PLACAR). */
    @Volatile internal var jogoCruz: String? = null

    /**
     * As pílulas dos cartões próprios: lê o guardado (rápido) e mostra; depois, fora da tela, busca a cotação e testa os
     * serviços (cada um tem o seu intervalo) e, se algo mudou, mostra de novo.
     */
    private fun atualizarPilulasCartoes(buscar: Boolean = false) {
        Fundo.rodar("cartoes") {
            if (buscar) {
                val mudou = runCatching { HomeCardsTv.atualizarCotacoes(this) }.getOrDefault(false) or
                    runCatching { HomeCardsTv.testarServicos(this) }.getOrDefault(false)
                if (mudou) ui.post { if (!isDestroyed) atualizarPilulasCartoes() }
                runCatching { HomeCardsTv.avisarLembretes(this) }
            }
            val cotacao = runCatching { HomeCardsTv.textoCotacao(this) }.getOrNull()
            val lembretes = runCatching { HomeCardsTv.textosLembretes(this) }.getOrDefault(emptyList())
            val contagem = runCatching { HomeCardsTv.textoContagem(this) }.getOrNull()
            val servico = runCatching { HomeCardsTv.textoServicoFora(this) }.getOrNull()
            val jogo = runCatching { if (Extras.ligado(this, Extras.Chave.CARTAO_PLACAR)) LembretesTv.proximoJogo(this) else null }.getOrNull()
            ui.post {
                if (isDestroyed) return@post
                cortadas.clear()   // de novo com todos; o ajuste da barra (esquerda) corta o que não couber
                fun mostrar(p: LinearLayout, t: TextView, texto: String?) {
                    if (texto == null) { if (p.hasFocus()) focoPadrao(); p.visibility = View.GONE }
                    else { t.text = texto; p.visibility = View.VISIBLE }
                }
                mostrar(pilulaCotacao, txtCotacao, cotacao)
                mostrar(pilulaLembreteCasa, txtLembreteCasa, lembretes.firstOrNull()?.let { it + if (lembretes.size > 1) "  +${lembretes.size - 1}" else "" })
                mostrar(pilulaContagem, txtContagem, contagem)
                mostrar(pilulaServico, txtServico, servico)
                atualizarAvisos()
                atualizarIlha()
                if (jogo != jogoCruz) { jogoCruz = jogo; atualizarCruz() }
            }
        }
    }
    internal lateinit var pilulaAniversario: LinearLayout
    internal lateinit var txtAniversario: TextView
    internal lateinit var pilulaJogo: LinearLayout

    // ---- Tocando agora (ideia 75)
    internal val capsulaPronta get() = ::capsulaTocando.isInitialized
    internal lateinit var capsulaTocando: LinearLayout
    internal lateinit var pilulaTocando: LinearLayout
    private lateinit var capaTocando: ImageView
    internal lateinit var barrasTocando: BarrasSom
    internal lateinit var txtTocandoTitulo: TextView
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
    internal var ouvindoCruz: Pair<String, String>? = null

    private fun mostrarTocando(p: TocandoAgoraTv.Pilula?, icone: android.graphics.drawable.Drawable?) {
        ui.removeCallbacks(tarefaTocando)
        val ouvindo = p?.takeIf { it.nosso && it.tocando }?.let { it.tipoNosso.ifEmpty { "radio" } to it.titulo }
        if (ouvindo != ouvindoCruz) { ouvindoCruz = ouvindo; atualizarCruz() }
        if (p == null) {
            if (pilulaTocando.hasFocus()) focoPadrao()   // o foco não pode ficar numa pílula invisível
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
        atualizarIlha()   // pausar/continuar não muda o tamanho de nada: a Ilha não veria pela arrumação da tela
    }

    /** Ideia 93: aniversário hoje ou amanhã (lista da TV). Mais de um: o primeiro e "+N". */
    private fun atualizarPilulaAniversario() {
        Fundo.rodar {
            val textos = runCatching { Aniversarios.textos(this) }.getOrDefault(emptyList())
            ui.post {
                if (isDestroyed) return@post
                if (textos.isEmpty()) { if (pilulaAniversario.hasFocus()) focoPadrao(); pilulaAniversario.visibility = View.GONE }
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
                if (texto == null) { if (pilulaJogo.hasFocus()) focoPadrao(); pilulaJogo.visibility = View.GONE }
                else { txtJogo.text = texto; pilulaJogo.visibility = View.VISIBLE }
                atualizarAvisos()
            }
        }
    }

    /** O próximo despertador na pílula: "hoje 18:30", "amanhã 6:30" ou "sáb 7:00". Sem alarme, a pílula some. */
    private fun atualizarPilulaAlarme() {
        val prox = runCatching { Despertador.proximo(this) }.getOrNull()
        if (prox == null) { alarmeEm = null; if (pilulaAlarme.hasFocus()) focoPadrao(); pilulaAlarme.visibility = View.GONE; atualizarAvisos(); return }
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
        alarmeEm = prox.second
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
        fundo?.visibility = if (TesteHome.fundo) View.VISIBLE else View.INVISIBLE
        capa.setLayerType(if (TesteHome.capaCamada) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE, null)
    }

    /** "T1 E2" (como fica gravado) vira "Temporada 1, episódio 2": sigla é jargão (crítica de design 19/11/2026). */
    internal fun epLegivel(e: String): String {
        val m = Regex("""^T(\d+) ?E(\d+)$""").find(e.trim()) ?: return e
        return getString(R.string.serie_ep_longo, m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    internal fun duracaoTexto(min: Int): String {
        val h = min / 60; val m = min % 60
        return when { h == 0 -> "$m min"; m == 0 -> "$h h"; else -> "$h h $m min" }
    }

    /**
     * Linha de baixo de um app (dono, 10/11/2026, opção A): uma frase curta escrita à mão para os apps desta TV
     * ([DESCRICAO_APPS]); para app novo, a categoria que ele declara no idioma da TV (ex.: "Vídeo"); sem nada, "App".
     * (Medido: YouTube, Netflix e cia. não declaram categoria, por isso a tabela.)
     */
    internal fun subApp(pacote: String, nome: String): String {
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

    internal fun mostrarCapaDoApp(pacote: String, icone: Drawable?) {
        capaPedida = pacote
        val pronta = capas[pacote]
        if (pronta != null) { capa.mostrar(pronta.first, if (pronta.first == null) pronta.second else null); return }
        // Ainda não lida (app novo, ou a leitura antecipada não chegou nele): lê fora e mostra se o foco ainda for dele.
        filaApps.execute {
            val (img, cores) = lerCapa(pacote, icone)
            ui.post { if (!isDestroyed && capaPedida == pacote) capa.mostrar(img, if (img == null) cores else null) }
        }
    }

    internal fun preencherContinuar() {
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
        if (novos.isNullOrEmpty()) atualizarGuiaNoFundo()
        return if (novos.isNullOrEmpty()) u else u.copy(programas = novos)
    }

    /**
     * O guia acabou (visto na Sala, 19/11/2026: a TV ficou o dia sem abrir o player e o item TV perdeu o "No ar"; o guia
     * cobre só ~14 h e só o player o baixava). A HOME pede o guia de novo, fora da tela: [PlayerCarga.guia] só baixa se
     * estiver velho ou sem nada passando agora, e no máximo 1 vez por hora; daqui também 1 pedido por hora. Pronto, o
     * Continuar é lido de novo.
     */
    private fun atualizarGuiaNoFundo() {
        val agora = System.currentTimeMillis()
        if (agora - guiaPedidoEm < 3_600_000L) return
        guiaPedidoEm = agora
        Fundo.rodar("guia-home") {
            val canais = runCatching { PlayerCarga.canaisDoCache(this) }.getOrDefault(emptyList())
            if (canais.isEmpty()) return@rodar
            if (runCatching { PlayerCarga.guia(this, canais) }.isSuccess) ui.post { if (!isDestroyed) preencherContinuar() }
        }
    }
    @Volatile private var guiaPedidoEm = 0L
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

    /** Fundo de um card de Assistir: o ícone grande, ou o degradê da cor enquanto ele não fica pronto. Parado, sem zoom. */
    internal fun mostrarCapaAtalho(tipo: IconeApp.Tipo) {
        capaPedida = null
        // Jogos: o banner (logo oficial) do jogo usado por último, como o de um app em foco. Sem jogo usado, o controle.
        if (tipo == IconeApp.Tipo.JOGOS) ultimoJogo?.first?.let { p -> capas[p]?.first?.let { capa.mostrar(it, null); return } }
        // Séries e Filmes: a arte do último título, grande à direita (12/11/2026). Sem arte, o ícone como os outros.
        arteAtalho[tipo]?.second?.let { capa.mostrar(it, null, arte = true); return }
        // Sem arte não vai o ícone gigante esmaecido atrás do foco (crítica de design 19/11/2026: cruzava o eixo
        // e encostava no mundo da direita). Fica o fundo limpo.
        capa.esconder()
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

    /** O que preencherContinuar leu (TV, Séries/Filmes, último canal) chega à cruz, na thread da tela. */
    private fun desenharContinuar(temTv: Boolean, temVod: Boolean, ultimo: PlayerArmazem.Ultimo?) {
        ultimoTv = ultimo; cruzTemTv = temTv; cruzTemVod = temVod; cruzContinuarLido = true
        atualizarCruz()
    }

    /** Cartões dos plugins permitidos (PluginsTv), da última leitura na filaApps. */
    @Volatile internal var cartoesPlugin: List<PluginCartao.Cartao> = emptyList()

    /** Quantos apps de jogo a TV tem (lido em preencherApps, fora da tela). */
    @Volatile private var jogosNaTv = 0
    /** pacote e nome do jogo usado por último (UsageStats), ou null. Lido em preencherApps. */
    @Volatile private var ultimoJogo: Pair<String, String>? = null

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

    /** true até o 1º desenho do Continuar nesta abertura da HOME (ver desenharContinuar). */
    internal var focoInicialNaTv = true

    /** A busca única roda no processo da HOME (só lê os caches). */
    private fun abrirBusca() {
        try { startActivity(Intent(this, BuscaActivity::class.java)) }
        catch (e: Exception) { android.util.Log.w("TvLauncher.Busca", "não abriu: ${e.javaClass.simpleName}") }
    }

    /** Abre só um catálogo do player (Séries ou Filmes), no processo dele; o VOLTAR de lá cai de novo na HOME. */
    internal fun abrirCatalogo(tela: Class<out Activity>) {
        try { startActivity(Intent(this, tela)) }
        catch (e: Exception) { android.util.Log.w("TvLauncher.Player", "não abriu ${tela.simpleName}: ${e.javaClass.simpleName}") }
    }

    /** Abre o player (processo próprio, ver PlayerActivity) no último canal assistido. */
    internal fun abrirTv() {
        try { startActivity(Intent(this, PlayerActivity::class.java)) }
        catch (e: Exception) { android.util.Log.w("TvLauncher.Player", "não abriu o player: ${e.javaClass.simpleName}") }
    }


    // ---------------------------------------------------------------- menu (segure OK em uma pilha)

    internal class Opcao(val rotulo: String, val acao: () -> Unit)

    internal var menuAberto: android.app.Dialog? = null


    /**
     * Pergunta antes de desinstalar (não tem volta: os dados do app somem junto). O foco começa no "Cancelar", para um
     * OK distraído não apagar nada. Quem desinstala é o PainelApps.desinstalar: em silêncio, pelo Device Owner, e
     * esperando o resultado de verdade do Android (fora da thread da tela, porque espera até 60 s).
     */
    internal fun confirmarDesinstalar(pacote: String) {
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

    internal fun mostrarAviso(texto: String) = AvisoTv.rapido(this, "Apps", texto)

    /**
     * Leva o app para o pen drive adotado ou de volta para a TV (AppStorage.kt). O Android fecha o app enquanto copia;
     * 1 GB leva ~1,5 min num pen drive comum. Dá para desfazer pelo mesmo menu, então não pede confirmação.
     */
    /** RetroArch e afins: libera ler pen drives e arquivos (PainelPermissoes.liberarArquivos), pelo shell, fora da tela. */
    internal fun liberarArquivos(pacote: String) {
        val nome = nomeDe(pacote)
        Fundo.rodar("Arquivos") {
            val motivo = PainelPermissoes.liberarArquivos(this, pacote)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                mostrarAviso(if (motivo == null) getString(R.string.home_arquivos_liberado, nome)
                    else getString(R.string.home_arquivos_falhou, nome, motivo))
            }
        }
    }

    internal fun mudarArmazenamento(pacote: String, paraExterno: Boolean, confirmado: Boolean = false) {
        val nome = nomeDe(pacote)
        if (!confirmado) {
            // App grande: aviso antes (o que derrubou a Sala em 20/11/2026). Pesar o app lê o disco: fora da tela.
            Fundo.rodar("AppStorage") {
                val a = StorageMode.appMovel(this, pacote)
                runOnUiThread {
                    if (isFinishing || isDestroyed) return@runOnUiThread
                    if (a?.arriscado == true) StorageActivity.avisoGrande(this, nome, Formatter.formatShortFileSize(this, a.bytes)) {
                        mudarArmazenamento(pacote, paraExterno, confirmado = true)
                    } else mudarArmazenamento(pacote, paraExterno, confirmado = true)
                }
            }
            return
        }
        mostrarAviso(getString(R.string.home_instalando_lugar, nome))
        Fundo.rodar("AppStorage") {
            val motivo = AppStorage.mover(this, pacote, paraExterno)
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                mostrarAviso(when {
                    motivo == null && paraExterno -> getString(R.string.home_instalado_externo, nome)
                    motivo == null -> getString(R.string.home_instalado_dispositivo, nome)
                    motivo == "sem_adotado" -> getString(R.string.home_instalar_lugar_falhou, nome, getString(R.string.home_instalar_lugar_sem_pendrive))
                    motivo == "shell" -> getString(R.string.home_instalar_lugar_falhou, nome, getString(R.string.home_instalar_lugar_sem_shell))
                    else -> getString(R.string.home_instalar_lugar_falhou, nome, motivo)
                })
            }
        }
    }

    /** Depois de mostrar/ocultar: fecha a pasta aberta, relê tudo e leva o foco para [foco] (ou perto de onde estava). */
    internal fun atualizarAposMudanca(foco: String?) {
        reorganizar()
    }

    internal fun nomeDe(pacote: String): String = instalados.firstOrNull { it.pacote == pacote }?.nome ?: pacote

    // ---------------------------------------------------------------- tela inicial em cruz (17/11/2026)
    // A única HOME desde 21/11/2026 (a antiga, em fileira/grade, saiu). Dados: preencherContinuar (TV, Séries, Filmes,
    // plugins) e preencherApps (apps em ordem, ocultos, modo visita, Jogos). "Todos os apps" é a AllAppsScreen (abaixo).

    internal var cruz: HomeCruz? = null
    /** "Todos os apps" aberto: a cruz some e a grade dos apps aparece; Voltar ou HOME fecham. */
    internal var todosAbertos = false
    /** Entre onResume e onPause: a HOME é o que está na tela (onNewIntent chega ANTES do onResume vindo de um app). */
    internal var homeNaFrente = false
    internal var cruzTemTv = false
    internal var cruzTemVod = false
    /** A 1ª leitura do player chegou (sem ela a cruz montaria sem a TV e pularia de lugar logo depois). */
    internal var cruzContinuarLido = false
    private val estiloCruz by lazy { LayoutHome.iconesCruz(this) }
    /** Um desenho por lugar (id): a mesma instância volta a cada atualização (HomeCruz só troca o que mudou). */
    private val iconesCruz = HashMap<String, android.graphics.drawable.Drawable>()
    /** Cópia do ícone de um app para a cruz: um Drawable não pode estar em duas views (o card da grade usa o original). */
    private val iconesAppCruz = java.util.WeakHashMap<Item, android.graphics.drawable.Drawable>()

    /** Os tipos com desenho trocado à mão (Trocar ícone): vencem o conjunto de Aparência. */
    private val iconesTrocados by lazy { LayoutHome.iconesTrocados(this) }
    internal fun icCruz(lugar: String, tipo: IconeCruz.Tipo) = iconesCruz.getOrPut(lugar) { IconeCruz(tipo, iconesTrocados[tipo] ?: estiloCruz) }

    /**
     * "Trocar ícone" de um item do Sofaly na cruz (21/11/2026): primeiro o quê (o item ou a categoria dele, quando são
     * desenhos diferentes), depois o desenho, com a prévia de cada conjunto na própria linha. "Seguir o conjunto" apaga a
     * troca. A HOME se refaz para todos os lugares com esse tipo mudarem juntos.
     */
    internal fun trocarIconeSofaly(tipo: IconeCruz.Tipo, nome: String, tipoCat: IconeCruz.Tipo?, nomeCat: String?) {
        fun escolherDesenho(t: IconeCruz.Tipo, rotulo: String) {
            val lado = (40 * resources.displayMetrics.density).toInt()
            fun comDesenho(e: IconeCruz.Estilo, texto: String): CharSequence {
                val d = IconeCruz(t, e).apply { setBounds(0, 0, lado * 6 / 5, lado) }
                return android.text.SpannableString("\u00A0  $texto").apply {
                    setSpan(android.text.style.ImageSpan(d, android.text.style.DynamicDrawableSpan.ALIGN_BOTTOM), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            val estilos = IconeCruz.Estilo.entries
            val conjunto = LayoutHome.iconesCruz(this)
            val nomes = listOf(comDesenho(conjunto, getString(R.string.cruz_icone_seguir, getString(LayoutHome.nomeIcones(conjunto))))) +
                estilos.map { comDesenho(it, getString(LayoutHome.nomeIcones(it))) }
            val atual = LayoutHome.iconeTrocado(this, t)
            menuAberto = Estilo.lista(this, getString(R.string.cruz_icone_de, rotulo), nomes,
                marcadas = listOf(if (atual == null) 0 else atual.ordinal + 1)) { i ->
                val novo = if (i == 0) null else estilos[i - 1]
                if (novo == atual) return@lista
                LayoutHome.trocarIcone(this, t, novo)
                recreate()
            }
        }
        if (tipoCat == null || nomeCat == null || tipoCat == tipo) { escolherDesenho(tipo, nome); return }
        menuAberto = Estilo.lista(this, getString(R.string.wp_trocar_icone), listOf(nome, getString(R.string.cruz_icone_categoria, nomeCat))) { i ->
            if (i == 0) escolherDesenho(tipo, nome) else escolherDesenho(tipoCat, nomeCat)
        }
    }
    internal fun icApp(item: Item) = iconesAppCruz.getOrPut(item) {
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
                    // Ícone de pacote (ou trocado à mão): vence o banner do app, numa placa da moldura da cruz.
                    val doPacote = if (item.pacote in iconeDePacote) CustomIcons.icone(this, item.pacote, null) else null
                    val banner = capa?.first
                    if (doPacote != null) {
                        c.drawColor(0xFF1C2030.toInt())
                        val brilho = android.graphics.Paint().apply {
                            shader = android.graphics.LinearGradient(0f, 0f, 0f, a.toFloat(), 0x1FFFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
                        }
                        c.drawRect(0f, 0f, l.toFloat(), a.toFloat(), brilho)
                        val t = (a * 0.68f).toInt()
                        doPacote.setBounds((l - t) / 2, (a - t) / 2, (l + t) / 2, (a + t) / 2)
                        doPacote.draw(c)
                    } else if (banner != null && !banner.isRecycled) {
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
        c.detalheEmCima = barraCruz == LayoutHome.BarraCruz.RELOGIO
        c.aoMudar = { _, item -> arteDoItem(item) }
        c.aoSom = { som, cat -> if (homeNaFrente) SonsCruz.tocar(this, som, cat) }   // voltando de um app (HOME), sem som
        c.aoSubirTopo = { repeticao ->
            // Só o ▲ que SAI da cruz para a barra arma a Central (▲ entre itens da cruz continua repetindo normalmente).
            // Visto na Sala (v464530): o foco só sai da cruz DEPOIS do dispatchKeyEvent, então o aviso vem daqui.
            // E só um ▲ NOVO no 1º item (repetição 0): segurar ▲ para subir a coluna chegava ao topo e abria a Central
            // sem querer (debug 20/11/2026).
            if (barraCruz == LayoutHome.BarraCruz.ILHA && repeticao == 0) ilhaCimaDaCruz = true
            ui.post { bolinhaPerfil?.requestFocus() }   // ▲ no 1º item: o perfil, 1º da barra
        }
        c.aoPasso = { t -> deslizarArte(t) }
        raizCruz = raiz
        vigiarTela()
        montarBarraCruz(raiz, c)
    }

    // ------------------------------------------------------------------ barra de cima e arte do item, na cruz

    internal val barraCruz by lazy { LayoutHome.barraCruz(this) }
    internal var relogioCruz: View? = null       // relógio grande (barra RELOGIO), no canto livre de baixo
    private var tempoCruz: TextView? = null     // "27°" ao lado da hora, com a barra recolhida
    internal var barraRecolhida = false

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
        BarraIlha.agora = null   // outro estilo de barra (ou a tela refeita): o MCP não fica com a fila velha
        if (barraCruz == LayoutHome.BarraCruz.ILHA) montarIlha(raiz, topo)
        // Recua: a temperatura entra ao lado da hora enquanto a barra está recolhida.
        if (barraCruz == LayoutHome.BarraCruz.RECUA && direita != null) {
            val t = texto(18f, textoClaro, fonteUi).apply { includeFontPadding = false; text = txtTemp.text; alpha = 0f }
            direita.addView(t, 0, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(4) })
            // Enquanto o clima não chegou ("…"), nada: "… ·" solto parecia texto quebrado (crítica de design 19/11/2026).
            fun copiar() { t.text = txtTemp.text.toString().takeIf { it.isNotBlank() && it != "…" }?.let { "$it  ·" }.orEmpty() }
            txtTemp.addTextChangedListener(espelho { copiar() })
            copiar()
            tempoCruz = t
        }
        // A pílula "Tocando agora" costuma aparecer DEPOIS que a barra já recolheu (estava GONE, sem posição): a cada novo
        // layout dela, com a barra recolhida, ela vai para o começo da linha (visto na TV, v462231: ficava sobre a temperatura).
        capsulaTocando.addOnLayoutChangeListener { v, l, _, _, _, _, _, _, _ ->
            if (barraRecolhida && barraCruz != LayoutHome.BarraCruz.ILHA && v.translationX != -l.toFloat()) { v.animate().cancel(); v.alpha = 1f; v.translationY = 0f; v.translationX = -l.toFloat() }
        }
        if (barraCruz == LayoutHome.BarraCruz.RELOGIO) relogioCruz = montarRelogioCruz().also {
            raiz.addView(it, FrameLayout.LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply {
                marginEnd = dp(Grade.MARGEM); bottomMargin = dp(40)
            })
        }
        window.decorView.viewTreeObserver.addOnGlobalFocusChangeListener { velho, novo ->
            if (novo == null || todosAbertos) return@addOnGlobalFocusChangeListener
            // Ilha (19/11/2026): ▲ saindo da cruz vai direto ao botão do evento que a pílula mostrava (pausar, cancelar o
            // timer, Diagnóstico), como a Central da Apple TV abre na aba do que está ativo.
            if (velho === c && novo !== c && barraCruz == LayoutHome.BarraCruz.ILHA) {
                botaoDoEvento(ilhaEvento)?.takeIf { it !== novo && it.visibility == View.VISIBLE && it.isFocusable }?.let { b -> b.post { b.requestFocus() } }
            }
            recolherBarra(novo === c)
        }
        recolherBarra(true, animar = false)
    }

    internal fun recolherBarra(recolher: Boolean, animar: Boolean = true) {
        val topo = topoRaiz as? ViewGroup ?: return
        if (recolher == barraRecolhida && animar) return
        barraRecolhida = recolher
        val d = if (animar) 220L else 0L
        // Leitor de tela (pente fino 19/11/2026): o que está apagado sai da árvore de acessibilidade; antes ele lia
        // "Perfil", "Buscar" e a hora duas vezes com a barra recolhida. O foco do controle não muda com isto.
        fun leitor(v: View?, visivel: Boolean) {
            v?.importantForAccessibility = if (visivel) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        fun mexer(v: View, fica: Boolean) {
            leitor(v, !recolher || fica)
            v.animate().alpha(if (!recolher || fica) 1f else 0f).translationY(if (recolher && !fica) -dp(24).toFloat() else 0f).setDuration(d).start()
        }
        for (i in 0 until topo.childCount) {
            val v = topo.getChildAt(i)
            // Recua: a direita (hora, e a temperatura) fica; Relógio: some tudo (a hora está grande embaixo).
            val fica = recolher && i == 2 && barraCruz == LayoutHome.BarraCruz.RECUA
            // A pílula "Tocando agora" nunca some com a barra (visto na TV 17/11/2026: estava na árvore de acessibilidade,
            // mas invisível): o grupo dela fica, e só os vizinhos (busca, clima, avisos) recolhem.
            // Na Ilha o que toca está na pílula do centro: a cápsula recolhe com as outras.
            if (v is ViewGroup && capsulaTocando.parent === v && barraCruz != LayoutHome.BarraCruz.ILHA) {
                v.animate().cancel(); v.alpha = 1f; v.translationY = 0f; leitor(v, true)
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
        leitor(tempoCruz, recolher)
        leitor(ilhaPilula, recolher)
        leitor(relogioCruz, recolher)   // aberta, a hora já está na barra (o relógio grande fica a 35%)
        // Ilha: recolhida = só a pílula; aberta = a barra de sempre com o véu atrás (a pílula dá lugar à hora dela).
        if (!recolher) { ui.removeCallbacks(tarefaIlhaEncolhe); ilhaDica?.animate()?.cancel(); ilhaDica?.alpha = 0f }
        ilhaPilula?.animate()?.alpha(if (recolher) 1f else 0f)?.translationY(if (recolher) 0f else -dp(16).toFloat())?.setDuration(d)?.start()
        ilhaVeu?.animate()?.alpha(if (recolher) 0f else 1f)?.setDuration(d)?.start()
        relogioCruz?.animate()?.alpha(if (recolher) 1f else 0.35f)?.setDuration(d)?.start()
        cruz?.animate()?.translationY(if (recolher) 0f else dp(34).toFloat())?.setDuration(d)?.start()
    }

    /** Relógio grande da barra RELOGIO: hora, data por extenso e o tempo de agora (espelha a pílula do clima). */
    internal fun montarRelogioCruz(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.END
        val sombra = 0x99000000.toInt()
        addView(RelogioTela(this@MainActivity, data = false).apply {
            format12Hour = "HH:mm"; format24Hour = "HH:mm"
            // 44 sp (era 72): o relógio do canto não pode gritar mais que o item em foco da cruz (32 sp); crítica 19/11/2026
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 44f)
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

    // ------------------------------------------------------------------ barra Ilha (A+B+D, 18/11/2026; ver BarraIlha)

    internal var ilhaPilula: LinearLayout? = null
    internal var ilhaExtra: LinearLayout? = null
    internal var ilhaIcone: ImageView? = null
    internal var ilhaBarras: BarrasSom? = null
    internal var ilhaTexto: TextView? = null
    internal var ilhaTemp: TextView? = null
    internal var ilhaVeu: View? = null
    internal var ilhaEvento: BarraIlha.Evento? = null
    internal var ilhaFila: List<BarraIlha.Evento> = emptyList()
    internal var ilhaPasso = 0
    internal var ilhaPontoView: View? = null
    internal var ilhaTrilho: LinearLayout? = null
    internal var ilhaContador: TextView? = null
    internal var ilhaHora: TextView? = null
    /** Lidos a cada minuto fora da thread da tela ([tarefaIlhaTique]): (texto, quando começa). */
    @Volatile internal var ilhaLembrete: Pair<String, Long>? = null
    @Volatile internal var ilhaAgenda: Pair<String, Long>? = null
    @Volatile internal var ilhaIndicadores: Set<BarraIlha.Indicador> = emptySet()
    internal var ilhaTrilhoMostrado: BarraIlha.Trilho? = null
    /** Ilha que cresce: a faixa de baixo com o evento que chegou e "▲ para abrir"; some em [BarraIlha.CRESCER_MS]. */
    internal var ilhaDica: TextView? = null
    internal val tarefaIlhaEncolhe = Runnable { ilhaDica?.animate()?.alpha(0f)?.translationY(-dp(6).toFloat())?.setDuration(220)?.start() }
    /** Rodízio da fila: troca o evento da vez a cada [BarraIlha.RODIZIO_MS], só com a barra recolhida e a HOME na frente. */
    internal val tarefaIlhaGira = object : Runnable {
        override fun run() {
            if (ilhaFila.size < 2 || !homeNaFrente) return
            if (barraRecolhida) { ilhaPasso++; aplicarIlha() }
            ui.postDelayed(this, BarraIlha.RODIZIO_MS)
        }
    }
    /** A cada minuto: lembrete do guia, agenda de hoje e o trilho de estado (nada disso muda a arrumação da tela sozinho). */
    internal val tarefaIlhaTique = object : Runnable {
        override fun run() {
            if (!homeNaFrente || ilhaPilula == null) return
            filaApps.execute { runCatching { lerFontesIlha() }; ui.post { atualizarIlha() } }
            // Cartões (visto na Sala 19/11/2026): o serviço só era testado ao voltar à HOME, então com a TV parada na
            // tela inicial o "fora do ar" nunca aparecia. Aqui ele e a cotação seguem o próprio intervalo (5 min e 3 h).
            Fundo.rodar("cartoes-tique") {
                val mudou = runCatching { HomeCardsTv.testarServicos(this@MainActivity) }.getOrDefault(false) or
                    runCatching { HomeCardsTv.atualizarCotacoes(this@MainActivity) }.getOrDefault(false)
                if (mudou) ui.post { if (!isDestroyed) atualizarPilulasCartoes() }
            }
            ui.postDelayed(this, 60_000L)
        }
    }
    /** Quando toca o próximo despertador (ms), para a pílula só mostrá-lo nas últimas 12 h. */
    internal var alarmeEm: Long? = null
    internal val tarefaIlhaAcordou = Runnable {
        if (cruz?.hasFocus() != true) return@Runnable
        recolherBarra(true)
        BarraIlha.resumoChegada(ilhaFila)?.let { crescerIlhaTexto(it, ilhaFila.any { e -> e.atencao }) }   // resumo de chegada
    }











    // ------------------------------------------------------------------ movimento dos ícones (MovimentoAdaptativo)










    // ------------------------------------------------------------------ Continuar e Jogar da cruz (18/11/2026)

    /** Uma entrada de app de mídia no Continuar, com a capa já pronta (lida fora da tela). */
    internal class MidiaCruz(val registro: MidiaRecente.Registro, val capa: Drawable?)
    @Volatile internal var radioCruz: Pair<Radios.Radio, Long>? = null
    @Volatile internal var podcastCruz: Podcasts.Posicao? = null
    @Volatile internal var midiaCruz: List<MidiaCruz> = emptyList()
    /** O último título de cada app de mídia (para a linha de baixo do app na cruz) e quantos podcasts você segue. */
    @Volatile private var midiaPorApp: Map<String, MidiaRecente.Registro> = emptyMap()
    @Volatile internal var assinadosCruz = 0

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
    internal fun jogosNaCruz(): List<Item> {
        val ocultos = Organizacao.ocultos(this)
        val daVisita = if (ModoVisita.ativo(this)) ModoVisita.apps(this) else emptySet()
        return instalados.filter { JogosTv.ehJogo(this, it.pacote) && it.pacote !in ocultos && it.pacote !in daVisita }
    }

    /** Até [JOGOS_NO_CONTINUAR] jogos já abertos, do mais recente. */
    internal fun jogosRecentes(): List<Pair<String, Item>> =
        jogosNaCruz().filter { (ultimoUso[it.pacote] ?: 0L) > 0L }.sortedByDescending { ultimoUso[it.pacote] }
            .take(JOGOS_NO_CONTINUAR).map { it.pacote to it }

    // ---- linhas de informação da cruz (dono, 18/11/2026: "a regra da TV em toda a HOME"; CruzLogica.linhas)

    /** "há 2 horas", "ontem"… no idioma da TV; null sem data ou no futuro. */
    internal fun quando(ms: Long?): String? {
        val agora = System.currentTimeMillis()
        if (ms == null || ms <= 0L || ms > agora + 60_000) return null
        // Menos de 1 min: "agora" (o Android diria "Há 0 minuto"). Senão "há 5 min", abreviado e com minúscula,
        // porque entra no meio da frase ("Aberto há 5 min").
        if (agora - ms < 60_000) return getString(R.string.pg_agora)
        return android.text.format.DateUtils.getRelativeTimeSpanString(ms, agora, android.text.format.DateUtils.MINUTE_IN_MILLIS,
            android.text.format.DateUtils.FORMAT_ABBREV_RELATIVE).toString().replaceFirstChar { it.lowercase(Locale.getDefault()) }
    }

    /** Ano · duração (filme) · nota, numa linha. */
    internal fun metaFicha(f: PlayerArmazem.FichaHome, serie: Boolean): String? = CruzLogica.juntar(
        f.ano?.takeIf { it > 1800 }?.toString(),
        if (serie) null else f.duracaoMin?.takeIf { it > 0 }?.let { duracaoTexto(it) },
        f.nota?.takeIf { it > 0f }?.let { "★ " + String.format(Locale.getDefault(), "%.1f", it) })

    /** Quanto falta do episódio parado no meio. */
    internal fun faltaSerie(f: PlayerArmazem.FichaHome): String? = posicoesHome[f.chave]?.takeIf { it.duracao > 0 && it.ms > 0 && it.ms < it.duracao }
        ?.let { ((it.duracao - it.ms) / 60_000).toInt() }?.takeIf { it > 0 }?.let { BarraTempo.faltam(this, duracaoTexto(it)) }

    internal fun faltaMidia(r: MidiaRecente.Registro): String? = r.takeIf { it.durMs > 0 && it.posMs in 1 until it.durMs }
        ?.let { ((it.durMs - it.posMs) / 60_000).toInt() }?.takeIf { it > 0 }?.let { BarraTempo.faltam(this, duracaoTexto(it)) }

    /** Um app na cruz: a frase dele, o que você viu/ouviu por último nele (sessão de mídia) e quando abriu. */
    internal fun linhasApp(pacote: String, nome: String): String = CruzLogica.linhas(subApp(pacote, nome),
        midiaPorApp[pacote]?.let { r -> getString(R.string.cruz_por_ultimo, CruzLogica.juntar(r.titulo, r.sub) ?: r.titulo) },
        quando(ultimoUso[pacote])?.let { getString(R.string.cruz_aberto, it) })

    /** Central de jogos: a turma do Discord e os controles ligados, cada um na sua linha. */
    internal fun linhasCentral(): String {
        val controles = runCatching { JogosTv.controles().size }.getOrDefault(0)
        return CruzLogica.linhas(resumoSofa(), controles.takeIf { it > 0 }?.let { resources.getQuantityString(R.plurals.jogos_controles, it, it) },
            jogosRecentes().firstOrNull()?.let { (p, it) -> CruzLogica.juntar(getString(R.string.cruz_por_ultimo, it.nome), quando(ultimoUso[p])) })
    }

    /** Pendrives/HDs ligados agora (nome do volume). */
    internal fun pendrivesCruz(): List<String> = runCatching {
        (getSystemService(android.content.Context.STORAGE_SERVICE) as android.os.storage.StorageManager).storageVolumes
            .filter { it.isRemovable && it.state == android.os.Environment.MEDIA_MOUNTED }.map { it.getDescription(this) }
    }.getOrDefault(emptyList()).take(2)

    internal fun faltaPodcast(p: Podcasts.Posicao): String =
        if (p.durMs > p.ms) ((p.durMs - p.ms) / 60_000).toInt().takeIf { it > 0 }?.let { BarraTempo.faltam(this, duracaoTexto(it)) } ?: ""
        else ""

    internal fun tocarRadioCruz(r: Radios.Radio) {
        if (semInternetCruz) { cruz?.naoAbriu(); return }
        OuvirServico.tocarRadio(this, r)   // toca e fica na HOME; a pílula "Tocando agora" aparece
    }

    /** Retoma o episódio de onde parou (o feed já está no cache); sem o feed, abre a tela de Podcasts. */
    internal fun retomarPodcastCruz(p: Podcasts.Posicao) {
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
    internal fun abrirMidiaCruz(pacote: String) {
        val sessao = runCatching {
            getSystemService(android.media.session.MediaSessionManager::class.java)
                ?.getActiveSessions(android.content.ComponentName(this, OuvinteNotificacoes::class.java))
                ?.firstOrNull { it.packageName == pacote }
        }.getOrNull()
        val musica = CategoriaApps.destino(pacote, categoria(pacote), false, CategoriaApps.escolhas(this)[pacote], catsFicha?.get(pacote)) == CruzLogica.Categoria.OUVIR
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

    internal fun menuMidiaCruz(pacote: String, nomeApp: String) {
        val opcoes = listOf(
            Opcao(getString(R.string.cruz_abrir_app, nomeApp)) { abrir(pacote) },
            Opcao(getString(R.string.cruz_tirar_continuar)) {
                Fundo.rodar("midia-esquecer") { MidiaRecente.esquecer(this, pacote); lerContinuarCruz(); ui.post { if (!isDestroyed) atualizarCruz() } }
            })
        menuAberto = Estilo.lista(this, nomeApp, opcoes.map { it.rotulo }) { i -> opcoes[i].acao() }
    }

    /**
     * "Trocar ícone" (18/11/2026): escolhe o pacote (se houver mais de um) e abre a grade dele para este app; "Ícone
     * original" volta ao do app. Pacote de ícones de outro desenvolvedor é do plano Pro.
     */
    internal fun trocarIconeCruz(pacote: String) {
        if (!Planos.permite(this, Planos.Recurso.PLUGINS)) { startActivity(Intent(this, ConhecerProActivity::class.java)); return }
        val pacotes = PacoteIcones.deFora(this)
        val temTroca = CustomIcons.escolha(this, pacote) != null
        if (pacotes.isEmpty() && !temTroca) { AvisoTv.rapido(this, getString(R.string.wp_trocar_icone), getString(R.string.wp_icone_sem_pacote)); return }
        val classe = packageManager.getLeanbackLaunchIntentForPackage(pacote)?.component?.className
        fun abrir(p: String) = startActivity(Intent(this, IconPickerActivity::class.java)
            .putExtra(IconPickerActivity.EXTRA_APP, pacote).putExtra(IconPickerActivity.EXTRA_CLASSE, classe)
            .putExtra(IconPickerActivity.EXTRA_PACOTE, p).putExtra(IconPickerActivity.EXTRA_NOME, nomeDe(pacote)))
        if (pacotes.size == 1 && !temTroca) { abrir(pacotes[0].first); return }
        val nomes = pacotes.map { it.second } + getString(R.string.wp_icone_voltar)
        menuAberto = Estilo.lista(this, getString(R.string.wp_icone_titulo, nomeDe(pacote)), nomes) { i ->
            if (i < pacotes.size) abrir(pacotes[i].first)
            else { CustomIcons.definir(this, pacote, null); recreate() }   // volta a seguir o pacote de todos (ou o do app)
        }
    }

    /** "Mover para…": Assistir, Ouvir, Jogar (com a Central ligada) ou Aplicativos; a atual vem marcada. */
    internal fun moverAppCruz(pacote: String) {
        val eraJogo = JogosTv.ehJogo(this, pacote)
        val atual = CategoriaApps.destino(pacote, categoria(pacote), eraJogo && JogosTv.cardLigado(this), CategoriaApps.escolhas(this)[pacote], catsFicha?.get(pacote))
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
            CategoriaApps.escolher(this, pacote, k, categoria(pacote), eraJogo, catsFicha?.get(pacote))
            // A prateleira escolhida à mão em Todos os apps que fica em outro mundo deixa de valer: o mundo novo manda.
            val prat = prateleirasManuais().toMutableMap()
            if (AppShelf.Shelf.of(prat[pacote])?.let { it.world != k } == true) { prat.remove(pacote); prefsApps().edit().putString("shelves", AppShelf.toJson(prat)).apply() }
            getSharedPreferences(PREFS_CRUZ, MODE_PRIVATE).edit().putString(k.id, "app:$pacote").apply()   // a coluna nova abre nele
            atualizarAposMudanca(null)
        }
    }

    // ------------------------------------------------------------------ onda 3 (CruzOnda3): ambiente, paralaxe, quem vê

    internal var raizCruz: FrameLayout? = null
    internal var ambiente = false
    internal var relogioAmbiente: View? = null
    /** Tecla que tirou do modo ambiente: o "soltar" dela também é engolido (senão o OK abriria o item). */
    internal var engolirTecla = -1
    internal val tarefaAmbiente = Runnable { entrarAmbiente() }







    // ---- "Quem está vendo?" ao ligar a TV
    internal var vigiaTela: android.content.BroadcastReceiver? = null
    internal var apagouEm = 0L
    internal var acendeuEm = 0L
    internal var dormiuMs = 0L
    internal var perguntouNestaVez = true   // começa true: abrir o launcher não é "ligar a TV"
    /** Até quando (elapsedRealtime) a logo ainda entra no onResume depois de a tela acender; 0 = nada pendente. */
    internal var splashPendenteAte = 0L
    internal val tarefaQuemVe = Runnable { talvezPerguntarQuemVe() }



    // ---- "Dormir em 30 min" (Continuar, à noite)



    // ---------------------------------------------------------------- "Todos os apps" (proposta C, 20/11/2026)
    // A tela em prateleiras (AllAppsScreen). Liga com: AppShelf (categoria da Play pela ficha), CategoriaApps (mundo da cruz),
    // Organizacao.ocultos (Escondidos, com PIN), PainelApps.ocultar (Congelados) e a prova do Observador (ObserverTv).

    internal var telaTodos: AllAppsScreen? = null
    /** A categoria de cada ficha no padrão da Play (AppShelf.sheetCats): a cruz, esta tela e o painel usam. */
    internal var catsFicha: Map<String, String>?
        get() = AppShelf.sheetCats
        set(v) { AppShelf.sheetCats = v }
    /** Os escondidos aparecem nesta abertura da tela (o PIN foi certo). Fechar a tela tranca de novo. */
    internal var escondidosLiberados = false
    /** O que custa disco/IPC, lido fora da tela a cada abertura: categoria das fichas, apps de sistema e congelados. */
    internal class DadosTodos(val sistema: Set<String>, val congelados: List<Item>)
    @Volatile internal var dadosTodos: DadosTodos? = null
    internal val bannersTodos = java.util.concurrent.ConcurrentHashMap<String, Drawable>()










    internal val hostTodos = object : AllAppsScreen.Host {
        override fun open(card: AllAppsScreen.Card) = when (card.kind) {
            AllAppsScreen.Kind.LOCK -> liberarEscondidos()
            AllAppsScreen.Kind.FROZEN -> abrirCongelado(card)
            else -> abrir(card.pkg)
        }

        override fun hold(card: AllAppsScreen.Card) = when (card.kind) {
            AllAppsScreen.Kind.LOCK -> liberarEscondidos()
            AllAppsScreen.Kind.FROZEN -> abrirCongelado(card)
            else -> menuTodos(card)
        }

        override fun focused(card: AllAppsScreen.Card) {
            if (card.kind == AllAppsScreen.Kind.LOCK) {
                telaTodos?.showInfo(card, AllAppsScreen.Info(card.name, emptyList(), null, getString(R.string.aa_hint_lock)))
                return
            }
            Fundo.rodar("TodosInfo") {
                val info = runCatching { infoTodos(card) }.getOrNull() ?: return@rodar
                ui.post { if (!isDestroyed) telaTodos?.showInfo(card, info) }
            }
        }

        override fun loadBanner(card: AllAppsScreen.Card, done: (Drawable?) -> Unit) {
            bannersTodos[card.pkg]?.let { done(it); return }
            capas[card.pkg]?.first?.let { b -> android.graphics.drawable.BitmapDrawable(resources, b).also { bannersTodos[card.pkg] = it; done(it) }; return }
            Fundo.rodar("TodosBanner") {
                val d = runCatching { bannerTodos(card.pkg) }.getOrNull()
                if (d != null) bannersTodos[card.pkg] = d
                ui.post { if (!isDestroyed) done(d) }
            }
        }
    }












    /** Teclas da HOME: o ambiente, o "Todos os apps" (Voltar fecha, OK segurado = opções) e ▲ segurado na cruz. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        // Tecla durante a abertura com a logo: a logo sai na hora e a tecla segue para a HOME.
        if (event.action == KeyEvent.ACTION_DOWN && SplashOverlay.showing(this)) SplashOverlay.dismiss(this)
        if (teclaDoAmbiente(event)) return true
        // Voltar no "Todos os apps" volta para a cruz (na cruz, Voltar não faz nada).
        if (todosAbertos && event.keyCode == KeyEvent.KEYCODE_BACK) { if (event.action == KeyEvent.ACTION_UP) fecharTodos(); return true }
        if (todosAbertos && telaTodos?.key(event) == true) return true   // OK curto abre, OK segurado = opções
        if (centralAoSegurarCima(event)) return true
        return super.dispatchKeyEvent(event)
    }




    // ---- Aplicativos da cruz: quantas vezes cada app foi aberto pela HOME (meia-vida de 7 dias) e os fixados no topo.
    // Fica nas preferências "cruz_apps": "f:<pacote>" = "pontos;quando" e "fixados" = pacotes separados por vírgula.







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
                    getSharedPreferences(Prefs.CLIMA, MODE_PRIVATE).edit()
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
        val c = getSharedPreferences(Prefs.CLIMA, MODE_PRIVATE)
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
        val c = getSharedPreferences(Prefs.CLIMA, MODE_PRIVATE)
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

    internal fun dp(valor: Int): Int = (valor * resources.displayMetrics.density + 0.5f).toInt()

    internal fun texto(sp: Float, cor: Int, fonte: Typeface? = null): TextView = TextView(this).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(cor)
        typeface = fonte ?: fonteUi
        // Sobre o céu ou a pintura, uma sombra suave mantém o texto legível em qualquer cor de fundo.
        if (tema != Tema.ESCURO) setShadowLayer(dp(8).toFloat(), 0f, 0f, 0x99000000.toInt())
    }

}
