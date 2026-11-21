package br.paulo.tvlauncher.listen

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import br.paulo.tvlauncher.Http
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * "Voltar 30 s" na rádio ao vivo (proposta C do Ouvir, 21/11/2026).
 *
 * Por quê: o Apple e o TuneIn deixam voltar alguns segundos no ao vivo ("o que ele disse?"); rádio na TV não deixava.
 * Para quê: ⏪ (ou "Voltar 30 s" na prateleira Tocando) volta 30 s, quantas vezes quiser até ~4 min; "Ao vivo" volta.
 * Como: enquanto a rádio toca, uma segunda conexão grava o áudio num anel de [CAPACIDADE] bytes na memória ([Anel]).
 * Ao voltar, o player passa a ler do anel ([Leitor]) a partir de N segundos atrás (bytes = taxa × segundos) e segue
 * lendo o que a gravação continua trazendo, como um gravador de fita. O ao vivo normal nunca passa pelo anel: se a
 * gravação falhar, a rádio segue igual, só sem o botão. Só MP3 e AAC (dá para começar a tocar de qualquer byte);
 * HLS, Ogg e FLAC ficam sem. Custo: a rádio baixa duas vezes enquanto toca (~1 MB por minuto a 128 kbps).
 */
object AtrasoRadio {
    const val CAPACIDADE = 4 * 1024 * 1024
    const val PASSO_S = 30

    /** O formato deixa começar de qualquer ponto? (codec do catálogo, ou o endereço quando não diz). */
    fun suporta(r: Radios.Radio): Boolean {
        val u = r.url.substringBefore('?').lowercase()
        if (u.endsWith(".m3u8") || u.endsWith(".ogg") || u.endsWith(".opus") || u.endsWith(".flac")) return false
        val c = r.codec.uppercase()
        return c.isEmpty() || c.startsWith("MP3") || c.startsWith("AAC")
    }

    /** Bytes por segundo: o bitrate do catálogo; sem ele, o medido; sem os dois, 128 kbps. */
    fun taxa(bitrateKbps: Int, medidoBps: Long): Long = when {
        bitrateKbps in 16..1000 -> bitrateKbps * 1000L / 8
        medidoBps in 2_000..200_000 -> medidoBps
        else -> 16_000L
    }

    /** Onde começar a ler para voltar [segundos] a partir de [posAtual] (ao vivo = o fim do gravado), sem passar do mais velho. */
    fun destino(posAtual: Long, segundos: Int, taxa: Long, maisVelho: Long): Long = maxOf(maisVelho, posAtual - segundos * taxa)

    /** Quantos segundos atrás do ao vivo (para escrever "−1:30"). */
    fun atrasoS(posLeitura: Long, escrito: Long, taxa: Long): Int = if (taxa <= 0) 0 else ((escrito - posLeitura).coerceAtLeast(0) / taxa).toInt()

    /**
     * Anel de bytes: a gravação escreve sem parar, sobrescrevendo o mais velho; quem lê pede pela posição absoluta
     * (bytes desde o começo da gravação). Puro (AtrasoRadioTest).
     */
    class Anel(val capacidade: Int = CAPACIDADE) {
        private val buf = ByteArray(capacidade)
        @Volatile var escrito = 0L
            private set
        @Volatile var fechado = false
            private set
        private val trava = Object()

        val maisVelho: Long get() = maxOf(0L, escrito - capacidade)

        fun escrever(b: ByteArray, off: Int, n: Int) = synchronized(trava) {
            var o = off; var falta = n
            while (falta > 0) {
                val i = (escrito % capacidade).toInt()
                val k = minOf(falta, capacidade - i)
                System.arraycopy(b, o, buf, i, k)
                o += k; falta -= k; escrito += k
            }
            trava.notifyAll()
        }

        fun fechar() = synchronized(trava) { fechado = true; trava.notifyAll() }

        /**
         * Lê a partir de [pos] até [n] bytes em [dest]. Espera até [esperaMs] por dado novo. Devolve quantos leu,
         * 0 se nada chegou no tempo, -1 se fechou. [pos] mais velho que o anel guarda é puxado para o mais velho:
         * quem chama lê o novo [pos] por [ondeFicou].
         */
        var ondeFicou = 0L
            private set
        fun ler(pos: Long, dest: ByteArray, off: Int, n: Int, esperaMs: Long): Int = synchronized(trava) {
            var p = maxOf(pos, maisVelho)
            if (p >= escrito) {
                if (fechado) return -1
                trava.wait(esperaMs)
                p = maxOf(pos, maisVelho)
                if (p >= escrito) { ondeFicou = p; return if (fechado) -1 else 0 }
            }
            val disp = (escrito - p).toInt()
            val i = (p % capacidade).toInt()
            val k = minOf(n, disp, capacidade - i)
            System.arraycopy(buf, i, dest, off, k)
            ondeFicou = p + k
            k
        }
    }

