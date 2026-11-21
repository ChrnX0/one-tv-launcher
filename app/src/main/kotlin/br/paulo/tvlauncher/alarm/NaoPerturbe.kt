package br.paulo.tvlauncher.alarm

import org.json.JSONArray
import org.json.JSONObject

/**
 * Não perturbe (elevação 11.4, onda 1, 17/11/2026): a regra, pura (NaoPerturbeTest). A parte Android é o
 * NaoPerturbeTv; quem decide se um aviso passa é o AvisoTv.mostrar.
 *
 * Com a chave ligada (Extras.Chave.NAO_PERTURBE, desligada de fábrica), durante um FILME, de MADRUGADA ou com VISITA
 * os avisos comuns esperam numa fila e voltam num resumo só ("Enquanto você assistia: 3 avisos") quando o motivo
 * acaba. Passam sempre: despertador, campainha, alerta oficial e as regras da casa (hora de dormir, tempo de tela).
 * Confirmação curta do que a própria pessoa pediu (historico = false) também passa: ela está esperando a resposta.
 */
object NaoPerturbe {
    enum class Motivo { FILME, MADRUGADA, VISITA }
    enum class Tipo { COMUM, ALARME, CAMPAINHA, ALERTA_OFICIAL, REGRA }

    const val MADRUGADA_INICIO_MIN = 23 * 60
    const val MADRUGADA_FIM_MIN = 7 * 60
    const val MAX_FILA = 20
    /** Aviso guardado há mais que isso já não serve no resumo (recado da véspera). */
    const val VALIDADE_MS = 12 * 3_600_000L

    /** Apps em que a pessoa está vendo um filme ou série (além do nosso player de filmes). */
    val APPS_FILME = setOf(
        "com.netflix.ninja", "com.amazon.amazonvideo.livingroom", "com.disney.disneyplus", "com.wbd.stream", "com.hbo.hbonow",
        "com.globo.globotv", "com.apple.atve.androidtv.appletv", "com.google.android.youtube.tv", "com.google.android.youtube.tvunplugged",
        "tv.pluto.android", "com.paramount.plus", "com.crunchyroll.crunchyroid", "com.mubi", "br.com.telecine.play",
    )

    fun passa(t: Tipo): Boolean = t != Tipo.COMUM

    fun madrugada(minutoDoDia: Int, inicio: Int = MADRUGADA_INICIO_MIN, fim: Int = MADRUGADA_FIM_MIN): Boolean =
        if (inicio <= fim) minutoDoDia in inicio until fim else minutoDoDia >= inicio || minutoDoDia < fim

    /** Por que segurar agora (null = não segura). Filme primeiro: é o motivo que a pessoa reconhece no resumo. */
    fun motivo(ligado: Boolean, filme: Boolean, minutoDoDia: Int, visita: Boolean): Motivo? = when {
        !ligado -> null
        filme -> Motivo.FILME
        visita -> Motivo.VISITA
        madrugada(minutoDoDia) -> Motivo.MADRUGADA
        else -> null
    }

    data class Guardado(val titulo: String, val texto: String, val em: Long)

    /** Põe na fila (sem repetir o mesmo aviso; o mais novo fica), até [MAX_FILA]. */
    fun guardar(fila: List<Guardado>, g: Guardado): List<Guardado> =
        (fila.filterNot { it.titulo == g.titulo && it.texto == g.texto } + g).takeLast(MAX_FILA)

    /** O que ainda vale mostrar no resumo. */
    fun validos(fila: List<Guardado>, agora: Long): List<Guardado> = fila.filter { agora - it.em < VALIDADE_MS }

    /** O texto do resumo: até 3 títulos, do mais novo para o mais velho, e "+N". */
    fun textoResumo(fila: List<Guardado>, mais: (Int) -> String): String {
        val l = fila.asReversed()
        val nomes = l.take(3).joinToString(" · ") { it.titulo }
        return if (l.size > 3) "$nomes · ${mais(l.size - 3)}" else nomes
    }

    fun texto(l: List<Guardado>): String = JSONArray().apply {
        l.forEach { put(JSONObject().put("t", it.titulo).put("x", it.texto).put("em", it.em)) }
    }.toString()

    fun ler(t: String?): List<Guardado> = runCatching {
        val a = JSONArray(t ?: return emptyList())
        (0 until a.length()).map { i -> a.getJSONObject(i).let { Guardado(it.getString("t"), it.optString("x"), it.getLong("em")) } }
    }.getOrDefault(emptyList())
}
