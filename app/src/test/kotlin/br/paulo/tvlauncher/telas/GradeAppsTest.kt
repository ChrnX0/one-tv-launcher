package br.paulo.tvlauncher.telas

import br.paulo.tvlauncher.GradeApps
import org.robolectric.annotation.ConscryptMode

import android.view.View
import android.widget.LinearLayout
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** O modo grade da HOME: 14 cartões em 6 colunas viram 3 fileiras, na ordem, (o foco anda pelo FocusFinder do Android, que usa estas posições). */
@RunWith(RobolectricTestRunner::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@Config(sdk = [34])
class GradeAppsTest {
    @Test fun dispoeEmFileiras() {
        val ctx = RuntimeEnvironment.getApplication()
        val g = GradeApps(ctx, 6, 12)
        val filhos = (0 until 14).map { View(ctx).apply { isFocusable = true } }
        filhos.forEach { g.addView(it, LinearLayout.LayoutParams(124, 75).apply { marginEnd = 4 }) }
        val solto = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        g.measure(solto, solto)
        g.layout(0, 0, g.measuredWidth, g.measuredHeight)
        assertEquals(6 * 128, g.measuredWidth)
        assertEquals(3 * 75 + 2 * 12, g.measuredHeight)
        assertEquals(0, filhos[6].left); assertEquals(87, filhos[6].top)       // 7º cartão: começo da 2ª fileira
        assertEquals(128, filhos[13].left); assertEquals(174, filhos[13].top)  // 14º: 2º da 3ª fileira
    }
}
