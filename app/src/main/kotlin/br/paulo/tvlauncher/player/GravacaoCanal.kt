package br.paulo.tvlauncher.player

import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Abre o canal para o gravador (14/11/2026): um fluxo de bytes MPEG-TS sem fim.
 *
 *  - Canal .ts (o normal do Xtream): o próprio GET HTTP, que nunca acaba.
 *  - Canal HLS (.m3u8): lê a lista, baixa os pedaços .ts em ordem e entrega emendados (TS emendado continua tocando).
 *    Lista "mestra" (várias qualidades): fica com a de maior qualidade. Pedaços fMP4 (raro em IPTV) não são aceitos.
 *
 * TLS: a verificação padrão do Android, nunca desligada. Puro (sem Android), com teste JVM (GravacaoTest).
 */
object GravacaoCanal {
    private const val AGENTE = "TvLauncher-Player/1.0"   // o mesmo do player: a lista vê o mesmo "aparelho"

    fun abrir(url: String, agente: String = ""): InputStream {
        val (c, final) = conectar(url, agente, leitura = 20_000)
        val tipo = c.contentType.orEmpty().lowercase()
        if (!ehHls(final, tipo)) return c.inputStream
        val texto = c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        return Hls(final, texto, agente)
    }

    fun ehHls(url: String, tipo: String) = url.substringBefore('?').endsWith(".m3u8", ignoreCase = true) ||
        "mpegurl" in tipo

    private fun conectar(endereco: String, agente: String, leitura: Int): Pair<HttpURLConnection, String> {
        var url = endereco
        repeat(5) {
            if (!M3u.urlAceita(url)) throw IOException("endereço recusado")
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = leitura
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", agente.ifEmpty { AGENTE })
            val codigo = c.responseCode
            if (codigo in 300..399) {
                val destino = c.getHeaderField("Location"); c.disconnect()
                url = URL(URL(url), destino ?: throw IOException("redirecionamento sem destino")).toString()
                return@repeat
            }
            if (codigo != 200) { c.disconnect(); throw IOException("HTTP $codigo") }
            return c to url
        }
        throw IOException("redirecionamentos demais")
    }

    private fun texto(url: String, agente: String): Pair<String, String> {
        val (c, final) = conectar(url, agente, leitura = 15_000)
        return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) } to final
    }

    // ---------------------------------------------------------------- HLS (lógica pura, testada)

    class Lista(val sequencia: Long, val duracaoAlvoS: Int, val pedacos: List<String>, val fim: Boolean)

    /** Lista mestra: o endereço da variante de maior BANDWIDTH; null se não for mestra. */
    fun variante(base: String, texto: String): String? {
        var melhor: Pair<Long, String>? = null
        var banda = -1L
        for (l in texto.lineSequence().map { it.trim() }) {
            if (l.startsWith("#EXT-X-STREAM-INF", ignoreCase = true)) {
                banda = Regex("BANDWIDTH=(\\d+)", RegexOption.IGNORE_CASE).find(l)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            } else if (banda >= 0 && l.isNotEmpty() && !l.startsWith("#")) {
                if (melhor == null || banda > melhor.first) melhor = banda to resolver(base, l)
                banda = -1
            }
        }
        return melhor?.second
    }

    fun lerLista(base: String, texto: String): Lista {
        var seq = 0L; var alvo = 6; var fim = false
        val pedacos = ArrayList<String>()
        for (l in texto.lineSequence().map { it.trim() }) when {
            l.startsWith("#EXT-X-MEDIA-SEQUENCE:", true) -> seq = l.substringAfter(':').trim().toLongOrNull() ?: 0
            l.startsWith("#EXT-X-TARGETDURATION:", true) -> alvo = l.substringAfter(':').trim().toIntOrNull() ?: 6
            l.startsWith("#EXT-X-ENDLIST", true) -> fim = true
            l.startsWith("#EXT-X-MAP", true) -> throw IOException("HLS com fMP4 não é gravado")
            l.isNotEmpty() && !l.startsWith("#") -> pedacos += resolver(base, l)
        }
        return Lista(seq, alvo.coerceIn(1, 30), pedacos, fim)
    }

    /** Os pedaços ainda não baixados: número de sequência > [ultimo]. Na primeira volta (ultimo < 0), só os 2 últimos. */
    fun novos(l: Lista, ultimo: Long): List<Pair<Long, String>> {
        val todos = l.pedacos.mapIndexed { i, u -> (l.sequencia + i) to u }
        return if (ultimo < 0) todos.takeLast(2) else todos.filter { it.first > ultimo }
    }

    fun resolver(base: String, rel: String): String = URL(URL(base), rel).toString()

    /** Fluxo que emenda os pedaços conforme a lista ao vivo cresce. */
    private class Hls(primeira: String, texto: String, private val agente: String) : InputStream() {
        private var endereco = variante(primeira, texto) ?: primeira
        private var lista: Lista? = if (endereco == primeira) lerLista(primeira, texto) else null
        private var ultimo = -1L
        private val fila = ArrayDeque<String>()
        private var atual: InputStream? = null
        private var fechado = false

        private fun proximo(): InputStream? {
            var vazias = 0
            while (fila.isEmpty()) {
                if (fechado) return null
                val l = lista ?: texto(endereco, agente).let { (t, f) -> endereco = f; lerLista(f, t) }
                lista = null
                val n = novos(l, ultimo)
                n.forEach { fila.addLast(it.second) }
                n.lastOrNull()?.let { ultimo = it.first }
                if (fila.isEmpty()) {
                    if (l.fim) return null
                    if (++vazias > 6) throw IOException("lista HLS parada")
                    Thread.sleep(l.duracaoAlvoS * 500L)
                }
            }
            return conectar(fila.removeFirst(), agente, 20_000).first.inputStream
        }

        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) <= 0) -1 else b[0].toInt() and 0xff }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            while (true) {
                val e = atual ?: proximo()?.also { atual = it } ?: return -1
                val n = e.read(b, off, len)
                if (n >= 0) return n
                runCatching { e.close() }; atual = null
            }
        }

        override fun close() { fechado = true; runCatching { atual?.close() }; atual = null }
    }
}
