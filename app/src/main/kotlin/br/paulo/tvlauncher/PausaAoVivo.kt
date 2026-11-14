package br.paulo.tvlauncher

/**
 * Pausa curta no ao vivo (ideia 18, 14/11/2026): regra pura, sem Android, com teste JVM (PausaAoVivoTest).
 *
 * Como funciona: pausar o ao vivo NÃO grava nada. O player só para de mostrar e continua baixando o canal para a
 * memória dele (o buffer "à frente" do Media3), como faz com qualquer vídeo pausado. Ao continuar, ele toca do ponto
 * em que parou, atrasado; o que chega da rede entra no fim do buffer. Trocar de canal, sair ou voltar ao vivo joga
 * tudo fora (é só memória do processo :player).
 *
 * A conta (Chromecast com Google TV: 2 GB de RAM, 32 bits, sem largeHeap no manifesto):
 *  - um canal IPTV tem de 3 a 8 Mbps: 1 min = 22 MB a 3 Mbps e 60 MB a 8 Mbps (8 Mbps = 1 MB/s);
 *  - o buffer do Media3 são blocos de 64 KB no heap Java do processo (DefaultAllocator). Sem largeHeap o teto do
 *    heap é o heapgrowthlimit do aparelho (em TVs de 2 GB costuma ser 192 a 256 MB; o app lê o valor real com
 *    Runtime.maxMemory()). O resto do :player (lista de canais, guia, telas) precisa de folga nesse mesmo heap;
 *  - hoje o Media3 já segura até 50 s à frente (DefaultLoadControl), com teto de 144 MB: a 8 Mbps são ~50 MB.
 *    Ou seja, ~50 MB no buffer já é o normal do player hoje, e é isso que vale no uso comum (ver CargaPausa);
 *  - teto da pausa: 2 min OU 96 MB OU 3/8 do heap, o que vier primeiro. Com heap de 192 MB: 72 MB = 72 s a 8 Mbps,
 *    2 min a 4 Mbps ou menos. Com 256 MB: 96 MB = 96 s a 8 Mbps. Folga de no mínimo 5/8 do heap para o resto;
 *    largeHeap não foi ligado (não é preciso, e aumentaria o risco de o sistema matar outros apps).
 *
 * Por que não mais: 5 min a 8 Mbps seriam 300 MB, mais do que o heap inteiro. E não dá para guardar em disco (o dono
 * vetou gravação).
 */
object PausaAoVivo {
    const val TETO_MS = 120_000L                    // 2 min no máximo, mesmo em canal leve
    const val TETO_BYTES_MAX = 96L * 1024 * 1024    // 96 MB no máximo, mesmo com heap grande
    const val TETO_BYTES_MIN = 16L * 1024 * 1024    // heap minúsculo (não esperado): ainda segura uns segundos
    const val BITRATE_PADRAO = 8_000_000L           // bitrate desconhecido (o .ts do Xtream não diz): o pior caso
    const val NORMAL_MS = 50_000L                   // fora da pausa, o buffer à frente fica no padrão do Media3 (50 s)
    const val FOLGA_MS = 15_000L                    // HLS chega em pedaços de 6 a 10 s: tolerância ao conferir o buffer

    /** Bytes que a pausa pode ocupar, a partir do teto do heap do processo (Runtime.maxMemory()). */
    fun tetoBytes(maxMemoria: Long): Long = (maxMemoria / 8 * 3).coerceIn(TETO_BYTES_MIN, TETO_BYTES_MAX)

    /** Quanto tempo de canal cabe no teto de bytes, pelo bitrate estimado (0 ou negativo = desconhecido). */
    fun tetoMs(bitrateBps: Long, tetoBytes: Long): Long {
        val b = if (bitrateBps > 0) bitrateBps else BITRATE_PADRAO
        return minOf(TETO_MS, tetoBytes * 8 * 1000 / b)
    }

    /** Bitrate do canal: vídeo + áudio, quando o Media3 sabe (o HLS diz no playlist); senão 0 (desconhecido). */
    fun bitrate(video: Int, audio: Int): Long = if (video > 0) video.toLong() + audio.coerceAtLeast(0) else 0L

    /** Daqui a quanto tempo, pausado, a pausa estoura (o buffer à frente chega ao teto). */
    fun estouraEm(bufferNaPausaMs: Long, tetoMs: Long): Long = (tetoMs - bufferNaPausaMs).coerceAtLeast(0)

    enum class Decisao { CONTINUAR_ATRASADO, VOLTAR_AO_VIVO }

    /**
     * Ao continuar: segue de onde parou se tudo o que ficou pausado coube na memória e o buffer acompanhou a rede
     * (não caiu a conexão nem bateu no teto de bytes antes da hora); senão volta ao vivo (e a tela avisa).
     */
    fun decidir(pausadoMs: Long, bufferNaPausaMs: Long, bufferAgoraMs: Long, tetoMs: Long): Decisao = when {
        pausadoMs < 0 -> Decisao.VOLTAR_AO_VIVO
        bufferNaPausaMs + pausadoMs > tetoMs -> Decisao.VOLTAR_AO_VIVO
        bufferAgoraMs + FOLGA_MS < bufferNaPausaMs + pausadoMs -> Decisao.VOLTAR_AO_VIVO
        else -> Decisao.CONTINUAR_ATRASADO
    }

    /**
     * "atrasado 2 min" / "atrasado 40 s": modelos vêm dos recursos (%1$d). Abaixo de 1 min, em passos de 5 s;
     * de 1 min para cima, minutos arredondados para baixo. Menos de 1 s = sem atraso (texto vazio).
     */
    fun textoAtraso(atrasoMs: Long, modeloMin: String, modeloSeg: String): String = when {
        atrasoMs < 1_000 -> ""
        atrasoMs < 60_000 -> String.format(modeloSeg, (((atrasoMs / 1000) + 4) / 5 * 5).coerceAtMost(55))
        else -> String.format(modeloMin, atrasoMs / 60_000)
    }
}
