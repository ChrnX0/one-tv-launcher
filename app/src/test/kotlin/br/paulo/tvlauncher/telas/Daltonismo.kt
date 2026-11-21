package br.paulo.tvlauncher.telas

import android.graphics.Bitmap
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.settings.CorSegura
import java.io.File

/**
 * Elevação 16.4 (17/11/2026), nas telas de referência:
 *  - salva cada foto também como vista com protanopia, deuteranopia e tritanopia (app/build/telas/daltonismo/), para
 *    olhar no artefato do workflow;
 *  - acha estado que só a cor conta: texto pintado de cor de estado sem sinal nem palavra (o "●" colorido), e
 *    bolinha pequena de cor de estado sem descrição. O nome entra em "soCor" na referência; um caso novo quebra.
 */
object Daltonismo {
    fun salvar(foto: Bitmap, pasta: File, nome: String) {
        pasta.mkdirs()
        val w = foto.width; val h = foto.height
        val px = IntArray(w * h).also { foto.getPixels(it, 0, w, 0, 0, w, h) }
        for (t in CorSegura.Daltonismo.values()) {
            val cache = HashMap<Int, Int>()
            val out = IntArray(px.size) { i -> cache.getOrPut(px[i]) { CorSegura.simular(px[i], t) } }
            val b = Bitmap.createBitmap(out, w, h, Bitmap.Config.ARGB_8888)
            File(pasta, "$nome-${t.name.lowercase()}.png").outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    fun estadosSoPelaCor(raiz: View): Set<String> {
        val achados = mutableSetOf<String>()
        val dp = raiz.resources.displayMetrics.density
        fun nomeDe(v: View) = (v as? TextView)?.text?.toString()?.replace('\n', ' ')?.trim()?.take(40)
            ?: "${v.javaClass.simpleName} ${(v.width / dp).toInt()}x${(v.height / dp).toInt()}dp"
        fun andar(v: View) {
            if (v.visibility != View.VISIBLE || v.alpha == 0f || v.width == 0) return
            if (v is TextView && v.text.isNotBlank()) {
                val t = v.text
                if (CorSegura.ehCorDeEstado(v.currentTextColor) && !CorSegura.temSinalOuPalavra(t.toString())) achados += nomeDe(v)
                if (t is Spanned) for (sp in t.getSpans(0, t.length, ForegroundColorSpan::class.java)) {
                    val pedaco = t.subSequence(t.getSpanStart(sp), t.getSpanEnd(sp)).toString()
                    if (pedaco.isNotBlank() && CorSegura.ehCorDeEstado(sp.foregroundColor) && !CorSegura.temSinalOuPalavra(pedaco))
                        achados += "\"$pedaco\" em \"${nomeDe(v)}\""
                }
            } else if (v !is ViewGroup && v.width <= 24 * dp && v.height <= 24 * dp && v.contentDescription.isNullOrBlank()) {
                val cor = when (val f = v.background) {
                    is ColorDrawable -> f.color
                    is GradientDrawable -> f.color?.defaultColor
                    else -> null
                }
                if (cor != null && CorSegura.ehCorDeEstado(cor)) achados += "bolinha ${nomeDe(v)}"
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i))
        }
        andar(raiz)
        return achados
    }
}
