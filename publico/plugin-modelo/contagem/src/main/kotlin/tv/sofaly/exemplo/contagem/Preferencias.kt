package tv.sofaly.exemplo.contagem

import android.content.Context
import java.time.LocalDate

/** A data e o nome escolhidos ficam no próprio app (nada sai da TV). */
object Preferencias {
    private fun p(ctx: Context) = ctx.getSharedPreferences("contagem", Context.MODE_PRIVATE)
    fun data(ctx: Context): LocalDate? = p(ctx).getString("data", null)?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    fun titulo(ctx: Context): String = p(ctx).getString("titulo", null) ?: ctx.getString(R.string.titulo_padrao)
    fun salvar(ctx: Context, data: LocalDate, titulo: String) =
        p(ctx).edit().putString("data", data.toString()).putString("titulo", titulo.trim().take(30)).apply()
}
