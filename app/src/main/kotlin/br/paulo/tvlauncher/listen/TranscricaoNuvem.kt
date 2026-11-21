package br.paulo.tvlauncher.listen

import android.content.Context
import android.util.Base64
import br.paulo.tvlauncher.Http
import br.paulo.tvlauncher.player.PlayerArmazem
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL
import java.security.MessageDigest

/**
 * Texto da fala gerado na nuvem, quando o podcast não publica o dele (proposta C do Ouvir, 21/11/2026).
 *
 * Por quê: o "Texto da fala" só existia nos feeds com podcast:transcript (minoria). O Apple gera o dele; nós geramos
 * com o Whisper do Workers AI, de graça (cota diária do plano Free da Cloudflare; server/api/src/transcribe.js).
 * Para quê: letra grande sincronizada, busca dentro da fala, resumo da Sofy e pular propaganda em qualquer episódio MP3.
 * Como: a TV baixa o episódio em pedaços de [PEDACO] bytes (Range), a partir de onde está tocando; cada pedaço vai em
 * Base64 ao servidor e volta com os trechos e o tempo dentro do pedaço; o tempo no episódio sai da proporção de bytes
 * (bytes / total × duração). Só MP3: pedaço de MP3 toca sozinho; de M4A não (o índice fica no começo do arquivo).
 * Cuidado com a cota (Paulo: "com cuidado com o transcrever"): teto de [MAX_MIN_DIA] minutos de áudio por dia nesta TV,
 * só gera quando alguém pede (botão do texto), e o que foi gerado fica guardado: o mesmo episódio nunca vai de novo.
 */
object TranscricaoNuvem {
    const val PEDACO = 1_500_000L          // ~95 s de MP3 a 128 kbps; em Base64 ~2 MB (o servidor aceita até 2,8 MB)
    const val MAX_MIN_DIA = 120
    private const val SERVIDOR = PodcastBusca.SERVIDOR

    // ---------------------------------------------------------------- puro (TranscricaoNuvemTest)

    /** Dá para gerar? Só áudio MP3 (pelo tipo do feed ou pelo fim do endereço). */
    fun podeGerar(e: PodcastFeed.Episodio): Boolean =
        !e.video && ("mpeg" in e.tipo.lowercase() || "mp3" in e.tipo.lowercase() || e.url.substringBefore('?').lowercase().endsWith(".mp3"))

    /** Ordem dos pedaços: do que tem [posByte] até o fim, depois os de antes (o que se vai ouvir primeiro). */
    fun ordem(total: Long, posByte: Long): List<Int> {
        val n = ((total + PEDACO - 1) / PEDACO).toInt().coerceAtLeast(0)
        if (n == 0) return emptyList()
        val i = (posByte.coerceIn(0, total - 1) / PEDACO).toInt()
        return (i until n) + (0 until i)
    }

    fun msDe(byte: Long, total: Long, durMs: Long): Long = if (total <= 0 || durMs <= 0) 0 else (byte.toDouble() / total * durMs).toLong()
    fun byteDe(ms: Long, total: Long, durMs: Long): Long = if (total <= 0 || durMs <= 0) 0 else (ms.toDouble() / durMs * total).toLong()

