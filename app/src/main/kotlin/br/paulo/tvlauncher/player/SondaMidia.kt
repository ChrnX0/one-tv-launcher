package br.paulo.tvlauncher.player

import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import br.paulo.tvlauncher.Config
import br.paulo.tvlauncher.FfNativo
import br.paulo.tvlauncher.LeitorNativo
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * Capítulos (ideia 5, 15/11/2026): contas puras, com teste (CapitulosTest).
 */
object Capitulos {
    data class Capitulo(val inicioMs: Long, val fimMs: Long, val titulo: String?)

    /**
     * Monta a lista a partir de [inicio fim inicio fim ...] (o que o FFmpeg dá; fim pode vir inválido) e dos títulos.
     * Fim inválido = começo do próximo (ou a duração, no último). Ordenado e sem capítulos de tamanho zero.
     */
    fun montar(tempos: LongArray, titulos: List<String?>, duracaoMs: Long): List<Capitulo> {
        val ini = (0 until tempos.size / 2).map { Triple(tempos[2 * it], tempos[2 * it + 1], titulos.getOrNull(it)) }
            .filter { it.first >= 0 }.sortedBy { it.first }
        return ini.mapIndexedNotNull { i, (a, b, t) ->
            val prox = ini.getOrNull(i + 1)?.first ?: duracaoMs
            val fim = if (b > a && (prox <= 0 || b <= prox)) b else prox
            if (fim <= a) null else Capitulo(a, fim, t?.trim()?.takeIf { it.isNotEmpty() })
        }
    }

    // Títulos que indicam abertura em fansubs e Blu-rays (inglês, português, espanhol, japonês romanizado).
    private val ABERTURA = Regex("""^(op|opening|intro|introdu[cç][aã]o|abertura|apertura|opening credits|credits|main titles?)\b""", RegexOption.IGNORE_CASE)

    /** Capítulo da abertura, se algum título disser (e durar entre 20 s e 4 min, para não confundir com o episódio). */
    fun abertura(caps: List<Capitulo>): Capitulo? = caps.firstOrNull { c ->
        val t = c.titulo ?: return@firstOrNull false
        ABERTURA.containsMatchIn(t) && (c.fimMs - c.inicioMs) in 20_000L..240_000L
    }

    /** Começo do próximo capítulo depois de [posMs], ou -1 no último. */
    fun proximo(caps: List<Capitulo>, posMs: Long): Long = caps.firstOrNull { it.inicioMs > posMs + 1_000 }?.inicioMs ?: -1L

    /**
     * Começo do capítulo atual ou do anterior: como o ⏮ de um player de DVD, se já passaram mais de 3 s do começo
     * deste, volta ao começo dele; senão, vai ao anterior.
     */
    fun anterior(caps: List<Capitulo>, posMs: Long): Long {
        val i = caps.indexOfLast { it.inicioMs <= posMs }
        if (i < 0) return 0L
        return if (posMs - caps[i].inicioMs > 3_000 || i == 0) caps[i].inicioMs else caps[i - 1].inicioMs
    }

    /** Fontes anexadas ao MKV (pelo tipo ou, se vier sem tipo, pela extensão do nome). */
    fun ehFonte(mime: String?, nome: String?): Boolean {
        val m = mime?.lowercase().orEmpty()
        if (m.startsWith("font/") || m.contains("truetype") || m.contains("opentype") || m.contains("font-ttf") || m.contains("font-otf")) return true
        val n = nome?.lowercase().orEmpty()
        return n.endsWith(".ttf") || n.endsWith(".otf") || n.endsWith(".ttc")
    }

    /** Tamanho da miniatura (ideia 3): [largura] fixa, altura pela proporção do vídeo, ambos pares. */
    fun tamanhoMiniatura(videoL: Int, videoA: Int, largura: Int = 240): Pair<Int, Int> {
        if (videoL <= 0 || videoA <= 0) return largura to (largura * 9 / 16 and 1.inv())
        return largura to maxOf(2, (largura.toLong() * videoA / videoL).toInt() and 1.inv())
    }
}

/**
 * Lê o arquivo em segundo plano pelo libavformat (sessão própria, separada da do player): capítulos, fontes do MKV e
 * miniaturas. Uma thread só; os pedidos de miniatura atrasados são descartados (vale o último).
 */
@OptIn(UnstableApi::class)
class SondaMidia(private val uri: Uri, private val dados: DataSource.Factory) {
    companion object { private const val TAG = "SondaMidia" }

