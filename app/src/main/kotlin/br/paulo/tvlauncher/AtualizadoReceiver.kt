package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

/**
 * Logo depois de uma versão nova do launcher ser instalada (MY_PACKAGE_REPLACED, 14/11/2026).
 *
 * A instalação mata o processo E a tarefa da nossa MainActivity ("stop br.paulo.tvlauncher due to installPackageLI").
 * Com a TV dormindo (a atualização sozinha é de madrugada), quem fica no topo é a HOME do launcherx; de manhã a TV
 * acordava nela, e a troca pela nossa dependia só do vigia da acessibilidade (ver Home.desviar). Aqui a nossa volta
 * para o topo já na instalação, com a tela apagada: a TV acorda direto nela.
 * Com a tela acesa não faz nada: alguém pode estar assistindo (e aí o vigia da acessibilidade cuida da HOME).
 */
class AtualizadoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val acesa = ctx.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        Log.i("TvLauncher.Home", "versão nova instalada (tela ${if (acesa) "acesa: não mexe" else "apagada: nossa HOME de volta ao topo"})")
        if (!acesa && Home.launcherxAtivo(ctx)) Home.irParaHome(ctx)
    }
}
