package tv.sofaly.exemplo.contagem

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import java.time.LocalDate

/**
 * O que o launcher lê. Responda com UMA linha e estas colunas:
 *   titulo (até 30 letras), valor (até 12: o número grande), detalhe (até 80), cor ("#RRGGBB"),
 *   acao (opcional: um "intent:" que só abre o SEU app; null = abre o app).
 * Regras do launcher: a leitura tem 2 s de prazo e acontece toda vez que a pessoa volta à tela inicial,
 * então responda RÁPIDO e só com dados locais (guarde antes o que vier da internet).
 */
class CartaoProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<String>?, selection: String?, args: Array<String>?, sort: String?): Cursor {
        val ctx = context!!
        val c = MatrixCursor(arrayOf("titulo", "valor", "detalhe", "cor", "acao"))
        val alvo = Preferencias.data(ctx)
        if (alvo == null) {
            // Sem data escolhida: o cartão convida a escolher (OK abre a EscolherDataActivity).
            c.addRow(arrayOf(ctx.getString(R.string.nome), "?", ctx.getString(R.string.escolha), "#5B6B8C", null))
        } else {
            val dias = Contagem.diasAte(LocalDate.now(), alvo)
            val detalhe = when {
                dias > 0 -> ctx.resources.getQuantityString(R.plurals.faltam, dias.toInt().coerceAtMost(9999), dias)
                dias == 0L -> ctx.getString(R.string.hoje)
                else -> ctx.getString(R.string.passou)
            }
            c.addRow(arrayOf(Preferencias.titulo(ctx), Contagem.valor(dias), detalhe, "#CE3D00", null))
        }
        return c
    }

    // O launcher só lê. O resto não é usado.
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<String>?) = 0
}
