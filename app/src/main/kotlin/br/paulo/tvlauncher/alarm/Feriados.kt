package br.paulo.tvlauncher.alarm

import org.json.JSONObject

/**
 * Feriados nacionais do país onde a TV está (pedido do Paulo, 18/11/2026: "dia útil" das automações não vale em feriado,
 * com os feriados do país do aparelho, não só os do Brasil). Lógica pura; o Android fica em [FeriadosTv].
 *
 * A tabela vem pronta no APK (assets/feriados/feriados.json, gerada por scripts/gerar-feriados.py a partir da
 * python-holidays, MIT): nada sai da TV para saber se hoje é feriado. Formato de cada país: "2026:01010403...;2027:...",
 * as datas MMDD coladas por ano.
 */
object Feriados {
    /** As datas (MMDD) de [ano] na linha de um país, ou null se a tabela não cobre esse ano. */
    fun doAno(linha: String, ano: Int): Set<String>? {
        val prefixo = "$ano:"
        val parte = linha.split(';').firstOrNull { it.startsWith(prefixo) } ?: return null
        val datas = parte.removePrefix(prefixo)
        if (datas.length % 4 != 0) return null
        return datas.chunked(4).toSet()
    }

    /** [mes] de 1 a 12. null = não sei (país ou ano fora da tabela): quem chama trata como dia comum. */
    fun eFeriado(linha: String?, ano: Int, mes: Int, dia: Int): Boolean? {
        val datas = linha?.let { doAno(it, ano) } ?: return null
        // Locale.ROOT: em árabe/persa o format escrevia dígitos locais e nenhum feriado batia (debug 20/11/2026).
        return String.format(java.util.Locale.ROOT, "%02d%02d", mes, dia) in datas
    }

    /** A linha do [pais] (ISO 3166 de 2 letras) no JSON da tabela, ou null. */
    fun linhaDoPais(json: String, pais: String?): String? {
        val p = pais?.uppercase()?.takeIf { it.length == 2 && it.all { c -> c in 'A'..'Z' } } ?: return null
        return runCatching { JSONObject(json).optString(p).ifEmpty { null } }.getOrNull()
    }

    /**
     * O país da TV: a região do fuso (o Google TV acerta o fuso pela rede, então ele diz onde a TV está) e, se o fuso
     * não diz (UTC, "001"), a região do idioma do sistema.
     */
    fun pais(regiaoDoFuso: String?, regiaoDoIdioma: String?): String? =
        listOf(regiaoDoFuso, regiaoDoIdioma).firstOrNull { it != null && it.length == 2 && it.all { c -> c.isLetter() } }?.uppercase()
}
