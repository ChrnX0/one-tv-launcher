package br.paulo.tvlauncher.weather

import java.util.Calendar
import java.util.TimeZone
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * O céu da noite, calculado na TV sem internet (protetor que reage ao tempo, caminho 2, 17/11/2026): planetas que dá
 * para ver, distância da Lua (superlua), chuvas de meteoros e as passagens visíveis da Estação Espacial (com o TLE).
 * Puro (sem Android), testado contra as bibliotecas de referência (PyEphem e sgp4) em [CeuNoturnoTest].
 *
 * Precisão de propósito "de almanaque": planetas a ~1° (elementos keplerianos aproximados do JPL, válidos de 1800 a
 * 2050), Estação a ~1 min no horário da passagem (o TLE envelhece; por isso é renovado a cada 12 h).
 */
object CeuNoturno {
    private const val RAD = PI / 180

    // ------------------------------------------------------------------ direção (8 pontos da rosa dos ventos)

    /** 0 = norte, 1 = nordeste, 2 = leste ... 7 = noroeste, a partir do azimute em graus (0 = norte, 90 = leste). */
    fun rumo(azimuteGraus: Double): Int = (((azimuteGraus % 360 + 360) % 360 + 22.5) / 45).toInt() % 8

    /** Altura e azimute (graus) de um ponto de ascensão reta/declinação (rad) visto de [lat]/[lon] no instante [ms]. */
    fun alturaAzimute(ra: Double, dec: Double, ms: Long, lat: Double, lon: Double): Pair<Double, Double> {
        val h = Sgp4.gmst(ms) + lon * RAD - ra
        val la = lat * RAD
        val alt = asin(sin(la) * sin(dec) + cos(la) * cos(dec) * cos(h))
        val az = atan2(-cos(dec) * sin(h), sin(dec) * cos(la) - cos(dec) * sin(la) * cos(h))
        return alt / RAD to ((az / RAD) % 360 + 360) % 360
    }

    // ------------------------------------------------------------------ planetas

    /** Os planetas fáceis de ver a olho nu (Mercúrio fica de fora: sempre baixo, no clarão do sol). */
    enum class Planeta { VENUS, MARTE, JUPITER, SATURNO }

    /** Elementos de [Planeta] e da Terra: a, e, I, L, ϖ, Ω em J2000 e a variação por século (JPL, tabela 1). */
    private class Elementos(val a: Double, val e: Double, val i: Double, val l: Double, val w: Double, val o: Double,
        val da: Double, val de: Double, val di: Double, val dl: Double, val dw: Double, val dO: Double)

    private val TERRA = Elementos(1.00000261, 0.01671123, -0.00001531, 100.46457166, 102.93768193, 0.0,
        0.00000562, -0.00004392, -0.01294668, 35999.37244981, 0.32327364, 0.0)
    private val ELEMENTOS = mapOf(
        Planeta.VENUS to Elementos(0.72333566, 0.00677672, 3.39467605, 181.97909950, 131.60246718, 76.67984255,
            0.00000390, -0.00004107, -0.00078890, 58517.81538729, 0.00268329, -0.27769418),
        Planeta.MARTE to Elementos(1.52371034, 0.09339410, 1.84969142, -4.55343205, -23.94362959, 49.55953891,
            0.00001847, 0.00007882, -0.00813131, 19140.30268499, 0.44441088, -0.29257343),
        Planeta.JUPITER to Elementos(5.20288700, 0.04838624, 1.30439695, 34.39644051, 14.72847983, 100.47390909,
            -0.00011607, -0.00013253, -0.00183714, 3034.74612775, 0.21252668, 0.20469106),
        Planeta.SATURNO to Elementos(9.53667594, 0.05386179, 2.48599187, 49.95424423, 92.59887831, 113.66242448,
            -0.00125060, -0.00050991, 0.00193609, 1222.49362201, -0.41897216, -0.28867794),
    )

