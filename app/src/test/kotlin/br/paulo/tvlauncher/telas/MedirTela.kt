package br.paulo.tvlauncher.telas

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import br.paulo.tvlauncher.Config
import java.io.File
import org.robolectric.RuntimeEnvironment

/**
 * Regras fixas de tela do degrau 4 (17/11/2026), as mesmas do CuidarTelaTest: salva a foto em app/build/telas, e
 * reclama de letra menor que [minimoSp], texto cortado (reticências), texto por cima de outro (só em [cartao], onde
 * nada rola) e tela sem foco.
 */
object MedirTela {
    private val saida = File("build/telas")

    fun medir(a: Activity, chave: String, arquivo: String, minimoSp: Float, cartao: Boolean): List<String> {
        saida.mkdirs()
        val raiz: View = a.window.decorView
        val foto = Bitmap.createBitmap(raiz.width, raiz.height, Bitmap.Config.ARGB_8888)
        raiz.draw(Canvas(foto))
        File(saida, "$arquivo.png").outputStream().use { foto.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val escala = RuntimeEnvironment.getApplication().resources.displayMetrics.scaledDensity
        val textos = mutableListOf<Pair<TextView, Rect>>()
        fun andar(v: View) {
            if (v.visibility != View.VISIBLE || v.alpha == 0f) return
            if (v is TextView && v.text.isNotBlank() && v.width > 0 && v.height > 0) {
                val r = Rect()
                if (v.getGlobalVisibleRect(r)) textos += v to r
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i))
        }
        andar(raiz)
        val p = mutableListOf<String>()
        fun rotulo(t: TextView) = t.text.toString().replace('\n', ' ').trim().take(40)
        for ((t, _) in textos) {
            val sp = t.textSize / escala
            if (sp < minimoSp - 0.05f) p += "$chave: letra pequena (${sp}sp) em \"${rotulo(t)}\""
            if (t.layout?.let { l -> (0 until l.lineCount).any { l.getEllipsisCount(it) > 0 } } == true) p += "$chave: texto cortado \"${rotulo(t)}\""
        }
        if (cartao) for (i in textos.indices) for (j in i + 1 until textos.size) {
            val (t1, r1) = textos[i]; val (t2, r2) = textos[j]
            if (t1.parent === t2 || t2.parent === t1) continue
            val x = Rect()
            if (x.setIntersect(r1, r2) && x.width() > 3 && x.height() > 3) p += "$chave: \"${rotulo(t1)}\" por cima de \"${rotulo(t2)}\""
        }
        if (raiz.findFocus() == null) p += "$chave: nada tem o foco"
        return p
    }
}
