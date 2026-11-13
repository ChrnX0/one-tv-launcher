package br.paulo.tvlauncher

/** Leitura pura do METAR (sem rede nem Android), para ter teste JVM. */
object Metar {
    /**
     * Código WMO do tempo presente no campo wxString do METAR (ex.: "-RA" chuva fraca, "SHRA" pancada, "TSRA" trovoada),
     * ou null se nada acontece NA estação. Grupos que começam com "VC" ("vicinity": a 8-16 km, não em cima da estação)
     * ficam de fora: antes "VCSH" virava "chovendo agora" e o AvisoChuva deixava de avisar (auditoria 10/11/2026, home-4).
     */
    fun fenomeno(wx: String): Int? {
        val aqui = wx.split(' ').filter { it.isNotBlank() && !it.startsWith("VC") }.joinToString(" ")
        return when {
            "TS" in aqui -> 95
            "SH" in aqui -> 80
            "RA" in aqui -> 61
            "DZ" in aqui -> 51
            "FG" in aqui || "BR" in aqui -> 45
            else -> null
        }
    }
}
