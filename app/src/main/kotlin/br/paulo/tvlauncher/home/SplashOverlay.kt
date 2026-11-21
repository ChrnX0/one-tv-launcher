package br.paulo.tvlauncher.home

import android.app.Activity
import android.content.Context
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import br.paulo.tvlauncher.Estilo
import br.paulo.tvlauncher.Prefs
import br.paulo.tvlauncher.R
import br.paulo.tvlauncher.settings.Fontes

/**
 * A abertura com a logo Sofaly (18/11/2026): uma camada por cima da HOME, no grafite das telas, com o nome e o slogan
 * no centro. Fica [SplashRules.HOLD_MS] e esmaece. Não pega foco: a HOME por baixo já está pronta, e qualquer tecla
 * tira a logo na hora ([dismiss], chamado pelo dispatchKeyEvent da MainActivity) e segue para a HOME normalmente.
 * Quando aparece: [SplashRules].
 */
object SplashOverlay {
    private const val TAG_VIEW = "sofaly_splash"

    fun enabled(ctx: Context): Boolean =
        ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).getBoolean(SplashRules.PREF, true)

    fun setEnabled(ctx: Context, on: Boolean) {
        ctx.getSharedPreferences(Prefs.LAUNCHER, Context.MODE_PRIVATE).edit().putBoolean(SplashRules.PREF, on).apply()
    }

    /** Põe a logo por cima da [a]. Já aberta: só recomeça a contagem. */
    fun show(a: Activity) {
        val root = a.window?.decorView as? ViewGroup ?: return
        root.findViewWithTag<View>(TAG_VIEW)?.let { root.removeView(it) }
        val layer = FrameLayout(a).apply {
            tag = TAG_VIEW
            background = Estilo.fundoTela(a)
            isClickable = false; isFocusable = false
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }
        val d = a.resources.displayMetrics.density
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
        column.addView(SofalyWordmark(a, 96f * d), LinearLayout.LayoutParams(-2, -2))
        column.addView(TextView(a).apply {
            text = a.getString(R.string.brand_slogan)
            setTextColor(Estilo.TEXTO_2)
            typeface = Fontes.nunito(a, 400)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(-2, -2).apply { topMargin = (14 * d).toInt() })
        layer.addView(column, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        root.addView(layer, FrameLayout.LayoutParams(-1, -1))
        // Animações desligadas no Android ("Remover animações"): some de uma vez, sem esmaecer.
        val semAnim = runCatching {
            android.provider.Settings.Global.getFloat(a.contentResolver, android.provider.Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
        layer.postDelayed({ hide(layer, if (semAnim) 0L else SplashRules.FADE_MS) }, SplashRules.HOLD_MS)
    }

    /** Uma tecla durante a abertura: a logo sai rápido (a tecla segue para a HOME). */
    fun dismiss(a: Activity) {
        val root = a.window?.decorView as? ViewGroup ?: return
        root.findViewWithTag<View>(TAG_VIEW)?.let { hide(it, 150L) }
    }

    fun showing(a: Activity): Boolean = (a.window?.decorView as? ViewGroup)?.findViewWithTag<View>(TAG_VIEW) != null

    private fun hide(v: View, fadeMs: Long) {
        val parent = v.parent as? ViewGroup ?: return
        if (v.getTag(R.id.brand_splash_leaving) == true) return
        v.setTag(R.id.brand_splash_leaving, true)
        if (fadeMs <= 0L) { parent.removeView(v); return }
        v.animate().alpha(0f).setDuration(fadeMs).withLayer().withEndAction { (v.parent as? ViewGroup)?.removeView(v) }.start()
    }
}
