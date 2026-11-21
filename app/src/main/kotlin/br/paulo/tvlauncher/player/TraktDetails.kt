package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.family.Classificacao
import br.paulo.tvlauncher.txt
import java.net.URLEncoder
import java.util.Locale
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ficha de filmes e séries do plano Básico pelo Trakt (19/11/2026, regra do Paulo: grátis no Básico; o TMDB, pago, só
 * no Pro com contrato). O Trakt libera "free and paid apps" sem contrato (developer.trakt.tv/docs/api-use-policy).
 * Só lógica pura (TraktDetailsTest); quem fala com a rede é o [InfoExternaCache].
 *
 * O id que fica guardado continua sendo o do TMDB (o Trakt devolve ids.tmdb): a nota da noite, a idade do modo infantil,
 * o Simkl e a lista do IPTV já se entendem por ele. Nada é pedido ao TMDB.
 *
 * O que o Trakt NÃO traz e fica para o Pro: saga (coleção), "Onde assistir", gêneros no idioma da TV (o Trakt manda o
 * slug em inglês; a ficha usa o gênero da lista) e o pôster (o Trakt proíbe carregar a imagem direto do CDN dele).
 *
 * Formatos (li em developer.trakt.tv em 19/11/2026; NÃO conferi com uma resposta real, a chave do app não está aqui):
 * busca [{"type","score","movie"|"show":{title,year,ids:{trakt,slug,imdb,tmdb},overview,status,trailer,...}}],
 * traduções [{title,overview,language,country}], pessoas {cast:[{character,person:{name}}],crew:{directing:[{job|jobs,person}]}},
 * próximo episódio {season,number,first_aired:"2026-11-20T01:00:00.000Z"} (204 sem corpo quando não há).
 */
object TraktDetails {
    private fun api() = ServicosFilmes.TRAKT_API
    private fun cod(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")
    private fun tipo(serie: Boolean) = if (serie) "show" else "movie"
    private fun plural(serie: Boolean) = if (serie) "shows" else "movies"

    /** Idioma da TV no formato das traduções do Trakt: ("pt", "br"). */
    fun idioma(l: Locale = Locale.getDefault()): Pair<String, String> = l.language.ifBlank { "en" }.lowercase() to l.country.lowercase()

    fun urlBusca(nome: String, ano: Int?, serie: Boolean): String =
        "${api()}/search/${tipo(serie)}?query=${cod(nome)}&extended=full&limit=10" + (ano?.let { "&years=${it - 1}-${it + 1}" } ?: "")

    fun urlPorTmdb(tmdb: Int, serie: Boolean): String = "${api()}/search/tmdb/$tmdb?type=${tipo(serie)}&extended=full"
    fun urlTraducao(trakt: Int, serie: Boolean, lingua: String): String = "${api()}/${plural(serie)}/$trakt/translations/${cod(lingua)}"
    fun urlPessoas(trakt: Int, serie: Boolean): String = "${api()}/${plural(serie)}/$trakt/people"
    fun urlProximo(trakt: Int): String = "${api()}/shows/$trakt/next_episode?extended=full"
    fun urlTemporadas(trakt: Int): String = "${api()}/shows/$trakt/seasons"
    fun urlEpisodio(trakt: Int, temporada: Int, numero: Int): String = "${api()}/shows/$trakt/seasons/$temporada/episodes/$numero?extended=full"
    fun urlTraducaoEpisodio(trakt: Int, temporada: Int, numero: Int, lingua: String): String =
        "${api()}/shows/$trakt/seasons/$temporada/episodes/$numero/translations/${cod(lingua)}"

    /** Um resultado da busca: o id do TMDB (o que guardamos), o do Trakt (para as perguntas seguintes) e o resto. */
    data class Achado(val trakt: Int, val tmdb: Int, val titulo: String, val original: String, val ano: Int?, val pontos: Double)

    fun lerBusca(json: String, serie: Boolean): List<Achado> {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val r = a.optJSONObject(i) ?: return@mapNotNull null
            achadoDe(r.optJSONObject(tipo(serie)) ?: return@mapNotNull null, r.optDouble("score", 0.0))
        }
    }

    private fun achadoDe(o: JSONObject, pontos: Double): Achado? {
        val ids = o.optJSONObject("ids") ?: return null
        val trakt = ids.optInt("trakt").takeIf { it > 0 } ?: return null
        val tmdb = ids.optInt("tmdb").takeIf { it > 0 } ?: return null   // sem id do TMDB não se casa com o resto do app
        return Achado(trakt, tmdb, txt(o, "title"), txt(o, "original_title"), o.optInt("year").takeIf { it in 1880..2100 }, pontos)
    }

