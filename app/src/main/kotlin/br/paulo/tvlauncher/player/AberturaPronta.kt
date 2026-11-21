package br.paulo.tvlauncher.player

import org.json.JSONObject

/**
 * Abertura pulada sem ninguém marcar (elevação 6.3, onda 1, 17/11/2026): as REGRAS (AberturaProntaTest). A parte de
 * rede é o AberturaProntaTv.
 *
 * Fonte: IntroDB (introdb.app), banco comunitário de marcações de abertura, leitura sem chave e sem conta. Termos lidos
 * em 17/11/2026 (https://introdb.app/docs/terms): "Commercial apps with reasonable usage" é PERMITIDO; proibido varrer a
 * API em massa e passar do limite; atribuição "appreciated but not required" pela API (o crédito vai nas Licenças).
 * Pedimos UM episódio por vez, só quando a pessoa abre o episódio, e guardamos a resposta (inclusive "não tem").
 *
 * TheIntroDB (theintrodb.org) fica de fora por ora: o FAQ diz que uso comercial precisa de licença
 * (commercial@theintrodb.org) e a API pede chave. Entra quando o Paulo tiver a resposta deles.
 *
 * A marcação manual (AberturaSerie) continua de reserva: sem marcação do banco, vale a da pessoa.
 */
object AberturaPronta {
    const val URL = "https://api.introdb.app/segments"
    /** Abaixo disso a marcação é palpite de pouca gente: melhor não pular errado. */
    const val CONFIANCA_MIN = 0.6
    /** "Não tem marcação" é perguntado de novo depois de uma semana (alguém pode ter marcado). */
    const val REPERGUNTAR_MS = 7L * 24 * 3600 * 1000

    fun url(imdb: String, temporada: Int, episodio: Int): String? {
        if (!InfoExterna.idImdbValido(imdb) || temporada < 0 || episodio <= 0) return null
        return "$URL?imdb_id=$imdb&season=$temporada&episode=$episodio"
    }

    /** O trecho da abertura na resposta, ou null (sem marcação, pouca confiança, ou "este episódio não tem abertura"). */
    fun ler(json: String?): AberturaSerie.Marca? {
        return try {
            val o = JSONObject(json ?: return null)
            if (o.optBoolean("no_intro")) return null
            val i = o.optJSONObject("intro") ?: return null
            if (i.optDouble("confidence", 1.0) < CONFIANCA_MIN) return null
            val ini = if (i.has("start_ms")) i.getLong("start_ms") else (i.getDouble("start_sec") * 1000).toLong()
            val fim = if (i.has("end_ms")) i.getLong("end_ms") else (i.getDouble("end_sec") * 1000).toLong()
            AberturaSerie.Marca(ini, fim).takeIf { AberturaSerie.valida(it) }
        } catch (_: Exception) { null }
    }

    /** Guardado: "ini-fim" ou "-" (não tem), com a hora; null = perguntar ao banco. */
    fun doCache(texto: String?, agora: Long): Pair<AberturaSerie.Marca?, Boolean>? {
        val p = texto?.split('|') ?: return null
        val em = p.getOrNull(1)?.toLongOrNull() ?: return null
        if (p[0] == "-") return if (agora - em < REPERGUNTAR_MS) null to true else null
        val n = p[0].split('-').mapNotNull { it.toLongOrNull() }
        if (n.size != 2) return null
        return AberturaSerie.Marca(n[0], n[1]).takeIf { AberturaSerie.valida(it) }?.let { it to true }
    }

    fun paraCache(m: AberturaSerie.Marca?, agora: Long) = (if (m == null) "-" else "${m.ini}-${m.fim}") + "|$agora"
}
