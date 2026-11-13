package br.paulo.tvlauncher

import android.content.Context
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Vídeo ao vivo da tela no painel (10/11/2026, ideia E; o ws-scrcpy do PC fazia isto, e a aba Controle do painel só
 * tem foto a cada 2 s). Sem PC e sem app externo:
 *
 *   shell próprio (uid 2000) --exec:screenrecord ... - (H.264 cru)--> launcher --HTTP em fluxo--> navegador (JMuxer)
 *
 *  - `screenrecord` é a ferramenta do próprio Android; só o uid shell pode usá-la, por isso passa pelo nosso adb.
 *    O comando é FIXO ([COMANDO]): nada vindo do navegador entra nele.
 *  - `exec:` (em vez de `shell:`) entrega os bytes crus, sem terminal no meio que estrague o binário.
 *  - o screenrecord para sozinho em [LIMITE_S] s (limite dele: 180); o navegador pede de novo se quiser continuar.
 *  - o launcher corta o H.264 em unidades NAL ([SeparadorNal]) e manda cada uma como [tamanho de 4 bytes][NAL]
 *    ([quadro]): o navegador lê em fluxo (fetch + ReadableStream) e entrega ao JMuxer, que monta o vídeo no <video>.
 *  - um espectador por vez ([ocupado]): cada fluxo custa CPU do codificador da TV.
 *
 * Não verificado na TV: se o adbd aceita um segundo fluxo aberto ao mesmo tempo que os comandos do guardião (o adb
 * multiplexa fluxos numa conexão, mas a fila do [GuardiaoShizuku] existe por cautela), quanto de CPU custa, e se o
 * codificador do Chromecast embaralha 1280x720 como embaralhava 720x400 (no ws-scrcpy 1280x720 saía certo). Conteúdo com
 * DRM (Netflix etc.) sai preto: é proteção do Android, não defeito.
 */
object VideoAoVivo {
    private const val TAG = "TvLauncher.Video"
    const val LIMITE_S = 120
    const val COMANDO = "screenrecord --output-format=h264 --size 1280x720 --bit-rate 2000000 --time-limit $LIMITE_S -"
    const val MAX_NAL = 2 shl 20   // 2 MB: um quadro maior que isso é lixo, não vídeo

    private val ocupado = AtomicBoolean(false)

    /** [tamanho em 4 bytes, big-endian][nal]. Puro. */
    fun quadro(nal: ByteArray): ByteArray {
        val n = nal.size
        return byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()) + nal
    }

    /**
     * Corta um fluxo H.264 "Annex B" nas unidades NAL (cada uma começa com 00 00 01 ou 00 00 00 01). Recebe pedaços de
     * qualquer tamanho ([colocar]) e devolve as NAL completas, COM o código de início. A última fica guardada até chegar o
     * começo da próxima ou o [fim]. Puro (SeparadorNalTest).
     */
    class SeparadorNal(private val maximo: Int = MAX_NAL) {
        private val buf = ByteArrayOutputStream()

        fun colocar(dados: ByteArray, n: Int = dados.size): List<ByteArray> {
            buf.write(dados, 0, n)
            val b = buf.toByteArray()
            val inicios = inicios(b)
            if (inicios.size < 2) {
                if (b.size > maximo) { buf.reset(); throw IllegalStateException("NAL maior que $maximo bytes") }
                return emptyList()
            }
            val saida = (0 until inicios.size - 1).map { b.copyOfRange(inicios[it], inicios[it + 1]) }
            buf.reset(); buf.write(b, inicios.last(), b.size - inicios.last())
            return saida
        }

        fun fim(): ByteArray? {
            val b = buf.toByteArray(); buf.reset()
            return if (inicios(b).isNotEmpty()) b.copyOfRange(inicios(b).first(), b.size) else null
        }

        /** Posições onde começa um código de início (o 00 00 00 01 conta a partir do 1º zero). */
        private fun inicios(b: ByteArray): List<Int> {
            val l = ArrayList<Int>()
            var i = 0
            while (i + 2 < b.size) {
                if (b[i] == 0.toByte() && b[i + 1] == 0.toByte() && b[i + 2] == 1.toByte()) {
                    l += if (i > 0 && b[i - 1] == 0.toByte()) i - 1 else i
                    i += 3
                } else i++
            }
            return l
        }
    }

    /**
     * Transmite para [saida] até o screenrecord acabar, [parar] dizer chega ou o navegador fechar a conexão.
     * Bloqueia: rodar na thread da conexão do painel. Devolve null se transmitiu, ou o motivo de não ter começado.
     */
    fun transmitir(ctx: Context, saida: OutputStream, parar: () -> Boolean): String? {
        if (!ocupado.compareAndSet(false, true)) return "já tem alguém vendo a tela ao vivo"
        try {
            val m = AdbConnectionManager.obter(ctx.applicationContext)
            if (!m.isConnected && !m.autoConnect(ctx.applicationContext, 15_000)) return "o shell próprio não conectou"
            val fluxo = m.openStream("exec:$COMANDO")
            try {
                val ent = fluxo.openInputStream()
                val sep = SeparadorNal()
                val b = ByteArray(64 * 1024)
                var total = 0L
                while (!parar()) {
                    val n = try { ent.read(b) } catch (e: java.io.IOException) { if (fluxo.isClosed) -1 else throw e }
                    if (n < 0) break
                    total += n
                    for (nal in sep.colocar(b, n)) saida.write(quadro(nal))
                    saida.flush()
                }
                sep.fim()?.let { saida.write(quadro(it)); saida.flush() }
                Log.i(TAG, "fim do vídeo: ${total / 1024} KB")
                return null
            } finally {
                try { fluxo.close() } catch (_: Exception) {}   // fechar o fluxo encerra o screenrecord
            }
        } catch (e: Exception) {
            Log.w(TAG, "vídeo falhou: $e")
            return "erro: ${e.javaClass.simpleName}"
        } finally {
            ocupado.set(false)
        }
    }
}
