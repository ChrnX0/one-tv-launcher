package br.paulo.tvlauncher

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * Trava do launcher contra "Forçar parada" e "Desinstalar" SEM Device Owner (12/11/2026, pedido do dono: "travar apps é
 * importante").
 *
 * Com Device Owner, o ExtrasDO usa setUserControlDisabledPackages. Sem ele, o caminho é ser ADMINISTRADOR DO APARELHO
 * comum (o nível de baixo, o mesmo de apps de "encontrar meu aparelho"). Li no código do TvSettings do Android 14:
 *  - ForceStopPreference.refresh: se o app tem administrador ativo (packageHasActiveAdmins), o botão "Forçar parada" SOME;
 *  - UninstallPreference: o "Desinstalar" manda desativar o administrador primeiro.
 * Quem ativa é o shell próprio: `dpm set-active-admin` exige MANAGE_DEVICE_ADMINS, e o shell desta TV tem (conferido em
 * 12/11/2026 com dumpsys package com.android.shell, só leitura). O "Limpar dados" é travado por outro caminho, sem
 * shell: GuardarDadosActivity.
 *
 * Frágil onde: a pessoa pode desativar o administrador em Configurações > Privacidade/Segurança > Apps de administração;
 * a autocura (GuardiaoShizuku, a cada 10 min) ativa de novo. Não verificado na TV: o Google TV usa o TvSettings
 * "TwoPanel" de sistema; se a Google mudou esse trecho, o botão continua lá. O adb (`am force-stop`, `pm clear`) não
 * passa pelas Configurações e segue funcionando: é o caminho do dono.
 *
 * Com Device Owner nada daqui é preciso (o dono já é administrador ativo); a chave TRAVAR_CRITICOS desligada para de
 * reativar. Nunca chamamos removeActiveAdmin: no aparelho com dono, isso mexeria no próprio Device Owner.
 */
object TravaSemDono {
    private fun admin(ctx: Context) = ComponentName(ctx, AdminReceiver::class.java)

    /** Falta virar administrador? Só quando a chave está ligada e o launcher ainda não é administrador ativo. */
    fun falta(ctx: Context): Boolean = try {
        Extras.ligado(ctx, Extras.Chave.TRAVAR_CRITICOS) &&
            !(ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager).isAdminActive(admin(ctx))
    } catch (e: Exception) { false }

    /** O comando do shell. O usuário Android vem do uid do processo (uid = usuário × 100000 + app). */
    fun comando(ctx: Context): String =
        montar(ctx.packageName, AdminReceiver::class.java.name, android.os.Process.myUid() / 100_000)

    /** Puro, para o teste no PC (TravaSemDonoTest). */
    fun montar(pacote: String, classe: String, usuario: Int): String =
        "dpm set-active-admin --user $usuario $pacote/$classe"
}