    private val fila = Executors.newSingleThreadExecutor { r -> Thread(r, "SondaMidia").apply { isDaemon = true } }
    private val leitor = LeitorDados()
    private var ctx = 0L
    @Volatile private var fechada = false
    private val pedidoMiniatura = AtomicLong(-1)

    /** Abre e devolve capítulos e fontes anexadas (na thread da sonda; [pronto] é chamado nela também). */
    fun abrir(pronto: (capitulos: List<Capitulos.Capitulo>, fontes: List<Pair<String, ByteArray>>, videoL: Int, videoA: Int) -> Unit) {
        if (!FfNativo.disponivel) return
        fila.execute {
            try {
                ctx = FfNativo.arquivoAbrir(leitor, false)
                if (ctx == 0L || fechada) return@execute
                val n = FfNativo.arquivoFaixas(ctx)
                val fontes = ArrayList<Pair<String, ByteArray>>()
                var vl = 0; var va = 0
                for (i in 0 until n) {
                    val info = FfNativo.arquivoFaixaInfo(ctx, i) ?: continue
                    if (info[0] == 0L && vl == 0) { vl = info[1].toInt(); va = info[2].toInt() }
                    if (info[0] != 3L) continue
                    val nome = FfNativo.arquivoFaixaMeta(ctx, i, "filename")
                    if (!Capitulos.ehFonte(FfNativo.arquivoFaixaMeta(ctx, i, "mimetype"), nome)) continue
                    FfNativo.arquivoFaixaExtra(ctx, i)?.let { fontes += (nome ?: "fonte$i") to it }
                }
                val tempos = FfNativo.arquivoCapitulos(ctx)
                val titulos = (0 until tempos.size / 2).map { FfNativo.arquivoCapituloTitulo(ctx, it) }
                val dur = FfNativo.arquivoDuracaoUs(ctx).let { if (it > 0) it / 1000 else -1L }
                pronto(Capitulos.montar(tempos, titulos, dur), fontes, vl, va)
            } catch (e: Throwable) {
                Log.w(TAG, "sonda falhou: $e")
            }
        }
    }

    /**
     * Miniatura em [tempoMs] (ideia 3). Se chegar outro pedido antes deste começar, este é pulado. [pronto] recebe o
     * Bitmap (ou nada, se o arquivo não deixou), na thread da sonda.
     */
    fun miniatura(tempoMs: Long, largura: Int, altura: Int, pronto: (Long, Bitmap) -> Unit) {
        if (!FfNativo.disponivel) return
        pedidoMiniatura.set(tempoMs)
        fila.execute {
            val t = pedidoMiniatura.getAndSet(-1)
            if (t < 0 || ctx == 0L || fechada) return@execute
            val buf = ByteBuffer.allocateDirect(largura * altura * 4)
            if (!FfNativo.arquivoMiniatura(ctx, t * 1000, buf, largura, altura)) return@execute
            val bmp = Bitmap.createBitmap(largura, altura, Bitmap.Config.ARGB_8888)
            buf.rewind(); bmp.copyPixelsFromBuffer(buf)
            pronto(t, bmp)
        }
    }

    fun fechar() {
        fechada = true
        fila.execute {
            if (ctx != 0L) { FfNativo.arquivoFechar(ctx); ctx = 0 }
            leitor.fechar()
        }
        fila.shutdown()
    }

    /** Leitura com posição livre por um DataSource do app (rede, pastas, nuvem): reabre só quando pula. */
    private inner class LeitorDados : LeitorNativo {
        private var fonte: DataSource? = null
        private var pos = -1L
        private var total = -2L

        override fun tamanho(): Long {
            if (total == -2L) {
                total = try { abrirEm(0) } catch (e: IOException) { -1L }
            }
            return total
        }

        private fun abrirEm(p: Long): Long {
            fechar()
            val d = dados.createDataSource()
            val n = d.open(DataSpec.Builder().setUri(uri).setPosition(p).build())
            fonte = d; pos = p
            return if (n == C.LENGTH_UNSET.toLong()) -1L else p + n
        }

        override fun ler(posicao: Long, destino: ByteArray, n: Int): Int = try {
            if (fonte == null || posicao != pos) abrirEm(posicao)
            val r = fonte!!.read(destino, 0, n)
            if (r == C.RESULT_END_OF_INPUT) -1 else { pos += r; r }
        } catch (e: IOException) { -1 }

        fun fechar() { try { fonte?.close() } catch (_: IOException) {}; fonte = null; pos = -1 }
    }
}
