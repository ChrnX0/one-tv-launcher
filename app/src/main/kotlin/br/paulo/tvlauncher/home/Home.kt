package br.paulo.tvlauncher.home

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import br.paulo.tvlauncher.AutorizaShizukuService
import br.paulo.tvlauncher.AvisoTv
import br.paulo.tvlauncher.MainActivity
import br.paulo.tvlauncher.PainelRapidoActivity
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.privilege.JanelaNaFrente

/**
 * Manter a NOSSA HOME com o launcher do Google (launcherx) ativo (04/11/2026).
 *
 * Por que o launcherx fica ativo: o Gemini mostra a resposta da voz em launcherx/.gemini.chat.ChatActivity, e a página
 * de um filme da busca também é dele. Com ele desativado ou oculto, a voz dava "o Google Assistente está com problema".
 *
 * Por que precisamos disto: a HomeActivity dele declara priority=2 (app de sistema privilegiado), e no resolvedor do
 * Android a prioridade maior vence antes de qualquer preferência — a persistente do Device Owner e o papel de HOME
 * não seguram. Então:
 *  - o botão HOME é tratado pelo serviço de acessibilidade (BotoesControle.tratarHome);
 *  - quem mais abrir a HOME (boot, um app, o sistema) cai na HomeActivity dele, e o serviço de acessibilidade vê a
 *    janela aparecer e traz a nossa por cima (AutorizaShizukuService.onAccessibilityEvent).
 * Com o launcherx desativado nada disto age: a HOME do sistema já é a nossa.
 */
object Home {
    private const val TAG = "TvLauncher.Home"
    const val LAUNCHERX = "com.google.android.apps.tv.launcherx"
    private const val HOME_DELE = "$LAUNCHERX.home.HomeActivity"
    const val SEGURAR_HOME_MS = 700L

    private var ultimoDesvioPainel = 0L
    /** Até quando o painel do Google pode aparecer sem ser trocado pelo nosso (só quando NÓS o abrimos: Casa > Google Home). */
    @Volatile private var googleLiberadoAte = 0L
    /** Até quando, ao aparecer o painel do Google, tocamos sozinhos no tile "Google Home" dele (abrirGoogleHome). */
    @Volatile private var tocarCasaAte = 0L
    private val principal = Handler(Looper.getMainLooper())

    fun launcherxAtivo(ctx: Context): Boolean = try {
        val ai = ctx.packageManager.getApplicationInfo(LAUNCHERX, 0)
        ai.enabled && ctx.packageManager.getApplicationEnabledSetting(LAUNCHERX) !in
            setOf(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
    } catch (e: Exception) { false }   // não instalado ou oculto

    /** Abre a nossa HOME como o sistema abriria (onNewIntent com CATEGORY_HOME volta o foco ao primeiro app). */
    fun irParaHome(ctx: Context) {
        try {
            ctx.startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .setClass(ctx, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED))
        } catch (e: Exception) { Log.w(TAG, "não abriu a nossa HOME: $e") }
    }

    /**
     * O painel do Google TV, de propósito (hoje só a tela Casa, para os aparelhos do Google Home). O HOME segurado e a
     * bolinha da HOME abrem o NOSSO painel (PainelRapidoActivity, 11/11/2026); por isso aqui o vigia é avisado para não
     * trocar este pelo nosso.
     * Abrir a DashboardActivity direto exige android.permission.STATUS_BAR_SERVICE (de sistema; medido: "Permission
     * Denial ... requires android.permission.STATUS_BAR_SERVICE"). O caminho certo é pedir ao sistema que abra as
     * notificações (ação global da acessibilidade): no Google TV o SystemUI repassa isso ao painel do launcherx.
     * O intent direto fica de reserva (funciona se um dia tivermos a permissão; hoje só registra a falha).
     */
    fun abrirPainelGoogle(ctx: Context) {
        googleLiberadoAte = SystemClock.elapsedRealtime() + 5_000
        val servico = AutorizaShizukuService.instancia
        if (servico != null && servico.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)) return
        try {
            ctx.startActivity(Intent("android.app.action.TOGGLE_NOTIFICATION_HANDLER_PANEL").setPackage(LAUNCHERX)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: Exception) { Log.w(TAG, "não abriu o painel do Google: $e") }
    }

