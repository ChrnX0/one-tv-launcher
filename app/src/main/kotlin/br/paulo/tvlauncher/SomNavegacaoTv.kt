package br.paulo.tvlauncher

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent

/**
 * Som de navegação (10/11/2026, 2ª leva das ideias; FLauncher e LTvLauncher têm): um clique baixo do próprio Android a
 * cada seta ou OK, só com uma tela do launcher na frente (nunca dentro do filme). Desligado por padrão.
 * Quem chama: o serviço de acessibilidade, que vê as teclas e sabe qual app está na frente ([pacoteNaFrente]).
 */
object SomNavegacaoTv {
    @Volatile var pacoteNaFrente: String? = null

    fun tecla(ctx: Context, e: KeyEvent) {
        if (e.action != KeyEvent.ACTION_DOWN || pacoteNaFrente != ctx.packageName) return
        val som = SomNavegacao.som(e.keyCode) ?: return
        if (!Extras.ligado(ctx, Extras.Chave.SOM_NAVEGACAO)) return
        val efeito = when (som) {
            SomNavegacao.Som.CIMA -> AudioManager.FX_FOCUS_NAVIGATION_UP
            SomNavegacao.Som.BAIXO -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
            SomNavegacao.Som.ESQUERDA -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
            SomNavegacao.Som.DIREITA -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
            SomNavegacao.Som.CLIQUE -> AudioManager.FX_KEY_CLICK
        }
        // Volume 0,3: baixo de propósito. O Android ignora o pedido se os "sons do sistema" estiverem desligados.
        ctx.getSystemService(AudioManager::class.java)?.playSoundEffect(efeito, 0.3f)
    }
}
