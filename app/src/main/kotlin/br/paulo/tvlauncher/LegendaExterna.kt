package br.paulo.tvlauncher

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
    const val AGENTE = "OneTVLauncher v1.0"   // a API exige um User-Agent com o nome do app
    const val IDIOMAS = "pt-br,pt-pt"   // TV em português (o caso de sempre); os outros idiomas: idiomasBusca()
    const val MAX_SRT = 2_000_000             // legenda de 3 h tem ~150 KB; acima disso não é legenda
    const val MAX_RESULTADOS = 12

    /** O que procurar: filme (nome + ano) ou episódio (nome da série + temporada + episódio). */
    data class Busca(val nome: String, val ano: Int? = null, val temporada: Int? = null, val episodio: Int? = null) {
        val eEpisodio get() = temporada != null && episodio != null
    }

    /** Uma legenda encontrada. [arquivo] é o file_id que o download pede. */
    data class Candidata(val arquivo: Long, val idioma: String, val nome: String, val baixadas: Int, val surdos: Boolean)

    private fun cod(v: String) = URLEncoder.encode(v, "UTF-8").replace("+", "%20")

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
        p["query"] = b.nome.trim().lowercase()
        return "$BASE/subtitles?" + p.entries.joinToString("&") { "${it.key}=${cod(it.value)}" }
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
            saida += Candidata(id, a.optString("language").lowercase(), nome, a.optInt("download_count", 0), a.optBoolean("hearing_impaired", false))
        }
        return saida.sortedWith(compareBy<Candidata>({ it.idioma != preferida }, { it.surdos }, { -it.baixadas })).take(MAX_RESULTADOS)
    }

    fun corpoDownload(arquivo: Long): String = JSONObject().put("file_id", arquivo).put("sub_format", "srt").toString()

    /** O link temporário do arquivo, só https (é o que a API devolve; outra coisa é recusada). */
    fun lerDownload(json: String): String? = runCatching { JSONObject(json).optString("link") }.getOrNull()
        ?.takeIf { it.startsWith("https://") }

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
