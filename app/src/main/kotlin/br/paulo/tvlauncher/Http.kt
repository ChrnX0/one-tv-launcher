package br.paulo.tvlauncher

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * O GET de texto que o app inteiro usa (refatoração de 21/11/2026). Antes havia uma cópia em cada arquivo (AvisoChuva,
 * AvisoTemperatura, ClimaFontes, Previsão, Rádios, Podcasts...), com tempos, limites e User-Agent diferentes por acaso.
 *
 * Regras de todos os pedidos:
 * - [USER_AGENT] único e identificável (o MET Norway exige um agente com contato; os outros só agradecem).
 * - Teto de bytes: uma resposta que não acaba (um link que é o próprio áudio, um servidor quebrado) não enche a memória.
 *   [cut] = true corta no teto e segue com o que leu; false devolve null quando passa do teto.
 * - Só 200 vale. Outro código devolve null; erro de rede vira IOException, para quem chama decidir (ver [getTextOrNull]).
 * Quem precisa de cabeçalhos de API, POST ou de seguir redirecionamento conferindo o destino (AddonsTv, PlayerLogos,
 * Nuvem) continua com a própria conexão: são regras de segurança de cada um, não cópia.
 */
object Http {
    /** O nome do app para os servidores. Agentes exigidos por um serviço (OpenSubtitles, Trakt, listas IPTV) ficam lá. */
    const val USER_AGENT = "Sofaly/1.0 (+https://sofaly.tv)"
    const val MAX_BYTES = 4_000_000

    /**
     * GET de [url] e o corpo como texto UTF-8, ou null se a resposta não for 200 ou passar de [maxBytes] sem [cut].
     * [followRedirects] = false quando o destino foi conferido antes (um 302 levaria para outro host, ex.: 127.0.0.1).
     */
    @Throws(IOException::class)
    fun getText(
        url: String,
        connectMs: Int = 8_000,
        readMs: Int = 8_000,
        maxBytes: Int = MAX_BYTES,
        cut: Boolean = false,
        followRedirects: Boolean = true,
        userAgent: String = USER_AGENT,
        headers: Map<String, String> = emptyMap(),
    ): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs; c.readTimeout = readMs
            c.instanceFollowRedirects = followRedirects
            c.setRequestProperty("User-Agent", userAgent)
            for ((k, v) in headers) c.setRequestProperty(k, v)
            if (c.responseCode != 200) return null
            return c.inputStream.use { readLimited(it, maxBytes, cut) }?.toString(Charsets.UTF_8)
        } finally { c.disconnect() }
    }

    /** O mesmo [getText], mas erro de rede também vira null (para quem só quer "tem ou não tem"). */
    fun getTextOrNull(
        url: String,
        connectMs: Int = 8_000,
        readMs: Int = 8_000,
        maxBytes: Int = MAX_BYTES,
        cut: Boolean = false,
        followRedirects: Boolean = true,
    ): String? = try { getText(url, connectMs, readMs, maxBytes, cut, followRedirects) } catch (e: IOException) { null }

    /** Lê até [max] bytes. Passou do teto: corta ([cut]) ou devolve null. Puro (HttpTest). */
    fun readLimited(input: InputStream, max: Int, cut: Boolean): ByteArray? {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (true) {
            val n = input.read(buf, 0, minOf(buf.size, max + 1 - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
            if (out.size() > max) return if (cut) out.toByteArray().copyOf(max) else null
        }
        return out.toByteArray()
    }
}