    /** Para o [InfoExterna.escolher], que já sabe casar nome + ano (a popularidade é a nota da busca do Trakt). */
    fun candidatos(l: List<Achado>): List<InfoExterna.Candidato> = l.map { InfoExterna.Candidato(it.tmdb, it.titulo, it.original, it.ano, it.pontos) }

    /** O título do item pela busca por id do TMDB (o primeiro). */
    fun lerPorTmdb(json: String, serie: Boolean): JSONObject? =
        runCatching { JSONArray(json).optJSONObject(0)?.optJSONObject(tipo(serie)) }.getOrNull()

    /**
     * Texto do campo, "" se faltar OU vier null. No Android, optString de um `null` do JSON devolve a palavra "null"
     * (o org.json dos testes devolve ""): o Trakt manda "overview": null nas traduções e a ficha mostrava "null" (20/11/2026).
     */
    internal fun txt(o: JSONObject?, campo: String): String =
        if (o == null || o.isNull(campo)) "" else o.optString(campo)

    fun traktDe(o: JSONObject?): Int? = o?.optJSONObject("ids")?.optInt("trakt")?.takeIf { it > 0 }

    /** A tradução do país da TV, senão a primeira da língua com texto. (título, sinopse); null se nenhuma serve. */
    fun lerTraducao(json: String, pais: String): Pair<String, String>? = runCatching {
        val a = JSONArray(json)
        val l = (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        val boa = l.filter { txt(it, "overview").isNotBlank() || txt(it, "title").isNotBlank() }
        val t = boa.firstOrNull { txt(it, "country").equals(pais, true) } ?: boa.firstOrNull() ?: return@runCatching null
        txt(t, "title").trim() to txt(t, "overview").trim()
    }.getOrNull()

    /** Chave do vídeo do YouTube no endereço do trailer do Trakt; vazio se não for YouTube. */
    fun trailer(url: String?): String {
        val u = url.orEmpty()
        if (!u.contains("youtube.com/") && !u.contains("youtu.be/")) return ""
        return (Regex("[?&]v=([A-Za-z0-9_-]{6,20})").find(u) ?: Regex("youtu\\.be/([A-Za-z0-9_-]{6,20})").find(u))?.groupValues?.get(1).orEmpty()
    }

    /** Elenco (até 6 nomes) e direção (até 2) de /people. */
    fun lerPessoas(json: String): Pair<List<String>, String> = runCatching {
        val o = JSONObject(json)
        val elenco = o.optJSONArray("cast")?.let { c ->
            (0 until c.length()).mapNotNull { c.optJSONObject(it)?.optJSONObject("person")?.let { txt(it, "name") }?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty().distinct().take(6)
        val diretor = o.optJSONObject("crew")?.optJSONArray("directing")?.let { d ->
            (0 until d.length()).mapNotNull { d.optJSONObject(it) }.filter { p ->
                txt(p, "job") == "Director" || p.optJSONArray("jobs")?.let { j -> (0 until j.length()).any { j.optString(it) == "Director" } } == true
            }.mapNotNull { it.optJSONObject("person")?.let { pe -> txt(pe, "name") }?.takeIf { n -> n.isNotBlank() } }
        }.orEmpty().distinct().take(2).joinToString(", ")
        elenco to diretor
    }.getOrDefault(emptyList<String>() to "")

    /** Próximo episódio; a data do Trakt é UTC e vira o dia local da TV. Null sem episódio marcado (204 ou corpo vazio). */
    fun lerProximo(json: String, fuso: TimeZone = TimeZone.getDefault()): InfoExterna.ProximoEp? = runCatching {
        if (json.isBlank()) return null
        val o = JSONObject(json)
        val quando = txt(o, "first_aired").takeIf { it.isNotBlank() } ?: return null
        val utc = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val ms = utc.parse(quando.take(19))?.time ?: return null
        val dia = java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { timeZone = fuso }.format(java.util.Date(ms))
        InfoExterna.ProximoEp(dia, o.optInt("season"), o.optInt("number")).takeIf { it.temporada > 0 && it.numero > 0 }
    }.getOrNull()

    /**
     * Idade da classificação americana do item (/search/tmdb): "PG-13" -> 13, "TV-MA" -> 17... pela mesma tabela do
     * TMDB ([Classificacao.idadeEua]); sem classificação ou item = [Classificacao.SEM]. Null se a resposta não serve.
     */
    fun idade(json: String, serie: Boolean): Int? {
        val a = runCatching { JSONArray(json) }.getOrNull() ?: return null
        val o = a.optJSONObject(0)?.optJSONObject(tipo(serie)) ?: return Classificacao.SEM
        return txt(o, "certification").takeIf { it.isNotBlank() }?.let { Classificacao.idadeEua(it) } ?: Classificacao.SEM
    }

    /** Quantas temporadas (sem a 0, de especiais). */
    fun lerTemporadas(json: String): Int = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).count { (a.optJSONObject(it)?.optInt("number") ?: 0) > 0 }
    }.getOrDefault(0)

    /** Série ainda no ar pelo status do Trakt ("returning series", "in production", "planned", "upcoming"). */
    fun noAr(status: String): Boolean = status.lowercase() in setOf("returning series", "in production", "planned", "upcoming", "pilot")

    /**
     * Monta a [InfoExterna.Detalhe] com o item (de /search/tmdb), a tradução no idioma da TV (null = fica o inglês),
     * as pessoas, o próximo episódio e as temporadas. Gêneros vazios (ver o cabeçalho) e sem pôster.
     */
    fun detalhe(tmdb: Int, item: JSONObject, serie: Boolean, traducao: Pair<String, String>?, pessoas: Pair<List<String>, String>,
                proximo: InfoExterna.ProximoEp?, temporadas: Int): InfoExterna.Detalhe {
        val imdb = txt(item.optJSONObject("ids"), "imdb").takeIf { InfoExterna.idImdbValido(it) }.orEmpty()
        val sinopse = traducao?.second?.takeIf { it.isNotBlank() } ?: txt(item, "overview").trim()
        return InfoExterna.Detalhe(tmdb, imdb, sinopse.take(1500), pessoas.first, emptyList(), null, proximo,
            noAr = serie && (noAr(txt(item, "status")) || proximo != null),
            trailer = trailer(txt(item, "trailer")), diretor = if (serie) "" else pessoas.second,
            poster = "", temporadas = if (serie) temporadas else 0)
    }

    /** (título, resumo) do episódio: a tradução quando houver, senão o original. Null sem resumo. */
    fun lerEpisodio(json: String, traducao: Pair<String, String>?): Pair<String, String>? = runCatching {
        val o = JSONObject(json)
        val resumo = traducao?.second?.takeIf { it.isNotBlank() } ?: txt(o, "overview").trim()
        if (resumo.isEmpty()) null else (traducao?.first?.takeIf { it.isNotBlank() } ?: txt(o, "title").trim()) to resumo.take(900)
    }.getOrNull()

    /**
     * Uma busca de texto: [consulta] vai ao Trakt; [alvo] é o nome que a tradução tem de bater; [direto] = pode casar
     * também pelo título em inglês ([InfoExterna.escolher]).
     */
    data class Busca(val consulta: String, val alvo: String, val direto: Boolean)

    /**
     * Buscas para um título da lista: o inteiro e, quando ele vem como "dublado - original", a parte dublada e a parte
     * original. A original sozinha pode ser outra obra ("Law & Order" é a série-mãe, não a SVU), então ela só vale pela
     * tradução bater com a parte dublada. Na Sala (19/11/2026) a busca com o nome em português devolveu só a série-mãe
     * e as derivadas, nunca a SVU.
     */
    fun nomesDeBusca(nome: String): List<Busca> {
        val inteiro = nome.trim()
        val antes = inteiro.substringBefore(" - ", "").trim()
        val depois = inteiro.substringAfter(" - ", "").trim()
        if (antes.length < 3 || depois.length < 2) return listOf(Busca(inteiro, inteiro, true))
        return listOf(Busca(inteiro, inteiro, true), Busca(antes, antes, true), Busca(depois, antes, false))
    }

    /**
     * Mesmo título, sem contar "&" nem o "e"/"and" que o substitui: a tradução do Trakt é "Lei & Ordem: Unidade de
     * Vítimas Especiais" e a lista do IPTV escreve "Lei e Ordem: ..." (Sala, 19/11/2026).
     */
    fun mesmoTitulo(a: String, b: String): Boolean = chaveSemE(a).let { it.isNotEmpty() && it == chaveSemE(b) }
    private val CONJUNCOES = setOf("e", "and", "y", "et", "und", "i")
    private fun chaveSemE(t: String): String =
        Idioma.chaveTitulo(t.replace("&", " ")).split(' ').filter { it.isNotEmpty() && it !in CONJUNCOES }.joinToString(" ")
}
