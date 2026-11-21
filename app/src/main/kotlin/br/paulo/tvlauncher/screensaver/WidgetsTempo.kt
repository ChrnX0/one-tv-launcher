package br.paulo.tvlauncher.screensaver

import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * A parte pura dos widgets de baixo do protetor (15/11/2026, [Protetor.CHAVE_WIDGET]): ler as próximas horas que a HOME
 * grava, escolher as marcas de 2 em 2 e decidir a frase do estilo "sem caixa" ("Chuva fraca até umas 3h").
 * Sem Android: testada na JVM (WidgetsTempoTest).
 */
object WidgetsTempo {
    /** Uma hora da previsão: quando, temperatura, código WMO do céu e chance de chuva (-1 = sem dado). */
    data class Marca(val ms: Long, val temp: Int, val codigo: Int, val chuva: Int)

    /**
     * Lê "hora|temp|código|chuva;…" (ClimaFontes.proximasHoras, hora a hora, no fuso do lugar, que é o da TV).
     * Linha quebrada é pulada, não derruba o resto.
     */
    fun lerHoras(texto: String, fuso: TimeZone = TimeZone.getDefault()): List<Marca> {
        if (texto.isBlank()) return emptyList()
        val leitor = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.US).apply { timeZone = fuso }
        return texto.split(';').mapNotNull { linha ->
            val p = linha.split('|')
            if (p.size < 4) return@mapNotNull null
            val ms = try { leitor.parse(p[0].trim())?.time } catch (e: Exception) { null } ?: return@mapNotNull null
            val temp = p[1].trim().toIntOrNull() ?: return@mapNotNull null
            Marca(ms, temp, p[2].trim().toIntOrNull() ?: 0, p[3].trim().toIntOrNull() ?: -1)
        }.sortedBy { it.ms }
    }

    /** As marcas dos widgets: só as que ainda vão chegar, de 2 em 2 horas, no máximo [quantas]. */
    fun deDuasEmDuas(horas: List<Marca>, agora: Long, quantas: Int = 5): List<Marca> =
        horas.filter { it.ms > agora }.filterIndexed { i, _ -> i % 2 == 0 }.take(quantas)

    /** Chance de chuva que conta como "vai chover" e a que conta como "parou". Entre as duas, a frase não arrisca. */
    const val CHUVA_SIM = 60
    const val CHUVA_NAO = 30
    /** Até quantas horas à frente a frase olha: mais longe que isso a previsão muda demais. */
    const val HORIZONTE_MS = 12 * 60 * 60_000L

    sealed class Frase {
        /** Chove agora e para lá pelas [ms]. */
        data class ParaPorVolta(val ms: Long) : Frase()
        /** Chove agora e continua pelas próximas horas. */
        object ChuvaSegue : Frase()
        /** Não chove agora e começa lá pelas [ms]. */
        data class ChegaPorVolta(val ms: Long) : Frase()
    }

    /**
     * A frase do tempo para as próximas horas, ou null quando não há nada a dizer (aí o widget mostra só o céu de agora).
     * Hora sem dado de chuva (-1) não decide nada.
     */
    fun frase(chovendo: Boolean, horas: List<Marca>, agora: Long): Frase? {
        val futuras = horas.filter { it.ms > agora && it.ms <= agora + HORIZONTE_MS && it.chuva >= 0 }
        if (futuras.isEmpty()) return null
        return if (chovendo) {
            futuras.firstOrNull { it.chuva < CHUVA_NAO }?.let { Frase.ParaPorVolta(it.ms) }
                ?: if (futuras.all { it.chuva >= CHUVA_NAO }) Frase.ChuvaSegue else null
        } else futuras.firstOrNull { it.chuva >= CHUVA_SIM }?.let { Frase.ChegaPorVolta(it.ms) }
    }

    /**
     * Altura de cada temperatura na curva, de 0 (a mais fria) a 1 (a mais quente). Tudo igual fica no meio: uma linha
     * reta no alto ou no pé da caixa daria a impressão de calor ou frio que não existe.
     */
    fun alturas(temps: List<Int>): List<Float> {
        if (temps.isEmpty()) return emptyList()
        val min = temps.min(); val max = temps.max()
        if (max == min) return temps.map { 0.5f }
        return temps.map { (it - min).toFloat() / (max - min) }
    }

    /**
     * Onde o nascer (ou pôr) do sol cai entre as marcas, de 0 (primeira) a 1 (última); null se ficar fora delas.
     * Serve para riscar a hora do sol na curva das próximas horas.
     */
    fun posicaoNaCurva(marcas: List<Marca>, ms: Long?): Float? {
        if (ms == null || marcas.size < 2) return null
        val ini = marcas.first().ms; val fim = marcas.last().ms
        if (ms < ini || ms > fim) return null
        return (ms - ini).toFloat() / (fim - ini)
    }

    /**
     * Onde escrever o "pôr 18:29" (ou "nasce") na faixa das barras de chuva sem cobrir nenhuma barra (foto da Sala,
     * 15/11/2026: o rótulo ficava em cima da barra das 20h). Tenta à direita do risco, depois à esquerda, e por fim
     * centraliza no vão entre as duas barras vizinhas. Devolve o x da esquerda do texto.
     * [barras] = x do centro de cada barra desenhada; [meia] = meia largura da barra + folga; [w] = largura da curva.
     */
    fun xRotuloSol(xSol: Float, largura: Float, barras: List<Float>, meia: Float, folga: Float, w: Float): Float {
        fun livre(tx: Float) = tx >= 0f && tx + largura <= w && barras.none { b -> b + meia > tx && b - meia < tx + largura }
        val direita = xSol + folga
        if (livre(direita)) return direita
        val esquerda = xSol - folga - largura
        if (livre(esquerda)) return esquerda
        // Vão entre a barra de cada lado do risco (ou a borda da curva, se não houver barra).
        val ini = (barras.filter { it <= xSol }.maxOrNull()?.plus(meia)) ?: 0f
        val fim = (barras.filter { it > xSol }.minOrNull()?.minus(meia)) ?: w
        return ((ini + fim - largura) / 2f).coerceIn(0f, (w - largura).coerceAtLeast(0f))
    }
}
