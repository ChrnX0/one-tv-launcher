package br.paulo.tvlauncher.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import br.paulo.tvlauncher.EntradasTv
import br.paulo.tvlauncher.MainActivity
import br.paulo.tvlauncher.family.Perfil
import br.paulo.tvlauncher.privilege.JanelaNaFrente
import br.paulo.tvlauncher.settings.Extras

/**
 * "Ao ligar a TV" (21/11/2026, item 5 do plano para passar o Projectivy).
 *
 * Porquê: antes era uma constante de build (Config.ABRIR_AO_LIGAR), invisível para quem usa. Tem casa em que a TV é
 * "a TV do canal" (o pai liga e quer o app de TV ao vivo direto) e casa em que é a TV do videogame.
 * Para quê: ligar a TV e já cair no app certo, sem passar pela tela inicial.
 * Como: uma escolha (tela inicial / último app / um app / uma entrada HDMI) que vale no BOOT e, se quiser, também ao
 * ACORDAR do standby (o Chromecast quase nunca reinicia: sem isso a opção quase nunca agiria). A escolha é POR PERFIL
 * (Perfil.ativa): cada conta pode ter a sua, e quem não escolheu herda a da TV. Por HORÁRIO já existe: Automações com
 * o gatilho "TV ligou" e "Só se" (Gatilhos.kt); a tela de opções aponta para lá em vez de duplicar a regra.
 *
 * Freios (puro em [decidir], testado em StartupAppTest): só age se a frente for a NOSSA tela inicial (ou o launcher do
 * Google, que às vezes aparece por um instante ao acordar). Se a TV acordou por um Cast, pelo despertador (a tela do
 * alarme é nossa mas não é a MainActivity) ou já está num app, não rouba a tela.
 */
object StartupApp {
    private const val TAG = "TvLauncher.Startup"

    /** Valores especiais da escolha (o resto é um pacote ou a chave "entrada:<id>" das entradas HDMI). */
    const val HOME = ""
    const val LAST = "@last"

    const val KEY = "startup_app"                 // escolha da TV (vale para quem não tem a sua)
    private const val KEY_PROFILE = "startup_app@" // + conta: escolha do perfil
    const val KEY_WAKE = "startup_app_wake"       // também ao acordar do standby (padrão: sim)
    const val KEY_DELAY = "startup_app_delay"     // segundos de espera no boot
    private const val KEY_LAST = "startup_last_app" // último app que estava na frente quando a tela apagou

    val DELAYS = listOf(0, 5, 15, 30)
    private const val LAUNCHERX = "com.google.android.apps.tv.launcherx"

    // ------------------------------------------------------------------ escolha (por perfil, com herança da TV)

    fun choiceForTv(ctx: Context): String = Extras.texto(ctx, KEY, HOME)

    /** Escolha do perfil ativo; null = o perfil não escolheu (herda a da TV). */
    fun choiceForProfile(ctx: Context, conta: String? = Perfil.ativa(ctx)): String? =
        conta?.let { Extras.texto(ctx, KEY_PROFILE + it, "\u0000").takeIf { v -> v != "\u0000" } }

    fun choice(ctx: Context): String = choiceForProfile(ctx) ?: choiceForTv(ctx)

    fun setChoice(ctx: Context, valor: String, soPerfil: Boolean) {
        val conta = Perfil.ativa(ctx)
        if (soPerfil && conta != null) Extras.definirTexto(ctx, KEY_PROFILE + conta, valor)
        else Extras.definirTexto(ctx, KEY, valor)
    }

    /** Tira a escolha própria do perfil: volta a seguir a da TV. */
    fun clearProfile(ctx: Context) {
        val conta = Perfil.ativa(ctx) ?: return
        Extras.prefs(ctx).edit().remove(KEY_PROFILE + conta).apply()
    }

    fun onWake(ctx: Context) = Extras.prefs(ctx).getBoolean(KEY_WAKE, true)
    fun setOnWake(ctx: Context, sim: Boolean) = Extras.prefs(ctx).edit().putBoolean(KEY_WAKE, sim).apply()
    fun delaySeconds(ctx: Context) = Extras.numero(ctx, KEY_DELAY, 0).takeIf { it in DELAYS } ?: 0
    fun nextDelay(atual: Int) = DELAYS[(DELAYS.indexOf(atual) + 1).mod(DELAYS.size)]

