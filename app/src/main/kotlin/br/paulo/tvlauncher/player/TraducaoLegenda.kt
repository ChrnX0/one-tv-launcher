package br.paulo.tvlauncher.player

/**
 * Ideia 90 (legenda traduzida na TV, 15/11/2026), só a parte pura (testada em TraducaoLegendaTest).
 *
 * Medido na TV da Sala (Chromecast 2 GB, 32 bits) com o tradutor do ML Kit: 1500 falas em 271 s (180 ms cada), pico de
 * 94 MB. Decisão do Paulo: traduzir antes os [PRIMEIRO_BLOCO_MS] a partir de onde o vídeo está (uns 20 s de espera) e o
 * resto em seguida, em segundo plano, sempre à frente da cena. Nunca fala por fala na hora em que aparece.
 */
object TraducaoLegenda {
    const val PRIMEIRO_BLOCO_MS = 10L * 60_000
    /** A origem procurada no OpenSubtitles quando falta legenda no idioma da TV. */
    const val ORIGEM = "en"
    const val MAX_LINHA = 42

    data class Fala(val inicio: Long, val fim: Long, val tempo: String, val texto: String)

    private val reTempo = Regex("""(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}):(\d{2}):(\d{2})[,.](\d{1,3})""")

    private fun ms(h: String, m: String, s: String, f: String): Long =
        ((h.toLong() * 60 + m.toLong()) * 60 + s.toLong()) * 1000 + f.padEnd(3, '0').take(3).toLong()

    /** As falas de um .srt, na ordem do arquivo. Bloco sem linha de tempo ou sem texto fica de fora. */
    fun ler(srt: String): List<Fala> {
        val saida = ArrayList<Fala>()
        for (bloco in srt.replace("\r\n", "\n").replace('\r', '\n').removePrefix("﻿").split(Regex("\n\\s*\n"))) {
            val linhas = bloco.lines().map { it.trimEnd() }
            val i = linhas.indexOfFirst { reTempo.containsMatchIn(it) }
            if (i < 0) continue
            val m = reTempo.find(linhas[i])!!.groupValues
            val texto = linhas.drop(i + 1).filter { it.isNotBlank() }.joinToString("\n")
            if (texto.isBlank()) continue
            saida += Fala(ms(m[1], m[2], m[3], m[4]), ms(m[5], m[6], m[7], m[8]), linhas[i].trim(), texto)
        }
        return saida
    }

    /** Volta a um .srt numerado de 1 em diante. */
    fun escrever(falas: List<Fala>): String = buildString {
        falas.forEachIndexed { i, f -> append(i + 1).append('\n').append(f.tempo).append('\n').append(f.texto).append("\n\n") }
    }

    /**
     * O texto que vai para o tradutor: sem marcas (<i>, {\an8}), as linhas juntas numa frase só (o tradutor erra menos
     * com a frase inteira) e o travessão de diálogo preservado em linhas separadas.
     */
    fun paraTraduzir(texto: String): List<String> {
        val limpo = texto.replace(Regex("<[^>]{1,20}>"), "").replace(Regex("\\{\\\\[^}]{1,30}\\}"), "")
        val linhas = limpo.lines().map { it.trim() }.filter { it.isNotEmpty() }
        // Diálogo ("- Oi." / "- Tudo bem?"): cada fala da pessoa vai separada.
        return if (linhas.size > 1 && linhas.all { it.startsWith("-") }) linhas
        else listOf(linhas.joinToString(" ")).filter { it.isNotEmpty() }
    }

    /** No máximo 2 linhas de até ~[MAX_LINHA] letras, quebrando no espaço mais perto do meio. */
    fun quebrar(texto: String, max: Int = MAX_LINHA): String {
        val t = texto.trim().replace(Regex("\\s+"), " ")
        if (t.length <= max) return t
        val meio = t.length / 2
        val espacos = t.indices.filter { t[it] == ' ' }
        val corte = espacos.minByOrNull { kotlin.math.abs(it - meio) } ?: return t
        return t.substring(0, corte) + "\n" + t.substring(corte + 1)
    }

    /** Junta de volta o que o tradutor devolveu para uma fala (diálogo: uma linha por pessoa). */
    fun montar(partes: List<String>): String =
        if (partes.size > 1) partes.joinToString("\n") { it.trim() } else quebrar(partes.firstOrNull().orEmpty())

    /**
     * A ordem de tradução: primeiro as falas de [posicaoMs] até [PRIMEIRO_BLOCO_MS] depois (o que a pessoa vai ver
     * agora), depois o resto para a frente e, por último, o que ficou para trás. Índices de [falas].
     */
    fun ordem(falas: List<Fala>, posicaoMs: Long): Pair<List<Int>, List<Int>> {
        val ini = posicaoMs.coerceAtLeast(0)
        val fimBloco = ini + PRIMEIRO_BLOCO_MS
        val primeiro = falas.indices.filter { falas[it].fim >= ini && falas[it].inicio < fimBloco }
        val usados = primeiro.toHashSet()
        val frente = falas.indices.filter { it !in usados && falas[it].inicio >= fimBloco }
        val atras = falas.indices.filter { it !in usados && falas[it].inicio < fimBloco }
        return primeiro to (frente + atras)
    }

    /** As falas já traduzidas (índice -> texto), na ordem do filme; as que ainda não foram ficam de fora. */
    fun parcial(falas: List<Fala>, traduzidas: Map<Int, String>): List<Fala> =
        falas.indices.mapNotNull { i -> traduzidas[i]?.takeIf { it.isNotBlank() }?.let { falas[i].copy(texto = it) } }
}
