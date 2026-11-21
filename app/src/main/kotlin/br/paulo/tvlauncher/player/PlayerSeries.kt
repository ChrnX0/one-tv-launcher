package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.JsonEmFluxo
import java.net.URLEncoder
import org.json.JSONArray
import org.json.JSONObject

/**
 * Player universal, séries do Xtream (06/11/2026): catálogo, temporadas, episódios e "continuar de onde parou".
 * Só lógica pura (sem Android), testada em PlayerSeriesTest. Como nos filmes, nenhum endereço com senha é guardado:
 * o do episódio é montado na hora de tocar (urlEpisodio).
 */
data class Serie(
    val id: String,          // series_id
    val fonteId: String,
    val nome: String,
    val grupo: String = "",
    val capa: String = "",
    val nota: Float? = null,
    val ano: Int? = null,
    val atualizada: Long = 0, // last_modified (s): série com episódio novo sobe em "recentes"
    val tmdb: Int? = null,   // id do TMDB, quando a lista manda (InfoExterna, 14/11/2026)
) {
    val chave: String get() = "$fonteId:s$id"
}

data class Episodio(
    val id: String,          // id do episódio no servidor (vai no endereço)
    val temporada: Int,
    val numero: Int,
    val titulo: String,
    val extensao: String = "mp4",
    val sinopse: String = "",
    val duracaoMin: Int? = null,
) {
    fun chave(fonteId: String) = "$fonteId:e$id"
    val rotulo: String get() = "T${temporada}E${numero}"
}

data class FichaSerie(
    val sinopse: String = "",
    val elenco: String = "",
    val trailer: String = "",   // link do YouTube, vazio = não tem
    val genero: String = "",
    val temporadas: Map<Int, List<Episodio>> = emptyMap(),   // ordenadas por número
    val cenas: List<String> = emptyList(),   // imagens de cena (backdrop_path), para o card Séries da HOME
    val tmdb: Int? = null,   // tmdb_id da ficha do Xtream (InfoExterna)
)

/**
 * Quanto da série já foi visto (07/11/2026, "Assistidos" das séries): episódios vistos / total, calculado quando a ficha
 * abre (só ela tem a lista de episódios). `atualizada` = o last_modified da série naquela hora: se o servidor publicar
 * episódio novo depois, a série deixa de contar como "Finalizado" até a ficha abrir de novo.
 */
data class ProgressoSerie(
    val vistos: Int, val total: Int, val atualizada: Long,
    /** Quantos episódios e temporadas a série tinha quando foi concluída (0 = nunca concluída): base do "N episódios novos". */
    val concluidaCom: Int = 0, val temporadasConcluida: Int = 0,
    val temporadas: Int = 0,
)

/** Etiqueta da série nos Interesses e na grade (14/11/2026): verde "Concluída", amarela "Nova temporada"/"N episódios novos". */
sealed class EtiquetaSerie {
    object Concluida : EtiquetaSerie()
    object NovaTemporada : EtiquetaSerie()
    /** [quantos] = 0 quando o servidor avisou que mudou (last_modified) mas a ficha ainda não foi relida. */
    data class Novos(val quantos: Int) : EtiquetaSerie()
}

/** O último episódio que a pessoa abriu numa série (para o "continuar" sem baixar a ficha de todas). */
data class UltimoEpisodio(val episodioId: String, val temporada: Int, val numero: Int, val titulo: String, val extensao: String, val quando: Long)

object Series {
    /**
     * Nome do episódio sem o que já está na tela (passada na TV, 12/11/2026): o servidor manda "Overgeared 2026 -
     * S01E07 - Episódio 7", e a barra do player mostrava "Overgeared 2026 · T1E7 · Overgeared 2026 - S01E07 - Episódio 7".
     * Tira o nome da série e o "S01E07" do começo; se não sobrar nada, devolve o título como veio.
     */
    fun tituloCurto(serie: String, titulo: String): String {
        var t = titulo.trim()
        for (n in listOf(serie.trim(), Vod.nomeLimpo(serie))) {
            if (n.isNotEmpty() && t.startsWith(n, ignoreCase = true)) { t = t.substring(n.length); break }
        }
        // O ano que a lista põe depois do nome ("Slow Horses 2022 - S01E01 - ..." ou "(2022)") sai junto com o código.
        t = t.replace(reCodigoEp, "")
        return t.trim().ifEmpty { titulo.trim() }
    }

