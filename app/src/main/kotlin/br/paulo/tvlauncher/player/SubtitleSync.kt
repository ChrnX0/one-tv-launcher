package br.paulo.tvlauncher.player

import br.paulo.tvlauncher.input.SpeechTap
import kotlin.math.sqrt

/**
 * Legenda que se acerta sozinha (estudo "referência mundial", proposta A, 20/11/2026). Kodi, VLC, Infuse e Plex pedem
 * que a pessoa ajuste o atraso à mão; a ideia aqui é a do ffsubsync: comparar ONDE HÁ FALA no áudio com ONDE HÁ
 * LEGENDA e achar o deslocamento que melhor encaixa os dois.
 *
 * Só a conta (SubtitleSyncTest). Quem escuta o áudio e marca a fala por janela é o player; aqui entram:
 *  - [fala]: uma lista de janelas de [PASSO_MS] (true = havia fala), a partir de [inicioMs] do vídeo;
 *  - [falas]: os intervalos das legendas (início e fim, em ms do vídeo, já com o atraso de hoje).
 * Sai o atraso a SOMAR ao de hoje (ms; positivo = a legenda vinha cedo demais e passa a aparecer depois) e a confiança.
 *
 * Regras de segurança (uma legenda certa nunca piora): só responde com fala suficiente ouvida ([MIN_FALA_MS]); só
 * propõe mudança quando o melhor encaixe se destaca dos outros ([MIN_CONFIANCA]); e mudança menor que [MIN_MUDANCA_MS]
 * não vale (ninguém nota, e evita ficar mexendo).
 */
object SubtitleSync {
    const val PASSO_MS = 100L
    /** Quanto procurar para cada lado. Legenda de outra edição pode vir muito fora; além disso é outra edição mesmo. */
    const val BUSCA_MS = 20_000L
    const val MIN_FALA_MS = 60_000L
    const val MIN_CONFIANCA = 4.0
    const val MIN_MUDANCA_MS = 300L
    /** Quanto do que foi ouvido entra em cada conta (os 20 min mais recentes). */
    const val JANELA_ANALISE_MS = 20 * 60_000L

    data class Fala(val inicioMs: Long, val fimMs: Long)
    data class Resultado(val atrasoMs: Long, val confianca: Double)

    /** Null = não dá para dizer com segurança (pouca fala ouvida ou encaixe sem destaque). */
    fun ajustar(fala: BooleanArray, inicioMs: Long, falas: List<Fala>, buscaMs: Long = BUSCA_MS): Resultado? {
        val n = fala.size
        if (fala.count { it } * PASSO_MS < MIN_FALA_MS) return null
        // Legenda em janelas, numa faixa mais larga que o áudio (para poder deslocar para os dois lados).
        val folga = (buscaMs / PASSO_MS).toInt()
        val leg = BooleanArray(n + 2 * folga)
        for (f in falas) {
            val a = ((f.inicioMs - inicioMs) / PASSO_MS).toInt() + folga
            val b = ((f.fimMs - inicioMs) / PASSO_MS).toInt() + folga
            for (i in a.coerceAtLeast(0) until b.coerceAtMost(leg.size)) leg[i] = true
        }
        // Para cada deslocamento d: quantas janelas têm fala E legenda (legenda movida d janelas para a frente).
        val pontos = IntArray(2 * folga + 1)
        for (k in pontos.indices) {
            val d = k - folga   // a janela i do áudio olha a legenda em i - d
            var s = 0
            for (i in 0 until n) if (fala[i] && leg[i - d + folga]) s++
            pontos[k] = s
        }
        // Empates no topo (um platô de deslocamentos igualmente bons): fica o do meio, não o primeiro.
        val topo = pontos.maxOrNull() ?: return null
        val empatados = pontos.indices.filter { pontos[it] == topo }
        val melhor = empatados[empatados.size / 2]
        // Destaque: o melhor contra a média e o desvio dos deslocamentos longe dele (mais de 1 s).
        val longe = pontos.indices.filter { kotlin.math.abs(it - melhor) * PASSO_MS > 1_000 }.map { pontos[it].toDouble() }
        if (longe.isEmpty()) return null
        val media = longe.average()
        val desvio = sqrt(longe.sumOf { (it - media) * (it - media) } / longe.size).coerceAtLeast(1.0)
        val confianca = (pontos[melhor] - media) / desvio
        if (confianca < MIN_CONFIANCA) return null
        return Resultado((melhor - folga) * PASSO_MS, confianca)
    }

    /**
     * Onde há fala, a partir da energia de cada janela (dB, em [SpeechTap]; NaN = janela não ouvida). Limiar relativo ao
     * próprio filme: o piso (percentil [PISO_PERCENTIL] das janelas ouvidas) mais [ACIMA_DO_PISO_DB]. Assim serve para
     * filme baixo e filme alto; música e explosão às vezes passam por fala, e a conta de [ajustar] aguenta esse ruído.
     */
    fun marcarFala(energiaDb: FloatArray): BooleanArray {
        val ouvidas = energiaDb.filter { !it.isNaN() }.sorted()
        if (ouvidas.isEmpty()) return BooleanArray(energiaDb.size)
        val limiar = ouvidas[(ouvidas.size * PISO_PERCENTIL).toInt().coerceIn(0, ouvidas.size - 1)] + ACIMA_DO_PISO_DB
        return BooleanArray(energiaDb.size) { !energiaDb[it].isNaN() && energiaDb[it] >= limiar }
    }
    const val PISO_PERCENTIL = 0.2
    const val ACIMA_DO_PISO_DB = 8f

    private val reLinha = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    /** Os intervalos das falas de um .srt (o texto não importa, só quando cada uma aparece e some). */
    fun falasDoSrt(srt: String): List<Fala> = reLinha.findAll(srt).mapNotNull { m ->
        val g = m.groupValues
        fun ms(h: String, mi: String, s: String, f: String) =
            h.toLong() * 3_600_000 + mi.toLong() * 60_000 + s.toLong() * 1000 + f.padEnd(3, '0').take(3).toLong()
        val a = ms(g[1], g[2], g[3], g[4]); val b = ms(g[5], g[6], g[7], g[8])
        if (b > a) Fala(a, b) else null
    }.toList()

    /** Vale aplicar? Só com confiança e se mudar o bastante para alguém notar. */
    fun aplicar(r: Resultado?): Boolean = r != null && kotlin.math.abs(r.atrasoMs) >= MIN_MUDANCA_MS
}
