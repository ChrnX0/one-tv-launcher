package br.paulo.tvlauncher.listen

import br.paulo.tvlauncher.network.LgNetcast
import br.paulo.tvlauncher.player.item

/**
 * Som no fone (degrau 4, item 4, 17/11/2026): o som do NOSSO player vai para até 4 celulares da casa, cada um no seu
 * fone, e a TV fica muda (ou não, se quem ouve pedir). Regras puras, testadas em SomNoFoneTest; o servidor e a
 * captura estão em SomNoFone.kt.
 *
 * Só o nosso player: o som sai do processador de áudio que já existe na corrente do ExoPlayer (EfeitosSom.fabrica),
 * nada de capturar o som de outros apps (sem MediaProjection, sem permissão nova). Só a rede de casa: o servidor
 * recusa quem não vem de IP privado (10/8, 172.16/12, 192.168/16), e o túnel Tailscale não chega nele.
 *
 * Sincronia boca-som: cada pedaço leva a hora da TV em que entrou no player; o celular acerta o relógio com a TV
 * (várias idas e voltas, fica a mais rápida) e toca cada pedaço em hora marcada = hora da TV + [ATRASO_PADRAO_MS]
 * (o tempo que o som da TV leva do player até sair). Cada celular ajusta o atraso num controle, salvo nele.
 */
object SomNoFoneRegras {
    const val PORTA = 8097
    const val MAX_CELULARES = 4
    /** Quanto o som da TV demora do processador até o alto-falante (buffer do AudioTrack). Ponto de partida do ajuste. */
    const val ATRASO_PADRAO_MS = 250
    /** Fila por celular (~20 ms cada): passou disso o celular está lento e perde os mais velhos, em vez de atrasar. */
    const val FILA_PEDACOS = 40
    /** Cabeçalho de cada pedaço no fluxo: hora (ms, 8 bytes), taxa (4), bytes de PCM (4). Big-endian. */
    const val CABECALHO = 16

    /** Só IPv4 privado da casa (mesma regra da LG: LgNetcast.ipLocal). */
    fun daCasa(ip: String?): Boolean = LgNetcast.ipLocal(ip)

    fun podeEntrar(ip: String?, ouvindo: Int): Boolean = daCasa(ip) && ouvindo < MAX_CELULARES

    private val TOKEN = Regex("^[0-9a-f]{32}$")

    /** "/fone/<token>" (página), "/fone/<token>/som|hora|tv". Devolve (token, ação) ou null. */
    fun rota(caminho: String): Pair<String, String>? {
        val p = caminho.substringBefore('?').removePrefix("/fone/").takeIf { it != caminho.substringBefore('?') } ?: return null
        val partes = p.trimEnd('/').split('/')
        val t = partes[0].takeIf { TOKEN.matches(it) } ?: return null
        return when {
            partes.size == 1 -> t to "pagina"
            partes.size == 2 && partes[1] in setOf("som", "hora", "tv") -> t to partes[1]
            else -> null
        }
    }

    /** A TV fica muda enquanto alguém ouve no fone, a não ser que tenham pedido o som nos dois. */
    fun tvMuda(ouvindo: Int, somTambemNaTv: Boolean) = ouvindo > 0 && !somTambemNaTv

    /**
     * Amostras intercaladas ([canais] por quadro, -1..1) para PCM 16 bits estéreo little-endian (o que a página toca).
     * Mono vira os dois lados; mais de 2 canais: fica com frente esquerda e direita (o diálogo já vem misturado nelas
     * pelo ProcessadorDialogo quando a opção está ligada).
     */
    fun estereo16(amostras: FloatArray, canais: Int, quadros: Int = if (canais > 0) amostras.size / canais else 0): ByteArray {
        val saida = ByteArray(quadros * 4)
        if (canais <= 0) return saida
        var o = 0
        for (q in 0 until quadros) {
            val l = amostras[q * canais]
            val r = if (canais >= 2) amostras[q * canais + 1] else l
            for (v in floatArrayOf(l, r)) {
                val s = (v.coerceIn(-1f, 1f) * 32767f).toInt()
                saida[o++] = (s and 0xff).toByte(); saida[o++] = (s shr 8 and 0xff).toByte()
            }
        }
        return saida
    }

    /** Cabeçalho + PCM de um pedaço. */
    fun pedaco(horaMs: Long, taxa: Int, pcm: ByteArray): ByteArray {
        val b = java.nio.ByteBuffer.allocate(CABECALHO + pcm.size)
        b.putLong(horaMs).putInt(taxa).putInt(pcm.size).put(pcm)
        return b.array()
    }

    /** Lê um pedaço (o que a página faz em JS; aqui para o teste): (hora, taxa, pcm) ou null se incompleto/estranho. */
    fun lerPedaco(b: ByteArray): Triple<Long, Int, ByteArray>? {
        if (b.size < CABECALHO) return null
        val bb = java.nio.ByteBuffer.wrap(b)
        val hora = bb.long; val taxa = bb.int; val n = bb.int
        if (taxa !in 8_000..192_000 || n < 0 || n % 4 != 0 || b.size < CABECALHO + n) return null
        return Triple(hora, taxa, b.copyOfRange(CABECALHO, CABECALHO + n))
    }

    /**
     * Acerto de relógio (a página faz igual): de várias medidas (envio, hora da TV, chegada) no relógio do celular,
     * fica a de ida e volta mais curta; deslocamento = hora da TV - meio do caminho. Null sem medidas.
     */
    fun deslocamento(medidas: List<Triple<Double, Double, Double>>): Double? =
        medidas.filter { it.third >= it.first }.minByOrNull { it.third - it.first }?.let { (e, tv, c) -> tv - (e + c) / 2 }

    /** A fila de cada celular: cheia, descarta o mais velho (atrasar o som é pior que perder 20 ms). */
    fun <T> enfileirar(fila: ArrayDeque<T>, item: T, max: Int = FILA_PEDACOS) {
        while (fila.size >= max) fila.removeFirst()
        fila.addLast(item)
    }
}
