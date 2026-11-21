package br.paulo.tvlauncher.listen

import br.paulo.tvlauncher.PodcastPlayerActivity
import br.paulo.tvlauncher.player.Episodio
import java.util.Locale

/**
 * As contas do player de podcast em tela cheia ([PodcastPlayerActivity], 18/11/2026), puras para teste JVM:
 * a fila "A seguir", a hora em que o episódio termina, os tempos da barra e as opções do timer para dormir.
 */
object PodcastPlayerLogic {

    /** Opções do timer para dormir, em minutos (0 = desligado). */
    val DORMIR_MIN = listOf(0, 15, 30, 45, 60, 90)

    /** Quanto ◀ ▶ andam com o foco na barra de tempo. */
    const val PASSO_BARRA_MS = 10_000L

    /** Quantos episódios a fila "A seguir" mostra. */
    const val MAX_A_SEGUIR = 8

    /**
     * Os próximos episódios do mesmo podcast, depois do [atual]: primeiro os MAIS NOVOS que ele (o mais perto primeiro:
     * é a ordem de quem acompanha uma série ou o noticiário do dia), depois os mais antigos (o mais perto primeiro).
     * Pula os já ouvidos ([ouvido]) e os vídeos (abrem em outro player). [eps] vem como o feed manda (mais novo primeiro).
     * Sem o atual na lista: os não ouvidos, do mais novo para o mais antigo.
     */
    fun aSeguir(eps: List<PodcastFeed.Episodio>, atual: String, ouvido: (PodcastFeed.Episodio) -> Boolean,
                max: Int = MAX_A_SEGUIR): List<PodcastFeed.Episodio> {
        val bons = { e: PodcastFeed.Episodio -> e.guid != atual && !e.video && !ouvido(e) }
        val i = eps.indexOfFirst { it.guid == atual }
        if (i < 0) return eps.filter(bons).take(max)
        val novos = eps.subList(0, i).asReversed()
        val antigos = eps.subList(i + 1, eps.size)
        return (novos + antigos).filter(bons).take(max)
    }

    /** Quando termina (relógio de parede, ms), contando a velocidade; null sem duração. */
    fun terminaEm(agoraMs: Long, posMs: Long, durMs: Long, velocidade: Float): Long? {
        if (durMs <= 0 || posMs >= durMs) return null
        val v = if (velocidade > 0f) velocidade else 1f
        return agoraMs + ((durMs - posMs) / v).toLong()
    }

    /** "4:05", "1:02:03". */
    fun tempo(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
        else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** O que falta, com sinal de menos ("−33:30"), contando a velocidade. Vazio sem duração. */
    fun falta(posMs: Long, durMs: Long, velocidade: Float): String {
        if (durMs <= 0) return ""
        val v = if (velocidade > 0f) velocidade else 1f
        return "−" + tempo(((durMs - posMs).coerceAtLeast(0) / v).toLong())
    }

    /** "1x", "1,2x", "1,75x" (vírgula ou ponto conforme a língua). */
    fun rotuloVelocidade(v: Float, locale: Locale = Locale.getDefault()): String =
        String.format(locale, "%.2f", v).trimEnd('0').trimEnd('.', ',') + "x"

    /** Posição depois de andar [deltaMs] na barra: nunca antes do zero nem no último segundo (não termina sem querer). */
    fun andar(posMs: Long, deltaMs: Long, durMs: Long): Long {
        val fim = if (durMs > 0) maxOf(0L, durMs - 1_000) else Long.MAX_VALUE
        return (posMs + deltaMs).coerceIn(0L, fim)
    }

    /** Minutos que faltam para o timer dormir (arredonda para cima; 0 = desligado ou já passou). */
    fun minutosParaDormir(dormirAte: Long, agora: Long): Int =
        if (dormirAte <= agora) 0 else (((dormirAte - agora) + 59_999) / 60_000).toInt()
}