    private val reCodigoEp = Regex("""^[\s\-–:|·]*(?:\(?(?:19|20)\d{2}\)?[\s\-–:|·]+(?=S\d|T\d|\d{1,2}x\d))?(?:S\d{1,2}\s*E\d{1,3}|T\d{1,2}\s*E\d{1,3}|\d{1,2}x\d{1,3})?[\s\-–:|·]*""", RegexOption.IGNORE_CASE)

    /** Teto de episódios por ficha: a maior série real tem poucos milhares; um servidor com defeito não estoura a memória. */
    const val MAX_EPISODIOS = 5000

    private fun cod(v: String) = URLEncoder.encode(v, "UTF-8")

    fun urlCategorias(conta: Xtream.Conta) = Xtream.urlApi(conta, "get_series_categories")
    fun urlSeries(conta: Xtream.Conta) = Xtream.urlApi(conta, "get_series")
    fun urlFicha(conta: Xtream.Conta, id: String) = Xtream.urlApi(conta, "get_series_info", mapOf("series_id" to id))

    fun urlEpisodio(conta: Xtream.Conta, episodioId: String, extensao: String): String =
        "${conta.servidor}/series/${cod(conta.usuario)}/${cod(conta.senha)}/${cod(episodioId)}.${Vod.extensaoSegura(extensao)}"

    private fun numero(o: JSONObject, chave: String): Double? = when (val v = o.opt(chave)) {
        is Number -> v.toDouble()
        is String -> v.trim().replace(',', '.').toDoubleOrNull()
        else -> null
    }

    private fun texto(o: JSONObject, k: String) = o.optString(k, "").trim().takeIf { it != "null" }.orEmpty()

    fun lerSeries(json: String, fonteId: String, categorias: List<Xtream.Categoria> = emptyList()): List<Serie> {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return lerSeries(Xtream.objetos(a), fonteId, categorias)
    }

