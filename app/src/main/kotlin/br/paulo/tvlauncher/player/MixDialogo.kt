package br.paulo.tvlauncher.player

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Ideia 2 (15/11/2026): "diálogo mais alto" ao transformar 5.1/7.1 em estéreo. Em filme, a fala fica quase toda no
 * canal central; na mistura padrão para duas caixas ela chega a -3 dB e briga com música e efeitos. Aqui o central
 * entra com reforço ([REFORCO], +6 dB) e os traseiros pela metade; o LFE (grave) sai, a TV de duas caixas não o toca.
 *
 * Vale para qualquer áudio que vire PCM no aparelho (do chip ou do FFmpeg). Por isso, com esta chave ligada, o player
 * desliga o envio direto de Dolby/DTS para a TV (igual aos efeitos de som). Estéreo e mono passam sem mexer.
 */
object MixDialogo {
    const val PREF = "dialogo_alto"   // prefs "player"
    const val REFORCO = 2.0f          // central x2 = +6 dB acima da mistura padrão

    /**
     * Coeficientes [saída esquerda, saída direita] x canais de entrada, na ordem do Android:
     * 5.1 = FL FR FC LFE BL BR; 7.1 = FL FR FC LFE BL BR SL SR. Null = não mexe (menos de 6 canais).
     * O ganho geral cai para a soma da frente + centro caber em 1; picos raros dos traseiros são cortados no limite.
     */
    fun coeficientes(canais: Int, reforco: Float = REFORCO): Array<FloatArray>? {
        if (canais != 6 && canais != 8) return null
        val c = 0.7071f * reforco
        val tras = 0.5f
        val g = 1f / (1f + c)
        val l = FloatArray(canais); val r = FloatArray(canais)
        l[0] = 1f * g; r[1] = 1f * g
        l[2] = c * g; r[2] = c * g
        l[4] = tras * g; r[5] = tras * g
        if (canais == 8) { l[6] = tras * g; r[7] = tras * g }
        return arrayOf(l, r)
    }

    /** Mistura um quadro de amostras (valores -1..1) em duas saídas, com corte no limite. */
    fun misturar(entrada: FloatArray, m: Array<FloatArray>): Pair<Float, Float> {
        var l = 0f; var r = 0f
        for (i in entrada.indices) { l += entrada[i] * m[0][i]; r += entrada[i] * m[1][i] }
        return l.coerceIn(-1f, 1f) to r.coerceIn(-1f, 1f)
    }
}

/** O AudioProcessor do Media3 que aplica o [MixDialogo]. Ligado/desligado na criação do player. */
@OptIn(UnstableApi::class)
class ProcessadorDialogo(private val ligado: Boolean) : BaseAudioProcessor() {
    private var matriz: Array<FloatArray>? = null
    private var canais = 0
    private var flutuante = false

    override fun onConfigure(entrada: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        val m = if (ligado) MixDialogo.coeficientes(entrada.channelCount) else null
        if (m == null || (entrada.encoding != C.ENCODING_PCM_16BIT && entrada.encoding != C.ENCODING_PCM_FLOAT)) {
            matriz = null
            return AudioProcessor.AudioFormat.NOT_SET   // inativo: o áudio passa direto
        }
        matriz = m; canais = entrada.channelCount; flutuante = entrada.encoding == C.ENCODING_PCM_FLOAT
        return AudioProcessor.AudioFormat(entrada.sampleRate, 2, entrada.encoding)
    }

    override fun queueInput(dados: ByteBuffer) {
        val m = matriz ?: return
        val bytes = if (flutuante) 4 else 2
        val quadros = dados.remaining() / (bytes * canais)
        val saida = replaceOutputBuffer(quadros * 2 * bytes)
        val amostra = FloatArray(canais)
        repeat(quadros) {
            for (c in 0 until canais) amostra[c] = if (flutuante) dados.float else dados.short / 32768f
            val (l, r) = MixDialogo.misturar(amostra, m)
            if (flutuante) { saida.putFloat(l); saida.putFloat(r) }
            else { saida.putShort((l * 32767f).toInt().toShort()); saida.putShort((r * 32767f).toInt().toShort()) }
        }
        saida.flip()
    }
}
