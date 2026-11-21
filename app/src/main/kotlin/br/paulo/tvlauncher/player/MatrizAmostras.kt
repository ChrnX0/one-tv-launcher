package br.paulo.tvlauncher.player

import org.json.JSONArray
import org.json.JSONObject

/**
 * Elevação 6.1 (17/11/2026): matriz "toca tudo". As amostras (vídeo, áudio, contêiner, legenda, HDR, HLS/DASH) são
 * geradas e conferidas no PC por scripts/amostras.py, com conteúdo sintético nosso (CC0, docs/amostras.md); o aparelho
 * de bancada toca cada uma e o resultado vira a frase pública "toca X de N neste aparelho".
 *
 * Só a regra, pura (MatrizAmostrasTest): ler a matriz, dar o veredito de cada amostra pelo que o player viu, montar o
 * relatório. Quem toca é [MatrizAmostrasTv].
 */
object MatrizAmostras {

    data class Amostra(
        val id: String, val arquivo: String, val categoria: String, val descricao: String,
        val video: Boolean, val legendas: Int, val legendaExterna: String?, val licenca: String,
    )

    /** O que o player viu numa amostra. */
    data class Visto(
        val pronto: Boolean = false,
        val primeiroQuadro: Boolean = false,
        val posicaoMs: Long = 0,
        val erro: String? = null,
        val legendasVistas: Int = 0,
        /** Decodificador de vídeo e de áudio usados (chip ou FFmpeg), para o relatório. */
        val decodificadores: String = "",
    )

    data class Resultado(val amostra: Amostra, val tocou: Boolean, val motivo: String, val visto: Visto)

    /** Tocou de verdade: andou [MIN_ANDOU_MS] ou mais, sem erro, com imagem (se tem vídeo) e com as legendas reconhecidas. */
    const val MIN_ANDOU_MS = 2500L

    fun ler(json: String): List<Amostra> {
        val a = JSONObject(json).optJSONArray("amostras") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val id = o.optString("id").takeIf { it.matches(Regex("[a-z]\\d{2}")) } ?: return@mapNotNull null
            val arquivo = o.optString("arquivo").takeIf { arquivoSeguro(it) } ?: return@mapNotNull null
            Amostra(id, arquivo, o.optString("categoria"), o.optString("descricao"), o.optBoolean("video", true),
                o.optInt("legendas"), o.optString("legenda_externa").takeIf { it.isNotBlank() && it != "null" && arquivoSeguro(it) },
                o.optString("licenca"))
        }
    }

    /** Nome de arquivo relativo e simples: nada de "..", barra no começo nem esquema (a matriz vem da rede). */
    fun arquivoSeguro(a: String): Boolean = a.matches(Regex("[A-Za-z0-9_\\-]+(/[A-Za-z0-9_\\-]+)?\\.[a-z0-9]{2,5}"))

    /** O endereço da amostra na [base] (http/https da casa, ou file:// de um pendrive). */
    fun url(base: String, arquivo: String): String? {
        val b = base.trim()
        if (!(b.startsWith("http://") || b.startsWith("https://") || b.startsWith("file:///"))) return null
        if (!arquivoSeguro(arquivo)) return null
        return b.trimEnd('/') + "/" + arquivo
    }

    fun veredito(a: Amostra, v: Visto): Resultado {
        val motivo = when {
            v.erro != null -> v.erro
            !v.pronto -> "não ficou pronta"
            a.video && !v.primeiroQuadro -> "sem imagem"
            v.posicaoMs < MIN_ANDOU_MS -> "não andou"
            v.legendasVistas < a.legendas -> "legenda não reconhecida"
            else -> null
        }
        return Resultado(a, motivo == null, motivo ?: "ok", v)
    }

    /** "toca 61 de 66 neste aparelho (Chromecast HD, Android 12)" + as que falharam. */
    fun texto(r: List<Resultado>, aparelho: String): String = buildString {
        append("toca ${r.count { it.tocou }} de ${r.size} neste aparelho ($aparelho)")
        val falhas = r.filter { !it.tocou }
        if (falhas.isNotEmpty()) {
            append("\nnão tocou:")
            falhas.forEach { append("\n  ${it.amostra.id} ${it.amostra.descricao}: ${it.motivo}") }
        }
        r.groupBy { it.amostra.categoria }.forEach { (c, l) -> append("\n$c: ${l.count { it.tocou }}/${l.size}") }
    }

    fun json(r: List<Resultado>, aparelho: String): JSONObject = JSONObject().apply {
        put("aparelho", aparelho); put("tocou", r.count { it.tocou }); put("total", r.size)
        put("amostras", JSONArray().apply {
            r.forEach { x -> put(JSONObject().put("id", x.amostra.id).put("tocou", x.tocou).put("motivo", x.motivo)
                .put("decodificadores", x.visto.decodificadores).put("andou_ms", x.visto.posicaoMs)) }
        })
    }
}