    /** A segunda conexão: grava a rádio no [anel] até [parar]. Mede a taxa real para quando o catálogo não diz. */
    class Gravador(private val url: String, private val aoPrimeiro: () -> Unit = {}) {
        val anel = Anel()
        @Volatile private var ativo = true
        @Volatile var medidoBps = 0L
            private set
        @Volatile var falhou = false
            private set
        private var con: HttpURLConnection? = null

        fun comecar() = Thread({
            try {
                val c = abrir(url) ?: throw IOException("destino recusado")
                con = c
                val inicio = System.currentTimeMillis()
                var marcaT = 0L; var marcaB = 0L
                c.inputStream.use { ins ->
                    val b = ByteArray(16 * 1024)
                    while (ativo) {
                        val n = ins.read(b)
                        if (n < 0) break
                        anel.escrever(b, 0, n)
                        if (anel.escrito == n.toLong()) aoPrimeiro()   // os primeiros bytes: o botão "Voltar 30 s" pode aparecer
                        // Os primeiros 10 s vêm em rajada (o servidor manda um estoque): mede depois deles.
                        val t = System.currentTimeMillis() - inicio
                        if (t > 10_000 && marcaT == 0L) { marcaT = t; marcaB = anel.escrito }
                        if (marcaT > 0 && t - marcaT > 10_000) medidoBps = (anel.escrito - marcaB) * 1000 / (t - marcaT)
                    }
                }
            } catch (e: Exception) {
                if (ativo) { falhou = true; Log.w("TvLauncher.Ouvir", "gravação da rádio parou: ${e.javaClass.simpleName}") }
            } finally { anel.fechar() }
        }, "radio-atraso").apply { isDaemon = true; start() }

        fun parar() { ativo = false; runCatching { con?.disconnect() }; anel.fechar() }

        /** Segue até 5 redirecionamentos conferindo cada destino (a rede de casa vale: rádio do NAS; a própria TV não). */
        private fun abrir(url: String): HttpURLConnection? {
            var u = url
            repeat(6) {
                val ips = runCatching { InetAddress.getAllByName(URL(u).host).toList() }.getOrNull()
                if (ips.isNullOrEmpty() || ips.any { Radios.destinoProibido(it) }) return null
                val c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false; c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", Http.USER_AGENT)
                val cod = c.responseCode
                if (cod in 300..399) {
                    val prox = c.getHeaderField("Location"); c.disconnect()
                    if (prox.isNullOrEmpty()) return null
                    u = URL(URL(u), prox).toString()
                    if (!u.startsWith("http://") && !u.startsWith("https://")) return null
                } else return if (cod == 200) c else { c.disconnect(); null }
            }
            return null
        }
    }

    /** O que o player lê quando está atrasado: o anel, a partir de [inicio], esperando o que a gravação ainda traz. */
    @androidx.annotation.OptIn(markerClass = [UnstableApi::class])
    class Leitor(private val anel: Anel, private val inicio: Long) : BaseDataSource(true) {
        @Volatile var pos = inicio
            private set
        private var uri: Uri? = null
        override fun open(spec: DataSpec): Long {
            transferInitializing(spec)
            uri = spec.uri; pos = maxOf(pos, anel.maisVelho)   // reabrir (nova tentativa) segue de onde estava
            transferStarted(spec)
            return C.LENGTH_UNSET.toLong()
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            // Espera em fatias curtas: o player pode pedir para fechar (trocar de rádio) a qualquer momento.
            repeat(100) {
                val n = anel.ler(pos, buffer, offset, length, 200)
                if (n < 0) return C.RESULT_END_OF_INPUT
                if (n > 0) { pos = anel.ondeFicou; bytesTransferred(n); return n }
            }
            throw IOException("a gravação parou de chegar")
        }
        override fun getUri(): Uri? = uri
        override fun close() { if (uri != null) { uri = null; transferEnded() } }
    }
}
