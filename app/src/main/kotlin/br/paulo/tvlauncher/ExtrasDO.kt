package br.paulo.tvlauncher

import android.app.admin.DevicePolicyManager
import android.app.admin.SystemUpdatePolicy
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Poderes do Device Owner que o launcher ainda não usava (pesquisa de 10/11/2026, ideias D e O). Chamado por
 * [PrivilegioDO.aplicar], fora da thread da tela. Cada um é idempotente e respeita a chave em [Extras].
 *
 * - Janela de atualização: li em https://developer.android.com/work/dpc/system-updates. Com a política "windowed",
 *   o sistema só instala atualização dentro da janela (aqui 3h às 5h). NÃO usamos "adiar 30 dias": atrasaria correção
 *   de segurança. Não verificado: se o atualizador do Google TV respeita a política como o de celular.
 * - Travar apps críticos (Android 11+): setUserControlDisabledPackages tira "Forçar parada" e "Limpar dados" das
 *   Configurações para os pacotes listados: o launcher e o app de VPN fixado como "sempre ativa". Ninguém derruba o
 *   filtro da casa sem querer.
 */
object ExtrasDO {
    private const val TAG = "TvLauncher.ExtrasDO"
    const val JANELA_INICIO_MIN = 3 * 60   // 3h00, em minutos desde a meia-noite (hora local)
    const val JANELA_FIM_MIN = 5 * 60      // 5h00

    private fun dpm(ctx: Context) = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    fun aplicar(ctx: Context) {
        if (!PrivilegioDO.somosDonos(ctx)) {
            // Sem dono, a trava vai pelo shell próprio (TravaSemDono): pede ao guardião para agir já, sem esperar 10 min.
            if (TravaSemDono.falta(ctx)) GuardiaoShizuku.garantir(ctx, "trava sem dono")
            return
        }
        janelaOta(ctx, Extras.ligado(ctx, Extras.Chave.JANELA_OTA))
        travarCriticos(ctx, Extras.ligado(ctx, Extras.Chave.TRAVAR_CRITICOS))
        DiarioDns.ajustar(ctx)
        TravaRelogio.ajustar(ctx)   // hora de dormir ligada = relógio travado (ver TravaRelogio.kt)
    }

    /** Liga (janela 3h–5h) ou tira (null = o sistema decide sozinho) a política de atualização. */
    fun janelaOta(ctx: Context, ligar: Boolean) {
        try {
            val atual = dpm(ctx).systemUpdatePolicy
            val jaEsta = atual != null && atual.policyType == SystemUpdatePolicy.TYPE_INSTALL_WINDOWED &&
                atual.installWindowStart == JANELA_INICIO_MIN && atual.installWindowEnd == JANELA_FIM_MIN
            if (ligar && !jaEsta) {
                dpm(ctx).setSystemUpdatePolicy(admin(ctx),
                    SystemUpdatePolicy.createWindowedInstallPolicy(JANELA_INICIO_MIN, JANELA_FIM_MIN))
                Log.i(TAG, "atualização do sistema só entre 3h e 5h")
            } else if (!ligar && atual != null) {
                dpm(ctx).setSystemUpdatePolicy(admin(ctx), null)
                Log.i(TAG, "política de atualização removida")
            }
        } catch (e: Exception) { Log.w(TAG, "janelaOta falhou: $e") }
    }

    /** Há atualização do sistema esperando a janela? (null = não, ou não deu para saber). Usado na tela de extras. */
    fun atualizacaoPendente(ctx: Context): Long? = try {
        if (!PrivilegioDO.somosDonos(ctx)) null else dpm(ctx).getPendingSystemUpdate(admin(ctx))?.receivedTime
    } catch (e: Exception) { null }

    /** Pacotes que não podem ser parados nem limpos pelas Configurações: o launcher e a VPN fixa, se houver. */
    fun pacotesCriticos(nosso: String, vpn: String?): List<String> =
        listOfNotNull(nosso, vpn?.takeIf { it.isNotBlank() && it != nosso })

    fun travarCriticos(ctx: Context, ligar: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        try {
            val lista = if (ligar) pacotesCriticos(ctx.packageName, PrivilegioDO.vpnFixada(ctx)) else emptyList()
            if (dpm(ctx).getUserControlDisabledPackages(admin(ctx)).toSet() != lista.toSet()) {
                dpm(ctx).setUserControlDisabledPackages(admin(ctx), lista)
                Log.i(TAG, "apps travados contra parar/limpar: $lista")
            }
        } catch (e: Exception) { Log.w(TAG, "travarCriticos falhou: $e") }
    }
}
