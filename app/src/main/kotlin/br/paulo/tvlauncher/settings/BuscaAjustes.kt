package br.paulo.tvlauncher.settings

import br.paulo.tvlauncher.player.PlayerFiltro
import br.paulo.tvlauncher.search.BuscaUnica
import org.json.JSONArray
import org.json.JSONObject

/**
 * Busca nos ajustes (elevação 12.2, onda 1, 17/11/2026): a regra, pura (BuscaAjustesTest). A tela é a das
 * Configurações (ConfigLauncherActivity), que monta o índice com as linhas de verdade de cada aba, no idioma da TV.
 *
 * Por nome: o título e a explicação de cada linha, na língua da TV (as 14 traduções já são o texto da linha).
 * Por sinônimo: cada língua tem grupos de palavras que querem dizer a mesma coisa para quem procura (recurso
 * elv_aj_sinonimos: grupos separados por ";", palavras por ","). "fonte" acha "Tamanho do texto" porque "fonte",
 * "letra" e "texto" estão no mesmo grupo. A palavra procurada vale se ELA ou um sinônimo dela estiver na linha.
 *
 * "Mexidos há pouco": as últimas [MAX_RECENTES] linhas em que a pessoa deu OK, a mais recente primeiro.
 */
object BuscaAjustes {
    const val MAX_RECENTES = 8
    const val MAX_RESULTADOS = 12

    data class Entrada(val aba: Int, val titulo: String, val detalhe: String)
    data class Recente(val aba: Int, val titulo: String, val em: Long)

    /** "letra,fonte,texto;wifi,rede,internet" -> grupos normalizados. */
    fun grupos(recurso: String): List<Set<String>> = recurso.split(';').map { g ->
        g.split(',').map { PlayerFiltro.normalizar(it) }.filter { it.isNotEmpty() }.toSet()
    }.filter { it.size > 1 }

    /** A palavra e seus sinônimos (a própria primeiro). */
    fun expandir(palavra: String, grupos: List<Set<String>>): Set<String> =
        setOf(palavra) + grupos.filter { g -> palavra in g || g.any { it.length >= 4 && palavra.length >= 4 && it.startsWith(palavra) } }.flatten()

    /**
     * As linhas que batem com [termo]: cada palavra do termo (ou um sinônimo) tem de estar no título ou na explicação.
     * Ordem: palavra no título antes de palavra só na explicação; sem sinônimo antes de com sinônimo; título curto antes.
     */
    fun procurar(entradas: List<Entrada>, termo: String, grupos: List<Set<String>>, max: Int = MAX_RESULTADOS): List<Entrada> {
        val palavras = BuscaUnica.palavras(termo)
        if (palavras.isEmpty()) return emptyList()
        val vistos = HashSet<String>()
        return entradas.mapNotNull { e ->
            val t = PlayerFiltro.normalizar(e.titulo)
            val d = PlayerFiltro.normalizar(e.detalhe)
            var pts = 0
            for (p in palavras) {
                pts += when {
                    p in t -> 0
                    p in d -> 2
                    else -> {
                        val sin = expandir(p, grupos) - p
                        when {
                            sin.any { it in t } -> 1
                            sin.any { it in d } -> 3
                            BuscaUnica.pontosNorm(t, listOf(p), tolerante = true) != null -> 4   // uma letra errada
                            else -> return@mapNotNull null
                        }
                    }
                }
            }
            Triple(e, pts, e.titulo.length)
        }.sortedWith(compareBy({ it.second }, { it.third }))
            .filter { vistos.add("${it.first.aba}|${it.first.titulo}") }   // a mesma linha montada duas vezes aparece uma
            .take(max).map { it.first }
    }

    /** Guarda o OK na linha [titulo] da aba [aba]: vai para o topo, sem repetir, até [MAX_RECENTES]. */
    fun lembrar(lista: List<Recente>, aba: Int, titulo: String, agora: Long): List<Recente> =
        (listOf(Recente(aba, titulo, agora)) + lista.filterNot { it.aba == aba && it.titulo == titulo }).take(MAX_RECENTES)

    fun texto(l: List<Recente>): String = JSONArray().apply {
        l.forEach { put(JSONObject().put("a", it.aba).put("t", it.titulo).put("em", it.em)) }
    }.toString()

    fun ler(t: String?): List<Recente> = runCatching {
        val a = JSONArray(t ?: return emptyList())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Recente(it.getInt("a"), it.getString("t"), it.getLong("em")) } }
    }.getOrDefault(emptyList())
}
