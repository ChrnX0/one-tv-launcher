package br.paulo.tvlauncher.alarm

/**
 * A hora dita em palavras, para o relógio "Por extenso" do protetor (17/11/2026): "Nove e cinquenta e um" e embaixo
 * "da noite". Só em português (cada língua fala a hora de um jeito; inventar a regra das outras sairia errado).
 * Português de Portugal muda três números (dezasseis, dezassete, dezanove). Lógica pura (teste: HoraPorExtensoTest).
 */
object HoraPorExtenso {
    /** Existe para esta língua? (código ISO: "pt" serve para o Brasil e para Portugal.) */
    fun disponivel(lingua: String): Boolean = lingua.lowercase() == "pt"

    private val UNIDADES_BR = listOf("", "um", "dois", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze",
        "doze", "treze", "catorze", "quinze", "dezesseis", "dezessete", "dezoito", "dezenove")
    private val DEZENAS = listOf("", "", "vinte", "trinta", "quarenta", "cinquenta")
    // As horas são femininas: "uma", "duas" (a hora), e os minutos masculinos: "um", "dois" (o minuto).
    private val HORAS = listOf("", "uma", "duas", "três", "quatro", "cinco", "seis", "sete", "oito", "nove", "dez", "onze", "doze")

    private fun unidade(n: Int, portugal: Boolean): String = if (portugal) when (n) {
        16 -> "dezasseis"; 17 -> "dezassete"; 19 -> "dezanove"; else -> UNIDADES_BR[n]
    } else UNIDADES_BR[n]

    /** Os minutos (1 a 59) em palavras: "cinquenta e um". */
    fun minutos(m: Int, portugal: Boolean = false): String {
        if (m < 20) return unidade(m, portugal)
        val d = m / 10; val u = m % 10
        return DEZENAS[d] + if (u == 0) "" else " e " + unidade(u, portugal)
    }

    /**
     * [h] 0-23, [m] 0-59 -> (a hora, o período). "Nove e meia" / "da manhã"; "Meio-dia" / ""; "Uma em ponto" / "da tarde".
     * Períodos: madrugada 0-5, manhã 6-11, tarde 12-17, noite 18-23 (meia-noite e meio-dia já dizem o período).
     */
    fun texto(h: Int, m: Int, portugal: Boolean = false): Pair<String, String> {
        val hora = when (h) { 0 -> "meia-noite"; 12 -> "meio-dia"; else -> HORAS[h % 12] }
        val resto = when {
            m == 0 && (h == 0 || h == 12) -> ""
            m == 0 -> " em ponto"
            m == 30 -> " e meia"
            else -> " e " + minutos(m, portugal)
        }
        val periodo = when {
            h == 0 || h == 12 -> ""
            h < 6 -> "da madrugada"
            h < 12 -> "da manhã"
            h < 18 -> "da tarde"
            else -> "da noite"
        }
        val frase = hora + resto
        return frase.replaceFirstChar { it.uppercase() } to periodo
    }
}