    // ------------------------------------------------------------------ regra pura

    /**
     * O que abrir agora, ou null para não fazer nada.
     * [escolha]: HOME, LAST, pacote ou "entrada:<id>". [ultimo]: o último app guardado. [frentePacote]/[frenteClasse]:
     * quem está na frente (JanelaNaFrente). [nosso]: o nosso pacote.
     */
    fun decidir(escolha: String, ultimo: String?, frentePacote: String?, frenteClasse: String?, nosso: String): String? {
        val alvo = when (escolha) {
            HOME -> return null
            LAST -> ultimo?.takeIf { it.isNotBlank() && it != nosso && it != LAUNCHERX } ?: return null
            else -> escolha
        }
        val nossaHome = frentePacote == nosso && (frenteClasse == null || frenteClasse == MAIN)
        val homeDoGoogle = frentePacote == LAUNCHERX
        if (frentePacote != null && !nossaHome && !homeDoGoogle) return null   // Cast, alarme, app aberto: não rouba
        if (alvo == frentePacote) return null
        return alvo
    }

    /** Guarda o app da frente quando a tela apaga (para "Último app"). Puro: diz o que gravar, ou null. */
    fun lembrarUltimo(frentePacote: String?, nosso: String): String? =
        frentePacote?.takeIf { it != nosso && it != LAUNCHERX && it != "com.android.systemui" && it != "android" &&
            !it.startsWith("com.android.tv.settings") }

    private val MAIN = MainActivity::class.java.name

    // ------------------------------------------------------------------ Android

    private var iniciado = false
    private val ui by lazy { Handler(Looper.getMainLooper()) }

    /** Liga o ouvinte de tela acesa/apagada. Chamado pelo serviço de acessibilidade, como os Gatilhos; repetir não faz nada. */
    fun iniciar(ctx: Context) {
        if (iniciado) return
        iniciado = true
        val app = ctx.applicationContext
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                when (i.action) {
                    Intent.ACTION_SCREEN_OFF -> lembrarUltimo(JanelaNaFrente.pacote, app.packageName)?.let {
                        Extras.definirTexto(app, KEY_LAST, it)
                    }
                    // 1,5 s: o sistema ainda está pondo a última tela na frente; decidir antes disso erra quem está lá.
                    Intent.ACTION_SCREEN_ON -> if (onWake(app)) ui.postDelayed({ talvezAbrir(app, "acordou") }, 1500)
                }
            }
        }
        val f = IntentFilter().apply { addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF) }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED) else app.registerReceiver(r, f)
        }.onFailure { Log.w(TAG, "tela: $it") }
    }

    /** No boot (BootReceiver): espera o atraso escolhido (mínimo 3 s, a HOME precisa subir) e decide. */
    fun aoReiniciar(ctx: Context) {
        val app = ctx.applicationContext
        if (choice(app) == HOME) return
        ui.postDelayed({ talvezAbrir(app, "boot") }, maxOf(3, delaySeconds(app)) * 1000L)
    }

    fun talvezAbrir(ctx: Context, motivo: String) {
        val alvo = decidir(choice(ctx), Extras.texto(ctx, KEY_LAST, ""), JanelaNaFrente.pacote, JanelaNaFrente.classe,
            ctx.packageName) ?: return
        Log.i(TAG, "$motivo: abrindo $alvo")
        runCatching { abrirAlvo(ctx, alvo) }.onFailure { Log.w(TAG, "não abriu $alvo: $it") }
    }

    private fun abrirAlvo(ctx: Context, alvo: String) {
        if (EntradasTv.eh(alvo)) { EntradasTv.abrir(ctx, alvo); return }
        val i = ctx.packageManager.getLeanbackLaunchIntentForPackage(alvo) ?: ctx.packageManager.getLaunchIntentForPackage(alvo)
        if (i == null) { Log.w(TAG, "$alvo não está instalado"); return }
        ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
