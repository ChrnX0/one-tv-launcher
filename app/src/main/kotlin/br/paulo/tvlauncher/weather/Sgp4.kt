package br.paulo.tvlauncher.weather

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Órbita de um satélite perto da Terra (a Estação Espacial) a partir do TLE, pelo modelo SGP4 (o padrão do NORAD; mesmo
 * algoritmo do "sgp4unit" de Vallado 2006, constantes WGS-72, só a parte "perto da Terra": período < 225 min, que é o
 * caso da Estação). Sem velocidade: o protetor só precisa da posição. Puro (sem Android), testado contra a biblioteca
 * de referência em [Sgp4Test].
 *
 * Por que calcular na TV: a passagem visível depende do lugar exato; um serviço externo saberia onde a pessoa mora.
 * Só o TLE (dado público do NORAD, via CelesTrak) vem da internet, uma vez a cada 12 h.
 */
class Sgp4(linha1: String, linha2: String) {
    /** Instante do TLE em ms (UTC). */
    val epocaMs: Long

    private val no: Double; private val ecco: Double; private val inclo: Double; private val nodeo: Double
    private val argpo: Double; private val mo: Double; private val bstar: Double
    private var isimp = false
    private val aycof: Double; private val con41: Double; private val cc1: Double; private val cc4: Double; private val cc5: Double
    private var d2 = 0.0; private var d3 = 0.0; private var d4 = 0.0
    private val delmo: Double; private val eta: Double; private val argpdot: Double; private val omgcof: Double
    private val sinmao: Double; private val t2cof: Double; private var t3cof = 0.0; private var t4cof = 0.0; private var t5cof = 0.0
    private val x1mth2: Double; private val x7thm1: Double; private val mdot: Double; private val nodedot: Double
    private val xlcof: Double; private val xmcof: Double; private val nodecf: Double

