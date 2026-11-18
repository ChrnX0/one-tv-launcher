package br.paulo.tvlauncher

import org.json.JSONObject

/**
 * Antes e depois da limpeza (elevação 14.3, onda 0, 17/11/2026): as REGRAS (AntesDepoisTest). Quem mede é o
 * LimpezaMedida (memória livre do Android e `am start -W` pelo shell próprio).
 *
 * Mede duas vezes, a pedido da pessoa: antes de ocultar os apps de fábrica e depois. A frase final cabe numa linha:
 * "310 MB livres a mais · Netflix abre 1,2 s mais rápido". Diferença pequena (ruído da medida) não vira promessa.
 */
object AntesDepois {
    /** Menos que isso de memória é ruído (o Android mexe em dezenas de MB sozinho). */
    const val RUIDO_MB = 30L
    /** Menos que isso no tempo de abrir é ruído. */
    const val RUIDO_MS = 150L

    data class Medida(val livreMb: Long, val abrirMs: Map<String, Long>, val em: Long)

    /** O que mudou: memória (MB, + = mais livre) e, por app, quanto abriu mais rápido (ms, + = mais rápido). */
    data class Diferenca(val memoriaMb: Long, val apps: List<Pair<String, Long>>)

    fun comparar(antes: Medida, depois: Medida): Diferenca = Diferenca(
        depois.livreMb - antes.livreMb,
        antes.abrirMs.keys.filter { it in depois.abrirMs }.map { it to (antes.abrirMs.getValue(it) - depois.abrirMs.getValue(it)) },
    )

    /**
     * Uma linha. [nomes]: pacote → nome do app. Textos vêm de fora (strings da TV) para as 14 línguas:
     * [maisLivre] "%1$d MB livres a mais", [menosLivre] "%1$d MB livres a menos", [maisRapido] "%1$s abre %2$s mais
     * rápido", [maisLento] "%1$s abre %2$s mais devagar", [igual] "sem diferença que dê para medir".
     */
    fun frase(
        d: Diferenca, nomes: Map<String, String>, maisLivre: String, menosLivre: String, maisRapido: String, maisLento: String,
        igual: String, tempo: (Long) -> String,
    ): String {
        val partes = ArrayList<String>()
        when {
            d.memoriaMb >= RUIDO_MB -> partes += maisLivre.format(d.memoriaMb)
            d.memoriaMb <= -RUIDO_MB -> partes += menosLivre.format(-d.memoriaMb)
        }
        // O app que mais mudou primeiro; no máximo 2 apps para caber numa linha.
        for ((p, ms) in d.apps.filter { kotlin.math.abs(it.second) >= RUIDO_MS }.sortedByDescending { kotlin.math.abs(it.second) }.take(2)) {
            val nome = nomes[p] ?: p
            partes += if (ms > 0) maisRapido.format(nome, tempo(ms)) else maisLento.format(nome, tempo(-ms))
        }
        return if (partes.isEmpty()) igual else partes.joinToString(" · ")
    }

    /** Lê o "TotalTime: 1234" do `am start -W` (CRLF e linhas a mais). null = não abriu ou não mediu. */
    fun tempoDoAmStart(saida: String?): Long? {
        if (saida == null) return null
        if (saida.lines().any { it.trim().startsWith("Error") }) return null
        return Regex("""TotalTime:\s*(\d+)""").find(saida)?.groupValues?.get(1)?.toLongOrNull()?.takeIf { it in 1..60_000 }
    }

    fun texto(m: Medida): String = JSONObject().put("livre", m.livreMb).put("em", m.em)
        .put("abrir", JSONObject().apply { m.abrirMs.forEach { (k, v) -> put(k, v) } }).toString()

    fun ler(t: String?): Medida? = try {
        val o = JSONObject(t ?: return null)
        val a = o.optJSONObject("abrir") ?: JSONObject()
        Medida(o.getLong("livre"), a.keys().asSequence().associateWith { a.getLong(it) }, o.getLong("em"))
    } catch (_: Exception) { null }
}
