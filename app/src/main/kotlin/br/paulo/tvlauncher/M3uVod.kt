package br.paulo.tvlauncher

/**
 * Ideia 26 do estudo (14/11/2026): catálogo de Filmes e Séries para quem só tem lista M3U. Antes, Filmes e Séries
 * exigiam conta Xtream, e os filmes de uma lista M3U ficavam misturados aos canais ao vivo. Muitas listas trazem os
 * filmes e os episódios como entradas comuns: aqui eles viram catálogo.
 *
 * O que conta como filme ou episódio (só lógica pura, sem Android; testado no PC em M3uVodTest):
 *  - endereço com `/movie/` ou `/series/` no caminho (o formato dos painéis Xtream exportado em M3U), ou
 *  - arquivo de vídeo pela extensão (mp4, mkv, avi...). `.ts` e `.m3u8` NÃO contam: são o formato dos canais ao vivo.
 *  Episódio = nome com "S01E02" (ou "S01 E02"), ou endereço com `/series/`. O nome antes do "S01E02" é a série.
 *
 * Os canais ao vivo NÃO mudam (mesma lista, mesmos números): favoritos e "coloca no canal 12" continuam valendo.
 * Ids: o filme e o episódio usam o id estável do canal ([IdCanal]); a série, um id do nome normalizado. Nenhum
 * endereço vai para o catálogo (o player pede o endereço ao cache da lista na hora de tocar, ver PlayerCarga.urlM3u).
 */
object M3uVod {
    private val EXT_VIDEO = setOf("mp4", "mkv", "avi", "m4v", "mov", "wmv", "flv", "webm", "mpg", "mpeg", "divx", "3gp")
    private val reEpisodio = Regex("""(?i)(?:^|[\s._\-|(\[])S(\d{1,2})\s?[._\-]?\s?E(\d{1,3})(?![0-9])""")

    class Catalogo(val filmes: List<Filme>, val series: List<Serie>, val episodios: Map<String, Map<Int, List<Episodio>>>) {
        val vazio get() = filmes.isEmpty() && series.isEmpty()
    }

    /** Caminho do endereço, sem esquema, servidor e consulta (a parte que diz o tipo do arquivo). */
    private fun caminho(url: String): String =
        url.substringAfter("://", url).substringAfter('/', "").substringBefore('?').substringBefore('#').lowercase()

    /** "mkv" de ".../filme.mkv?token=..."; null sem extensão. */
    fun extensao(url: String): String? =
        caminho(url).substringAfterLast('/').substringAfterLast('.', "").takeIf { it.isNotEmpty() && it.length <= 5 && it.all { c -> c.isLetterOrDigit() } }

    fun ehVod(url: String): Boolean {
        val c = "/" + caminho(url)
        return "/movie/" in c || "/series/" in c || extensao(url) in EXT_VIDEO
    }

    /** "Breaking Bad S02E05 - Título" -> ("Breaking Bad", 2, 5). Null se não é episódio. */
    fun episodio(nome: String): Triple<String, Int, Int>? {
        val m = reEpisodio.find(nome) ?: return null
        val serie = nome.substring(0, m.range.first).trim().trimEnd('-', '|', ':', '.', '_').trim()
        if (serie.isEmpty()) return null
        return Triple(serie, m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /** O que vem depois do "S01E02" no nome ("Dark S01E01 - Segredos" -> "Segredos"); vazio se nada. */
    fun tituloEpisodio(nome: String): String {
        val m = reEpisodio.find(nome) ?: return ""
        return nome.substring(m.range.last + 1).trim().trimStart('-', '|', ':', '.', '_').trim()
    }

    /** Id da série: o nome sem acento, caixa e pontuação, e o grupo (duas séries com o mesmo nome em grupos diferentes). */
    fun idSerie(nome: String, grupo: String): String {
        val n = PlayerFiltro.normalizar(Vod.nomeLimpo(nome)).filter { it.isLetterOrDigit() }
        val chave = n + "|" + PlayerFiltro.normalizar(grupo)
        return "m" + Integer.toHexString(chave.hashCode()) + n.take(24)
    }

    /** Separa filmes e séries das entradas de uma lista M3U. Os canais ao vivo ficam de fora. */
    fun montar(canais: List<Canal>, fonteId: String): Catalogo {
        val filmes = ArrayList<Filme>()
        val series = LinkedHashMap<String, Serie>()
        val eps = HashMap<String, MutableMap<Int, MutableList<Episodio>>>()
        for (c in canais) {
            if (!ehVod(c.url)) continue
            val ext = Vod.extensaoSegura(extensao(c.url))
            val ep = episodio(c.nome)
            if (ep == null && "/series/" !in "/" + caminho(c.url)) {
                filmes += Filme(c.id, fonteId, c.nome, c.grupo, c.logo, null, Vod.anoDoNome(c.nome), ext)
                continue
            }
            // Endereço /series/ sem "S01E02" no nome: a entrada é o episódio de uma série com o nome do grupo (ou o
            // próprio nome), na temporada 1, na ordem da lista.
            val nomeSerie = ep?.first ?: c.grupo.ifBlank { c.nome }
            val t = ep?.second ?: 1
            val id = idSerie(nomeSerie, c.grupo)
            val s = series.getOrPut(id) { Serie(id, fonteId, nomeSerie, c.grupo, c.logo, null, Vod.anoDoNome(nomeSerie)) }
            if (s.capa.isEmpty() && c.logo.isNotEmpty()) series[id] = s.copy(capa = c.logo)
            val temporadas = eps.getOrPut(id) { HashMap() }
            val lista = temporadas.getOrPut(t) { ArrayList() }
            val numero = ep?.third ?: (lista.size + 1)
            val titulo = if (ep != null) tituloEpisodio(c.nome) else c.nome
            lista += Episodio(c.id, t, numero, titulo.ifEmpty { "$nomeSerie ${"T%dE%d".format(t, numero)}" }, ext)
        }
        val ordenados = eps.mapValues { (_, ts) -> ts.toSortedMap().mapValues { (_, l) -> l.distinctBy { it.numero }.sortedBy { it.numero } } }
        return Catalogo(filmes, series.values.toList(), ordenados)
    }
}