    init {
        require(linha1.length >= 63 && linha2.length >= 63) { "TLE curto" }
        // Linha 1: época (ano de 2 dígitos + dia do ano com fração) e o B* ("arrasto"), no formato " 12345-3" = 0.12345e-3.
        val ano2 = linha1.substring(18, 20).trim().toInt()
        val dias = linha1.substring(20, 32).trim().toDouble()
        epocaMs = msDoAno(if (ano2 < 57) 2000 + ano2 else 1900 + ano2) + ((dias - 1) * 86_400_000.0).toLong()
        bstar = expoente(linha1.substring(53, 61))
        // Linha 2: inclinação, nodo, excentricidade (decimal implícito), argumento do perigeu, anomalia média, revoluções/dia.
        inclo = linha2.substring(8, 16).trim().toDouble() * RAD
        nodeo = linha2.substring(17, 25).trim().toDouble() * RAD
        ecco = ("0." + linha2.substring(26, 33).trim()).toDouble()
        argpo = linha2.substring(34, 42).trim().toDouble() * RAD
        mo = linha2.substring(43, 51).trim().toDouble() * RAD
        val noKozai = linha2.substring(52, 63).trim().toDouble() * 2 * PI / 1440.0   // rad/min

        // ---- inicialização (sgp4init / initl), WGS-72
        val eccsq = ecco * ecco; val omeosq = 1 - eccsq; val rteosq = sqrt(omeosq)
        val cosio = cos(inclo); val cosio2 = cosio * cosio
        val ak = (XKE / noKozai).pow(X2O3)
        val d1 = 0.75 * J2 * (3 * cosio2 - 1) / (rteosq * omeosq)
        var del = d1 / (ak * ak)
        val adel = ak * (1 - del * del - del * (1.0 / 3 + 134 * del * del / 81))
        del = d1 / (adel * adel)
        no = noKozai / (1 + del)
        val ao = (XKE / no).pow(X2O3)
        val sinio = sin(inclo)
        val po = ao * omeosq
        val con42 = 1 - 5 * cosio2
        con41 = -con42 - cosio2 - cosio2
        val posq = po * po
        val rp = ao * (1 - ecco)
        require(rp > 1.0 && 2 * PI / no < 225.0) { "órbita fora do modelo perto da Terra" }

        if (rp < 220 / RT + 1) isimp = true
        var sfour = 78 / RT + 1
        var qzms24 = ((120 - 78) / RT).pow(4)
        val perige = (rp - 1) * RT
        if (perige < 156) {
            sfour = if (perige < 98) 20.0 else perige - 78
            qzms24 = ((120 - sfour) / RT).pow(4)
            sfour = sfour / RT + 1
        }
        val pinvsq = 1 / posq
        val tsi = 1 / (ao - sfour)
        eta = ao * ecco * tsi
        val etasq = eta * eta; val eeta = ecco * eta
        val psisq = abs(1 - etasq)
        val coef = qzms24 * tsi.pow(4); val coef1 = coef / psisq.pow(3.5)
        val cc2 = coef1 * no * (ao * (1 + 1.5 * etasq + eeta * (4 + etasq)) +
            0.375 * J2 * tsi / psisq * con41 * (8 + 3 * etasq * (8 + etasq)))
        cc1 = bstar * cc2
        val cc3 = if (ecco > 1.0e-4) -2 * coef * tsi * J3OJ2 * no * sinio / ecco else 0.0
        x1mth2 = 1 - cosio2
        cc4 = 2 * no * coef1 * ao * omeosq * (eta * (2 + 0.5 * etasq) + ecco * (0.5 + 2 * etasq) -
            J2 * tsi / (ao * psisq) * (-3 * con41 * (1 - 2 * eeta + etasq * (1.5 - 0.5 * eeta)) +
                0.75 * x1mth2 * (2 * etasq - eeta * (1 + etasq)) * cos(2 * argpo)))
        cc5 = 2 * coef1 * ao * omeosq * (1 + 2.75 * (etasq + eeta) + eeta * etasq)
        val cosio4 = cosio2 * cosio2
        val temp1 = 1.5 * J2 * pinvsq * no
        val temp2 = 0.5 * temp1 * J2 * pinvsq
        val temp3 = -0.46875 * J4 * pinvsq * pinvsq * no
        mdot = no + 0.5 * temp1 * rteosq * con41 + 0.0625 * temp2 * rteosq * (13 - 78 * cosio2 + 137 * cosio4)
        argpdot = -0.5 * temp1 * con42 + 0.0625 * temp2 * (7 - 114 * cosio2 + 395 * cosio4) + temp3 * (3 - 36 * cosio2 + 49 * cosio4)
        val xhdot1 = -temp1 * cosio
        nodedot = xhdot1 + (0.5 * temp2 * (4 - 19 * cosio2) + 2 * temp3 * (3 - 7 * cosio2)) * cosio
        omgcof = bstar * cc3 * cos(argpo)
        xmcof = if (ecco > 1.0e-4) -X2O3 * coef * bstar / eeta else 0.0
        nodecf = 3.5 * omeosq * xhdot1 * cc1
        t2cof = 1.5 * cc1
        xlcof = if (abs(cosio + 1) > 1.5e-12) -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / (1 + cosio)
            else -0.25 * J3OJ2 * sinio * (3 + 5 * cosio) / 1.5e-12
        aycof = -0.5 * J3OJ2 * sinio
        delmo = (1 + eta * cos(mo)).pow(3)
        sinmao = sin(mo)
        x7thm1 = 7 * cosio2 - 1
        if (!isimp) {
            val cc1sq = cc1 * cc1
            d2 = 4 * ao * tsi * cc1sq
            val temp = d2 * tsi * cc1 / 3
            d3 = (17 * ao + sfour) * temp
            d4 = 0.5 * temp * ao * tsi * (221 * ao + 31 * sfour) * cc1
            t3cof = d2 + 2 * cc1sq
            t4cof = 0.25 * (3 * d3 + cc1 * (12 * d2 + 10 * cc1sq))
            t5cof = 0.2 * (3 * d4 + 12 * cc1 * d3 + 6 * d2 * d2 + 15 * cc1sq * (2 * d2 + cc1sq))
        }
    }

