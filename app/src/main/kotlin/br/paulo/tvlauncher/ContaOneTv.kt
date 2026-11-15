package br.paulo.tvlauncher

import android.accounts.AbstractAccountAuthenticator
import android.accounts.Account
import android.accounts.AccountAuthenticatorResponse
import android.accounts.AccountManager
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
import android.util.Log

/**
 * Caminho 2 para virar Device Owner num Chromecast/Google TV COM conta Google, sem apagar nada (15/11/2026, aprovado pelo
 * dono).
 *
 * POR QUE PRECISA (li o Android 14, DevicePolicyManagerService.hasIncompatibleAccountsOrNonAdbNoLock): o `dpm
 * set-device-owner` recusa quando há "contas incompatíveis". Para cada conta no aparelho o Android pergunta ao
 * autenticador dela se ela aceita Device Owner: chama AccountManager.hasFeatures com a marca
 * "android.account.DEVICE_OR_PROFILE_OWNER_ALLOWED". A conta Google responde NÃO (por isso bloqueia). O
 * [VirarDono] (caminho 1, jeito Hail) contorna desligando o Google por alguns segundos, para a conta sumir da lista.
 * Em TVs onde o Google não desliga, ou a lista não esvazia a tempo, aquele caminho falha.
 *
 * ESTE caminho não mexe no Google. Ele registra um TIPO DE CONTA PRÓPRIO, "One TV" ([TIPO], que NÃO é "com.google" —
 * nada aqui finge ser o Google), cujo autenticador responde SIM à marca de Device Owner. A pessoa, no teste, TROCA a
 * conta Google pela conta One TV só durante o `dpm` (tira a conta Google em Config > Contas, adiciona a One TV, roda o
 * comando, apaga a One TV e recoloca a conta Google). Com só a conta One TV presente, a lista fica "compatível" e o
 * comando passa.
 *
 * TRANSPARÊNCIA (Play): o tipo é claramente do nosso app, o autenticador não guarda senha nem fala com servidor nenhum,
 * e por padrão o serviço vem DESLIGADO de fábrica ([enabled=false] no manifesto). Só liga quando a pessoa pede o teste
 * ([ligar]), e há um desligamento de segurança automático ([MINUTOS_SEGURANCA]).
 *
 * NÃO TESTADO NA TV: primeiro teste será no Chromecast HD de teste, nunca na Sala.
 */
object ContaOneTv {
    private const val TAG = "TvLauncher.ContaOneTv"

    /** O tipo de conta. NUNCA "com.google": um autenticador falso do Google ensinaria a burlar DO em qualquer Android. */
    const val TIPO = "br.paulo.tvlauncher.conta"

    /** O nome que aparece na conta (Config > Contas). */
    const val NOME = "One TV"

    /** As marcas que o Android 14 usa para decidir se uma conta aceita Device Owner (valores @hide do DevicePolicyManager). */
    const val PERMITE = "android.account.DEVICE_OR_PROFILE_OWNER_ALLOWED"
    const val PROIBE = "android.account.DEVICE_OR_PROFILE_OWNER_DISALLOWED"

    /** Desligamento de segurança: se a pessoa esquecer o teste, o autenticador e a conta somem sozinhos depois disto. */
    const val MINUTOS_SEGURANCA = 15

    private fun servico(ctx: Context) = ComponentName(ctx, ServicoContaOneTv::class.java)

    /**
     * A resposta do autenticador à pergunta do Android "esta conta aceita Device Owner?". PURA (teste JVM ContaOneTvTest):
     * só responde SIM quando a única marca perguntada é [PERMITE]. Qualquer outra marca (inclusive [PROIBE]) → NÃO.
     */
    fun aceitaDono(marcas: Array<out String>?): Boolean =
        !marcas.isNullOrEmpty() && marcas.all { it == PERMITE }

