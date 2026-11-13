package br.paulo.tvlauncher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Mural da família no protetor de tela (12/11/2026, ideia 6). Duas listas curtas que ficam à vista enquanto a TV está
 * parada:
 *  - compras ("leite", "pão"): um cartão "Lista de compras";
 *  - recados fixos ("Dentista quinta 15h"): um cartão cada, até [MAX_RECADOS]. Diferente do recado do painel, que some em
 *    6 h, estes ficam até alguém tirar.
 * Os aniversários de hoje vêm da agenda (o calendário "Aniversários" que o Google cria pelos contatos).
 *
 * Edita pelo painel (aba Extras) ou pelo Claude (MCP "mural": "põe leite na lista da TV"). O modo visita esconde.
 * A lógica (limites, limpeza do texto) é pura e testada em IdeiasTresTest.
 */
object Mural {
    const val CHAVE = "mural"   // nas prefs "extras"
    const val MAX_COMPRAS = 15
    const val MAX_RECADOS = 3
    const val MAX_TEXTO = 60

    data class Conteudo(val compras: List<String> = emptyList(), val recados: List<String> = emptyList())

    enum class Lista { COMPRAS, RECADOS }

    /** Tira quebras de linha e espaços repetidos; corta no tamanho máximo. Vazio = inválido. */
    fun limpar(t: String): String = t.replace(Regex("\\s+"), " ").trim().take(MAX_TEXTO)

    fun adicionar(c: Conteudo, l: Lista, texto: String): Conteudo {
        val t = limpar(texto)
        if (t.isEmpty()) return c
        return when (l) {
            // Compra repetida (sem diferenciar maiúsculas e acento) não entra duas vezes.
            Lista.COMPRAS -> if (c.compras.any { Lembretes.normalizar(it) == Lembretes.normalizar(t) }) c
                else c.copy(compras = (c.compras + t).takeLast(MAX_COMPRAS))
            Lista.RECADOS -> c.copy(recados = (c.recados + t).takeLast(MAX_RECADOS))
        }
    }

    /** Tira o item que bate com [texto] (sem diferenciar maiúsculas e acento). */
    fun remover(c: Conteudo, l: Lista, texto: String): Conteudo {
        val n = Lembretes.normalizar(limpar(texto))
        return when (l) {
            Lista.COMPRAS -> c.copy(compras = c.compras.filterNot { Lembretes.normalizar(it) == n })
            Lista.RECADOS -> c.copy(recados = c.recados.filterNot { Lembretes.normalizar(it) == n })
        }
    }

    fun limparLista(c: Conteudo, l: Lista): Conteudo = when (l) {
        Lista.COMPRAS -> c.copy(compras = emptyList())
        Lista.RECADOS -> c.copy(recados = emptyList())
    }

    /** "Aniversário de Ana" / "Ana's birthday": título de evento de dia inteiro que é aniversário. */
    fun eAniversario(titulo: String): Boolean = Lembretes.normalizar(titulo).let { "aniversario" in it || "birthday" in it }

    fun paraJson(c: Conteudo): JSONObject = JSONObject().put("compras", JSONArray(c.compras)).put("recados", JSONArray(c.recados))

    fun deJson(texto: String?): Conteudo {
        val o = try { JSONObject(texto ?: return Conteudo()) } catch (_: Exception) { return Conteudo() }
        fun lista(k: String, max: Int) = o.optJSONArray(k)?.let { a -> (0 until a.length()).map { limpar(a.optString(it)) }.filter { it.isNotEmpty() } }
            .orEmpty().takeLast(max)
        return Conteudo(lista("compras", MAX_COMPRAS), lista("recados", MAX_RECADOS))
    }

    // ---- na TV

    fun ler(ctx: Context): Conteudo = deJson(Extras.texto(ctx, CHAVE))

    @Synchronized
    fun editar(ctx: Context, mudar: (Conteudo) -> Conteudo): Conteudo {
        val novo = mudar(ler(ctx))
        Extras.definirTexto(ctx, CHAVE, paraJson(novo).toString())
        return novo
    }

    fun lista(nome: String?): Lista? = when (nome?.lowercase()) {
        "compras", "lista", "mercado" -> Lista.COMPRAS
        "recados", "recado" -> Lista.RECADOS
        else -> null
    }
}