    /** Posição em km no referencial TEME (equador e equinócio "verdadeiros" da data) no instante [ms] (UTC). */
    fun posicao(ms: Long): DoubleArray {
        val t = (ms - epocaMs) / 60_000.0   // minutos desde a época
        val xmdf = mo + mdot * t
        val argpdf = argpo + argpdot * t
        val nodedf = nodeo + nodedot * t
        var argpm = argpdf; var mm = xmdf
        val t2 = t * t
        var nodem = nodedf + nodecf * t2
        var tempa = 1 - cc1 * t
        var tempe = bstar * cc4 * t
        var templ = t2cof * t2
        if (!isimp) {
            val delomg = omgcof * t
            val delm = xmcof * ((1 + eta * cos(xmdf)).pow(3) - delmo)
            val temp = delomg + delm
            mm = xmdf + temp; argpm = argpdf - temp
            val t3 = t2 * t; val t4 = t3 * t
            tempa = tempa - d2 * t2 - d3 * t3 - d4 * t4
            tempe += bstar * cc5 * (sin(mm) - sinmao)
            templ += t3cof * t3 + t4 * (t4cof + t * t5cof)
        }
        val am = (XKE / no).pow(X2O3) * tempa * tempa
        var em = ecco - tempe
        if (em < 1.0e-6) em = 1.0e-6
        mm += no * templ
        var xlm = mm + argpm + nodem
        nodem = mod2pi(nodem); argpm = mod2pi(argpm); xlm = mod2pi(xlm)
        mm = mod2pi(xlm - argpm - nodem)

        // ---- termos de período longo e Kepler
        val sinip = sin(inclo); val cosip = cos(inclo)
        val axnl = em * cos(argpm)
        var temp = 1 / (am * (1 - em * em))
        val aynl = em * sin(argpm) + temp * aycof
        val xl = mm + argpm + nodem + temp * xlcof * axnl
        val u = mod2pi(xl - nodem)
        var eo1 = u; var tem5 = 9999.9; var ktr = 1
        var sineo1 = 0.0; var coseo1 = 0.0
        while (abs(tem5) >= 1.0e-12 && ktr <= 10) {
            sineo1 = sin(eo1); coseo1 = cos(eo1)
            tem5 = 1 - coseo1 * axnl - sineo1 * aynl
            tem5 = (u - aynl * coseo1 + axnl * sineo1 - eo1) / tem5
            if (abs(tem5) >= 0.95) tem5 = if (tem5 > 0) 0.95 else -0.95
            eo1 += tem5; ktr++
        }
        // ---- termos de período curto
        val ecose = axnl * coseo1 + aynl * sineo1
        val esine = axnl * sineo1 - aynl * coseo1
        val el2 = axnl * axnl + aynl * aynl
        val pl = am * (1 - el2)
        val rl = am * (1 - ecose)
        val betal = sqrt(1 - el2)
        temp = esine / (1 + betal)
        val sinu = am / rl * (sineo1 - aynl - axnl * temp)
        val cosu = am / rl * (coseo1 - axnl + aynl * temp)
        var su = atan2(sinu, cosu)
        val sin2u = (cosu + cosu) * sinu
        val cos2u = 1 - 2 * sinu * sinu
        temp = 1 / pl
        val temp1 = 0.5 * J2 * temp
        val temp2 = temp1 * temp
        val mrt = rl * (1 - 1.5 * temp2 * betal * con41) + 0.5 * temp1 * x1mth2 * cos2u
        su -= 0.25 * temp2 * x7thm1 * sin2u
        val xnode = nodem + 1.5 * temp2 * cosip * sin2u
        val xinc = inclo + 1.5 * temp2 * cosip * sinip * cos2u
        val sinsu = sin(su); val cossu = cos(su); val snod = sin(xnode); val cnod = cos(xnode)
        val sini = sin(xinc); val cosi = cos(xinc)
        val xmx = -snod * cosi; val xmy = cnod * cosi
        val ux = xmx * sinsu + cnod * cossu
        val uy = xmy * sinsu + snod * cossu
        val uz = sini * sinsu
        val r = mrt * RT
        return doubleArrayOf(r * ux, r * uy, r * uz)
    }

    companion object {
        const val RT = 6378.135                 // raio da Terra (km), WGS-72
        private const val MU = 398600.8
        private val XKE = 60.0 / sqrt(RT * RT * RT / MU)
        private const val J2 = 0.001082616
        private const val J3 = -0.00000253881
        private const val J4 = -0.00000165597
        private const val J3OJ2 = J3 / J2
        private const val X2O3 = 2.0 / 3.0
        private const val RAD = PI / 180

        private fun mod2pi(x: Double): Double { val r = x % (2 * PI); return if (r < 0) r + 2 * PI else r }

        /** " 12345-3" -> 0.12345e-3 (formato do TLE, sem o ponto e com o expoente colado). */
        internal fun expoente(campo: String): Double {
            val s = campo.trim()
            if (s.isEmpty()) return 0.0
            val sinal = if (s.startsWith("-")) -1.0 else 1.0
            val corpo = s.trimStart('-', '+')
            val i = corpo.indexOfAny(charArrayOf('-', '+'), 1)
            if (i < 0) return sinal * ("0.$corpo").toDouble()
            return sinal * ("0." + corpo.substring(0, i)).toDouble() * 10.0.pow(corpo.substring(i).toInt())
        }

        /** 1º de janeiro do [ano], 00:00 UTC, em ms (calendário gregoriano). */
        internal fun msDoAno(ano: Int): Long {
            val y = ano - 1
            val diasAte = 365L * (ano - 1970) + (y / 4 - 1969 / 4) - (y / 100 - 1969 / 100) + (y / 400 - 1969 / 400)
            return diasAte * 86_400_000L
        }

        /** Hora sideral média de Greenwich (rad) no instante [ms] (UTC ~ UT1; erro de < 1 s não importa aqui). */
        fun gmst(ms: Long): Double {
            val jd = ms / 86_400_000.0 + 2440587.5
            val tut1 = (jd - 2451545.0) / 36525.0
            var s = -6.2e-6 * tut1 * tut1 * tut1 + 0.093104 * tut1 * tut1 + (876600.0 * 3600 + 8640184.812866) * tut1 + 67310.54841
            s = (s * RAD / 240.0) % (2 * PI)
            return if (s < 0) s + 2 * PI else s
        }

    }
}
