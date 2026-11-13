package br.paulo.tvlauncher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.SystemClock
import android.util.Log

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

    private var ultimoDesvio = 0L
    private var ultimoDesvioPainel = 0L
    /** Até quando o painel do Google pode aparecer sem ser trocado pelo nosso (só quando NÓS o abrimos: Casa > Google Home). */
    @Volatile private var googleLiberadoAte = 0L

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
     * Chamado quando uma janela aparece: se for a HOME do launcherx, traz a nossa por cima; se for o painel dele
     * (.dashboard.DashboardHandler, aberto pelo sistema, pelo Gemini ou por um app), fecha e abre o nosso no lugar
     * (dono, 11/11/2026: "ocultar o painel do Google afim de mantermos apenas o nosso"). O launcherx continua ativo.
     */
    fun janelaApareceu(ctx: Context, pacote: CharSequence?, classe: CharSequence?) {
        if (pacote?.toString() != LAUNCHERX) return
        if (classe?.toString()?.startsWith("$LAUNCHERX.dashboard.") == true) { trocarPainel(ctx); return }
        if (classe?.toString() != HOME_DELE) return
        val agora = SystemClock.elapsedRealtime()
        if (agora - ultimoDesvio < 1500) return   // trava contra laço, se algo insistir em reabrir a dele
        ultimoDesvio = agora
        Log.i(TAG, "a HOME do launcherx apareceu: trazendo a nossa")
        irParaHome(ctx)
    }

    private fun trocarPainel(ctx: Context) {
        val agora = SystemClock.elapsedRealtime()
        if (agora < googleLiberadoAte) return
        if (agora - ultimoDesvioPainel < 1500) return   // trava contra laço
        ultimoDesvioPainel = agora
        Log.i(TAG, "o painel do Google apareceu: trocando pelo nosso")
        AutorizaShizukuService.instancia?.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK)
        PainelRapidoActivity.abrir(ctx)
    }
}
