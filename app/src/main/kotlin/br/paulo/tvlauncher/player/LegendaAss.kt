package br.paulo.tvlauncher.player

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.BaseRenderer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.FfNativo
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Contas puras da legenda ASS (15/11/2026), com teste (AssLogicaTest).
 *
 * O leitor de MKV do Media3 entrega cada fala ASS assim: "Dialogue: 0:00:00:00,H:MM:SS:CC," + o bloco do MKV
 * ("ReadOrder,Layer,Style,Name,MarginL,MarginR,MarginV,Effect,Text"). O primeiro tempo é sempre zero; o segundo é a
 * DURAÇÃO da fala (em centésimos). O início vem no tempo da amostra.
 */
object AssLogica {
    private val PREFIXO = "Dialogue: ".toByteArray(Charsets.US_ASCII)

    /** Fala separada: duração em ms e o bloco que o libass espera. Null se não for nesse formato. */
    class Fala(val duracaoMs: Long, val bloco: ByteArray)

    fun fala(amostra: ByteArray, tamanho: Int = amostra.size): Fala? {
        if (tamanho < PREFIXO.size + 22) return null
        for (i in PREFIXO.indices) if (amostra[i] != PREFIXO[i]) return null
        // Depois de "Dialogue: ": início (até a 1ª vírgula), fim (até a 2ª vírgula), e o resto.
        var v1 = -1; var v2 = -1
        for (i in PREFIXO.size until tamanho) {
            if (amostra[i] == ','.code.toByte()) { if (v1 < 0) v1 = i else { v2 = i; break } }
        }
        if (v1 < 0 || v2 < 0) return null
        val ini = tempo(String(amostra, PREFIXO.size, v1 - PREFIXO.size, Charsets.US_ASCII)) ?: return null
        val fim = tempo(String(amostra, v1 + 1, v2 - v1 - 1, Charsets.US_ASCII)) ?: return null
        return Fala(maxOf(fim - ini, 0), amostra.copyOfRange(v2 + 1, tamanho))
    }

    /** "H:MM:SS:CC" ou "H:MM:SS.CC" -> ms. */
    fun tempo(t: String): Long? {
        val p = t.trim().split(':', '.')
        if (p.size != 4) return null
        val n = p.map { it.toLongOrNull() ?: return null }
        return ((n[0] * 60 + n[1]) * 60 + n[2]) * 1000 + n[3] * 10
    }

    /** Arquivo .ass/.ssa inteiro (legenda de fora): começa com "[Script Info]", com ou sem BOM. */
    fun ehArquivo(d: ByteArray, tamanho: Int = d.size): Boolean {
        var i = 0
        if (tamanho >= 3 && d[0] == 0xEF.toByte() && d[1] == 0xBB.toByte() && d[2] == 0xBF.toByte()) i = 3
        while (i < tamanho && (d[i] == ' '.code.toByte() || d[i] == '\n'.code.toByte() || d[i] == '\r'.code.toByte())) i++
        val alvo = "[Script Info]".toByteArray(Charsets.US_ASCII)
        if (tamanho - i < alvo.size) return false
        for (k in alvo.indices) if (d[i + k].toInt().toChar().lowercaseChar() != alvo[k].toInt().toChar().lowercaseChar()) return false
        return true
    }

    /**
     * Tamanho da imagem de legenda: a área do vídeo na tela, limitada a [maxL] x [maxA] (o libass desenha cada pixel;
     * 1080p cheio custa caro na Sala). A imagem é esticada na tela pela LegendaImagem.
     */
    fun tamanho(areaL: Int, areaA: Int, maxL: Int = 1280, maxA: Int = 720): Pair<Int, Int> {
        if (areaL <= 0 || areaA <= 0) return maxL to maxA
        val f = minOf(1.0, maxL.toDouble() / areaL, maxA.toDouble() / areaA)
        return maxOf(2, (areaL * f).toInt() and 1.inv()) to maxOf(2, (areaA * f).toInt() and 1.inv())
    }
}

/**
 * O que a tela do player passa para a legenda ASS: área do vídeo, tamanho original do vídeo, escala da letra
 * (ideia 7) e as fontes anexadas ao MKV (chegam depois, lidas em segundo plano). Lido pela thread do player.
 */
class AssAjustes {
    @Volatile var areaL = 0; @Volatile var areaA = 0
    @Volatile var videoL = 0; @Volatile var videoA = 0
    @Volatile var escala = 1.0
    @Volatile var versao = 0   // muda a cada ajuste: o renderizador redesenha
    private val fontes = java.util.concurrent.CopyOnWriteArrayList<Pair<String, ByteArray>>()

    fun area(l: Int, a: Int) { if (l != areaL || a != areaA) { areaL = l; areaA = a; versao++ } }
    fun video(l: Int, a: Int) { if (l != videoL || a != videoA) { videoL = l; videoA = a; versao++ } }
    fun escala(f: Double) { if (f != escala) { escala = f; versao++ } }
    fun fonte(nome: String, dados: ByteArray) { fontes.add(nome to dados); versao++ }
    fun fontes(desde: Int): List<Pair<String, ByteArray>> = if (desde >= fontes.size) emptyList() else fontes.subList(desde, fontes.size).toList()
    val quantasFontes get() = fontes.size
}

