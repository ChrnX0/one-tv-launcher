package tv.sofaly.extras

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Cartão na HOME do launcher (contrato PluginCartao: titulo, valor, detalhe, cor, acao). Mostra se o aviso "TV não
 * ligou" está ligado. Também é a porta pela qual o dono autoriza o plugin (Configurações › Tela inicial › Plugins).
 */
class CartaoProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? {
        val ctx = context ?: return null
        val e = Launcher.estado(ctx)
        // Aproveita a leitura do launcher para garantir o alarme (a pessoa pode nunca abrir o Extras).
        Launcher.agendar(ctx)
        val valor = if (e.naoLigouAtivo) "✓" else "–"
        val detalhe = ctx.getString(if (e.naoLigouAtivo) R.string.cartao_ligado else R.string.cartao_desligado)
        return MatrixCursor(arrayOf("titulo", "valor", "detalhe", "cor", "acao")).apply {
            addRow(arrayOf(ctx.getString(R.string.nome), valor, detalhe, "#2E7D6B",
                "intent:#Intent;action=android.intent.action.MAIN;category=android.intent.category.LEANBACK_LAUNCHER;package=tv.sofaly.extras;end"))
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}