    /**
     * Abre direto a tela da casa do Google Home (luzes, favoritos), que só existe DENTRO do painel do launcherx: não há app
     * Google Home na TV nem intent exportado para ela (medido numa TV em 13/11/2026: só OPEN/TOGGLE_NOTIFICATION_HANDLER_PANEL,
     * que exigem STATUS_BAR_SERVICE). Então abre o painel e, quando ele aparece, a acessibilidade toca no tile "Google Home".
     * Se o tile não existir (conta sem casa no Google Home), fica o painel do Google aberto, como antes.
     */
    fun abrirGoogleHome(ctx: Context) {
        tocarCasaAte = SystemClock.elapsedRealtime() + 5_000
        abrirPainelGoogle(ctx)
    }

    /** Procura o tile "Google Home" no painel do Google e toca nele; tenta de novo enquanto o painel monta (até ~2,7 s). */
    private fun tocarTileCasa(tentativa: Int) {
        if (SystemClock.elapsedRealtime() > tocarCasaAte) return
        val servico = AutorizaShizukuService.instancia ?: return
        val raiz = try { servico.rootInActiveWindow } catch (_: Exception) { null }
        var no: AccessibilityNodeInfo? = raiz?.findAccessibilityNodeInfosByText("Google Home")?.firstOrNull()
        while (no != null && !no.isClickable) no = no.parent   // o texto fica dentro do tile; quem recebe o clique é ele
        if (no != null && no.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            tocarCasaAte = 0L
            marcarGoogleHome(servico, true)
            Log.i(TAG, "tile Google Home tocado no painel do Google")
            return
        }
        if (tentativa < 8) principal.postDelayed({ tocarTileCasa(tentativa + 1) }, 300)
        else {
            // Conta sem casa no Google Home (Sala, 17/11/2026): o painel do Google abria sem luz nem câmera e ninguém
            // dizia por quê. Agora avisa, e o cartão da tela Casa passa a dizer isso (tocar nele tenta de novo).
            Log.i(TAG, "painel do Google sem o tile Google Home (conta sem casa?)")
            tocarCasaAte = 0L
            marcarGoogleHome(servico, false)
            AvisoTv.rapido(servico, servico.getString(R.string.casa_google_home), servico.getString(R.string.casa_google_home_vazio), 6)
        }
    }

    private fun marcarGoogleHome(ctx: Context, tem: Boolean) =
        ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).edit().putBoolean("google_home_sem_casa", !tem).apply()

    /** O painel do Google já foi aberto e não tinha o Google Home (conta sem casa)? */
    fun googleHomeSemCasa(ctx: Context): Boolean =
        ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).getBoolean("google_home_sem_casa", false)

    /**
     * Chamado quando uma janela aparece: se for a HOME do launcherx, traz a nossa por cima; se for o painel dele
     * (.dashboard.DashboardHandler, aberto pelo sistema, pelo Gemini ou por um app), fecha e abre o nosso no lugar
     * (dono, 11/11/2026: "ocultar o painel do Google afim de mantermos apenas o nosso"). O launcherx continua ativo.
     */
    fun janelaApareceu(ctx: Context, pacote: CharSequence?, classe: CharSequence?) {
        if (pacote?.toString() != LAUNCHERX) return
        if (classe?.toString()?.startsWith("$LAUNCHERX.dashboard.") == true) { trocarPainel(ctx); return }
        if (classe?.toString() != HOME_DELE) return
        desviar(ctx.applicationContext)
    }

    /** Hora dos desvios recentes (trava contra laço). Só a thread principal mexe aqui. */
    private val desvios = ArrayDeque<Long>()
    private var conferenciaMarcada = false

    /**
     * Traz a nossa HOME por cima da do launcherx, respeitando a trava contra laço ([HomeDesvio.decidir]).
     *
     * Bug de 14/11/2026 (Sala, v457287 e v457301): a instalação de uma versão nova mata a nossa MainActivity; ao acordar,
     * a TV mostra a HOME do launcherx, nós trazemos a nossa e, ~1 s depois, o sistema abre a dele de novo (logcat:
     * START launcherx HomeActivity "from uid 0" 0,8 s e 1,1 s depois da nossa). Antes, a 2ª aparição caía na trava de
     * 1,5 s e era só ignorada: a do Google ficava na frente e, como ela já estava no topo, os HOMEs seguintes não abriam
     * janela nova (nenhum evento de acessibilidade) e nada mais a trocava. Agora a 2ª aparição marca uma conferência
     * para quando a trava vencer: se a do Google ainda estiver na frente, troca.
     */
    private fun desviar(ctx: Context) {
        val agora = SystemClock.elapsedRealtime()
        while (desvios.isNotEmpty() && agora - desvios.first() > HomeDesvio.JANELA_LACO_MS) desvios.removeFirst()
        when (val d = HomeDesvio.decidir(agora, desvios.lastOrNull(), desvios.size)) {
            HomeDesvio.Desvio.Agora -> {
                desvios.addLast(agora)
                Log.i(TAG, "a HOME do launcherx apareceu: trazendo a nossa")
                irParaHome(ctx)
            }
            is HomeDesvio.Desvio.Depois -> if (!conferenciaMarcada) {
                conferenciaMarcada = true
                principal.postDelayed({
                    conferenciaMarcada = false
                    // Só se a do Google ainda é a última tela que apareceu (a pessoa pode ter aberto um app no meio).
                    if (JanelaNaFrente.pacote == LAUNCHERX && JanelaNaFrente.classe == HOME_DELE) desviar(ctx)
                }, d.ms)
            }
            HomeDesvio.Desvio.Desiste -> Log.w(TAG, "a HOME do launcherx insiste em voltar: desisto por ${HomeDesvio.JANELA_LACO_MS / 1000} s (trava contra laço)")
        }
    }

    private fun trocarPainel(ctx: Context) {
        val agora = SystemClock.elapsedRealtime()
        if (agora < googleLiberadoAte) {
            // Enquanto a pessoa navega no painel que NÓS abrimos (ex.: a tela da casa), cada janela nova renova a liberação.
            googleLiberadoAte = agora + 120_000
            if (agora < tocarCasaAte) principal.postDelayed({ tocarTileCasa(0) }, 300)
            return
        }
        if (agora - ultimoDesvioPainel < 1500) return   // trava contra laço
        ultimoDesvioPainel = agora
        Log.i(TAG, "o painel do Google apareceu: trocando pelo nosso")
        AutorizaShizukuService.instancia?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        PainelRapidoActivity.abrir(ctx)
    }
}

