package br.paulo.tvlauncher.listen

import org.json.JSONObject

/**
 * Ajustes de som por podcast (proposta C do estudo "podcast e rádio contra os melhores", 21/11/2026).
 *
 * Por quê: o Pocket Casts, o Apple Podcasts e o Overcast guardam velocidade e efeitos por programa. Um noticiário rende
 * em 1,5x e um audiodrama só em 1x; uma mesa-redonda mal gravada precisa de voz clara, um podcast de estúdio não.
 * Para quê: escolher uma vez e o podcast tocar sempre do jeito certo, sem mexer no menu a cada episódio.
 * Como: cada campo é opcional; vazio = vale o ajuste geral do Ouvir. Guardado como JSON nas prefs "ouvir", chave por
 * feed (endereço normalizado, o mesmo de [Podcasts.normalizar]).
 *
 * Pular abertura e fim: segundos do começo que o episódio pula ao começar do zero (vinheta, propaganda fixa) e
 * segundos do fim que contam como "terminou" (créditos, chamada do próximo). Retomar do meio não pula nada.
 */
object AjustesPodcast {
    /** Opções de pular a abertura e o fim, em segundos (0 = não pula). */
    val PULAR_S = listOf(0, 10, 15, 30, 45, 60, 90, 120)

    data class Ajuste(
        val velocidade: Float? = null,
        val vozClara: Boolean? = null,
        val pularSilencio: Boolean? = null,
        val aberturaS: Int = 0,
        val fimS: Int = 0,
    ) {
        val vazio: Boolean get() = velocidade == null && vozClara == null && pularSilencio == null && aberturaS == 0 && fimS == 0
    }

    /** O que vale de fato para o episódio: o do podcast, ou o geral. */
    data class Efetivo(val velocidade: Float, val vozClara: Boolean, val pularSilencio: Boolean, val aberturaS: Int, val fimS: Int)

    fun efetivo(a: Ajuste?, velocidadeGeral: Float, vozGeral: Boolean, silencioGeral: Boolean): Efetivo = Efetivo(
        a?.velocidade ?: velocidadeGeral, a?.vozClara ?: vozGeral, a?.pularSilencio ?: silencioGeral,
        a?.aberturaS ?: 0, a?.fimS ?: 0,
    )

    fun chave(feed: String) = "ajuste:" + Podcasts.normalizar(feed)

    fun paraJson(a: Ajuste): String = JSONObject().apply {
        a.velocidade?.let { put("v", it.toDouble()) }
        a.vozClara?.let { put("voz", it) }
        a.pularSilencio?.let { put("sil", it) }
        if (a.aberturaS > 0) put("ab", a.aberturaS)
        if (a.fimS > 0) put("fim", a.fimS)
    }.toString()

    fun deJson(t: String?): Ajuste? {
        if (t.isNullOrBlank()) return null
        return runCatching {
            val o = JSONObject(t)
            Ajuste(
                if (o.has("v")) o.getDouble("v").toFloat().takeIf { it in 0.5f..3f } else null,
                if (o.has("voz")) o.getBoolean("voz") else null,
                if (o.has("sil")) o.getBoolean("sil") else null,
                o.optInt("ab", 0).coerceIn(0, 600),
                o.optInt("fim", 0).coerceIn(0, 600),
            ).takeUnless { it.vazio }
        }.getOrNull()
    }

    /**
     * Onde começar: só pula a abertura quando o episódio começaria do zero (até 2 s, a margem de quem apertou ⏯ logo).
     * Episódio mais curto que abertura + 1 min não pula (um trailer de 40 s com abertura de 30 s sumiria).
     */
    fun inicio(inicioMs: Long, aberturaS: Int, duracaoMs: Long): Long {
        if (aberturaS <= 0 || inicioMs > 2_000) return inicioMs
        val ab = aberturaS * 1000L
        if (duracaoMs in 1 until ab + 60_000) return inicioMs
        return ab
    }

    /** Chegou ao trecho do fim que o dono mandou pular? Só com a duração conhecida. */
    fun noFim(posMs: Long, duracaoMs: Long, fimS: Int): Boolean =
        fimS > 0 && duracaoMs > (fimS * 1000L + 60_000) && posMs >= duracaoMs - fimS * 1000L
}