    /** Posição heliocêntrica na eclíptica J2000 (UA). */
    private fun helio(el: Elementos, ms: Long): DoubleArray {
        val t = (ms / 86_400_000.0 + 2440587.5 - 2451545.0) / 36525.0
        val a = el.a + el.da * t; val e = el.e + el.de * t; val i = (el.i + el.di * t) * RAD
        val l = el.l + el.dl * t; val w = el.w + el.dw * t; val o = el.o + el.dO * t
        val argPeri = (w - o) * RAD
        var m = ((l - w) % 360) * RAD
        if (m > PI) m -= 2 * PI; if (m < -PI) m += 2 * PI
        var ea = m + e * sin(m)
        repeat(8) { ea -= (ea - e * sin(ea) - m) / (1 - e * cos(ea)) }
        val xp = a * (cos(ea) - e); val yp = a * sqrt(1 - e * e) * sin(ea)
        val co = cos(o * RAD); val so = sin(o * RAD); val cw = cos(argPeri); val sw = sin(argPeri); val ci = cos(i); val si = sin(i)
        return doubleArrayOf(
            (cw * co - sw * so * ci) * xp + (-sw * co - cw * so * ci) * yp,
            (cw * so + sw * co * ci) * xp + (-sw * so + cw * co * ci) * yp,
            (sw * si) * xp + (cw * si) * yp)
    }

    /** Ascensão reta e declinação (rad) do planeta visto da Terra, e a distância (UA). */
    fun radec(p: Planeta, ms: Long): Triple<Double, Double, Double> {
        val pl = helio(ELEMENTOS.getValue(p), ms); val te = helio(TERRA, ms)
        val x = pl[0] - te[0]; val y = pl[1] - te[1]; val z = pl[2] - te[2]
        val eps = 23.43928 * RAD   // eclíptica -> equador
        val ye = y * cos(eps) - z * sin(eps); val ze = y * sin(eps) + z * cos(eps)
        val d = sqrt(x * x + ye * ye + ze * ze)
        return Triple(((atan2(ye, x)) + 2 * PI) % (2 * PI), asin(ze / d), d)
    }

    /** Um planeta alto no céu escuro: quando olhar, para que lado (0..7, ver [rumo]) e a que altura (graus). */
    data class PlanetaVisivel(val planeta: Planeta, val ms: Long, val rumo: Int, val altura: Int)

    /**
     * Os planetas que valem a pena olhar na próxima noite a partir de [agora]: testa de 15 em 15 min, entre o fim do
     * crepúsculo (sol abaixo de -8°) e meia-noite (ninguém idoso fica esperando de madrugada); se a noite já passou da
     * meia-noite, olha até o começo do crepúsculo da manhã. Conta o instante em que cada planeta está mais alto, desde
     * que passe de [alturaMin] graus. Mais brilhantes primeiro (Vênus, Júpiter, Marte, Saturno).
     */
    fun planetas(agora: Long, lat: Double, lon: Double, fuso: TimeZone = TimeZone.getDefault(), alturaMin: Double = 20.0): List<PlanetaVisivel> {
        val (inicio, fim) = janelaDaNoite(agora, lat, lon, fuso) ?: return emptyList()
        return Planeta.values().mapNotNull { p ->
            var melhor: PlanetaVisivel? = null
            var t = inicio
            while (t <= fim) {
                val (ra, dec, _) = radec(p, t)
                val (alt, az) = alturaAzimute(ra, dec, t, lat, lon)
                if (alt >= alturaMin && (melhor == null || alt > melhor.altura)) melhor = PlanetaVisivel(p, t, rumo(az), alt.roundToInt())
                t += 15 * 60_000L
            }
            melhor
        }
    }