    /** Igual, de um fluxo ([JsonEmFluxo]): para no máximo e não lê o resto. */
    fun lerSeries(itens: Sequence<JSONObject>, fonteId: String, categorias: List<Xtream.Categoria> = emptyList()): List<Serie> {
        val grupos = categorias.associate { it.id to it.nome }
        val saida = ArrayList<Serie>()
        for (o in itens) {
            if (saida.size >= Vod.MAX_FILMES) break
            val id = o.opt("series_id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
            val nome = texto(o, "name")
            if (nome.isEmpty()) continue
            val capa = texto(o, "cover")
            val ano = Vod.anoDoNome(nome) ?: texto(o, "releaseDate").ifEmpty { texto(o, "release_date") }.take(4).toIntOrNull()?.takeIf { it in 1900..2100 }
            saida += Serie(id, fonteId, nome, grupos[o.opt("category_id")?.toString()].orEmpty(),
                if (M3u.urlAceita(capa)) capa else "",
                numero(o, "rating")?.toFloat()?.takeIf { it > 0f && it <= 10f }, ano,
                numero(o, "last_modified")?.toLong() ?: 0, InfoExterna.tmdbDoXtream(o))
        }
        return saida
    }

    /**
     * A ficha da série com os episódios. O Xtream manda "episodes" como objeto {"1":[...], "2":[...]} (às vezes como
     * lista de listas); os dois formatos são aceitos. Episódio sem id é descartado (não teria como tocar).
     */
    fun lerFicha(json: String): FichaSerie? {
        val raiz = runCatching { JSONObject(json) }.getOrNull() ?: return null
        val info = raiz.optJSONObject("info") ?: JSONObject()
        val listas = ArrayList<Pair<Int?, JSONArray>>()
        when (val e = raiz.opt("episodes")) {
            is JSONObject -> e.keys().forEach { k -> e.optJSONArray(k)?.let { listas += k.toIntOrNull() to it } }
            is JSONArray -> for (i in 0 until e.length()) e.optJSONArray(i)?.let { listas += null to it }
        }
        if (listas.isEmpty() && !raiz.has("info")) return null
        val porTemporada = HashMap<Int, MutableList<Episodio>>()
        var lidos = 0
        for ((tempChave, arr) in listas) {
            for (i in 0 until arr.length()) {
                if (lidos >= MAX_EPISODIOS) break
                val o = arr.optJSONObject(i) ?: continue
                val id = o.opt("id")?.toString()?.takeIf { it.isNotEmpty() && it != "null" } ?: continue
                val inf = o.optJSONObject("info") ?: JSONObject()
                val temporada = numero(o, "season")?.toInt() ?: tempChave ?: 1
                val num = numero(o, "episode_num")?.toInt() ?: (i + 1)
                val seg = numero(inf, "duration_secs")
                lidos++
                porTemporada.getOrPut(temporada) { ArrayList() } += Episodio(
                    id, temporada, num, texto(o, "title").take(300).ifEmpty { "Episódio $num" }, Vod.extensaoSegura(texto(o, "container_extension")),
                    texto(inf, "plot").take(800),
                    (seg?.let { (it / 60).toInt() } ?: Vod.duracaoTexto(texto(inf, "duration")))?.takeIf { it in 1..600 },
                )
            }
        }
        return FichaSerie(
            trailer = Vod.linkTrailer(texto(info, "youtube_trailer")),
            sinopse = texto(info, "plot").take(1500), elenco = texto(info, "cast").take(300), genero = texto(info, "genre").take(120),
            temporadas = porTemporada.toSortedMap().mapValues { (_, l) -> l.sortedBy { it.numero } },
            cenas = Vod.cenas(info),
            tmdb = InfoExterna.tmdbDoXtream(info),
        )
    }

    /**
     * "Visto até aqui" (11/11/2026, visto no 1 Play): este episódio e todos os anteriores (na ordem T1E1, T1E2...) ficam
     * como vistos até o fim. Os depois dele não mudam.
     */
    fun marcarVistosAte(f: FichaSerie, fonteId: String, ate: Episodio, posicoes: Map<String, Posicao>, agora: Long): Map<String, Posicao> {
        val ordem = emOrdem(f)
        val i = ordem.indexOfFirst { it.id == ate.id }
        if (i < 0) return posicoes
        val saida = HashMap(posicoes)
        // No máximo os MAX_VISTOS_ATE mais perto deste, com "quando" decrescente: numa série de 1100 episódios, 1100
        // posições com o mesmo "quando" passavam do teto (Vod.MAX_POSICOES) e o corte apagava o "continuar" dos filmes.
        val marcar = ordem.take(i + 1).takeLast(MAX_VISTOS_ATE)
        marcar.forEachIndexed { k, ep ->
            val d = posicoes[ep.chave(fonteId)]?.duracao?.takeIf { it > 0 } ?: ((ep.duracaoMin ?: 1) * 60_000L)
            saida[ep.chave(fonteId)] = Posicao(d, d, agora - (marcar.size - 1 - k))
        }
        return saida
    }

    const val MAX_VISTOS_ATE = 300

    /** "Não visto": apaga a posição deste episódio (volta a começar do zero). */
    fun desmarcar(fonteId: String, ep: Episodio, posicoes: Map<String, Posicao>): Map<String, Posicao> = posicoes - ep.chave(fonteId)

    /** Todos os episódios em ordem (T1E1, T1E2, ..., T2E1...). */
    fun emOrdem(f: FichaSerie): List<Episodio> = f.temporadas.values.flatten()

    /**
     * O mesmo episódio (temporada e número) na outra versão da série, dublada ou legendada (18/11/2026): no catálogo
     * são duas séries com ids próprios e cada episódio é um arquivo diferente. Null quando a outra não tem esse.
     */
    fun mesmoEpisodio(outra: FichaSerie, ep: Episodio): Episodio? =
        outra.temporadas[ep.temporada]?.firstOrNull { it.numero == ep.numero }

    /** O episódio depois deste (passa para a próxima temporada), ou null no último. */
    fun proximo(f: FichaSerie, atual: Episodio): Episodio? {
        val todos = emOrdem(f)
        val i = todos.indexOfFirst { it.id == atual.id }
        return if (i < 0) null else todos.getOrNull(i + 1)
    }

    /**
     * O que o botão principal da ficha oferece: o último episódio aberto, se não terminou; se terminou, o seguinte;
     * sem nada salvo, o primeiro. `retomar` = true quando é "Continuar" (tem posição no meio).
     */
    fun sugestao(f: FichaSerie, fonteId: String, ultimo: UltimoEpisodio?, posicoes: Map<String, Posicao>): Pair<Episodio, Boolean>? {
        val todos = emOrdem(f)
        if (todos.isEmpty()) return null
        val ep = ultimo?.let { u -> todos.firstOrNull { it.id == u.episodioId } } ?: return todos.first() to false
        val pos = posicoes[ep.chave(fonteId)]
        if (Vod.deveRetomar(pos, episodio = true)) return ep to true
        val terminou = Vod.finalizado(pos, episodio = true)
        return if (terminou) (proximo(f, ep) ?: ep) to false else ep to false
    }

    /**
     * Séries com episódio aberto, da mais recente para a mais velha (o "Continuar assistindo" das séries). Série
     * finalizada sai daqui (como o filme visto até o fim) e fica só em "Assistidos"; volta se sair episódio novo.
     */
    fun continuar(series: List<Serie>, ultimos: Map<String, UltimoEpisodio>, progresso: Map<String, ProgressoSerie> = emptyMap()): List<Serie> =
        series.filter { it.chave in ultimos && percentual(it, progresso[it.chave]) != 100 }
            .sortedByDescending { ordemContinuar(it, ultimos[it.chave], progresso[it.chave]) }

    /**
     * Momento usado para ordenar o Continuar (16/11/2026, ideia do Stremio): a última vez que a pessoa assistiu OU, se o
     * servidor publicou episódio depois da última conta da ficha, a hora dessa publicação. Assim a série que ganhou
     * episódio novo sobe para o topo do Continuar, junto com a etiqueta "N episódios novos", em vez de ficar lá embaixo
     * por ter sido vista há semanas. `atualizada` vem em segundos (last_modified); `quando` em milissegundos.
     */
    fun ordemContinuar(s: Serie, ultimo: UltimoEpisodio?, p: ProgressoSerie?): Long {
        val visto = ultimo?.quando ?: 0L
        val novidade = if (p != null && p.vistos > 0 && s.atualizada > p.atualizada) s.atualizada * 1000 else 0L
        return maxOf(visto, novidade)
    }

    // ---------------------------------------------------------------- assistidos (07/11/2026)

    /**
     * Conta os episódios CONCLUÍDOS (régua de minutos do episódio, [Vod.noFim]) com a ficha na mão. Se o total não
     * mudou, não desce por posição que saiu do posicoes.json (limite de 1000): o visto continua visto.
     * Concluiu tudo: guarda quantos episódios e temporadas havia, para avisar quando o servidor publicar mais.
     */
    fun progressoDaFicha(s: Serie, f: FichaSerie, posicoes: Map<String, Posicao>, anterior: ProgressoSerie?): ProgressoSerie? {
        val todos = emOrdem(f)
        if (todos.isEmpty()) return anterior
        val vistosAgora = todos.count { Vod.finalizado(posicoes[it.chave(s.fonteId)], episodio = true) }
        // O "nunca desce" só cobre episódio cuja posição SUMIU (o posicoes.json guarda 1000 e joga fora as mais velhas),
        // e o que some é o que vem antes do último episódio com posição. Sem esse limite, uma conta velha ficava para
        // sempre: Sala, 17/11/2026, Noturno/Slow Horses em T1E1 diziam "1 de N" sem nenhum episódio visto (a regra de
        // antes de 14/11 contava episódio começado). Nenhuma posição guardada = série inteira saiu do arquivo: mantém.
        val ultimoComPosicao = todos.indexOfLast { posicoes[it.chave(s.fonteId)] != null }
        val sumidos = if (ultimoComPosicao < 0) todos.size else (0 until ultimoComPosicao).count { posicoes[todos[it].chave(s.fonteId)] == null }
        val manter = if (anterior != null && anterior.total == todos.size) minOf(anterior.vistos, vistosAgora + sumidos) else 0
        val vistos = maxOf(vistosAgora, manter).coerceAtMost(todos.size)
        val temporadas = f.temporadas.size
        val (com, temps) = if (vistos >= todos.size) todos.size to temporadas
            else (anterior?.concluidaCom ?: 0) to (anterior?.temporadasConcluida ?: 0)
        return ProgressoSerie(vistos, todos.size, s.atualizada, com, temps, temporadas)
    }

    /**
     * A etiqueta da série: Concluída (tudo visto e nada novo); depois de concluída, se chegou coisa nova, "Nova
     * temporada" ou "N episódios novos" (N = 0 se só o last_modified mudou e a ficha ainda não foi relida). null = nada.
     */
    fun etiqueta(s: Serie, p: ProgressoSerie?): EtiquetaSerie? {
        if (p == null || p.total <= 0) return null
        if (p.vistos >= p.total) return if (s.atualizada > p.atualizada) EtiquetaSerie.Novos(0) else EtiquetaSerie.Concluida
        if (p.concluidaCom <= 0 || p.total <= p.concluidaCom) return null
        if (p.temporadasConcluida in 1 until p.temporadas) return EtiquetaSerie.NovaTemporada
        return EtiquetaSerie.Novos(p.total - maxOf(p.vistos, p.concluidaCom))
    }

    /**
     * Começar um episódio conclui o anterior (14/11/2026): quem pulou para o próximo já viu o fim (ou os créditos) do
     * outro. Só mexe no anterior se ele foi COMEÇADO e não está concluído; episódio nunca aberto continua não visto.
     */
    fun concluirAnterior(f: FichaSerie, fonteId: String, atual: Episodio, posicoes: Map<String, Posicao>, agora: Long): Map<String, Posicao> {
        val ordem = emOrdem(f)
        val i = ordem.indexOfFirst { it.id == atual.id }
        val ant = ordem.getOrNull(i - 1) ?: return posicoes
        val k = ant.chave(fonteId)
        val p = posicoes[k] ?: return posicoes
        if (Vod.finalizado(p, episodio = true)) return posicoes
        val d = p.duracao.takeIf { it > 0 } ?: ((ant.duracaoMin ?: 1) * 60_000L)
        return posicoes + (k to Posicao(d, d, p.quando))
    }

    /**
     * O número da etiqueta, na mesma régua dos filmes: null = sem etiqueta; 1 a 99 = % dos episódios vistos;
     * 100 = Finalizado (todos vistos e nada novo desde a conta).
     */
    fun percentual(s: Serie, p: ProgressoSerie?): Int? {
        if (p == null || p.total <= 0 || p.vistos <= 0) return null
        if (p.vistos >= p.total && s.atualizada <= p.atualizada) return 100
        return (p.vistos * 100 / p.total).coerceIn(1, 99)
    }

    /**
     * "Assistidos": só as séries CONCLUÍDAS (14/11/2026; a começada está no Continuar), da mais recente para a mais
     * velha. Concluída que ganhou episódio novo continua aqui, com a etiqueta amarela.
     */
    @Suppress("UNUSED_PARAMETER")
    fun assistidos(series: List<Serie>, ultimos: Map<String, UltimoEpisodio>, posicoes: Map<String, Posicao>,
                   progresso: Map<String, ProgressoSerie>): List<Serie> =
        series.filter { s -> progresso[s.chave]?.let { it.total > 0 && (it.vistos >= it.total || it.concluidaCom > 0) } == true }
            .sortedByDescending { ultimos[it.chave]?.quando ?: 0 }

    fun progressoParaJson(p: Map<String, ProgressoSerie>): String = JSONObject().apply {
        p.forEach { (k, v) -> put(k, JSONArray().put(v.vistos).put(v.total).put(v.atualizada).put(v.concluidaCom).put(v.temporadasConcluida).put(v.temporadas)) }
    }.toString()

    fun progressoDeJson(texto: String?): Map<String, ProgressoSerie> {
        val o = runCatching { JSONObject(texto ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val saida = LinkedHashMap<String, ProgressoSerie>()
        for (k in o.keys()) {
            val a = o.optJSONArray(k) ?: continue
            if (k.length > 200 || a.length() < 3) continue
            saida[k] = ProgressoSerie(a.optInt(0), a.optInt(1), a.optLong(2), a.optInt(3), a.optInt(4), a.optInt(5))
        }
        return saida
    }

    fun ordenar(series: List<Serie>, ordem: Vod.Ordem): List<Serie> = when (ordem) {
        Vod.Ordem.RECENTES -> series.sortedByDescending { it.atualizada }
        Vod.Ordem.NOME -> Vod.ordenarPorNome(series) { it.nome }
        Vod.Ordem.NOTA -> series.sortedByDescending { it.nota ?: -1f }
        Vod.Ordem.ANO -> series.sortedByDescending { it.ano ?: 0 }
    }

    fun buscar(series: List<Serie>, termo: String, max: Int = 500): List<Serie> {
        val t = PlayerFiltro.normalizar(termo)
        if (t.isEmpty()) return emptyList()
        val palavras = t.split(' ')
        return series.filter { s -> val n = PlayerFiltro.normalizar(s.nome); palavras.all { n.contains(it) } }.take(max)
    }

    fun grupos(series: List<Serie>): List<Pair<String, Int>> {
        val m = LinkedHashMap<String, Int>()
        series.forEach { m[it.grupo.trim()] = (m[it.grupo.trim()] ?: 0) + 1 }
        return ColunaRegras.ordenarAZ(m.entries.map { it.key to it.value })
    }

    // ---------------------------------------------------------------- arquivos (sem senha)

    fun seriesParaJson(series: List<Serie>): String = JSONArray().apply {
        series.forEach { s ->
            put(JSONObject().put("i", s.id).put("n", s.nome).put("g", s.grupo).put("c", s.capa).put("r", s.nota?.toDouble() ?: 0.0)
                .put("a", s.ano ?: 0).put("m", s.atualizada).put("t", s.tmdb ?: 0))
        }
    }.toString()

    fun seriesDeJson(texto: String?, fonteId: String): List<Serie> {
        val a = runCatching { JSONArray(texto ?: return emptyList()) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("i").ifEmpty { return@mapNotNull null }
            Serie(id, fonteId, o.optString("n"), o.optString("g"), o.optString("c").takeIf { M3u.urlAceita(it) }.orEmpty(),
                o.optDouble("r", 0.0).toFloat().takeIf { it > 0f }, o.optInt("a").takeIf { it > 0 }, o.optLong("m"), o.optInt("t").takeIf { it > 0 })
        }
    }

    fun ultimosParaJson(u: Map<String, UltimoEpisodio>): String = JSONObject().apply {
        u.forEach { (k, v) ->
            put(k, JSONObject().put("e", v.episodioId).put("t", v.temporada).put("n", v.numero).put("ti", v.titulo.take(120))
                .put("x", v.extensao).put("q", v.quando))
        }
    }.toString()

    fun ultimosDeJson(texto: String?): Map<String, UltimoEpisodio> {
        val o = runCatching { JSONObject(texto ?: return emptyMap()) }.getOrNull() ?: return emptyMap()
        val saida = LinkedHashMap<String, UltimoEpisodio>()
        for (k in o.keys()) {
            val v = o.optJSONObject(k) ?: continue
            val e = v.optString("e")
            if (e.isEmpty()) continue
            saida[k] = UltimoEpisodio(e, v.optInt("t"), v.optInt("n"), v.optString("ti"), Vod.extensaoSegura(v.optString("x")), v.optLong("q"))
        }
        return saida
    }

    /** Guarda o último episódio aberto da série; mantém só as 200 séries mais recentes. */
    fun registrarUltimo(u: Map<String, UltimoEpisodio>, serieChave: String, e: UltimoEpisodio): Map<String, UltimoEpisodio> {
        val novo = LinkedHashMap(u); novo[serieChave] = e
        return if (novo.size <= 200) novo else novo.entries.sortedByDescending { it.value.quando }.take(200).associate { it.key to it.value }
    }
}
