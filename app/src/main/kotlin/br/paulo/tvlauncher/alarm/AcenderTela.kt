package br.paulo.tvlauncher.alarm

import android.app.Activity
import android.os.Build
import android.view.WindowManager

/**
 * Acende a tela e mostra a Activity por cima do standby (alarme, lembrete, rotina que acorda a TV).
 * setShowWhenLocked/setTurnScreenOn só existem a partir do Android 8.1 (API 27); o minSdk é 26 (Android 8.0), onde
 * chamá-los derrubava a tela com NoSuchMethodError. Lá vão as flags antigas da janela, que fazem o mesmo.
 */
object AcenderTela {
    fun em(a: Activity) {
        if (Build.VERSION.SDK_INT >= 27) { a.setShowWhenLocked(true); a.setTurnScreenOn(true) }
        else @Suppress("DEPRECATION") a.window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
    }
}
