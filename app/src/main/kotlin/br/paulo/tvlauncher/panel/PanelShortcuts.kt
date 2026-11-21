package br.paulo.tvlauncher.panel

import android.content.Context
import org.json.JSONArray

/**
 * "Seus atalhos" da Início do painel (estudo do painel 20/11/2026, regra 6: "seu controle, seu jeito"). A estrela de
 * um bloco ou de uma chave fixa ele na Início, com o interruptor ali mesmo. Fica na TV (não no navegador) para o
 * computador e o celular verem a mesma lista.
 *
 * Cada atalho é um texto "tipo:nome": "chave:FILTRO_NOTURNO" (chave de Ligar e desligar), "config:visita" (opção do
 * /api/config) ou "bloco:cartaoManutencao" (link para o bloco). O painel ignora o que não achar (bloco que saiu).
 */
object PanelShortcuts {
    private const val PREFS = "painel_atalhos"
    private const val MAX = 12
    private val FORMA = Regex("(chave|config|bloco):[A-Za-z0-9_]{1,48}")

    /** Os 4 de fábrica, os do estudo: filtro noturno, modo visita, hora de dormir e reiniciar a TV (Manutenção). */
    val FACTORY = listOf("chave:FILTRO_NOTURNO", "config:visita", "chave:HORA_DORMIR", "bloco:cartaoManutencao")

    /** Lista limpa: só itens na forma certa, sem repetir, no máximo [MAX]. null = pedido inválido. */
    fun normalize(valor: Any?): List<String>? {
        val a = valor as? JSONArray ?: return null
        val itens = (0 until a.length()).map { a.opt(it) as? String ?: return null }
        if (itens.any { !FORMA.matches(it) }) return null
        return itens.distinct().take(MAX)
    }

    fun read(ctx: Context): List<String> {
        val s = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("lista", null) ?: return FACTORY
        return runCatching { normalize(JSONArray(s)) }.getOrNull() ?: FACTORY
    }

    fun write(ctx: Context, lista: List<String>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("lista", JSONArray(lista).toString()).apply()
    }
}
