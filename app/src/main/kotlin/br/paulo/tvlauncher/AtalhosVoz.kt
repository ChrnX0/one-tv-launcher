package br.paulo.tvlauncher

/**
 * Tabela dos atalhos de voz (ideia 8, 14/11/2026): nome do activity-alias do manifesto -> o que ele faz.
 * Pura (sem Android) para o AtalhosVozTest conferir, lendo o AndroidManifest.xml, que cada alias que aponta para a
 * AtalhoVozActivity tem destino aqui (e vice-versa) e rótulo nos dois idiomas. Quem executa é a AtalhoVozActivity.
 */
object AtalhosVoz {
    enum class Destino { DESLIGAR_30, DESLIGAR_60, NAO_DESLIGAR, CENAS, DIAGNOSTICO, AGENDA, FILMES, SERIES, NA_REDE, CASA, BUSCA, MURAL }

    val TABELA: Map<String, Destino> = mapOf(
        "VozDesligar30" to Destino.DESLIGAR_30,
        "VozDesligar60" to Destino.DESLIGAR_60,
        "VozNaoDesligar" to Destino.NAO_DESLIGAR,
        "VozCenas" to Destino.CENAS,
        "VozDiagnostico" to Destino.DIAGNOSTICO,
        "VozAgenda" to Destino.AGENDA,
        "VozFilmes" to Destino.FILMES,
        "VozSeries" to Destino.SERIES,
        "VozNaRede" to Destino.NA_REDE,
        "VozCasa" to Destino.CASA,
        "VozBusca" to Destino.BUSCA,
        "VozMural" to Destino.MURAL,
    )

    /** [alias]: o nome curto do componente (sem pacote), ex. "VozFilmes". Desconhecido = null (não faz nada). */
    fun destino(alias: String): Destino? = TABELA[alias]
}
