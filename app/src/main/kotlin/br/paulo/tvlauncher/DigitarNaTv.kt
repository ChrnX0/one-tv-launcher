package br.paulo.tvlauncher

import android.os.Bundle
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Ideia 56: escreve [texto] no campo em foco pela acessibilidade (AutorizaShizukuService). Regras em TextoNaTv.
 * Devolve true se o Android aceitou. False = serviço desligado, nenhum campo editável em foco ou o app recusou;
 * quem chama decide o plano B (o `input text` sem acento).
 */
object DigitarNaTv {
    fun escrever(texto: String): Boolean {
        val servico = AutorizaShizukuService.instancia ?: return false
        val campo = runCatching { servico.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) }.getOrNull() ?: return false
        if (!campo.isEditable) return false
        val final = TextoNaTv.juntar(campo.text, campo.isShowingHintText, texto)
        val ok = campo.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, final)
        })
        // Cursor no fim, como quem digitou: a próxima letra (modo ao vivo) e o "apagar" mexem no lugar certo.
        if (ok) runCatching {
            campo.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, final.length)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, final.length)
            })
        }
        return ok
    }
}
