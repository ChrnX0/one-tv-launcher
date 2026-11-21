package br.paulo.tvlauncher.settings

import br.paulo.tvlauncher.Estilo
import kotlin.math.pow

/**
 * Contas de cor das elevações 16.3 (alto contraste) e 16.4 (nunca só pela cor), 17/11/2026. Pura (sem Android), para
 * os testes JVM e o teste de telas usarem as mesmas contas.
 *
 * - [contraste]: a razão de contraste da WCAG 2.x entre duas cores (1:1 a 21:1). Alto contraste pede 7:1 (nível AAA).
 * - [simular]: como uma pessoa com daltonismo vê a cor (matrizes de Machado, Oliveira e Fernandes 2009, severidade 1,
 *   aplicadas em RGB linear). Serve para conferir as fotos das telas e para provar que um estado nunca depende só da cor.
 *
 * Cores em ARGB (o Int do Android); o alfa é ignorado (a conta é da cor já composta sobre o fundo).
 */
object CorSegura {
    enum class Daltonismo { PROTANOPIA, DEUTERANOPIA, TRITANOPIA }

    /** O sinal que acompanha cada estado (16.4): quem não distingue a cor lê o sinal. */
    const val SINAL_OK = "✓"
    const val SINAL_ATENCAO = "!"
    const val SINAL_FALHA = "✕"

    private fun linear(c: Int): Double { val v = c / 255.0; return if (v <= 0.04045) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4) }
    private fun gama(v: Double): Int {
        val x = v.coerceIn(0.0, 1.0)
        return ((if (x <= 0.0031308) x * 12.92 else 1.055 * x.pow(1 / 2.4) - 0.055) * 255.0 + 0.5).toInt().coerceIn(0, 255)
    }

    /** Luminância relativa (WCAG): 0 = preto, 1 = branco. */
    fun luminancia(cor: Int): Double =
        0.2126 * linear((cor shr 16) and 0xFF) + 0.7152 * linear((cor shr 8) and 0xFF) + 0.0722 * linear(cor and 0xFF)

    /** Razão de contraste WCAG entre duas cores, de 1.0 a 21.0. */
    fun contraste(a: Int, b: Int): Double {
        val la = luminancia(a); val lb = luminancia(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }

    /** Compõe [frente] (com alfa) sobre [fundo] opaco: a cor que de fato aparece. */
    fun compor(frente: Int, fundo: Int): Int {
        val a = ((frente ushr 24) and 0xFF) / 255.0
        fun canal(s: Int) = (((frente shr s) and 0xFF) * a + ((fundo shr s) and 0xFF) * (1 - a) + 0.5).toInt()
        return (0xFF shl 24) or (canal(16) shl 16) or (canal(8) shl 8) or canal(0)
    }

    private val MATRIZ = mapOf(
        Daltonismo.PROTANOPIA to doubleArrayOf(0.152286, 1.052583, -0.204868, 0.114503, 0.786281, 0.099216, -0.003882, -0.048116, 1.051998),
        Daltonismo.DEUTERANOPIA to doubleArrayOf(0.367322, 0.860646, -0.227968, 0.280085, 0.672501, 0.047413, -0.011820, 0.042940, 0.968881),
        Daltonismo.TRITANOPIA to doubleArrayOf(1.255528, -0.076749, -0.178779, -0.078411, 0.930809, 0.147602, 0.004733, 0.691367, 0.303900),
    )

    /** A cor como vista com o [tipo] de daltonismo (alfa preservado). */
    fun simular(cor: Int, tipo: Daltonismo): Int {
        val m = MATRIZ.getValue(tipo)
        val r = linear((cor shr 16) and 0xFF); val g = linear((cor shr 8) and 0xFF); val b = linear(cor and 0xFF)
        val nr = gama(m[0] * r + m[1] * g + m[2] * b)
        val ng = gama(m[3] * r + m[4] * g + m[5] * b)
        val nb = gama(m[6] * r + m[7] * g + m[8] * b)
        return (cor and 0xFF000000.toInt()) or (nr shl 16) or (ng shl 8) or nb
    }

    /** Distância simples entre duas cores (0 a ~441): abaixo de ~40 a maioria das pessoas não separa as duas. */
    fun distancia(a: Int, b: Int): Double {
        val dr = ((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)
        val dg = ((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)
        val db = (a and 0xFF) - (b and 0xFF)
        return kotlin.math.sqrt((dr * dr + dg * dg + db * db).toDouble())
    }

    /** O verde do "online" da turma (JogosActivity.VERDE). */
    const val VERDE_ONLINE = 0xFF57D98A.toInt()

    /** As cores de estado do app (ok, atenção, falha): as que nunca podem aparecer sozinhas, sem sinal ou palavra. */
    fun ehCorDeEstado(cor: Int): Boolean {
        val c = cor or 0xFF000000.toInt()
        return c == Estilo.ERRO || c == Estilo.ATENCAO || c == VERDE_ONLINE
    }

    /** O texto já leva sinal? (16.4: "✓", "!", "✕", "⚠"...) Palavra também vale: duas ou mais letras seguidas. */
    fun temSinalOuPalavra(texto: String): Boolean {
        val t = texto.trim()
        if (t.any { it in "✓✔!✕✗×⚠▲▼⏸▶■◆★?" }) return true
        return Regex("""\p{L}{2,}""").containsMatchIn(t)
    }
}
