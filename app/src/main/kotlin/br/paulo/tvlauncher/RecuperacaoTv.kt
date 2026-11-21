package br.paulo.tvlauncher

import android.content.Context
import br.paulo.tvlauncher.backup.ChaveRecuperacao
import java.util.Base64

/**
 * Elevação 19.6: a TV guarda SÓ a parte pública da chave de recuperação (prefs "backup_recuperacao", que viaja no
 * backup completo para a TV restaurada continuar trancando com a mesma chave). O código secreto sai uma vez pelo painel
 * ([criar]) e é esquecido aqui. Regras e cripto em [ChaveRecuperacao].
 */
object RecuperacaoTv {
    const val PREFS = "backup_recuperacao"
    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun publica(ctx: Context): ByteArray? =
        prefs(ctx).getString("publica", null)?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }

    fun criadaEm(ctx: Context): Long = prefs(ctx).getLong("criada_desde", 0L)
    fun digital(ctx: Context): String? = publica(ctx)?.let { ChaveRecuperacao.digital(it) }

    /** Cria uma chave nova (a anterior deixa de valer para os backups NOVOS) e devolve o código, que não fica na TV. */
    @Synchronized fun criar(ctx: Context): String {
        val nova = ChaveRecuperacao.gerar()
        prefs(ctx).edit().putString("publica", Base64.getEncoder().encodeToString(nova.publica))
            .putLong("criada_desde", System.currentTimeMillis()).commit()
        return nova.codigo
    }

    fun apagar(ctx: Context) { prefs(ctx).edit().clear().commit() }
}