    /** O serviço do autenticador está ligado agora? */
    fun ativo(ctx: Context): Boolean =
        ctx.packageManager.getComponentEnabledSetting(servico(ctx)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    /** Existe uma conta One TV no aparelho? */
    fun temConta(ctx: Context): Boolean =
        runCatching { AccountManager.get(ctx).getAccountsByType(TIPO).isNotEmpty() }.getOrDefault(false)

    /**
     * Liga (true) ou desliga (false) o caminho da conta One TV. Ligar: habilita o serviço do autenticador (o próprio app
     * habilita o próprio componente — o shell não consegue, "Shell cannot change component state") e cria a conta One TV.
     * Desligar: apaga a conta e desabilita o serviço. Agenda o desligamento de segurança quando liga.
     *
     * Devolve uma linha de estado para o adb. BLOQUEIA pouco (AccountManager é local); roda fora da thread da tela por via
     * das dúvidas.
     */
    fun ligar(ctx: Context, ligar: Boolean): String {
        val app = ctx.applicationContext
        val am = AccountManager.get(app)
        app.packageManager.setComponentEnabledSetting(
            servico(app),
            if (ligar) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        if (ligar) {
            val conta = Account(NOME, TIPO)
            val criou = runCatching { am.addAccountExplicitly(conta, null, null) }.getOrDefault(false)
            agendarSeguranca(app)
            Log.i(TAG, "conta One TV ligada, conta criada=$criou")
            return "ligada=true conta=${temConta(app)} (desliga sozinha em ${MINUTOS_SEGURANCA} min)"
        } else {
            for (c in runCatching { am.getAccountsByType(TIPO) }.getOrDefault(emptyArray())) {
                runCatching { am.removeAccountExplicitly(c) }
            }
            seguranca?.let { android.os.Handler(android.os.Looper.getMainLooper()).removeCallbacks(it) }
            seguranca = null
            Log.i(TAG, "conta One TV desligada")
            return "ligada=false conta=${temConta(app)}"
        }
    }

    private var seguranca: Runnable? = null

    /**
     * Desligamento de segurança: o launcher é um processo persistente durante o teste, então um Handler basta (se o
     * processo morrer antes, sobra só um tipo de conta local inócuo, que o próximo [ligar] false limpa). Evita um
     * receiver e um alarme exato só para isto.
     */
    private fun agendarSeguranca(ctx: Context) {
        val h = android.os.Handler(android.os.Looper.getMainLooper())
        seguranca?.let { h.removeCallbacks(it) }
        val r = Runnable { Fundo.rodar { if (ativo(ctx)) ligar(ctx, false) } }
        seguranca = r
        h.postDelayed(r, MINUTOS_SEGURANCA * 60_000L)
    }
}

/** O serviço que entrega o autenticador ao Android. DESLIGADO por padrão no manifesto; [ContaOneTv.ligar] o liga. */
class ServicoContaOneTv : Service() {
    private val autenticador by lazy { AutenticadorOneTv(this) }
    override fun onBind(intent: Intent?): IBinder = autenticador.iBinder
}

/**
 * O autenticador da conta One TV. Só existe para responder à pergunta de Device Owner; não tem login, senha nem token.
 */
class AutenticadorOneTv(ctx: Context) : AbstractAccountAuthenticator(ctx) {

    /** A pergunta que importa: "esta conta aceita Device Owner?". Responde pela regra pura de [ContaOneTv.aceitaDono]. */
    override fun hasFeatures(response: AccountAuthenticatorResponse?, account: Account?, features: Array<out String>?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, ContaOneTv.aceitaDono(features)) }

    /** A conta é criada direto por addAccountExplicitly, então aqui não há tela de login: devolve vazio. */
    override fun addAccount(response: AccountAuthenticatorResponse?, accountType: String?, authTokenType: String?,
                            requiredFeatures: Array<out String>?, options: Bundle?): Bundle = Bundle()

    override fun getAccountRemovalAllowed(response: AccountAuthenticatorResponse?, account: Account?): Bundle =
        Bundle().apply { putBoolean(AccountManager.KEY_BOOLEAN_RESULT, true) }

    override fun editProperties(response: AccountAuthenticatorResponse?, accountType: String?): Bundle = Bundle()
    override fun confirmCredentials(response: AccountAuthenticatorResponse?, account: Account?, options: Bundle?): Bundle = Bundle()
    override fun getAuthToken(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle = Bundle()
    override fun getAuthTokenLabel(authTokenType: String?): String = ContaOneTv.NOME
    override fun updateCredentials(response: AccountAuthenticatorResponse?, account: Account?, authTokenType: String?, options: Bundle?): Bundle = Bundle()
}
