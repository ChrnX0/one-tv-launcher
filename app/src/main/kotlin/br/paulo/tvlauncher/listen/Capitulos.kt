package br.paulo.tvlauncher.listen

import org.json.JSONObject

/**
 * Capítulos de um episódio (proposta C do Ouvir, 21/11/2026).
 *
 * Por quê: os três líderes (Pocket Casts, Apple, Overcast) mostram capítulos e deixam pular de um para o outro; o nosso
 * feed já lia o endereço deles e não usava.
 * Para quê: ver em que parte do programa se está ("Entrevista", "Notícias do dia") e ir direto para a que interessa.
 * Como: duas fontes, as mesmas dos outros apps:
 *  - Podcasting 2.0: `<podcast:chapters url="…json">`, um JSON {"chapters":[{"startTime":12.5,"title":"…"}]};
 *  - Podlove: `<psc:chapter start="00:12:30.000" title="…"/>` dentro do feed, guardado por [PodcastFeed] como
 *    "[PSC]início\ttítulo\n…" no mesmo campo do endereço.
 * Capítulo com "toc": false (marcação interna, não é para o índice) fica de fora.
 */
object Capitulos {
    const val MAX = 200
    /** Marca do campo [PodcastFeed.Episodio.capitulos] quando os capítulos vieram dentro do feed (Podlove). */
    const val PSC = "psc:"

    data class Capitulo(val inicioMs: Long, val titulo: String)

    /** O campo do episódio é um endereço para baixar (JSON)? (Senão, os capítulos já vieram no feed.) */
    fun precisaBaixar(campo: String) = campo.isNotEmpty() && !campo.startsWith(PSC)

    /** Capítulos que vieram dentro do feed (Podlove), ou vazio. */
    fun doCampo(campo: String): List<Capitulo> {
        if (!campo.startsWith(PSC)) return emptyList()
        return arrumar(campo.removePrefix(PSC).lineSequence().mapNotNull { l ->
            val i = l.indexOf('\t'); if (i <= 0) return@mapNotNull null
            val ms = tempoMs(l.substring(0, i)) ?: return@mapNotNull null
            Capitulo(ms, l.substring(i + 1).trim())
        }.toList())
    }

    /** O JSON do Podcasting 2.0. Texto quebrado ou fora do formato: vazio. */
    fun doJson(texto: String?): List<Capitulo> {
        if (texto.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONObject(texto).optJSONArray("chapters") ?: return emptyList()
            arrumar((0 until minOf(a.length(), MAX)).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                if (o.has("toc") && !o.optBoolean("toc", true)) return@mapNotNull null
                val s = o.optDouble("startTime", Double.NaN)
                if (s.isNaN() || s < 0) return@mapNotNull null
                Capitulo((s * 1000).toLong(), o.optString("title"))
            })
        }.getOrDefault(emptyList())
    }

    /** Em ordem, sem título vazio, sem dois no mesmo segundo; menos de 2 capítulos não é índice (vazio). */
    private fun arrumar(l: List<Capitulo>): List<Capitulo> {
        val ok = l.map { it.copy(titulo = it.titulo.replace(Regex("\\s+"), " ").trim().take(120)) }
            .filter { it.titulo.isNotEmpty() }.sortedBy { it.inicioMs }.distinctBy { it.inicioMs / 1000 }.take(MAX)
        return if (ok.size < 2) emptyList() else ok
    }

    /** "1:02:03.500", "12:30", "75" (segundos) → ms. */
    fun tempoMs(t: String): Long? {
        val p = t.trim().split(':')
        if (p.isEmpty() || p.size > 3) return null
        val nums = p.map { it.toDoubleOrNull() ?: return null }
        if (nums.any { it < 0 }) return null
        val s = nums.fold(0.0) { acc, n -> acc * 60 + n }
        return (s * 1000).toLong()
    }

    /** Índice do capítulo em [posMs], ou -1 (antes do primeiro). */
    fun atual(c: List<Capitulo>, posMs: Long): Int = c.indexOfLast { it.inicioMs <= posMs }

    /** Onde começa o próximo capítulo, ou null (já no último). */
    fun proximo(c: List<Capitulo>, posMs: Long): Long? = c.getOrNull(atual(c, posMs) + 1)?.inicioMs

    /**
     * ⏮ de capítulo: com mais de 3 s dentro do capítulo, volta ao começo dele (como faixa de música); senão, ao anterior.
     */
    fun anterior(c: List<Capitulo>, posMs: Long): Long? {
        val i = atual(c, posMs)
        if (i < 0) return null
        return if (posMs - c[i].inicioMs > 3_000 || i == 0) c[i].inicioMs else c[i - 1].inicioMs
    }
}
