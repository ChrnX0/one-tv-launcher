package br.paulo.tvlauncher

import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/**
 * "Meu time" com alcance de dias (ideia 16, 14/11/2026). O guia guarda só ~14 h à frente (GuiaConsulta.janela), então
 * o lembrete do "Meu time" não via o jogo de sábado na quarta. Agora, ao baixar o guia, os programas além da janela que
 * casam com as palavras do "Meu time" (até [JANELA_MS] à frente) vão para uma lista à parte, pequena ([MAX] partidas),
 * gravada num arquivo (PlayerCarga) e lida pelos lembretes (LembretesTv.varrer). Nada além disso fica na memória.
 *
 * Também mora aqui o casamento palavra/título (normalizar, casa), que o Lembretes usa: assim esta regra roda pura, sem
 * Android nem o resto do player, no MeuTimeAdianteTest (testes-puros.sh).
 */
object MeuTimeAdiante {
    const val DIAS = 7
    const val JANELA_MS = DIAS * 24 * 3_600_000L
    /** Teto da lista guardada: um time joga poucas vezes por semana; o resto é reprise. */
    const val MAX = 60
    /** Teto de título guardado (o aviso mostra uma linha). */
    private const val MAX_TITULO = 120

    /** Um programa achado: só o que o lembrete precisa (sem descrição). */
    data class Partida(val guiaId: String, val inicio: Long, val fim: Long, val titulo: String)

    private val MARCAS = Regex("\\p{Mn}+")

    /** Minúsculas e sem acento: "São dono" e "sao ana" casam. */
    fun normalizar(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD).replace(MARCAS, "").lowercase().trim()

    /**
     * O título tem a palavra inteira? "Palmeiras x Santos" casa com "palmeiras"; "Palmeirense" não. A palavra pode ter
     * espaço ("formula 1"): vale como trecho, com borda de palavra nas duas pontas.
     */
    fun casa(titulo: String, palavra: String): Boolean {
        val p = normalizar(palavra)
        if (p.length < 3) return false
        val t = normalizar(titulo)
        var i = t.indexOf(p)
        while (i >= 0) {
            val antes = i == 0 || !t[i - 1].isLetterOrDigit()
            val depois = i + p.length >= t.length || !t[i + p.length].isLetterOrDigit()
            if (antes && depois) return true
            i = t.indexOf(p, i + 1)
        }
        return false
    }

    /**
     * Junta as partidas durante a leitura do guia, em fluxo: cada programa passa por [oferecer] e só os que casam ficam.
     * A memória fica em até 2 × [max] partidas (ao passar disso, fica com as [max] mais cedo).
     */
    class Coletor(private val palavras: List<String>, private val agora: Long, private val max: Int = MAX) {
        private val lista = ArrayList<Partida>()
        private val chaves = HashSet<String>()

        /** Devolve true se o programa entrou. */
        fun oferecer(guiaId: String, inicio: Long, fim: Long, titulo: String): Boolean {
            if (palavras.isEmpty() || guiaId.isEmpty() || fim <= inicio) return false
            if (inicio <= agora || inicio > agora + JANELA_MS) return false
            if (palavras.none { casa(titulo, it) }) return false
            if (!chaves.add("$guiaId|$inicio")) return false
            lista += Partida(guiaId, inicio, fim, titulo.take(MAX_TITULO))
            if (lista.size > 2 * max) aparar()
            return true
        }

        private fun aparar() {
            lista.sortBy { it.inicio }
            while (lista.size > max) chaves.remove(lista.removeAt(lista.size - 1).let { "${it.guiaId}|${it.inicio}" })
        }

        /** As partidas achadas, das mais cedo para as mais tarde, no máximo [max]. */
        fun partidas(): List<Partida> { aparar(); return lista.toList() }
    }

    /**
     * Na hora do lembrete: as partidas guardadas que ainda servem. Saem as que já acabaram, as fora do alcance e as que
     * não casam mais com as palavras de agora (palavra apagada no painel some na hora, sem esperar o próximo guia).
     */
    fun validas(partidas: List<Partida>, palavras: List<String>, agora: Long): List<Partida> =
        partidas.filter { it.fim > agora && it.inicio <= agora + JANELA_MS && palavras.any { p -> casa(it.titulo, p) } }

    fun paraJson(partidas: List<Partida>): String = JSONArray().apply {
        for (p in partidas) put(JSONObject().put("c", p.guiaId).put("i", p.inicio).put("f", p.fim).put("t", p.titulo))
    }.toString()

    /** Lê o arquivo; item estragado sai sozinho, texto inválido vira lista vazia. */
    fun deJson(texto: String?): List<Partida> {
        val a = try { JSONArray(texto ?: return emptyList()) } catch (_: Exception) { return emptyList() }
        val saida = ArrayList<Partida>()
        for (i in 0 until minOf(a.length(), MAX)) {
            val o = a.optJSONObject(i) ?: continue
            val c = o.optString("c"); val t = o.optString("t"); val ini = o.optLong("i"); val fim = o.optLong("f")
            if (c.isEmpty() || t.isEmpty() || ini <= 0 || fim <= ini) continue
            saida += Partida(c, ini, fim, t)
        }
        return saida
    }
}
