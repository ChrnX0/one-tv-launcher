package br.paulo.tvlauncher

/**
 * "Teste do controle" da tela Jogos (21/11/2026): de quanto em quanto tempo o controle manda sinal enquanto a pessoa
 * gira o analógico. Na Sala, o DualSense por Bluetooth chegou a cada 32-44 ms com pausas de 200 ms+ (o normal é ~4 ms)
 * e o Paulo sentiu o jogo "atrasado": é o número que explica isso. Lógica pura (testes em ControleLatenciaTest).
 */
object ControleLatencia {
    enum class Veredito { BOM, JOGAVEL, LENTO, PARADO }

    /** [media] = intervalo médio em ms (só entre sinais seguidos, < 200 ms); [pausas] = buracos de 200 ms ou mais. */
    class Resultado(val sinais: Int, val media: Int, val pausas: Int, val veredito: Veredito)

    /** Menos que isso em 10 s = a pessoa não girou o analógico (não dá para medir). */
    const val MINIMO_SINAIS = 40

    /** [tempos] = hora (ms) de cada sinal do analógico, em ordem (incluindo os do histórico do MotionEvent). */
    fun resumo(tempos: List<Long>): Resultado {
        val ord = tempos.sorted()
        val difs = ord.zipWithNext { a, b -> b - a }.filter { it > 0 }
        val seguidos = difs.filter { it < 200 }
        val pausas = difs.count { it >= 200 }
        if (ord.size < MINIMO_SINAIS || seguidos.isEmpty()) return Resultado(ord.size, 0, pausas, Veredito.PARADO)
        val media = seguidos.average().toInt()
        val v = when {
            media <= 12 && pausas == 0 -> Veredito.BOM
            media <= 25 && pausas <= 2 -> Veredito.JOGAVEL
            else -> Veredito.LENTO
        }
        return Resultado(ord.size, media, pausas, v)
    }
}
