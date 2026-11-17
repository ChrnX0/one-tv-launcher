package tv.sofaly.exemplo.lua

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.time.Instant

/** O cartão da Lua: emoji da fase como número grande, nome da fase como título. Tudo calculado aqui, em microssegundos. */
class CartaoProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val ctx = context!!
        val idade = Lua.idade(Instant.now())
        val fase = Lua.fase(idade)
        val nomes = ctx.resources.getStringArray(R.array.fases)
        val dias = Lua.diasAteCheia(idade)
        val detalhe = if (dias == 0) ctx.getString(R.string.cheia_hoje)
            else ctx.resources.getQuantityString(R.plurals.ate_cheia, dias, dias)
        return MatrixCursor(arrayOf("titulo", "valor", "detalhe", "cor", "acao")).apply {
            addRow(arrayOf(nomes[fase.ordinal], fase.emoji, detalhe, "#1F2A44", null))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}