    /** A resposta do servidor em trechos, com o tempo do pedaço somado ([inicioMs]). */
    fun ler(json: String?, inicioMs: Long): List<Transcricao.Trecho> = runCatching {
        val a = JSONObject(json ?: return emptyList()).optJSONArray("segments") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val t = o.optString("text").trim().takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Transcricao.Trecho(inicioMs + (o.optDouble("start") * 1000).toLong(), inicioMs + (o.optDouble("end") * 1000).toLong(), t)
        }
    }.getOrDefault(emptyList())

    /** O texto que vai para o resumo: uma linha por trecho com o segundo em que começa ("754: ..."), até [max] letras. */
    fun textoParaResumo(l: List<Transcricao.Trecho>, max: Int = 24_000): String {
        val sb = StringBuilder()
        for (t in l) {
            val linha = "${t.inicioMs / 1000}: ${t.texto}\n"
            if (sb.length + linha.length > max) break
            sb.append(linha)
        }
        return sb.toString()
    }

    /** Resumo + propagandas da resposta do servidor ({"summary": "...", "ads": [[ini, fim]]}, em segundos). */
    fun lerResumo(json: String?): Pair<String, List<LongRange>>? = runCatching {
        val o = JSONObject(json ?: return null)
        val ads = o.optJSONArray("ads")?.let { a ->
            (0 until a.length()).mapNotNull { i -> a.optJSONArray(i)?.let { (it.getDouble(0) * 1000).toLong()..(it.getDouble(1) * 1000).toLong() } }
                .filter { it.last > it.first }
        }.orEmpty()
        o.optString("summary").trim() to ads
    }.getOrNull()

    /** A propaganda em que [posMs] está e que ainda não foi pulada, ou null. */
    fun propagandaEm(ads: List<LongRange>, posMs: Long, puladas: Set<Long>): LongRange? =
        ads.firstOrNull { posMs in it && it.first !in puladas }

    /** Minutos que ainda cabem hoje, dado o que já foi usado (dia = "aaaa-mm-dd" local). */
    fun restante(usadoDia: String, usadoMin: Float, hoje: String): Float = if (usadoDia != hoje) MAX_MIN_DIA.toFloat() else MAX_MIN_DIA - usadoMin

    // ---------------------------------------------------------------- guardado

    private fun pasta(ctx: Context) = File(PlayerArmazem.pasta(ctx), "textos").apply { mkdirs() }
    private fun arq(ctx: Context, feed: String, guid: String) = File(pasta(ctx), MessageDigest.getInstance("SHA-1")
        .digest("$feed\u0001$guid".toByteArray()).joinToString("") { "%02x".format(it) }.take(24) + ".json")

    data class Guardado(val trechos: List<Transcricao.Trecho>, val feitos: Set<Int>, val total: Long)

    fun carregar(ctx: Context, feed: String, guid: String): Guardado? = runCatching {
        val o = JSONObject(arq(ctx, feed, guid).readText())
        val a = o.getJSONArray("t")
        Guardado((0 until a.length()).map { val x = a.getJSONArray(it); Transcricao.Trecho(x.getLong(0), x.getLong(1), x.getString(2)) },
            o.optJSONArray("f")?.let { f -> (0 until f.length()).map { f.getInt(it) }.toSet() }.orEmpty(), o.optLong("total"))
    }.getOrNull()

    fun gravar(ctx: Context, feed: String, guid: String, g: Guardado) = runCatching {
        val o = JSONObject().put("total", g.total).put("f", JSONArray(g.feitos.sorted()))
            .put("t", JSONArray().apply { g.trechos.forEach { put(JSONArray().put(it.inicioMs).put(it.fimMs).put(it.texto)) } })
        val tmp = File(pasta(ctx), arq(ctx, feed, guid).name + ".tmp")
        tmp.writeText(o.toString()); tmp.renameTo(arq(ctx, feed, guid))
    }

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ouvir", Context.MODE_PRIVATE)
    private fun hoje() = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.ROOT).format(java.util.Date())
    fun restanteHoje(ctx: Context): Float = prefs(ctx).let { restante(it.getString("nuvem_dia", "").orEmpty(), it.getFloat("nuvem_min", 0f), hoje()) }
    private fun gastar(ctx: Context, min: Float) {
        val p = prefs(ctx); val h = hoje()
        val usado = if (p.getString("nuvem_dia", "") == h) p.getFloat("nuvem_min", 0f) else 0f
        p.edit().putString("nuvem_dia", h).putFloat("nuvem_min", usado + min).apply()
    }

    // ---------------------------------------------------------------- rede (fora da thread da tela)

    /** Abre [url] seguindo até 5 redirecionamentos, conferindo cada destino (nunca a própria TV ou a rede da casa). */
    private fun abrir(url: String, range: String?): HttpURLConnection? {
        var u = url
        repeat(6) {
            val host = URL(u).host
            val ips = runCatching { InetAddress.getAllByName(host).toList() }.getOrNull()
            if (ips.isNullOrEmpty() || ips.any { Radios.destinoDeFeedProibido(it) }) return null
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false; c.connectTimeout = 10_000; c.readTimeout = 20_000
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            range?.let { c.setRequestProperty("Range", it) }
            val cod = c.responseCode
            if (cod in 300..399) {
                val prox = c.getHeaderField("Location"); c.disconnect()
                if (prox.isNullOrEmpty()) return null
                u = URL(URL(u), prox).toString()
                if (!u.startsWith("https://") && !u.startsWith("http://")) return null
            } else return c
        }
        return null
    }

    /** Tamanho do arquivo (pelo Content-Range de um pedido de 1 byte), ou -1. */
    fun tamanho(url: String): Long {
        val c = abrir(url, "bytes=0-0") ?: return -1
        try {
            if (c.responseCode != 206) return -1
            return c.getHeaderField("Content-Range")?.substringAfterLast('/')?.trim()?.toLongOrNull() ?: -1
        } finally { c.disconnect() }
    }

    /** Os bytes [de] até [ate] (inclusive), ou null. */
    fun baixar(url: String, de: Long, ate: Long): ByteArray? {
        val c = abrir(url, "bytes=$de-$ate") ?: return null
        try {
            if (c.responseCode != 206) return null
            return c.inputStream.use { Http.readLimited(it, (ate - de + 1).toInt(), cut = true) }
        } finally { c.disconnect() }
    }

    /** Pede o resumo da Sofy e as propagandas do texto (uma chamada só, só quando alguém pede). */
    fun resumir(titulo: String, trechos: List<Transcricao.Trecho>, idioma: String): Pair<String, List<LongRange>>? {
        val corpo = JSONObject().put("title", titulo.take(200)).put("text", textoParaResumo(trechos)).toString().toByteArray()
        val c = URL("$SERVIDOR/v1/summary?lang=$idioma").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 60_000
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            c.setRequestProperty("Content-Type", "application/json")
            c.setFixedLengthStreamingMode(corpo.size)
            c.outputStream.use { it.write(corpo) }
            if (c.responseCode == 429) throw SemCota()
            if (c.responseCode != 200) return null
            return lerResumo(c.inputStream.use { Http.readLimited(it, 100_000, cut = false) }?.toString(Charsets.UTF_8))
        } finally { c.disconnect() }
    }

    /** Erro de cota: o servidor respondeu 429 (a da casa ou a de todos acabou por hoje). */
    class SemCota : Exception()

    /** Manda um pedaço ao servidor e devolve o JSON da resposta. */
    fun transcrever(ctx: Context, bytes: ByteArray, segundos: Float, idioma: String): String? {
        if (restanteHoje(ctx) < segundos / 60f) throw SemCota()
        val c = URL("$SERVIDOR/v1/transcribe?lang=$idioma").openConnection() as HttpURLConnection
        try {
            c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = 10_000; c.readTimeout = 90_000
            c.setRequestProperty("User-Agent", Http.USER_AGENT)
            c.setRequestProperty("Content-Type", "text/plain")
            val corpo = Base64.encode(bytes, Base64.NO_WRAP)
            c.setFixedLengthStreamingMode(corpo.size)
            c.outputStream.use { it.write(corpo) }
            when (c.responseCode) {
                200 -> { gastar(ctx, segundos / 60f); return c.inputStream.use { Http.readLimited(it, 1_000_000, cut = false) }?.toString(Charsets.UTF_8) }
                429 -> throw SemCota()
                else -> return null
            }
        } finally { c.disconnect() }
    }
}
