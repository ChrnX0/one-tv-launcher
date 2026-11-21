package br.paulo.tvlauncher.search

import android.content.Context

/**
 * Buscas recentes (estudo global 20/11/2026, ideia 7). Por quê: digitar com a cruz do controle é lento, e a busca vazia
 * era só um campo. Para quê: repetir uma busca em 1 toque. Como: guarda as últimas [MAX] buscas que a pessoa USOU (abriu
 * um resultado), a mais nova primeiro, sem repetir (maiúsculas não contam). Fica só na TV, fora do backup na nuvem.
 */
object BuscaRecentes {
    const val MAX = 6
    private const val PREFS = "busca_recentes"

    /** Lógica pura (testada): põe [termo] na frente, tira a repetição e corta em [max]. Termo com menos de 2 letras não entra. */
    fun juntar(lista: List<String>, termo: String, max: Int = MAX): List<String> {
        val t = termo.trim().replace(Regex("\\s+"), " ")
        if (t.length < 2) return lista.take(max)
        return (listOf(t) + lista.filterNot { it.equals(t, ignoreCase = true) }).take(max)
    }

    fun ler(ctx: Context): List<String> =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lista", "").orEmpty().split('\n').filter { it.isNotBlank() }

    fun guardar(ctx: Context, termo: String) {
        val nova = juntar(ler(ctx), termo)
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lista", nova.joinToString("\n")).apply()
    }
}
