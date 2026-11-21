package br.paulo.tvlauncher.player

import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.text.SubtitleParser
import br.paulo.tvlauncher.FfNativo
import br.paulo.tvlauncher.LeitorNativo
import br.paulo.tvlauncher.home.abrir
import java.io.EOFException
import java.io.IOException

/**
 * Ideia 1 (15/11/2026): formatos antigos que o Media3 não abre, lidos pelo libavformat (nativo/cola/arquivo.cc).
 * Contas puras com teste (FfFormatosTest).
 */
object FfFormatos {
    /** Formatos (nomes do FFmpeg) que vão para o nosso leitor. MPEG-PS/VOB e MKV/MP4 ficam com o Media3. */
    val ACEITOS = setOf("avi", "asf", "rm", "flv")

    /** Certeza mínima (0-100) do FFmpeg para aceitar; abaixo disso é chute pela extensão. */
    const val CERTEZA_MIN = 25

    /** Interpreta "nome:certeza" de arquivoSondar. FLV só quando o vídeo NÃO é H.264/HEVC (esse o Media3 já toca no chip). */
    fun aceita(sonda: String?, inicio: ByteArray): Boolean {
        val nome = sonda?.substringBefore(':') ?: return false
        val certeza = sonda.substringAfter(':').toIntOrNull() ?: return false
        if (certeza < CERTEZA_MIN || nome !in ACEITOS) return false
        if (nome == "flv") return flvVideoAntigo(inicio)
        return true
    }

    /**
     * FLV: o primeiro bloco de vídeo diz o codec nos 4 bits de baixo do 1º byte de dados (7 = H.264, 12 = HEVC).
     * Cabeçalho de 9 bytes (ou o que ele disser) + 4 do "tamanho anterior"; cada bloco: tipo(1) tamanho(3) tempo(4) id(3).
     */
    fun flvVideoAntigo(d: ByteArray): Boolean {
        if (d.size < 13 || d[0] != 'F'.code.toByte() || d[1] != 'L'.code.toByte() || d[2] != 'V'.code.toByte()) return false
        // Sem sinal (Long): com d[5] >= 0x80 o deslocamento ficava negativo e d[p] estourava dentro do sniff.
        val cab = ((d[5].toLong() and 0xFF) shl 24) or ((d[6].toLong() and 0xFF) shl 16) or ((d[7].toLong() and 0xFF) shl 8) or (d[8].toLong() and 0xFF)
        if (cab < 9 || cab > d.size) return false
        var p = cab.toInt() + 4
        while (p + 11 < d.size) {
            val tipo = d[p].toInt() and 0x1F
            val tam = ((d[p + 1].toInt() and 0xFF) shl 16) or ((d[p + 2].toInt() and 0xFF) shl 8) or (d[p + 3].toInt() and 0xFF)
            if (tipo == 9) {
                val codec = d[p + 11].toInt() and 0x0F
                return codec != 7 && codec != 12
            }
            p += 11 + tam + 4
        }
        return false   // sem vídeo no começo: deixa com o Media3
    }

    /** Distância máxima (bytes) que vale pular lendo para frente em vez de abrir outra conexão. */
    const val PULO_MAX = 2L * 1024 * 1024
}

/** Põe o [FfExtrator] junto dos leitores do Media3: na frente, mas ele só aceita os formatos de [FfFormatos.ACEITOS]. */
@OptIn(UnstableApi::class)
class FfExtratores(private val base: ExtractorsFactory, private val dados: DataSource.Factory) : ExtractorsFactory {
    // Os ajustes passam para os leitores do Media3. Sem isto (visto na Sala, 15/11), o DefaultMediaSourceFactory
    // entregava o leitor de legendas sem ASS a este embrulho, que o ignorava: o MKV lia a ASS como texto simples
    // e o libass nunca a recebia.
    override fun setSubtitleParserFactory(f: SubtitleParser.Factory): ExtractorsFactory { base.setSubtitleParserFactory(f); return this }
    override fun experimentalSetTextTrackTranscodingEnabled(ligado: Boolean): ExtractorsFactory { base.experimentalSetTextTrackTranscodingEnabled(ligado); return this }
    override fun experimentalSetCodecsToParseWithinGopSampleDependencies(c: Int): ExtractorsFactory { base.experimentalSetCodecsToParseWithinGopSampleDependencies(c); return this }
    override fun setParseHagcMetadata(ligado: Boolean): ExtractorsFactory { base.setParseHagcMetadata(ligado); return this }
    override fun createExtractors(): Array<Extractor> = base.createExtractors()
    override fun createExtractors(uri: Uri, cabecalhos: Map<String, List<String>>): Array<Extractor> {
        val normais = base.createExtractors(uri, cabecalhos)
        return if (FfNativo.disponivel) arrayOf<Extractor>(FfExtrator(uri, dados)) + normais else normais
    }
}

