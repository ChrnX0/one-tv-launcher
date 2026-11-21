package br.paulo.tvlauncher.screensaver

import br.paulo.tvlauncher.R

/**
 * As fotos do fundo "A Terra vista do espaço" (protetor v2, 17/11/2026): tiradas pelos astronautas da Estação Espacial,
 * da NASA (images.nasa.gov). Sem direito autoral nos EUA; a NASA pede o crédito e que não se sugira apoio dela
 * (assets/licencas.txt). Ficam no APK (assets/terra, 1600 px, ~3,7 MB): o protetor funciona sem internet.
 * A foto acompanha a hora e o tempo: luzes das cidades e auroras à noite, o nascer do sol na órbita perto do nascer e do
 * pôr daqui, mar e desertos de dia, nuvens e tempestades quando está nublado ou chovendo. Lógica pura (FotosTerraTest).
 */
object FotosTerra {
    enum class Grupo { NOITE, CREPUSCULO, DIA, NUVENS }

    /** [id] = nome do arquivo em assets/terra (o número da foto na NASA); [titulo] = legenda na língua da TV. */
    class Foto(val id: String, val grupo: Grupo, val titulo: Int)

    val TODAS = listOf(
        Foto("iss071e439624", Grupo.CREPUSCULO, R.string.terra_nascer_orbita),
        Foto("iss072e487315", Grupo.CREPUSCULO, R.string.terra_nascer_namibia),
        Foto("iss070e090453", Grupo.CREPUSCULO, R.string.terra_noctilucentes),
        Foto("iss066e146457", Grupo.NOITE, R.string.terra_aurora_indico),
        Foto("iss030e119777", Grupo.NOITE, R.string.terra_aurora),
        Foto("iss062e103874", Grupo.NOITE, R.string.terra_luzes_europa_asia),
        Foto("iss040e089021", Grupo.NOITE, R.string.terra_costa_eua),
        Foto("iss035e017619", Grupo.NOITE, R.string.terra_brilho_ar),
        Foto("iss071e449837", Grupo.DIA, R.string.terra_bahamas),
        Foto("iss058e006004", Grupo.DIA, R.string.terra_andes),
        Foto("iss066e137392", Grupo.DIA, R.string.terra_saara),
        Foto("iss066e108216", Grupo.DIA, R.string.terra_bahamas_areia),
        Foto("iss040e092688", Grupo.DIA, R.string.terra_lua_pondo),
        Foto("iss074e0573450", Grupo.NUVENS, R.string.terra_tempestade),
        Foto("iss071e378517", Grupo.NUVENS, R.string.terra_kilimanjaro),
        Foto("iss040e006883", Grupo.NUVENS, R.string.terra_buenos_aires),
    )

    /** Quanto antes e depois do nascer e do pôr do sol ainda vale a foto de "crepúsculo". */
    const val CREPUSCULO_MS = 40 * 60_000L

    /**
     * O grupo da hora. [nascerPor] = nascer e pôr de hoje (null = região sem nascer/pôr hoje: vale o relógio, 6h-18h).
     * [nublado] = código do céu de nublado para cima, ou chovendo: só muda o grupo de DIA (à noite as nuvens não aparecem).
     */
    fun grupo(agora: Long, nascerPor: Pair<Long, Long>?, nublado: Boolean, horaLocal: Int): Grupo {
        val dia: Boolean
        if (nascerPor != null) {
            val (nasce, poe) = nascerPor
            if (kotlin.math.abs(agora - nasce) <= CREPUSCULO_MS || kotlin.math.abs(agora - poe) <= CREPUSCULO_MS) return Grupo.CREPUSCULO
            dia = agora in nasce..poe
        } else dia = horaLocal in 6..17
        return if (!dia) Grupo.NOITE else if (nublado) Grupo.NUVENS else Grupo.DIA
    }

    /** Céu de nublado para cima (código WMO 3 = encoberto; 45+ = névoa, garoa, chuva, neve, trovoada) ou chovendo. */
    fun nublado(codigo: Int, chovendo: Boolean): Boolean = chovendo || codigo >= 3

    /** A próxima foto do grupo depois de [anterior] (em volta). [anterior] fora do grupo = a primeira. */
    fun proxima(grupo: Grupo, anterior: String?): Foto {
        val l = TODAS.filter { it.grupo == grupo }
        val i = l.indexOfFirst { it.id == anterior }
        return l[(i + 1) % l.size]
    }
}
