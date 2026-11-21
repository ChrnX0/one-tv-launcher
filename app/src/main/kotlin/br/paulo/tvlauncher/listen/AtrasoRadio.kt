package br.paulo.tvlauncher.listen

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import br.paulo.tvlauncher.Http
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URL

/**
 * "Voltar 30 s" na rádio ao vivo (proposta C do Ouvir, 21/11/2026).
 *
 * Por quê: o Apple e o TuneIn deixam voltar alguns segundos no ao vivo ("o que ele disse?"); rádio na TV não deixava.
 * Para quê: ⏪ (ou "Voltar 30 s" na prateleira Tocando) volta 30 s, quantas vezes quiser; "Ao vivo" volta ao agora.
 * Como (revisto 21/11/2026, pedido do Paulo): UMA conexão só. O [Gravador] baixa a rádio e grava numa fita ([Anel]);
 * o player SEMPRE lê da fita ([Leitor]): no ao vivo, da ponta; atrasado, de N segundos atrás. Antes eram duas conexões
 * (o player e a gravação), o dobro de internet. Com pendrive ligado a fita é um arquivo nele ([armazem]: até 1 h, sem
 * gastar a memória interna da TV); sem pendrive, 4 MB na RAM (~4 min a 128 kbps). O título da música (ICY) passa a
 * ser lido pelo Gravador ([Icy]) e anotado na posição da fita, para mostrar a música que se OUVE, não a do ao vivo.
 * Só MP3 e AAC (dá para começar a tocar de qualquer byte); HLS, Ogg e FLAC tocam direto, sem o botão. Se a gravação não
 * consegue começar, a rádio toca direto como antes (OuvirServico).
 */
object AtrasoRadio {
    const val CAPACIDADE = 4 * 1024 * 1024
    const val PASSO_S = 30
    /** No pendrive: guarda até 1 h, entre 8 e 128 MB, e nunca mais que metade do livre. */
    const val PENDRIVE_S = 3600
    const val PENDRIVE_MIN = 8L * 1024 * 1024
    const val PENDRIVE_MAX = 128L * 1024 * 1024
    const val ARQUIVO = "radio-atraso.bin"

    /** Tamanho da fita no pendrive para a [taxa] (bytes/s) com [livre] bytes livres; 0 = não cabe (usa a RAM). Puro. */
    fun capacidadePendrive(taxa: Long, livre: Long): Long {
        val c = (PENDRIVE_S * taxa).coerceIn(PENDRIVE_MIN, PENDRIVE_MAX).coerceAtMost(livre / 2)
        return if (c < PENDRIVE_MIN) 0 else c
    }

    /** Onde a fita guarda os bytes: posição física [i] dentro de 0 until capacidade. */
    interface Armazem {
        val capacidade: Long
        val noPendrive: Boolean get() = false
        fun escrever(i: Long, b: ByteArray, off: Int, n: Int)
        fun ler(i: Long, dest: ByteArray, off: Int, n: Int)
        fun fechar() {}
    }

    class Memoria(cap: Int = CAPACIDADE) : Armazem {
        private val buf = ByteArray(cap)
        override val capacidade = cap.toLong()
        override fun escrever(i: Long, b: ByteArray, off: Int, n: Int) = System.arraycopy(b, off, buf, i.toInt(), n)
        override fun ler(i: Long, dest: ByteArray, off: Int, n: Int) = System.arraycopy(buf, i.toInt(), dest, off, n)
    }

    /**
     * Arquivo de tamanho fixo (pendrive). Começa vazio e cresce na primeira volta (sem pré-alocar: no FAT de um pendrive
     * lento, reservar 60 MB de zeros levaria segundos antes do som). Apagado ao [fechar].
     */
    class Arquivo(private val f: File, override val capacidade: Long) : Armazem {
        private val raf = RandomAccessFile(f, "rw").also { it.setLength(0) }
        override val noPendrive = true
        override fun escrever(i: Long, b: ByteArray, off: Int, n: Int) { raf.seek(i); raf.write(b, off, n) }
        override fun ler(i: Long, dest: ByteArray, off: Int, n: Int) { raf.seek(i); raf.readFully(dest, off, n) }
        override fun fechar() { runCatching { raf.close() }; f.delete() }
    }

    /**
     * A fita para esta rádio: arquivo na pasta do app no pendrive (escrever ali não pede permissão) quando há pendrive
     * com espaço; senão a RAM. [taxa] em bytes/s.
     */
    fun armazem(ctx: Context, taxa: Long): Armazem {
        val dir = runCatching {
            ctx.getExternalFilesDirs(null).filterNotNull().firstOrNull {
                Environment.isExternalStorageRemovable(it) && Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED
            }
        }.getOrNull()
        if (dir != null) {
            val cap = capacidadePendrive(taxa, runCatching { dir.usableSpace }.getOrDefault(0L))
            if (cap > 0) runCatching { return Arquivo(File(dir.apply { mkdirs() }, ARQUIVO), cap) }
                .onFailure { Log.w("TvLauncher.Ouvir", "fita no pendrive falhou: ${it.javaClass.simpleName}") }
        }
        return Memoria()
    }

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
    class Anel(val armazem: Armazem = Memoria()) {
        constructor(capacidade: Int) : this(Memoria(capacidade))
        val capacidade: Long = armazem.capacidade
        @Volatile var escrito = 0L
            private set
        @Volatile var fechado = false
            private set
        private val trava = Object()

        val maisVelho: Long get() = maxOf(0L, escrito - capacidade)