/**
 * O leitor em si. O libavformat pede bytes em qualquer posição (o índice do AVI fica no fim); [Leitor] atende pela
 * própria leitura do Media3 quando a posição bate (assim o Media3 controla quanto baixa à frente) e por uma segunda
 * conexão quando precisa pular para longe (cabeçalho, índice).
 */
@OptIn(UnstableApi::class)
class FfExtrator(private val uri: Uri, private val dados: DataSource.Factory) : Extractor {
    companion object {
        private const val TAG = "FfExtrator"
        /** Maior pacote aceito (um quadro 4K sem compressão tem ~25 MB). */
        const val MAX_PACOTE = 128 * 1024 * 1024
    }

    private lateinit var saida: ExtractorOutput
    @Volatile private var ctx = 0L
    /**
     * O AVFormatContext não é seguro entre threads: o ExoPlayer pede pontos de busca (getSeekPoints) na thread de
     * reprodução enquanto a de carga está dentro de arquivoLerPacote, e FLV/ASF/RM realocam o índice ao ler (SIGSEGV
     * aleatório numa busca). Tudo que toca o ctx passa por esta trava; a busca não espera (tryLock) e, ocupada, usa o
     * ponto aproximado.
     */
    private val trava = java.util.concurrent.locks.ReentrantLock()
    private val leitor = Leitor()
    private val faixas = HashMap<Int, TrackOutput>()
    private val ultimoTempo = HashMap<Int, Long>()
    private var buscarPara = C.TIME_UNSET
    private var pacote = ByteArray(256 * 1024)
    private val embrulho = ParsableByteArray()

    override fun sniff(input: ExtractorInput): Boolean {
        if (!FfNativo.disponivel) return false
        val inicio = ByteArray(8192)
        var n = 0
        while (n < inicio.size) {
            val r = input.peek(inicio, n, inicio.size - n)
            if (r == C.RESULT_END_OF_INPUT) break
            n += r
        }
        val pedaco = inicio.copyOf(n)
        return FfFormatos.aceita(FfNativo.arquivoSondar(pedaco), pedaco)
    }

    override fun init(output: ExtractorOutput) { saida = output }

    override fun read(input: ExtractorInput, posicao: PositionHolder): Int {
        leitor.entrada = input
        trava.lock()
        try {
            if (ctx == 0L) abrir()
            if (buscarPara != C.TIME_UNSET) { FfNativo.arquivoBuscar(ctx, buscarPara); buscarPara = C.TIME_UNSET; ultimoTempo.clear() }
            val n = FfNativo.arquivoLerPacote(ctx)
            // O erro de rede guardado vem ANTES do "fim": o leitor devolve -1 ao FFmpeg quando a rede falha, e o FFmpeg
            // chama isso de fim de arquivo. Antes, um AVI/WMV pela rede "terminava" no meio, sem nova tentativa.
            leitor.erro?.let { leitor.erro = null; throw it }
            if (n == -1) return Extractor.RESULT_END_OF_INPUT
            if (n < 0) throw IOException("FFmpeg não conseguiu ler o arquivo")
            val i = FfNativo.arquivoPacoteFaixa(ctx)
            val t = faixas[i] ?: return Extractor.RESULT_CONTINUE
            // Teto: um AVI ruim declarando pacote de GB estourava o n + n/2 (Int) ou a memória do player.
            if (n > MAX_PACOTE) throw IOException("pacote grande demais ($n bytes)")
            if (pacote.size < n) pacote = ByteArray(minOf(n.toLong() + n / 2, MAX_PACOTE.toLong()).toInt())
            FfNativo.arquivoPacoteCopiar(ctx, pacote)
            var tempo = FfNativo.arquivoPacoteTempoUs(ctx)
            if (tempo == Long.MIN_VALUE) tempo = (ultimoTempo[i] ?: 0L) + maxOf(FfNativo.arquivoPacoteDuracaoUs(ctx), 0)
            ultimoTempo[i] = tempo
            embrulho.reset(pacote, n)
            t.sampleData(embrulho, n)
            t.sampleMetadata(tempo, if (FfNativo.arquivoPacoteChave(ctx)) C.BUFFER_FLAG_KEY_FRAME else 0, n, 0, null)
            return Extractor.RESULT_CONTINUE
        } finally {
            trava.unlock()
            leitor.entrada = null
        }
    }

