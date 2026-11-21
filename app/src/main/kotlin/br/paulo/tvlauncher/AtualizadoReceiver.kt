package br.paulo.tvlauncher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import br.paulo.tvlauncher.home.Home
import br.paulo.tvlauncher.home.HomeDesvio

/**
 * Logo depois de uma versão nova do launcher ser instalada (MY_PACKAGE_REPLACED, 14/11/2026).
 *
 * A instalação mata o processo E a tarefa da nossa MainActivity ("stop br.paulo.tvlauncher due to installPackageLI"),
 * e quem fica no topo da pilha da HOME é a do launcherx.
 *  - Tela apagada (a atualização sozinha é de madrugada): a nossa volta para o topo já na instalação; a TV acorda nela.
 *  - Tela acesa (v457540, 14/11 14:44, instalada logo depois de sair do protetor): a do Google subiu na frente com o
 *    nosso processo morto. O vigia da acessibilidade (Home.janelaApareceu) não viu a janela aparecer, porque o serviço
 *    só religa uns segundos depois, e a TV ficou na tela do Google. Agora, assim que o serviço religa, conferimos quem
 *    está na frente: se for o launcherx, trazemos a nossa; se for outro app (alguém assistindo), não mexemos.
 */
class AtualizadoReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (!Home.launcherxAtivo(ctx)) return   // sem o launcherx a HOME do sistema já é a nossa
        val app = ctx.applicationContext
        val acesa = app.getSystemService(PowerManager::class.java)?.isInteractive ?: true
        Log.i(TAG, "versão nova instalada (tela ${if (acesa) "acesa: confere quem está na frente" else "apagada: nossa HOME de volta ao topo"})")
        if (!acesa) { Home.irParaHome(app); return }
        val fim = goAsync()   // segura o receiver enquanto a acessibilidade religa (bem menos que os 10 s do limite)
        conferir(app, 0) { fim.finish() }
    }

    private fun conferir(ctx: Context, tentativa: Int, pronto: () -> Unit) {
        val servico = AutorizaShizukuService.instancia
        val naFrente = servico?.let { s -> try { s.rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null } }
        when (HomeDesvio.depoisDeAtualizar(servico != null, naFrente, tentativa)) {
            HomeDesvio.AposAtualizar.TROCAR -> {
                Log.i(TAG, "depois da instalação a HOME do launcherx ficou na frente: trazendo a nossa")
                Home.irParaHome(ctx); pronto()
            }
            HomeDesvio.AposAtualizar.ESPERAR -> principal.postDelayed({ conferir(ctx, tentativa + 1, pronto) }, ESPERA_MS)
            HomeDesvio.AposAtualizar.DEIXAR -> { Log.i(TAG, "depois da instalação: na frente está ${naFrente ?: "?"}, não mexe"); pronto() }
        }
    }

    companion object {
        private const val TAG = "TvLauncher.Home"
        private const val ESPERA_MS = 700L
        private val principal = Handler(Looper.getMainLooper())
    }
}
