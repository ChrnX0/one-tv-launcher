package br.paulo.tvlauncher

import android.view.KeyEvent
import android.widget.EditText

/** Campos de texto nas perguntas ([CaixaTv]) das telas do Ouvir. */
object CampoTv {
    /**
     * Enter (ou "OK"/"Concluído" do teclado da TV) no campo = apertar OK. Visto na TV 17/11/2026: sem isto o Enter só
     * passava o foco adiante, e ele caía em Cancelar (o primeiro botão).
     */
    fun enterConfirma(campo: EditText, d: CaixaTv.Janela) {
        campo.imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
        val ok = { d.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.performClick(); true }
        campo.setOnEditorActionListener { _, _, _ -> ok() }
        campo.setOnKeyListener { _, tecla, e ->
            // A descida também é nossa: solta, ela andaria com o foco para o botão de baixo antes da subida chegar.
            if (tecla != KeyEvent.KEYCODE_ENTER && tecla != KeyEvent.KEYCODE_NUMPAD_ENTER) false
            else { if (e.action == KeyEvent.ACTION_UP) ok(); true }
        }
    }
}