/** Regra pura da trava contra laço de [Home.desviar], fora do Home (que cria um Handler e não carrega na JVM dos testes). */
object HomeDesvio {
    const val INTERVALO_MIN_MS = 1500L
    const val JANELA_LACO_MS = 30_000L
    const val MAX_DESVIOS_NA_JANELA = 6

    sealed class Desvio {
        object Agora : Desvio()
        data class Depois(val ms: Long) : Desvio()
        object Desiste : Desvio()
    }

    /**
     * Regra pura (HomeDesvioTest). [ultimo] = hora do último desvio (null = nenhum na janela); [naJanela] = quantos nos
     * últimos [JANELA_LACO_MS]. Mais de [MAX_DESVIOS_NA_JANELA] = algo reabre a dele sem parar: desiste (sem laço).
     * Desvio recente demais: não troca já (a nossa pode ainda estar subindo), confere quando passar o intervalo.
     */
    fun decidir(agora: Long, ultimo: Long?, naJanela: Int): Desvio = when {
        naJanela >= MAX_DESVIOS_NA_JANELA -> Desvio.Desiste
        ultimo != null && agora - ultimo < INTERVALO_MIN_MS -> Desvio.Depois(INTERVALO_MIN_MS - (agora - ultimo))
        else -> Desvio.Agora
    }

    enum class AposAtualizar { TROCAR, ESPERAR, DEIXAR }
    /** Quantas vezes o AtualizadoReceiver confere (a cada 0,7 s): ~8 s, abaixo dos 10 s do goAsync. */
    const val TENTATIVAS_APOS_ATUALIZAR = 11

    /**
     * Regra pura (HomeDesvioTest): logo depois de instalar uma versão nova com a tela acesa, o que fazer com quem está na
     * frente. [servicoLigado] = a acessibilidade já religou; [naFrente] = pacote da janela ativa (null = ainda sem janela).
     * launcherx na frente = a HOME dele subiu porque a nossa morreu na instalação: troca. Outro app = alguém usando: deixa.
     * Sem serviço ou sem janela ainda: espera, até [TENTATIVAS_APOS_ATUALIZAR].
     */
    fun depoisDeAtualizar(servicoLigado: Boolean, naFrente: String?, tentativa: Int): AposAtualizar = when {
        servicoLigado && naFrente == Home.LAUNCHERX -> AposAtualizar.TROCAR
        servicoLigado && naFrente != null -> AposAtualizar.DEIXAR
        tentativa + 1 >= TENTATIVAS_APOS_ATUALIZAR -> AposAtualizar.DEIXAR
        else -> AposAtualizar.ESPERAR
    }
}
