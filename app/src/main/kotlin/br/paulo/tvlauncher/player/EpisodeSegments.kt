package br.paulo.tvlauncher.player

import org.json.JSONObject

/**
 * Trechos do episódio além da abertura (players, proposta C, etapa 1, 20/11/2026): o RESUMO ("Anteriormente em...") e o
 * começo dos CRÉDITOS, onde aparece "Próximo episódio" (como na Netflix), e o "termina às" da barra.
 *
 * Fontes, nesta ordem: o IntroDB (mesma resposta da abertura; conferido em 20/11/2026 que ela traz "recap", "outro" e
 * "post_credits") e, sem ele, os capítulos do próprio arquivo ("Recap", "Credits", "Ending"...). Só regras puras
 * (EpisodeSegmentsTest); a rede é o AberturaProntaTv.
 */
object EpisodeSegments {
    /** Créditos têm de cair no fim: depois de 70% do vídeo, a pelo menos 15 s do fim e com no máximo 12 min. */
    const val CREDITOS_DEPOIS_DE = 0.70
    const val CREDITOS_FOLGA_FIM_MS = 15_000L
    const val CREDITOS_MAX_MS = 12 * 60_000L
    /** A pílula "Próximo episódio" some a 2 s do fim: dali o fim normal do vídeo já cuida. */
    const val PROXIMO_FOLGA_FIM_MS = 2_000L
    /** "O que ele disse?": quanto volta. */
    const val REPETIR_MS = 10_000L

    /** O que o IntroDB disse do episódio. [creditos] = início dos créditos em ms. */
    data class Trechos(val abertura: AberturaSerie.Marca?, val resumo: AberturaSerie.Marca?, val creditos: Long?)

    /**
     * Lê a resposta do IntroDB. Resumo: mesmas regras de validade da abertura (10 s a 5 min, nos primeiros 15 min).
     * Créditos: só quando NÃO há cena depois deles ("post_credits"): pular para o próximo faria perder a cena.
     * Null = resposta ilegível.
     */
    fun lerIntroDb(json: String?): Trechos? {
        val o = try { JSONObject(json ?: return null) } catch (_: Exception) { return null }
        val resumo = if (o.optBoolean("no_recap")) null else trecho(o.optJSONObject("recap"))?.takeIf { AberturaSerie.valida(it) }
        val temCena = o.optJSONObject("post_credits") != null
        val creditos = if (o.optBoolean("no_outro") || temCena) null else trecho(o.optJSONObject("outro"))?.ini
        return Trechos(AberturaPronta.ler(json), resumo, creditos)
    }

    private fun trecho(t: JSONObject?): AberturaSerie.Marca? {
        t ?: return null
        if (t.optDouble("confidence", 1.0) < AberturaPronta.CONFIANCA_MIN) return null
        return try {
            val ini = if (t.has("start_ms")) t.getLong("start_ms") else (t.getDouble("start_sec") * 1000).toLong()
            val fim = if (t.has("end_ms")) t.getLong("end_ms") else (t.getDouble("end_sec") * 1000).toLong()
            AberturaSerie.Marca(ini, fim)
        } catch (_: Exception) { null }
    }

    private val CAP_RESUMO = Regex("""^(recap|previously( on)?|anteriormente|resumo|previamente)\b""", RegexOption.IGNORE_CASE)
    private val CAP_CREDITOS = Regex("""^(ed|ending|end credits|credits|cr[eé]ditos( finais)?|encerramento|outro)\b""", RegexOption.IGNORE_CASE)

    /** Capítulo "Recap"/"Anteriormente" do arquivo, se valer como trecho a pular. */
    fun resumoDoCapitulo(caps: List<Capitulos.Capitulo>): AberturaSerie.Marca? = caps.firstOrNull { c ->
        CAP_RESUMO.containsMatchIn(c.titulo?.trim().orEmpty())
    }?.let { AberturaSerie.Marca(it.inicioMs, it.fimMs) }?.takeIf { AberturaSerie.valida(it) }

    /** Capítulo "Credits"/"Ending" do arquivo perto do fim (um "Credits" no começo é a abertura). */
    fun creditosDoCapitulo(caps: List<Capitulos.Capitulo>, duracaoMs: Long): Long? =
        caps.lastOrNull { CAP_CREDITOS.containsMatchIn(it.titulo?.trim().orEmpty()) }?.inicioMs?.takeIf { creditosValidos(it, duracaoMs) }

    /** O começo dos créditos que vale: o do IntroDB, senão o do capítulo; sempre conferido contra a duração real. */
    fun inicioCreditos(doBanco: Long?, caps: List<Capitulos.Capitulo>, duracaoMs: Long): Long? =
        doBanco?.takeIf { creditosValidos(it, duracaoMs) } ?: creditosDoCapitulo(caps, duracaoMs)

    fun creditosValidos(ini: Long, duracaoMs: Long): Boolean =
        duracaoMs > 0 && ini >= (duracaoMs * CREDITOS_DEPOIS_DE).toLong() && ini <= duracaoMs - CREDITOS_FOLGA_FIM_MS &&
            duracaoMs - ini <= CREDITOS_MAX_MS

    /** A pílula "Próximo episódio" vale nesta posição? */
    fun mostrarProximo(ini: Long?, posMs: Long, duracaoMs: Long): Boolean =
        ini != null && duracaoMs > 0 && posMs >= ini && posMs < duracaoMs - PROXIMO_FOLGA_FIM_MS

    /** Qual pílula de pular vale agora: o resumo vem antes da abertura quando os dois cobrem a posição. */
    fun pularAgora(resumo: AberturaSerie.Marca?, abertura: AberturaSerie.Marca?, posMs: Long): AberturaSerie.Marca? = when {
        AberturaSerie.mostrarPulo(resumo, posMs) -> resumo
        AberturaSerie.mostrarPulo(abertura, posMs) -> abertura
        else -> null
    }

    /** Hora (em ms do relógio) em que o vídeo acaba, na velocidade atual. Null sem duração. */
    fun terminaEm(agoraMs: Long, posMs: Long, duracaoMs: Long, velocidade: Float): Long? {
        if (duracaoMs <= 0) return null
        val resta = (duracaoMs - posMs).coerceAtLeast(0)
        return agoraMs + (resta / velocidade.coerceIn(0.25f, 4f)).toLong()
    }

    /** "O que ele disse?": para onde voltar. */
    fun repetirDe(posMs: Long): Long = (posMs - REPETIR_MS).coerceAtLeast(0)
}