    /**
     * A parte escura e "acordada" da noite: do fim do crepúsculo até a meia-noite local (ou, depois da meia-noite, de
     * agora até o começo do crepúsculo da manhã). Nunca começa antes de [agora]. null perto dos polos (sem noite).
     */
    fun janelaDaNoite(agora: Long, lat: Double, lon: Double, fuso: TimeZone = TimeZone.getDefault()): Pair<Long, Long>? {
        val c = Calendar.getInstance(fuso).apply { timeInMillis = agora }
        val hora = c.get(Calendar.HOUR_OF_DAY)
        val meiaNoite = Calendar.getInstance(fuso).apply {
            timeInMillis = agora; set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        fun escuro(ms: Long) = Astronomia.alturaSol(ms, lat, lon) < -8
        return if (hora < 5) {
            // Madrugada: de agora até o céu clarear.
            var fim = agora; while (fim < agora + 8 * 3_600_000L && escuro(fim + 600_000L)) fim += 600_000L
            if (escuro(agora) && fim > agora) agora to fim else null
        } else {
            val fim = meiaNoite + 24 * 3_600_000L
            var ini = maxOf(agora, meiaNoite + 12 * 3_600_000L)
            while (ini < fim && !escuro(ini)) ini += 600_000L
            if (ini < fim) ini to fim else null
        }
    }

    // ------------------------------------------------------------------ Lua

    /** Distância Terra-Lua (km), fórmula curta (erro de ~1.000 km; superlua tem folga de 20 mil). */
    fun distanciaLua(ms: Long): Double {
        val d = ms / 86_400_000.0 + 2440587.5 - 2451545.0
        val mLua = (134.963 + 13.064993 * d) * RAD   // anomalia média da Lua
        val dEl = (297.850 + 12.190749 * d) * RAD    // elongação média
        val mSol = (357.529 + 0.98560028 * d) * RAD
        return 385001 - 20905 * cos(mLua) - 3699 * cos(2 * dEl - mLua) - 2956 * cos(2 * dEl) - 570 * cos(2 * mLua) +
            246 * cos(2 * mLua - 2 * dEl) - 205 * cos(mSol - 2 * dEl) - 171 * cos(mLua + 2 * dEl)
    }

    /** Lua cheia "superlua": perto do perigeu (convenção usual: a menos de ~360 mil km). */
    const val SUPERLUA_KM = 360_000.0

    // ------------------------------------------------------------------ chuvas de meteoros

    /**
     * As chuvas de meteoros fortes do ano (IMO): pico (mês, dia), meteoros por hora no pico com céu perfeito (ZHR) e a
     * declinação do radiante (de onde parecem sair), que decide de qual hemisfério dá para ver.
     */
    enum class ChuvaMeteoros(val mes: Int, val dia: Int, val porHora: Int, val declinacao: Int) {
        QUADRANTIDAS(1, 3, 110, 49), LIRIDAS(4, 22, 18, 33), ETA_AQUARIDAS(5, 6, 50, -1), DELTA_AQUARIDAS(7, 30, 25, -16),
        PERSEIDAS(8, 12, 100, 58), ORIONIDAS(10, 21, 20, 16), LEONIDAS(11, 17, 15, 22), GEMINIDAS(12, 14, 150, 33),
    }

    /**
     * A chuva de meteoros que está no pico (de 2 dias antes a 1 dia depois) e que dá para ver de [lat]: o radiante tem
     * que subir pelo menos 25° acima do horizonte (90 - |lat - declinação|). null fora dos picos.
     */
    fun meteoros(agora: Long, lat: Double, fuso: TimeZone = TimeZone.getDefault()): ChuvaMeteoros? {
        val c = Calendar.getInstance(fuso).apply { timeInMillis = agora }
        val ano = c.get(Calendar.YEAR)
        return ChuvaMeteoros.values().firstOrNull { m ->
            val pico = Calendar.getInstance(fuso).apply { clear(); set(ano, m.mes - 1, m.dia, 12, 0) }.timeInMillis
            val dif = (agora - pico) / 86_400_000.0
            dif in -2.5..1.5 && 90 - abs(lat - m.declinacao) >= 25
        }
    }

    // ------------------------------------------------------------------ Estação Espacial

    /** Uma passagem visível: começo e fim (acima de 10°), de onde vem e para onde vai (0..7) e a altura máxima. */
    data class Passagem(val inicio: Long, val fim: Long, val deRumo: Int, val paraRumo: Int, val alturaMax: Int) {
        val minutos: Int get() = ((fim - inicio) / 60_000.0).roundToInt().coerceAtLeast(1)
    }

    /**
     * A próxima passagem VISÍVEL da Estação nas próximas [horas]: ela acima de 10°, iluminada pelo sol, e o céu do lugar
     * escuro (sol abaixo de -6°). Só conta se subir a pelo menos [alturaMin] graus em algum momento (passagem rasante
     * some atrás de prédio e árvore). Passo de 20 s; o começo e o fim ficam com essa precisão.
     */
    fun proximaPassagem(sat: Sgp4, agora: Long, lat: Double, lon: Double, horas: Int = 24, alturaMin: Double = 25.0): Passagem? {
        val obs = observadorEcef(lat, lon)
        val passo = 20_000L
        var t = agora
        var inicio = -1L; var fim = -1L; var azIni = 0.0; var azFim = 0.0; var maxAlt = -90.0
        while (t <= agora + horas * 3_600_000L) {
            val (alt, az) = alturaAzimuteSat(sat, t, obs, lat, lon)
            val vista = alt >= 10 && iluminado(sat.posicao(t), t) && Astronomia.alturaSol(t, lat, lon) < -6
            if (vista) {
                if (inicio < 0) { inicio = t; azIni = az; maxAlt = alt }
                fim = t; azFim = az; if (alt > maxAlt) maxAlt = alt
            } else if (inicio >= 0) {
                if (maxAlt >= alturaMin) return Passagem(inicio, fim, rumo(azIni), rumo(azFim), maxAlt.roundToInt())
                inicio = -1; maxAlt = -90.0
            }
            // Longe do horizonte (abaixo de -20°): pula de minuto em minuto, a passagem inteira leva ~6 min.
            t += if (alt < -20 && inicio < 0) 60_000L else passo
        }
        return null
    }

    private const val RE_WGS84 = 6378.137
    private const val F_WGS84 = 1 / 298.257223563

    private fun observadorEcef(lat: Double, lon: Double): DoubleArray {
        val la = lat * RAD; val lo = lon * RAD
        val e2 = F_WGS84 * (2 - F_WGS84)
        val n = RE_WGS84 / sqrt(1 - e2 * sin(la) * sin(la))
        return doubleArrayOf(n * cos(la) * cos(lo), n * cos(la) * sin(lo), n * (1 - e2) * sin(la))
    }

    /** Altura e azimute (graus) da Estação vista do observador. TEME -> Terra pela hora sideral (sem movimento do polo). */
    fun alturaAzimuteSat(sat: Sgp4, ms: Long, obs: DoubleArray, lat: Double, lon: Double): Pair<Double, Double> {
        val r = sat.posicao(ms)
        val g = Sgp4.gmst(ms)
        val x = cos(g) * r[0] + sin(g) * r[1]; val y = -sin(g) * r[0] + cos(g) * r[1]; val z = r[2]
        val dx = x - obs[0]; val dy = y - obs[1]; val dz = z - obs[2]
        val la = lat * RAD; val lo = lon * RAD
        val leste = -sin(lo) * dx + cos(lo) * dy
        val norte = -sin(la) * cos(lo) * dx - sin(la) * sin(lo) * dy + cos(la) * dz
        val cima = cos(la) * cos(lo) * dx + cos(la) * sin(lo) * dy + sin(la) * dz
        val alt = atan2(cima, sqrt(leste * leste + norte * norte)) / RAD
        val az = ((atan2(leste, norte) / RAD) + 360) % 360
        return alt to az
    }

    /** A Estação está no sol? Sombra da Terra como cilindro (basta para "dá para ver brilhando"). */
    fun iluminado(r: DoubleArray, ms: Long): Boolean {
        val s = direcaoSol(ms)
        val proj = r[0] * s[0] + r[1] * s[1] + r[2] * s[2]
        if (proj > 0) return true
        val px = r[0] - proj * s[0]; val py = r[1] - proj * s[1]; val pz = r[2] - proj * s[2]
        return sqrt(px * px + py * py + pz * pz) > RE_WGS84
    }

    /** Vetor unitário Terra -> Sol no referencial equatorial (mesmas fórmulas da [Astronomia]). */
    private fun direcaoSol(ms: Long): DoubleArray {
        val d = ms / 86_400_000.0 + 2440587.5 - 2451545.0
        val g = (357.529 + 0.98560028 * d) * RAD
        val q = 280.459 + 0.98564736 * d
        val l = (q + 1.915 * sin(g) + 0.020 * sin(2 * g)) * RAD
        val e = (23.439 - 0.00000036 * d) * RAD
        return doubleArrayOf(cos(l), cos(e) * sin(l), sin(e) * sin(l))
    }
}
