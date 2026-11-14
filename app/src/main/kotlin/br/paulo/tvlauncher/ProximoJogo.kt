package br.paulo.tvlauncher

import java.util.Calendar
import java.util.TimeZone

/**
 * "Hoje na TV" fora do esconderijo (ideia 12, 14/11/2026): o próximo jogo do "Meu time" vira mais um item da cápsula
 * de avisos do topo da HOME (ao lado do despertador, do timer e do vencimento) e um cartão no protetor Galeria. SEM
 * fileira nova na HOME. Texto curto: "Flamengo × Palmeiras · 21:30".
 *
 * A fonte são os lembretes automáticos (LembretesTv: os gerados pelas palavras do "Meu time", que já juntam o guia de
 * 14 h e as partidas de até 7 dias do MeuTimeAdiante). Só a regra mora aqui, pura (sem Android), testada em
 * ProximoJogoTest (testes-puros.sh).
 */
object ProximoJogo {
    const val JANELA_MS = 24 * 3_600_000L
    /** Teto do nome do jogo na pílula (o topo da HOME é estreito). */
    const val MAX_TITULO = 36

    /** O que a regra precisa de um lembrete: título, início, fim e a palavra do "Meu time" que o gerou ("" = à mão). */
    data class Item(val titulo: String, val inicio: Long, val fim: Long, val palavra: String)

    /**
     * O jogo a mostrar: o que começa primeiro entre os que ainda não acabaram e começam nas próximas 24 h, gerados por
     * uma palavra do "Meu time" que ainda está salva ([palavras]) e cujo título ainda casa com ela. Null = nada (sem
     * palavras, só lembretes marcados à mão, ou nenhum jogo nas próximas 24 h).
     */
    fun escolher(itens: List<Item>, palavras: List<String>, agora: Long): Item? {
        if (palavras.isEmpty()) return null
        return itens.filter { i ->
            i.palavra.isNotEmpty() && i.fim > agora && i.inicio <= agora + JANELA_MS &&
                palavras.any { MeuTimeAdiante.normalizar(it) == MeuTimeAdiante.normalizar(i.palavra) } &&
                palavras.any { MeuTimeAdiante.casa(i.titulo, it) }
        }.minByOrNull { it.inicio }
    }

    private val reVersus = Regex("""\s+(?:x|X|vs\.?|VS\.?|v\.?|×)\s+""")

    /**
     * "Futebol: Flamengo x Palmeiras" -> "Flamengo × Palmeiras". O que vem antes de ": " sai quando o resto tem o "×"
     * (é o nome do campeonato ou do esporte). Mais longo que [MAX_TITULO]: corta com "…".
     */
    fun titulo(bruto: String): String {
        var t = bruto.trim().replace(Regex("\\s+"), " ").replace(reVersus, " × ")
        val dois = t.lastIndexOf(": ")
        if (dois > 0 && t.substring(dois + 2).contains(" × ")) t = t.substring(dois + 2).trim()
        return if (t.length > MAX_TITULO) t.take(MAX_TITULO - 1).trimEnd() + "…" else t
    }

    /** "Flamengo × Palmeiras · 21:30" (a hora de início no fuso da TV). */
    fun texto(i: Item, fuso: TimeZone): String {
        val c = Calendar.getInstance(fuso).apply { timeInMillis = i.inicio }
        return String.format(java.util.Locale.ROOT, "%s · %02d:%02d", titulo(i.titulo), c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }
}
