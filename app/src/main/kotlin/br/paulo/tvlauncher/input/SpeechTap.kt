package br.paulo.tvlauncher.input

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import br.paulo.tvlauncher.player.SubtitleSync
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.log10

/**
 * O "ouvido" da legenda que se acerta sozinha (SubtitleSync, 20/11/2026): fica no COMEÇO da corrente de áudio do player
 * (antes do diálogo mais alto e da velocidade), deixa o som passar intacto e anota a energia da voz em janelas de
 * [SubtitleSync.PASSO_MS] do tempo do VÍDEO.
 *
 * Tempo do vídeo: o processador não recebe o relógio do player, então conta quadros desde o último "flush" (o Media3 faz
 * um a cada pulo ou recarga) a partir de uma âncora que o player avisa antes ([proximaPosicao]). Flush sem âncora = não
 * anota nada até o próximo pulo (melhor não ouvir do que ouvir no lugar errado). A tela ainda confere o relógio daqui
 * contra o do player ([relogioMs]) e descarta se divergir.
 *
 * Voz: com 5.1 usa só o canal central (onde fica o diálogo); em estéreo, a média dos dois. Uma diferença entre amostras
 * vizinhas tira o grave (música, explosão) antes de medir. Áudio que vai direto para a TV (Dolby sem decodificar) não
 * passa por aqui: aí não há ajuste, e a legenda fica como está.
 */
@OptIn(UnstableApi::class)
class SpeechTap : BaseAudioProcessor() {
    private var canais = 0
    private var taxa = 0
    private var flutuante = false
    private var anterior = 0f
    private var soma = 0.0
    private var naJanela = 0

    override fun onConfigure(entrada: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (entrada.encoding != C.ENCODING_PCM_16BIT && entrada.encoding != C.ENCODING_PCM_FLOAT) return AudioProcessor.AudioFormat.NOT_SET
        canais = entrada.channelCount; taxa = entrada.sampleRate; flutuante = entrada.encoding == C.ENCODING_PCM_FLOAT
        return entrada
    }

    override fun queueInput(dados: ByteBuffer) {
        val n = dados.remaining()
        if (n == 0) return
        if (canais > 0 && taxa > 0) {
            // O relógio anda sempre (mesmo sem escutar), senão ligar no meio do filme daria a hora errada.
            val quadros = n / ((if (flutuante) 4 else 2) * canais)
            if (quadrosDesdeFlush == 0L) pendente.also { pendente = -1L }.let { if (it >= 0) ancora = it }
            if (ligado) ouvir(dados.duplicate().order(ByteOrder.nativeOrder()), quadros)
            quadrosDesdeFlush += quadros
            ultimaTaxa = taxa
        }
        val saida = replaceOutputBuffer(n)
        saida.put(dados); saida.flip()
    }

    private fun ouvir(d: ByteBuffer, quadros: Int) {
        val porJanela = (taxa * SubtitleSync.PASSO_MS / 1000).toInt()
        val central = if (canais >= 6) 2 else -1
        for (q in 0 until quadros) {
            var v = 0f
            for (c in 0 until canais) {
                val a = if (flutuante) d.float else d.short / 32768f
                if (central >= 0) { if (c == central) v = a } else if (c < 2) v += a / 2f
            }
            val dif = v - anterior; anterior = v
            soma += dif * dif; naJanela++
            if (naJanela >= porJanela) {
                val inicio = ancora
                if (inicio >= 0) {
                    val t = inicio + (quadrosDesdeFlush + q + 1 - naJanela) * 1000 / taxa   // começo da janela
                    anotar(t, (10 * log10(soma / naJanela + 1e-12)).toFloat())
                }
                soma = 0.0; naJanela = 0
            }
        }
    }

    override fun onFlush() {
        quadrosDesdeFlush = 0L; ancora = -1L; soma = 0.0; naJanela = 0; anterior = 0f
    }

    companion object {
        /** Só escuta quando há legenda baixada tocando (o resto do tempo só passa o som adiante). */
        @Volatile var ligado = false
        @Volatile private var pendente = -1L
        @Volatile private var ancora = -1L
        @Volatile private var quadrosDesdeFlush = 0L
        @Volatile private var ultimaTaxa = 0
        /** Até 4 h de vídeo em janelas de 100 ms (~580 KB); NaN = não ouvida. */
        private val energia = FloatArray((4 * 3_600_000L / SubtitleSync.PASSO_MS).toInt()) { Float.NaN }

        /** O player vai recomeçar o som em [ms] (abrir, pular, recarregar): a próxima leva de áudio começa ali. */
        fun proximaPosicao(ms: Long) { pendente = ms.coerceAtLeast(0) }

        /** Onde o som que acabou de passar está no vídeo (ms), ou null sem âncora. Fica à frente do player pelo buffer. */
        fun relogioMs(): Long? {
            val a = ancora; val t = ultimaTaxa
            return if (a < 0 || t <= 0) null else a + quadrosDesdeFlush * 1000 / t
        }

        /** O relógio daqui não bate com o do player: para de anotar até o próximo pulo. */
        fun invalidar() { ancora = -1L }

        /** Vídeo novo: esquece tudo o que ouviu. */
        fun zerar() { synchronized(energia) { energia.fill(Float.NaN) }; pendente = -1L; ancora = -1L }

        private fun anotar(t: Long, db: Float) {
            val i = (t / SubtitleSync.PASSO_MS).toInt()
            if (i in energia.indices) synchronized(energia) { energia[i] = db }
        }

        /** As janelas ouvidas no trecho mais recente (até [maxMs]): (início em ms, energias). Null sem nada ouvido. */
        fun trecho(maxMs: Long): Pair<Long, FloatArray>? = synchronized(energia) {
            val ultima = energia.indices.lastOrNull { !energia[it].isNaN() } ?: return null
            val primeira = maxOf(0, ultima - (maxMs / SubtitleSync.PASSO_MS).toInt() + 1)
            (primeira * SubtitleSync.PASSO_MS) to energia.copyOfRange(primeira, ultima + 1)
        }
    }
}