/**
 * Faz o Media3 entregar a ASS crua (sem converter em texto simples) para o [AssRenderer]: diz "não sei" para SSA e
 * repassa o resto (SRT, WebVTT, PGS...) para os leitores normais do Media3.
 */
@OptIn(UnstableApi::class)
class LeitoresLegendaSemAss(private val base: SubtitleParser.Factory = DefaultSubtitleParserFactory()) : SubtitleParser.Factory {
    override fun supportsFormat(f: Format) = f.sampleMimeType != MimeTypes.TEXT_SSA && base.supportsFormat(f)
    override fun getCueReplacementBehavior(f: Format) = base.getCueReplacementBehavior(f)
    override fun create(f: Format) = base.create(f)
}

/**
 * Renderizador da legenda ASS/SSA pelo libass (15/11/2026, nativo/cola/legenda_ass.cc). Recebe as falas do arquivo
 * (ou o .ass inteiro de fora), desenha a tela de legenda no momento do vídeo e manda como um Cue de imagem do tamanho
 * do vídeo, que a LegendaImagem.Vista estica por cima. Assim efeitos, posições e fontes do arquivo saem como o autor fez.
 */
@OptIn(UnstableApi::class)
class AssRenderer(ctx: Context, private val saida: TextOutput, looperSaida: Looper, private val ajustes: AssAjustes) :
    BaseRenderer(C.TRACK_TYPE_TEXT) {

    companion object {
        private const val TAG = "AssRenderer"
        private const val INTERVALO_US = 40_000L   // 25 desenhos por segundo bastam para karaokê e movimento
        private const val BUFFERS = 3              // a tela pode estar desenhando a imagem anterior

        /** fonts.conf do fontconfig (fontes do Android + cache na pasta do app) e uma fonte padrão. */
        fun configurarFontes(ctx: Context): Pair<String, String?> {
            val pasta = File(ctx.noBackupFilesDir, "fontconfig").apply { mkdirs() }
            val conf = File(pasta, "fonts.conf")
            val cache = File(ctx.cacheDir, "fontconfig").apply { mkdirs() }
            val texto = """<?xml version="1.0"?><!DOCTYPE fontconfig SYSTEM "fonts.dtd">
<fontconfig><dir>/system/fonts</dir><dir>/product/fonts</dir><cachedir>${cache.absolutePath}</cachedir>
<alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>
<alias><family>Arial</family><prefer><family>Roboto</family></prefer></alias></fontconfig>
"""
            if (!conf.exists() || conf.readText() != texto) conf.writeText(texto)
            val padrao = listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf", "/system/fonts/DroidSans.ttf")
                .firstOrNull { File(it).exists() }
            return conf.absolutePath to padrao
        }
    }

    private val appCtx = ctx.applicationContext
    private val handler = Handler(looperSaida)
    private val entrada = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_NORMAL)
    private val formatoH = FormatHolder()
    private var lib = 0L
    private var faixa = 0L
    private var formato: Format? = null
    private var offsetUs = 0L
    private var fimEntrada = false
    private var ultimoDesenhoUs = Long.MIN_VALUE
    private var versaoVista = -1
    private var fontesPostas = 0
    private var larg = 0; private var alt = 0
    private var buffer: ByteBuffer? = null
    private val imagens = arrayOfNulls<Bitmap>(BUFFERS)
    private var proxima = 0
    private var mostrando = false

    override fun getName() = TAG

    override fun supportsFormat(f: Format): Int =
        if (f.sampleMimeType == MimeTypes.TEXT_SSA && FfNativo.disponivel) RendererCapabilities.create(C.FORMAT_HANDLED)
        else RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)

    override fun onStreamChanged(formats: Array<out Format>, inicioUs: Long, offsetUs: Long, id: MediaSource.MediaPeriodId) {
        this.offsetUs = offsetUs
        val f = formats.firstOrNull()
        if (f != formato) { formato = f; novaFaixa(f) }
    }

    private fun garantirLib(): Boolean {
        if (lib != 0L) return true
        val (conf, padrao) = configurarFontes(appCtx)
        lib = FfNativo.assAbrir(conf, padrao)
        if (lib == 0L) Log.w(TAG, "libass não abriu")
        return lib != 0L
    }

    private fun novaFaixa(f: Format?) {
        if (faixa != 0L) { FfNativo.assFecharFaixa(faixa); faixa = 0 }
        if (f == null || !garantirLib()) return
        // Faixa de dentro do MKV: [linha de formato, cabeçalho]. Legenda de fora: sem cabeçalho; o arquivo vem na amostra.
        val cab = f.initializationData.getOrNull(1)
        if (cab != null) faixa = FfNativo.assFaixa(lib, cab)
        ultimoDesenhoUs = Long.MIN_VALUE
    }

    override fun onPositionReset(posicaoUs: Long, juntando: Boolean, chave: Boolean) {
        fimEntrada = false
        ultimoDesenhoUs = Long.MIN_VALUE   // redesenha já no novo ponto
    }

    override fun render(posicaoUs: Long, decorridoUs: Long) {
        lerAmostras()
        if (faixa == 0L || lib == 0L) return
        ajustar()
        val agora = posicaoUs
        if (ultimoDesenhoUs != Long.MIN_VALUE && agora - ultimoDesenhoUs in 0 until INTERVALO_US) return
        val forcar = ultimoDesenhoUs == Long.MIN_VALUE
        ultimoDesenhoUs = agora
        val b = buffer ?: return
        val r = FfNativo.assDesenhar(lib, faixa, (agora - offsetUs) / 1000, b, forcar)
        when (r) {
            1 -> publicar(agora)
            2 -> if (mostrando || forcar) { mostrando = false; enviar(emptyList(), agora) }
        }
    }

    private fun lerAmostras() {
        while (!fimEntrada) {
            entrada.clear()
            when (readSource(formatoH, entrada, 0)) {
                C.RESULT_FORMAT_READ -> { val f = formatoH.format; if (f != formato) { formato = f; novaFaixa(f) } }
                C.RESULT_BUFFER_READ -> {
                    if (entrada.isEndOfStream) { fimEntrada = true; return }
                    val d = entrada.data ?: continue
                    d.flip()
                    val bytes = ByteArray(d.remaining()).also { d.get(it) }
                    receber(bytes, entrada.timeUs)
                }
                else -> return
            }
        }
    }

    private fun receber(bytes: ByteArray, tempoUs: Long) {
        if (!garantirLib()) return
        if (AssLogica.ehArquivo(bytes)) {   // .ass inteiro de fora: vira a faixa
            if (faixa != 0L) FfNativo.assFecharFaixa(faixa)
            faixa = FfNativo.assFaixaArquivo(lib, bytes)
            ultimoDesenhoUs = Long.MIN_VALUE
            return
        }
        if (faixa == 0L) faixa = FfNativo.assFaixa(lib, null)
        val fala = AssLogica.fala(bytes) ?: return
        FfNativo.assFala(faixa, fala.bloco, (tempoUs - offsetUs) / 1000, fala.duracaoMs)
    }

    /** Aplica área, tamanho do vídeo, escala e fontes novas, se mudaram. */
    private fun ajustar() {
        if (ajustes.versao == versaoVista && buffer != null) return
        versaoVista = ajustes.versao
        // Conta as que entregou (não "quantasFontes" relido depois): uma fonte chegando no meio ficava pulada para sempre.
        val novas = ajustes.fontes(fontesPostas)
        for ((nome, dados) in novas) FfNativo.assFonte(lib, nome, dados)
        fontesPostas += novas.size
        val (l, a) = AssLogica.tamanho(ajustes.areaL, ajustes.areaA)
        if (l != larg || a != alt || buffer == null) {
            larg = l; alt = a
            buffer = ByteBuffer.allocateDirect(l * a * 4).order(ByteOrder.nativeOrder())
            imagens.fill(null)   // sem recycle: a tela pode estar desenhando a anterior
        }
        FfNativo.assTamanho(lib, larg, alt, ajustes.videoL, ajustes.videoA)
        FfNativo.assEscala(lib, ajustes.escala)
        ultimoDesenhoUs = Long.MIN_VALUE
    }

    private fun publicar(tempoUs: Long) {
        val b = buffer ?: return
        val img = imagens[proxima] ?: Bitmap.createBitmap(larg, alt, Bitmap.Config.ARGB_8888).also { imagens[proxima] = it }
        proxima = (proxima + 1) % BUFFERS
        b.rewind()
        img.copyPixelsFromBuffer(b)
        b.rewind()
        val cue = Cue.Builder().setBitmap(img)
            .setPosition(0f).setPositionAnchor(Cue.ANCHOR_TYPE_START)
            .setLine(0f, Cue.LINE_TYPE_FRACTION).setLineAnchor(Cue.ANCHOR_TYPE_START)
            .setSize(1f).setBitmapHeight(1f).build()
        mostrando = true
        enviar(listOf(cue), tempoUs)
    }

    private fun enviar(cues: List<Cue>, tempoUs: Long) {
        val g = CueGroup(cues, tempoUs - offsetUs)
        handler.post { saida.onCues(g) }
    }

    override fun onDisabled() {
        if (mostrando) { mostrando = false; enviar(emptyList(), 0) }
        if (faixa != 0L) { FfNativo.assFecharFaixa(faixa); faixa = 0 }
        formato = null
        fimEntrada = false
    }

    override fun onReset() {
        if (lib != 0L) { FfNativo.assFechar(lib); lib = 0 }
        buffer = null
        versaoVista = -1; fontesPostas = 0
        // As imagens podem estar na tela ainda: o GC as solta (sem recycle aqui).
        for (i in imagens.indices) imagens[i] = null
    }

    override fun isReady() = true
    override fun isEnded() = fimEntrada
}
