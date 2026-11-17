package tv.sofaly.exemplo.lua

import android.app.Activity
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import java.time.Instant

/** O que abre no OK do cartão: a fase de hoje em letra bem grande. Voltar fecha. */
class LuaActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val idade = Lua.idade(Instant.now())
        val fase = Lua.fase(idade)
        setContentView(TextView(this).apply {
            gravity = Gravity.CENTER; textSize = 48f
            text = "${fase.emoji}\n${resources.getStringArray(R.array.fases)[fase.ordinal]}"
        })
    }
}
