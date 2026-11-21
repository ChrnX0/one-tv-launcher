package br.paulo.tvlauncher.player

import org.json.JSONObject
import java.net.URLEncoder

/**
 * Legenda baixada da internet (11/11/2026, dono: "ou ao menos arquivo de legendas"). Usa a API REST do OpenSubtitles
 * (api.opensubtitles.com/api/v1) com a chave de API e a conta DO DONO da TV, cadastradas nas Configurações: o launcher é
 * público e não leva chave nenhuma. Só lógica pura (sem Android), testada em LegendaExternaTest; quem fala com a rede é a
 * LegendaBaixar.
 *
 * Limites do serviço (sem conta: 5 legendas por dia; com conta grátis: em torno de 10): por isso cada legenda baixada fica
 * guardada na TV e a busca mostra a lista para a pessoa escolher, em vez de baixar várias às cegas.
 */
object LegendaExterna {
    const val BASE = "https://api.opensubtitles.com/api/v1"
    // A API exige um User-Agent com o nome do app; o produto se chama Sofaly (16/11/2026).
    const val AGENTE = "Sofaly v1.0"
    /**
     * Crédito obrigatório para chave grátis, "onde as legendas são procuradas ou escolhidas" (termos para integradores do
     * OpenSubtitles, 13/10/2026: opensubtitles.stoplight.io, "Terms of use for integrators"). Texto exato, em inglês, em
     * todos os idiomas. Os mesmos termos pedem: login da própria pessoa para passar de 5 por dia (ServicosContas),
     * legenda guardada só na TV de quem pediu (LegendaBaixar) e tradução só para essa pessoa (LegendaTraduzida).
     */
    const val CREDITO = "Subtitles provided by OpenSubtitles.com"
    const val IDIOMAS = "pt-br,pt-pt"   // TV em português (o caso de sempre); os outros idiomas: idiomasBusca()
    const val MAX_SRT = 2_000_000             // legenda de 3 h tem ~150 KB; acima disso não é legenda
    const val MAX_RESULTADOS = 12

    /** O que procurar: filme (nome + ano) ou episódio (nome da série + temporada + episódio). */
    /** [tmdb] = id TMDB do filme ou da série (fatia 3: o player conta ao Trakt/Simkl o que está passando). */
    /** [hash] = o "moviehash" do arquivo (ideia 89): acha a legenda feita para ESTE arquivo, com o tempo certo. */
    /** [versao]: a marca da lista ("LEG", "Dublado"); decide a legenda automática quando o áudio não diz o idioma. */
    data class Busca(val nome: String, val ano: Int? = null, val temporada: Int? = null, val episodio: Int? = null, val tmdb: Int? = null,
                     val hash: String? = null, val versao: Idioma.Versao = Idioma.Versao.DESCONHECIDA) {
        val eEpisodio get() = temporada != null && episodio != null
    }

    /** Uma legenda encontrada. [arquivo] é o file_id que o download pede. */
    data class Candidata(val arquivo: Long, val idioma: String, val nome: String, val baixadas: Int, val surdos: Boolean,
                         val bateHash: Boolean = false)

    /**
     * O nome do lançamento legível na TV (18/11/2026, lista no jeito da cruz): pontos e "_" viram espaço, mas o canal do
     * som continua "5.1"/"7.1" ("Dune.2021.1080p.WEBRip.DD5.1.x264" -> "Dune 2021 1080p WEBRip DD5.1 x264"). O nome
     * importa (diz se a legenda bate com o arquivo), então nada é cortado nem trocado além disso.
     */
    fun nomeLegivel(nome: String): String =
        nome.replace(Regex("[._]+"), " ").replace(Regex("(?<=[A-Za-z]\\d) (?=\\d(?!\\d))"), ".").replace(Regex("\\s+"), " ").trim()

    /** "pt-BR" -> "Português (Brasil)", no idioma da TV. Código desconhecido fica como veio, em maiúsculas. */
    fun nomeIdioma(codigo: String, tv: java.util.Locale = java.util.Locale.getDefault()): String {
        val l = java.util.Locale.forLanguageTag(codigo.replace('_', '-'))
        val n = l.getDisplayName(tv)
        if (l.language.isEmpty() || n.isBlank() || n.equals(codigo, ignoreCase = true)) return codigo.uppercase()
        return n.replaceFirstChar { it.titlecase(tv) }
    }

    /**
     * Espaço vira "+" (18/11/2026). Antes era "%20", e a API responde 301 para essa forma (conferido com curl: "query=sem%20deixar"
     * -> 301 para "query=sem+deixar"). Como o redirecionamento não era seguido, TODA busca com nome de mais de uma palavra
     * acabava em "Não consegui buscar a legenda" na Sala.
     */
    private fun cod(v: String) = URLEncoder.encode(v, "UTF-8")