    private fun abrir() {
        ctx = FfNativo.arquivoAbrir(leitor, true)
        leitor.erro?.let {
            // A rede caiu durante a abertura: fecha o que o FFmpeg chegou a abrir, para a nova tentativa abrir de novo
            // e declarar as faixas (com ctx != 0 ela pulava abrir() e o player ficava carregando para sempre).
            leitor.erro = null
            if (ctx != 0L) { FfNativo.arquivoFechar(ctx); ctx = 0 }
            throw it
        }
        if (ctx == 0L) throw IOException("FFmpeg não abriu o arquivo")
        val n = FfNativo.arquivoFaixas(ctx)
        for (i in 0 until n) {
            val info = FfNativo.arquivoFaixaInfo(ctx, i) ?: continue
            val codec = FfNativo.arquivoFaixaCodec(ctx, i) ?: continue
            val tipo = info[0].toInt()
            if (tipo != 0 && tipo != 1) continue
            val f = Format.Builder().setId(i.toString()).setCodecs(codec)
                .setLanguage(FfNativo.arquivoFaixaMeta(ctx, i, "language")).setLabel(FfNativo.arquivoFaixaMeta(ctx, i, "title"))
            val extra = FfNativo.arquivoFaixaExtra(ctx, i)
            // Áudio: o 2º bloco leva o tamanho do bloco e os bits do contêiner (FfCodecs.parametrosAudio).
            if (tipo == 1) f.setInitializationData(listOf(extra ?: ByteArray(0), FfCodecs.empacotarAudio(info[9].toInt(), info[8].toInt())))
            else extra?.let { f.setInitializationData(listOf(it)) }
            if (info[7] > 0) f.setAverageBitrate(info[7].toInt())
            val faixa = if (tipo == 0) {
                f.setSampleMimeType(FfCodecs.MIME_VIDEO_FF).setWidth(info[1].toInt()).setHeight(info[2].toInt())
                if (info[6] > 0) f.setFrameRate(info[6] / 1000f)
                saida.track(i, C.TRACK_TYPE_VIDEO)
            } else {
                f.setSampleMimeType(FfCodecs.MIME_AUDIO_FF).setSampleRate(info[3].toInt()).setChannelCount(info[4].toInt())
                saida.track(i, C.TRACK_TYPE_AUDIO)
            }
            faixa.format(f.build())
            faixas[i] = faixa
        }
        saida.endTracks()
        val duracao = FfNativo.arquivoDuracaoUs(ctx).let { if (it > 0) it else C.TIME_UNSET }
        saida.seekMap(object : SeekMap {
            override fun isSeekable() = duracao != C.TIME_UNSET && leitor.tamanhoTotal > 0
            override fun getDurationUs() = duracao
            override fun getSeekPoints(t: Long): SeekMap.SeekPoints {
                // Lê o ctx de agora (não uma cópia: depois do release ela apontava para memória liberada).
                val p = if (trava.tryLock()) try { ctx.takeIf { it != 0L }?.let { FfNativo.arquivoPontoBusca(it, t) } } finally { trava.unlock() } else null
                return SeekMap.SeekPoints(if (p != null && p[1] >= 0) SeekPoint(maxOf(p[0], 0), p[1]) else SeekPoint(t, 0))
            }
        })
        Log.i(TAG, "aberto: $n faixas, ${duracao / 1_000_000} s")
    }

    override fun seek(posicao: Long, tempoUs: Long) {
        leitor.posicaoEntrada = posicao
        leitor.erro = null   // erro de antes da busca não vale para a leitura nova
        if (ctx != 0L) buscarPara = tempoUs
    }

    override fun release() {
        trava.lock()
        try { if (ctx != 0L) { FfNativo.arquivoFechar(ctx); ctx = 0 } } finally { trava.unlock() }
        leitor.fecharLado()
    }

    /** O [LeitorNativo] híbrido descrito acima. Só a thread de carga do Media3 o usa. */
    private inner class Leitor : LeitorNativo {
        var entrada: ExtractorInput? = null
        var posicaoEntrada = 0L
        var erro: IOException? = null
        private var lado: DataSource? = null
        private var posLado = -1L
        var tamanhoTotal = -1L; private set

        override fun tamanho(): Long {
            val e = entrada ?: return tamanhoTotal
            if (tamanhoTotal < 0 && e.length != C.LENGTH_UNSET.toLong()) tamanhoTotal = e.length
            return tamanhoTotal
        }

        override fun ler(posicao: Long, destino: ByteArray, n: Int): Int = try {
            val e = entrada
            if (e != null && posicao >= e.position && posicao - e.position <= FfFormatos.PULO_MAX) {
                if (posicao > e.position) e.skipFully((posicao - e.position).toInt())
                val r = e.read(destino, 0, n)
                if (r == C.RESULT_END_OF_INPUT) -1 else r
            } else lerDoLado(posicao, destino, n)
        } catch (x: EOFException) { -1 } catch (x: IOException) { erro = x; -1 }

        private fun lerDoLado(posicao: Long, destino: ByteArray, n: Int): Int {
            if (lado == null || posicao != posLado) {
                fecharLado()
                val d = dados.createDataSource()
                d.open(DataSpec.Builder().setUri(uri).setPosition(posicao).build())
                lado = d; posLado = posicao
            }
            val r = lado!!.read(destino, 0, n)
            if (r == C.RESULT_END_OF_INPUT) return -1
            posLado += r
            return r
        }

        fun fecharLado() { try { lado?.close() } catch (_: IOException) {}; lado = null; posLado = -1 }
    }
}
