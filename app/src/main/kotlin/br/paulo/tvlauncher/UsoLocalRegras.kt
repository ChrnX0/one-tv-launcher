package br.paulo.tvlauncher

import org.json.JSONObject

/**
 * Ideia 67 (14/11/2026): uso local das funções. A TV conta quantas vezes cada tela abriu e cada opção das
 * Configurações foi apertada, para o dono ver o que ninguém usa e decidir o que cortar. NADA sai da TV: não é
 * telemetria (porta fechada pelo dono); não vai no backup nem no painel web.
 *
 * Puro (org.json): o mapa é {"nome": {"n": vezes, "u": último uso em ms}}. Testado na JVM (UsoLocalRegrasTest).
 */
object UsoLocalRegras {
    /** Teto de nomes guardados: um nome por tela/opção; passar disso = tira o menos usado (nunca cresce sem fim). */
    const val MAX_NOMES = 300

    data class Item(val nome: String, val vezes: Int, val ultimo: Long)

    fun somar(json: String?, nome: String, agora: Long): String {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: JSONObject()
        val i = o.optJSONObject(nome) ?: JSONObject()
        o.put(nome, i.put("n", i.optInt("n") + 1).put("u", agora))
        if (o.length() > MAX_NOMES) itens(o.toString()).lastOrNull()?.let { o.remove(it.nome) }
        return o.toString()
    }

    /** Do mais usado ao menos usado (empate: o usado mais recentemente primeiro). */
    fun itens(json: String?): List<Item> {
        val o = runCatching { JSONObject(json ?: "{}") }.getOrNull() ?: return emptyList()
        return o.keys().asSequence().mapNotNull { k ->
            o.optJSONObject(k)?.let { Item(k, it.optInt("n"), it.optLong("u")) }
        }.sortedWith(compareByDescending<Item> { it.vezes }.thenByDescending { it.ultimo }).toList()
    }

    /** Nome curto da tela: "br.paulo.tvlauncher.FilmesActivity" -> "Filmes". */
    fun nomeDaTela(classe: String): String = classe.substringAfterLast('.').removeSuffix("Activity").ifEmpty { classe }

    /** Dias desde o último uso (para "há 12 dias"); 0 = hoje ou no futuro (relógio mexido). */
    fun diasDesde(ultimo: Long, agora: Long): Long = ((agora - ultimo) / 86_400_000L).coerceAtLeast(0)
}