    /**
     * Ideia 25: a legenda procurada é a do idioma da TV (2 letras, Idioma.idiomaDaTv). Português: Brasil e Portugal, como
     * antes; chinês: as duas grafias; o resto, o próprio código (é o que o OpenSubtitles usa: "en", "es", "fr").
     */
    fun idiomasBusca(idioma: String = "pt"): String = when (idioma) {
        "pt" -> IDIOMAS
        "zh" -> "zh-cn,zh-tw"
        else -> idioma.lowercase().filter { it in 'a'..'z' }.take(3).ifEmpty { IDIOMAS }
    }

    /** Endereço da busca. A documentação pede os parâmetros em ordem alfabética e minúsculos (senão a API redireciona). */
    fun urlBusca(b: Busca, idioma: String = "pt"): String {
        val p = sortedMapOf<String, String>()
        if (b.eEpisodio) {
            p["episode_number"] = b.episodio.toString(); p["season_number"] = b.temporada.toString(); p["type"] = "episode"
        } else {
            p["type"] = "movie"
            b.ano?.let { p["year"] = it.toString() }
        }
        p["languages"] = idiomasBusca(idioma)
        b.hash?.takeIf { it.matches(Regex("[0-9a-f]{16}")) }?.let { p["moviehash"] = it }
        p["query"] = b.nome.trim().lowercase()
        return "$BASE/subtitles?" + p.entries.joinToString("&") { "${it.key}=${cod(it.value)}" }
    }

    /**
     * Busca pelo id TMDB (18/11/2026, Paulo: "legendas que não são achadas"). O nome que a lista de IPTV dá é o
     * TRADUZIDO ("Duna: Parte Dois", "A Casa do Dragão"), e o OpenSubtitles indexa pelo original: a busca por nome
     * voltava vazia. Com o id TMDB (o filme, ou a série-mãe no episódio) o acerto não depende do nome nem do ano da lista.
     * Nulo sem id.
     */
    fun urlBuscaTmdb(b: Busca, idioma: String = "pt"): String? {
        val id = b.tmdb?.takeIf { it > 0 } ?: return null
        val p = sortedMapOf<String, String>()
        if (b.eEpisodio) {
            p["episode_number"] = b.episodio.toString(); p["parent_tmdb_id"] = id.toString()
            p["season_number"] = b.temporada.toString(); p["type"] = "episode"
        } else { p["tmdb_id"] = id.toString(); p["type"] = "movie" }
        p["languages"] = idiomasBusca(idioma)
        b.hash?.takeIf { it.matches(Regex("[0-9a-f]{16}")) }?.let { p["moviehash"] = it }
        return "$BASE/subtitles?" + p.entries.joinToString("&") { "${it.key}=${cod(it.value)}" }
    }

    /**
     * As buscas, da mais certeira para a mais larga, sem repetir: id TMDB; nome + ano; nome sem ano (o ano da lista de
     * IPTV às vezes é o do lançamento no Brasil, um a mais que o do OpenSubtitles). Quem busca para na primeira com
     * resultado (cada busca é uma ida à rede; o limite diário é só de download, a busca não gasta).
     */
    fun buscas(b: Busca, idioma: String = "pt"): List<String> = buildList {
        urlBuscaTmdb(b, idioma)?.let { add(it) }
        if (b.nome.isNotBlank()) {
            add(urlBusca(b, idioma))
            if (!b.eEpisodio && b.ano != null) add(urlBusca(b.copy(ano = null), idioma))
        }
    }.distinct()

    /** Redirecionamento que pode ser seguido: só https e só para o próprio OpenSubtitles (a chave vai no cabeçalho). */
    fun redirecionamentoSeguro(local: String?): String? {
        val u = local?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val absoluto = if (u.startsWith("/")) "https://api.opensubtitles.com$u" else u
        val host = Regex("""^https://([^/:?#]+)""").find(absoluto)?.groupValues?.get(1)?.lowercase() ?: return null
        return absoluto.takeIf { host == "opensubtitles.com" || host.endsWith(".opensubtitles.com") }
    }

    /**
     * Lê a resposta da busca: a variante preferida do idioma da TV primeiro (português do Brasil, na TV em português),
     * depois as mais baixadas; sem a "para surdos" na frente.
     */
    fun lerBusca(json: String, idioma: String = "pt"): List<Candidata> {
        val preferida = idiomasBusca(idioma).substringBefore(',')
        val dados = runCatching { JSONObject(json).optJSONArray("data") }.getOrNull() ?: return emptyList()
        val saida = ArrayList<Candidata>()
        for (i in 0 until dados.length()) {
            val a = dados.optJSONObject(i)?.optJSONObject("attributes") ?: continue
            val arquivos = a.optJSONArray("files") ?: continue
            val f = arquivos.optJSONObject(0) ?: continue
            val id = f.optLong("file_id", -1).takeIf { it > 0 } ?: continue
            val nome = (a.optString("release").takeIf { it.isNotBlank() && it != "null" } ?: f.optString("file_name")).trim().take(120)
            saida += Candidata(id, a.optString("language").lowercase(), nome, a.optInt("download_count", 0), a.optBoolean("hearing_impaired", false),
                a.optBoolean("moviehash_match", false))
        }
        return saida.sortedWith(compareBy<Candidata>({ !it.bateHash }, { it.idioma != preferida }, { it.surdos }, { -it.baixadas })).take(MAX_RESULTADOS)
    }