        fun escrever(b: ByteArray, off: Int, n: Int) = synchronized(trava) {
            var o = off; var falta = n
            while (falta > 0) {
                val i = escrito % capacidade
                val k = minOf(falta.toLong(), capacidade - i).toInt()
                armazem.escrever(i, b, o, k)
                o += k; falta -= k; escrito += k
            }
            trava.notifyAll()
        }

        fun fechar() = synchronized(trava) { fechado = true; trava.notifyAll() }

        /** Fim de tudo (rádio trocada ou parada): solta o arquivo do pendrive. */
        fun soltar() = synchronized(trava) { fechado = true; armazem.fechar(); trava.notifyAll() }

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
            val i = p % capacidade
            val k = minOf(n.toLong(), escrito - p, capacidade - i).toInt()
            armazem.ler(i, dest, off, k)
            ondeFicou = p + k
            k
        }
    }

    /**
     * Tira do fluxo os blocos de metadados ICY (a cada [metaint] bytes de áudio vem 1 byte de tamanho × 16 e o texto,
     * com `StreamTitle='Artista - Música';`) e entrega só o áudio. Puro (AtrasoRadioTest). Mesmas regras de texto do
     * leitor ICY do media3: UTF-8, e Latin-1 quando o UTF-8 não fecha.
     */
    class Icy(private val metaint: Int, private val aoTitulo: (String) -> Unit) {
        private var faltaDados = metaint
        private var faltaMeta = 0
        private var lendoTamanho = false
        private val meta = ByteArrayOutputStream()

        fun filtrar(b: ByteArray, n: Int, audio: (ByteArray, Int, Int) -> Unit) {
            var i = 0
            while (i < n) {
                when {
                    faltaMeta > 0 -> {
                        val k = minOf(faltaMeta, n - i); meta.write(b, i, k); i += k; faltaMeta -= k
                        if (faltaMeta == 0) { titulo(meta.toByteArray())?.let(aoTitulo); faltaDados = metaint }
                    }
                    faltaDados > 0 -> { val k = minOf(faltaDados, n - i); audio(b, i, k); i += k; faltaDados -= k }
                    else -> {
                        val len = (b[i].toInt() and 0xff) * 16; i++
                        if (len == 0) faltaDados = metaint else { faltaMeta = len; meta.reset() }
                    }
                }
            }
        }

        companion object {
            private val TITULO = Regex("StreamTitle='(.*?)';", RegexOption.DOT_MATCHES_ALL)
            fun titulo(bytes: ByteArray): String? {
                val utf = String(bytes, Charsets.UTF_8)
                val txt = if ('\uFFFD' in utf) String(bytes, Charsets.ISO_8859_1) else utf
                return TITULO.find(txt.trimEnd('\u0000'))?.groupValues?.get(1)?.trim()
            }
        }
    }

    /**
     * A única conexão com a rádio: grava na fita ([anel]) até [parar]. Pede os títulos ICY e anota cada um na posição da
     * fita em que começou ([tituloEm]). Mede a taxa real para quando o catálogo não diz.
     */
    class Gravador(private val url: String, armazem: Armazem = Memoria(), private val aoPrimeiro: () -> Unit = {}) {
        val anel = Anel(armazem)
        @Volatile private var ativo = true
        @Volatile var medidoBps = 0L
            private set
        @Volatile var falhou = false
            private set
        private var con: HttpURLConnection? = null
        private val titulos = ArrayList<Pair<Long, String>>()   // (posição na fita, título), em ordem

        /** O título que vale na posição [pos] da fita (a música que está tocando ali), ou null se nenhum chegou. */
        fun tituloEm(pos: Long): String? = synchronized(titulos) { titulos.lastOrNull { it.first <= pos }?.second }

        fun comecar() = Thread({
            try {
                val c = abrir(url) ?: throw IOException("destino recusado")
                con = c
                val metaint = c.getHeaderField("icy-metaint")?.trim()?.toIntOrNull()?.takeIf { it in 1..1_000_000 }
                val icy = metaint?.let { Icy(it) { t -> synchronized(titulos) {
                    if (titulos.lastOrNull()?.second != t) { titulos.add(anel.escrito to t); if (titulos.size > 200) titulos.removeAt(0) }
                } } }
                val inicio = System.currentTimeMillis()
                var marcaT = 0L; var marcaB = 0L
                c.inputStream.use { ins ->
                    val b = ByteArray(16 * 1024)
                    while (ativo) {
                        val n = ins.read(b)
                        if (n < 0) break
                        val antes = anel.escrito
                        if (icy != null) icy.filtrar(b, n) { x, o, k -> anel.escrever(x, o, k) } else anel.escrever(b, 0, n)
                        if (antes == 0L && anel.escrito > 0) aoPrimeiro()   // os primeiros bytes: dá para tocar e voltar
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

        fun parar() { ativo = false; runCatching { con?.disconnect() }; anel.soltar() }

        /** Segue até 5 redirecionamentos conferindo cada destino (a rede de casa vale: rádio do NAS; a própria TV não). */
        private fun abrir(url: String): HttpURLConnection? {
            var u = url
            repeat(6) {
                val ips = runCatching { InetAddress.getAllByName(URL(u).host).toList() }.getOrNull()
                if (ips.isNullOrEmpty() || ips.any { Radios.destinoProibido(it) }) return null
                val c = URL(u).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = false; c.connectTimeout = 10_000; c.readTimeout = 20_000
                c.setRequestProperty("User-Agent", Http.USER_AGENT)
                c.setRequestProperty("Icy-MetaData", "1")   // o título da música vem junto e o Icy separa
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

    /** O que o player lê: a fita, a partir de [inicio] (ponta = ao vivo), esperando o que a gravação ainda traz. */
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
