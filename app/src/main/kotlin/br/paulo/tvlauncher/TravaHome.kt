package br.paulo.tvlauncher

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout

/**
 * PIN para mexer na HOME (10/11/2026, ideia N; li no tv-remote, issue #24: "Ask for the PIN to change TV Home", desligado
 * por padrão, vale 10 minutos). Com a chave [Extras.Chave.PIN_HOME] ligada, o menu do OK segurado (mover, ocultar,
 * desinstalar) pede o PIN do painel. Acertou: fica liberado por [LIBERADO_MS]. Sem PIN definido no painel, não pede
 * nada (não dá para travar com uma senha que não existe) e avisa como definir.
 */
object TravaHome {
    const val LIBERADO_MS = 10 * 60_000L
    @Volatile private var liberadoAte = 0L

    /** Regra pura (TravaHomeTest): precisa pedir o PIN agora? */
    fun precisaPin(ligado: Boolean, temPin: Boolean, agora: Long, liberadoAte: Long): Boolean =
        ligado && temPin && agora >= liberadoAte

    fun precisaPin(act: Activity): Boolean =
        precisaPin(Extras.ligado(act, Extras.Chave.PIN_HOME), PainelArmazem.temPin(act), System.currentTimeMillis(), liberadoAte)

    /** Roda [acao] direto se não precisa de PIN; senão pede o PIN e roda se acertar. */
    fun comPin(act: Activity, acao: () -> Unit) {
        if (!precisaPin(act)) { acao(); return }
        PinTv.pedir(act, act.getString(R.string.trava_home_titulo)) { certo ->
            if (certo) { liberadoAte = System.currentTimeMillis() + LIBERADO_MS; acao() }
            else { AvisoTv.rapido(act, act.getString(R.string.trava_home_titulo), act.getString(R.string.trava_home_errado), 4) }
        }
    }
}

/** Caixa de PIN reutilizável (o mesmo PIN do painel). Confere fora da thread da tela: o PBKDF2 passa de 1 s na TV. */
object PinTv {
    fun pedir(act: Activity, titulo: String, resultado: (Boolean) -> Unit) {
        val campo = EditText(act).apply {
            inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
            isSingleLine = true
        }
        val conferir = {
            val pin = campo.text.toString()
            Fundo.rodar {
                val certo = PainelArmazem.conferirPin(act, pin)
                act.runOnUiThread { if (!act.isDestroyed) resultado(certo) }
            }
        }
        val moldura = FrameLayout(act).apply {
            val m = (20 * act.resources.displayMetrics.density).toInt()
            setPadding(m, m / 2, m, 0); addView(campo)
        }
        val d = CaixaTv(act).setTitle(titulo).setView(moldura)
            .setPositiveButton(android.R.string.ok) { _, _ -> conferir() }
            .setNegativeButton(android.R.string.cancel, null).create()
        campo.setOnEditorActionListener { _, a, _ ->
            if (a == EditorInfo.IME_ACTION_DONE || a == EditorInfo.IME_ACTION_GO) { d.dismiss(); conferir(); true } else false
        }
        d.setOnShowListener { campo.requestFocus() }
        d.show()
    }
}