    // ---------------------------------------------------------------- ideia 89: legenda automática

    /** Bytes lidos do começo e do fim do arquivo para o hash (o algoritmo do OpenSubtitles). */
    const val BLOCO_HASH = 65_536

    /**
     * O "moviehash" do OpenSubtitles: tamanho do arquivo + a soma dos números de 64 bits (little-endian) dos primeiros e
     * dos últimos 64 KB, com estouro (módulo 2^64), em 16 dígitos hexadecimais. Nulo se o arquivo é menor que um bloco.
     */
    fun hash(tamanho: Long, inicio: ByteArray, fim: ByteArray): String? {
        if (tamanho < BLOCO_HASH || inicio.size < BLOCO_HASH || fim.size < BLOCO_HASH) return null
        var h = tamanho
        for (bloco in listOf(inicio, fim)) {
            val b = java.nio.ByteBuffer.wrap(bloco, 0, BLOCO_HASH).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            repeat(BLOCO_HASH / 8) { h += b.long }
        }
        return "%016x".format(h)
    }

    /** "bytes 0-65535/734003200" -> 734003200 (o tamanho do arquivo, do cabeçalho Content-Range). */
    fun tamanhoDoRange(cabecalho: String?): Long? = cabecalho?.substringAfterLast('/')?.trim()?.toLongOrNull()?.takeIf { it > 0 }

    /** A legenda que a TV liga sozinha: a que bate com o arquivo; sem ela, a primeira da lista (já ordenada). */
    fun escolherAuto(lista: List<Candidata>): Candidata? = lista.firstOrNull { it.bateHash } ?: lista.firstOrNull()

    fun corpoDownload(arquivo: Long): String = JSONObject().put("file_id", arquivo).put("sub_format", "srt").toString()

    /** O link temporário do arquivo, só https (é o que a API devolve; outra coisa é recusada). */
    fun lerDownload(json: String): String? = runCatching { JSONObject(json).optString("link") }.getOrNull()
        ?.takeIf { it.startsWith("https://") }

    /** O download foi recusado porque acabou a cota do dia? (HTTP 406, ou a mensagem "You have downloaded your allowed ..."). */
    fun acabouLimite(http: Int, json: String?): Boolean =
        http == 406 || (http !in 200..299 && lerErro(json)?.contains("allowed", ignoreCase = true) == true)

    fun corpoLogin(usuario: String, senha: String): String = JSONObject().put("username", usuario).put("password", senha).toString()

    fun lerToken(json: String): String? = runCatching { JSONObject(json).optString("token") }.getOrNull()?.takeIf { it.isNotBlank() }

    /** A mensagem que a API manda no erro (ex.: "You have downloaded your allowed 5 subtitles for 24h"), curta. */
    fun lerErro(json: String?): String? = runCatching { JSONObject(json ?: return null).optString("message") }.getOrNull()
        ?.takeIf { it.isNotBlank() }?.take(160)

    // ---------------------------------------------------------------- arquivo .srt

    private val reTempo = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    /** Parece um .srt (tem pelo menos uma linha de tempo "00:00:01,000 --> 00:00:02,000")? */
    fun pareceSrt(texto: String): Boolean = texto.length <= MAX_SRT && Regex("""\d{1,2}:\d{2}:\d{2}[,.]\d{1,3}\s*-->""").containsMatchIn(texto)

    /**
     * Atrasa (ms > 0) ou adianta (ms < 0) todas as falas. Tempo que ficaria negativo vira zero. Legenda baixada costuma vir
     * de outra cópia do filme e sair um pouco fora de tempo: isto é o "ajustar atraso" do menu do player.
     */
    fun deslocar(srt: String, ms: Long): String {
        if (ms == 0L) return srt
        return srt.lineSequence().joinToString("\n") { linha ->
            if (!linha.contains("-->")) linha else reTempo.replace(linha) { m ->
                val (h, mi, s, f) = m.destructured
                val frac = f.padEnd(3, '0').take(3).toLong()
                val total = (h.toLong() * 3_600_000 + mi.toLong() * 60_000 + s.toLong() * 1000 + frac + ms).coerceAtLeast(0)
                "%02d:%02d:%02d,%03d".format(total / 3_600_000, total / 60_000 % 60, total / 1000 % 60, total % 1000)
            }
        }
    }

    /** Texto do arquivo baixado: tira o BOM e, se não for UTF-8 válido, lê como Latin-1 (muita legenda antiga é assim). */
    fun decodificar(bytes: ByteArray): String {
        val utf = runCatching {
            java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        }.getOrNull()
        return (utf ?: String(bytes, Charsets.ISO_8859_1)).removePrefix("﻿")
    }

    /** "Duna · T1E2 · Piloto" não serve de busca: o nome vem limpo, sem marca de versão ([L], Dublado). */
    fun nomeDeBusca(nome: String): String = Idioma.chaveTitulo(nome)
}
