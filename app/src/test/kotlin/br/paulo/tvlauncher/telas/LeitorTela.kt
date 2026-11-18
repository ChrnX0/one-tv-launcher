package br.paulo.tvlauncher.telas

import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * Elevação 16.1 (17/11/2026): o que o leitor de tela do sistema (TalkBack) diria em cada elemento que recebe foco.
 * Um elemento focável sem nome (sem descrição, sem texto e sem texto nos filhos) vira "semNome" na referência das
 * telas; um caso novo quebra o teste. O nome segue a regra do TalkBack: descrição da view, senão o texto dela, senão
 * os textos dos filhos que não são focáveis.
 */
object LeitorTela {
    fun nome(v: View): String {
        v.contentDescription?.takeIf { it.isNotBlank() }?.let { return it.toString() }
        val info = AccessibilityNodeInfo.obtain()
        v.onInitializeAccessibilityNodeInfo(info)
        info.contentDescription?.takeIf { it.isNotBlank() }?.let { return it.toString() }
        info.text?.takeIf { it.isNotBlank() }?.let { return it.toString() }
        if (v is TextView && v.text.isNotBlank()) return v.text.toString()
        if (v is ViewGroup) {
            val partes = mutableListOf<String>()
            fun andar(g: ViewGroup) {
                for (i in 0 until g.childCount) {
                    val c = g.getChildAt(i)
                    if (c.visibility != View.VISIBLE || c.isFocusable) continue
                    c.contentDescription?.takeIf { it.isNotBlank() }?.let { partes += it.toString() }
                        ?: (c as? TextView)?.text?.takeIf { it.isNotBlank() }?.let { partes += it.toString() }
                    if (c is ViewGroup && c.contentDescription.isNullOrBlank()) andar(c)
                }
            }
            andar(v)
            if (partes.isNotEmpty()) return partes.joinToString(", ")
        }
        return ""
    }

    fun semNome(raiz: View): Set<String> {
        val achados = mutableSetOf<String>()
        val dp = raiz.resources.displayMetrics.density
        fun andar(v: View) {
            if (v.visibility != View.VISIBLE || v.width == 0) return
            // Rolagem é recipiente (o leitor anuncia o que está dentro), não um elemento a nomear.
            val rolagem = v is android.widget.ScrollView || v is android.widget.HorizontalScrollView || v is android.widget.AbsListView ||
                v.javaClass.name.contains("RecyclerView")
            if (!rolagem && v.isFocusable && v.importantForAccessibility != View.IMPORTANT_FOR_ACCESSIBILITY_NO && nome(v).isBlank()) {
                val pos = IntArray(2).also { v.getLocationInWindow(it) }
                achados += "${v.javaClass.simpleName} ${(v.width / dp).toInt()}x${(v.height / dp).toInt()}dp em ${(pos[0] / dp).toInt()},${(pos[1] / dp).toInt()}"
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) andar(v.getChildAt(i))
        }
        andar(raiz)
        return achados
    }
}
